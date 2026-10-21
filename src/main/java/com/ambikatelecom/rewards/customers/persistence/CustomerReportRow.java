package com.ambikatelecom.rewards.customers.persistence;

import java.time.Instant;

/** Name and phone only, plus the paging keys. This is everything the monthly report is allowed to see. */
public interface CustomerReportRow {
    Long getId();
    String getName();
    String getPhoneE164();
    Instant getCreatedAt();
}
