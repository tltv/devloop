package com.vaadin.devloop.connector;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vaadin.base.devserver.hotswap.Hotswapper;
import com.vaadin.flow.internal.BrowserLiveReloadAccessor;
import com.vaadin.flow.internal.DevModeHandlerManager;
import com.vaadin.flow.server.VaadinService;

/**
 * The runtime leg, in-process: one atomic redefine of every changed class,
 * followed by the {@code onHotswap} call that makes Vaadin notice.
 * <p>
 * Two hard-won rules from the earlier phases are encoded here.
 * <ul>
 * <li><b>Every loaded copy.</b> A binary name can map to more than one loaded
 * {@code Class}; redefining one leaves the copy the app instantiates untouched
 * while {@code redefineClasses} still reports success. That is the silent
 * "green apply on a stale page" failure, so all copies go into the one call.</li>
 * <li><b>Report what cannot work.</b> A successful redefine is not proof the
 * change is live. Entity mappings never are, and a structural change to a Spring
 * bean is actively broken without HotswapAgent, so the classes involved are
 * reported back and the daemon escalates instead of claiming success.</li>
 * </ul>
 */
final class DevLoopRedefiner {

    private DevLoopRedefiner() {
    }

    /** Answers a single {@code REDEFINE a.b.C,a.b.D} request. */
    static String redefine(String csv) {
        Instrumentation inst = instrumentation();
        if (inst == null) {
            return "ERR kind=no-agent message=Instrumentation-unavailable";
        }
        Hotswapper hotswapper = DevLoopServiceInitListener.getHotswapper();
        if (hotswapper == null) {
            return "ERR kind=no-hotswapper message=Hotswapper-not-registered";
        }

        List<String> requested = Arrays.stream(csv.split(",")).map(String::trim)
                .filter(name -> !name.isEmpty()).toList();
        if (requested.isEmpty()) {
            return "ERR kind=protocol message=no-classes";
        }

        Path classesDir = Paths
                .get(System.getProperty("devloop.classes", "target/classes"));

        Map<String, List<Class<?>>> loaded = new HashMap<>();
        for (Class<?> candidate : inst.getAllLoadedClasses()) {
            if (requested.contains(candidate.getName())) {
                loaded.computeIfAbsent(candidate.getName(),
                        key -> new ArrayList<>()).add(candidate);
            }
        }

        List<ClassDefinition> definitions = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();
        int duplicates = 0;
        Set<String> entities = new LinkedHashSet<>();
        Set<String> beans = new LinkedHashSet<>();

        for (String name : requested) {
            List<Class<?>> targets = loaded.getOrDefault(name, List.of());
            if (targets.isEmpty()) {
                notLoaded.add(name);
                continue;
            }
            if (targets.size() > 1) {
                duplicates += targets.size() - 1;
            }
            Class<?> first = targets.get(0);
            if (isEntity(first)) {
                entities.add(simple(name));
            }
            if (isSpringBean(first)) {
                beans.add(simple(name));
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(
                        classesDir.resolve(name.replace('.', '/') + ".class"));
            } catch (IOException e) {
                return "ERR kind=missing-class-file message=" + name;
            }
            for (Class<?> target : targets) {
                definitions.add(new ClassDefinition(target, bytes));
            }
        }

        DevLoopHotswapper observer = DevLoopHotswapper.getActive();
        if (observer != null) {
            observer.reset();
        }

        // Member signatures before the redefine, so a structural change can be
        // named afterwards. On a stock JVM such a change is simply rejected, but
        // an enhanced-redefinition JVM accepts it - and that is exactly when a
        // Spring bean's existing proxy silently stops matching the class.
        Map<String, String> before = new HashMap<>();
        for (ClassDefinition definition : definitions) {
            before.putIfAbsent(definition.getDefinitionClass().getName(),
                    members(definition.getDefinitionClass()));
        }

        long redefineStart = System.nanoTime();
        if (!definitions.isEmpty()) {
            try {
                inst.redefineClasses(
                        definitions.toArray(new ClassDefinition[0]));
            } catch (Throwable t) {
                return "ERR kind=redefine-rejected class="
                        + t.getClass().getSimpleName() + " message="
                        + oneLine(String.valueOf(t.getMessage()));
            }
        }
        long redefineMs = (System.nanoTime() - redefineStart) / 1_000_000;

        Set<String> structural = new LinkedHashSet<>();
        for (ClassDefinition definition : definitions) {
            Class<?> type = definition.getDefinitionClass();
            String previous = before.get(type.getName());
            if (previous != null && !previous.equals(members(type))) {
                structural.add(simple(type.getName()));
            }
        }

        long hotswapStart = System.nanoTime();
        hotswapper.onHotswap(requested.toArray(new String[0]), Boolean.TRUE);
        long hotswapMs = (System.nanoTime() - hotswapStart) / 1_000_000;

        boolean completed = observer != null && observer.isCompleted();
        boolean pageReload = observer != null && observer.isPageReloadRequired();

        return "OK redefined=" + definitions.size() + " notLoaded="
                + notLoaded.size() + " dupes=" + duplicates + " completed="
                + completed + " pageReload=" + pageReload + " entities="
                + join(entities) + " beans=" + join(beans) + " structural="
                + join(structural) + " hotswapAgent=" + hotswapAgentLoaded()
                + " redefineMs=" + redefineMs + " hotswapMs=" + hotswapMs;
    }

    /**
     * The resource leg. Notifies Flow of changed resources and then asks the
     * browser to re-fetch.
     * <p>
     * A browser reload rather than in-place CSS replacement, deliberately:
     * {@code StyleSheetHotswapper.onResourcesChange} is an explicit no-op
     * ("changes in CSS files are handled by a dedicated file watcher"), so there
     * is no supported hook for the daemon to push CSS in place. Flow's own
     * watcher does that - and on Windows it throws on any {@code context:}
     * stylesheet URL, falling back to a page reload that then serves the stale
     * classpath copy. The daemon refreshes the classpath copy first, which is
     * what makes this reload show the new content at all.
     */
    static String resources(String csv) {
        Hotswapper hotswapper = DevLoopServiceInitListener.getHotswapper();
        if (hotswapper == null) {
            return "ERR kind=no-hotswapper message=Hotswapper-not-registered";
        }
        List<URI> uris = new ArrayList<>();
        for (String value : csv.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                try {
                    uris.add(Paths.get(trimmed).toUri());
                } catch (RuntimeException e) {
                    return "ERR kind=protocol message=bad-path:" + trimmed;
                }
            }
        }
        if (uris.isEmpty()) {
            return "ERR kind=protocol message=no-resources";
        }

        long started = System.nanoTime();
        URI[] none = new URI[0];
        hotswapper.onHotswap(none, uris.toArray(new URI[0]), none);

        // Prefer pushing the content in place. A plain browser reload loses a
        // race that the daemon's own speed creates: static resources are served
        // with Cache-Control: no-cache plus Last-Modified, whose one-second
        // granularity means a copy followed ~10 ms later by a reload revalidates
        // to 304 and the browser keeps the previous CSS. Pushing content skips
        // HTTP entirely, and skips the Windows-broken watcher path too.
        int pushed = pushStyleSheets(csv);
        boolean reloaded = pushed == 0 && requestBrowserReload();
        return "OK resources=" + uris.size() + " pushed=" + pushed
                + " browserReload=" + reloaded + " ms="
                + (System.nanoTime() - started) / 1_000_000;
    }

    /**
     * Sends new CSS content straight to the browser for each changed stylesheet,
     * keyed by the URL the client knows it as - the file name relative to the
     * public resource root, which is what {@code @StyleSheet("styles.css")}
     * resolves to.
     */
    private static int pushStyleSheets(String csv) {
        VaadinService service = DevLoopServiceInitListener.getService();
        if (service == null) {
            return 0;
        }
        var liveReload = BrowserLiveReloadAccessor
                .getLiveReloadFromService(service);
        if (liveReload.isEmpty()) {
            return 0;
        }
        int pushed = 0;
        for (String value : csv.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.endsWith(".css")) {
                continue;
            }
            Path path = Paths.get(trimmed);
            String url = publicUrlOf(path);
            if (url == null) {
                continue;
            }
            try {
                liveReload.get().update(url,
                        Files.readString(path, java.nio.charset.StandardCharsets.UTF_8));
                pushed++;
            } catch (IOException | RuntimeException e) {
                System.out.println(
                        "[devloop] could not push " + url + ": " + e);
            }
        }
        return pushed;
    }

    /** Maps a file under a public resource root to its served URL. */
    private static String publicUrlOf(Path path) {
        String normalized = path.toString().replace('\\', '/');
        for (String root : new String[] { "/META-INF/resources/", "/static/",
                "/public/", "/resources/" }) {
            int at = normalized.indexOf(root);
            if (at >= 0) {
                return normalized.substring(at + root.length());
            }
        }
        return null;
    }

    private static boolean requestBrowserReload() {
        VaadinService service = DevLoopServiceInitListener.getService();
        if (service == null) {
            return false;
        }
        return BrowserLiveReloadAccessor.getLiveReloadFromService(service)
                .map(liveReload -> {
                    liveReload.reload();
                    return true;
                }).orElse(false);
    }

    /**
     * Whether the Vite dev server is still alive.
     * <p>
     * Not via {@code DevServerWatchDog}: that runs the other way round - the JVM
     * opens a socket so <em>Vite</em> can notice the JVM died - and it is
     * package-private. The dev-mode handler exposes Vite's port, and connecting
     * to an HTTP port is a harmless liveness check (unlike a JDWP handshake,
     * which would disable the listener).
     */
    static String frontendStatus() {
        VaadinService service = DevLoopServiceInitListener.getService();
        if (service == null) {
            return "unknown";
        }
        var handler = DevModeHandlerManager.getDevModeHandler(service);
        if (handler.isEmpty()) {
            return "none";
        }
        String kind = handler.get().getClass().getSimpleName();
        int port = handler.get().getPort();
        if (port <= 0) {
            // Two different states share "no port", and they need different
            // answers: the bundle-building handler never gets one, while a dev
            // server that has not finished booting will. Conflating them would
            // tell an agent to wait for something that is never coming.
            return kind.contains("Bundle") ? "no-dev-server(" + kind + ")"
                    : "starting(" + kind + ")";
        }
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    port), 750);
            return "up:" + port;
        } catch (IOException e) {
            return "down:" + port;
        }
    }

    /** What the daemon needs to know about this JVM's reload capabilities. */
    static String info() {
        Instrumentation inst = instrumentation();
        return "OK instrumentation=" + (inst != null) + " redefineSupported="
                + (inst != null && inst.isRedefineClassesSupported())
                + " hotswapAgent=" + hotswapAgentLoaded() + " hotswapper="
                + (DevLoopServiceInitListener.getHotswapper() != null)
                + " enhancedRedefinition=" + enhancedRedefinition()
                + " frontend=" + frontendStatus();
    }

    private static boolean hotswapAgentLoaded() {
        try {
            Class.forName("org.hotswap.agent.HotswapAgent", false,
                    ClassLoader.getSystemClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Enhanced redefinition is a JVM feature, not a HotswapAgent one, so it is
     * detected separately - the two can be present independently.
     */
    private static boolean enhancedRedefinition() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments().stream()
                .anyMatch(arg -> arg.contains("AllowEnhancedClassRedefinition"));
    }

    private static boolean isEntity(Class<?> type) {
        return hasAnnotation(type, "jakarta.persistence.Entity",
                "jakarta.persistence.MappedSuperclass",
                "jakarta.persistence.Embeddable");
    }

    private static boolean isSpringBean(Class<?> type) {
        return hasAnnotation(type, "org.springframework.stereotype.Component",
                "org.springframework.stereotype.Service",
                "org.springframework.stereotype.Repository",
                "org.springframework.stereotype.Controller",
                "org.springframework.transaction.annotation.Transactional");
    }

    /**
     * Name-based so the connector needs no compile-time dependency on Spring or
     * JPA; also checks one level of meta-annotation, which is how
     * {@code @Service} and friends are composed.
     */
    private static boolean hasAnnotation(Class<?> type, String... wanted) {
        Set<String> names = new LinkedHashSet<>();
        for (var annotation : type.getAnnotations()) {
            names.add(annotation.annotationType().getName());
            for (var meta : annotation.annotationType().getAnnotations()) {
                names.add(meta.annotationType().getName());
            }
        }
        for (String candidate : wanted) {
            if (names.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Instrumentation instrumentation() {
        Object value = System.getProperties().get("devloop.instrumentation");
        return value instanceof Instrumentation inst ? inst : null;
    }

    /**
     * A stable fingerprint of a class's declared members, used to tell a
     * method-body change from one that alters the class's shape.
     */
    private static String members(Class<?> type) {
        List<String> signatures = new ArrayList<>();
        for (var method : type.getDeclaredMethods()) {
            signatures.add("m:" + method.getName() + Arrays
                    .toString(method.getParameterTypes()));
        }
        for (var field : type.getDeclaredFields()) {
            signatures.add("f:" + field.getName() + ":"
                    + field.getType().getName());
        }
        java.util.Collections.sort(signatures);
        return String.join(";", signatures);
    }

    private static String simple(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }

    private static String join(Set<String> values) {
        return values.isEmpty() ? "-" : String.join("|", values);
    }

    private static String oneLine(String value) {
        return value == null ? "null" : value.replaceAll("\\s+", " ").trim();
    }
}
