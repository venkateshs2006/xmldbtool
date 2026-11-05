package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller;
import com.enterprise.xmldbtool.model.StagingXmlRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

/**
 * Enterprise StAX-based streaming ingestion service that loads massive XML files (10GB+)
 * into a flat staging table with constant O(1) heap memory.
 * Computes SHA-256 payload fingerprints for delta detection and supports skip-unchanged optimization.
 */
@Service
public class StagingXmlStreamingService {

    private static final Logger log = LoggerFactory.getLogger(StagingXmlStreamingService.class);

    private final DynamicXmlUnmarshaller dynamicXmlUnmarshaller;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    public record StagingSummary(
            long totalRead,
            long stagedCount,
            long skippedUnchangedCount,
            long failedCount,
            long elapsedTimeMs
    ) {}

    public StagingXmlStreamingService(DynamicXmlUnmarshaller dynamicXmlUnmarshaller, ObjectMapper objectMapper) {
        this.dynamicXmlUnmarshaller = dynamicXmlUnmarshaller;
        this.objectMapper = objectMapper;
    }

    /**
     * Memory-efficient streaming of XML file into flat staging table.
     */
    public StagingSummary streamXmlToStaging(String sourceXmlPath,
                                            String recordRootElement,
                                            Long jobExecutionId,
                                            DataSource targetDataSource,
                                            int chunkSize) throws Exception {
        long startTime = System.currentTimeMillis();
        String targetRoot = (recordRootElement != null && !recordRootElement.trim().isEmpty())
                ? recordRootElement.trim() : "Person";
        int batchSize = chunkSize > 0 ? chunkSize : 1000;

        JdbcTemplate jdbcTemplate = new JdbcTemplate(targetDataSource);
        ensureStagingTableExists(jdbcTemplate);

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        long totalRead = 0;
        long stagedCount = 0;
        long skippedUnchanged = 0;
        long failedCount = 0;

        List<StagingXmlRecord> buffer = new ArrayList<>(batchSize);

        try (BufferedReader reader = openReader(sourceXmlPath)) {
            XMLEventReader xmlReader = factory.createXMLEventReader(reader);

            while (xmlReader.hasNext()) {
                XMLEvent event = xmlReader.peek();
                if (event != null && event.isStartElement()) {
                    StartElement startElement = event.asStartElement();
                    String tagName = startElement.getName().getLocalPart();

                    if (tagName.equalsIgnoreCase(targetRoot)) {
                        totalRead++;
                        try {
                            // Memory-efficient: StAX unmarshals only this isolated fragment
                            Map<String, Object> recordMap = dynamicXmlUnmarshaller.parseFragmentFromEventReader(xmlReader);
                            if (recordMap != null && !recordMap.isEmpty()) {
                                String identifier = extractRecordIdentifier(recordMap);
                                String action = extractValue(recordMap, "@action", "action", "status");
                                String recordDate = extractValue(recordMap, "@date", "date", "record_date");
                                String jsonPayload = objectMapper.writeValueAsString(recordMap);
                                String payloadHash = computeSha256(jsonPayload);

                                StagingXmlRecord record = new StagingXmlRecord(
                                        jobExecutionId,
                                        targetRoot,
                                        identifier,
                                        action,
                                        recordDate,
                                        jsonPayload,
                                        payloadHash
                                );

                                // Delta Detection: check if previous identical hash exists
                                boolean isUnchanged = isRecordUnchanged(jdbcTemplate, targetRoot, identifier, payloadHash);
                                if (isUnchanged) {
                                    record.setStatus("SKIPPED_UNCHANGED");
                                    skippedUnchanged++;
                                } else {
                                    record.setStatus("STAGED");
                                    stagedCount++;
                                }

                                buffer.add(record);
                                if (buffer.size() >= batchSize) {
                                    flushStagingBatch(jdbcTemplate, buffer);
                                    buffer.clear();
                                }
                            }
                        } catch (Exception ex) {
                            failedCount++;
                            log.error("Failed to unmarshal XML fragment #{} for element '{}': {}", totalRead, targetRoot, ex.getMessage());
                        }
                        continue;
                    }
                }
                xmlReader.nextEvent();
            }

            if (!buffer.isEmpty()) {
                flushStagingBatch(jdbcTemplate, buffer);
                buffer.clear();
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("StAX Staging ingestion completed: totalRead={}, staged={}, skippedUnchanged={}, failed={}, elapsedMs={}",
                totalRead, stagedCount, skippedUnchanged, failedCount, elapsed);

        return new StagingSummary(totalRead, stagedCount, skippedUnchanged, failedCount, elapsed);
    }

    private void flushStagingBatch(JdbcTemplate jdbcTemplate, List<StagingXmlRecord> records) {
        if (records.isEmpty()) return;

        String insertSql = "INSERT INTO staging_xml_records (" +
                "job_execution_id, entity_type, record_identifier, record_action, record_date, raw_payload, payload_hash, status, created_at, updated_at" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

        jdbcTemplate.batchUpdate(insertSql, records, records.size(), (ps, r) -> {
            if (r.getJobExecutionId() != null) ps.setLong(1, r.getJobExecutionId()); else ps.setNull(1, java.sql.Types.BIGINT);
            ps.setString(2, r.getEntityType());
            ps.setString(3, r.getRecordIdentifier());
            ps.setString(4, r.getRecordAction());
            ps.setString(5, r.getRecordDate());
            ps.setString(6, r.getRawPayload());
            ps.setString(7, r.getPayloadHash());
            ps.setString(8, r.getStatus());
        });
    }

    private boolean isRecordUnchanged(JdbcTemplate jdbcTemplate, String entityType, String recordIdentifier, String currentHash) {
        if (recordIdentifier == null || recordIdentifier.isEmpty()) {
            return false;
        }
        try {
            String checkSql = "SELECT COUNT(*) FROM staging_xml_records " +
                    "WHERE entity_type = ? AND record_identifier = ? AND payload_hash = ? AND status IN ('STAGED', 'NORMALIZED')";
            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, entityType, recordIdentifier, currentHash);
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String computeSha256(String data) {
        if (data == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(data.hashCode());
        }
    }

    private String extractRecordIdentifier(Map<String, Object> recordMap) {
        for (String key : List.of("@id", "id", "person_id", "Person/@id", "code", "@code", "sku", "identifier")) {
            Object val = recordMap.get(key);
            if (val != null && !String.valueOf(val).trim().isEmpty()) {
                return String.valueOf(val).trim();
            }
        }
        return UUID.randomUUID().toString();
    }

    private String extractValue(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !String.valueOf(v).trim().isEmpty()) {
                return String.valueOf(v).trim();
            }
        }
        return null;
    }

    private void ensureStagingTableExists(JdbcTemplate jdbcTemplate) {
        try {
            String sqlStaging = "CREATE TABLE IF NOT EXISTS staging_xml_records (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "job_execution_id BIGINT, " +
                    "entity_type VARCHAR(50) NOT NULL, " +
                    "record_identifier VARCHAR(100), " +
                    "record_action VARCHAR(50), " +
                    "record_date VARCHAR(50), " +
                    "raw_payload TEXT NOT NULL, " +
                    "payload_hash VARCHAR(64) NOT NULL, " +
                    "status VARCHAR(30) DEFAULT 'STAGED', " +
                    "error_message TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sqlStaging);
        } catch (Exception ex) {
            log.debug("Staging table creation check: {}", ex.getMessage());
        }
    }

    private BufferedReader openReader(String path) throws Exception {
        Path direct = Paths.get(path);
        if (Files.exists(direct)) {
            return Files.newBufferedReader(direct, StandardCharsets.UTF_8);
        }
        Resource res = resourceLoader.getResource(path);
        if (res.exists()) {
            return new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8));
        }
        Path relative = Paths.get("/home/sv/Projects/xmldbtool", path);
        if (Files.exists(relative)) {
            return Files.newBufferedReader(relative, StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("Unable to find XML source file: " + path);
    }
}

