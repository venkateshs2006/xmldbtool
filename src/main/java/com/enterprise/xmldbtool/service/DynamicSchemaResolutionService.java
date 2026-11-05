package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.batch.DatabaseErrorSkipListener;
import com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter;
import com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor;
import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.model.StagingXmlRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;

/**
 * Dynamic Schema Resolution and Normalization Service.
 * Projects flat staging data into normalized canonical relational tables
 * (Master Person entity + 1-to-N child relationships for attributes and roles)
 * governed by user-defined UI mapping configurations.
 * Supports delta processing using cross-database upsert logic and dynamically
 * handles schema evolution, unmapped attributes, and column mismatches.
 */
@Service
public class DynamicSchemaResolutionService {

    private static final Logger log = LoggerFactory.getLogger(DynamicSchemaResolutionService.class);

    private final ObjectMapper objectMapper;

    public record NormalizationSummary(
            long totalStagedProcessed,
            long normalizedCount,
            long failedCount,
            Map<String, Long> tableWriteCounts,
            long elapsedTimeMs
    ) {}

    public DynamicSchemaResolutionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Reads STAGED records from flat staging table and projects them into canonical relational tables.
     */
    public NormalizationSummary normalizeStagingToCanonical(Long jobExecutionId,
                                                           DataSource targetDataSource,
                                                           List<FieldMapping> mappings,
                                                           List<String> tableOrder,
                                                           String targetMasterTable,
                                                           int batchChunkSize) throws Exception {
        long startTime = System.currentTimeMillis();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(targetDataSource);
        int chunkSize = batchChunkSize > 0 ? batchChunkSize : 1000;

        List<String> resolvedTableOrder = DynamicJdbcBatchWriter.determineTableOrder(
                tableOrder,
                mappings,
                targetMasterTable != null ? targetMasterTable : "Person"
        );

        DatabaseErrorSkipListener skipListener = new DatabaseErrorSkipListener(targetDataSource, System.currentTimeMillis(), "stagingNormalizationStep");
        DynamicJdbcBatchWriter batchWriter = new DynamicJdbcBatchWriter(targetDataSource, resolvedTableOrder, skipListener);
        if (mappings != null) {
            Map<String, Set<String>> explicitPks = new HashMap<>();
            for (FieldMapping m : mappings) {
                if ("PK".equalsIgnoreCase(m.getConstraintType()) && m.getTargetTable() != null && m.getTargetColumn() != null) {
                    explicitPks.computeIfAbsent(m.getTargetTable().toLowerCase(), k -> new LinkedHashSet<>())
                            .add(m.getTargetColumn().toLowerCase());
                }
            }
            if (!explicitPks.isEmpty()) {
                batchWriter.setExplicitPrimaryKeys(explicitPks);
            }
        }
        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor(
                targetMasterTable != null ? targetMasterTable : "Person",
                mappings,
                Collections.emptyMap(),
                resolvedTableOrder
        );

        // Fetch staged records
        String selectSql = "SELECT id, job_execution_id, entity_type, record_identifier, record_action, record_date, raw_payload, payload_hash, status " +
                "FROM staging_xml_records WHERE status = 'STAGED' ORDER BY id ASC LIMIT " + chunkSize;

        long totalProcessed = 0;
        long normalizedCount = 0;
        long failedCount = 0;

        List<StagingXmlRecord> stagedBatch = fetchStagedRecords(jdbcTemplate, selectSql);

        while (!stagedBatch.isEmpty()) {
            List<DatabaseRowPayload> payloads = new ArrayList<>(stagedBatch.size());
            List<Long> successfulStagingIds = new ArrayList<>(stagedBatch.size());
            Map<Long, String> failedStagingIds = new LinkedHashMap<>();

            for (StagingXmlRecord record : stagedBatch) {
                totalProcessed++;
                try {
                    Map<String, Object> rawMap = objectMapper.readValue(record.getRawPayload(), new TypeReference<Map<String, Object>>() {});

                    // Schema Evolution: dynamically capture unmapped fields into unmapped_attributes JSON
                    enrichWithSchemaEvolutionAttributes(rawMap, mappings);

                    DatabaseRowPayload payload = processor.process(rawMap);
                    if (payload != null) {
                        payloads.add(payload);
                        successfulStagingIds.add(record.getId());
                    } else {
                        successfulStagingIds.add(record.getId());
                    }
                } catch (Exception ex) {
                    failedCount++;
                    failedStagingIds.put(record.getId(), ex.getMessage());
                    log.error("Failed to project staging record #{} (id: {}): {}", record.getId(), record.getRecordIdentifier(), ex.getMessage());
                }
            }

            // Write canonical records using delta upsert
            if (!payloads.isEmpty()) {
                try {
                    batchWriter.write(new Chunk<>(payloads));
                    normalizedCount += payloads.size();
                } catch (Exception ex) {
                    log.error("Batch write to canonical tables failed: {}", ex.getMessage());
                }
            }

            // Update staging records status to NORMALIZED or FAILED
            if (!successfulStagingIds.isEmpty()) {
                updateStagingStatus(jdbcTemplate, successfulStagingIds, "NORMALIZED", null);
            }
            for (Map.Entry<Long, String> entry : failedStagingIds.entrySet()) {
                updateStagingStatus(jdbcTemplate, List.of(entry.getKey()), "FAILED", entry.getValue());
            }

            // Fetch next chunk
            stagedBatch = fetchStagedRecords(jdbcTemplate, selectSql);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Normalization completed: stagedProcessed={}, normalized={}, failed={}, tableWrites={}, elapsedMs={}",
                totalProcessed, normalizedCount, failedCount, batchWriter.getTableWriteCounts(), elapsed);

        return new NormalizationSummary(totalProcessed, normalizedCount, failedCount, batchWriter.getTableWriteCounts(), elapsed);
    }

    /**
     * Handles schema evolution by identifying newly added or unmapped XML fields
     * and aggregating them into a canonical 'unmapped_attributes' JSON payload.
     */
    public void enrichWithSchemaEvolutionAttributes(Map<String, Object> rawMap, List<FieldMapping> mappings) {
        if (rawMap == null || mappings == null) return;

        Set<String> mappedXmlFields = new HashSet<>();
        for (FieldMapping m : mappings) {
            if (m.getXmlField() != null) {
                mappedXmlFields.add(m.getXmlField().toLowerCase());
                String simple = m.getXmlField().contains("/")
                        ? m.getXmlField().substring(m.getXmlField().lastIndexOf("/") + 1).toLowerCase()
                        : m.getXmlField().toLowerCase();
                if (simple.startsWith("@")) simple = simple.substring(1);
                mappedXmlFields.add(simple);
            }
        }

        Map<String, Object> unmappedFields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_entities_")) continue; // Repeating collections handled separately
            String normKey = key.toLowerCase();
            if (normKey.startsWith("@")) normKey = normKey.substring(1);

            if (!mappedXmlFields.contains(normKey) && !mappedXmlFields.contains(key.toLowerCase())) {
                unmappedFields.put(key, entry.getValue());
            }
        }

        if (!unmappedFields.isEmpty()) {
            try {
                String extraJson = objectMapper.writeValueAsString(unmappedFields);
                rawMap.put("unmapped_attributes", extraJson);
                rawMap.put("extra_attributes", extraJson);
                rawMap.put("attributes", extraJson);
            } catch (Exception ignored) {}
        }
    }

    private List<StagingXmlRecord> fetchStagedRecords(JdbcTemplate jdbcTemplate, String sql) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            StagingXmlRecord r = new StagingXmlRecord();
            r.setId(rs.getLong("id"));
            r.setJobExecutionId(rs.getLong("job_execution_id"));
            r.setEntityType(rs.getString("entity_type"));
            r.setRecordIdentifier(rs.getString("record_identifier"));
            r.setRecordAction(rs.getString("record_action"));
            r.setRecordDate(rs.getString("record_date"));
            r.setRawPayload(rs.getString("raw_payload"));
            r.setPayloadHash(rs.getString("payload_hash"));
            r.setStatus(rs.getString("status"));
            return r;
        });
    }

    private void updateStagingStatus(JdbcTemplate jdbcTemplate, List<Long> ids, String status, String errorMessage) {
        if (ids.isEmpty()) return;
        String updateSql = "UPDATE staging_xml_records SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        jdbcTemplate.batchUpdate(updateSql, ids, ids.size(), (ps, id) -> {
            ps.setString(1, status);
            ps.setString(2, errorMessage);
            ps.setLong(3, id);
        });
    }
}

