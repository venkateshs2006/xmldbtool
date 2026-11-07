package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.batch.DatabaseErrorSkipListener;
import com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter;
import com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor;
import com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;

/**
 * Dynamic Schema Resolution and Normalization Service:
 * Phase 2 of the high-throughput ingestion pipeline.
 * Reads staged raw XML fragments from optimized entity-partitioned flat tables ('<entity>_flat')
 * or fallback 'staging_xml_records' using ultra-fast keyset pagination (O(1) memory and sub-millisecond seeks).
 *
 * Enforces:
 * 1. Persistent unique identity via 'bnpp_id' across all destination and flat tables.
 * 2. Enterprise audit traceability with 'tag_name' and 'version_number'.
 * 3. Smart Deduplication: Identical payload hashes are ignored with zero redundant DB writes.
 * 4. Delta Version Bumping: Modified records are updated with incremented version_number (v -> v + 1).
 * 5. New Records: Inserted with initial version_number and timestamps.
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

    public record ExistingRecordInfo(String bnppId, String payloadHash, Integer versionNumber) {}

    public DynamicSchemaResolutionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @FunctionalInterface
    public interface NormalizationProgressCallback {
        void onProgress(long totalProcessed, long normalizedCount, long failedCount, Map<String, Long> tableWriteCounts, String currentTable);
    }

    /**
     * Reads STAGED records from partitioned flat staging tables or staging_xml_records,
     * performs deduplication and version-aware projection into canonical relational tables.
     */
    public NormalizationSummary normalizeStagingToCanonical(Long jobExecutionId,
                                                           DataSource targetDataSource,
                                                           List<FieldMapping> mappings,
                                                           List<String> tableOrder,
                                                           String targetMasterTable,
                                                           int batchChunkSize) throws Exception {
        return normalizeStagingToCanonical(jobExecutionId, targetDataSource, mappings, tableOrder, targetMasterTable, batchChunkSize, false, null, null);
    }

    public NormalizationSummary normalizeStagingToCanonical(Long jobExecutionId,
                                                           DataSource targetDataSource,
                                                           List<FieldMapping> mappings,
                                                           List<String> tableOrder,
                                                           String targetMasterTable,
                                                           int batchChunkSize,
                                                           NormalizationProgressCallback progressCallback) throws Exception {
        return normalizeStagingToCanonical(jobExecutionId, targetDataSource, mappings, tableOrder, targetMasterTable, batchChunkSize, false, null, progressCallback);
    }

    public NormalizationSummary normalizeStagingToCanonical(Long jobExecutionId,
                                                           DataSource targetDataSource,
                                                           List<FieldMapping> mappings,
                                                           List<String> tableOrder,
                                                           String targetMasterTable,
                                                           int batchChunkSize,
                                                           boolean retryFailedRecords,
                                                           java.util.function.BooleanSupplier cancellationChecker,
                                                           NormalizationProgressCallback progressCallback) throws Exception {
        long startTime = System.currentTimeMillis();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(targetDataSource);
        int chunkSize = batchChunkSize > 0 ? batchChunkSize : 1000;

        List<String> resolvedTableOrder = DynamicJdbcBatchWriter.determineTableOrder(
                tableOrder,
                mappings,
                targetMasterTable != null ? targetMasterTable : "record"
        );

        List<FieldMapping> effectiveMappings = (mappings != null && !mappings.isEmpty())
                ? new ArrayList<>(mappings)
                : autoDeriveMappingsFromTargetTables(jdbcTemplate, tableOrder, targetMasterTable);

        if ((tableOrder == null || tableOrder.isEmpty()) && !effectiveMappings.isEmpty()) {
            resolvedTableOrder = DynamicJdbcBatchWriter.determineTableOrder(
                    null,
                    effectiveMappings,
                    targetMasterTable != null ? targetMasterTable : "record"
            );
        }

        DatabaseErrorSkipListener skipListener = new DatabaseErrorSkipListener(targetDataSource, System.currentTimeMillis(), "stagingNormalizationStep");
        DynamicJdbcBatchWriter batchWriter = new DynamicJdbcBatchWriter(targetDataSource, resolvedTableOrder, skipListener);
        if (!effectiveMappings.isEmpty()) {
            Map<String, Set<String>> explicitPks = new HashMap<>();
            for (FieldMapping m : effectiveMappings) {
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
                targetMasterTable != null ? targetMasterTable : "record",
                effectiveMappings,
                Collections.emptyMap(),
                resolvedTableOrder
        );

        long totalProcessed = 0;
        long normalizedCount = 0;
        long failedCount = 0;

        // 1. Identify distinct entity flat tables for high-performance 10GB+ partitioned ingestion
        Set<String> candidateFlatTables = new LinkedHashSet<>();
        if (resolvedTableOrder != null) {
            for (String tbl : resolvedTableOrder) {
                String norm = tbl.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                String directFlat = norm + "_flat";
                String singularFlat = com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller.singularize(norm) + "_flat";
                String pluralFlat = com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller.pluralize(norm) + "_flat";

                if (tableExists(jdbcTemplate, directFlat)) {
                    candidateFlatTables.add(directFlat);
                } else if (tableExists(jdbcTemplate, singularFlat)) {
                    candidateFlatTables.add(singularFlat);
                } else if (tableExists(jdbcTemplate, pluralFlat)) {
                    candidateFlatTables.add(pluralFlat);
                }
            }
        }

        // Also check if any other <root>_flat tables exist in the target database
        for (String flat : findExistingFlatTables(jdbcTemplate)) {
            String normFlat = flat.toLowerCase();
            if (!normFlat.equals("staging_xml_records") && !candidateFlatTables.contains(normFlat)) {
                candidateFlatTables.add(normFlat);
            }
        }

        String dbProduct = null;
        try (Connection conn = targetDataSource.getConnection()) {
            dbProduct = conn.getMetaData().getDatabaseProductName();
        } catch (Exception ignored) {}
        boolean isOracle = dbProduct != null && dbProduct.toLowerCase().contains("oracle");

        boolean processedAnyFlat = false;

        String statusClause = retryFailedRecords
                ? "(status = 'STAGED' OR status = 'RETRY' OR status = 'FAILED')"
                : "(status = 'STAGED' OR (status IS NOT NULL AND status != 'NORMALIZED' AND status != 'FAILED' AND status != 'SKIPPED_UNCHANGED'))";

        // Process each entity-specific flat table in dependency sequence
        for (String flatTable : candidateFlatTables) {
            long lastSeenId = 0;
            boolean hasMore = true;

            while (hasMore) {
                if (cancellationChecker != null && cancellationChecker.getAsBoolean()) {
                    log.info("Job #{} termination requested. Cleanly halting Stage 2 normalization at record #{}.", jobExecutionId, totalProcessed);
                    break;
                }

                String selectSql = "SELECT id, job_execution_id, bnpp_id, tag_name, version_number, " +
                        "record_identifier, record_action, record_date, raw_payload, payload_hash, status " +
                        "FROM " + flatTable + " WHERE id > ? AND " + statusClause + " ORDER BY id ASC " +
                        (isOracle ? "FETCH FIRST " + chunkSize + " ROWS ONLY" : "LIMIT " + chunkSize);

                List<StagingXmlRecord> stagedBatch = fetchStagedRecordsWithKeyset(jdbcTemplate, selectSql, lastSeenId);
                if (stagedBatch.isEmpty()) {
                    break;
                }
                processedAnyFlat = true;
                lastSeenId = stagedBatch.get(stagedBatch.size() - 1).getId();

                NormalizationBatchResult batchResult = processStagedBatch(
                        stagedBatch, processor, effectiveMappings, jdbcTemplate, batchWriter, skipListener
                );

                totalProcessed += batchResult.processed;
                normalizedCount += batchResult.written;
                failedCount += batchResult.failed;

                // Update status in flat table and central staging table
                if (!batchResult.successfulRecords.isEmpty()) {
                    updateFlatTableStatus(jdbcTemplate, flatTable, batchResult.successfulRecords, "NORMALIZED", null);
                    updateStagingStatus(jdbcTemplate, batchResult.successfulRecords, "NORMALIZED", null);
                }
                for (Map.Entry<StagingXmlRecord, String> entry : batchResult.failedRecords.entrySet()) {
                    updateFlatTableStatus(jdbcTemplate, flatTable, List.of(entry.getKey()), "FAILED", entry.getValue());
                    updateStagingStatus(jdbcTemplate, List.of(entry.getKey()), "FAILED", entry.getValue());
                }

                if (progressCallback != null) {
                    progressCallback.onProgress(totalProcessed, normalizedCount, failedCount, new LinkedHashMap<>(batchWriter.getTableWriteCounts()), flatTable);
                }
            }
        }

        // 2. Fallback: Process central staging_xml_records ONLY if no partitioned flat tables were processed
        if (!processedAnyFlat) {
            long lastSeenCentralId = 0;
            boolean hasMoreCentral = true;
            while (hasMoreCentral) {
                if (cancellationChecker != null && cancellationChecker.getAsBoolean()) {
                    log.info("Job #{} termination requested. Cleanly halting Stage 2 normalization at record #{}.", jobExecutionId, totalProcessed);
                    break;
                }

                String centralStatusClause = retryFailedRecords ? "(status = 'STAGED' OR status = 'FAILED')" : "status = 'STAGED'";
                String centralSelect = "SELECT id, job_execution_id, bnpp_id, tag_name, version_number, " +
                        "record_identifier, record_action, record_date, raw_payload, payload_hash, status " +
                        "FROM staging_xml_records WHERE id > ? AND " + centralStatusClause + " ORDER BY id ASC LIMIT " + chunkSize;

                List<StagingXmlRecord> centralBatch = fetchStagedRecordsWithKeyset(jdbcTemplate, centralSelect, lastSeenCentralId);
                if (centralBatch.isEmpty()) {
                    break;
                }
                lastSeenCentralId = centralBatch.get(centralBatch.size() - 1).getId();

                NormalizationBatchResult batchResult = processStagedBatch(
                        centralBatch, processor, effectiveMappings, jdbcTemplate, batchWriter, skipListener
                );

                totalProcessed += batchResult.processed;
                normalizedCount += batchResult.written;
                failedCount += batchResult.failed;

                if (!batchResult.successfulRecords.isEmpty()) {
                    updateStagingStatus(jdbcTemplate, batchResult.successfulRecords, "NORMALIZED", null);
                }
                for (Map.Entry<StagingXmlRecord, String> entry : batchResult.failedRecords.entrySet()) {
                    updateStagingStatus(jdbcTemplate, List.of(entry.getKey()), "FAILED", entry.getValue());
                }

                if (progressCallback != null) {
                    progressCallback.onProgress(totalProcessed, normalizedCount, failedCount, new LinkedHashMap<>(batchWriter.getTableWriteCounts()), "staging_xml_records");
                }
            }
        }

        if (progressCallback != null) {
            progressCallback.onProgress(totalProcessed, normalizedCount, failedCount, new LinkedHashMap<>(batchWriter.getTableWriteCounts()), "COMPLETED");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Normalization completed: stagedProcessed={}, normalized={}, failed={}, tableWrites={}, elapsedMs={}",
                totalProcessed, normalizedCount, failedCount, batchWriter.getTableWriteCounts(), elapsed);

        return new NormalizationSummary(totalProcessed, normalizedCount, failedCount, batchWriter.getTableWriteCounts(), elapsed);
    }

    private record NormalizationBatchResult(
            long processed,
            long written,
            long failed,
            List<StagingXmlRecord> successfulRecords,
            Map<StagingXmlRecord, String> failedRecords
    ) {}

    /**
     * Processes a chunk of StagingXmlRecords:
     * - Maps them via ItemProcessor into multi-table payloads
     * - Injects bnpp_id, tag_name, payload_hash, and version_number
     * - Queries target tables to filter identical duplicates and bump version on modified records
     * - Batch writes non-duplicate records to destination tables
     */
    private NormalizationBatchResult processStagedBatch(List<StagingXmlRecord> stagedBatch,
                                                        DynamicXmlItemProcessor processor,
                                                        List<FieldMapping> mappings,
                                                        JdbcTemplate jdbcTemplate,
                                                        DynamicJdbcBatchWriter batchWriter,
                                                        DatabaseErrorSkipListener skipListener) {
        long processed = 0;
        long written = 0;
        long failed = 0;
        List<StagingXmlRecord> successfulRecords = new ArrayList<>(stagedBatch.size());
        Map<StagingXmlRecord, String> failedRecords = new LinkedHashMap<>();

        List<DatabaseRowPayload> chunkPayloads = new ArrayList<>(stagedBatch.size());
        Map<StagingXmlRecord, DatabaseRowPayload> recordToPayload = new LinkedHashMap<>();
        List<StagingXmlRecord> validCandidateRecords = new ArrayList<>();

        for (StagingXmlRecord record : stagedBatch) {
            processed++;
            try {
                Map<String, Object> rawMap = objectMapper.readValue(record.getRawPayload(), new TypeReference<Map<String, Object>>() {});
                String entityTag = record.getTagName() != null ? record.getTagName() : record.getEntityType();
                if (entityTag != null) {
                    rawMap.put("_entity_tag", entityTag);
                    rawMap.put("_tag_name", entityTag);
                }

                // Capture unmapped delta XML fields into unmapped_attributes JSON
                enrichWithSchemaEvolutionAttributes(rawMap, mappings);

                DatabaseRowPayload payload = processor.process(rawMap);
                if (payload != null) {
                    // Enrich payload rows with bnpp_id, tag_name, version_number, and payload_hash
                    enrichPayloadWithEnterpriseIdentity(payload, record, mappings);
                    chunkPayloads.add(payload);
                    recordToPayload.put(record, payload);
                }
                validCandidateRecords.add(record);
            } catch (Exception ex) {
                failed++;
                failedRecords.put(record, ex.getMessage());
                log.error("Failed to project staging record #{} (id: {}): {}. Persisting into migration_errors...",
                        record.getId(), record.getRecordIdentifier(), ex.getMessage());
                if (skipListener != null) {
                    skipListener.recordError("STAGE_2_PROJECTION", record.getRawPayload(), ex);
                }
            }
        }

        // Perform per-table deduplication, delta versioning, and batch write
        if (!chunkPayloads.isEmpty()) {
            DatabaseRowPayload filteredCombinedPayload = applyDeduplicationAndVersioning(
                    chunkPayloads, mappings, jdbcTemplate
            );

            if (filteredCombinedPayload != null && !filteredCombinedPayload.getMultiTableRecords().isEmpty()) {
                try {
                    batchWriter.write(new Chunk<>(List.of(filteredCombinedPayload)));
                    long batchCount = filteredCombinedPayload.getMultiTableRecords().values().stream()
                            .mapToLong(List::size).sum();
                    written += batchCount;
                    successfulRecords.addAll(validCandidateRecords);
                } catch (Exception batchEx) {
                    log.warn("Batch write to canonical destination tables failed: ({}). Retrying row-by-row for fault isolation...", batchEx.getMessage());
                    // Row-by-row fallback isolation: write each valid record individually!
                    for (StagingXmlRecord rec : validCandidateRecords) {
                        DatabaseRowPayload singlePayload = recordToPayload.get(rec);
                        if (singlePayload == null) {
                            successfulRecords.add(rec);
                            continue;
                        }
                        try {
                            DatabaseRowPayload filteredSingle = applyDeduplicationAndVersioning(
                                    List.of(singlePayload), mappings, jdbcTemplate
                            );
                            if (filteredSingle != null && !filteredSingle.getMultiTableRecords().isEmpty()) {
                                batchWriter.write(new Chunk<>(List.of(filteredSingle)));
                                long singleCount = filteredSingle.getMultiTableRecords().values().stream()
                                        .mapToLong(List::size).sum();
                                written += singleCount;
                            }
                            successfulRecords.add(rec);
                        } catch (Exception singleEx) {
                            failed++;
                            failedRecords.put(rec, singleEx.getMessage());
                            log.warn("Record #{} (identifier: {}) write failed: {}. Logging to migration_errors and skipping...",
                                    rec.getId(), rec.getRecordIdentifier(), singleEx.getMessage());
                            if (skipListener != null) {
                                skipListener.recordError("STAGE_2_CANONICAL_WRITE", rec.getRawPayload(), singleEx);
                            }
                        }
                    }
                }
            } else {
                successfulRecords.addAll(validCandidateRecords);
            }
        }

        return new NormalizationBatchResult(processed, written, failed, successfulRecords, failedRecords);
    }

    /**
     * Enriches all multi-table records inside DatabaseRowPayload with:
     * - bnpp_id (deterministic unique record identity)
     * - tag_name (originating XML tag)
     * - version_number (initial version)
     * - payload_hash (fingerprint of business columns for duplicate detection)
     */
    private void enrichPayloadWithEnterpriseIdentity(DatabaseRowPayload payload,
                                                     StagingXmlRecord record,
                                                     List<FieldMapping> mappings) {
        if (payload == null || payload.getMultiTableRecords() == null) return;

        String rootTag = record.getTagName() != null ? record.getTagName() : record.getEntityType();
        int initialVersion = record.getVersionNumber() != null ? record.getVersionNumber() : 1;

        for (Map.Entry<String, List<Map<String, Object>>> entry : payload.getMultiTableRecords().entrySet()) {
            String tableName = entry.getKey();
            List<Map<String, Object>> rows = entry.getValue();
            if (rows == null) continue;

            for (Map<String, Object> row : rows) {
                if (row == null) continue;

                // 1. Resolve bnpp_id: each canonical destination table row gets its own unique UUID
                String existingBnpp = (String) row.get("bnpp_id");
                if (existingBnpp == null || existingBnpp.trim().isEmpty()) {
                    row.put("bnpp_id", UUID.randomUUID().toString());
                }

                // 1b. Reference flat/staging table's bnpp_id UUID
                if (record != null && record.getBnppId() != null) {
                    row.put("source_bnpp_id", record.getBnppId());
                    row.put("staging_bnpp_id", record.getBnppId());
                }

                // 2. Set tag_name
                String effectiveTagName;
                if (rootTag != null && (tableName.equalsIgnoreCase(rootTag)
                        || DynamicXmlUnmarshaller.singularize(tableName).equalsIgnoreCase(DynamicXmlUnmarshaller.singularize(rootTag)))) {
                    effectiveTagName = rootTag;
                } else {
                    effectiveTagName = (tableName != null ? tableName.toLowerCase() : (rootTag != null ? rootTag : "record"));
                }
                row.put("tag_name", effectiveTagName);

                // 3. Set version_number
                row.put("version_number", initialVersion);

                // 4. Compute business payload hash for change detection
                String rowHash = computeRowPayloadHash(row);
                row.put("payload_hash", rowHash);
            }
        }
    }

    /**
     * Derives a unique UUID bnpp_id for a specific table row.
     */
    private String deriveRowBnppId(String tableName,
                                   Map<String, Object> row,
                                   StagingXmlRecord record,
                                   List<FieldMapping> mappings) {
        return UUID.randomUUID().toString();
    }

    /**
     * Filters duplicates and manages versioning:
     * - Case 1: Identical Duplicate (existing payload_hash == incoming payload_hash) -> IGNORE
     * - Case 2: Changed Record (existing payload_hash != incoming payload_hash) -> UPDATE with version + 1
     * - Case 3: New Record -> INSERT with current version
     */
    private DatabaseRowPayload applyDeduplicationAndVersioning(List<DatabaseRowPayload> chunkPayloads,
                                                               List<FieldMapping> mappings,
                                                               JdbcTemplate jdbcTemplate) {
        Map<String, List<Map<String, Object>>> filteredMultiRecords = new LinkedHashMap<>();

        // Group rows across all payloads by table
        Map<String, List<Map<String, Object>>> allTableRows = new LinkedHashMap<>();
        for (DatabaseRowPayload p : chunkPayloads) {
            if (p.getMultiTableRecords() != null) {
                for (Map.Entry<String, List<Map<String, Object>>> entry : p.getMultiTableRecords().entrySet()) {
                    allTableRows.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
                }
            }
        }

        for (Map.Entry<String, List<Map<String, Object>>> entry : allTableRows.entrySet()) {
            String tableName = entry.getKey();
            List<Map<String, Object>> incomingRows = entry.getValue();
            if (incomingRows == null || incomingRows.isEmpty()) continue;

            String pkCol = resolveBusinessPkColumn(tableName, mappings, incomingRows, jdbcTemplate);
            Map<String, ExistingRecordInfo> existingMap = queryExistingRecords(jdbcTemplate, tableName, pkCol, incomingRows);

            List<Map<String, Object>> writeList = new ArrayList<>();
            for (Map<String, Object> row : incomingRows) {
                String lookupKey = resolveRowLookupKey(row, pkCol);
                String incomingHash = (String) row.get("payload_hash");
                ExistingRecordInfo existing = (lookupKey != null) ? existingMap.get(lookupKey) : null;

                if (existing != null) {
                    if (existing.payloadHash() != null && existing.payloadHash().equals(incomingHash)) {
                        // Case 1: Identical duplicate (e.g. user re-uploaded the exact same file twice)
                        // IGNORE: do not insert or update
                        log.debug("Duplicate ignored for table '{}', key='{}'", tableName, lookupKey);
                        continue;
                    } else {
                        // Case 2: Changed record in delta upload
                        // Retain existing canonical UUID bnpp_id so row identity is stable
                        if (existing.bnppId() != null) {
                            row.put("bnpp_id", existing.bnppId());
                        }
                        int nextVersion = (existing.versionNumber() != null ? existing.versionNumber() : 1) + 1;
                        row.put("version_number", nextVersion);
                        row.put("updated_at", new Timestamp(System.currentTimeMillis()));
                        writeList.add(row);
                        if (lookupKey != null) {
                            String activeBnpp = (row.get("bnpp_id") != null) ? (String) row.get("bnpp_id") : existing.bnppId();
                            existingMap.put(lookupKey, new ExistingRecordInfo(activeBnpp, incomingHash, nextVersion));
                        }
                    }
                } else {
                    // Case 3: New record
                    row.put("version_number", 1);
                    row.put("created_at", new Timestamp(System.currentTimeMillis()));
                    row.put("updated_at", new Timestamp(System.currentTimeMillis()));
                    writeList.add(row);
                    if (lookupKey != null) {
                        String assignedBnpp = (String) row.get("bnpp_id");
                        existingMap.put(lookupKey, new ExistingRecordInfo(assignedBnpp, incomingHash, 1));
                    }
                }
            }

            if (!writeList.isEmpty()) {
                filteredMultiRecords.put(tableName, writeList);
            }
        }

        DatabaseRowPayload result = new DatabaseRowPayload();
        result.setMultiTableRecords(filteredMultiRecords);
        return result;
    }

    private String resolveBusinessPkColumn(String tableName,
                                           List<FieldMapping> mappings,
                                           List<Map<String, Object>> incomingRows,
                                           JdbcTemplate jdbcTemplate) {
        if (mappings != null) {
            for (FieldMapping m : mappings) {
                if ("PK".equalsIgnoreCase(m.getConstraintType())
                        && m.getTargetTable() != null && m.getTargetTable().equalsIgnoreCase(tableName)
                        && m.getTargetColumn() != null) {
                    return m.getTargetColumn();
                }
            }
        }
        if (incomingRows != null && !incomingRows.isEmpty()) {
            Set<String> rowKeys = incomingRows.get(0).keySet();
            for (String candidate : List.of(tableName.toLowerCase() + "_id", "id", "code", "record_identifier")) {
                for (String k : rowKeys) {
                    if (k.equalsIgnoreCase(candidate)) {
                        return k;
                    }
                }
            }
        }
        return null;
    }

    private String resolveRowLookupKey(Map<String, Object> row, String pkCol) {
        if (pkCol != null) {
            Object val = row.get(pkCol);
            if (val == null) {
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(pkCol)) {
                        val = e.getValue();
                        break;
                    }
                }
            }
            if (val != null && !String.valueOf(val).trim().isEmpty()) {
                return String.valueOf(val).trim();
            }
        }
        String sbnpp = (String) row.get("source_bnpp_id");
        if (sbnpp != null && !sbnpp.trim().isEmpty()) {
            return sbnpp.trim();
        }
        String bnpp = (String) row.get("bnpp_id");
        if (bnpp != null && !bnpp.trim().isEmpty()) {
            return bnpp.trim();
        }
        return null;
    }

    private Map<String, ExistingRecordInfo> queryExistingRecords(JdbcTemplate jdbcTemplate,
                                                                 String tableName,
                                                                 String pkCol,
                                                                 List<Map<String, Object>> incomingRows) {
        Map<String, ExistingRecordInfo> map = new HashMap<>();
        if (incomingRows == null || incomingRows.isEmpty()) return map;

        Set<Object> lookupValues = new LinkedHashSet<>();
        for (Map<String, Object> r : incomingRows) {
            Object val = (pkCol != null) ? r.get(pkCol) : null;
            if (val == null && pkCol != null) {
                for (Map.Entry<String, Object> e : r.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(pkCol)) {
                        val = e.getValue();
                        break;
                    }
                }
            }
            if (val == null) {
                val = r.get("source_bnpp_id");
            }
            if (val == null) {
                val = r.get("bnpp_id");
            }
            if (val != null && !String.valueOf(val).trim().isEmpty()) {
                lookupValues.add(val);
            }
        }
        if (lookupValues.isEmpty()) return map;

        String matchCol = (pkCol != null) ? pkCol : "source_bnpp_id";
        List<Object> valList = new ArrayList<>(lookupValues);
        int batchSize = 500;

        for (int i = 0; i < valList.size(); i += batchSize) {
            List<Object> subList = valList.subList(i, Math.min(valList.size(), i + batchSize));
            String inClause = String.join(",", Collections.nCopies(subList.size(), "?"));
            String sql = "SELECT " + matchCol + ", bnpp_id, payload_hash, version_number FROM " + tableName + " WHERE " + matchCol + " IN (" + inClause + ")";
            try {
                jdbcTemplate.query(sql, rs -> {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    String key = rs.getString(1);
                    String bid = null;
                    String hash = null;
                    int ver = 1;
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        String cn = md.getColumnName(c);
                        if (cn.equalsIgnoreCase("bnpp_id")) bid = rs.getString(c);
                        else if (cn.equalsIgnoreCase("payload_hash")) hash = rs.getString(c);
                        else if (cn.equalsIgnoreCase("version_number")) ver = rs.getInt(c);
                    }
                    if (key != null) {
                        map.put(key, new ExistingRecordInfo(bid, hash, ver));
                    }
                }, subList.toArray());
            } catch (Exception qEx) {
                try {
                    String quotedSql = "SELECT \"" + matchCol + "\", bnpp_id, payload_hash, version_number FROM \"" + tableName + "\" WHERE \"" + matchCol + "\" IN (" + inClause + ")";
                    jdbcTemplate.query(quotedSql, rs -> {
                        java.sql.ResultSetMetaData md = rs.getMetaData();
                        String key = rs.getString(1);
                        String bid = null;
                        String hash = null;
                        int ver = 1;
                        for (int c = 1; c <= md.getColumnCount(); c++) {
                            String cn = md.getColumnName(c);
                            if (cn.equalsIgnoreCase("bnpp_id")) bid = rs.getString(c);
                            else if (cn.equalsIgnoreCase("payload_hash")) hash = rs.getString(c);
                            else if (cn.equalsIgnoreCase("version_number")) ver = rs.getInt(c);
                        }
                        if (key != null) {
                            map.put(key, new ExistingRecordInfo(bid, hash, ver));
                        }
                    }, subList.toArray());
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    /**
     * Computes a deterministic SHA-256 fingerprint of business columns in a row (excluding audit metadata).
     */
    public static String computeRowPayloadHash(Map<String, Object> rowMap) {
        if (rowMap == null || rowMap.isEmpty()) return "";
        Set<String> ignoredKeys = Set.of(
                "bnpp_id", "source_bnpp_id", "staging_bnpp_id", "version_number",
                "created_at", "updated_at", "payload_hash", "job_execution_id", "status"
        );
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> e : rowMap.entrySet()) {
            if (e.getKey() == null) continue;
            String kNorm = e.getKey().toLowerCase();
            if (ignoredKeys.contains(kNorm)) continue;
            if (e.getValue() != null && !(e.getValue() instanceof Collection) && !(e.getValue() instanceof Map)) {
                sorted.put(kNorm, String.valueOf(e.getValue()));
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sorted.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            return Integer.toHexString(sorted.hashCode());
        }
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
            if (key.startsWith("_entities_")) continue;
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

    private List<StagingXmlRecord> fetchStagedRecordsWithKeyset(JdbcTemplate jdbcTemplate, String sql, long lastSeenId) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            StagingXmlRecord r = new StagingXmlRecord();
            r.setId(rs.getLong("id"));
            try { r.setJobExecutionId(rs.getLong("job_execution_id")); } catch (Exception ignored) {}
            try { r.setBnppId(rs.getString("bnpp_id")); } catch (Exception ignored) {}
            try { r.setTagName(rs.getString("tag_name")); } catch (Exception ignored) {}
            try { r.setVersionNumber(rs.getInt("version_number")); } catch (Exception ignored) {}
            try { r.setRecordIdentifier(rs.getString("record_identifier")); } catch (Exception ignored) {}
            try { r.setRecordAction(rs.getString("record_action")); } catch (Exception ignored) {}
            try { r.setRecordDate(rs.getString("record_date")); } catch (Exception ignored) {}
            try { r.setRawPayload(rs.getString("raw_payload")); } catch (Exception ignored) {}
            try { r.setPayloadHash(rs.getString("payload_hash")); } catch (Exception ignored) {}
            try { r.setStatus(rs.getString("status")); } catch (Exception ignored) {}
            return r;
        }, lastSeenId);
    }

    private boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        try {
            jdbcTemplate.queryForObject("SELECT 1 FROM " + tableName + " WHERE 1=0", Integer.class);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<String> findExistingFlatTables(JdbcTemplate jdbcTemplate) {
        List<String> flats = new ArrayList<>();
        try {
            if (jdbcTemplate.getDataSource() != null) {
                try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
                    DatabaseMetaData meta = conn.getMetaData();
                    String schema = null;
                    try { schema = conn.getSchema(); } catch (Throwable ignored) {}
                    try (ResultSet rs = meta.getTables(null, schema, "%", new String[]{"TABLE"})) {
                        while (rs.next()) {
                            String name = rs.getString("TABLE_NAME");
                            if (name != null && name.toLowerCase().endsWith("_flat")
                                    && !name.equalsIgnoreCase("staging_xml_records")
                                    && !name.contains("__")
                                    && !XmlSchemaDiscoveryService.isWrapperContainerTag(name.replaceFirst("(?i)_flat$", ""), null)) {
                                flats.add(name.toLowerCase());
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            try {
                jdbcTemplate.query("SELECT table_name FROM information_schema.tables WHERE LOWER(table_name) LIKE '%_flat'", rs -> {
                    String name = rs.getString(1);
                    if (name != null && !name.equalsIgnoreCase("staging_xml_records")
                            && !name.contains("__")
                            && !XmlSchemaDiscoveryService.isWrapperContainerTag(name.replaceFirst("(?i)_flat$", ""), null)) {
                        flats.add(name.toLowerCase());
                    }
                });
            } catch (Exception ignored) {}
        }
        return flats;
    }

    private void updateFlatTableStatus(JdbcTemplate jdbcTemplate, String flatTable, List<StagingXmlRecord> records, String status, String errorMessage) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(StagingXmlRecord::getId).filter(Objects::nonNull).toList();
        if (!ids.isEmpty()) {
            try {
                String updateSql = "UPDATE " + flatTable + " SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
                jdbcTemplate.batchUpdate(updateSql, ids, ids.size(), (ps, id) -> {
                    ps.setString(1, status);
                    ps.setLong(2, id);
                });
            } catch (Exception ignored) {}
        }
    }

    private void updateStagingStatus(JdbcTemplate jdbcTemplate, List<StagingXmlRecord> records, String status, String errorMessage) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(StagingXmlRecord::getId).filter(Objects::nonNull).toList();
        if (!ids.isEmpty()) {
            try {
                String updateSql = "UPDATE staging_xml_records SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
                jdbcTemplate.batchUpdate(updateSql, ids, ids.size(), (ps, id) -> {
                    ps.setString(1, status);
                    ps.setString(2, errorMessage);
                    ps.setLong(3, id);
                });
            } catch (Exception ignored) {}
        }
        try {
            String updateByIdentifier = "UPDATE staging_xml_records SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE (record_identifier = ? AND payload_hash = ?) OR bnpp_id = ?";
            jdbcTemplate.batchUpdate(updateByIdentifier, records, records.size(), (ps, r) -> {
                ps.setString(1, status);
                ps.setString(2, errorMessage);
                ps.setString(3, r.getRecordIdentifier());
                ps.setString(4, r.getPayloadHash());
                ps.setString(5, r.getBnppId());
            });
        } catch (Exception ignored) {}
    }

    private List<FieldMapping> autoDeriveMappingsFromTargetTables(JdbcTemplate jdbcTemplate, List<String> tableOrder, String masterTable) {
        List<FieldMapping> generated = new ArrayList<>();
        if (jdbcTemplate == null || jdbcTemplate.getDataSource() == null) return generated;
        List<String> tables = new ArrayList<>();
        if (tableOrder != null && !tableOrder.isEmpty()) {
            tables.addAll(tableOrder);
        } else {
            try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                String schema = null;
                try { schema = conn.getSchema(); } catch (Throwable ignored) {}
                try (ResultSet rs = meta.getTables(null, schema, "%", new String[]{"TABLE"})) {
                    while (rs.next()) {
                        String t = rs.getString("TABLE_NAME");
                        if (t != null) {
                            String norm = t.toLowerCase();
                            if (!norm.endsWith("_flat") && !norm.startsWith("staging_") && !norm.startsWith("batch_")) {
                                tables.add(t);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (tables.isEmpty() && masterTable != null) {
                tables.add(masterTable);
            }
        }

        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String schema = null;
            try { schema = conn.getSchema(); } catch (Throwable ignored) {}
            for (String tbl : tables) {
                if (tbl == null || tbl.trim().isEmpty()) continue;
                for (String tName : List.of(tbl, tbl.toUpperCase(), tbl.toLowerCase())) {
                    try (ResultSet rs = meta.getColumns(null, schema, tName, null)) {
                        boolean foundAny = false;
                        while (rs.next()) {
                            String colName = rs.getString("COLUMN_NAME");
                            if (colName != null
                                    && !colName.equalsIgnoreCase("created_at")
                                    && !colName.equalsIgnoreCase("updated_at")
                                    && !colName.equalsIgnoreCase("extra_attributes")
                                    && !colName.equalsIgnoreCase("bnpp_id")
                                    && !colName.equalsIgnoreCase("source_bnpp_id")
                                    && !colName.equalsIgnoreCase("staging_bnpp_id")
                                    && !colName.equalsIgnoreCase("version_number")
                                    && !colName.equalsIgnoreCase("tag_name")
                                    && !colName.equalsIgnoreCase("payload_hash")) {

                                boolean isPk = colName.equalsIgnoreCase("id") || colName.equalsIgnoreCase(tbl + "_id");
                                boolean isFk = !isPk && colName.toLowerCase().endsWith("_id");
                                String constraint = isPk ? "PK" : (isFk ? "FK" : "NONE");

                                String xmlField = tbl.toLowerCase() + "/" + colName.toLowerCase();
                                FieldMapping fm1 = new FieldMapping(xmlField, tbl, colName, constraint, "NONE");
                                FieldMapping fm2 = new FieldMapping(colName.toLowerCase(), tbl, colName, constraint, "NONE");
                                FieldMapping fm3 = new FieldMapping("@" + colName.toLowerCase(), tbl, colName, constraint, "NONE");

                                if (isFk) {
                                    String fkTarget = colName.substring(0, colName.length() - 3);
                                    fm1.setForeignKeyTable(fkTarget);
                                    fm1.setForeignKeyColumn("id");
                                    fm2.setForeignKeyTable(fkTarget);
                                    fm2.setForeignKeyColumn("id");
                                    fm3.setForeignKeyTable(fkTarget);
                                    fm3.setForeignKeyColumn("id");
                                }

                                generated.add(fm1);
                                generated.add(fm2);
                                generated.add(fm3);

                                // If the column is an entity identifier column (e.g. person_id, country_id, or id)
                                if (isPk) {
                                    generated.add(new FieldMapping("@id", tbl, colName, "PK", "NONE"));
                                    generated.add(new FieldMapping("id", tbl, colName, "PK", "NONE"));
                                    generated.add(new FieldMapping("@code", tbl, colName, "PK", "NONE"));
                                    generated.add(new FieldMapping("code", tbl, colName, "PK", "NONE"));
                                    generated.add(new FieldMapping(tbl.toLowerCase() + "/@id", tbl, colName, "PK", "NONE"));
                                }
                                foundAny = true;
                            }
                        }
                        if (foundAny) break;
                    }
                }
            }
        } catch (Exception ignored) {}
        return generated;
    }
}
