package com.vaadin.devloop.connector;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;


import com.vaadin.base.devserver.hotswap.Hotswapper;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceInitListener;

/**
 * Finding C2 of the plan, made concrete.
 * <p>
 * Nothing inside Flow ever calls {@link Hotswapper#register(VaadinService)} — a
 * search for callers across {@code flow-server} and {@code vaadin-dev-server}
 * returns zero. Its javadoc expects the hotswap tool to inject the call itself,
 * which is what HotswapAgent's Vaadin plugin does via bytecode injection.
 * <p>
 * Since registration is gated only on {@code !isProductionMode()}, a plain
 * service init listener is enough — no agent bytecode injection needed. Without
 * this call, a successful {@code redefineClasses} changes the running bytecode
 * but the route registry is never refreshed and no refresh command reaches the
 * browser: a green apply on a stale page.
 * <p>
 * Registration is guarded so that it happens exactly once even if this listener
 * is discovered through both the Spring context and {@code ServiceLoader} — two
 * {@code Hotswapper} instances would double every refresh.
 */
public class DevLoopServiceInitListener implements VaadinServiceInitListener {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static volatile Hotswapper hotswapper;
    private static volatile VaadinService service;

    @Override
    public void serviceInit(ServiceInitEvent event) {
        if (!REGISTERED.compareAndSet(false, true)) {
            System.out.println(
                    "[devloop] Hotswapper.register() skipped: already registered");
            return;
        }
        VaadinService service = event.getSource();
        DevLoopServiceInitListener.service = service;

        Optional<Hotswapper> registered = Hotswapper.register(service);
        registered.ifPresent(hs -> hotswapper = hs);

        System.out.println("[devloop] Hotswapper.register() -> "
                + (registered.isPresent() ? "registered"
                        : "skipped (production mode)"));

        if (registered.isPresent()) {
            DevLoopRegistration.start(service);
            // Flow's own CSS watcher would push on save and, on Windows, force a
            // full page reload. The daemon owns the resource leg now.
            FlowResourceWatcherSuppressor.suppress(service);
        }
    }

    /**
     * The registered hotswapper, driven after each atomic redefine.
     */
    public static Hotswapper getHotswapper() {
        return hotswapper;
    }

    /** The dev-mode service, for the frontend and resource legs. */
    public static VaadinService getService() {
        return service;
    }
}
