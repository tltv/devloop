# Vaadin dev-loop daemon — implementation plan

**Revision 2** · 2026-08-12 · target Vaadin 25.2.6, Spring Boot 4.1, Java 25

Revision 1 was a review of the RFC against the 25.2.6 source. Revision 2 folds in
Phase 0, which is now **complete** — see [PHASE0-FINDINGS.md](PHASE0-FINDINGS.md)
for the raw run. Phase 0 confirmed two of the corrections empirically, produced one
new load-bearing finding, and settled the Instrumentation-vs-JDWP decision with
evidence rather than preference.

Claims below are marked **[measured]** (observed in the running app),
**[read]** (verified in 25.2.6 source or jars), or **[open]** (not established).

---

## The short version

The RFC's spine holds and is now proven end to end: one daemon per project root,
one transaction in flight, terminal states only, CLI over local RPC. A method-body
change reached the browser with no restart, in ~80 ms of runtime work. **[measured]**

Six of the RFC's technical premises were wrong or stale against the 25.2.6 code
(C1–C6). Two were load-bearing, and Phase 0 confirmed both:

- **Nothing in Flow registers the `Hotswapper`** (C2). Without an explicit
  `register()` call, a perfect redefine changes the running bytecode and the page
  stays stale. A plain `VaadinServiceInitListener` is sufficient. **[measured]**
- **The completion signal and layout regeneration already exist** as a public SPI
  (C3), so the transaction gate needs no invention — though the *classification*
  does, see the C3 amendment. **[measured]**

Phase 0 then added the finding that most changes the design:

- **C7 — `redefineClasses` returning success does not mean the change is live.**
  A class name can map to several loaded `Class` objects; redefining one leaves the
  copy the app instantiates untouched, and the JVM reports success either way.
  **[measured]**

And one that narrows the RFC's dependency on HotswapAgent:

- **C8 — HotswapAgent is additive, not the mechanism.** Enhanced redefinition comes from
  the JVM flag alone; HA 2.0.1's Vaadin plugin still targets the 24.x package and is dead
  on 25.x. HA's real value is its Spring/Hibernate plugins, which is a separate question
  settled by P0.5. **[measured]**

Net effect on scope: v1 gets *simpler* than the RFC (no JDWP, no dependency on HA's Vaadin
plugin, no half-open-handshake caveat, and no `autoHotswap` race to fight since it is off by
default) but P3 gets *stricter* (success must be verified, not assumed).

---

## Corrections to the RFC

### C1 — The hotswap API moved packages in 25.x **[read]**

The RFC cites `Hotswapper` and `RouteRegistryHotswapper` as if in `flow-server`.
In 25.x they are in **vaadin-dev-server**, `com.vaadin.base.devserver.hotswap`,
`@since 25.1`. The 24.x coordinates (`com.vaadin.flow.hotswap`) exist only on the
`flow-24` branch.

Consequences: every code reference in the RFC needs rewriting; the daemon depends on
`vaadin-dev-server`. Also **any HotswapAgent build predating the 25.1 move targets a
package that no longer exists** — verify before relying on it.

### C2 — Nothing registers the Hotswapper; the redefine alone is inert **[measured]**

Zero callers of `Hotswapper.register(VaadinService)` across `flow-server` and
`vaadin-dev-server` main sources. The javadoc expects the hotswap tool to inject the
call, which is what HotswapAgent's plugin does via bytecode injection.
`DebugWindowConnection` only sniffs for `org.hotswap.agent.plugin.vaadin.VaadinIntegration`
to *label* the backend.

Registration is gated only on `!isProductionMode()`, so a plain
`VaadinServiceInitListener` calling `Hotswapper.register(service)` is enough — no
agent bytecode injection. Confirmed working in Phase 0.

This is the point where a naive implementation silently half-works: the JVM runs new
code, the route registry is never refreshed, and the agent sees a green `apply` on a
stale page.

### C3 — Most of the state model already exists **[measured]**, but not the classification **[measured]**

`VaadinHotswapper` is a public SPI discovered via `Lookup.lookupAll` backed by
`META-INF/services` and ordered by `@Priority`. `onHotswapComplete(HotswapCompleteEvent)`
fires reliably and is a usable completion gate. Phase 0 used it as exactly that.

**Amendment from Phase 0.** The public event API does **not** expose the strategy Flow
computed. `HotswapClassEvent.getUIUpdateStrategy(ui)` returned empty for every event in
every run (`strategies=[none]`) — it reports only a strategy a hotswapper explicitly
*requested* via `triggerUpdate`. Flow's actual decision appeared only in its DEBUG log:

```
DEBUG c.v.base.devserver.hotswap.Hotswapper : Triggering re-navigation to current
route for UIs affected by classes changes.
```

The internal `Hotswapper.UIRefreshStrategy` (`RELOAD`, `REFRESH`, `PUSH_REFRESH_ROUTE`,
`PUSH_REFRESH_CHAIN`, `SKIP`) is package-private; the public `UIUpdateStrategy` has only
`REFRESH`/`RELOAD`. So the plan's `classification` field needs a small Flow API addition
or log scraping. **Settle with the Flow team; do not build log scraping into the
contract.**

### C4 — ConnectionStatus is browser-side TypeScript, not server state **[read]**

`ConnectionStatus {ACTIVE, INACTIVE, UNAVAILABLE, ERROR}` with split
`frontendStatus`/`javaStatus` is defined in the **Copilot frontend**
(`copilot/frontend/copilot/connection.ts`), not in any Java type. The daemon cannot
read it — it is state in a browser tab.

What v1 can honestly expose server-side: the count of connected live-reload Atmosphere
resources tracked by `DebugWindowConnection`, and that a `reload()` / `refresh(boolean)`
/ `update(path, content)` was broadcast. Shrink that row of the RFC's status table, or
add a browser→server report as explicit net-new scope.

### C5 — DevServerWatchDog points the other way **[read]**

The RFC says the in-process component "already watches [Vite] via `DevServerWatchDog`".
It is the reverse: the JVM opens a `ServerSocket` and passes `watchDogHost`/`watchDogPort`
into Vite's environment (`AbstractDevServerRunner:329-331`) so **Vite** can notice the
**JVM** died and exit. The class is package-private and reports nothing back about Vite's
health.

`frontend-down` therefore needs a different mechanism — cheapest is the `Process` handle
already held by `AbstractDevServerRunner` (not public API today), otherwise a small Flow
addition. Scope, not free.

### C6 — The frontend leg is partly in-process already **[read]**

`RouteRegistryHotswapper.onClassesChange` calls `TaskGenerateReactFiles.writeLayouts(options, …)`
itself, inside the app JVM. The daemon must **not** run its own generator for layout/route
changes — it would duplicate and race Flow. The frontend leg is mostly *await* (Flow
regenerates → Vite's watcher rebuilds → HMR), not *drive*.

### C7 — Redefine success is not proof the change is live **[measured]** ⟵ new

A single binary class name can map to **more than one loaded `Class` object**. For the
routed view `TaskListView`, a second copy appeared after route re-registration
(`dupes=[TaskListViewx2]`). Redefining only one copy makes `redefineClasses` report
success while the app keeps instantiating the other.

Observed sequence:

1. Fresh JVM, first redefine → change live in the browser.
2. Same edit later in the session → `OK redefined=1 completed=true`, class file on disk
   correct, **browser stale even on a fresh page load**.
3. A different class (`MainLayout`) reloaded fine in the same JVM — the failure is
   class-scoped, which is what ruled out JVM-level degradation and pointed at duplicate
   copies.
4. Fix: collect **every** loaded copy and pass them all to the one atomic
   `redefineClasses` call. The previously-stale edit then went live.

Independent corroboration: the `draiv-experiment` `HotswapPusher` already iterates
`vm.classesByName(name)` and redefines every `ReferenceType` returned. Two implementations
converged on this, so treat it as a hard requirement, not a bug fix.

Consequences:

- P3 must not treat "no exception" as reaching `Stable`.
- The failure is invisible without browser-level checking, so a browser assertion belongs
  in the test suite even though the daemon's own job ends at `Stable`.
- The apply result should carry the duplicate count, so the condition is observable rather
  than silent.

### C8 — HotswapAgent is required, but not for the reason the RFC gives **[measured]**

The RFC treats HotswapAgent as the mechanism that makes hot reload work ("the daemon
defaults to HotswapAgent for daemon-launched JVMs"). Verified against
`hotswap-agent-2.0.1.jar` and JBR 25.0.2:

1. **HA does not provide enhanced redefinition.** `-XX:+AllowEnhancedClassRedefinition`
   is a JBR/DCEVM *JVM* feature. Phase 0 measured 22/24 on JBR with **no HotswapAgent
   loaded at all**. The 67%→92% jump is already banked without HA.
2. **HA 2.0.1's Vaadin plugin does not fire on Vaadin 25 — but only because of two
   hard-coded strings.** It reaches Vaadin entirely reflectively: `VaadinPlugin` holds
   `private java.lang.Object vaadinHotswapperObj`, injects this source text into
   `VaadinService.init()` via Javassist —
   `java.util.Optional maybeHotswapper = com.vaadin.flow.hotswap.Hotswapper.register(this);`
   — and later invokes `"onHotswap"` **by name**. Flow designs for exactly this: its
   `onHotswap(String[], Boolean)` takes a boxed `Boolean` specifically so HA's reflective
   lookup resolves ("*Hotswap agent will call this method by reflection, and it fails to
   identify it if it has primitive parameters*").
   <br>
   So this is **not** a compile-time dependency that would need porting: the 24.x
   coordinates live in two string constants, and the injected snippet is wrapped in
   `catch (Exception e) { e.printStackTrace(); }`, which is why the failure is quiet on
   25.2.6. The jar still contains **zero** references to
   `com.vaadin.base.devserver.hotswap`, so on this stack it registers nothing and C2
   remains load-bearing — but a string-level patch would make it work, and **Copilot ships
   its own HA jar**, so a 25.x-aware build may already exist. Do not rely on this plugin
   staying inert; see hazard 1.
3. **`-XX:HotswapAgent=fatjar` fails silently.** It expects
   `<jbr>/lib/hotswap/hotswap-agent.jar`; JBR 25.0.2 does not ship it. The JVM prints
   `HotswapAgent not found on path:...` and **continues with exit code 0**. A profile
   using that flag can run with no HA while enhanced redefinition still works from the
   other flag — indistinguishable from HA working. If we adopt the flag, fail loudly.
   (The `hotswap` profile in `draiv-experiment/webapp/pom.xml` uses this flag; worth
   confirming whether HA is actually installed on that machine.)
4. **The RFC's `autoHotswap` claim is backwards.** HA's bundled
   `hotswap-agent.properties` ships `autoHotswap=false` as the default. The RFC calls
   turning it off "the opposite of HotswapAgent's defaults"; it *is* the default. The
   file-watcher fan-out the RFC defends against only occurs if explicitly enabled.

**What HA is for, and why it is required.** Its framework plugins — `spring`,
`springBoot`, `hibernate_jakarta`, `proxy`, `jdk` — are what make changes to code *other
than* plain application classes reload. That is a product requirement, not an
optimisation: hot reload that covers only the app's own method bodies is not the loop we
are selling. It is also the only realistic mitigation for risk R1, since Phase 0 showed
JBR *accepts* an added `@Entity` field and an added `@Transactional` method while
Hibernate's metamodel, the schema, and existing CGLIB proxies all go stale.

**Decision: HotswapAgent is a required v1 component, provisioned automatically.** See
[Agent provisioning](#agent-provisioning). Unlike Copilot's IntelliJ plugin, which
downloads the jar and asks the user to place it, the daemon must do this with no user
action.

Verified by loading HA 2.0.1 into this stack alongside the Phase 0 connector
**[measured]**:

| | Result |
|---|---|
| Loads on Java 25 | yes — bytecode targets major 52, `Premain-Class` present |
| App starts, connector unaffected | yes — Instrumentation captured, Hotswapper registered, control server up |
| HA errors / exceptions | none |
| `SpringBoot` plugin | initialized — *"Spring Boot core version '4.1.0'"* |
| `Spring` plugin | initialized — *"Spring core version '7.0.8'"* |
| `Tomcat` plugin | initialized — Tomcat 11.0.22 |
| `HibernateJakarta` plugin | **discovered but never initialized** — app runs Hibernate ORM 7.4.1 |
| `Vaadin` plugin | initializes (`VaadinIntegration initialized for servlet SpringServlet`) but its hard-coded 24.x name does not resolve, so it registers nothing — while still firing its own `Live reload triggered` on every redefine |

**P0.5 then measured what HA changes behaviourally, and the requirement is vindicated
[measured]:**

| Test | no HA | HA | verdict |
|---|---|---|---|
| Add a `@Transactional` method to a service and call it | **breaks the app** (NPE, view will not instantiate) | **works** | HA required |
| Add a mapped `@Column` to an `@Entity` | not run | **fails** (`HibernateJakarta` never initializes on Hibernate 7.4.1) | must restart |

The Spring result is the strongest argument for the requirement, and it is worse than a
missing feature: without HA the redefine succeeds, the daemon would report `Stable`, and
the pre-existing CGLIB proxy has no override for the new method — so the call executes on
a proxy instance built without a constructor and NPEs on a null field. A corrupted
runtime reported as success. With HA, the identical edit works.

**HA also needs JPMS flags on Java 25.** Without `--add-opens java.base/java.lang`, HA's
`ClassLoaderHelper` fails on every redefine (`InaccessibleObjectException`), taking
`JdkPlugin` and `ProxyPlugin` with it — all logged and swallowed. The Spring test passed
anyway, so the absence degrades HA *silently*. Provisioning the jar is therefore not
enough; the daemon must own the flag set too (see
[Agent provisioning](#agent-provisioning)).

Full results in [PHASE0-FINDINGS.md](PHASE0-FINDINGS.md).

**Two hazards to design around.**

1. *Double registration — likely, not hypothetical.* Nothing is doubled with HA 2.0.1
   (its hard-coded name does not resolve), but per C8.2 that is a two-string defect, and
   Copilot distributes its own HA jar. Against **any** 25.x-aware build, HA's injected
   `Hotswapper.register(this)` and the connector's `register(service)` both run: two
   `Hotswapper` instances, every change dispatched twice, each with its own refresh. The
   connector's guard covers only its own double-discovery, and Flow exposes no way to ask
   whether a Hotswapper is already registered — so there is no defensive check available
   to us.
   <br>
   Mitigation is `-DdisabledPlugins=Vaadin`, which works **only because the
   daemon owns the launch**. That makes this an open problem for IDE-led mode (P6), where
   it does not — and an argument for asking the Flow team for idempotent registration (or a
   "who is registered" query) alongside C1/C3.
   <br>
   Note this is worth doing even if the plugin becomes fully 25.x-correct: it notifies on
   its own debounce (`vaadin.liveReloadQuietTime`) rather than when we ask, and it reloads
   the browser outright (`Redefined class {}, clearing Vaadin reflection cache and
   reloading browser`) instead of using Flow's computed in-place strategy. It also runs a
   second route-registry update of its own (`VaadinIntegration.updateRouteRegistry`). A
   timer-debounced, coarser notifier racing ours cannot be awaited and would break the
   transaction's completion gate — so the daemon calls `onHotswap` itself and this plugin
   stays off.
2. *Competing watchers.* HA registered **six** directory watchers on `target/classes`
   even with `autoHotswap=false` — its plugins watch for their own purposes (Spring bean
   definitions, class-init). The daemon writes `.class` files there and then performs its
   own atomic redefine, so the RFC's fan-out concern resurfaces through a different door.
   This must be characterised in P0.5: does a plugin watcher act on the daemon's writes
   concurrently with the daemon's redefine?

---

## Architecture

Three components, and one deletion.

### The deletion: no JDWP in v1 **[decided, evidence below]**

Both `Instrumentation.redefineClasses(ClassDefinition[])` and
`VirtualMachine.redefineClasses(Map<ReferenceType,byte[]>)` funnel into the same JVMTI
`RedefineClasses`. Same capability limits, same `UnsupportedOperationException` messages,
same DCEVM/JBR enhancement. **The Phase 0 escalation numbers transfer unchanged** — neither
API buys more reloadability. The choice is plumbing only.

| | `Instrumentation` (in-process) | `VirtualMachine` (JDI, out-of-process) |
|---|---|---|
| Launch requirement | `-javaagent:` | `-agentlib:jdwp` debug port |
| Bytes → class name | not needed, you hold the `Class` | must parse the constant pool |
| Calling `onHotswap` after | direct synchronous call | **cannot** — needs a second channel |
| Observing `onHotswapComplete` | in-process listener | needs that same channel |
| Per-apply cost | 20 ms / 130 ms (see below) | + pusher JVM start + attach handshake |
| Hazard to design around | none | half-open JDWP handshake disables the listener |

The decisive row is the third. C2 means the redefine is inert without `onHotswap`, and JDI
cannot call it — the reference implementation's own comment says *"no JDI invokeMethod
gymnastics — JDI requires event-suspended threads which we don't have"*, so it POSTs to an
`/api/dev/hotswap` endpoint inside the app, best-effort with a fallback. Since an in-process
connector is required anyway (registration, app-liveness, control socket), JDI would add a
second transport without removing the first, and would turn the completion gate from a
synchronous in-process call into a best-effort HTTP hop.

JDI keeps one genuine capability Instrumentation lacks — `ThreadReference.popFrames()`, how
IntelliJ re-runs a method with new code. Not needed here; view re-instantiation comes from
route re-navigation.

**`HotswapPusher` becomes the P6 IDE-led path**, where it is not a fallback but the right
mechanism: you cannot inject `-javaagent` into an already-running JVM (dynamic attach needs
`-XX:+EnableDynamicAgentLoading` and warns on JDK 21+), while an IDE has almost certainly
launched with JDWP already open. Two fixes needed if it is promoted: check
`vm.canRedefineClasses()` before calling, and distinguish `VMDisconnectedException` from a
rejection — those are different terminal outcomes (`Failed(app exited)` vs escalate to
restart).

### Agent provisioning

**Requirement: the daemon provisions HotswapAgent itself, with no user action.** Copilot's
IntelliJ plugin downloads the jar and asks the user to put it in place; that step must
disappear here, because an autonomous agent has nobody to ask.

The design that makes this easy is **`-javaagent:<path>` rather than
`-XX:HotswapAgent=fatjar`**. The `fatjar` form requires the jar to sit at
`<jdk>/lib/hotswap/hotswap-agent.jar` — writing into the JDK installation, which may be
read-only, may need elevation, is per-JDK, and pollutes a shared toolchain. That is
precisely why it needs a human today. With `-javaagent:` the daemon owns the path, so the
jar can live beside its own state and the JDK is never touched. Verified working
**[measured]**: `-javaagent:.vaadin/hotswap-agent-2.0.1.jar` loads HA fully on JBR 25
alongside the connector's own agent.

Requirements for the implementation:

- **Pinned version, verified download.** Pin an exact HA version per daemon release and
  check the downloaded jar against a bundled SHA-256 before first use. Never resolve
  "latest" at runtime — a silently changing agent would make apply outcomes
  irreproducible. Refuse to run on checksum mismatch rather than continuing.
- **Cache under `.vaadin/`,** keyed by version (`hotswap-agent-<version>.jar`), so the
  download happens at most once per project per version and the daemon works offline
  afterwards. Consider a shared user-level cache (`~/.vaadin/`) so a second project
  reuses it.
- **Escape hatches**, because air-gapped and locked-down environments are real: honour a
  configured local path or internal mirror, and use a pre-placed jar if one is already at
  the expected location. A build that cannot reach GitHub must still be able to run.
- **Be visible about the network call.** First run fetches ~2 MB from
  `github.com/HotswapProjects/HotswapAgent/releases`. Log it plainly with the URL and
  version. This is a supply-chain dependency the team is taking on deliberately; it
  should not be a surprise in a CI log.
- **Fail loudly.** Per C8.3, a missing agent with the `fatjar` flag warns and continues
  with exit code 0. If provisioning fails, `start` must fail with a clear reason, not
  quietly launch a JVM without the agent and then report `hot-reload` outcomes that are
  really only partial.
- **Add `.vaadin/` to `.gitignore`.** It is not ignored in this project today, so the
  port file and a 2 MB jar would land in commits. The daemon should ensure the entry on
  first run.

**The daemon owns the whole flag set, not just the jar.** P0.5 showed that a correctly
downloaded agent still degrades silently without JPMS opens, so provisioning means
composing all of this and reporting it in `status`:

```
-javaagent:.vaadin/hotswap-agent-<version>.jar
-XX:+AllowEnhancedClassRedefinition          # JBR only; detect and omit elsewhere
-DdisabledPlugins=Vaadin          # C8 hazard 1: avoid a second refresh path
-Dspring.devtools.restart.enabled=false
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.lang.reflect=ALL-UNNAMED
--add-opens java.base/java.io=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
--add-opens java.desktop/java.beans=ALL-UNNAMED
```

This is the real reason the manual setup is error-prone, and the strongest argument for
the daemon owning the launch: nine flags that must all be right, where getting one wrong
degrades hot reload silently rather than loudly. Keep `autoHotswap` at its default
(`false`) so HA's watcher never competes with the daemon's atomic redefine.

### 1 · `vaadin-dev` — the script

Maven-wrapper-style thin client. Discovers or spawns the daemon, forwards argv over loopback
RPC, streams stdout, propagates the exit code. Recommend loopback TCP with port + random
token in `.vaadin/` over AF_UNIX: portable, and this is a Windows-primary team. Liveness is
`ProcessHandle.of(pid)` plus a token match — process-level, never a socket probe.

Phase 0 already uses `.vaadin/devloop.port` for discovery, which exercises the idea.

### 2 · The daemon — Java, one per project root

Owns the transaction state machine, the compiler, the app process, the RPC surface.

Use **`javax.tools.JavaCompiler` in-process** rather than shelling to Maven: no JVM start per
`apply`, and its `Diagnostic<JavaFileObject>` objects *are* the RFC's `diagnostics[]`
contract — file, line, column, code, message, already structured. Phase 0's harness does this
and it works as advertised. Resolve the classpath once via `dependency:build-classpath`, cache,
invalidate on `pom.xml` mtime.

### 3 · The connector — a jar inside the app

**Four classes, one transport** (a fifth, a second socket for the measurement harnesses, was
removed after P3: the daemon never used it, and its copy of the redefine logic had already
gone stale). The harnesses now use a `vaadin-dev redefine <classes>` diagnostic verb that
returns the connector's raw reply without `apply`'s escalation policy.

Phase 0 built the first three pieces; they become one shipped artifact:

- a `premain` capturing `Instrumentation` (Phase 0 publishes it via a static field *and* the
  system-properties table, which needs no class visibility — keep both);
- a `VaadinServiceInitListener` calling `Hotswapper.register(service)`, guarded so double
  discovery (Spring bean *and* `ServiceLoader`) cannot register two `Hotswapper` instances and
  double every refresh;
- a `VaadinHotswapper` SPI implementation reporting `onHotswapComplete` back to the daemon and
  holding the long-lived registration connection whose closing means "the app is gone".

### The apply path

```
compiling          →  runtime            →  frontend           →  Stable
[daemon]              [connector]           [flow + vite]         [gate]
JavaCompiler,         ONE atomic            Flow regenerates      onHotswapComplete
structured            redefineClasses       layouts; Vite         + verification
diagnostics;          over EVERY loaded     rebuilds.             (C7) — never a
failure ends here     copy (C7), then       Await, don't drive.   timer
                      onHotswap (C2)

escalate ↳ redefine rejected (added method/field on stock JVM, changed hierarchy)
           → Spring-aware restart → Stable after restart, or Failed(reason)
```

---

## Measured data

24 realistic agent-style edits to `TaskListView`, `TaskService`, `Task`, `MainLayout`,
`ViewTitle`; identical edits on both JVMs. **[measured]**

| JVM | Hot-reloaded | Escalates | New class |
|---|---|---|---|
| OpenJDK 25.0.2 (stock) | **16/24 (67%)** | 7 | 1 |
| JBR 25.0.2 `-XX:+AllowEnhancedClassRedefinition` | **22/24 (92%)** | 1 | 1 |

Escalating on stock but not JBR: adding a method, adding a field, adding a lambda. Only
`change-superclass` escalates on both.

| JVM | `redefineClasses` median | `onHotswap` median | Cold restart |
|---|---|---|---|
| Stock JDK 25 | 20 ms (max 43) | 2 ms | ~10.6 s |
| JBR | 130 ms (max 213) | 18 ms | ~10.9 s |

JBR's enhanced redefinition costs ~6× more per call and remains ~70× cheaper than a restart.

**The RFC's flagship example escalates on a stock JDK.** "Add a Status column" reads like a
method-body change but the lambda compiles to a new synthetic method, so stock HotSpot rejects
it (`attempted to add a method`). It hot-reloads only on JBR. Worth fixing in the RFC before
that example reaches documentation.

**What the numbers do not say.** They measure *redefinability*, not correctness. Four edits were
verified in the browser end to end; the other twenty only as "the JVM accepted the bytecode".
`entity-add-field` counts as hot-reloaded on JBR at the bytecode level while Hibernate's
metamodel and the H2 schema certainly did not update; adding a `@Transactional` method to an
already-proxied bean is counted likewise though the proxy would not intercept it. **[open]**

---

## Phases

### P0 · Spike and measurement — **DONE**

Connector + agent + harness in `devloop/`. Exit criteria met: a method-body change went live in
the browser with no restart (drawer/menu DOM refs unchanged, only the view subtree replaced — an
in-place route re-render), and the escalation rate is recorded for both JVMs.

Delivered beyond the criteria: C2 and C3 confirmed in practice, C3 amended, C7 discovered, and
the Instrumentation-vs-JDI question settled.

### P0.5 · What does HotswapAgent actually cover? — **DONE**

Harness: `devloop/harness/P05Harness.java`. Results in
[PHASE0-FINDINGS.md](PHASE0-FINDINGS.md); summarised in C8 above.

Settled:

- **Spring bean changes need HA**, and without it they actively corrupt the runtime rather
  than merely failing to reload. HA is justified as a requirement by evidence.
- **JPA entity changes do not hot reload at all** on Hibernate 7.4.1 — they must escalate
  to restart, and `hot-reload` must never be claimed for them.
- **HA needs five `--add-opens` flags on Java 25**, absent which it degrades silently.
- HA's Vaadin plugin fires its own live reload on every redefine, confirming
  `disabledPlugins=Vaadin`.

Left open: the watcher interaction (C8 hazard 2) did not reproduce across ~10 runs with
`autoHotswap=false`. Treated as unresolved rather than safe; P3 should assert the setting.

### P1 · Daemon lifecycle and CLI skeleton — **DONE**

`vaadin-dev` (bash) plus a JDK-only daemon jar; verbs `status`, `start`, `stop`, `restart`,
`shutdown`, `ping`. All exit criteria met — details in
[PHASE0-FINDINGS.md](PHASE0-FINDINGS.md).

Two decisions taken during implementation:

- **The daemon launches the app JVM directly**, not through `spring-boot:run`. That goal
  forks, making the app a grandchild: exit codes are lost and a kill orphans the JVM, which
  is why Phase 0 had to kill by port. A direct child yields real exit codes (`crashed
  exit=-1`) and a clean stop, and skips ~3 s of Maven startup.
- **The handshake file is `.vaadin/daemon.properties`**, not `daemon.json`, so the bash CLI
  reads port and token with `grep`/`cut` — no JSON parser, no JVM per command. It records
  `startEpochMillis` beside `pid` so a recycled pid is not mistaken for a live daemon.

Also delivered here, because `start` needs it: HotswapAgent provisioning (pinned 2.0.1,
SHA-256 verified, cached under `.vaadin/`, override for air-gapped setups) and the full
nine-flag launch line with JBR auto-detection.

Testing found three defects, all fixed: the auth token was printed to stdout and into the
log; `stopped exit=1` after a deliberate stop read as a failure; and bash leaked
`/dev/tcp: Connection refused` on the stale-daemon path.

### P2 · Transaction engine and the compile leg — **DONE**

`apply` with the compile gate, structured diagnostics, exit codes, `--json`, and the
transaction rules (one in flight, supersede rather than queue, terminal states only).
Exit criterion met; details in [PHASE0-FINDINGS.md](PHASE0-FINDINGS.md).

Verified: the RFC's `cannot find symbol` example with file:line:column and exit 1, the
runtime leg untouched; two concurrent applies giving `Failed(superseded)` exit 4 to one and
a real outcome to the other, both terminal; and a successful apply reaching the browser
(checked there, not inferred). Compile is 0.6–1.5 s in-process; a no-op `apply` costs
0.19 s wall clock end to end.

**Superseded on 2026-08-14** — the artifact comparison alone could not see edits an IDE had
already built, reporting `no changes` for real changes. The daemon now also tracks what it
has made live. See the correction in [PHASE0-FINDINGS.md](PHASE0-FINDINGS.md).

One design correction worth carrying forward: **change detection compares each source
against its compiled artifact, not against a snapshot taken at daemon start.** The
snapshot version made every edit predating the daemon invisible, so the first `apply` after
a daemon restart said `no changes` while `target/classes` was stale — the daemon's
lifecycle leaking into the answer. The artifact comparison is stateless, survives
restarts, and gives "only the latest bytes on disk matter" for free.

Until P3, a successful compile escalates straight to a restart when the app is running, so
`classification` is always `restart` and `Stable` takes ~15 s. That is the honest report,
and it is already the attempt-then-escalate shape with the attempt missing.

Deferred to P3: deleted sources are not detected, because removing a class also needs its
stale artifact cleaned up.

### P3 · The runtime leg — **DONE**

Atomic redefine over every loaded copy → `onHotswap` → `onHotswapComplete`, then escalate
only if it cannot stick. Commands ride the registration connection the connector already
holds, so there is one channel and not two. All exit criteria met; details in
[PHASE0-FINDINGS.md](PHASE0-FINDINGS.md).

**Hot reload is 1.70 s against 14.6 s for the P2 restart** — ~8× on the common case.

Escalation turned out to need three rules, not one, because a *successful* redefine is not
proof (C7, and P0.5's two cases). All three verified:

| Change | JVM verdict | Daemon verdict |
|---|---|---|
| Method body | accepted | `hot-reload` |
| Added method, stock JDK | rejected | `restart: class redefinition failed: attempted to add a method` |
| Entity gains a mapped column | accepted on JBR | `restart`: Hibernate's metamodel is fixed at startup |
| Spring bean gains a member | accepted on JBR | `restart`: the existing proxy would not match |
| Spring bean method **body** | accepted | `hot-reload` — deliberately *not* escalated |

The last two rows are why the rule keys on *structural* change rather than "is a bean":
escalating every bean edit would turn the commonest change from 2 s into 14 s. The
connector fingerprints declared members before the redefine and compares after, so
structural is measured rather than predicted.

**New finding, and a policy change.** Structurally redefining a Spring bean corrupts the
context: HA's scanner loses the Spring Data repository bean
(`NoSuchBeanDefinitionException`, `basePackage … not associated with any scannerAgent`)
while the redefine reports success and the app still answers HTTP 200. HotswapAgent's
`Spring` and `SpringBoot` plugins are therefore **disabled** in the launch line, matching
what `draiv-experiment` already did — now with a measured reason. This sharpens C8: HA is
required for some things and destabilising for others, and it is the daemon's escalation
rules, not HA, that keep `Stable` honest.

> **Corrected 2026-08-14.** The flag P3 shipped for this (`hotswapagent.disablePlugin`) was
> not a real property name, so no plugin was ever disabled — including the Vaadin one, which
> kept firing a full page reload on top of Flow's soft refresh until a user reported it. The
> key is `disabledPlugins`, plural and unprefixed. The escalation rule was doing all the
> protective work on its own; the plugins are only genuinely off now. See the correction
> section in [PHASE0-FINDINGS.md](PHASE0-FINDINGS.md).

**`classification` decision: explicitly deferred.** Flow does not expose the strategy it
computed, so v1 reports only what the daemon itself did (`hot-reload`, `restart`) and does
not claim Flow's `UIRefreshStrategy`. Log scraping was considered and rejected — it would
put a debug-log format into the agent contract. A small Flow API remains the way to close
it.

Known gap: the duplicate-copy condition is not reproducible on demand (route
re-registration alone never triggered it), so the C7 regression test depends on a specific
sequence of structural view edits. A deterministic test wants a connector hook that loads a
second copy on request.

### P4 · Frontend leg, CSS, frontend-down — **DONE**

Both exit criteria met: a CSS-only edit is `hmr` in ~0.03 s with the app pid unchanged and the
new colour verified in the browser; killing Vite leaves the app `running registered=true`,
`status` reports `frontend down:<port>`, and a CSS apply fails in 0.29 s with a named reason
instead of hanging. Details in [PHASE0-FINDINGS.md](PHASE0-FINDINGS.md).

**The plan's assumed mechanism was wrong.** `StyleSheetHotswapper.onResourcesChange` is an
explicit no-op — *"changes in CSS files are handled by a dedicated file watcher"* — so
`update(path, content)` is not reachable via the hotswap SPI at all. CSS belongs to
`PublicResourcesLiveUpdater`, which watches the source roots itself (C6 again, one layer
deeper than expected).

**And that watcher is broken on Windows.** `isVaadinThemeUrl` calls `new File(url).toPath()`
on a `context://…` stylesheet URL; the colon is legal in POSIX and illegal on Windows, so it
throws before updating anything and every CSS edit degrades to a full page reload. Any app
with a relative `@StyleSheet` is affected — worth filing against Flow.

So the daemon's frontend leg is two things Flow does not do:

1. **Copy changed resources to `target/classes`.** Flow watches sources and never refreshes
   the classpath copy, so its fallback reload serves stale bytes. Measured before/after with
   `curl`.
2. **Push CSS content with `BrowserLiveReload.update(url, content)`.** A plain reload loses a
   race the daemon's own speed creates: `Cache-Control: no-cache` plus one-second
   `Last-Modified` granularity means copy-then-reload revalidates to 304, leaving the browser
   reproducibly one generation behind. Pushing content skips HTTP.

**frontend-down** uses `DevModeHandler.getPort()` plus a loopback connect (harmless for an
HTTP port, unlike a JDWP handshake). It distinguishes `no-dev-server(DevBundleBuildingHandler)`
from `starting(ViteHandler)`, because one never resolves and the other does.

Two facts worth carrying into P5 and the docs: **this project runs no Vite at all by default**
(`DevBundleBuildingHandler`), so there is no frontend leg unless
`-Dvaadin.frontend.hotdeploy=true`; and the `mode` string we report at registration
(`DEVELOPMENT_FRONTEND_LIVERELOAD`) says nothing about whether a dev server exists — the
handler kind is the honest signal. The daemon now forwards any `vaadin.*` property it was
started with to the app JVM so such options can be steered.

Remaining gap: with Flow's watcher still forcing a reload on Windows, this is correct and fast
but not yet true no-reload HMR end to end. Nothing in the daemon needs to change when the Flow
bug is fixed. Non-CSS resources are copied but not yet followed by a refresh.

### P5 · Agent enablement

`project_model`, and the `AGENTS.md` / `CLAUDE.md` instructions that are the actual delivery
vehicle — the CLI's token advantage only materialises if the agent learns it once and uses it
tersely.

*Exit:* a fresh agent session, given only the instructions file, completes the RFC's "add a Status
column" task without polling, sleeping, or asking how to check state. Note this task escalates to
a restart on stock JDK — the agent must handle that outcome gracefully.

### P6 · Deferred: IDE-led mode and MCP

Both are additional clients of the same RPC contract. IDE-led uses JDI via `HotswapPusher` with
the two fixes noted above, plus an in-app dispatch endpoint for `onHotswap`.

Blocker to resolve first: in IDE-led mode the daemon does **not** own the launch, so it cannot
pass `-DdisabledPlugins=Vaadin`. If the IDE's HotswapAgent is 25.x-aware (C8 hazard 1),
its plugin and the connector both register a `Hotswapper` and every change is dispatched twice.
Either the connector must skip registration when it detects an IDE-owned session — which is the
ownership model's own rule, since the launcher owns reload — or Flow needs idempotent
registration.

---

## Risks

| Risk | Status | Why it matters |
|---|---|---|
| **Spring bean changes without HA corrupt the runtime** | **measured, mitigated by requiring HA** | P0.5: adding a method to a `@Transactional` bean and calling it NPEs on the stale CGLIB proxy while the redefine reports success. With HA it works. So HA is not an enhancement, it is what keeps `Stable` honest for bean changes — and the daemon must refuse to claim `hot-reload` if HA is not confirmed loaded. |
| **JPA entity changes never hot reload** | **measured, accepted** | `HibernateJakarta` does not initialize on Hibernate 7.4.1; a new mapped column reads back `null`. Entity edits escalate to restart, and the docs must say so. Revisit only if HA gains Hibernate 7 support. |
| **HA degrades silently without JPMS flags** | **measured, mitigated** | Missing `--add-opens` breaks HA's `ClassLoaderHelper`, `JdkPlugin` and `ProxyPlugin`, all logged and swallowed, while some tests still pass. The daemon owns the flag set; `status` must report it. |
| **HA as a supply-chain dependency** | new, accepted | The daemon will download a 2 MB agent from GitHub releases on first run. Needs a pinned version, a verified checksum, an offline/mirror escape hatch, and a visible log line. Also means the daemon's hot-reload behaviour is coupled to a third-party release cadence that has already lagged this stack. |
| **HA watchers vs the daemon's redefine** | new, open | HA registered six watchers on `target/classes` with `autoHotswap=false`. The daemon writes class files there and then redefines them itself, so a plugin watcher may act concurrently — the RFC's fan-out race through a different door. P0.5 characterises it. |
| **Flow's CSS hot update is broken on Windows** | **measured, worked around** | `PublicResourcesLiveUpdater.isVaadinThemeUrl` throws `InvalidPathException` on any `context:` stylesheet URL, so every CSS edit degrades to a page reload. The daemon works around it by refreshing the classpath copy and pushing content directly, but true in-place HMR needs the Flow fix. |
| **A 25.x-aware HA double-notifies** | **likely, mitigated only when we own the launch** | HA reaches Vaadin by reflection over two hard-coded 24.x strings (C8.2), so making it fire is a patch, not a port — and Copilot ships its own HA jar. Then two `Hotswapper` instances dispatch every change, each with its own refresh, one of them timer-debounced. `disabledPlugins=Vaadin` fixes it for daemon-launched apps and cannot fix it for IDE-led ones (P6). |
| **Silent stale success (C7)** | **found, fixed, must stay a requirement** | The failure mode is invisible without browser verification and reports `Stable`. Needs the all-copies redefine, the duplicate count in the result, and a browser-level regression test. |
| **Spring-managed types carry no annotation** | **measured, fixed** | Adding `Task findOne(Long id)` to a Spring Data repository reported `Stable` on an app that cannot even start with it (`No property 'findOne' found for type 'Task'`). The interface is registered by `@EnableJpaRepositories`, so an annotation-based bean check sees nothing, and its live bean is a proxy built from the old interface. Classification now looks for a loaded proxy over the changed type — `proxied=` in the redefine reply — which needs no Spring on the connector's classpath and covers CGLIB bean proxies too. |
| **Redefinition scope** | **measured** | 67% stock vs 92% JBR. No longer an unknown; it is now a product decision (below). On stock, every structural edit escalates — including the RFC's own example. |
| **Contract sits on internal API** | open | `Hotswapper` and `VaadinHotswapper` are "For internal use only. May be renamed or removed", and C1 shows the package already moved once. C3's amendment adds that the strategy is not exposed at all. Needs a Flow-team agreement to stabilise and extend, or version-pinned adapters plus a compatibility matrix. |
| **JBR availability** | **resolved for this machine** | `jbr-25.0.2` is installed and accepts `-XX:+AllowEnhancedClassRedefinition`. Remains a distribution question for users, not a technical one. |
| **Bash-only on a Windows team** | open | The RFC specifies one Bash script; the primary dev machine is Windows 11. Ship a `.cmd` too, or state Git Bash as a requirement. |

---

## Open decisions

**1 · Do we require JBR, recommend it, or stay JVM-agnostic?** New, and the most consequential.
67% vs 92% is the difference between hot reload being the common case and the exception; on stock
JDK every added method or field costs a ~10 s restart.
*Recommendation:* run on any JVM, detect the JVM at launch, and state it in `status` and in the
`apply` result so the agent and the developer know which regime they are in. Recommend JBR in the
docs rather than requiring it — but only after the Spring/JPA question is settled, since if most
structural edits need a Spring restart anyway, JBR's advantage shrinks and the case for requiring
it collapses.

Note this is a question about the **JVM**, not about HotswapAgent: per C8, enhanced redefinition
comes from `-XX:+AllowEnhancedClassRedefinition` alone.

**HotswapAgent itself is no longer an open decision — it is required and auto-provisioned**
(C8, [Agent provisioning](#agent-provisioning)). That does tilt this decision, though: HA's
Spring plugins are what make library-level hotswapping work, and they are most valuable on a JVM
that can also apply structural redefinitions. Requiring HA while leaving the JVM to chance means
the two halves of the feature can be present or absent independently — so `status` must report
both, and the docs should present JBR + HA as the supported configuration.

**2 · What does "restart" mean concretely?** Still open, but P0.5 narrowed what has to escalate:
entity/mapping changes always, and anything touching a Spring bean when HA is not confirmed
loaded.
*Recommendation:* full JVM restart for v1 — honest, predictable, composes with the daemon owning
the launch. Devtools' restart classloader is faster but fights hotswap agents and muddies
ownership (and the daemon already sets `spring.devtools.restart.enabled=false`).

**5 · Should `apply` pre-empt the Spring-proxy trap?** New, from P0.5. A structural change to a
Spring bean is only safe because HA fixes the proxy; without HA it corrupts the app while
reporting success.
*Recommendation:* have the connector report at startup whether HA is loaded and which plugins
initialized, and have the daemon escalate structural bean changes to restart when it is not.
Never rely on the redefine's return value for this class of change — P0.5 proved it lies.

**3 · Where does the code live?**
*Recommendation:* the Phase 0 connector graduates to a real module shipped near
`vaadin-dev-server`, since C2, C3 and C7 make it effectively a Flow extension-point consumer —
and C3's amendment means it needs a Flow-side change anyway.

**4 · `classification` source.** Resolved into P3's exit criteria: either Flow exposes the
computed strategy, or v1 ships without a faithful `classification` and says so. Log scraping must
not become the contract.
