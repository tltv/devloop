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
| `demo-app` | A Vaadin application, standing in for any user application, with the `vaadin-dev` CLI and the Maven wrapper. |

`demo-app` is self-contained on purpose: the daemon serves one application using only that
application's own wrapper and classpath, and knows nothing about this repository's layout.

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
whole reactor, and plain `./mvnw` builds the app alone once `flow-devloop` is installed.

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
```

## Where things end up

Everything is under the application, because that is the only tree the daemon assumes:

- `demo-app/.vaadin/` — handshake file (port + token), the provisioned HotswapAgent jar, and
  the daemon's own jars
- `demo-app/target/devloop/` — daemon log, app log, classpath cache, harness results

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
