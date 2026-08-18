package com.vaadin.devloop.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Composes the app JVM command line, and provisions HotswapAgent so the user
 * never has to.
 * <p>
 * Phase 0.5 established that getting the flag set right matters as much as
 * having the jar: without the JPMS opens, HotswapAgent's core helper fails on
 * every redefine and its plugins degrade silently. Nine flags have to be right
 * and one wrong one fails quietly, which is exactly why the daemon composes
 * them rather than a human.
 */
final class Launch {

    static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("windows");

    /** Pinned, never "latest": a changing agent would make applies irreproducible. */
    static final String HA_VERSION = "2.0.1";
    static final String HA_SHA256 = "ba8d5e0571dc7952f2455f09d9ef6ca96782c17bc3d12f61eb3a5760f2a897f1";
    static final String HA_URL = "https://github.com/HotswapProjects/HotswapAgent/releases/download/RELEASE-"
            + HA_VERSION + "/hotswap-agent-" + HA_VERSION + ".jar";

    private static final List<String> ADD_OPENS = List.of(
            "java.base/java.lang", "java.base/java.lang.reflect",
            "java.base/java.io", "java.base/java.util",
            "java.desktop/java.beans");

    private final Path root;
    private final Log log;

    Launch(Path root, Log log) {
        this.root = root;
        this.log = log;
    }

    /**
     * Where the daemon keeps its per-app runtime artifacts: the classpath cache and
     * the app log. Under the module's own {@code target/} so a clean wipes them and
     * nothing lands in a source tree.
     */
    static Path workDir(Path appModule) {
        return appModule.resolve("target").resolve("devloop");
    }

    /**
     * The agent jar to load into the app JVM.
     * <p>
     * The CLI builds it and pins the path with a property, which is the normal
     * route. The fallback looks beside this daemon's own jar rather than anywhere in
     * the project, so a hand-started daemon still works without assuming anything
     * about how the application's tree is arranged.
     */
    private Path agentJar() {
        String configured = System.getProperty("vaadin.dev.agentJar");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        try {
            Path own = Path.of(Launch.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            Path beside = own.getParent();
            if (beside != null) {
                String name = own.getFileName().toString();
                return beside.resolve(name.endsWith(".jar")
                        ? name.replaceFirst("\\.jar$", "-agent.jar")
                        : "devloop-daemon-agent.jar");
            }
        } catch (java.net.URISyntaxException | RuntimeException e) {
            log.line("could not locate the agent jar beside the daemon: " + e);
        }
        return root.resolve("devloop-daemon-agent.jar");
    }

    /**
     * Ensures the HotswapAgent jar is present and matches the pinned checksum.
     * Honours an already-present jar and an explicit override so air-gapped
     * setups still work.
     */
    Path ensureHotswapAgent() throws IOException {
        String override = System.getProperty("vaadin.dev.hotswapAgentJar");
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override);
            if (!Files.isRegularFile(path)) {
                throw new IOException(
                        "vaadin.dev.hotswapAgentJar does not exist: " + path);
            }
            return path;
        }

        Path jar = root.resolve(".vaadin")
                .resolve("hotswap-agent-" + HA_VERSION + ".jar");
        if (Files.isRegularFile(jar)) {
            String actual = sha256(jar);
            if (!HA_SHA256.equalsIgnoreCase(actual)) {
                throw new IOException("cached HotswapAgent checksum mismatch: "
                        + jar + " (expected " + HA_SHA256 + ", got " + actual
                        + "). Delete it to re-download.");
            }
            return jar;
        }

        Files.createDirectories(jar.getParent());
        log.line("provisioning HotswapAgent " + HA_VERSION + " from " + HA_URL);
        Path temp = jar.resolveSibling(jar.getFileName() + ".part");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(HA_URL))
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("download failed with HTTP "
                        + response.statusCode() + " from " + HA_URL);
            }
            try (InputStream in = response.body()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", e);
        }

        String actual = sha256(temp);
        if (!HA_SHA256.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(temp);
            throw new IOException(
                    "downloaded HotswapAgent checksum mismatch (expected "
                            + HA_SHA256 + ", got " + actual + ")");
        }
        Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
        log.line("provisioned " + jar + " (verified sha256)");
        return jar;
    }

    /** Prefers a JBR, because enhanced redefinition is a JVM feature. */
    Path javaBinary() {
        String override = System.getProperty("vaadin.dev.javaHome");
        if (override != null && !override.isBlank()) {
            return javaIn(Path.of(override));
        }
        Optional<Path> jbr = findJbr();
        return jbr.map(this::javaIn)
                .orElseGet(() -> javaIn(Path.of(System.getProperty("java.home"))));
    }

    private Optional<Path> findJbr() {
        Path jdks = Path.of(System.getProperty("user.home"), ".jdks");
        if (!Files.isDirectory(jdks)) {
            return Optional.empty();
        }
        try (var stream = Files.list(jdks)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase(
                            Locale.ROOT).startsWith("jbr"))
                    .filter(p -> Files.isRegularFile(javaIn(p)))
                    .sorted((a, b) -> b.getFileName().toString()
                            .compareTo(a.getFileName().toString()))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path javaIn(Path javaHome) {
        Path win = javaHome.resolve("bin").resolve("java.exe");
        return Files.isRegularFile(win) ? win
                : javaHome.resolve("bin").resolve("java");
    }

    static boolean supportsEnhancedRedefinition(Path javaBinary) {
        return javaBinary.toString().toLowerCase(Locale.ROOT).contains("jbr");
    }

    /**
     * The classpath, cached and invalidated on pom.xml changes - the same cache
     * the compile leg will use in P2.
     */
    String classpath() throws IOException {
        Path cache = workDir(root).resolve("cp.txt");
        Path pom = root.resolve("pom.xml");
        boolean stale = !Files.isRegularFile(cache) || (Files.isRegularFile(pom)
                && Files.getLastModifiedTime(pom)
                        .compareTo(Files.getLastModifiedTime(cache)) > 0);
        if (stale) {
            log.line("resolving classpath (pom.xml newer than cache)");
            Files.createDirectories(cache.getParent());
            // The application's own wrapper. The daemon works against one app and
            // uses only that app's build, so it looks no further than the app dir.
            // Chosen by platform, not by which file happens to exist: a project
            // generated on Windows ships both wrappers, and mvnw.cmd is a batch
            // file that a Linux or macOS shell cannot run.
            String mvnw = mavenWrapper().toString();
            Process process = new ProcessBuilder(mvnw, "-q", "-o",
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=" + cache).directory(root.toFile())
                            .redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            try {
                if (process.waitFor() != 0) {
                    // The wrapper's own words: without them a failure here is a
                    // dead end, and this is the first build the daemon ever runs.
                    throw new IOException("classpath resolution failed ("
                            + mvnw + "): " + lastLines(output, 10));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("classpath resolution interrupted", e);
            }
        }
        return Files.readString(cache).trim() + java.io.File.pathSeparator
                + root.resolve("target").resolve("classes");
    }

    /** The Maven wrapper for this platform, inside the application. */
    private Path mavenWrapper() {
        return root.resolve(WINDOWS ? "mvnw.cmd" : "mvnw");
    }

    private static String lastLines(String output, int count) {
        List<String> lines = output.strip().lines().toList();
        return String.join(" | ",
                lines.subList(Math.max(0, lines.size() - count), lines.size()));
    }

    /** The full command line, in the order a human would want to read it. */
    List<String> command(int daemonPort, String token, String mainClass)
            throws IOException {
        Path java = javaBinary();
        Path haJar = ensureHotswapAgent();
        Path connectorAgent = agentJar();

        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        cmd.add("-javaagent:" + haJar);
        if (Files.isRegularFile(connectorAgent)) {
            cmd.add("-javaagent:" + connectorAgent);
        } else {
            // Without it there is no Instrumentation, so the runtime leg can only
            // ever escalate to a restart. Say so rather than degrading quietly.
            log.line("WARNING: agent jar not found at " + connectorAgent
                    + " - every apply will restart instead of hot reloading");
        }
        if (supportsEnhancedRedefinition(java)) {
            cmd.add("-XX:+AllowEnhancedClassRedefinition");
        }
        // Vaadin: its plugin targets the 24.x package and would fire a second,
        // competing browser refresh.
        // Spring/SpringBoot: measured to corrupt the context under repeated
        // redefinitions on this stack - HA's scanner loses the Spring Data
        // repository bean ("basePackage not associated with any scannerAgent"),
        // after which the app throws NoSuchBeanDefinitionException while the
        // redefine still reported success. A structural change to a bean is
        // escalated to a restart instead, which is slower but deterministic.
        // The key is "disabledPlugins", plural and unprefixed: HotswapAgent loads
        // hotswap-agent.properties and then merges System.getProperties() over it
        // with the same key names. A wrong name is accepted silently and disables
        // nothing, which is how the Vaadin plugin kept firing its own full page
        // reload on top of Flow's soft refresh.
        cmd.add("-DdisabledPlugins=Vaadin,Spring,SpringBoot");
        cmd.add("-Dspring.devtools.restart.enabled=false");
        ADD_OPENS.forEach(target -> {
            cmd.add("--add-opens");
            cmd.add(target + "=ALL-UNNAMED");
        });
        cmd.add("-Dvaadin.launch-browser=false");
        // Forward any vaadin.* property the daemon itself was started with, so a
        // developer can steer the app's dev mode (for example
        // -Dvaadin.frontend.hotdeploy=true to run Vite rather than build a
        // bundle) without the daemon needing to know each option.
        System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.startsWith("vaadin.")
                        && !name.equals("vaadin.launch-browser"))
                .sorted().forEach(name -> cmd
                        .add("-D" + name + "=" + System.getProperty(name)));
        cmd.add("-Dvaadin.devloop.daemonPort=" + daemonPort);
        cmd.add("-Dvaadin.devloop.token=" + token);
        cmd.add("-cp");
        cmd.add(classpath());
        cmd.add(mainClass);
        return cmd;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    /** Minimal sink so provisioning progress reaches the client that asked. */
    interface Log {
        void line(String text);
    }
}
