# Phase 0 — findings

Spike proving the dev-loop daemon's core mechanism, and measuring how often an
agent-style edit can be applied without a restart.

Target: Vaadin 25.2.6, Spring Boot 4.1, Java 25. Measured 2026-08-12.

## What was built

| Piece | Location | Role |
|---|---|---|
| Java agent | `devloop/agent-src`, built by `devloop/build-agent.sh` | Captures `Instrumentation` at startup; no other job. |
| Connector | `src/main/java/.../devloop/` | Calls `Hotswapper.register()`, observes the `VaadinHotswapper` SPI, serves a control socket. |
| Harness | `devloop/harness/DevLoopHarness.java` | Patches sources, compiles with `javax.tools.JavaCompiler`, drives the redefine, records outcomes, reverts. |

Run: build the agent, then

```
./mvnw -o spring-boot:run -Dspring-boot.run.jvmArguments="-javaagent:devloop/devloop-agent.jar -Dvaadin.launch-browser=false"
java devloop/harness/DevLoopHarness.java --label jdk25
```

## Exit criteria — met

A method-body change (`setHeader("Description")` → `"Task"`) went live in the
browser with no restart. Flow logged *"Triggering re-navigation to current
route for UIs affected by classes changes"*; the drawer and menu DOM refs stayed
identical while only the view subtree was replaced — an in-place route
re-render, not a page reload.

The plan's C2 and C3 both confirmed in practice:

- **C2 was real and load-bearing.** Nothing in Flow registers the `Hotswapper`.
  A plain `VaadinServiceInitListener` calling `Hotswapper.register(service)` is
  sufficient — no HotswapAgent, no bytecode injection.
- **C3 was real.** `onHotswapComplete` fires as an authoritative completion
  signal, so the transaction gate needs no invention.

## Escalation rate

24 realistic agent-style edits, same edits on both JVMs:

| JVM | Hot-reloaded | Escalates to restart | New class |
|---|---|---|---|
| OpenJDK 25.0.2 (stock) | **16/24 (67%)** | 7 | 1 |
| JBR 25.0.2 `-XX:+AllowEnhancedClassRedefinition` | **22/24 (92%)** | 1 | 1 |

What escalates on stock but not on JBR: adding a method, adding a field,
adding a lambda. Only `change-superclass` escalates on both.

Timings (median over successful redefines):

| JVM | `redefineClasses` | `onHotswap` | Cold restart |
|---|---|---|---|
| Stock JDK 25 | 20 ms (max 43) | 2 ms | ~10.6 s |
| JBR | 130 ms (max 213) | 18 ms | ~10.9 s |

JBR's enhanced redefinition costs ~6× more per call and is still ~70× cheaper
than a restart.

## The finding that changes the design

**`redefineClasses` returning without throwing does not mean the change is
live.**

A single binary class name can map to **more than one loaded `Class` object`**.
For the routed view `TaskListView`, a second copy appears after route
re-registration (`dupes=[TaskListViewx2]`). Redefining only one copy makes
`redefineClasses` report success while the app keeps instantiating the other —
the exact "green apply on a stale page" failure the transaction model exists to
prevent.

Observed, then fixed:

- Fresh JVM, first redefine → change live in the browser.
- Same edit later in the session → `OK redefined=1 completed=true`, class file
  on disk correct, **browser stale even on a fresh page load**.
- Another class (`MainLayout`) reloaded fine in the same JVM — the failure is
  class-scoped, which is what pointed at duplicate copies rather than JVM
  degradation.
- Fix: collect **every** loaded copy and pass them all to the one atomic
  `redefineClasses` call. The previously-stale edit then went live.

Consequences for the plan:

1. P3 cannot treat "no exception" as reaching `Stable`. It needs either a
   read-back verification or, at minimum, the all-copies redefine plus an
   explicit duplicate count in the apply result.
2. This class of bug is invisible without browser-level checking, so P0's
   browser verification should stay in the loop as a test, even though the
   daemon's own job ends at `Stable`.

## Refinement to C3

The public event API does **not** expose the strategy Flow computed.
`HotswapClassEvent.getUIUpdateStrategy(ui)` returned empty for every event in
every run (`strategies=[none]`) — it reports only a strategy a hotswapper
explicitly *requested* via `triggerUpdate`. The authoritative decision was
visible only in Flow's DEBUG log.

So the plan's `classification` field needs a Flow API addition or log scraping.
This should be settled with the Flow team rather than worked around.

## Caveats — what was not established

- The counts above measure **redefinability**, not semantic correctness. Four
  edits were verified in the browser end to end; the other twenty were only
  confirmed as "the JVM accepted the new bytecode".
- In particular `entity-add-field` counts as hot-reloaded on JBR at the
  bytecode level. That says nothing about Hibernate's metamodel or the H2
  schema, which certainly did not update. The Spring/JPA restart question
  (risk R1 in the plan) remains fully open and should be settled before P3.
- Adding a `@Transactional` method to an already-proxied Spring bean is
  similarly counted as hot-reloaded, though the new method would not be
  intercepted by the existing proxy.

---

# Phase 0.5 — what HotswapAgent actually covers

Run 2026-08-12 on JBR 25.0.2 with `-XX:+AllowEnhancedClassRedefinition`, using
`devloop/harness/P05Harness.java`. Both tests are deliberately multi-file, because that
is what an agent does: add a method and call it.

## Results

| Test | no HotswapAgent | HA, no `--add-opens` | HA + `--add-opens` |
|---|---|---|---|
| **Spring** — add a `@Transactional` method to a service, call it from the view | **breaks the app** | pass | pass |
| **JPA** — add a mapped `@Column` to the `@Entity`, show it in the grid | not run | **fail** | **fail** |

## Spring: HotswapAgent is genuinely required

Without HA the redefine reports `OK redefined=2 completed=true` and the view then fails to
instantiate at all:

```
NullPointerException: Cannot invoke "TaskRepository.count()" because "this.taskRepository" is null
  at TaskService.countTasks(TaskService.java:30)
  at TaskListView.<init>(TaskListView.java:55)
```

The mechanism: `TaskService` is `@Transactional`, so the injected bean is a CGLIB subclass
proxy created before the redefine. The proxy class has no override for the new method, so
the call is not intercepted and never delegates to the real target — it executes on the
proxy instance, which was built via Objenesis without running a constructor and therefore
has null fields.

Two consequences, both important:

1. **This is worse than a failed reload — it is a corrupted runtime.** The daemon would
   report `Stable` on an app whose view now throws on every render. Escalating to a
   restart would have been strictly better. It is the C7 lesson again, from a new angle.
2. **With HA the identical edit works** (heading rendered `Task List [1]`), so HA's Spring
   plugin is doing real work: the requirement to ship HA is justified by evidence, not
   just by principle.

## JPA: entity mapping does not hot reload, with or without HA

`HibernateJakarta` is discovered but **never initializes** against Hibernate ORM 7.4.1, and
adding a mapped column stays dead: a task created after the redefine reads back `null` for
the new field.

This test had to be designed carefully. The obvious version — give the new field a default
(`private String status = "open"`) — **passes spuriously**: the grid shows `open` because
that is the field initializer in the freshly redefined bytecode, not because anything
round-tripped through the database. The discriminating version gives the field no default
and sets it explicitly in `createTask`; the grid then re-queries and Hibernate hands back a
fresh instance, so `null` proves the column is unmapped. The naive test would have reported
a false positive.

Conclusion: **entity changes must escalate to restart**, and `hot-reload` must never be
claimed for them.

## HotswapAgent needs JPMS flags on Java 25

Without `--add-opens`, HA's core helper fails on every redefine:

```
NoClassDefFoundError: Could not initialize class org.hotswap.agent.util.classloader.ClassLoaderHelper
Caused by: InaccessibleObjectException: Unable to make ClassLoader.findLoadedClass accessible:
           module java.base does not "opens java.lang" to unnamed module
```

with `JdkPlugin` cache-flush errors and an `InvocationTargetException` from `ProxyPlugin`
alongside. These are logged and swallowed, so the failure is quiet. The flags used to clear
them all:

```
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.lang.reflect=ALL-UNNAMED
--add-opens java.base/java.io=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
--add-opens java.desktop/java.beans=ALL-UNNAMED
```

Provisioning the jar is therefore not sufficient — the daemon must own these flags too.
Notably the Spring test passed even without them, so their absence degrades HA silently
rather than obviously: exactly the kind of half-working setup a user cannot diagnose.

## HA's Vaadin plugin triggers a second browser refresh

`VaadinIntegration - Live reload triggered` appears on every redefine, even though the
plugin cannot register the 25.x `Hotswapper` (C8.2). So there are two refresh paths at
once: HA's live reload and our `Hotswapper` re-navigation. Confirms the plan's
`-Dhotswapagent.disablePlugin=Vaadin` recommendation with evidence.

## Watcher interaction — not reproduced

HA registers six watchers on `target/classes`, but with `autoHotswap=false` no competing
redefinition was observed across ~10 harness runs writing class files there. Treat as
unresolved rather than safe: `autoHotswap` must stay off, and P3 should assert it.

---

# Phase 1 — daemon lifecycle and CLI

Built 2026-08-13. Scope is lifecycle only: `status`, `start`, `stop`, `restart`,
`shutdown`, `ping`. No `apply` yet — but the wire protocol already streams `> text`
progress lines before a final `EXIT <code>`, which is the shape `apply` needs in P2.

## What was built

| Piece | Location |
|---|---|
| CLI | `vaadin-dev` (bash) |
| Daemon | `devloop/daemon-src/`, built by `devloop/build-daemon.sh` → `vaadin-dev-daemon.jar` |
| Registration | `src/main/java/.../devloop/DevLoopRegistration.java` |

JDK-only, no dependencies.

## Exit criteria — all met

| Criterion | Result |
|---|---|
| First command auto-spawns the daemon | `status` on a clean tree spawned it and returned `stopped`, exit 0 |
| Two shells share one daemon | both `ping`s returned `pong p1 pid 12352` |
| App starts and registers | `running pid=28496 owner=daemon registered=true`, HTTP 200, mode `DEVELOPMENT_FRONTEND_LIVERELOAD` |
| Hard kill → `crashed` + exit code, daemon survives | `crashed exit=-1`, daemon still up, `app registration closed` logged |
| `restart` from crashed | crashed → running |
| Stale record reaped, not trusted | daemon logged `reaping stale daemon record (pid 999999 is gone)` via `ProcessHandle`, never a socket probe |
| Idle timeout | with `idleSeconds=10`, exited after ~15 s and removed the handshake file |
| Idle timeout suppressed while app runs | still `running` after 25 s with a 10 s timeout |
| `shutdown` stops the app it owns | app gone, handshake removed |

## Two design decisions worth recording

**The daemon launches the app JVM directly, not via `spring-boot:run`.** That goal forks,
so the app would be a *grandchild*: exit codes are lost and a kill orphans the JVM — which
is exactly why `pkill` failed during Phase 0 and the app had to be killed by port. A direct
child gives real exit codes (`crashed exit=-1`) and a clean stop. It also drops ~3 s of
Maven startup and reuses the classpath cache the compile leg needs anyway.

**The handshake file is `.vaadin/daemon.properties`, not `daemon.json`.** The CLI is bash;
a properties file is parseable with `grep`/`cut`, so no JSON parser and no JVM start per
command. `vaadin-dev status` is a `/dev/tcp` round trip in milliseconds, which is the point
of preferring a CLI over MCP in the first place. It carries `pid` *and*
`startEpochMillis`, so a recycled pid cannot be mistaken for a live daemon.

## Three defects found by testing, all fixed

1. **The auth token was printed to stdout** in the launch-flags line, and therefore into
   `daemon.log`. Now redacted.
2. **`exit=1` after a deliberate stop.** The code came from `destroy()`, but an agent
   reading `stopped exit=1` would infer failure. Exit codes are now reported only for
   `crashed`.
3. **Bash leaked `/dev/tcp` errors** (`Connection refused`) on the stale-daemon path.
   Bash reports a failed redirection itself, so the suppression has to wrap the
   redirection — `if ! { exec 3<>...; } 2>/dev/null`.

## Operational wrinkle

On Windows a running daemon holds `vaadin-dev-daemon.jar` open, so rebuilding it fails
while one is live — and `set -euo pipefail` made the failure easy to miss when only the
tail of the output was read. `vaadin-dev shutdown` before rebuilding, or version the jar
filename. Worth handling before this ships to anyone else.

---

# Phase 2 — transaction engine and the compile leg

Built 2026-08-13. Adds `apply` on top of P1's lifecycle. Runtime redefine is still
P3, so a successful compile escalates straight to a restart when the app is
running — the honest way to make a change live with only P1+P2 machinery, and
already the right shape for attempt-then-escalate.

## Exit criterion — met

The RFC's failure example reproduces, with a real javac diagnostic:

```
$ ./vaadin-dev apply
change-set: 1 file(s)
compiling → Failed
TaskListView.java:66:40  error: cannot find method getStatus() in variable task of type Task
  → check the name, or add the missing member/import
$ echo $?
1
```

File, line and column; exit code 1; the runtime leg never attempted. `--json` carries
the same thing structured, including javac's diagnostic code:

```json
{"transaction":"tx#2","outcome":"failed","classification":"none","reason":"compile",
 "changeSet":["src/main/java/.../TaskListView.java"],"classes":[],
 "diagnostics":[{"file":"TaskListView.java","line":66,"column":40,
   "code":"compiler.err.cant.resolve.location.args",
   "message":"cannot find method getStatus() in variable task of type Task",
   "hint":"check the name, or add the missing member/import"}],
 "timings":{"detectMs":3,"compileMs":609,"runtimeMs":0,"totalMs":670},
 "nextAction":"check the name, or add the missing member/import"}
```

## Transaction semantics — verified

**Supersede, don't queue.** Two applies fired concurrently:

| client | output | exit |
|---|---|---|
| A | `Failed(superseded): a newer apply took over` | 4 |
| B | `superseding tx#2` … `compiling → compiled (1.22s)` | 0 |

Both callers got a terminal answer, only one transaction was ever in flight, and the
superseded one is deliberately not recorded as "the last transaction" — it is not the
answer to "what is the state of my last change?".

**Success path**, verified in the browser rather than trusted (the C7 lesson):
`compiling → restarting → Stable (14.63s)`, and the edited empty-state string was
present in the rendered page.

**Costs.** Compile is 0.6–1.5 s for one or two files in-process, against ~3 s of Maven
JVM startup alone. A no-op `apply` is 0.19 s wall clock including the bash client and
socket round trip — the common case stays cheap, which is the point of a CLI over MCP.

## Change detection: artifacts, not a snapshot

First implementation kept an in-memory snapshot of source mtimes taken when the daemon
started. It was wrong in a way testing caught immediately: **any edit made before the
daemon started was invisible**, so the first `apply` after a daemon restart reported
`no changes` while `target/classes` was stale. The daemon's own lifecycle leaked into
the answer.

Replaced with a stateless comparison of each source against its compiled artifact
(missing, or older than the source). This survives daemon restarts, needs no state, and
gives "only the latest bytes on disk matter" for free: a failed compile leaves the file
in the change-set until it succeeds.

Not yet handled: deleted sources. Removing a class also needs its stale artifact
cleaned up, which belongs with P3's class-removal work.

## Four defects found by testing, all fixed

1. **`--json` was not parseable.** Progress lines were interleaved with the JSON
   object, so piping to a parser would fail. Progress is now suppressed in `--json`.
2. **Locale-dependent numbers.** `String.format("%.2fs", …)` rendered `0,71s` on this
   Finnish machine. Now `Locale.ROOT` — this output is parsed, not just read.
3. **javac messages were repetitive when flattened** —
   `cannot find symbol symbol: method getStatus() location: variable task of type
   com.dev.vaadin.example.examplefeature.Task`. Collapsed, and fully-qualified names
   shortened to simple ones. Output length is a real cost when an agent reads it every
   apply.
4. **`status --json` omitted the transaction** that the text form showed, so an agent
   using the structured output would miss it.

---

# Phase 3 — the runtime leg

Built 2026-08-13. `apply` now attempts an atomic redefine first and escalates only when
it cannot stick. Commands ride the registration connection the connector already holds, so
there is one channel, not two: the daemon sends `REDEFINE`/`INFO` down it and reads the
reply, and its close still means the app is gone.

## Exit criteria — all met

| Criterion | Result |
|---|---|
| Method body → `Stable` as `hot-reload` | `compiling → runtime → Stable (1.70s)`, verified live in the browser with no page reload |
| Added method → `Stable` as `restart` with the JVM's reason (stock JDK) | `restart: class redefinition failed: attempted to add a method` |
| C7 regression: duplicate copy present, body edit still live | `duplicateClassCopies=1`, `redefineClasses(2)`, asserted live in the browser |
| Apply result carries the duplicate count | `"duplicateClassCopies":1` in `--json` |
| `classification` decision recorded | deferred, see below |

**1.70 s hot-reload against 14.6 s for the P2 restart** — the loop is ~8× faster on the
common case, and that is the whole point of the runtime leg.

The daemon reports the JVM's actual capabilities at registration, since the two halves are
independent:

```
app registered (mode=DEVELOPMENT_FRONTEND_LIVERELOAD) OK instrumentation=true
  redefineSupported=true hotswapAgent=true hotswapper=true enhancedRedefinition=true
```

On a JDK pinned with `-Dvaadin.dev.javaHome`, the same line reports
`enhancedRedefinition=false` — which is how the added-method escalation was exercised.

## Escalation is not only about what the JVM refuses

P0.5 showed that a *successful* redefine can still leave a change not live, or leave the
app broken. Both cases are now escalation rules, and both were verified:

| Change | JVM verdict | Daemon verdict |
|---|---|---|
| Entity gains a mapped column | accepted on JBR | `restart: entity mapping cannot hot reload (Task): Hibernate's metamodel and schema are fixed at startup` |
| Spring bean gains a method | accepted on JBR | `restart: structural change to a Spring bean (TaskService): the existing proxy would not match the new class` |
| Spring bean method **body** changes | accepted | `hot-reload` in 2.04 s — correctly *not* escalated |

That last row is why the rule keys on *structural* change rather than on "is a bean".
Escalating every bean edit would turn a 2 s loop into a 14 s one for the most common kind
of change. The connector fingerprints each class's declared methods and fields before the
redefine and compares afterwards, so "structural" is measured, not guessed.

## A new finding: structurally redefining a Spring bean corrupts the context

Running the P0 harness (which drives the redefine directly and therefore bypasses the
daemon's escalation rules) left the app throwing:

```
NoSuchBeanDefinitionException: No qualifying bean of type 'TaskRepository' available
```

with HotswapAgent's scanner reporting `basePackage 'com.dev.vaadin.example' not associated
with any scannerAgent`, and, at startup, `Fail to fetch url from resource: TaskRepository
defined in @EnableJpaRepositories` — HA's Spring plugin does not handle Spring Data
repository proxies. The app was serving HTTP 200 while every navigation failed, and the
redefine had reported success.

Two consequences:

1. **HotswapAgent's `Spring` and `SpringBoot` plugins are now disabled** in the launch
   line (`disablePlugin=Vaadin,Spring,SpringBoot`), matching what `draiv-experiment`
   already did — and now with a measured reason. Structural bean changes escalate instead.
2. It sharpens C8: HA is required for some things (P0.5) and destabilising for others
   (here). The daemon's escalation rules, not HA, are what keep `Stable` honest.

## `classification` — explicitly deferred

Flow does not expose the refresh strategy it computed (the C3 amendment; `strategies` was
empty in every run). v1 therefore reports only what the daemon itself did — `hot-reload`,
`restart` — and does not claim Flow's internal `UIRefreshStrategy`. Log scraping was
considered and rejected: it would put a debug-log format into the agent contract. Raising
a small Flow API remains the way to close this.

## Defects found by testing, all fixed

1. **The `INFO` handshake deadlocked.** It was sent before the reader loop started, so it
   waited for a reply nobody was reading. Now issued off-thread.
2. **The JVM's rejection reason was truncated to one word.** The `key=value` reply parser
   split on whitespace and `message=class redefinition failed: attempted to add a method`
   contains spaces. `message=` is now last and takes the rest of the line.
3. **A test fixture silently no-op'd.** A `sed` anchor no longer matched, so an assertion
   "failed" against an unchanged app and briefly looked like a stale-reload bug. Fixtures
   now assert that the edit applied before drawing conclusions from the result.

## Known gap

The duplicate-copy condition is not reproducible on demand: eight route re-registrations,
adding a new routed view, and visiting new routes all yielded `dupes=0`. It appeared only
after a run of structural view edits (`add-new-view`, `change-superclass`, `add-field`,
`add-private-method`, `add-grid-column-lambda`). The regression test therefore depends on
that sequence rather than on a direct trigger. A deterministic test wants a connector test
hook that loads a second copy on demand.

---

# Cleanup — one transport, one redefine implementation

After P3 the connector had five classes and two ways in. An audit of actual references
showed the second one was dead weight.

**Removed: `DevLoopControlServer`.** The daemon referenced it zero times — it opened its own
socket and wrote `.vaadin/devloop.port` purely for the measurement harnesses, duplicating
the registration connection the connector already holds. It also still contained its own
private `redefine()` that had become unreachable when the `REDEFINE` branch was pointed at
the shared implementation: ~90 lines of *stale* logic with no structural detection and no
entity/bean reporting. Reviving it would have quietly reintroduced P0-era behaviour under
P3 rules.

**The four that remain, and why each is load-bearing:**

| Class | Role |
|---|---|
| `DevLoopServiceInitListener` | calls `Hotswapper.register()` — the C2 fix; nothing in Flow does this |
| `DevLoopHotswapper` | the `VaadinHotswapper` SPI impl; supplies the `onHotswapComplete` gate |
| `DevLoopRedefiner` | all-copies atomic redefine plus entity/bean/structural detection |
| `DevLoopRegistration` | the one connection: registers, serves `REDEFINE`/`INFO`, close = app gone |

**Harnesses ported to a `redefine` verb.** The daemon gained `vaadin-dev redefine
<classes>`, which returns the connector's reply verbatim with none of `apply`'s escalation
policy — so the harnesses still measure raw JVM behaviour, on one transport, through a
shared `DaemonClient`. Also dropped three dead accessors (`getLog`,
`getCompletedClasses`, `getStrategies`).

Verified after the change: app starts and registers; `.vaadin/devloop.port` no longer
appears; `apply` still hot-reloads in 2.1 s; the P0 suite reports **22/24, identical to
before**.

An unplanned benefit: the harness output now carries `beans=TaskService
structural=TaskService`, so the unsafe redefine it performs on purpose is visible in its
own results. Previously that hazard was silent — which is how it corrupted the Spring
context during P3 without any signal.

---

# Phase 4 — frontend leg, CSS, frontend-down

Built 2026-08-13. Adds the resource leg to `apply` and dev-server liveness to `status`.

## Exit criteria — met

| Criterion | Result |
|---|---|
| CSS-only edit classifies as `hmr`, never restarts | `frontend → Stable (0.03s)`, `hmr: 1 resource(s) copied, pushed 1 stylesheet(s) in place`; app pid unchanged; new colour verified in the browser |
| Killing Vite leaves the app registered and blocks the frontend leg | app stays `running registered=true`, `status` shows `frontend down:63358`, and a CSS apply fails in **0.29 s** with `frontend → Failed (frontend-down: the Vite dev server is not answering on 63358)`, exit 1 |

## Flow owns CSS — and it is broken on Windows

`PublicResourcesLiveUpdater` (started by `DevModeHandlerManagerImpl` in dev mode) already
watches `src/main/resources/META-INF/resources` and friends, re-bundles active
`@StyleSheet` URLs and pushes to the browser. Per C6 the daemon must not duplicate it —
and `StyleSheetHotswapper.onResourcesChange` is an explicit **no-op**, commented *"changes
in CSS files are handled by a dedicated file watcher"*, so there is no supported hook for
the daemon to push CSS through `onHotswap` either.

That watcher fails on Windows. Every CSS change logs:

```
ERROR PublicResourcesLiveUpdater : Unable to perform hot update for CSS change under root
   ...\src\main\resources\META-INF\resources, fall back to page reload
java.nio.file.InvalidPathException: Illegal char <:> at index 7: context:\view-title.css
   at PublicResourcesLiveUpdater.isVaadinThemeUrl(PublicResourcesLiveUpdater.java:187)
```

`isVaadinThemeUrl` does `new File(url).toPath()` on a `context://…` stylesheet URL. A colon
is legal in a POSIX filename and illegal in a Windows path, so this throws only on Windows
— and it throws before the loop over active URLs does any work, so **no** stylesheet is
updated and every CSS edit degrades to a full page reload. Any app with a relative
`@StyleSheet` is affected. Worth filing against Flow.

## Two gaps the daemon closes

**The classpath copy.** Flow's watcher watches the *source* tree and never refreshes
`target/classes`, so the fallback page reload re-fetches the **stale** classpath copy.
Measured: after editing the source, `curl /styles.css` still served the old bytes; after
copying to `target/classes` it served the new ones. Copying changed resources is the
resource analogue of compiling, and it is what makes the reload show anything new.

**An HTTP race the daemon's own speed creates.** Static resources are served
`Cache-Control: no-cache` with `Last-Modified`, whose granularity is one second. Copying a
file and broadcasting a reload ~10 ms later revalidates to 304, so the browser kept the
*previous* CSS — reproducibly one generation behind, confirmed by a page marker that showed
the reload really happened. Fixed by pushing content with
`BrowserLiveReload.update(url, content)`, which skips HTTP entirely; a reload is now only
the fallback when no stylesheet could be pushed.

## frontend-down

Per C5, `DevServerWatchDog` is useless here: it runs the other way round and is
package-private. Instead the connector reads Vite's port from
`DevModeHandlerManager.getDevModeHandler(service).getPort()` and connects to it. A loopback
connect to an HTTP port is a harmless liveness check — the JDWP caveat about half-open
handshakes does not apply.

Two states share "no port" and needed separating, because one resolves and the other never
will:

- `no-dev-server(DevBundleBuildingHandler)` — **the default for this project**: no Vite
  process exists at all, the frontend is a prebuilt bundle.
- `starting(ViteHandler)` — a dev server that has not finished booting.

That default matters: **the kill-Vite criterion is not reachable in a stock configuration**
of this app. It required `-Dvaadin.frontend.hotdeploy=true`, which prompted a new feature —
the daemon now forwards any `vaadin.*` system property it was started with to the app JVM,
so dev-mode options can be steered without the daemon knowing each one.

It also shows the `mode` string reported at registration is misleading:
`DEVELOPMENT_FRONTEND_LIVERELOAD` comes from `isDevModeLiveReloadEnabled()` and says nothing
about whether Vite runs. The handler kind is the honest signal, and `status` now carries it.

## Defect found and fixed

Failures printed `compiling → Failed` regardless of which leg failed, sending the reader to
the wrong place. The phase is now derived from the transaction.

## Honest gap

Even with the in-place push delivering correct content immediately, a page marker confirmed
the browser **still reloads** — Flow's broken watcher forces it independently of us. So on
Windows this is "correct and fast" but not true no-reload HMR end to end. It will be, once
the `isVaadinThemeUrl` bug is fixed; nothing in the daemon needs to change for that.

Also unaddressed: non-CSS resources. Flow's watcher ignores them deliberately ("images/fonts
need IDE copying to output dir, so full reload is not reliable"), and the daemon now does
that copying — so wiring a reload for them is a small, sensible follow-up.

---

# Correction — HotswapAgent was never actually disabled

Found 2026-08-14 from a user report: a one-word change to a view title hot reloaded
correctly *and then reloaded the whole page*.

The extra reload was HotswapAgent's Vaadin plugin. It had been running the whole time:

```
HOTSWAP AGENT: VaadinPlugin' initialized in ClassLoader 'AppClassLoader'
HOTSWAP AGENT: VaadinIntegration - class ...VaadinIntegration initialized for servlet
HOTSWAP AGENT: VaadinIntegration - Live reload triggered      <- right after our redefine
```

while our own events for the same transaction reported `requiresPageReload=false` — Flow
had correctly chosen a soft refresh. Two notifiers, and the coarser one won.

**The flag was wrong.** P3 shipped `-Dhotswapagent.disablePlugin=Vaadin,Spring,SpringBoot`.
The real property, read from HotswapAgent 2.0.1's own bytecode, is **`disabledPlugins`** —
plural, and *unprefixed*: `PluginConfiguration` loads `hotswap-agent.properties` and then
merges `System.getProperties()` over it under the same key names, and
`getDisabledPlugins()` reads the key `disabledPlugins`. An unrecognised key is accepted in
silence and disables nothing. Fixed to `-DdisabledPlugins=Vaadin,Spring,SpringBoot`.

Verified: the Vaadin plugin no longer initializes (`0` matches in the app log), and the same
title change now leaves the page in place — a marker set in `window` before the apply
survives it, `performance.getEntriesByType('navigation')[0].type` stays `navigate`, and the
new title is live.

## What this corrects in P3

P3 concluded "HotswapAgent's `Spring` and `SpringBoot` plugins are now disabled … structural
bean changes escalate instead". Only the second half was true. The plugins stayed active, so:

- The Spring context corruption P3 observed happened **with** the Spring plugin running,
  which is consistent with what was written — but it was never mitigated by disabling it.
- What actually kept `Stable` honest was the structural-bean escalation rule, which stops
  `apply` performing that redefine at all. That rule was doing all the work alone.

Both bean paths were re-checked now that the plugins are genuinely off, and both still
behave: a method-body change to `TaskService` hot reloads in 0.92 s, and adding a method
escalates with `structural change to a Spring bean (TaskService): the existing proxy would
not match the new class`, after which the app still serves HTTP 200.

## Worth passing upstream

`draiv-experiment/webapp/pom.xml` uses the same wrong name
(`-Dhotswapagent.disablePlugin=Spring,SpringBoot`), so its Spring plugins are not disabled
either, whatever the profile intends.

Broader lesson, and the third instance of it in this project: a configuration key that is
merely *ignored* when misspelled is indistinguishable from one that works. The others were
`-XX:HotswapAgent=fatjar` warning and continuing (C8.3), and HA degrading silently without
`--add-opens` (P0.5). Anything the daemon depends on being off should be *verified* off, not
assumed — the check here is one grep of the app log for `VaadinPlugin' initialized`.

---

# Correction — change detection could not see IDE-built edits

Found 2026-08-14 from a user report: CSS edits produced `no changes`, and the browser
reloaded on its own after editing Java while styles never updated.

Three separate defects, with one shared root.

## 1. The artifact comparison answers the wrong question

P2 chose stateless detection: a source is changed if its build artifact is missing or
older. That is right for "does this need compiling?" and wrong for "is this live?" — and
the second is the question `apply` exists to answer.

With an IDE building on save (IntelliJ with auto-build), the artifact is written *before*
the daemon looks:

- **CSS:** IntelliJ copies resources into `target/classes`, so the classpath copy is
  already current → `no changes`, and the browser is never told.
- **Java:** IntelliJ compiles to `target/classes`, so the `.class` is newer than the
  `.java` → `no changes`, while the running JVM still holds the old bytecode.

Reproduced both exactly: editing the source alone was detected; editing it *and*
refreshing the artifact reported `no changes`.

Fix: keep the artifact check, and add state for what the daemon has actually made live —
a fingerprint (mtime + size) per source of the last time it was redefined, and per
resource of the last time the browser was notified. Both are reset from disk whenever the
app registers, because a freshly started app is running exactly what is on disk. A quiet
project still reports `no changes`, twice in a row.

## 2. Flow's CSS watcher was reloading the page on save

The reload the user saw was not caused by the Java edit at all. With no CSS in play, a
Java compile into `target/classes` triggers nothing (verified: page marker survives, and
the change correctly stays invisible until `apply`). The reload came from Flow's
`PublicResourcesLiveUpdater`, asynchronously, from the *earlier* CSS edit — it throws
`InvalidPathException` on the `context://` stylesheet URL (the Windows bug) and falls back
to a full page reload, several times per edit.

That watcher is both broken here and redundant now that the daemon owns the resource leg,
and two actors pushing to the browser is precisely what the transaction model exists to
prevent. There is no supported way to switch it off — `DevModeHandlerManagerImpl` starts it
unconditionally and keeps it only inside a shutdown lambda — so the connector now closes it
reflectively at startup (`FlowResourceWatcherSuppressor`), logging
`closed Flow's PublicResourcesLiveUpdater; apply is now the only trigger for CSS`, and
failing quietly if Flow's internals move. **This wants a real Flow API**: either fix the
Windows path handling, or let a hotswap owner declare that it owns the resource leg.

## 3. The in-place CSS push had never actually been proven

P4 reported "pushed 1 stylesheet(s) in place" and a changed colour — but Flow's watcher was
reloading the page at the same time, so the reload plus a fresh classpath copy could have
produced that result on its own. With the watcher now suppressed, the push is the only
mechanism left, and it works: font-size went 16px → 26px with the page marker surviving.
What P4 could not distinguish, this can.

## Verified end to end

Editing CSS and Java together, both artifacts pre-built by an "IDE": nothing happens on
save, then one `apply` reports `change-set: 2 file(s)` and
`hot-reload: redefineClasses(1)`, after which the title reads `Both Legs`, the font is
26px, and the page was never reloaded.

## Method note

Three of my own test fixtures were wrong before the code was, and each looked like a
product bug: a `sed` anchor that silently stopped matching, an exit code read through a
pipe (`| head`) so it measured `head`, and a CSS rule inserted *above* an existing
declaration of the same property, so the later one kept winning. Assert the fixture landed
— and that it can actually take effect — before believing what the app then shows.

---

# Why invalid CSS reports Stable, and why that stays

Reported 2026-08-14: appending random invalid syntax to `styles.css` and running `apply`
returns `Stable` with `hmr: 1 resource(s) copied, pushed 1 stylesheet(s) in place`, and no
error anywhere.

**Nothing is swallowing an error — there is none.** CSS error recovery is mandated by the
spec: a browser drops what it cannot parse, keeps the rest, and stays quiet. Verified
directly. With garbage appended to `styles.css`, the browser still reported a working
stylesheet with its other four rules intact and no console message:

```
{ href: "http://localhost:8080/styles.css", rules: 4 }
```

So no component in the chain produces a diagnostic: the daemon copies bytes and pushes text,
Flow forwards it, the browser silently discards the bad part. The Java leg has a compile
gate; the resource leg has no equivalent, so `Stable` here means "the new bytes are on the
classpath and were pushed", not "the stylesheet is correct".

A structural gate was built and then **reverted by decision** — unbalanced braces,
unterminated comments and strings, and trailing text that never opens a block were all
detectable with no false positives on nesting, `@media`, custom properties or `@import`. It
worked, and the cost was not worth carrying: CSS mistakes surface immediately in the browser,
which is where the developer is already looking.

What remains true and worth remembering:

- Only Java changes are gated. A CSS change is copied and pushed unvalidated.
- Closing the gap properly needs the browser to report what it parsed — comparing
  `cssRules.length` against what was pushed would catch dropped rules. That is the same
  missing browser→server channel as C4, and belongs in that ask rather than in a
  server-side CSS parser.
