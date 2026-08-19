package com.vaadin.devloop.daemon;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the app's own log, so the daemon can report why a launch failed instead
 * of leaving the developer to go and find out.
 * <p>
 * The app's stdout and stderr go straight to {@code target/devloop/app.log},
 * which makes that file the only place the real reason lives: "Port 8080 was
 * already in use" is printed by the app and is not observable from the daemon's
 * side of the process boundary. A failure that says only "exit code 1" and
 * leaves the cause in a file nobody was told to read is exactly what made a port
 * clash look like a regression in the tool.
 */
final class AppLog {

    /** Enough tail to carry the head of a stack trace without flooding a reply. */
    private static final int TAIL_LINES = 20;

    /** The end of the log is where failures are; reading it all is wasteful. */
    private static final int TAIL_BYTES = 64 * 1024;

    /**
     * The web server is listening. This is the line a port clash never reaches,
     * and it is printed after the bind, which is what makes it usable as the
     * "the port really is ours" signal.
     */
    private static final Pattern SERVING = Pattern.compile(
            "(?i)(tomcat|jetty|netty|undertow).{0,40}started on port"
                    + "|(?i)started \\S+ in \\d+([.,]\\d+)? second");

    /** Spring Boot's failure analyzer puts the plain-words cause under this. */
    private static final Pattern DESCRIPTION = Pattern
            .compile("^Description:\\s*$");

    private static final Pattern THROWN = Pattern
            .compile("\\S*(Exception|Error)(:|\\s|$)");

    /**
     * Where a failure report starts. Everything after it is the trace it
     * explains, which is why the reason has to be looked for from here forwards
     * and not at the end of the log: a Spring context failure prints its reason
     * first and a hundred frames after it.
     */
    private static final Pattern REPORT = Pattern
            .compile("APPLICATION FAILED TO START|Application run failed"
                    + "|Exception encountered during context initialization"
                    + "|\\b(ERROR|SEVERE|FATAL)\\b");

    private AppLog() {
    }

    /** Whether one log line says the app's web server is up. */
    static boolean serving(String line) {
        return SERVING.matcher(line).find();
    }

    /**
     * Follows a log as it is written. Only the bytes that appeared since the last
     * call are ever read, so a start can poll this every 100 ms for as long as an
     * app takes to boot without the cost growing with the log.
     */
    static final class Cursor {

        private final Path log;
        private long at;
        private String partial = "";

        Cursor(Path log) {
            this.log = log;
        }

        /**
         * The complete lines written since the previous call. A trailing fragment
         * is held back rather than returned as a line, because the app may well be
         * mid-write in the middle of the very line being waited for.
         */
        List<String> drain() {
            String text;
            try (SeekableByteChannel channel = Files.newByteChannel(log,
                    StandardOpenOption.READ)) {
                long available = channel.size() - at;
                if (available <= 0) {
                    return List.of();
                }
                ByteBuffer buffer = ByteBuffer
                        .allocate((int) Math.min(available, TAIL_BYTES));
                channel.position(at);
                int read = channel.read(buffer);
                if (read <= 0) {
                    return List.of();
                }
                at += read;
                text = decode(buffer.array(), read);
            } catch (IOException e) {
                // A log that cannot be read is not a reason to fail a start; the
                // process signals are what decide the outcome.
                return List.of();
            }

            List<String> lines = new ArrayList<>();
            String pending = partial + text;
            int from = 0;
            for (int i = 0; i < pending.length(); i++) {
                if (pending.charAt(i) == '\n') {
                    lines.add(pending.substring(from, i).stripTrailing());
                    from = i + 1;
                }
            }
            partial = pending.substring(from);
            return lines;
        }
    }

    /** The last lines of the log, blank ones dropped, oldest first. */
    static List<String> tail(Path log) {
        List<String> lines = window(log);
        return lines.size() <= TAIL_LINES ? lines
                : new ArrayList<>(
                        lines.subList(lines.size() - TAIL_LINES, lines.size()));
    }

    /**
     * The lines worth showing for a failure: the head of the last failure report
     * rather than the end of the log. A long trace pushes the reason out of a
     * fixed tail, which then carries all of the evidence and none of the cause -
     * twenty frames ending on {@code ... 46 common frames omitted}.
     */
    static List<String> excerpt(Path log) {
        List<String> lines = window(log);
        int start = reportStart(lines);
        return start < 0 ? tail(log)
                : new ArrayList<>(lines.subList(start,
                        Math.min(lines.size(), start + TAIL_LINES)));
    }

    /**
     * The one line that explains a failure, for the places with room for a reason
     * but not for a tail - {@code status} and a transaction's reason.
     */
    static Optional<String> cause(Path log) {
        List<String> lines = window(log);
        // The app's own diagnosis first: when Spring Boot knows why it could not
        // start it says so in plain words, which beats any line guessed at here.
        // The last such block, because an app that logged one of these and then
        // carried on did not fail for that reason.
        for (int i = lines.size() - 2; i >= 0; i--) {
            if (DESCRIPTION.matcher(lines.get(i)).matches()) {
                return Optional.of(lines.get(i + 1));
            }
        }
        int start = reportStart(lines);
        Optional<String> reason = reason(lines, Math.max(0, start));
        if (reason.isEmpty() && start >= 0) {
            // A report whose own lines name no exception - a bare ERROR from a
            // hotswap plugin, say - must not shadow a real trace above it, and
            // failing that it is still a better answer than the log's last line,
            // which for an app that died quietly is whatever it did last.
            reason = reason(lines, 0).or(() -> Optional.of(lines.get(start)));
        }
        return reason.or(() -> lines.isEmpty() ? Optional.empty()
                : Optional.of(lines.get(lines.size() - 1)));
    }

    /**
     * The reason within one stretch of the log: the deepest {@code Caused by:},
     * else the exception that opened it. Deepest, because Spring wraps the real
     * reason in two or three bean-creation failures and only the innermost one
     * names the mistake - "No property 'findOne' found for type 'Task'".
     */
    private static Optional<String> reason(List<String> lines, int from) {
        for (int i = lines.size() - 1; i >= from; i--) {
            String line = lines.get(i);
            if (line.startsWith("Caused by:")) {
                // Without the prefix: this line goes on to be quoted inside a
                // sentence of the daemon's own ("restart: ...").
                return Optional
                        .of(line.substring("Caused by:".length()).trim());
            }
        }
        // Nothing wrapped it, so the report's first exception line is the reason.
        // Forwards, because the ones after it are its frames.
        for (int i = from; i < lines.size(); i++) {
            if (THROWN.matcher(lines.get(i)).find()) {
                return Optional.of(lines.get(i));
            }
        }
        return Optional.empty();
    }

    /**
     * Where the last failure report begins, or -1 if the window holds none. The
     * last, not the first: the question is always about the latest failure.
     */
    private static int reportStart(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (REPORT.matcher(lines.get(i)).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The tail of the log as lines, blank and banner-rule lines dropped. Wider
     * than what any caller prints: the reason for a failure and the lines worth
     * showing for it are found in here, not in the last twenty lines.
     */
    private static List<String> window(Path log) {
        long size = size(log);
        List<String> lines = new ArrayList<>();
        for (String line : read(log, Math.max(0, size - TAIL_BYTES)).lines()
                .toList()) {
            String trimmed = line.stripTrailing();
            // Spring Boot's banner rules around a failure report carry nothing
            // and would spend three of the lines available.
            if (!trimmed.isBlank() && !trimmed.matches("\\*{3,}")) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static long size(Path log) {
        try {
            return Files.isRegularFile(log) ? Files.size(log) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String read(Path log, long from) {
        if (!Files.isRegularFile(log)) {
            return "";
        }
        try (SeekableByteChannel channel = Files.newByteChannel(log,
                StandardOpenOption.READ)) {
            long available = channel.size() - from;
            if (available <= 0) {
                return "";
            }
            ByteBuffer buffer = ByteBuffer
                    .allocate((int) Math.min(available, TAIL_BYTES));
            channel.position(from);
            int read = channel.read(buffer);
            return read <= 0 ? "" : decode(buffer.array(), read);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * A read that starts at an arbitrary offset can begin mid-character, so this
     * replaces rather than fails: the text is only ever shown or matched.
     */
    private static String decode(byte[] bytes, int length) {
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
