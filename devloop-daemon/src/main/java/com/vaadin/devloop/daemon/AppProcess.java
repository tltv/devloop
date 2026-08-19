package com.vaadin.devloop.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the app process. The daemon launches the app JVM directly rather than
 * through {@code spring-boot:run}, which forks: with a fork the app is a
 * grandchild, so exit codes are lost and a kill can orphan the JVM. A direct
 * child gives real exit codes and a clean stop.
 * <p>
 * Two independent signals decide the reported state, as the design requires:
 * the process exit (authoritative, carries the code) and the registration
 * connection held open by the in-app connector (its close means the app is
 * gone). Whether a stop was expected is what separates {@code stopped} from
 * {@code crashed}.
 */
final class AppProcess {

    enum State {
        STOPPED, STARTING, RUNNING, CRASHED
    }

    /** How long an app may take to register before a start gives up on it. */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    /**
     * How long the app has to prove it survived registering, used only when it
     * never reports a listening web server. See {@link #start}.
     */
    private static final Duration SETTLE = Duration
            .ofMillis(Long.getLong("vaadin.dev.startSettleMillis", 2500L));

    private static final long POLL_MILLIS = 100L;

    /**
     * A start's verdict. Callers need the answer itself, not a message to match
     * on: "exit code is the outcome" only holds if the code is derived from the
     * same fact the caller sees.
     */
    record Startup(boolean ok, String message, List<String> detail) {

        static Startup ok(String message) {
            return new Startup(true, message, List.of());
        }

        /**
         * The verdict first, then whatever evidence there is. The message is the
         * bare reason so that a caller which has its own framing - a
         * transaction's {@code restart: ...} - does not end up saying "failed"
         * twice.
         */
        List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add(ok ? message : "failed: " + message);
            lines.addAll(detail);
            return lines;
        }
    }

    private final Path root;
    private final Launch launch;

    private volatile State state = State.STOPPED;
    private volatile Process process;
    private volatile Integer exitCode;
    private volatile String mode = "unknown";
    private volatile boolean registered;
    private volatile String failureReason;
    private volatile Path logFile;
    private final AtomicBoolean stopExpected = new AtomicBoolean();
    private volatile CountDownLatch registrationLatch = new CountDownLatch(1);

    AppProcess(Path root, Launch launch) {
        this.root = root;
        this.launch = launch;
    }

    State state() {
        return state;
    }

    Optional<Integer> exitCode() {
        return Optional.ofNullable(exitCode);
    }

    Optional<Long> pid() {
        Process current = process;
        return current != null && current.isAlive()
                ? Optional.of(current.pid()) : Optional.empty();
    }

    String mode() {
        return mode;
    }

    boolean isRegistered() {
        return registered;
    }

    Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /**
     * Launches and waits until the app is serving or gone, because a start that
     * reports success for an app that is already dying is worse than no answer.
     * Two gates, neither of them a timer:
     * <ol>
     * <li><b>Registered</b> - the in-app connector called home, so the app's own
     * code is running.</li>
     * <li><b>Serving</b> - registration happens while the Spring context is
     * still refreshing and the embedded web server binds its port <em>after</em>
     * that, so an app whose port is taken registers happily and only then dies.
     * The second gate is the app reporting its server listening, with surviving a
     * short settle as the fallback for a stack that logs no such line.</li>
     * </ol>
     * Either gate losing the race to the process exiting is a failure, reported
     * with the reason from the app's own log instead of a bare exit code.
     */
    synchronized Startup start(Launch.Log log) throws IOException {
        if (state == State.RUNNING || state == State.STARTING) {
            return Startup.ok(state == State.STARTING ? "already starting"
                    : "already running");
        }
        List<String> command = launch.command(Daemon.currentPort(),
                Daemon.currentToken(), Daemon.MAIN_CLASS);
        Path appLog = Launch.workDir(root).resolve("app.log");
        Files.createDirectories(appLog.getParent());

        stopExpected.set(false);
        registered = false;
        failureReason = null;
        exitCode = null;
        logFile = appLog;
        registrationLatch = new CountDownLatch(1);
        state = State.STARTING;

        log.line("launching " + command.get(0));
        // The launch line is worth showing - nine flags that all have to be right
        // - but the auth token must not be echoed to stdout or into a log.
        String flags = command.subList(1, Math.max(1, command.indexOf("-cp")))
                .stream()
                .map(flag -> flag.startsWith("-Dvaadin.devloop.token=")
                        ? "-Dvaadin.devloop.token=<redacted>" : flag)
                .collect(java.util.stream.Collectors.joining(" "));
        log.line("flags: " + flags);

        Process started = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(appLog.toFile()))
                .start();
        this.process = started;
        log.line("app pid " + started.pid() + ", log " + appLog);

        started.onExit().thenAccept(this::handleExit);

        // Redirect.to truncates, so this run's output starts at offset zero.
        AppLog.Cursor cursor = new AppLog.Cursor(appLog);
        CountDownLatch latch = registrationLatch;
        long registerBy = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        long settleBy = 0;
        boolean up = false;
        boolean serving = false;

        while (true) {
            serving = serving || cursor.drain().stream().anyMatch(AppLog::serving);
            if (up && (serving || System.nanoTime() >= settleBy)) {
                state = State.RUNNING;
                return Startup.ok(serving ? "running"
                        : "running (registered; the app logged no server port)");
            }
            if (!started.isAlive()) {
                // The authoritative signal, and the only one carrying a code.
                return failed("app exited with code " + started.exitValue()
                        + (up ? " right after registering, before it was serving"
                                : " before registering"), appLog);
            }
            if (!up && System.nanoTime() >= registerBy) {
                return failed("app did not register within "
                        + STARTUP_TIMEOUT.toMinutes() + " minutes", appLog);
            }
            try {
                // The latch also fires when the process exits, so registration is
                // decided by the flag the connector sets, not by the wake-up.
                if (up) {
                    Thread.sleep(POLL_MILLIS);
                } else if (latch.await(POLL_MILLIS, TimeUnit.MILLISECONDS)
                        && registered) {
                    up = true;
                    settleBy = System.nanoTime() + SETTLE.toNanos();
                    log.line("registered; waiting for the web server to bind");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failed("interrupted while waiting for the app to start",
                        appLog);
            }
        }
    }

    synchronized String stop() {
        Process current = process;
        if (current == null || !current.isAlive()) {
            state = State.STOPPED;
            return "not running";
        }
        stopExpected.set(true);
        current.destroy();
        try {
            if (!current.waitFor(10, TimeUnit.SECONDS)) {
                current.destroyForcibly();
                current.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        state = State.STOPPED;
        return "stopped";
    }

    /**
     * Turns a dead or unreachable app into an answer that stands on its own. The
     * app's log holds the only copy of the real reason - "Port 8080 was already
     * in use" is printed by the app, nothing here can observe it - so the cause
     * goes into the message and the tail comes along as the evidence.
     */
    private Startup failed(String message, Path appLog) {
        String cause = AppLog.cause(appLog).orElse("");
        String full = message + (cause.isEmpty() ? "" : ": " + cause);
        // A crash has already recorded its own reason from the same log; this one
        // only fills the gap where the app is alive but never registered.
        if (failureReason == null) {
            failureReason = full;
        }
        List<String> tail = AppLog.tail(appLog);
        if (tail.isEmpty()) {
            return new Startup(false, full, List.of("no output in " + appLog));
        }
        List<String> detail = new ArrayList<>();
        detail.add("--- last " + tail.size() + " lines of " + appLog + " ---");
        detail.addAll(tail);
        return new Startup(false, full, detail);
    }

    private void handleExit(Process exited) {
        exitCode = exited.exitValue();
        registered = false;
        if (stopExpected.get()) {
            state = State.STOPPED;
        } else {
            state = State.CRASHED;
            Path appLog = logFile;
            // The reason, not just the code: an "exit=1" whose cause stays buried
            // in a log file is what made a port clash look like a tool bug.
            failureReason = "app exited unexpectedly with code " + exitCode
                    + (appLog == null ? ""
                            : AppLog.cause(appLog).map(c -> ": " + c).orElse("")
                                    + " (see " + appLog + ")");
        }
        // Release anyone waiting on startup so a failed launch returns at once.
        registrationLatch.countDown();
    }

    /** Called when the in-app connector registers over its long-lived socket. */
    void onRegistered(String reportedMode) {
        this.mode = reportedMode;
        this.registered = true;
        this.state = State.RUNNING;
        registrationLatch.countDown();
    }

    /**
     * Called when the registration connection closes. The process exit is the
     * authoritative signal, so this only records the loss of the app-side
     * channel; it does not by itself declare a crash.
     */
    void onUnregistered() {
        registered = false;
    }

    boolean isIdle() {
        return state == State.STOPPED || state == State.CRASHED;
    }
}
