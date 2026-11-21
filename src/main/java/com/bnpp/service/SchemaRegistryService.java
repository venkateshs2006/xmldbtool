package com.bnpp.service;

import com.bnpp.model.SchemaRegistryVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SchemaRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryService.class);

    private final Map<Long, SchemaRegistryVersion> versionStore = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    public SchemaRegistryService() {
        // Seed default version for quick template reuse
        SchemaRegistryVersion v1 = new SchemaRegistryVersion();
        v1.setId(idSequence.getAndIncrement());
        v1.setVendorName("DOW_JONES");
        v1.setVendorId("VND-000001");
        v1.setVersionName("Standard Person & Address Feed v1");
        v1.setVersionNumber(1);
        v1.setCreatedBy("admin");
        v1.setCreatedAt(Timestamp.from(Instant.now()));
        v1.setIngestionJson("""
            [
              {"xmlField":"person/@id","targetTable":"Person","targetColumn":"person_id","constraintType":"PK","transform":"NONE"},
              {"xmlField":"person/@status","targetTable":"Person","targetColumn":"status","constraintType":"NONE","transform":"UPPER"},
              {"xmlField":"person/name","targetTable":"Person","targetColumn":"name","constraintType":"NONE","transform":"NONE"},
              {"xmlField":"person/age","targetTable":"Person","targetColumn":"age","constraintType":"NONE","transform":"TO_INT"},
              {"xmlField":"person/addresses/address/@id","targetTable":"Address","targetColumn":"address_id","constraintType":"PK","transform":"NONE"},
              {"xmlField":"person/@id","targetTable":"Address","targetColumn":"person_id","constraintType":"FK","foreignKeyTable":"Person","foreignKeyColumn":"person_id","transform":"NONE"},
              {"xmlField":"person/addresses/address/street","targetTable":"Address","targetColumn":"street","constraintType":"NONE","transform":"NONE"}
            ]
        """);
        versionStore.put(v1.getId(), v1);
    }

    public List<SchemaRegistryVersion> getAllVersions() {
        return new ArrayList<>(versionStore.values());
    }

    public Optional<SchemaRegistryVersion> getVersionById(Long id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(versionStore.get(id));
    }

    public SchemaRegistryVersion saveVersion(SchemaRegistryVersion version) {
        if (version == null) throw new IllegalArgumentException("Version cannot be null.");
        if (version.getId() == null) {
            version.setId(idSequence.incrementAndGet());
        }
        if (version.getCreatedAt() == null) {
            version.setCreatedAt(Timestamp.from(Instant.now()));
        }
        versionStore.put(version.getId(), version);
        log.info("Saved schema registry version #{}: {} ({})", version.getId(), version.getVersionName(), version.getVendorName());
        return version;
    }
}
