package com.ambikatelecom.rewards.common;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;
import java.util.regex.Pattern;

/**
 * Last line of defence in the log pipeline (see logback-spring.xml). Anything that looks like a phone number or
 * an IMEI, meaning a run of 10 or more digits, optionally with a leading plus, is replaced before the line is written.
 * No code here logs such values on purpose; this catches a mistake, or text from a library, before it is stored.
 */
public class PiiMaskingConverter extends CompositeConverter<ILoggingEvent> {

    private static final Pattern LONG_NUMBER = Pattern.compile("\\+?\\d{10,}");

    @Override
    protected String transform(ILoggingEvent event, String in) {
        if (in == null || in.isEmpty()) return in;
        return LONG_NUMBER.matcher(in).replaceAll("[number-redacted]");
    }
}
