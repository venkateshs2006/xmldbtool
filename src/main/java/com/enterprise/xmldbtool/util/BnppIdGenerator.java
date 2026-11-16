package com.enterprise.xmldbtool.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, thread-safe sequential alphanumeric ID generator.
 * Produces strictly monotonic and unique identifiers formatted as:
 * BNPP_<yyyymmddhhmmssSSS>_<7digit SequenceNumber> (e.g. BNPP_20261115172244584_0000001)
 * and PREFIX-yyyyMMddHHmmssSSS-XXXXXX for FILE, AUDIT, and ERR.
 */
public final class BnppIdGenerator {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneId.systemDefault());

    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private BnppIdGenerator() {
        // Utility class
    }

    /**
     * Generates a unique sequential alphanumeric bnpp_id matching user specification:
     * BNPP_<yyyymmddhhmmssSSS>_<7digit SequenceNumber> (e.g. BNPP_20261115172244584_0000001).
     */
    public static String generateId() {
        return generateBnppId();
    }

    public static String generateBnppId() {
        long seq = SEQUENCE.incrementAndGet() % 10_000_000L;
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        return String.format("BNPP_%s_%07d", timestamp, seq);
    }

    /**
     * Generates a unique sequential alphanumeric ID with a custom prefix:
     * - If prefix is "BNPP", produces BNPP_<timestamp>_<7digit SequenceNumber>
     * - For system tracking tables (FILE, AUDIT, ERR), produces PREFIX-yyyyMMddHHmmssSSS-XXXXXX
     *   (e.g. AUDIT-20261115172244584-000002, FILE-20261115172244575-000001)
     */
    public static String generateId(String prefix) {
        if (prefix == null || prefix.trim().isEmpty() || "BNPP".equalsIgnoreCase(prefix)) {
            return generateBnppId();
        }
        String pfx = prefix.trim().toUpperCase();
        long seq = SEQUENCE.incrementAndGet() % 1_000_000L;
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        return String.format("%s-%s-%06d", pfx, timestamp, seq);
    }
}
