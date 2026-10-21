package com.ambikatelecom.rewards.reporting.application;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** A calendar month in the shop's timezone, as a half-open UTC interval [start, end). */
public record ReportPeriod(YearMonth month, ZoneId zone, Instant start, Instant end) {

    public static ReportPeriod of(YearMonth month, ZoneId zone) {
        Instant start = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        return new ReportPeriod(month, zone, start, end);
    }

    /** Label used as the unique run key, e.g. 2026-09. */
    public String label() { return month.toString(); }

    public boolean isFinished(Instant now) { return !end.isAfter(now); }

    public String humanRange() {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        return month.atDay(1).format(f) + " to " + month.atEndOfMonth().format(f);
    }

    public String humanMonth() {
        return month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
    }
}
