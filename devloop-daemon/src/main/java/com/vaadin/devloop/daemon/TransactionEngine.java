package com.vaadin.devloop.daemon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The transaction: one logical source change carried through to a stable running
 * app. It is the spine — state, diagnostics and the agent API all hang off it.
 * <p>
 * Two rules make "what is the state of my last change?" answerable:
 * <ul>
 * <li><b>At most one in flight.</b> Concurrent transactions would make the
 * question unanswerable.</li>
 * <li><b>Supersede, don't queue.</b> A new apply cancels the in-flight one and
 * proceeds with the accumulated change-set, because only the latest bytes on
 * disk matter. The superseded caller still gets a terminal answer.</li>
 * </ul>
 * P2 implements the compile leg. The runtime leg lands in P3; until then a
 * successful compile escalates straight to a restart when the app is running,
 * which is the honest way to make the change live with only P1+P2 machinery.
 */
final class TransactionEngine {

    enum Outcome {
        /** Change is live and consistent. The success an agent waits on. */
        STABLE(0),
        /** Compiled, but nothing is running to apply it to. */
        COMPILED(0),
        /** Nothing changed on disk since the last apply. */
        NO_CHANGES(0),
        /** Could not complete; reason names the phase. */
        FAILED(1),
        /** Cancelled by a newer apply. */
        SUPERSEDED(4);

        final int exitCode;

        Outcome(int exitCode) {
            this.exitCode = exitCode;
        }

        String label() {
            return switch (this) {
            case STABLE -> "Stable";
            case COMPILED -> "compiled";
            case NO_CHANGES -> "no changes";
            case FAILED -> "Failed";
            case SUPERSEDED -> "Failed(superseded)";
            };
        }
    }

    /** Everything a transaction carries, per the design. */
    static final class Transaction {
        final int id;
        volatile List<String> changeSet = List.of();
        volatile String state = "pending";
        volatile Outcome outcome;
        volatile String reason = "";
        volatile String classification = "none";
        volatile String nextAction = "";
        volatile List<Compile.Message> diagnostics = List.of();
        volatile List<String> classes = List.of();
        volatile boolean superseded;
        volatile int duplicates;
        volatile String hotswapDetail = "";
        volatile String escalation = "";
        volatile int resources;
        volatile String frontend = "";
        long detectMs;
        long compileMs;
        long runtimeMs;
        long totalMs;

        Transaction(int id) {
            this.id = id;
        }

        String json() {
            List<String> diag = diagnostics.stream().map(Compile.Message::json)
                    .toList();
            return "{\"transaction\":\"tx#" + id + "\",\"outcome\":\""
                    + outcome.name().toLowerCase() + "\",\"classification\":\""
                    + classification + "\",\"reason\":\"" + Json.escape(reason)
                    + "\",\"changeSet\":" + Json.strings(changeSet)
                    + ",\"classes\":" + Json.strings(classes)
                    + ",\"diagnostics\":" + Json.array(diag)
                    + ",\"actionsTaken\":\"" + Json.escape(hotswapDetail)
                    + "\",\"escalation\":"
                    + (escalation.isEmpty() ? "null"
                            : "\"" + Json.escape(escalation) + "\"")
                    + ",\"duplicateClassCopies\":" + duplicates
                    + ",\"resources\":" + resources + ",\"frontend\":\""
                    + Json.escape(frontend) + "\""
                    + ",\"timings\":{\"detectMs\":" + detectMs
                    + ",\"compileMs\":" + compileMs + ",\"runtimeMs\":"
                    + runtimeMs + ",\"totalMs\":" + totalMs
                    + "},\"nextAction\":\"" + Json.escape(nextAction) + "\"}";
        }

        String summary() {
            return "tx#" + id + " " + outcome.label()
                    + (reason.isEmpty() ? "" : " (" + reason + ")");
        }
    }

    private final Compile compile;
    private final Launch launch;
    private final AppProcess app;
    private final AtomicInteger ids = new AtomicInteger();
    private final ReentrantLock compileLock = new ReentrantLock(true);

    private volatile Connector connector;
    private volatile Transaction inFlight;
    private volatile Transaction last;

    TransactionEngine(Path root, Launch launch, AppProcess app) {
        this.compile = new Compile(root);
        this.launch = launch;
        this.app = app;
        // Seeded now so an untouched project reports "no changes" on first apply.
        this.compile.seedFromDisk();
    }

    Optional<Transaction> lastTransaction() {
        return Optional.ofNullable(last);
    }

    Optional<Transaction> current() {
        Transaction tx = inFlight;
        return tx != null && tx.outcome == null ? Optional.of(tx)
                : Optional.empty();
    }

    /**
     * Commits pending edits and blocks until the transaction is terminal. The
     * wait is on real state — a compile finishing, a restart registering — never
     * on a timer.
     */
    Transaction apply(Launch.Log log, boolean allowRestart) {
        Transaction tx = new Transaction(ids.incrementAndGet());
        long started = System.nanoTime();

        synchronized (this) {
            Transaction previous = inFlight;
            if (previous != null && previous.outcome == null) {
                previous.superseded = true;
                log.line("superseding tx#" + previous.id);
            }
            inFlight = tx;
        }

        try {
            long detectStart = System.nanoTime();
            Compile.Changes changes = compile.stale();
            List<Path> staleResources = compile.staleResources();
            tx.detectMs = (System.nanoTime() - detectStart) / 1_000_000;
            tx.changeSet = new ArrayList<>(changes.modified().stream()
                    .map(compile::relative).toList());
            changes.deleted().forEach(
                    path -> tx.changeSet.add(compile.relative(path) + " (deleted)"));
            staleResources.forEach(path -> tx.changeSet.add(compile.relative(path)));

            // The resource leg. Runs first because a Java change may also need
            // fresh resources on the classpath, and it is the whole transaction
            // when nothing else changed.
            if (!staleResources.isEmpty()) {
                tx.state = "frontend";
                try {
                    compile.copyResources(staleResources);
                    tx.resources = staleResources.size();
                    log.line("resources: copied " + tx.resources
                            + " to the classpath");
                } catch (java.io.IOException e) {
                    return finish(tx, Outcome.FAILED,
                            "resource copy: " + e.getMessage(), "none",
                            "check file permissions under target/classes",
                            started);
                }
            }

            if (changes.isEmpty() && !staleResources.isEmpty()) {
                return finishResourceOnly(tx, log, staleResources, started);
            }
            if (!staleResources.isEmpty()) {
                // Java and resources in one change-set: push the resources too,
                // otherwise the CSS half of the edit would sit on the classpath
                // unseen until something reloaded the page.
                notifyResources(staleResources, log);
            }

            if (changes.isEmpty()) {
                return finish(tx, Outcome.NO_CHANGES, "", "none",
                        "edit a source file, then apply", started);
            }
            if (bailIfSuperseded(tx, started)) {
                return tx;
            }

            tx.state = "compiling";
            log.line("change-set: " + tx.changeSet.size() + " file(s)");

            String classpath;
            try {
                classpath = launch.classpath();
            } catch (Exception e) {
                return finish(tx, Outcome.FAILED, "classpath: " + e.getMessage(),
                        "none", "check the Maven build", started);
            }

            Compile.Result result;
            compileLock.lock();
            try {
                // Re-check after queuing: a newer apply may have taken over while
                // we waited, and its change-set already includes ours.
                if (bailIfSuperseded(tx, started)) {
                    return tx;
                }
                result = compile.compile(changes.modified(), classpath);
            } finally {
                compileLock.unlock();
            }
            tx.compileMs = result.millis();

            if (!result.success()) {
                tx.diagnostics = result.errors();
                return finish(tx, Outcome.FAILED, "compile", "none",
                        result.errors().isEmpty() ? "fix the compile error"
                                : result.errors().get(0).hint()
                                        .orElse("fix the compile error"),
                        started);
            }
            tx.classes = result.writtenClasses();
            if (bailIfSuperseded(tx, started)) {
                return tx;
            }

            // --- runtime leg: attempt the atomic redefine, escalate if it cannot
            // stick. What actually happened is the authoritative answer; static
            // prediction is only ever a hint.
            Connector connector = this.connector;
            if (app.state() == AppProcess.State.RUNNING && connector != null
                    && connector.isOpen() && !tx.classes.isEmpty()) {
                long runtimeStart = System.nanoTime();
                tx.state = "runtime";
                Optional<String> reply = connector.command(
                        "REDEFINE " + String.join(",", tx.classes), 60);
                tx.runtimeMs = (System.nanoTime() - runtimeStart) / 1_000_000;

                if (reply.isEmpty()) {
                    log.line("app did not answer the redefine; escalating");
                } else {
                    Map<String, String> fields = Connector.fields(reply.get());
                    tx.duplicates = parseInt(fields.get("dupes"));
                    if ("OK".equals(fields.get("status"))) {
                        Optional<String> blocker = blockedReason(fields);
                        if (blocker.isEmpty()) {
                            tx.hotswapDetail = "redefineClasses("
                                    + fields.getOrDefault("redefined", "0")
                                    + "); onHotswap completed="
                                    + fields.getOrDefault("completed", "?");
                            // These sources are now live in the JVM, so the next
                            // apply should not offer them again.
                            compile.markSourcesApplied(changes.modified());
                            return finish(tx, Outcome.STABLE, "", "hot-reload",
                                    "", started);
                        }
                        log.line("redefine applied but " + blocker.get()
                                + "; escalating to restart");
                        tx.escalation = blocker.get();
                    } else {
                        String detail = fields.getOrDefault("message",
                                reply.get());
                        log.line("redefine rejected: " + detail
                                + "; escalating to restart");
                        tx.escalation = detail;
                    }
                }
            }

            if (!allowRestart || app.state() != AppProcess.State.RUNNING) {
                String next = app.state() == AppProcess.State.RUNNING
                        ? "run apply without --no-restart to make it live"
                        : "vaadin-dev start";
                return finish(tx, Outcome.COMPILED, "", "compile-only", next,
                        started);
            }

            // P2 has no redefine yet, so every change escalates. P3 attempts an
            // atomic redefine first and only escalates when the JVM refuses.
            tx.state = "restarting";
            log.line("restarting");
            long runtimeStart = System.nanoTime();
            app.stop();
            String startResult;
            try {
                startResult = app.start(log);
            } catch (java.io.IOException e) {
                tx.runtimeMs = (System.nanoTime() - runtimeStart) / 1_000_000;
                return finish(tx, Outcome.FAILED, "restart: " + e.getMessage(),
                        "restart", "check target/devloop/app.log", started);
            }
            tx.runtimeMs = (System.nanoTime() - runtimeStart) / 1_000_000;
            if (startResult.startsWith("failed")) {
                return finish(tx, Outcome.FAILED, "restart: " + startResult,
                        "restart", "check target/devloop/app.log", started);
            }
            return finish(tx, Outcome.STABLE, "", "restart", "", started);
        } catch (RuntimeException e) {
            return finish(tx, Outcome.FAILED, "internal: " + e, "none",
                    "see daemon.log", started);
        }
    }

    /**
     * A resources-only change: never a restart. The frontend leg is blocked
     * rather than hung when Vite is down, so the agent gets a terminal answer
     * either way.
     */
    private Transaction finishResourceOnly(Transaction tx, Launch.Log log,
            List<Path> resources, long started) {
        Connector active = this.connector;
        if (app.state() != AppProcess.State.RUNNING || active == null
                || !active.isOpen()) {
            return finish(tx, Outcome.COMPILED, "", "hmr",
                    app.state() == AppProcess.State.RUNNING ? ""
                            : "vaadin-dev start",
                    started);
        }

        String frontend = active.command("FRONTEND", 10)
                .map(reply -> Connector.fields(reply)
                        .getOrDefault("frontend", "unknown"))
                .orElse("unknown");
        tx.frontend = frontend;
        if (frontend.startsWith("down")) {
            return finish(tx, Outcome.FAILED,
                    "frontend-down: the Vite dev server is not answering on "
                            + frontend.substring(frontend.indexOf(':') + 1),
                    "hmr", "restart the app to bring the dev server back",
                    started);
        }

        long runtimeStart = System.nanoTime();
        Optional<String> reply = notifyResources(resources, log);
        tx.runtimeMs = (System.nanoTime() - runtimeStart) / 1_000_000;
        if (reply.isEmpty() || !reply.get().startsWith("OK")) {
            return finish(tx, Outcome.FAILED,
                    "resource notify: " + reply.orElse("no reply"), "hmr",
                    "see target/devloop/app.log", started);
        }
        Map<String, String> resourceFields = Connector.fields(reply.get());
        int pushed = parseInt(resourceFields.get("pushed"));
        tx.hotswapDetail = pushed > 0
                ? "pushed " + pushed + " stylesheet(s) in place"
                : "true".equals(resourceFields.get("browserReload"))
                        ? "browser reload requested"
                        : "no browser connected";
        return finish(tx, Outcome.STABLE, "", "hmr", "", started);
    }

    /**
     * Tells the app about changed resources and records that the browser has now
     * seen them, which is what keeps the next apply quiet.
     */
    private Optional<String> notifyResources(List<Path> resources,
            Launch.Log log) {
        Connector active = this.connector;
        if (active == null || !active.isOpen()) {
            return Optional.empty();
        }
        String paths = resources.stream().map(Path::toString)
                .collect(java.util.stream.Collectors.joining(","));
        Optional<String> reply = active.command("RESOURCES " + paths, 30);
        if (reply.isPresent() && reply.get().startsWith("OK")) {
            compile.markResourcesNotified(resources);
        } else if (reply.isPresent()) {
            log.line("resource notify: " + reply.get());
        }
        return reply;
    }

    void onConnector(Connector connector) {
        this.connector = connector;
        if (connector != null) {
            // An app that has just registered is running exactly what is on disk,
            // so that becomes the new "already live" baseline.
            compile.seedFromDisk();
        }
    }

    Connector connector() {
        return connector;
    }

    /**
     * Cases where the JVM accepts the redefine but the change still is not live.
     * Both were measured in P0.5, and both would otherwise be reported as
     * {@code Stable} on an app that is stale or, worse, broken.
     */
    private Optional<String> blockedReason(Map<String, String> fields) {
        String entities = fields.getOrDefault("entities", "-");
        if (!"-".equals(entities)) {
            return Optional.of("entity mapping cannot hot reload (" + entities
                    + "): Hibernate's metamodel and schema are fixed at startup");
        }
        // A method body inside a bean is fine: the proxy delegates to the target
        // and the target's new body runs. What breaks is a change to the class's
        // shape, because the proxy was generated against the old one. HA's Spring
        // plugin could fix that, but it is disabled for stability (see Launch),
        // so these escalate.
        String beans = fields.getOrDefault("beans", "-");
        String structural = fields.getOrDefault("structural", "-");
        if (!"-".equals(beans) && !"-".equals(structural)) {
            return Optional.of("structural change to a Spring bean (" + beans
                    + "): the existing proxy would not match the new class");
        }
        return Optional.empty();
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean bailIfSuperseded(Transaction tx, long started) {
        if (tx.superseded) {
            finish(tx, Outcome.SUPERSEDED, "a newer apply took over", "none",
                    "read the newer apply's result", started);
            return true;
        }
        return false;
    }

    private Transaction finish(Transaction tx, Outcome outcome, String reason,
            String classification, String nextAction, long startedNanos) {
        tx.outcome = outcome;
        tx.reason = reason;
        tx.classification = classification;
        tx.nextAction = nextAction;
        tx.state = outcome.name().toLowerCase();
        tx.totalMs = (System.nanoTime() - startedNanos) / 1_000_000;
        // A superseded transaction is not the answer to "what is the state?".
        if (outcome != Outcome.SUPERSEDED) {
            last = tx;
        }
        return tx;
    }

    /** Terse, human-and-agent-readable rendering; full detail only on failure. */
    List<String> render(Transaction tx) {
        List<String> lines = new ArrayList<>();
        // Locale.ROOT: the default locale would render "0,71s" on a Finnish
        // machine, and this output is parsed by agents as well as read by people.
        String seconds = String.format(java.util.Locale.ROOT, "%.2fs",
                tx.totalMs / 1000.0);
        switch (tx.outcome) {
        case NO_CHANGES -> lines.add("no changes");
        case SUPERSEDED -> lines.add("Failed(superseded): " + tx.reason);
        case FAILED -> {
            // Name the phase that actually failed; "compiling" on a frontend
            // failure sends the reader looking in the wrong place.
            String phase = "hmr".equals(tx.classification) ? "frontend"
                    : tx.escalation.isEmpty() ? "compiling" : "runtime";
            lines.add(phase + " → Failed" + (tx.diagnostics.isEmpty()
                    ? "  (" + tx.reason + ")" : ""));
            tx.diagnostics.forEach(message -> {
                lines.add(message.terse());
                message.hint().ifPresent(hint -> lines.add("  → " + hint));
            });
            if (tx.diagnostics.isEmpty() && !tx.reason.isEmpty()) {
                lines.add(tx.reason);
            }
        }
        case COMPILED -> {
            lines.add("compiling → compiled   (" + seconds + ")");
            lines.add(tx.classes.size() + " class(es); " + tx.nextAction);
        }
        case STABLE -> {
            if ("hmr".equals(tx.classification)) {
                lines.add("frontend → Stable   (" + seconds + ")");
                lines.add("hmr: " + tx.resources + " resource(s) copied, "
                        + tx.hotswapDetail);
            } else if ("hot-reload".equals(tx.classification)) {
                lines.add("compiling → runtime → Stable   (" + seconds + ")");
                lines.add("hot-reload: " + tx.hotswapDetail
                        + (tx.duplicates > 0 ? "; " + tx.duplicates
                                + " duplicate class copy/copies also redefined"
                                : ""));
            } else {
                lines.add("compiling → runtime → restarting → Stable   ("
                        + seconds + ")");
                lines.add("restart: " + tx.escalation);
            }
        }
        }
        return lines;
    }
}
