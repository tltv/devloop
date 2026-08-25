# Integrating the `vaadin-dev` CLI into Flow

## Context

The `vaadin-dev` prototype at `https://github.com/tltv/devloop/tree/main` is a
long-running daemon that owns one Vaadin application's edit-to-running-app loop
— background compilation, hot-swap or restart, browser refresh, error reporting
— and answers, authoritatively, "what is the state of my last change?" It exists
to make the agentic development loop of a Flow application faster and cheaper: an
agent batches edits, runs one `apply`, and reads a verdict from the exit code
instead of rebuilding, re-launching and guessing from screenshots.

It works today, but it lives outside Flow: a private `flow-devloop` artifact the
user's pom must declare, a daemon jar built from a sibling module and staged into
`.vaadin/`, a java agent whose source is carried inline in a bash heredoc and
compiled on demand, and a hard-coded application main class. It is Maven +
Spring Boot only; Gradle and Jetty come later.

This plan decides where each piece lands in the Flow repository, how the move
lets us delete code rather than carry it, and where the tests go. The code to be
integrated is ~6,000 hand-written lines: 958 (connector) + 4,208 (daemon) +
445 (bash CLI) + ~380 (demo app). The prototype's measurement harnesses
(`devloop-daemon/harness/`, 692 lines) are **out of scope** and are not ported.

**Scope note:** this plan covers placement and the simplifications the placement
enables. It does not add Gradle or Jetty support — it puts the build-tool-facing
code where that support can later be added without moving anything again.

---

## Decisions at a glance

| Prototype piece | Goes to |
|---|---|
| `flow-devloop` connector (958 LoC) | `vaadin-dev-server`, package `com.vaadin.base.devserver.devloop` |
| `devloop-daemon` (4,208 LoC) | new top-level module `flow-devloop-daemon`, `com.vaadin.flow.devloop.daemon` |
| `DevLoopAgent` (inline in bash) | `flow-devloop-daemon`, `com.vaadin.flow.devloop.agent` — daemon jar doubles as the javaagent |
| `vaadin-dev` script + `.ps1` + `.cmd` | resources in `flow-plugins/flow-plugin-base`, installed to `<project>/vaadin/` |
| `.agents/skills/vaadin-devloop/**` (canonical) | same resources, installed to `<project>/.agents/skills/vaadin-devloop/` |
| `.claude/skills/vaadin-devloop/SKILL.md` (adapter) | same resources, installed to `<project>/.claude/skills/vaadin-devloop/` |
| The installer | `DevCliInstaller` in `flow-plugin-base` + thin `InstallDevCliMojo` (goal `install-dev-cli`) |
| `demo-app` + `demo-shared` | `flow-tests/test-devloop/{devloop-app,devloop-shared}` |
| `PLAN.md` / `PHASE0-FINDINGS.md` | not ported |
| `harness/*.java` | not ported |
| Documentation | one short `README.md` per new module (§9.1) |

---

## 1. Connector → `vaadin-dev-server`

The connector implements `VaadinHotswapper`, which **already lives in
`vaadin-dev-server`** (`com.vaadin.base.devserver.hotswap`). Putting it in the
same module means no new artifact, no BOM entry, and — because every starter
already declares `com.vaadin:vaadin-dev` — **no change to any user pom**.

New package `vaadin-dev-server/src/main/java/com/vaadin/base/devserver/devloop/`:

- `DevLoopHotswapper` (112) — keep; registered via the existing
  `META-INF/services/com.vaadin.base.devserver.hotswap.VaadinHotswapper`.
- `DevLoopRedefiner` (531) — keep; this is the load-bearing part (collects
  *every* loaded `Class` per binary name and issues one atomic
  `Instrumentation.redefineClasses`).
- `DevLoopRegistration` (106) — keep. Replace its `System.out` calls with SLF4J,
  which is available here; the root pom's enforcer bans `java.util.logging`.

Two files are deleted rather than moved:

- **`DevLoopServiceInitListener` (73) — delete.** It exists only to call
  `Hotswapper.register(service)`, because nothing in Flow does — which is why a
  redefine is inert without it. Now that we are inside
  `vaadin-dev-server`, fix it at the source: register the hotswapper from
  `DevModeHandlerManagerImpl` / `startup/DevModeInitializer`. That repairs
  hot-swap for everyone, not just the dev loop, and removes a
  `VaadinServiceInitListener` service registration from the app's startup path.
- **`FlowResourceWatcherSuppressor` (136) — delete.** It reflectively reaches
  into `PublicResourcesLiveUpdater` to stop it, because that class was in another
  jar. It is now a sibling: add a package-private/`@Internal` off-switch on
  `PublicResourcesLiveUpdater` and call it directly. While there, fix the
  underlying Windows bug the workaround was papering over —
  `isVaadinThemeUrl` does `new File(url).toPath()` on a `context://` URL and
  throws `InvalidPathException`, degrading a CSS push to a full page reload.

Tests go in `vaadin-dev-server/src/test/java/com/vaadin/base/devserver/devloop/`,
beside the existing `HotswapperTest` / `StyleSheetHotswapperTest`.

---

## 2. Daemon → new top-level module `flow-devloop-daemon`

`flow-devloop-daemon/` at the repo root, `com.vaadin:flow-devloop-daemon`,
packaging `jar`, parent `flow-project`. Package
`com.vaadin.flow.devloop.daemon` (from `com.vaadin.devloop.daemon`).

It stays a separate module rather than folding into `vaadin-dev-server` because
its defining constraint is structural: **zero dependencies**, so it starts fast
and can never drag the application's classpath into the daemon JVM. A module
boundary enforces that; a package convention would not.

Files move essentially as-is: `Daemon` (476), `TransactionEngine` (749),
`Compile` (545), `Launch` (1025), `AppProcess` (337), `AppLog` (435),
`Reactor` (381), `Connector` (110), `Handshake` (109), `Json` (41).

`flow-devloop-daemon/pom.xml`:

- `<bnd.skip>true</bnd.skip>` — not an OSGi bundle.
- `<flow.apicmp.skip>true</flow.apicmp.skip>` — no public API contract.
- `maven-jar-plugin` manifest: `Main-Class: com.vaadin.flow.devloop.daemon.Daemon`
  **plus** `Premain-Class` / `Agent-Class: com.vaadin.flow.devloop.agent.DevLoopAgent`,
  `Can-Redefine-Classes: true`, `Can-Retransform-Classes: true` — see §7.
- A `maven-enforcer-plugin` `RestrictImports` execution banning `com.vaadin.**`,
  `org.slf4j.**`, `jakarta.**`, `org.springframework.**` in `src/main`, so the
  dependency-free rule is checked by the build. (The root pom's
  `java.util.logging` ban is satisfied: the daemon writes to `System.out`, which
  is correct here — its stdout *is* `target/devloop/daemon.log`.)

Also required, because this is a new module:
`<modules>` in the root `pom.xml`; an entry in `flow-bom/pom.xml` (it must be
published — the script resolves it from the repository); `mvn spotless:apply`
plus the Apache license header on all 4,208 lines; and a `moduleWeights` entry
in `scripts/computeMatrix.js`.

**Java level:** the prototype targets 25; the repo baseline is
`maven.compiler.release=21`. Virtual threads and `ProcessHandle` are fine at 21
— audit for any 22+ API during the move.

### How it reaches the app's dev classpath

`vaadin-dev-server/pom.xml` declares it as an ordinary compile-scope dependency:

```xml
<dependency>
  <groupId>com.vaadin</groupId>
  <artifactId>flow-devloop-daemon</artifactId>
  <version>${project.version}</version>
</dependency>
```

It then travels exactly the route `vaadin-dev-server` itself travels — in via the
`com.vaadin:vaadin-dev` optional dependency every starter declares, and out of
production builds by the same mechanism that already excludes the dev server.
No new exclusion mechanism is invented, and nothing is ever staged into the
project.

> Verify during implementation: confirm in a generated starter (or the platform
> repo) exactly how `vaadin-dev` is kept out of the production artifact, and that
> `flow-devloop-daemon` inherits it. If the guarantee turns out to be weaker than
> assumed, the fallback is to declare it `provided` in the *application* archetype
> rather than transitively.

---

## 3. Daemon jar discovery — the script resolves it, cached

No copying into `.vaadin/`. On first use the script asks Maven once for the jar
and caches the answer:

```
mvn -q dependency:build-classpath \
    -Dmdep.includeArtifactIds=flow-devloop-daemon \
    -Dmdep.outputFile=target/devloop/daemon-jar.txt
```

Cached at `target/devloop/daemon-jar.txt` and invalidated by the **pom stamp the
daemon already maintains** (`Launch.stampFile()` / `currentStamp()` — a hash over
every pom in the reactor), so a Vaadin version bump re-resolves and nothing else
does. Every later `vaadin-dev status` reads a one-line file and costs
milliseconds, which is the whole point of the bash/`/dev/tcp` design.

Overrides, in order: `-Dvaadin.dev.daemonJar` → `VAADIN_DEV_HOME` (a directory
containing `flow-devloop-daemon.jar`) → the cache → the Maven resolve. If the
resolve finds nothing, fail with a direct message: add `com.vaadin:vaadin-dev`
to the project.

`mdep.includeArtifactIds` must resolve across scopes, and the daemon's *own*
app-classpath resolve must not include `provided`/`test` — the two resolves use
different scope settings and must be kept distinct.

**Runtime file layout** (no `.vaadin/` staging, but the handshake stays there
deliberately):

- `<app>/.vaadin/daemon.properties` — the handshake (port, token, pid). Kept
  outside `target/` on purpose: `mvn clean` must not orphan a running daemon and
  leave the script spawning a second one to fight for port 8080.
- `<app>/target/devloop/` — `daemon.log`, `app.log`, `cp.txt`,
  `daemon-jar.txt`, the pom stamp, `jvm-args.txt`.
- `<module>/target/devloop/cp.txt` — each in-loop module's compile classpath.

---

## 4. CLI script + skills → `flow-plugin-base` resources, installed by a new goal

The payload ships as classpath resources in the module that already holds logic
shared by the Maven **and** Gradle plugins, so the future Gradle task reads the
same bytes from the same jar:

```
flow-plugins/flow-plugin-base/src/main/
  java/com/vaadin/flow/plugin/base/DevCliInstaller.java
  resources/vaadin-dev-cli/
    vaadin-dev             ->  <project>/vaadin/vaadin-dev
    vaadin-dev.ps1         ->  <project>/vaadin/vaadin-dev.ps1
    vaadin-dev.cmd         ->  <project>/vaadin/vaadin-dev.cmd
    agents-skill/vaadin-devloop/
      SKILL.md             ->  <project>/.agents/skills/vaadin-devloop/SKILL.md
      reference.md         ->  <project>/.agents/skills/vaadin-devloop/reference.md
    claude-skill/vaadin-devloop/
      SKILL.md             ->  <project>/.claude/skills/vaadin-devloop/SKILL.md
```

### The skills are two-tier, and both tiers are installed

The prototype now supports Codex as well as Claude Code, and it does so by
splitting the skill rather than duplicating it:

- **`.agents/skills/vaadin-devloop/`** holds the canonical, deliberately
  **tool-agnostic** instructions — `SKILL.md` (the cycle, the command set, what
  is in the loop, how to read an `apply` outcome) and `reference.md` (the full
  output vocabulary, which edits need a reload, pom/classpath semantics, the
  `--json` schema, environment variables, troubleshooting). This is what Codex
  reads, and it is the single source of truth for the loop's behaviour.
- **`.claude/skills/vaadin-devloop/SKILL.md`** is a thin adapter: the frontmatter
  Claude Code requires (`name`, `description`, `when_to_use`,
  `allowed-tools`), an instruction to read the shared file, and a
  *Bindings for this session* section naming the Claude-specific tooling — the
  Playwright MCP verbs for browser verification and the Vaadin MCP server for
  API lookups. It carries no `reference.md` of its own; it links to the
  `.agents` one by relative path.

Both trees must therefore be installed **as siblings under the same directory**,
or the adapter's `../../../.agents/skills/vaadin-devloop/SKILL.md` link breaks.
`DevCliInstaller` installs everything relative to `${project.basedir}` of the
module the goal runs on, which guarantees that. For a single-module application
that is the project root; for a reactor, invoke the goal on the application
module (`-pl :app`), which also matches the script's own `SCRIPT_APP` model and
the skill's `./vaadin/vaadin-dev` invocations. Expose a `<targetDirectory>`
parameter for teams that want the agent trees at the reactor root instead.

Two content changes are needed while moving the skill files: every command
example becomes **`./vaadin/vaadin-dev`** rather than `./vaadin-dev` (the script
is no longer beside the app root), and `allowed-tools` must cover the Windows
entry points as well — `Bash(./vaadin/vaadin-dev *), Read`.

`demo-app/.codex/config.toml` in the prototype registers the Vaadin docs and
Playwright MCP servers for Codex. **The goal does not write it.** Enabling MCP
servers in someone's agent configuration is a side effect a build goal should
not have; document the required servers in the shared `SKILL.md` instead, and
revisit only if an explicit opt-in flag is wanted.

`DevCliInstaller` copies **only** these files — nothing generated, no jars.
Model it on `TaskInstallFrontendBuildPlugins`
(`flow-build-tools/.../frontend/TaskInstallFrontendBuildPlugins.java`), which
already does "read a manifest of files from my own jar resources, stream each to
the target, don't clobber a developer's local edits", and use
`FileIOUtils.writeIfChanged`. Semantics: write if absent or byte-identical to a
previously shipped version; if locally modified, skip and warn, unless
`-Dvaadin.devcli.overwrite=true`. Set the executable bit on POSIX after copying
— a resource stream does not carry it.

The Mojo is thin, following `CleanFrontendMojo`:

```java
// flow-plugins/flow-maven-plugin/src/main/java/com/vaadin/flow/plugin/maven/InstallDevCliMojo.java
@Mojo(name = "install-dev-cli")           // no default phase: explicitly invoked
public class InstallDevCliMojo extends FlowModeAbstractMojo {
    @Override
    protected void executeInternal() throws MojoFailureException {
        DevCliInstaller.install(this, overwrite);
    }
}
```

`mvn vaadin:install-dev-cli` (goal prefix is `flow` inside this repo,
`vaadin` via the platform plugin wrapper). Unbound, like `convert-polymer`, so
it never runs as a side effect of a normal build.

Two consequences for the script itself:

- Its "am I staged in `.vaadin`?" special case becomes "am I in `vaadin/`?" —
  `SCRIPT_APP` is the parent of the directory holding the script.
- The `.gitattributes` at the repo root needs rules so `vaadin-dev` and
  `vaadin-dev.ps1` stay LF and `vaadin-dev.cmd` stays CRLF, both in this repo
  and (documented) in the user's.

All installed files — `vaadin/`, `.agents/skills/` and `.claude/skills/` — are
meant to be **committed** by the user: they are project tooling, like `mvnw`,
and the whole point is that every agent and every developer on the repository
gets the same instructions. No `.gitignore` entries. (Note that agent
*configuration* is a different matter: `.claude/settings.local.json` and
`.codex/config.toml` are per-developer and are neither installed nor committed.)

---

## 5. Windows CLI

Ship three files. `vaadin-dev` (bash, 445 lines) is the reference
implementation and moves unchanged apart from the `vaadin/` directory rule and
the removed agent bootstrap. `vaadin-dev.ps1` is a port using
`System.Net.Sockets.TcpClient` in place of bash's `/dev/tcp`, and must reproduce:
the handshake-file read, the one-line `<token> <verb> <args>` request, streaming
`> ` progress lines, the terminating `EXIT <code>` becoming the process exit
code, the `--app` argument stripping, the spinner gated on
`VAADIN_DEV_PROGRESS`, and the exit-99 stale-handshake respawn-and-retry.
`vaadin-dev.cmd` is a shim invoking the `.ps1` with
`-ExecutionPolicy Bypass -File`.

The exit-code contract is the part agents depend on (`0` live, `1` failed,
`4` superseded, `64` usage, `70` internal, `77` unauthorized), so it needs a
test of its own — see §8.

---

## 6. Application and tests → `flow-tests/test-devloop`

`flow-tests/README.md` says most tests belong in `test-default` and that new
modules follow the target structure, but also that tests needing "irreducible
special infrastructure" keep their own module. A dev loop qualifies twice over:
the interesting case is **multi-module**, which needs its own Maven reactor, and
the daemon **owns the application process**, so the usual
`spring-boot-maven-plugin start/stop` IT lifecycle cannot be used.

```
flow-tests/test-devloop/
  pom.xml                    aggregator — this is the reactor under test
  devloop-shared/            sibling library that exists to be edited
    src/main/java/.../DueDateFormatter.java
    src/main/resources/META-INF/resources/task-list.css
  devloop-app/               Spring Boot + Vaadin app (the demo-app equivalent)
    src/main/java/.../Application.java, views, service, repository
    src/main/java/.../mutable/     classes the ITs rewrite
    src/test/java/.../DevLoopApplyIT, DevLoopRestartIT,
                        DevLoopCssIT, DevLoopPomEditIT,
                        DevLoopMultiModuleIT
```

Notes that shape the module:

- `devloop-app` parents to `flow-tests` for infrastructure but must carry its own
  Spring Boot configuration, since it stands in for a user application.
- Its build runs `install-dev-cli`, which dogfoods the goal and makes the
  installed script the one the ITs drive.
- The IT lifecycle replaces jetty/spring-boot start-stop with
  `exec-maven-plugin`: `vaadin-dev start` at `pre-integration-test`,
  `vaadin-dev shutdown` at `post-integration-test`.
- ITs mutate real sources. Confine edits to a dedicated `mutable` package, copy
  a variant file over the original, and revert in `@After`, so a failed run
  never leaves the working tree dirty. `SpringDevToolsReloadUtils` in
  `vaadin-spring-tests/test-spring-boot-reload-time` is the closest existing
  precedent for a reload IT.
- Mark them `@NotThreadSafe` and `SlowTests`, and put the module in the
  `slow-tests`/`nightly` profile — a cold start is ~30 s and each IT restarts a
  JVM. Add a `moduleWeights` entry in `scripts/computeMatrix.js`.

What each IT proves: `apply` hot-swaps a view edit without a reload; a
structural edit escalates to restart; a CSS edit pushes in place; a `pom.xml`
edit that moves the app classpath restarts and that one that moves nothing
reports `no changes`; an edit in `devloop-shared` reaches the running page.

---

## 7. Simplifications the move enables

These are the point of integrating rather than vendoring. Roughly **300 lines of
Java and 90 lines of bash deleted**, plus two real bugs fixed.

1. **The java agent becomes a real class.** `DevLoopAgent` moves from a bash
   heredoc to `flow-devloop-daemon/src/main/java/com/vaadin/flow/devloop/agent/`,
   and the daemon jar carries `Premain-Class`/`Agent-Class` alongside
   `Main-Class` — a jar can be both an executable and a javaagent. One artifact,
   one resolved path, and the script loses ~90 lines of `javac` + `jar`
   bootstrap. (Trade-off: `-javaagent:` appends the daemon jar to the app JVM's
   system class path. Only the agent class loads, and HotswapAgent already sets
   this precedent at 2 MB. If it proves a problem, embed a minimal agent jar as
   a resource inside the daemon jar and extract it to `target/devloop/`.)
2. **`FlowResourceWatcherSuppressor` deleted** (136 lines of reflection) — see §1.
3. **`DevLoopServiceInitListener` deleted** (73 lines) — the hotswapper
   registration moves to `DevModeHandlerManagerImpl`, so hot-swap works for
   every Flow user rather than only when the dev loop is installed.
4. **The hard-coded main class goes.** `Daemon.MAIN_CLASS =
   "com.dev.vaadin.example.Application"` (used once, at `AppProcess:172`) is
   replaced by discovery in the daemon: read `Start-Class`/`Main-Class` from the
   app's build output, else scan `target/classes` for `@SpringBootApplication`,
   else for a `public static void main`. Overridable with
   `-Dvaadin.dev.mainClass`.
5. **HotswapAgent provisioning.** Keep the pinned, SHA-256-verified download, but
   move the destination from `<app>/.vaadin/` to a machine-level cache under
   `~/.vaadin/devloop/` — the directory `stats/ProjectHelpers` already uses. One
   download per machine instead of one per application, and nothing written into
   the project. (Alternative worth evaluating: if
   `org.hotswapagent:hotswap-agent` is on Maven Central, declaring it like the
   daemon jar and resolving it the same way deletes ~80 more lines from
   `Launch.java`. Verify the coordinates before committing to this.)
6. **`Reactor.java` vs `MavenUtils`.** The daemon's 381-line pom walker overlaps
   `vaadin-dev-server`'s `MavenUtils` (`parsePomFile`,
   `getParentPomOfMultiModuleProject`, `getModuleFolders`). The dependency
   direction now runs `vaadin-dev-server → flow-devloop-daemon`, so the shared
   pom-reading utility can live in the daemon and `MavenUtils` delegate to it.
   Treat as a follow-up, not a prerequisite: `MavenUtils` has its own callers and
   test fixtures under
   `vaadin-dev-server/src/test/resources/com/vaadin/base/devserver/maven/`.

What is deliberately **not** simplified: `Json.java` (41 lines of hand-rolled
JSON) stays, because the daemon must remain dependency-free; and the reactor /
module-discovery heuristics stay in the daemon, since the install goal generates
nothing for them to read.

---

## 8. Tests to add

- `flow-devloop-daemon/src/test/java/` — the daemon has **zero** tests today.
  Add focused unit tests for the pure logic only: `Json`, `Handshake`
  (write/read, stale-pid reaping), `Launch.membership` and classpath-drift
  comparison, `Compile` change-set grouping by module, `AppLog` failure-reason
  extraction, and `Reactor` discovery against pom fixtures (mirror
  `vaadin-dev-server`'s `maven/{standard,complex}-multimodule` resources).
- `vaadin-dev-server/src/test/java/com/vaadin/base/devserver/devloop/` —
  `DevLoopRedefiner`'s multiple-loaded-copies handling and the `OK`/`ERR`
  protocol vocabulary; `DevLoopRegistration`'s no-op path when the daemon
  properties are absent.
- `flow-plugins/flow-maven-plugin/src/it/install-dev-cli/` — an invoker IT on the
  existing `maven-invoker-plugin` harness: the goal creates `vaadin/vaadin-dev*`,
  `.agents/skills/vaadin-devloop/{SKILL.md,reference.md}` and
  `.claude/skills/vaadin-devloop/SKILL.md`; the adapter's relative link to the
  `.agents` copy resolves; the goal is idempotent; and it does not clobber a
  locally modified `SKILL.md` without `overwrite`.
- `flow-tests/test-devloop/devloop-app/src/test/java/` — the end-to-end ITs (§6).
- A cross-shell check that `vaadin-dev` and `vaadin-dev.ps1` return the same
  exit code for the same verb, so the contract the skill documents holds on both.

The prototype's measurement harnesses (`DevLoopHarness`, `P05Harness`,
`DaemonClient`) are not ported. They measure hot-swap escalation rates by
driving the daemon's raw `redefine` verb, bypassing `apply`'s escalation policy
and deliberately leaving the app in states `apply` would refuse — useful for the
prototype's JVM comparisons, but not something Flow should carry or maintain.

---

## 9. Suggested order

1. `flow-devloop-daemon` module lands: move, rename packages, spotless + license
   headers, Java 21 audit, enforcer rule, agent class + manifest, main-class
   discovery, unit tests. Root pom, `flow-bom`, `computeMatrix.js`.
2. Connector into `vaadin-dev-server`: move three classes, delete two, register
   the hotswapper from `DevModeHandlerManagerImpl`, fix the Windows
   `isVaadinThemeUrl` bug, add tests.
3. `DevCliInstaller` + resources + `install-dev-cli` goal + invoker IT. Script
   updated for `vaadin/` and jar discovery; skill files updated for
   `./vaadin/vaadin-dev` and split into the `.agents` canonical pair plus the
   `.claude` adapter.
4. `vaadin-dev.ps1` + `.cmd` + `.gitattributes`.
5. `flow-tests/test-devloop` app, shared module, ITs, CI wiring.
6. Documentation (§9.1).

Steps 1–2 are independent of 3–5 and can land separately; the loop is not
usable end to end until 3 is in.

### 9.1 Documentation

The prototype's two design documents — `devloop-daemon/PLAN.md` (52 KB) and
`PHASE0-FINDINGS.md` (38 KB) — are **not ported**. They are a record of how the
prototype was arrived at: an RFC reviewed against the 25.2.6 source, corrections
C1–C8, phase-by-phase evidence. That belongs in the pull request that lands this
work, not in the repository, where it would rot against the code it describes.

What replaces them is one **short** `README.md` per new module, written for
someone who has to change the code:

- **`flow-devloop-daemon/README.md`** — what the daemon is and the one rule that
  shapes it (zero dependencies, so it starts fast and never drags the app's
  classpath in); the three communication channels (CLI ↔ daemon over loopback
  TCP with the handshake file, daemon ↔ in-app connector over the long-lived
  registration socket, and the files under `target/devloop/`); the wire verbs and
  the `OK`/`ERR` reply grammar; the outcome vocabulary and exit codes; the
  `vaadin.dev.*` knobs; and a **Known limits** section. The limits are the one
  thing worth carrying over from the prototype's findings, because they are
  operational facts a maintainer will otherwise rediscover the hard way: JPA
  entity mappings do not hot-reload with or without HotswapAgent; a structural
  change to a proxied Spring bean must escalate to restart; hot-swap coverage
  differs sharply between stock HotSpot and a JBR; a sibling module contributing
  routes, `@JsModule` or `@NpmPackage` needs a restart rather than an `apply`.
- **`flow-tests/test-devloop/README.md`** — why this module has its own reactor
  and its own two sub-modules, how the IT lifecycle differs from every other
  `flow-tests` module (the daemon owns the app process, so `vaadin-dev
  start`/`shutdown` replace the container start/stop), the patch-and-revert rule
  for ITs that mutate real sources, and how to drive the loop by hand there.

Two existing documents get a short pointer rather than a new file: a section in
`vaadin-dev-server/README.md` for the connector package, since the connector is
not a module of its own, and a line in the root `CLAUDE.md` naming the new
module so the next agent working in this repository can find it.

The user-facing documentation is the shipped skill itself — `SKILL.md` plus
`reference.md` in §4 — and is not duplicated in any README.

---

## 10. Verification

```bash
# Build and unit-test the two moved halves
mvn -pl flow-devloop-daemon,vaadin-dev-server -am clean install
mvn -pl flow-devloop-daemon test
mvn -pl vaadin-dev-server test -Dtest='DevLoop*Test'

# The install goal, against real generated projects
mvn -pl flow-plugins/flow-maven-plugin verify   # maven-invoker src/it

# Formatting and structure gates that a new module must pass
mvn spotless:check
mvn -pl flow-devloop-daemon enforcer:enforce     # dependency-free check

# End to end, by hand, in the new test app
cd flow-tests/test-devloop/devloop-app
mvn vaadin:install-dev-cli
#   expect exactly: vaadin/vaadin-dev{,.ps1,.cmd}
#                   .agents/skills/vaadin-devloop/{SKILL.md,reference.md}
#                   .claude/skills/vaadin-devloop/SKILL.md   (and no daemon jar)
./vaadin/vaadin-dev status          # expect: stopped, in milliseconds
./vaadin/vaadin-dev start           # blocks until serving or failed
#   edit a view under src/main/java, and a CSS file under
#   src/main/resources/META-INF/resources
./vaadin/vaadin-dev apply           # expect exit 0 + "hot-reload:" / "hmr:"
#   edit ../devloop-shared/src/main/java/.../DueDateFormatter.java
./vaadin/vaadin-dev apply           # expect the change-set to name ../devloop-shared
./vaadin/vaadin-dev status --json
./vaadin/vaadin-dev shutdown

# Same sequence through the Windows CLI, comparing exit codes
.\vaadin\vaadin-dev.cmd status

# The ITs
mvn -pl flow-tests/test-devloop/devloop-app verify -Pslow-tests
```

Confirm in the browser (Playwright, per the skill's reference) that a Java
hot-swap and a CSS push land in an already-open page without a reload — the exit
code proves the bytes are live, only the browser proves the UI renders.

Finally, confirm production hygiene: build `devloop-app` in production mode and
assert `flow-devloop-daemon` is absent from the packaged artifact.

## Open items to verify during implementation

- Exactly how `com.vaadin:vaadin-dev` is excluded from production builds, and
  that `flow-devloop-daemon` inherits it (§2).
- Whether `org.hotswapagent:hotswap-agent` is on Maven Central at the pinned
  2.0.1, which would let us delete the download-and-checksum path (§7.5).
- Any Java 22+ API in the 4,208 daemon lines, which target 25 today (§2).
- `dependency:build-classpath` scope defaults for the two different resolves —
  the daemon-jar lookup needs `provided`, the app classpath must not have it (§3).
