package com.dev.vaadin.example.shared;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * Renders a task's due date for the grid.
 * <p>
 * Trivial on purpose. Its value is that it lives in another module and its output
 * is visible in the browser, so an {@code apply} that claims a sibling module's
 * edit is live can be checked against what the page actually shows.
 */
public final class DueDateFormatter {

    private final DateTimeFormatter formatter;

    public DueDateFormatter(Locale locale) {
        this.formatter = DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
    }

    /** A missing due date is a state, not an absence, so it gets words too. */
    public String format(LocalDate dueDate) {
        return dueDate == null ? "Never" : formatter.format(dueDate);
    }
}
