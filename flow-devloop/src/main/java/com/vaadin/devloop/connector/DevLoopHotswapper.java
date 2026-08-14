package com.vaadin.devloop.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;

import com.vaadin.base.devserver.hotswap.HotswapClassEvent;
import com.vaadin.base.devserver.hotswap.HotswapClassSessionEvent;
import com.vaadin.base.devserver.hotswap.HotswapCompleteEvent;
import com.vaadin.base.devserver.hotswap.UIUpdateStrategy;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinService;

/**
 * An observer on Flow's existing {@link com.vaadin.base.devserver.hotswap.VaadinHotswapper}
 * SPI.
 * <p>
 * The daemon does not need to invent a completion signal or a classification:
 * Flow already computes both, and {@code onHotswapComplete} is the authoritative
 * "the runtime leg is done" event that the transaction gates on.
 * <p>
 * The {@link Priority} is deliberately large so this runs after Flow's own
 * hotswappers (they sort ascending), meaning anything they decided has already
 * been decided by the time this sees the event.
 * <p>
 * Note: Flow does not expose the refresh strategy it computed. The public
 * {@code getUIUpdateStrategy} only reports a strategy a hotswapper explicitly
 * requested, so it is logged for diagnosis but never used as the apply
 * classification - the daemon reports what it did itself instead.
 */
@Priority(10_000)
public class DevLoopHotswapper implements
        com.vaadin.base.devserver.hotswap.VaadinHotswapper {

    /**
     * Set by {@link #onInit}, which Flow's {@code Hotswapper} calls on every
     * instance it obtained from {@code Lookup}, so this points at the instance
     * that actually receives the events.
     */
    private static volatile DevLoopHotswapper active;

    private volatile boolean completed;
    private volatile boolean pageReloadRequired;

    public static DevLoopHotswapper getActive() {
        return active;
    }

    @Override
    public void onInit(VaadinService vaadinService) {
        active = this;
        log("onInit");
    }

    @Override
    public void onClassesChange(HotswapClassEvent event) {
        log("onClassesChange(global) classes=" + names(event) + " redefined="
                + event.isRedefined() + " requiresPageReload="
                + event.requiresPageReload() + " anyUIRequiresPageReload="
                + event.anyUIRequiresPageReload());
        if (event.requiresPageReload() || event.anyUIRequiresPageReload()) {
            pageReloadRequired = true;
        }
    }

    @Override
    public void onClassesChange(HotswapClassSessionEvent event) {
        List<String> perUi = new ArrayList<>();
        try {
            for (UI ui : event.getVaadinSession().getUIs()) {
                perUi.add(ui.getUIId() + "=" + event.getUIUpdateStrategy(ui)
                        .map(UIUpdateStrategy::name).orElse("none"));
            }
        } catch (RuntimeException ex) {
            perUi.add("error:" + ex);
        }
        log("onClassesChange(session) classes=" + names(event) + " uis=" + perUi);
    }

    @Override
    public void onHotswapComplete(HotswapCompleteEvent event) {
        completed = true;
        log("onHotswapComplete classes=" + event.getClasses().stream()
                .map(Class::getName).sorted().toList() + " redefined="
                + event.isRedefined());
    }

    /** Clears recorded state before the next transaction. */
    public void reset() {
        completed = false;
        pageReloadRequired = false;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isPageReloadRequired() {
        return pageReloadRequired;
    }

    private static String names(HotswapClassEvent event) {
        return event.getChangedClasses().stream().map(Class::getSimpleName)
                .sorted().collect(Collectors.toList()).toString();
    }

    private static void log(String line) {
        System.out.println("[devloop-hotswapper] " + line);
    }
}
