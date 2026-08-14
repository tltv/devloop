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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Phase 0.5 — what does HotswapAgent actually cover on this stack?
 * <p>
 * P0 measured which edits the JVM will accept. This measures something else:
 * whether an accepted edit is <em>semantically</em> live once a framework is
 * involved. Both tests are deliberately multi-file, because that is what an
 * agent actually does — add a method and call it.
 * <p>
 * Test {@code spring}: add a {@code @Transactional} method to a service and call
 * it from the view. The service bean is a CGLIB proxy created before the
 * redefine, so the question is whether HA's Spring plugin refreshes it. A
 * non-intercepted call on such a proxy typically NPEs on a null field.
 * <p>
 * Test {@code jpa}: add a mapped column to the {@code @Entity} and show it in the
 * grid. Needs both the Hibernate metamodel and the H2 schema to update; the
 * grid's SELECT will fail if only the metamodel did.
 * <p>
 * Edits are left in place for browser verification. {@code --revert} undoes them.
 *
 * Usage: java ../devloop-daemon/harness/P05Harness.java --test spring|jpa [--revert]
 */
public class P05Harness {

    static final Path ROOT = Path.of(".");
    static final Path SRC = ROOT.resolve("src/main/java");
    static final Path CLASSES = ROOT.resolve("target/classes");
    static final Path VIEW = SRC
            .resolve("com/dev/vaadin/example/examplefeature/ui/TaskListView.java");
    static final Path SERVICE = SRC
            .resolve("com/dev/vaadin/example/examplefeature/TaskService.java");
    static final Path ENTITY = SRC
            .resolve("com/dev/vaadin/example/examplefeature/Task.java");

    record Change(Path file, String find, String replace) {
    }

    /** Adds a @Transactional service method and calls it from the view. */
    static List<Change> springTest() {
        // Single-line anchors only: git restores these files with CRLF, so a
        // multi-line anchor containing \n stops matching after a checkout.
        // Anchoring on list()'s signature means the existing
        // @Transactional(readOnly = true) above it lands on countTasks(), and
        // the annotation is re-added for list() - which is exactly the shape we
        // want to test.
        return List.of(
                new Change(SERVICE, "    public List<Task> list(Pageable pageable) {",
                        "    public long countTasks() {\n"
                                + "        return taskRepository.count();\n"
                                + "    }\n\n"
                                + "    @Transactional(readOnly = true)\n"
                                + "    public List<Task> list(Pageable pageable) {"),
                new Change(VIEW, "toolbar.add(new ViewTitle(\"Task List\")",
                        "toolbar.add(new ViewTitle(\"Task List [\" + taskService.countTasks() + \"]\")"));
    }

    /** Adds a mapped column to the entity and shows it in the grid. */
    static List<Change> jpaTest() {
        return List.of(
                new Change(ENTITY, "    protected Task() { // To keep Hibernate happy",
                        "    @Column(name = \"status\")\n"
                                + "    private String status = \"open\";\n\n"
                                + "    public String getStatus() {\n"
                                + "        return status;\n"
                                + "    }\n\n"
                                + "    protected Task() { // To keep Hibernate happy"),
                new Change(VIEW, "        taskGrid.setEmptyStateText(",
                        "        taskGrid.addColumn(Task::getStatus).setHeader(\"Status\");\n"
                                + "        taskGrid.setEmptyStateText("));
    }

    /**
     * Discriminating version of the JPA test. {@code jpa} is ambiguous: the new
     * field carries a default initializer, so the grid shows "open" whether the
     * value came from the database or from the freshly redefined bytecode — a
     * false positive of exactly the kind C7 warns about.
     * <p>
     * Here the field has <em>no</em> default and is set explicitly on create.
     * The grid re-queries, so Hibernate instantiates a fresh entity:
     * <ul>
     * <li>column mapped and schema updated → cell reads "open" (from the DB)</li>
     * <li>mapping ignored → cell is blank (field left null)</li>
     * </ul>
     */
    static List<Change> jpaStrictTest() {
        return List.of(
                new Change(ENTITY, "    protected Task() { // To keep Hibernate happy",
                        "    @Column(name = \"status\")\n"
                                + "    private String status;\n\n"
                                + "    public String getStatus() {\n"
                                + "        return status;\n"
                                + "    }\n\n"
                                + "    public void setStatus(String status) {\n"
                                + "        this.status = status;\n"
                                + "    }\n\n"
                                + "    protected Task() { // To keep Hibernate happy"),
                new Change(SERVICE, "        task.setDueDate(dueDate);",
                        "        task.setDueDate(dueDate);\n"
                                + "        task.setStatus(\"open\");"),
                new Change(VIEW, "        taskGrid.setEmptyStateText(",
                        "        taskGrid.addColumn(task -> String.valueOf(task.getStatus())).setHeader(\"Status\");\n"
                                + "        taskGrid.setEmptyStateText("));
    }

    public static void main(String[] args) throws Exception {
        String test = arg(args, "--test", "spring");
        boolean revert = List.of(args).contains("--revert");
        List<Change> changes = switch (test) {
            case "spring" -> springTest();
            case "jpa" -> jpaTest();
            case "jpa-strict" -> jpaStrictTest();
            default -> throw new IllegalArgumentException("unknown test: " + test);
        };

        if (revert) {
            // Sources are restored via git by the caller; just rebuild and push
            // the baseline bytecode back into the running JVM.
            FileTime before = stamp();
            for (Path file : distinctFiles(changes)) {
                compileOrThrow(file);
            }
            List<String> restored = changedClasses(before);
            if (!restored.isEmpty()) {
                System.out.println("restore -> "
                        + DaemonClient.discover(ROOT).redefine(restored));
            }
            return;
        }

        DaemonClient daemon = DaemonClient.discover(ROOT);
        System.out.println("[p05] via daemon | " + daemon.status());
        System.out.println("[p05] test=" + test);

        for (Change c : changes) {
            String original = Files.readString(c.file());
            if (!original.contains(c.find())) {
                throw new IllegalStateException(
                        "anchor not found in " + c.file() + ": " + c.find());
            }
            Files.writeString(c.file(),
                    original.replace(c.find(), c.replace()));
            System.out.println("[p05] patched " + c.file().getFileName());
        }

        FileTime before = stamp();
        for (Path file : distinctFiles(changes)) {
            List<String> errors = compile(file);
            if (!errors.isEmpty()) {
                System.out.println("[p05] COMPILE-ERROR " + errors.get(0));
                return;
            }
        }

        List<String> changed = changedClasses(before);
        System.out.println("[p05] changed classes: " + changed);
        System.out.println("[p05] redefine -> " + daemon.redefine(changed));
        System.out.println("[p05] now verify in the browser (edits left in place)");
    }

    static List<Path> distinctFiles(List<Change> changes) {
        Set<Path> files = new LinkedHashSet<>();
        changes.forEach(c -> files.add(c.file()));
        return List.copyOf(files);
    }

    static FileTime stamp() throws InterruptedException {
        FileTime t = FileTime.fromMillis(System.currentTimeMillis());
        Thread.sleep(20);
        return t;
    }

    static void compileOrThrow(Path file) throws IOException {
        List<String> errors = compile(file);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("compile failed: " + errors.get(0));
        }
    }

    static List<String> compile(Path file) throws IOException {
        String classpath = Files.readString(ROOT.resolve("target/devloop/cp.txt"))
                .trim() + File.pathSeparator + CLASSES;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                collected, null, StandardCharsets.UTF_8)) {
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
                        errors.add(d.getSource().getName() + ":"
                                + d.getLineNumber() + " "
                                + d.getMessage(null).replaceAll("\\s+", " "));
                    }
                }
            }
            return errors;
        }
    }

    static List<String> changedClasses(FileTime since) throws IOException {
        try (Stream<Path> walk = Files.walk(CLASSES)) {
            return walk.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p)
                                    .compareTo(since) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> CLASSES.relativize(p).toString()
                            .replace(File.separatorChar, '.')
                            .replaceAll("\\.class$", ""))
                    .sorted().toList();
        }
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
