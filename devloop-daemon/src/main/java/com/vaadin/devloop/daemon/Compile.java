package com.vaadin.devloop.daemon;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The compile leg: change detection plus an in-process compile.
 * <p>
 * Uses {@link JavaCompiler} rather than shelling out to Maven for two reasons.
 * It skips a JVM start per apply, and its {@link Diagnostic} objects already
 * carry file, line, column, code and message — so the agent-facing
 * {@code diagnostics[]} contract needs no parsing of compiler text output.
 */
final class Compile {

    /** One compiler error, in the shape the agent API promises. */
    record Message(String file, long line, long column, String code,
            String text, Optional<String> hint) {

        String terse() {
            return file + ":" + line + ":" + column + "  error: " + text;
        }

        String json() {
            return "{\"file\":\"" + Json.escape(file) + "\",\"line\":" + line
                    + ",\"column\":" + column + ",\"code\":\""
                    + Json.escape(code) + "\",\"message\":\""
                    + Json.escape(text) + "\",\"hint\":"
                    + hint.map(h -> "\"" + Json.escape(h) + "\"").orElse("null")
                    + "}";
        }
    }

    record Result(boolean success, List<Message> errors,
            List<String> writtenClasses, long millis) {
    }

    /**
     * A file's identity for change detection. Size is included because a same-
     * millisecond rewrite of a different length is otherwise invisible.
     */
    record Stamp(long modified, long size) {
    }

    record Changes(List<Path> modified, List<Path> deleted) {
        boolean isEmpty() {
            return modified.isEmpty() && deleted.isEmpty();
        }

        int size() {
            return modified.size() + deleted.size();
        }
    }

    /** Fingerprints of Java sources as of the last time they went live. */
    private final Map<Path, Stamp> applied = new java.util.concurrent.ConcurrentHashMap<>();

    /** Fingerprints as of the last browser notification, keyed by source path. */
    private final Map<Path, Stamp> notified = new java.util.concurrent.ConcurrentHashMap<>();

    private final Path root;
    private final Path sourceDir;
    private final Path resourceDir;
    private final Path classesDir;

    Compile(Path root) {
        this.root = root;
        this.sourceDir = root.resolve("src").resolve("main").resolve("java");
        this.resourceDir = root.resolve("src").resolve("main")
                .resolve("resources");
        this.classesDir = root.resolve("target").resolve("classes");
    }

    /**
     * Resources the browser has not been told about yet.
     * <p>
     * Two questions have to be asked, and the artifact comparison alone answers
     * only the first:
     * <ul>
     * <li><b>Is the classpath copy current?</b> Flow watches the source tree but
     * never refreshes {@code target/classes}, so anything that re-fetches the file
     * - a page reload, a new tab - would get stale bytes.</li>
     * <li><b>Has the browser seen this content?</b> An IDE that copies resources
     * on save (IntelliJ does, with auto-build on) makes the classpath copy current
     * on its own, and then a pure artifact check reports "no changes" for an edit
     * the browser has never received. So the daemon also remembers the fingerprint
     * of every resource as of the last time it notified the browser.</li>
     * </ul>
     * The fingerprint map is seeded at daemon start, so a first apply with nothing
     * edited is still quiet.
     */
    List<Path> staleResources() {
        List<Path> stale = new ArrayList<>();
        forEachResource((source, stamp) -> {
            if (!copyIsCurrent(source) || !stamp.equals(notified.get(source))) {
                stale.add(source);
            }
        });
        stale.sort(Comparator.naturalOrder());
        return stale;
    }

    /** Records that the browser has been told about these resources. */
    void markResourcesNotified(List<Path> resources) {
        for (Path source : resources) {
            stampOf(source).ifPresent(stamp -> notified.put(source, stamp));
        }
    }

    /** Seeds the fingerprints, so an untouched project reports no changes. */
    void seedResources() {
        notified.clear();
        forEachResource(notified::put);
    }

    private boolean copyIsCurrent(Path source) {
        Path target = classesDir.resolve(resourceDir.relativize(source).toString());
        try {
            return Files.isRegularFile(target) && Files.getLastModifiedTime(source)
                    .compareTo(Files.getLastModifiedTime(target)) <= 0;
        } catch (IOException e) {
            return false;
        }
    }

    private Optional<Stamp> stampOf(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file,
                    BasicFileAttributes.class);
            return Optional.of(new Stamp(attrs.lastModifiedTime().toMillis(),
                    attrs.size()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void forEachResource(java.util.function.BiConsumer<Path, Stamp> action) {
        if (!Files.isDirectory(resourceDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(resourceDir)) {
            walk.filter(Files::isRegularFile).forEach(source -> stampOf(source)
                    .ifPresent(stamp -> action.accept(source, stamp)));
        } catch (IOException ignored) {
            // An unreadable tree yields an empty change-set, not a failure.
        }
    }

    /** Copies changed resources onto the classpath; returns what was copied. */
    List<Path> copyResources(List<Path> sources) throws IOException {
        List<Path> copied = new ArrayList<>();
        for (Path source : sources) {
            Path target = classesDir
                    .resolve(resourceDir.relativize(source).toString());
            Files.createDirectories(target.getParent());
            Files.copy(source, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            copied.add(target);
        }
        return copied;
    }

    /**
     * The change-set: sources that either need compiling, or are compiled but not
     * yet live in the running JVM.
     * <p>
     * The artifact comparison alone is not enough. It is right that a source newer
     * than its {@code .class} needs work, and it survives a daemon restart without
     * state — but an IDE building on save writes the {@code .class} first, and then
     * the artifact looks current while the JVM is still running the old bytecode.
     * That reported "no changes" for a real edit. So the daemon also tracks which
     * source fingerprints it has actually made live, reset whenever the app starts
     * or restarts (at which point the JVM has loaded whatever is on disk).
     * <p>
     * A failed compile still stays in the change-set until it succeeds, which is
     * what "only the latest bytes on disk matter" requires.
     * <p>
     * Deleted sources are not detected yet; removing a class needs the stale
     * artifact cleaned up too, which is P3 work alongside class removal.
     */
    Changes stale() {
        List<Path> modified = new ArrayList<>();
        forEachSource((source, stamp) -> {
            // Two questions again, and for the same reason as resources. The
            // artifact check answers "does this need compiling?"; it cannot
            // answer "is this live in the running JVM?" - an IDE building on save
            // makes the .class newer than the .java, and a pure artifact check
            // then reports "no changes" for an edit the JVM has never loaded.
            if (isStale(source) || !stamp.equals(applied.get(source))) {
                modified.add(source);
            }
        });
        modified.sort(Comparator.naturalOrder());
        return new Changes(modified, List.of());
    }

    /** Records that these sources are now live in the running JVM. */
    void markSourcesApplied(List<Path> sources) {
        for (Path source : sources) {
            stampOf(source).ifPresent(stamp -> applied.put(source, stamp));
        }
    }

    /**
     * Declares everything on disk to be live, which is true immediately after the
     * app starts or restarts: it loaded its classes and resources from there.
     */
    void seedFromDisk() {
        applied.clear();
        forEachSource(applied::put);
        seedResources();
    }

    private void forEachSource(java.util.function.BiConsumer<Path, Stamp> action) {
        if (!Files.isDirectory(sourceDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .forEach(source -> stampOf(source)
                            .ifPresent(stamp -> action.accept(source, stamp)));
        } catch (IOException ignored) {
            // An unreadable tree yields an empty change-set, not a failure.
        }
    }

    private boolean isStale(Path source) {
        try {
            Path relative = sourceDir.relativize(source);
            String className = relative.toString()
                    .replaceAll("\\.java$", ".class");
            Path artifact = classesDir.resolve(className);
            if (!Files.isRegularFile(artifact)) {
                return true;
            }
            return Files.getLastModifiedTime(source)
                    .compareTo(Files.getLastModifiedTime(artifact)) > 0;
        } catch (IOException e) {
            return true;
        }
    }

    Result compile(List<Path> sources, String classpath) {
        long started = System.nanoTime();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new Result(false,
                    List.of(new Message("-", 0, 0, "no-compiler",
                            "no system Java compiler; the daemon must run on a JDK",
                            Optional.empty())),
                    List.of(), 0);
        }
        DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        long before = System.currentTimeMillis();
        try (StandardJavaFileManager fm = compiler
                .getStandardFileManager(collected, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(
                    sources.stream().map(Path::toFile).toList());
            List<String> options = List.of("-classpath", classpath, "-d",
                    classesDir.toString(), "-proc:none", "-encoding", "UTF-8",
                    "-nowarn");
            boolean ok = compiler
                    .getTask(null, fm, collected, options, null, units).call();
            List<Message> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : collected
                    .getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(toMessage(d));
                }
            }
            if (!ok && errors.isEmpty()) {
                errors.add(new Message("-", 0, 0, "unknown",
                        "compilation failed without an error diagnostic",
                        Optional.empty()));
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            List<String> written = ok ? classesWrittenSince(before) : List.of();
            return new Result(ok, errors, written, millis);
        } catch (IOException e) {
            return new Result(false, List.of(new Message("-", 0, 0, "io-error",
                    String.valueOf(e.getMessage()), Optional.empty())),
                    List.of(), (System.nanoTime() - started) / 1_000_000);
        }
    }

    private Message toMessage(Diagnostic<? extends JavaFileObject> d) {
        String file = d.getSource() == null ? "-"
                : Path.of(d.getSource().getName()).getFileName().toString();
        String text = tidy(d.getMessage(null));
        return new Message(file, d.getLineNumber(), d.getColumnNumber(),
                String.valueOf(d.getCode()), text, hintFor(d.getCode(), text));
    }

    /**
     * javac's messages are multi-line and, once flattened, repetitive
     * ("cannot find symbol symbol: method x()"). Output length is a real cost
     * for an agent reading this every apply, so collapse the duplication and
     * shorten fully-qualified names to simple ones.
     */
    static String tidy(String raw) {
        String text = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        text = text.replace("cannot find symbol symbol:", "cannot find symbol:");
        text = text.replaceAll("\\s+symbol:\\s+", " ");
        text = text.replaceAll("\\s+location:\\s+", " in ");
        // com.example.foo.Bar -> Bar, but leave lowercase-only words alone.
        text = text.replaceAll("\\b(?:[a-z][a-zA-Z0-9_]*\\.)+([A-Z][a-zA-Z0-9_]*)",
                "$1");
        return text;
    }

    /**
     * A next-action hint for the handful of errors an agent actually hits.
     * Deliberately narrow: a wrong hint is worse than none, so anything
     * unrecognised gets no hint rather than a guess.
     */
    private Optional<String> hintFor(String code, String text) {
        if (code == null) {
            return Optional.empty();
        }
        return switch (code) {
        case "compiler.err.cant.resolve.location.args",
                "compiler.err.cant.resolve.location" -> Optional.of(
                        "check the name, or add the missing member/import");
        case "compiler.err.cant.resolve.location.args.params" -> Optional
                .of("check the argument types at the call site");
        case "compiler.err.prob.found.req" -> Optional
                .of("types do not match; adjust the value or the declaration");
        case "compiler.err.does.not.override.abstract" -> Optional
                .of("implement the missing abstract method(s)");
        case "compiler.err.missing.ret.stmt" -> Optional
                .of("add a return statement on every path");
        case "compiler.err.expected", "compiler.err.illegal.start.of.expr" -> Optional
                .of("syntax error - check brackets and semicolons nearby");
        default -> Optional.empty();
        };
    }

    private List<String> classesWrittenSince(long epochMillis) {
        if (!Files.isDirectory(classesDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(classesDir)) {
            return walk.filter(p -> p.toString().endsWith(".class")).filter(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis() >= epochMillis;
                } catch (IOException e) {
                    return false;
                }
            }).map(p -> classesDir.relativize(p).toString()
                    .replace(File.separatorChar, '.')
                    .replaceAll("\\.class$", "")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    Path sourceDir() {
        return sourceDir;
    }

    String relative(Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }
}
