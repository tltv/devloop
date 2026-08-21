# Vaadin dev-loop daemon

A long-running daemon that owns one Vaadin application's edit-to-running-app loop —
background compilation, hot reload, browser refresh, error reporting — and answers,
authoritatively, "what is the state of my last change?" Driven by a `vaadin-dev` CLI, so
an agent or a developer gets the same answer with the same command.

Design and evidence live in [devloop-daemon/PLAN.md](devloop-daemon/PLAN.md) and
[devloop-daemon/PHASE0-FINDINGS.md](devloop-daemon/PHASE0-FINDINGS.md).

## Modules

| Module | What it is |
|---|---|
| `flow-devloop` | The in-app connector: registers Flow's `Hotswapper`, redefines every loaded copy of a changed class, and holds the connection the daemon drives. No Spring dependency. |
| `devloop-daemon` | The daemon — transactions, compile leg, app process, local RPC. JDK-only. |
| `demo-shared` | A dependency-free library module `demo-app` depends on, standing in for the domain or service module a real project keeps beside its application. It exists to be edited. |
| `demo-app` | A Vaadin application, standing in for any user application, with the `vaadin-dev` CLI and the Maven wrapper. |

The daemon serves one application and knows nothing about this repository's layout: it finds
the reactor above the application by reading poms, and asks Maven itself which modules that
application depends on.

## Using it

```bash
cd demo-app
./mvnw -f ../pom.xml -DskipTests install   # all modules; needs network for the frontend build
./vaadin-dev start                         # daemon auto-spawns and launches the app
# edit some Java or CSS, then:
./vaadin-dev apply                         # blocks until Stable or Failed; exit code is the outcome
./vaadin-dev status --json
./vaadin-dev shutdown
```

Run Maven from `demo-app` so the wrapper finds its own `.mvn`; `-f ../pom.xml` builds the
whole reactor, and plain `./mvnw` builds the app alone once the library modules are installed.

## Multi-module projects

An application inside a reactor gets the whole reactor in the loop. `apply` scans
`src/main/java` and `src/main/resources` of the application **and of every reactor module it
depends on**, compiles each into that module's own `target/classes`, and hot-reloads or
restarts exactly as it does for the application's own code — so an edit in `demo-shared`
reaches the running page without a rebuild.

Nothing has to be configured. On the first `start` or `apply` the daemon walks up from the
application to the aggregator that lists it, then resolves the classpath with
`-pl :<app> -am compile dependency:build-classpath` from there. The `compile` phase is what
makes Maven answer with each module's `target/classes` rather than with whatever jar happens
to be installed — which is also why that first resolution builds the modules the application
depends on, and why a fresh clone needs nothing installed first. The answer is cached and
re-resolved only when a pom in the reactor changes.

A pom edit is part of the loop too, and it needs two answers rather than one. Any module whose
compile classpath moved is **recompiled whole** — no source file changed, so nothing looks
stale, and without this a dependency removal surfaces as a `ClassNotFoundException` at runtime
or not until the next full build. And if the *application's* classpath moved, the app is
**restarted**, because a running JVM cannot be given a new class path. Both are compared on
membership, not order, so a resolution that merely reshuffles costs nothing. A pom edit that
changes neither reports `no changes`.

`status` names the loop, and names the reactor modules outside it:

```
modules demo-app, demo-shared, flow-devloop  (outside the loop: devloop-daemon - the app does not depend on it)
```

Known limits: modules aggregated only under a profile the daemon cannot evaluate, module paths
built from properties it cannot resolve, a custom `<sourceDirectory>` or extra source roots,
and generated sources in a library module. A sibling module that contributes routes,
`@JsModule` or `@NpmPackage` needs a `restart` rather than an `apply`. Where discovery gets it
wrong, `-Dvaadin.dev.reactorRoot` and `-Dvaadin.dev.modules` say so explicitly.

`vaadin-dev --help` lists the rest. The daemon starts itself on first use, one per
application, and on first run stages its jars into `.vaadin/` — building them from the
sibling `devloop-daemon` module if it is present. Point `VAADIN_DEV_HOME` at a directory
containing `devloop-daemon.jar` to use a prebuilt one instead, which is what a shipped
version would provision the way HotswapAgent already is. The java agent is not shipped at
all: the script carries its single class and compiles it on demand.

Useful knobs, passed through `VAADIN_DEV_DAEMON_OPTS`:

```bash
# run a real Vite dev server instead of building a bundle (any vaadin.* property
# is forwarded to the app JVM)
VAADIN_DEV_DAEMON_OPTS="-Dvaadin.frontend.hotdeploy=true" ./vaadin-dev start
# pin the app's JVM, e.g. to compare stock HotSpot against JBR
VAADIN_DEV_DAEMON_OPTS="-Dvaadin.dev.javaHome=$HOME/.jdks/openjdk-25.0.2" ./vaadin-dev start
# how long an apply follows the app's log for errors the redefine provoked (0 turns it off)
VAADIN_DEV_DAEMON_OPTS="-Dvaadin.dev.errorSettleMillis=800" ./vaadin-dev start
# multi-module: the reactor root, when it is not an ancestor of the application
VAADIN_DEV_DAEMON_OPTS="-Dvaadin.dev.reactorRoot=/repo" ./vaadin-dev start
# which modules are in the loop; "." is the application alone
VAADIN_DEV_DAEMON_OPTS="-Dvaadin.dev.modules=../demo-shared" ./vaadin-dev start
# the Maven to resolve with, when neither a wrapper nor PATH is right
VAADIN_DEV_DAEMON_OPTS="-Dvaadin.dev.maven=/opt/maven/bin/mvn" ./vaadin-dev start
```

## Where things end up

Everything is under the application, because that is the only tree the daemon assumes:

- `demo-app/.vaadin/` — handshake file (port + token), the provisioned HotswapAgent jar, and
  the daemon's own jars
- `demo-app/target/devloop/` — daemon log, app log, classpath cache and its pom stamp, the
  JVM argument file, harness results
- `<module>/target/devloop/cp.txt` — each in-loop module's own compile classpath, written by
  the same Maven run

## Measurement harnesses

Run from `demo-app`; they drive the daemon's `redefine` verb, which reports raw JVM
behaviour without `apply`'s escalation policy:

```bash
cd demo-app
java ../devloop-daemon/harness/DevLoopHarness.java --label jdk25   # escalation rate
java ../devloop-daemon/harness/P05Harness.java --test spring       # framework coverage
```

Note they deliberately bypass the escalation rules, so they can leave the app in a state
`apply` would have refused — that is the point of them.
