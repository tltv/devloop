package com.vaadin.devloop.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final Path root;
    private final Launch launch;

    private volatile State state = State.STOPPED;
    private volatile Process process;
    private volatile Integer exitCode;
    private volatile String mode = "unknown";
    private volatile boolean registered;
    private volatile String failureReason;
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
     * Launches and waits for the app to register. The wait is gated on the real
     * signal - registration, or the process dying - never on a timer, so an app
     * that starts in 3 s returns in 3 s and one that fails returns immediately.
     */
    synchronized String start(Launch.Log log) throws IOException {
        if (state == State.RUNNING || state == State.STARTING) {
            return "already running";
        }
        List<String> command = launch.command(Daemon.currentPort(),
                Daemon.currentToken(), Daemon.MAIN_CLASS);
        Path logFile = Launch.workDir(root).resolve("app.log");
        Files.createDirectories(logFile.getParent());

        stopExpected.set(false);
        registered = false;
        failureReason = null;
        exitCode = null;
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
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                .start();
        this.process = started;
        log.line("app pid " + started.pid() + ", log " + logFile);

        started.onExit().thenAccept(this::handleExit);

        // Race the registration against the process dying.
        CountDownLatch latch = registrationLatch;
        boolean up = false;
        long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        try {
            while (System.nanoTime() < deadlineNanos) {
                if (latch.await(200, TimeUnit.MILLISECONDS)) {
                    up = true;
                    break;
                }
                if (!started.isAlive()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "failed: interrupted while waiting for the app to register";
        }
        if (up) {
            state = State.RUNNING;
            return "running";
        }
        if (!started.isAlive()) {
            return "failed: app exited with code " + exitCode
                    + " before registering (see " + logFile + ")";
        }
        return "failed: app did not register within 5 minutes (see " + logFile
                + ")";
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

    private void handleExit(Process exited) {
        exitCode = exited.exitValue();
        registered = false;
        if (stopExpected.get()) {
            state = State.STOPPED;
        } else {
            state = State.CRASHED;
            failureReason = "app exited unexpectedly with code " + exitCode;
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
