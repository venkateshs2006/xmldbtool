package com.bnpp.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, thread-safe monotonic sequential ID generator.
 * Format for BNPP records: BNPP_<yyyymmddhhmmssSSS>_<7digit_sequence>
 * Format for tracking: PREFIX-<yyyymmddhhmmssSSS>-<6digit_sequence>
 * Format for vendors: VND-<6digit_sequence>
 */
public final class BnppIdGenerator {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneId.systemDefault());

    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private BnppIdGenerator() {
    }

    public static String generateBnppId() {
        long seq = SEQUENCE.incrementAndGet() % 10_000_000L;
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        return String.format("BNPP_%s_%07d", timestamp, seq);
    }

    public static String generateId(String prefix) {
        if (prefix == null || prefix.trim().isEmpty() || "BNPP".equalsIgnoreCase(prefix)) {
            return generateBnppId();
        }
        String pfx = prefix.trim().toUpperCase();
        if ("VND".equalsIgnoreCase(pfx) || "VENDOR".equalsIgnoreCase(pfx)) {
            long seq = SEQUENCE.incrementAndGet() % 1_000_000L;
            return String.format("VND-%06d", seq);
        }
        long seq = SEQUENCE.incrementAndGet() % 1_000_000L;
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        return String.format("%s-%s-%06d", pfx, timestamp, seq);
    }

    public static String generateFileId() {
        return generateId("FILE");
    }

    public static String generateAuditId() {
        return generateId("AUDIT");
    }

    public static String generateErrorId() {
        return generateId("ERR");
    }

    public static String generateVendorId() {
        return generateId("VND");
    }

    public static String generateVendorId(String vendorName) {
        return generateVendorId();
    }
}
