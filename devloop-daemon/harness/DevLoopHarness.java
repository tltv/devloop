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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Phase 0 measurement harness.
 * <p>
 * Answers the one question that can invalidate the product thesis: of realistic
 * agent-style edits, how many can be applied by an atomic in-process
 * {@code redefineClasses}, and how many escalate to a restart?
 * <p>
 * For each edit it patches the source, compiles it with
 * {@link javax.tools.JavaCompiler} (the compiler the plan recommends for the
 * daemon, so this doubles as a compile-leg prototype), asks the running app to
 * redefine the affected classes, records the outcome, then reverts and restores
 * the app to its baseline bytecode so edits stay independent.
 * <p>
 * Run from the demo-app module directory, whose sources and target/ it patches.
 * Usage: {@code java ../devloop-daemon/harness/DevLoopHarness.java --label jdk25 [--only <edit>] [--keep]}
 */
public class DevLoopHarness {

    static final Path ROOT = Path.of(".");
    static final Path SRC = ROOT.resolve("src/main/java");
    static final Path CLASSES = ROOT.resolve("target/classes");
    static final Path VIEW = SRC
            .resolve("com/dev/vaadin/example/examplefeature/ui/TaskListView.java");
    static final Path SERVICE = SRC
            .resolve("com/dev/vaadin/example/examplefeature/TaskService.java");
    static final Path ENTITY = SRC
            .resolve("com/dev/vaadin/example/examplefeature/Task.java");
    static final Path LAYOUT = SRC
            .resolve("com/dev/vaadin/example/base/ui/MainLayout.java");
    static final Path TITLE = SRC
            .resolve("com/dev/vaadin/example/base/ui/ViewTitle.java");
    static final Path NEW_VIEW = SRC
            .resolve("com/dev/vaadin/example/examplefeature/ui/AboutView.java");

    /**
     * @param kind
     *            what a developer would call this change, used to group results
     * @param find
     *            text to replace; {@code null} means "create this file"
     */
    record Edit(String label, String kind, Path file, String find,
            String replace) {
    }

    static final String NEW_VIEW_SOURCE = """
            package com.dev.vaadin.example.examplefeature.ui;

            import com.dev.vaadin.example.base.ui.ViewTitle;
            import com.vaadin.flow.component.html.Paragraph;
            import com.vaadin.flow.component.orderedlayout.VerticalLayout;
            import com.vaadin.flow.router.PageTitle;
            import com.vaadin.flow.router.Route;

            @Route("about")
            @PageTitle("About")
            public class AboutView extends VerticalLayout {
                public AboutView() {
                    add(new ViewTitle("About"), new Paragraph("Harness fixture."));
                }
            }
            """;

    static List<Edit> edits() {
        List<Edit> e = new ArrayList<>();

        // --- method bodies: the changes hot reload is supposed to be good at
        e.add(new Edit("grid-header-rename", "method body", VIEW,
                ".setHeader(\"Description\")", ".setHeader(\"Task\")"));
        e.add(new Edit("notification-text", "method body", VIEW,
                "Notification.show(\"Task added\"",
                "Notification.show(\"Task created\""));
        e.add(new Edit("field-placeholder", "method body", VIEW,
                "setPlaceholder(\"What do you want to do?\")",
                "setPlaceholder(\"What needs doing?\")"));
        e.add(new Edit("empty-state-text", "method body", VIEW,
                "setEmptyStateText(\"You have no tasks to complete\")",
                "setEmptyStateText(\"Nothing to do\")"));
        e.add(new Edit("notification-duration", "method body", VIEW,
                "\"Task added\", 3000,", "\"Task added\", 5000,"));
        e.add(new Edit("button-label", "method body", VIEW,
                "new Button(\"Create\"", "new Button(\"Add\""));
        e.add(new Edit("validation-message", "method body", VIEW,
                "setErrorMessage(\"Description is required\")",
                "setErrorMessage(\"Please describe the task\")"));
        e.add(new Edit("service-trim-input", "method body", SERVICE,
                "new Task(description, Instant.now())",
                "new Task(description.trim(), Instant.now())"));
        e.add(new Edit("service-defensive-copy", "method body", SERVICE,
                "return taskRepository.findAllBy(pageable).toList();",
                "return List.copyOf(taskRepository.findAllBy(pageable).toList());"));
        e.add(new Edit("layout-app-name", "method body", LAYOUT,
                "var appName = new Span(\"My Application\");",
                "var appName = new Span(\"Task Manager\");"));
        e.add(new Edit("layout-footer-text", "method body", LAYOUT,
                "new Span(\"Made with ❤️ with Vaadin\")",
                "new Span(\"Made with Vaadin\")"));
        e.add(new Edit("viewtitle-classname", "method body", TITLE,
                "addClassName(\"view-title\")",
                "addClassName(\"view-title-wide\")"));

        // --- the RFC's own worked example: add a column to the grid.
        // Reads like a method-body change, but the lambda compiles to a new
        // synthetic method on the class.
        e.add(new Edit("add-grid-column-lambda", "add column (lambda)", VIEW,
                "        taskGrid.setEmptyStateText(",
                "        taskGrid.addColumn(task -> \"open\").setHeader(\"Status\");\n"
                        + "        taskGrid.setEmptyStateText("));

        // --- diagnostic: one redefine carrying BOTH a method-body change and
        // an added method. If the rename lands but the column does not, the JVM
        // applied the redefinition partially while reporting success.
        e.add(new Edit("combined-rename-and-add", "body + add method", VIEW,
                "taskGrid.addColumn(Task::getDescription).setHeader(\"Description\");",
                "taskGrid.addColumn(Task::getDescription).setHeader(\"Renamed\");\n"
                        + "        taskGrid.addColumn(task -> \"open\").setHeader(\"Status\");"));

        // --- annotations: route/title/menu metadata
        e.add(new Edit("route-path-change", "annotation", VIEW,
                "@Route(value = \"\")", "@Route(value = \"tasks\")"));
        e.add(new Edit("page-title-change", "annotation", VIEW,
                "@PageTitle(\"Task List\")", "@PageTitle(\"Tasks\")"));
        e.add(new Edit("menu-title-change", "annotation", VIEW,
                "title = \"Task List\")", "title = \"Tasks\")"));

        // --- structural: what should escalate on a stock JVM
        e.add(new Edit("add-private-method", "add method", VIEW,
                "    private void createTask() {",
                "    private String statusLabel() {\n        return \"open\";\n    }\n\n    private void createTask() {"));
        e.add(new Edit("add-public-method", "add method", SERVICE,
                "    @Transactional(readOnly = true)",
                "    public long count() {\n        return taskRepository.count();\n    }\n\n    @Transactional(readOnly = true)"));
        e.add(new Edit("add-field", "add field", VIEW,
                "    private final TaskService taskService;",
                "    private final TaskService taskService;\n    private int refreshCount;"));
        e.add(new Edit("entity-add-field", "add field (JPA entity)", ENTITY,
                "    protected Task() { // To keep Hibernate happy",
                "    @Column(name = \"status\")\n    private String status = \"open\";\n\n    protected Task() { // To keep Hibernate happy"));
        e.add(new Edit("change-superclass", "change hierarchy", VIEW,
                "class TaskListView extends VerticalLayout {",
                "class TaskListView extends HorizontalLayout {"));

        // --- constant inlining: redefines fine, but callers hold the old value
        e.add(new Edit("change-constant", "change constant", ENTITY,
                "public static final int DESCRIPTION_MAX_LENGTH = 300;",
                "public static final int DESCRIPTION_MAX_LENGTH = 500;"));

        // --- a brand new routed view: never loaded, so nothing to redefine
        e.add(new Edit("add-new-view", "new class", NEW_VIEW, null,
                NEW_VIEW_SOURCE));

        return e;
    }

    public static void main(String[] args) throws Exception {
        String label = arg(args, "--label", "unknown");
        String only = arg(args, "--only", null);
        boolean keep = List.of(args).contains("--keep");

        DaemonClient daemon = DaemonClient.discover(ROOT);
        System.out.println("[harness] via daemon  |  " + daemon.status());
        System.out.println("[harness] jvm label: " + label);
        System.out.println();

        String classpath = Files
                .readString(ROOT.resolve("target/devloop/cp.txt")).trim()
                + File.pathSeparator + CLASSES;

        List<String[]> results = new ArrayList<>();
        for (Edit edit : edits()) {
            if (only != null && !only.equals(edit.label())) {
                continue;
            }
            results.add(run(edit, classpath, daemon, keep));
        }

        report(results, label);
    }

    static String[] run(Edit edit, String classpath, DaemonClient daemon,
            boolean keep)
            throws Exception {
        boolean isNewFile = edit.find() == null;
        String original = isNewFile ? null : Files.readString(edit.file());

        if (isNewFile) {
            Files.writeString(edit.file(), edit.replace());
        } else {
            if (!original.contains(edit.find())) {
                return row(edit, "SKIP", "anchor-not-found", "");
            }
            Files.writeString(edit.file(),
                    original.replace(edit.find(), edit.replace()));
        }

        try {
            FileTime before = FileTime.fromMillis(System.currentTimeMillis());
            Thread.sleep(20); // ensure a strictly later mtime on the outputs

            List<String> diagnostics = compile(edit.file(), classpath);
            if (!diagnostics.isEmpty()) {
                return row(edit, "COMPILE-ERROR", diagnostics.get(0), "");
            }

            List<String> changed = changedClasses(before);
            if (changed.isEmpty()) {
                return row(edit, "NO-OUTPUT", "no class files written", "");
            }

            String response = daemon.redefine(changed);
            String outcome = response.startsWith("OK") ? "HOT-RELOAD"
                    : "ESCALATE";
            if (response.contains("notLoaded=" + changed.size())) {
                outcome = "NEW-CLASS";
            }
            return row(edit, outcome, response, String.valueOf(changed.size()));
        } finally {
            if (!keep) {
                if (isNewFile) {
                    Files.deleteIfExists(edit.file());
                    // Remove the stale output so the app does not keep a route
                    // to a class that no longer has a source.
                    Files.deleteIfExists(CLASSES.resolve(
                            "com/dev/vaadin/example/examplefeature/ui/AboutView.class"));
                } else {
                    Files.writeString(edit.file(), original);
                    FileTime before = FileTime
                            .fromMillis(System.currentTimeMillis());
                    Thread.sleep(20);
                    compile(edit.file(), classpath);
                    List<String> restored = changedClasses(before);
                    if (!restored.isEmpty()) {
                        // Put the baseline bytecode back into the running JVM
                        // so the next edit starts from a clean state.
                        daemon.redefine(restored);
                    }
                }
            }
        }
    }

    static List<String> compile(Path file, String classpath) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler
                .getStandardFileManager(collected, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm
                    .getJavaFileObjects(file.toFile());
            List<String> options = List.of("-classpath", classpath, "-d",
                    CLASSES.toString(), "-proc:none", "-encoding", "UTF-8",
                    "-nowarn");
            boolean ok = compiler
                    .getTask(null, fm, collected, options, null, units).call();
            List<String> errors = new ArrayList<>();
            if (!ok) {
                for (Diagnostic<? extends JavaFileObject> d : collected
                        .getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        // This is exactly the daemon's diagnostics[] contract:
                        // file, line, column, code, message - already structured.
                        errors.add(shortName(d) + ":" + d.getLineNumber() + ":"
                                + d.getColumnNumber() + " " + d.getCode() + " "
                                + d.getMessage(null).replaceAll("\\s+", " "));
                    }
                }
                if (errors.isEmpty()) {
                    errors.add("compilation failed without an error diagnostic");
                }
            }
            return errors;
        }
    }

    static String shortName(Diagnostic<? extends JavaFileObject> d) {
        return d.getSource() == null ? "?"
                : Path.of(d.getSource().getName()).getFileName().toString();
    }

    /** Class files written after the given instant, as binary class names. */
    static List<String> changedClasses(FileTime since) throws IOException {
        try (Stream<Path> walk = Files.walk(CLASSES)) {
            return walk.filter(p -> p.toString().endsWith(".class")).filter(p -> {
                try {
                    return Files.getLastModifiedTime(p).compareTo(since) > 0;
                } catch (IOException e) {
                    return false;
                }
            }).map(p -> CLASSES.relativize(p).toString()
                    .replace(File.separatorChar, '.').replace('/', '.')
                    .replaceAll("\\.class$", ""))
                    .sorted(Comparator.naturalOrder()).toList();
        }
    }

    static String[] row(Edit edit, String outcome, String detail,
            String classCount) {
        return new String[] { edit.label(), edit.kind(), outcome, classCount,
                detail };
    }

    static void report(List<String[]> results, String label) throws IOException {
        System.out.printf("%-26s %-22s %-12s %-4s%n", "EDIT", "KIND", "OUTCOME",
                "CLS");
        System.out.println("-".repeat(72));
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String[] r : results) {
            System.out.printf("%-26s %-22s %-12s %-4s%n", r[0], r[1], r[2],
                    r[3]);
            if (!r[4].isBlank() && !r[2].equals("HOT-RELOAD")) {
                System.out.println("      " + r[4]);
            }
            tally.merge(r[2], 1, Integer::sum);
        }
        System.out.println("-".repeat(72));
        int total = results.size();
        System.out.println("jvm=" + label + " total=" + total + " " + tally);
        int hot = tally.getOrDefault("HOT-RELOAD", 0);
        System.out.printf("hot-reloadable: %d/%d (%.0f%%)%n", hot, total,
                100.0 * hot / total);

        Path out = ROOT.resolve("target/devloop/results-" + label + ".tsv");
        StringBuilder sb = new StringBuilder(
                "edit\tkind\toutcome\tclasses\tdetail\n");
        for (String[] r : results) {
            sb.append(String.join("\t", r)).append('\n');
        }
        Files.writeString(out, sb.toString());
        System.out.println("wrote " + out);
    }

    static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
