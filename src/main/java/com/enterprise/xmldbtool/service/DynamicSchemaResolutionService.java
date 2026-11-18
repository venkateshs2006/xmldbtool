package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.batch.DatabaseErrorSkipListener;
import com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter;
import com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor;
import com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller;
import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.model.StagingXmlRecord;
import com.enterprise.xmldbtool.util.BnppIdGenerator;
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

    public record ExistingRecordInfo(String bnppId, String payloadHash, Integer versionNumber, String historyIds, String sourceBnppId, String currentId, String sourceId) {
        public ExistingRecordInfo(String bnppId, String payloadHash, Integer versionNumber, String historyIds, String sourceBnppId) {
            this(bnppId, payloadHash, versionNumber, historyIds, sourceBnppId, bnppId, sourceBnppId);
        }
        public ExistingRecordInfo(String bnppId, String payloadHash, Integer versionNumber, String historyIds) {
            this(bnppId, payloadHash, versionNumber, historyIds, null, bnppId, null);
        }
    }

    private final SystemLoggingService systemLoggingService;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamicSchemaResolutionService(ObjectMapper objectMapper,
                                          @org.springframework.beans.factory.annotation.Autowired(required = false) SystemLoggingService systemLoggingService) {
        this.objectMapper = objectMapper;
        this.systemLoggingService = systemLoggingService;
    }

    public DynamicSchemaResolutionService(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public static String appendHistoryId(String existingHistoryIds, String newId) {
        if (newId == null || newId.trim().isEmpty()) return existingHistoryIds;
        List<String> list = new ArrayList<>();
        if (existingHistoryIds != null && !existingHistoryIds.trim().isEmpty()) {
            String s = existingHistoryIds.trim();
            if (s.startsWith("[") && s.endsWith("]")) {
                s = s.substring(1, s.length() - 1);
                for (String item : s.split(",")) {
                    String clean = item.trim().replace("\"", "").replace("'", "");
                    if (!clean.isEmpty() && !list.contains(clean)) {
                        list.add(clean);
                    }
                }
            } else {
                for (String item : s.split(",")) {
                    String clean = item.trim().replace("\"", "").replace("'", "");
                    if (!clean.isEmpty() && !list.contains(clean)) {
                        list.add(clean);
                    }
                }
            }
        }
        if (!list.contains(newId.trim())) {
            list.add(newId.trim());
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(list.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    @FunctionalInterface
    public interface NormalizationProgressCallback {
        void onProgress(long totalProcessed, long normalizedCount, long failedCount, Map<String, Long> tableWriteCounts, String currentTable);
    }

    /**
     * Directly moves data from flat staging tables gs_<entitytable> to canonical destination tables
     * selecting the LATEST VERSION of the data for each record.
     * Updates:
     * - source_id / source_bnpp_id: Root originating identifier
     * - current_id / bnpp_id: Active latest version identifier
     * - history_ids: Full chronological audit trail of identifiers across all historical versions
     * Marks all processed records in gs_<entitytable> as NORMALIZED.
     */
    public NormalizationSummary moveGsToCanonical(DataSource targetDataSource, List<FieldMapping> mappings) throws Exception {
        return moveGsToCanonical(null, targetDataSource, mappings, null, null, 1000, null);
    }

    public NormalizationSummary moveGsToCanonical(Long jobExecutionId,
                                                  DataSource targetDataSource,
                                                  List<FieldMapping> mappings,
                                                  List<String> tableOrder,
                                                  String targetMasterTable,
                                                  int batchChunkSize,
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

        DatabaseErrorSkipListener skipListener = new DatabaseErrorSkipListener(targetDataSource, System.currentTimeMillis(), "moveGsToCanonicalStep");
        DynamicJdbcBatchWriter batchWriter = new DynamicJdbcBatchWriter(targetDataSource, resolvedTableOrder, skipListener);
        batchWriter.setWriteToHistoricalGsTables(false);
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

        // Find candidate gs_<entity> flat tables
        Set<String> candidateFlatTables = new LinkedHashSet<>();
        Set<String> processedEntities = new HashSet<>();
        if (resolvedTableOrder != null) {
            for (String tbl : resolvedTableOrder) {
                String norm = tbl.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                String singNorm = DynamicXmlUnmarshaller.singularize(norm);
                String plurNorm = DynamicXmlUnmarshaller.pluralize(norm);
                if (processedEntities.contains(norm) || processedEntities.contains(singNorm) || processedEntities.contains(plurNorm)) continue;

                for (String candidate : List.of("gs_" + norm, "gs_" + singNorm, "gs_" + plurNorm, norm + "_gs", singNorm + "_gs", plurNorm + "_gs", norm + "_flat", singNorm + "_flat", plurNorm + "_flat")) {
                    if (candidate.equalsIgnoreCase("gs_error_records") || candidate.equalsIgnoreCase("migration_errors")
                            || candidate.equalsIgnoreCase("staging_xml_records") || candidate.equalsIgnoreCase("file_history")
                            || candidate.equalsIgnoreCase("audit_log") || candidate.equalsIgnoreCase("error_log")) {
                        continue;
                    }
                    if (tableExists(jdbcTemplate, candidate)) {
                        candidateFlatTables.add(candidate);
                        processedEntities.add(norm);
                        processedEntities.add(singNorm);
                        processedEntities.add(plurNorm);
                        break;
                    }
                }
            }
        }

        // Also discover any other existing gs_* tables
        for (String flat : findExistingFlatTables(jdbcTemplate)) {
            String normFlat = flat.toLowerCase();
            String entityBase = normFlat.startsWith("gs_") ? normFlat.substring(3) : normFlat.replaceFirst("(?i)_(gs|flat)$", "");
            String singBase = DynamicXmlUnmarshaller.singularize(entityBase);
            String plurBase = DynamicXmlUnmarshaller.pluralize(entityBase);
            if (!normFlat.equals("staging_xml_records") && !normFlat.equals("gs_error_records") && !normFlat.equals("migration_errors")
                    && !processedEntities.contains(entityBase) && !processedEntities.contains(singBase) && !processedEntities.contains(plurBase)
                    && !candidateFlatTables.contains(normFlat)) {
                candidateFlatTables.add(normFlat);
                processedEntities.add(entityBase);
                processedEntities.add(singBase);
                processedEntities.add(plurBase);
            }
        }

        long totalProcessed = 0;
        long normalizedCount = 0;
        long failedCount = 0;

        for (String flatTable : candidateFlatTables) {
            log.info("Moving latest version records from flat table '{}' to canonical table(s)...", flatTable);

            String selectSql = "SELECT * FROM " + flatTable + " ORDER BY id ASC";
            List<StagingXmlRecord> allFlatRecords = jdbcTemplate.query(selectSql, (rs, rowNum) -> {
                StagingXmlRecord r = new StagingXmlRecord();
                r.setId(rs.getLong("id"));
                try { r.setJobExecutionId(rs.getLong("job_execution_id")); } catch (Exception ignored) {}
                try { r.setFileId(rs.getString("file_id")); } catch (Exception ignored) {}
                try { r.setBnppId(rs.getString("bnpp_id")); } catch (Exception ignored) {}
                try { r.setCurrentId(rs.getString("current_id")); } catch (Exception ignored) {}
                try { if (r.getCurrentId() == null) r.setCurrentId(r.getBnppId()); } catch (Exception ignored) {}
                try { r.setSourceId(rs.getString("source_id")); } catch (Exception ignored) {}
                try { r.setSourceBnppId(rs.getString("source_bnpp_id")); } catch (Exception ignored) {}
                try { if (r.getSourceId() == null) r.setSourceId(r.getSourceBnppId()); } catch (Exception ignored) {}
                try { r.setStagingBnppId(rs.getString("staging_bnpp_id")); } catch (Exception ignored) {}
                try { r.setHistoryIds(rs.getString("history_ids")); } catch (Exception ignored) {}
                try { r.setTagName(rs.getString("tag_name")); } catch (Exception ignored) {}
                try {
                    if (r.getTagName() == null || r.getTagName().isEmpty()) r.setTagName(rs.getString("entity_type"));
                } catch (Exception ignored) {}
                try { r.setEntityType(rs.getString("entity_type")); } catch (Exception ignored) {}
                try {
                    if (r.getEntityType() == null || r.getEntityType().isEmpty()) r.setEntityType(rs.getString("tag_name"));
                } catch (Exception ignored) {}
                try { r.setVersionNumber(rs.getInt("version_number")); } catch (Exception ignored) {}
                try { r.setRecordIdentifier(rs.getString("record_identifier")); } catch (Exception ignored) {}
                try { r.setRecordAction(rs.getString("record_action")); } catch (Exception ignored) {}
                try { r.setRecordDate(rs.getString("record_date")); } catch (Exception ignored) {}
                try { r.setRawPayload(rs.getString("raw_payload")); } catch (Exception ignored) {}
                try { r.setPayloadHash(rs.getString("payload_hash")); } catch (Exception ignored) {}
                try { r.setStatus(rs.getString("status")); } catch (Exception ignored) {}

                try {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    Map<String, Object> colMap = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String cName = md.getColumnName(i);
                        Object val = rs.getObject(i);
                        if (val != null) {
                            colMap.put(cName, val);
                            colMap.put(cName.toLowerCase(), val);
                        }
                    }
                    r.setCachedMap(colMap);
                } catch (Exception ignored) {}
                return r;
            });

            if (allFlatRecords.isEmpty()) {
                continue;
            }

            // Group records by business recordIdentifier (or fallback to id/bnpp_id)
            Map<String, List<StagingXmlRecord>> recordsByEntityKey = new LinkedHashMap<>();
            for (StagingXmlRecord rec : allFlatRecords) {
                String key = rec.getRecordIdentifier();
                if (key == null || key.trim().isEmpty()) {
                    if (rec.getCachedMap() != null) {
                        for (String candidateCol : List.of("person_id", "id", "@id", "code", "record_identifier")) {
                            Object val = rec.getCachedMap().get(candidateCol);
                            if (val != null && !String.valueOf(val).trim().isEmpty()) {
                                key = String.valueOf(val).trim();
                                break;
                            }
                        }
                    }
                }
                if (key == null || key.trim().isEmpty()) {
                    key = rec.getBnppId() != null ? rec.getBnppId() : ("REC_" + rec.getId());
                }
                recordsByEntityKey.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
            }

            List<StagingXmlRecord> latestVersionRecords = new ArrayList<>();
            List<StagingXmlRecord> allProcessedRecords = new ArrayList<>();

            for (Map.Entry<String, List<StagingXmlRecord>> entry : recordsByEntityKey.entrySet()) {
                List<StagingXmlRecord> historyList = entry.getValue();
                // Sort by version_number ASC, id ASC
                historyList.sort(Comparator.comparingInt((StagingXmlRecord r) -> r.getVersionNumber() != null ? r.getVersionNumber() : 1)
                        .thenComparingLong(r -> r.getId() != null ? r.getId() : 0L));

                allProcessedRecords.addAll(historyList);

                // Originating source ID is from the first version
                StagingXmlRecord firstRec = historyList.get(0);
                String sourceId = firstRec.getSourceId();
                if (sourceId == null) sourceId = firstRec.getSourceBnppId();
                if (sourceId == null) sourceId = firstRec.getCurrentId();
                if (sourceId == null) sourceId = firstRec.getBnppId();

                // Latest version record
                StagingXmlRecord latestRec = historyList.get(historyList.size() - 1);
                String currentId = latestRec.getCurrentId();
                if (currentId == null) currentId = latestRec.getBnppId();
                if (currentId == null) currentId = BnppIdGenerator.generateId("BNPP");

                int maxVer = latestRec.getVersionNumber() != null ? latestRec.getVersionNumber() : historyList.size();

                // Aggregate complete history of IDs across all historical versions
                String aggregatedHistory = null;
                for (StagingXmlRecord h : historyList) {
                    String hid = h.getCurrentId() != null ? h.getCurrentId() : h.getBnppId();
                    if (hid != null) {
                        aggregatedHistory = appendHistoryId(aggregatedHistory, hid);
                    }
                    if (h.getHistoryIds() != null) {
                        aggregatedHistory = appendHistoryId(aggregatedHistory, h.getHistoryIds());
                    }
                }
                if (aggregatedHistory == null) {
                    aggregatedHistory = "[" + (sourceId != null ? "\"" + sourceId + "\"" : "") + "]";
                }

                latestRec.setSourceId(sourceId);
                latestRec.setSourceBnppId(sourceId);
                latestRec.setCurrentId(currentId);
                latestRec.setBnppId(currentId);
                latestRec.setHistoryIds(aggregatedHistory);
                latestRec.setVersionNumber(maxVer);

                latestVersionRecords.add(latestRec);
            }

            // Now project and write the latest records in chunks to canonical tables
            for (int i = 0; i < latestVersionRecords.size(); i += chunkSize) {
                List<StagingXmlRecord> subBatch = latestVersionRecords.subList(i, Math.min(latestVersionRecords.size(), i + chunkSize));
                NormalizationBatchResult batchResult = processStagedBatch(
                        subBatch, processor, effectiveMappings, jdbcTemplate, batchWriter, skipListener, true
                );

                totalProcessed += batchResult.processed();
                normalizedCount += batchResult.written();
                failedCount += batchResult.failed();
            }

            // Update status of ALL processed records in gs_<entity> table to NORMALIZED
            updateFlatTableStatus(jdbcTemplate, flatTable, allProcessedRecords, "NORMALIZED", null);
            updateStagingStatus(jdbcTemplate, allProcessedRecords, "NORMALIZED", null);

            if (progressCallback != null) {
                progressCallback.onProgress(totalProcessed, normalizedCount, failedCount,
                        new LinkedHashMap<>(batchWriter.getTableWriteCounts()), flatTable);
            }
        }

        if (progressCallback != null) {
            progressCallback.onProgress(totalProcessed, normalizedCount, failedCount,
                    new LinkedHashMap<>(batchWriter.getTableWriteCounts()), "COMPLETED");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("moveGsToCanonical completed: stagedProcessed={}, normalized={}, failed={}, tableWrites={}, elapsedMs={}",
                totalProcessed, normalizedCount, failedCount, batchWriter.getTableWriteCounts(), elapsed);

        return new NormalizationSummary(totalProcessed, normalizedCount, failedCount, batchWriter.getTableWriteCounts(), elapsed);
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
        batchWriter.setWriteToHistoricalGsTables(false);
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

        // 1. Identify distinct entity flat tables for high-performance 10GB+ partitioned ingestion
        Set<String> candidateFlatTables = new LinkedHashSet<>();
        Set<String> processedEntities = new HashSet<>();
        if (resolvedTableOrder != null) {
            for (String tbl : resolvedTableOrder) {
                String norm = tbl.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                String singNorm = com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller.singularize(norm);
                String plurNorm = com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller.pluralize(norm);
                if (processedEntities.contains(norm) || processedEntities.contains(singNorm) || processedEntities.contains(plurNorm)) continue;

                String gsPrefixDirect = "gs_" + norm;
                String gsPrefixSingular = "gs_" + singNorm;
                String gsPrefixPlural = "gs_" + plurNorm;
                String directGs = norm + "_gs";
                String singularGs = singNorm + "_gs";
                String pluralGs = plurNorm + "_gs";
                String directFlat = norm + "_flat";
                String singularFlat = singNorm + "_flat";
                String pluralFlat = plurNorm + "_flat";

                String selected = null;
                for (String candidate : List.of(gsPrefixDirect, gsPrefixSingular, gsPrefixPlural, directGs, singularGs, pluralGs, directFlat, singularFlat, pluralFlat)) {
                    if (candidate.equalsIgnoreCase("gs_error_records") || candidate.equalsIgnoreCase("migration_errors")
                            || candidate.equalsIgnoreCase("staging_xml_records") || candidate.equalsIgnoreCase("gs_file_history")
                            || candidate.equalsIgnoreCase("gs_audit_log") || candidate.equalsIgnoreCase("gs_audit_logs")
                            || candidate.equalsIgnoreCase("gs_error_log") || candidate.equalsIgnoreCase("gs_error_logs")
                            || candidate.equalsIgnoreCase("file_history") || candidate.equalsIgnoreCase("audit_log")
                            || candidate.equalsIgnoreCase("audit_logs") || candidate.equalsIgnoreCase("error_log")
                            || candidate.equalsIgnoreCase("error_logs")) {
                        continue;
                    }
                    if (tableExists(jdbcTemplate, candidate)) {
                        if (hasUnnormalizedRecords(jdbcTemplate, candidate, jobExecutionId)) {
                            selected = candidate;
                            break;
                        }
                        if (selected == null) {
                            selected = candidate;
                        }
                    }
                }
                if (selected != null) {
                    candidateFlatTables.add(selected);
                    processedEntities.add(norm);
                    processedEntities.add(singNorm);
                    processedEntities.add(plurNorm);
                }
            }
        }

        // Count already normalized records if resuming from a previous run
        long initialNormalized = 0;
        for (String flatTable : candidateFlatTables) {
            try {
                Integer normInTable = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + flatTable + " WHERE status = 'NORMALIZED'",
                        Integer.class
                );
                if (normInTable != null) {
                    initialNormalized += normInTable;
                }
            } catch (Exception ignored) {}
        }

        long totalProcessed = initialNormalized;
        long normalizedCount = initialNormalized;
        long failedCount = 0;

        // Also check if any other gs_<root>, <root>_flat or <root>_gs tables exist in the target database
        for (String flat : findExistingFlatTables(jdbcTemplate)) {
            String normFlat = flat.toLowerCase();
            String entityBase = normFlat.startsWith("gs_") ? normFlat.substring(3) : normFlat.replaceFirst("(?i)_(gs|flat)$", "");
            String singBase = com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller.singularize(entityBase);
            String plurBase = com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller.pluralize(entityBase);
            if (!normFlat.equals("staging_xml_records")
                    && !normFlat.equals("gs_error_records")
                    && !normFlat.equals("migration_errors")
                    && !processedEntities.contains(entityBase)
                    && !processedEntities.contains(singBase)
                    && !processedEntities.contains(plurBase)
                    && !candidateFlatTables.contains(normFlat)) {
                candidateFlatTables.add(normFlat);
                processedEntities.add(entityBase);
                processedEntities.add(singBase);
                processedEntities.add(plurBase);
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

        // Process each entity-specific flat table in dependency sequence using Java 21 Virtual Threads for high-speed parallel DB insertion
        int maxWorkers = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors() * 2));

        for (String flatTable : candidateFlatTables) {
            long lastSeenId = 0;
            boolean hasMore = true;

            try (java.util.concurrent.ExecutorService vThreadExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                java.util.concurrent.Semaphore inFlightTasks = new java.util.concurrent.Semaphore(maxWorkers);
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                java.util.concurrent.atomic.AtomicLong atomicProcessed = new java.util.concurrent.atomic.AtomicLong(totalProcessed);
                java.util.concurrent.atomic.AtomicLong atomicNormalized = new java.util.concurrent.atomic.AtomicLong(normalizedCount);
                java.util.concurrent.atomic.AtomicLong atomicFailed = new java.util.concurrent.atomic.AtomicLong(failedCount);

                while (hasMore) {
                    if (cancellationChecker != null && cancellationChecker.getAsBoolean()) {
                        log.info("Job #{} termination requested. Cleanly halting Stage 2 normalization at record #{}.", jobExecutionId, atomicProcessed.get());
                        break;
                    }

                    String selectSql = "SELECT * FROM " + flatTable + " WHERE id > ? AND " + statusClause + " ORDER BY id ASC " +
                            (isOracle ? "FETCH FIRST " + chunkSize + " ROWS ONLY" : "LIMIT " + chunkSize);

                    List<StagingXmlRecord> stagedBatch = fetchStagedRecordsWithKeyset(jdbcTemplate, selectSql, lastSeenId);
                    if (stagedBatch.isEmpty()) {
                        break;
                    }
                    processedAnyFlat = true;
                    lastSeenId = stagedBatch.get(stagedBatch.size() - 1).getId();

                    inFlightTasks.acquire();
                    futures.add(vThreadExecutor.submit(() -> {
                        try {
                            NormalizationBatchResult batchResult = processStagedBatch(
                                    stagedBatch, processor, effectiveMappings, jdbcTemplate, batchWriter, skipListener
                            );

                            atomicProcessed.addAndGet(batchResult.processed);
                            atomicNormalized.addAndGet(batchResult.written);
                            atomicFailed.addAndGet(batchResult.failed);

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
                                progressCallback.onProgress(atomicProcessed.get(), atomicNormalized.get(), atomicFailed.get(),
                                        new LinkedHashMap<>(batchWriter.getTableWriteCounts()), flatTable);
                            }
                        } finally {
                            inFlightTasks.release();
                        }
                    }));
                }

                for (java.util.concurrent.Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception ex) {
                        log.error("Error in parallel normalization worker for '{}': {}", flatTable, ex.getMessage(), ex);
                    }
                }

                totalProcessed = atomicProcessed.get();
                normalizedCount = atomicNormalized.get();
                failedCount = atomicFailed.get();
            } catch (Exception ex) {
                log.error("Error managing virtual thread execution in normalization for '{}': {}", flatTable, ex.getMessage(), ex);
            }
        }

        // 2. Fallback: Process central staging_xml_records ONLY if no partitioned flat tables were processed and table exists
        if (!processedAnyFlat && tableExists(jdbcTemplate, "staging_xml_records")) {
            long lastSeenCentralId = 0;
            boolean hasMoreCentral = true;

            try (java.util.concurrent.ExecutorService vThreadExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                java.util.concurrent.Semaphore inFlightTasks = new java.util.concurrent.Semaphore(maxWorkers);
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                java.util.concurrent.atomic.AtomicLong atomicProcessed = new java.util.concurrent.atomic.AtomicLong(totalProcessed);
                java.util.concurrent.atomic.AtomicLong atomicNormalized = new java.util.concurrent.atomic.AtomicLong(normalizedCount);
                java.util.concurrent.atomic.AtomicLong atomicFailed = new java.util.concurrent.atomic.AtomicLong(failedCount);

                while (hasMoreCentral) {
                    if (cancellationChecker != null && cancellationChecker.getAsBoolean()) {
                        log.info("Job #{} termination requested. Cleanly halting Stage 2 normalization at record #{}.", jobExecutionId, atomicProcessed.get());
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

                    inFlightTasks.acquire();
                    futures.add(vThreadExecutor.submit(() -> {
                        try {
                            NormalizationBatchResult batchResult = processStagedBatch(
                                    centralBatch, processor, effectiveMappings, jdbcTemplate, batchWriter, skipListener
                            );

                            atomicProcessed.addAndGet(batchResult.processed);
                            atomicNormalized.addAndGet(batchResult.written);
                            atomicFailed.addAndGet(batchResult.failed);

                            if (!batchResult.successfulRecords.isEmpty()) {
                                updateStagingStatus(jdbcTemplate, batchResult.successfulRecords, "NORMALIZED", null);
                            }
                            for (Map.Entry<StagingXmlRecord, String> entry : batchResult.failedRecords.entrySet()) {
                                updateStagingStatus(jdbcTemplate, List.of(entry.getKey()), "FAILED", entry.getValue());
                            }

                            if (progressCallback != null) {
                                progressCallback.onProgress(atomicProcessed.get(), atomicNormalized.get(), atomicFailed.get(),
                                        new LinkedHashMap<>(batchWriter.getTableWriteCounts()), "staging_xml_records");
                            }
                        } finally {
                            inFlightTasks.release();
                        }
                    }));
                }

                for (java.util.concurrent.Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception ex) {
                        log.error("Error in parallel central normalization worker: {}", ex.getMessage(), ex);
                    }
                }

                totalProcessed = atomicProcessed.get();
                normalizedCount = atomicNormalized.get();
                failedCount = atomicFailed.get();
            } catch (Exception ex) {
                log.error("Error managing virtual thread execution in central normalization: {}", ex.getMessage(), ex);
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
        return processStagedBatch(stagedBatch, processor, mappings, jdbcTemplate, batchWriter, skipListener, false);
    }

    private NormalizationBatchResult processStagedBatch(List<StagingXmlRecord> stagedBatch,
                                                        DynamicXmlItemProcessor processor,
                                                        List<FieldMapping> mappings,
                                                        JdbcTemplate jdbcTemplate,
                                                        DynamicJdbcBatchWriter batchWriter,
                                                        DatabaseErrorSkipListener skipListener,
                                                        boolean preserveVersion) {
        long processed = 0;
        long written = 0;
        long failed = 0;
        List<StagingXmlRecord> successfulRecords = new ArrayList<>(stagedBatch.size());
        Map<StagingXmlRecord, String> failedRecords = new LinkedHashMap<>();

        List<DatabaseRowPayload> chunkPayloads = new ArrayList<>(stagedBatch.size());
        Map<StagingXmlRecord, DatabaseRowPayload> recordToPayload = new LinkedHashMap<>();
        List<StagingXmlRecord> validCandidateRecords = new ArrayList<>();
        Set<String> mappedXmlFields = getOrBuildMappedFields(mappings);

        for (StagingXmlRecord record : stagedBatch) {
            processed++;
            try {
                Map<String, Object> rawMap = new LinkedHashMap<>();
                String payloadStr = record.getRawPayload();
                if (payloadStr != null && !payloadStr.trim().isEmpty()) {
                    if (payloadStr.trim().startsWith("<")) {
                        try {
                            List<Map<String, Object>> parsed = DynamicXmlItemProcessor.parseRawXmlSubTagEntities(payloadStr, null, mappings, record.getCachedMap());
                            if (!parsed.isEmpty()) rawMap.putAll(parsed.get(0));
                        } catch (Exception ignored) {}
                    } else if (payloadStr.trim().startsWith("{") || payloadStr.trim().startsWith("[")) {
                        try {
                            Map<String, Object> jsonMap = objectMapper.readValue(payloadStr, new TypeReference<Map<String, Object>>() {});
                            if (jsonMap != null) rawMap.putAll(jsonMap);
                        } catch (Exception ignored) {}
                    }
                }
                // Merge all business columns from the flat table row (especially sub-tag raw XML columns!)
                if (record.getCachedMap() != null) {
                    for (Map.Entry<String, Object> entry : record.getCachedMap().entrySet()) {
                        String k = entry.getKey().toLowerCase();
                        if (k.equals("id") || k.equals("status") || k.equals("version_number")
                                || k.equals("payload_hash") || k.equals("job_execution_id")
                                || k.equals("created_at") || k.equals("updated_at")
                                || k.equals("xml_version") || k.equals("schema_version")) {
                            continue;
                        }
                        rawMap.putIfAbsent(entry.getKey(), entry.getValue());
                        rawMap.putIfAbsent(k, entry.getValue());
                    }
                }
                if (record.getRecordIdentifier() != null) {
                    rawMap.putIfAbsent("record_identifier", record.getRecordIdentifier());
                    rawMap.putIfAbsent("id", record.getRecordIdentifier());
                    rawMap.putIfAbsent("@id", record.getRecordIdentifier());
                }
                if (record.getBnppId() != null) {
                    rawMap.putIfAbsent("bnpp_id", record.getBnppId());
                }

                String entityTag = record.getTagName() != null ? record.getTagName() : record.getEntityType();
                if (entityTag != null) {
                    rawMap.put("_entity_tag", entityTag);
                    rawMap.put("_tag_name", entityTag);
                }

                // Capture unmapped delta XML fields into unmapped_attributes JSON
                enrichWithSchemaEvolutionAttributes(rawMap, mappedXmlFields);

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
                if (systemLoggingService != null) {
                    try {
                        systemLoggingService.logError(jdbcTemplate.getDataSource(), record.getFileId(), record.getJobExecutionId(),
                                record.getEntityType(), record.getRecordIdentifier(), "ERROR", "STAGE_2_PROJECTION_ERROR",
                                ex.getMessage(), ex, record.getRawPayload());
                    } catch (Exception ignored) {}
                }
            }
        }

        // Perform per-table deduplication, delta versioning, and batch write
        if (!chunkPayloads.isEmpty()) {
            DatabaseRowPayload filteredCombinedPayload = applyDeduplicationAndVersioning(
                    chunkPayloads, mappings, jdbcTemplate, preserveVersion
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
                            if (systemLoggingService != null) {
                                try {
                                    systemLoggingService.logError(jdbcTemplate.getDataSource(), rec.getFileId(), rec.getJobExecutionId(),
                                            rec.getEntityType(), rec.getRecordIdentifier(), "ERROR", "CANONICAL_WRITE_ERROR",
                                            singleEx.getMessage(), singleEx, rec.getRawPayload());
                                } catch (Exception ignored) {}
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

                // 1. Resolve bnpp_id & current_id: prioritize staging record's currentId/bnppId
                String existingCurr = (record != null && record.getCurrentId() != null) ? record.getCurrentId()
                        : (record != null && record.getBnppId() != null) ? record.getBnppId()
                        : (String) row.get("current_id");
                if (existingCurr == null || existingCurr.trim().isEmpty()) {
                    existingCurr = (String) row.get("bnpp_id");
                }
                if (existingCurr == null || existingCurr.trim().isEmpty()) {
                    existingCurr = BnppIdGenerator.generateId("BNPP");
                }
                row.put("bnpp_id", existingCurr);
                row.put("current_id", existingCurr);

                // 1b. Reference flat/staging table's source_id & bnpp_id and file_id
                String sourceBnpp = (record != null && record.getSourceId() != null) ? record.getSourceId()
                        : (record != null && record.getSourceBnppId() != null) ? record.getSourceBnppId()
                        : (record != null && record.getBnppId() != null) ? record.getBnppId()
                        : (String) row.get("source_id");
                if (sourceBnpp == null) sourceBnpp = (String) row.get("source_bnpp_id");
                if (sourceBnpp == null) sourceBnpp = existingCurr;

                String stagingBnpp = (record != null && record.getStagingBnppId() != null) ? record.getStagingBnppId()
                        : (record != null && record.getBnppId() != null) ? record.getBnppId()
                        : (String) row.get("staging_bnpp_id");
                if (stagingBnpp == null) stagingBnpp = existingCurr;

                row.put("source_id", sourceBnpp);
                row.put("source_bnpp_id", sourceBnpp);
                row.put("staging_bnpp_id", stagingBnpp);

                // Backtracking: seed history_ids with source staging bnpp_id or history from record
                String existingHist = (record != null && record.getHistoryIds() != null) ? record.getHistoryIds() : (String) row.get("history_ids");
                if (existingHist == null) {
                    existingHist = "[" + stagingBnpp + "]";
                } else {
                    existingHist = appendHistoryId(existingHist, stagingBnpp);
                }
                row.put("history_ids", existingHist);

                if (record != null && record.getFileId() != null) {
                    row.put("file_id", record.getFileId());
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
        return BnppIdGenerator.generateId("BNPP");
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
        return applyDeduplicationAndVersioning(chunkPayloads, mappings, jdbcTemplate, false);
    }

    private DatabaseRowPayload applyDeduplicationAndVersioning(List<DatabaseRowPayload> chunkPayloads,
                                                               List<FieldMapping> mappings,
                                                               JdbcTemplate jdbcTemplate,
                                                               boolean preserveVersion) {
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
            Map<String, Integer> keyToIndex = new HashMap<>();

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
                        // Preserve original starting source_id / source_bnpp_id across updates
                        String startingBnpp = existing.sourceId() != null ? existing.sourceId()
                                : (existing.sourceBnppId() != null ? existing.sourceBnppId() : existing.bnppId());
                        if (startingBnpp == null) {
                            startingBnpp = (String) row.get("source_id");
                            if (startingBnpp == null) startingBnpp = (String) row.get("source_bnpp_id");
                            if (startingBnpp == null) startingBnpp = (String) row.get("bnpp_id");
                        }
                        row.put("source_id", startingBnpp);
                        row.put("source_bnpp_id", startingBnpp);

                        // Backtracking: append new historical ID from staging to history_ids
                        String histId = (String) row.get("staging_bnpp_id");
                        if (histId == null) histId = (String) row.get("current_id");
                        if (histId == null) histId = (String) row.get("bnpp_id");
                        if (histId == null) histId = BnppIdGenerator.generateId("BNPP");

                        row.put("current_id", histId);
                        if (existing.bnppId() != null) {
                            row.put("bnpp_id", existing.bnppId());
                        } else {
                            row.put("bnpp_id", histId);
                        }

                        int incomingVer = row.get("version_number") instanceof Number num ? num.intValue() : 1;
                        int nextVersion = preserveVersion
                                ? Math.max(incomingVer, existing.versionNumber() != null ? existing.versionNumber() : 1)
                                : Math.max(incomingVer, (existing.versionNumber() != null ? existing.versionNumber() : 1) + 1);
                        row.put("version_number", nextVersion);
                        row.put("updated_at", new Timestamp(System.currentTimeMillis()));

                        String updatedHistory = appendHistoryId(existing.historyIds(), histId);
                        row.put("history_ids", updatedHistory);

                        String dedupeKey = lookupKey != null ? lookupKey : (String) row.get("bnpp_id");
                        if (dedupeKey != null && keyToIndex.containsKey(dedupeKey)) {
                            writeList.set(keyToIndex.get(dedupeKey), row);
                        } else {
                            if (dedupeKey != null) keyToIndex.put(dedupeKey, writeList.size());
                            writeList.add(row);
                        }

                        if (lookupKey != null) {
                            String activeBnpp = (row.get("bnpp_id") != null) ? (String) row.get("bnpp_id") : existing.bnppId();
                            existingMap.put(lookupKey, new ExistingRecordInfo(activeBnpp, incomingHash, nextVersion, updatedHistory, startingBnpp, histId, startingBnpp));
                        }
                    }
                } else {
                    // Case 3: New record
                    int incomingVer = row.get("version_number") instanceof Number num ? num.intValue() : 1;
                    int finalVer = preserveVersion ? incomingVer : 1;
                    row.put("version_number", finalVer);
                    row.put("created_at", new Timestamp(System.currentTimeMillis()));
                    row.put("updated_at", new Timestamp(System.currentTimeMillis()));

                    String currId = (String) row.get("current_id");
                    if (currId == null) currId = (String) row.get("bnpp_id");
                    if (currId == null) currId = (String) row.get("staging_bnpp_id");
                    if (currId == null) currId = BnppIdGenerator.generateId("BNPP");
                    row.put("current_id", currId);
                    row.put("bnpp_id", currId);

                    String srcId = (String) row.get("source_id");
                    if (srcId == null) srcId = (String) row.get("source_bnpp_id");
                    if (srcId == null) srcId = currId;
                    row.put("source_id", srcId);
                    row.put("source_bnpp_id", srcId);
                    if (row.get("staging_bnpp_id") == null) {
                        row.put("staging_bnpp_id", currId);
                    }
                    String newHistory = appendHistoryId((String) row.get("history_ids"), currId);
                    row.put("history_ids", newHistory);

                    String dedupeKey = lookupKey != null ? lookupKey : currId;
                    if (dedupeKey != null && keyToIndex.containsKey(dedupeKey)) {
                        writeList.set(keyToIndex.get(dedupeKey), row);
                    } else {
                        if (dedupeKey != null) keyToIndex.put(dedupeKey, writeList.size());
                        writeList.add(row);
                    }

                    if (lookupKey != null) {
                        existingMap.put(lookupKey, new ExistingRecordInfo(currId, incomingHash, finalVer, newHistory, srcId, currId, srcId));
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
                val = r.get("bnpp_id");
            }
            if (val != null && !String.valueOf(val).trim().isEmpty()) {
                lookupValues.add(val);
            }
        }
        if (lookupValues.isEmpty()) return map;

        String matchCol = (pkCol != null) ? pkCol : "bnpp_id";
        List<Object> valList = new ArrayList<>(lookupValues);
        int batchSize = 500;

        for (int i = 0; i < valList.size(); i += batchSize) {
            List<Object> subList = valList.subList(i, Math.min(valList.size(), i + batchSize));
            String inClause = String.join(",", Collections.nCopies(subList.size(), "?"));
            String sql = "SELECT * FROM " + tableName + " WHERE " + matchCol + " IN (" + inClause + ")";
            try {
                jdbcTemplate.query(sql, rs -> {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    String key = null;
                    String bid = null;
                    String currId = null;
                    String hash = null;
                    int ver = 1;
                    String hist = null;
                    String srcBid = null;
                    String srcId = null;
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        String cn = md.getColumnName(c);
                        if (cn.equalsIgnoreCase(matchCol)) key = rs.getString(c);
                        else if (cn.equalsIgnoreCase("bnpp_id")) bid = rs.getString(c);
                        else if (cn.equalsIgnoreCase("current_id")) currId = rs.getString(c);
                        else if (cn.equalsIgnoreCase("payload_hash")) hash = rs.getString(c);
                        else if (cn.equalsIgnoreCase("version_number")) ver = rs.getInt(c);
                        else if (cn.equalsIgnoreCase("history_ids")) hist = rs.getString(c);
                        else if (cn.equalsIgnoreCase("source_id")) srcId = rs.getString(c);
                        else if (cn.equalsIgnoreCase("source_bnpp_id")) srcBid = rs.getString(c);
                    }
                    if (currId == null) currId = bid;
                    if (srcId == null) srcId = srcBid;
                    if (srcBid == null) srcBid = srcId;
                    if (key != null) {
                        map.put(key, new ExistingRecordInfo(bid != null ? bid : currId, hash, ver, hist, srcBid, currId, srcId));
                    }
                }, subList.toArray());
            } catch (Exception qEx) {
                try {
                    String quotedSql = "SELECT * FROM \"" + tableName + "\" WHERE \"" + matchCol + "\" IN (" + inClause + ")";
                    jdbcTemplate.query(quotedSql, rs -> {
                        java.sql.ResultSetMetaData md = rs.getMetaData();
                        String key = null;
                        String bid = null;
                        String currId = null;
                        String hash = null;
                        int ver = 1;
                        String hist = null;
                        String srcBid = null;
                        String srcId = null;
                        for (int c = 1; c <= md.getColumnCount(); c++) {
                            String cn = md.getColumnName(c);
                            if (cn.equalsIgnoreCase(matchCol)) key = rs.getString(c);
                            else if (cn.equalsIgnoreCase("bnpp_id")) bid = rs.getString(c);
                            else if (cn.equalsIgnoreCase("current_id")) currId = rs.getString(c);
                            else if (cn.equalsIgnoreCase("payload_hash")) hash = rs.getString(c);
                            else if (cn.equalsIgnoreCase("version_number")) ver = rs.getInt(c);
                            else if (cn.equalsIgnoreCase("history_ids")) hist = rs.getString(c);
                            else if (cn.equalsIgnoreCase("source_id")) srcId = rs.getString(c);
                            else if (cn.equalsIgnoreCase("source_bnpp_id")) srcBid = rs.getString(c);
                        }
                        if (currId == null) currId = bid;
                        if (srcId == null) srcId = srcBid;
                        if (srcBid == null) srcBid = srcId;
                        if (key != null) {
                            map.put(key, new ExistingRecordInfo(bid != null ? bid : currId, hash, ver, hist, srcBid, currId, srcId));
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
    public static Set<String> getOrBuildMappedFields(List<FieldMapping> mappings) {
        if (mappings == null) return Collections.emptySet();
        Set<String> mappedXmlFields = new HashSet<>(mappings.size() * 2);
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
        return mappedXmlFields;
    }

    public void enrichWithSchemaEvolutionAttributes(Map<String, Object> rawMap, List<FieldMapping> mappings) {
        enrichWithSchemaEvolutionAttributes(rawMap, getOrBuildMappedFields(mappings));
    }

    public void enrichWithSchemaEvolutionAttributes(Map<String, Object> rawMap, Set<String> mappedXmlFields) {
        if (rawMap == null || mappedXmlFields == null) return;

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
            try { r.setFileId(rs.getString("file_id")); } catch (Exception ignored) {}
            try { r.setBnppId(rs.getString("bnpp_id")); } catch (Exception ignored) {}
            try { r.setCurrentId(rs.getString("current_id")); } catch (Exception ignored) {}
            try { if (r.getCurrentId() == null) r.setCurrentId(r.getBnppId()); } catch (Exception ignored) {}
            try { r.setSourceId(rs.getString("source_id")); } catch (Exception ignored) {}
            try { r.setSourceBnppId(rs.getString("source_bnpp_id")); } catch (Exception ignored) {}
            try { if (r.getSourceId() == null) r.setSourceId(r.getSourceBnppId()); } catch (Exception ignored) {}
            try { r.setStagingBnppId(rs.getString("staging_bnpp_id")); } catch (Exception ignored) {}
            try { r.setHistoryIds(rs.getString("history_ids")); } catch (Exception ignored) {}
            try { r.setTagName(rs.getString("tag_name")); } catch (Exception ignored) {}
            try {
                if (r.getTagName() == null || r.getTagName().isEmpty()) r.setTagName(rs.getString("entity_type"));
            } catch (Exception ignored) {}
            try { r.setEntityType(rs.getString("entity_type")); } catch (Exception ignored) {}
            try {
                if (r.getEntityType() == null || r.getEntityType().isEmpty()) r.setEntityType(rs.getString("tag_name"));
            } catch (Exception ignored) {}
            try { r.setVersionNumber(rs.getInt("version_number")); } catch (Exception ignored) {}
            try { r.setRecordIdentifier(rs.getString("record_identifier")); } catch (Exception ignored) {}
            try { r.setRecordAction(rs.getString("record_action")); } catch (Exception ignored) {}
            try { r.setRecordDate(rs.getString("record_date")); } catch (Exception ignored) {}
            try { r.setRawPayload(rs.getString("raw_payload")); } catch (Exception ignored) {}
            try { r.setPayloadHash(rs.getString("payload_hash")); } catch (Exception ignored) {}
            try { r.setStatus(rs.getString("status")); } catch (Exception ignored) {}

            try {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                Map<String, Object> colMap = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String cName = md.getColumnName(i);
                    Object val = rs.getObject(i);
                    if (val != null) {
                        colMap.put(cName, val);
                        colMap.put(cName.toLowerCase(), val);
                    }
                }
                r.setCachedMap(colMap);
            } catch (Exception ignored) {}

            return r;
        }, lastSeenId);
    }

    private boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        try {
            jdbcTemplate.execute("SELECT 1 FROM " + tableName + " WHERE 1=0");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasUnnormalizedRecords(JdbcTemplate jdbcTemplate, String tableName, Long jobExecutionId) {
        try {
            if (jobExecutionId != null) {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + tableName + " WHERE job_execution_id = ? AND (status = 'STAGED' OR status = 'RETRY' OR (status IS NOT NULL AND status != 'NORMALIZED' AND status != 'SKIPPED_UNCHANGED' AND status != 'FAILED'))",
                        Integer.class,
                        jobExecutionId
                );
                if (count != null && count > 0) return true;
            }
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + tableName + " WHERE status = 'STAGED' OR status = 'RETRY' OR (status IS NOT NULL AND status != 'NORMALIZED' AND status != 'SKIPPED_UNCHANGED' AND status != 'FAILED')",
                    Integer.class
            );
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasStagedRecordsForJob(JdbcTemplate jdbcTemplate, String tableName, Long jobExecutionId) {
        return hasUnnormalizedRecords(jdbcTemplate, tableName, jobExecutionId);
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
                            if (name != null && (name.toLowerCase().startsWith("gs_") || name.toLowerCase().endsWith("_gs") || name.toLowerCase().endsWith("_flat"))
                                    && !name.equalsIgnoreCase("staging_xml_records")
                                    && !name.equalsIgnoreCase("gs_error_records")
                                    && !name.equalsIgnoreCase("migration_errors")
                                    && !name.contains("__")) {
                                String base = name.toLowerCase().startsWith("gs_") ? name.substring(3) : name.replaceFirst("(?i)_(gs|flat)$", "");
                                if (!XmlSchemaDiscoveryService.isWrapperContainerTag(base, null)) {
                                    flats.add(name.toLowerCase());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            try {
                jdbcTemplate.query("SELECT table_name FROM information_schema.tables WHERE LOWER(table_name) LIKE 'gs_%' OR LOWER(table_name) LIKE '%_gs' OR LOWER(table_name) LIKE '%_flat'", rs -> {
                    String name = rs.getString(1);
                    if (name != null && !name.equalsIgnoreCase("staging_xml_records")
                            && !name.equalsIgnoreCase("gs_error_records")
                            && !name.equalsIgnoreCase("migration_errors")
                            && !name.contains("__")) {
                        String base = name.toLowerCase().startsWith("gs_") ? name.substring(3) : name.replaceFirst("(?i)_(gs|flat)$", "");
                        if (!XmlSchemaDiscoveryService.isWrapperContainerTag(base, null)) {
                            flats.add(name.toLowerCase());
                        }
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
                String updateSql = "UPDATE " + flatTable + " SET status = ? WHERE id = ?";
                jdbcTemplate.batchUpdate(updateSql, ids, ids.size(), (ps, id) -> {
                    ps.setString(1, status);
                    ps.setLong(2, id);
                });
            } catch (Exception ignored) {}
        }
        // Also update any sibling flat tables for this entity (gs_<entity>, <entity>_gs, <entity>_flat)
        List<String> bnppIds = records.stream().map(StagingXmlRecord::getBnppId).filter(Objects::nonNull).toList();
        List<String> recordIds = records.stream().map(StagingXmlRecord::getRecordIdentifier).filter(Objects::nonNull).toList();
        String norm = flatTable.toLowerCase().startsWith("gs_") ? flatTable.substring(3) : flatTable.replaceFirst("(?i)_(gs|flat)$", "");
        for (String sibling : List.of("gs_" + norm, norm + "_gs", norm + "_flat")) {
            if (!sibling.equalsIgnoreCase(flatTable) && tableExists(jdbcTemplate, sibling)) {
                if (!bnppIds.isEmpty()) {
                    try {
                        String sibSql = "UPDATE " + sibling + " SET status = ? WHERE bnpp_id = ?";
                        jdbcTemplate.batchUpdate(sibSql, bnppIds, bnppIds.size(), (ps, bId) -> {
                            ps.setString(1, status);
                            ps.setString(2, bId);
                        });
                    } catch (Exception ignored) {}
                }
                if (!recordIds.isEmpty()) {
                    try {
                        String sibSql = "UPDATE " + sibling + " SET status = ? WHERE record_identifier = ?";
                        jdbcTemplate.batchUpdate(sibSql, recordIds, recordIds.size(), (ps, rId) -> {
                            ps.setString(1, status);
                            ps.setString(2, rId);
                        });
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void updateStagingStatus(JdbcTemplate jdbcTemplate, List<StagingXmlRecord> records, String status, String errorMessage) {
        if (records == null || records.isEmpty()) return;
        List<StagingXmlRecord> withBnpp = records.stream()
                .filter(r -> r.getBnppId() != null && !r.getBnppId().trim().isEmpty())
                .toList();
        if (!withBnpp.isEmpty()) {
            try {
                String updateSql = "UPDATE staging_xml_records SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE bnpp_id = ?";
                jdbcTemplate.batchUpdate(updateSql, withBnpp, withBnpp.size(), (ps, r) -> {
                    ps.setString(1, status);
                    ps.setString(2, errorMessage);
                    ps.setString(3, r.getBnppId());
                });
            } catch (Exception ignored) {}
        }
        List<StagingXmlRecord> withoutBnpp = records.stream()
                .filter(r -> (r.getBnppId() == null || r.getBnppId().trim().isEmpty()) && r.getId() != null)
                .toList();
        if (!withoutBnpp.isEmpty()) {
            try {
                String updateSql = "UPDATE staging_xml_records SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
                jdbcTemplate.batchUpdate(updateSql, withoutBnpp, withoutBnpp.size(), (ps, r) -> {
                    ps.setString(1, status);
                    ps.setString(2, errorMessage);
                    ps.setLong(3, r.getId());
                });
            } catch (Exception ignored) {}
        }
    }

    private static final Set<String> SYSTEM_METADATA_TABLES = Set.of(
            "file_history", "audit_log", "audit_logs", "error_log", "error_logs", "users", "app_users",
            "saved_job_configs", "job_details", "job_checkpoints",
            "schema_registry_versions", "migration_errors", "staging_xml_records",
            "gs_error_records"
    );

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
                            if (!norm.startsWith("gs_") && !norm.endsWith("_flat") && !norm.endsWith("_gs")
                                    && !norm.startsWith("staging_") && !norm.startsWith("batch_")
                                    && !SYSTEM_METADATA_TABLES.contains(norm)) {
                                tables.add(t);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (masterTable != null && !masterTable.trim().isEmpty()) {
                String mNorm = masterTable.trim().toLowerCase();
                String found = null;
                for (String t : tables) {
                    if (t.equalsIgnoreCase(mNorm)) {
                        found = t;
                        break;
                    }
                }
                if (found != null) {
                    tables.remove(found);
                    tables.add(0, found);
                } else {
                    tables.add(0, masterTable.trim());
                }
            }
        }

        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String schema = null;
            try { schema = conn.getSchema(); } catch (Throwable ignored) {}
            for (String tbl : tables) {
                if (tbl == null || tbl.trim().isEmpty()) continue;
                Set<String> candidateNames = new LinkedHashSet<>(List.of(tbl, tbl.toUpperCase(), tbl.toLowerCase()));
                for (String tName : candidateNames) {
                    try (ResultSet rs = meta.getColumns(null, schema, tName, null)) {
                        boolean foundAny = false;
                        while (rs.next()) {
                            String colName = rs.getString("COLUMN_NAME");
                            if (colName != null
                                    && !colName.equalsIgnoreCase("created_at")
                                    && !colName.equalsIgnoreCase("updated_at")
                                    && !colName.equalsIgnoreCase("extra_attributes")
                                    && !colName.equalsIgnoreCase("bnpp_id")
                                    && !colName.equalsIgnoreCase("current_id")
                                    && !colName.equalsIgnoreCase("source_id")
                                    && !colName.equalsIgnoreCase("source_bnpp_id")
                                    && !colName.equalsIgnoreCase("staging_bnpp_id")
                                    && !colName.equalsIgnoreCase("version_number")
                                    && !colName.equalsIgnoreCase("tag_name")
                                    && !colName.equalsIgnoreCase("payload_hash")
                                    && !colName.equalsIgnoreCase("history_ids")
                                    && !colName.equalsIgnoreCase("file_id")) {

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
