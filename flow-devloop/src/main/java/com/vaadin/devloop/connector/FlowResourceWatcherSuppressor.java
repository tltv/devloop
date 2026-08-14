package com.vaadin.devloop.connector;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.vaadin.flow.di.Lookup;
import com.vaadin.flow.internal.DevModeHandlerManager;
import com.vaadin.flow.server.VaadinService;

/**
 * Stops Flow's own public-resources watcher, so that the daemon is the only actor
 * that touches the browser.
 * <p>
 * Flow starts a {@code PublicResourcesLiveUpdater} in dev mode which watches the
 * CSS source folders itself. Two problems:
 * <ul>
 * <li>It fires on <em>save</em>, not on apply. The whole point of the transaction
 * model is that one command decides when a change goes live; a second watcher
 * pushing on its own makes "what is the state of my last change?" unanswerable
 * again.</li>
 * <li>On Windows it cannot succeed. {@code isVaadinThemeUrl} runs
 * {@code new File(url).toPath()} over a {@code context://} stylesheet URL, and a
 * colon is illegal in a Windows path, so every CSS change throws
 * {@code InvalidPathException} and it falls back to a <em>full page reload</em> -
 * discarding the in-place update the daemon just performed.</li>
 * </ul>
 * There is no supported way to switch it off: {@code DevModeHandlerManagerImpl}
 * starts it unconditionally and keeps it only inside a shutdown lambda. So this
 * reaches it reflectively, and fails quietly if Flow's internals move - losing the
 * suppression is a cosmetic regression, not a broken loop.
 * <p>
 * The right fix is upstream: either repair the Windows path handling, or give Flow
 * a way for a hotswap owner to declare that it owns the resource leg.
 */
final class FlowResourceWatcherSuppressor {

    private static final String TARGET = "PublicResourcesLiveUpdater";

    private FlowResourceWatcherSuppressor() {
    }

    /**
     * Flow creates the watcher asynchronously, after the dev server or bundle is
     * ready, so this polls briefly rather than assuming it exists yet.
     */
    static void suppress(VaadinService service) {
        Thread thread = new Thread(() -> {
            for (int attempt = 0; attempt < 120; attempt++) {
                if (tryOnce(service)) {
                    return;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            System.out.println("[devloop] Flow's " + TARGET + " was not found; "
                    + "CSS edits may still trigger their own page reload");
        }, "devloop-watcher-suppressor");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean tryOnce(VaadinService service) {
        try {
            Lookup lookup = service.getContext().getAttribute(Lookup.class);
            if (lookup == null) {
                return false;
            }
            DevModeHandlerManager manager = lookup
                    .lookup(DevModeHandlerManager.class);
            if (manager == null) {
                return false;
            }
            Field field = findField(manager.getClass(), "shutdownCommands");
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            Object value = field.get(manager);
            if (!(value instanceof Set<?> commands)) {
                return false;
            }
            // Each entry is a lambda that captured the watcher it closes; the
            // capture shows up as a synthetic field on the lambda class.
            synchronized (commands) {
                for (Object command : Set.copyOf(commands)) {
                    Closeable watcher = capturedWatcher(command);
                    if (watcher != null) {
                        watcher.close();
                        commands.remove(command);
                        System.out.println("[devloop] closed Flow's " + TARGET
                                + "; apply is now the only trigger for CSS");
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            System.out.println(
                    "[devloop] could not suppress Flow's " + TARGET + ": " + t);
            return true; // Do not keep retrying something that is failing hard.
        }
    }

    private static Closeable capturedWatcher(Object lambda) {
        for (Field captured : lambda.getClass().getDeclaredFields()) {
            try {
                captured.setAccessible(true);
                Object value = captured.get(lambda);
                if (value instanceof Closeable closeable && value.getClass()
                        .getSimpleName().equals(TARGET)) {
                    return closeable;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Not the capture we are looking for.
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Try the superclass.
            }
        }
        return null;
    }
}
