package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller;
import com.enterprise.xmldbtool.model.StagingXmlRecord;
import com.fasterxml.jackson.core.type.TypeReference;
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
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    @FunctionalInterface
    public interface StreamingProgressCallback {
        void onProgress(long totalRead, long stagedCount, long skippedUnchanged, Map<String, Long> tableCounts, String currentTag);
    }

    /**
     * Memory-efficient streaming of XML file into flat staging table.
     */
    public StagingSummary streamXmlToStaging(String sourceXmlPath,
                                            String recordRootElement,
                                            Long jobExecutionId,
                                            DataSource targetDataSource,
                                            int chunkSize) throws Exception {
        List<String> roots = (recordRootElement != null && !recordRootElement.trim().isEmpty())
                ? Arrays.stream(recordRootElement.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                : List.of("record");
        return streamXmlToStaging(sourceXmlPath, roots, null, null, jobExecutionId, targetDataSource, chunkSize, null);
    }

    public StagingSummary streamXmlToStaging(String sourceXmlPath,
                                            List<String> recordRootElements,
                                            String xmlVersion,
                                            String schemaVersion,
                                            Long jobExecutionId,
                                            DataSource targetDataSource,
                                            int chunkSize) throws Exception {
        return streamXmlToStaging(sourceXmlPath, recordRootElements, xmlVersion, schemaVersion, jobExecutionId, targetDataSource, chunkSize, null);
    }

    /**
     * Memory-efficient streaming of XML file into flat staging tables with multi-root tag and version support.
     */
    private String detectDocRoot(String sourcePath) {
        if (sourcePath == null || sourcePath.trim().isEmpty()) return null;
        try (BufferedReader reader = openReader(sourcePath)) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
            XMLStreamReader streamReader = factory.createXMLStreamReader(reader);
            while (streamReader.hasNext()) {
                int event = streamReader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    return streamReader.getLocalName();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public StagingSummary streamXmlToStaging(String sourceXmlPath,
                                            List<String> recordRootElements,
                                            String xmlVersion,
                                            String schemaVersion,
                                            Long jobExecutionId,
                                            DataSource targetDataSource,
                                            int chunkSize,
                                            StreamingProgressCallback progressCallback) throws Exception {
        return streamXmlToStaging(sourceXmlPath, recordRootElements, xmlVersion, schemaVersion, jobExecutionId, targetDataSource, chunkSize, 0L, null, progressCallback);
    }

    public StagingSummary streamXmlToStaging(String sourceXmlPath,
                                            List<String> recordRootElements,
                                            String xmlVersion,
                                            String schemaVersion,
                                            Long jobExecutionId,
                                            DataSource targetDataSource,
                                            int chunkSize,
                                            long resumeFromRecordIndex,
                                            java.util.function.BooleanSupplier cancellationChecker,
                                            StreamingProgressCallback progressCallback) throws Exception {
        long startTime = System.currentTimeMillis();
        clearColumnCache();
        String docRoot = detectDocRoot(sourceXmlPath);

        List<String> effectiveRoots = new ArrayList<>();
        if (recordRootElements != null && !recordRootElements.isEmpty()) {
            for (String r : recordRootElements) {
                if (r != null && !r.trim().isEmpty()) {
                    for (String token : r.split("[,;\\s]+")) {
                        String resolved = XmlSchemaDiscoveryService.resolveEntityTag(token);
                        if (resolved != null && !resolved.isEmpty() && !XmlSchemaDiscoveryService.isWrapperContainerTag(resolved, docRoot)) {
                            if (!effectiveRoots.contains(resolved)) {
                                effectiveRoots.add(resolved);
                            }
                        }
                    }
                }
            }
        }
        if (effectiveRoots.isEmpty()) {
            effectiveRoots.add("record");
        }

        Map<String, String> rootTagLookup = new LinkedHashMap<>();
        for (String r : effectiveRoots) {
            String norm = r.trim().toLowerCase();
            rootTagLookup.put(norm, r.trim());
            String singular = DynamicXmlUnmarshaller.singularize(norm);
            if (!singular.isEmpty() && !XmlSchemaDiscoveryService.isWrapperContainerTag(singular, docRoot)) {
                rootTagLookup.put(singular, r.trim());
            }
            if (norm.contains("country")) {
                rootTagLookup.put("countryname", r.trim());
            }
        }
        if (rootTagLookup.isEmpty()) {
            rootTagLookup.put("record", "record");
        }

        int batchSize = chunkSize > 0 ? chunkSize : 1000;

        JdbcTemplate jdbcTemplate = new JdbcTemplate(targetDataSource);
        ensureStagingTableExists(jdbcTemplate);
        ensureCheckpointTableExists(jdbcTemplate);
        ensureErrorTableExists(jdbcTemplate);

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        long totalRead = 0;
        long stagedCount = 0;
        long skippedUnchanged = 0;
        long failedCount = 0;
        Map<String, Long> tableCounts = new ConcurrentHashMap<>();

        List<StagingXmlRecord> buffer = new ArrayList<>(batchSize);
        String detectedDocVersion = xmlVersion;

        // Save initial checkpoint
        saveOrUpdateCheckpoint(jdbcTemplate, jobExecutionId, "stagedXmlPipelineJob_" + jobExecutionId, sourceXmlPath,
                "STAGE_1_STREAMING", stagedCount, 0, null, null, "STREAMING");

        try (BufferedReader reader = openReader(sourceXmlPath)) {
            XMLEventReader xmlReader = factory.createXMLEventReader(reader);

            while (xmlReader.hasNext()) {
                // Check if user requested stop / termination
                if (cancellationChecker != null && cancellationChecker.getAsBoolean()) {
                    log.info("Job #{} termination requested. Cleanly halting StAX streaming at record #{}. Checkpoint saved.",
                            jobExecutionId, totalRead);
                    saveOrUpdateCheckpoint(jdbcTemplate, jobExecutionId, "stagedXmlPipelineJob_" + jobExecutionId, sourceXmlPath,
                            "STAGE_1_STREAMING", stagedCount, 0, null, null, "STOPPED");
                    break;
                }

                XMLEvent event = xmlReader.peek();
                if (event != null && event.isStartElement()) {
                    StartElement startElement = event.asStartElement();
                    String tagName = startElement.getName().getLocalPart();

                    // Detect document root version attribute if not provided
                    if (detectedDocVersion == null && startElement.getAttributeByName(new javax.xml.namespace.QName("date")) != null) {
                        detectedDocVersion = startElement.getAttributeByName(new javax.xml.namespace.QName("date")).getValue();
                    }

                    // Never match docRoot (e.g. canopyapp, PFA) or wrapper containers as an isolated record item
                    if ((docRoot != null && tagName.equalsIgnoreCase(docRoot))
                            || XmlSchemaDiscoveryService.isWrapperContainerTag(tagName, docRoot)) {
                        xmlReader.nextEvent();
                        continue;
                    }

                    String matchedRoot = rootTagLookup.get(tagName.toLowerCase());
                    if (matchedRoot == null) {
                        String sing = DynamicXmlUnmarshaller.singularize(tagName.toLowerCase());
                        matchedRoot = rootTagLookup.get(sing);
                    }
                    if (matchedRoot != null) {
                        totalRead++;

                        // Fast-skip previously processed records when resuming from checkpoint!
                        if (resumeFromRecordIndex > 0 && totalRead <= resumeFromRecordIndex) {
                            fastSkipFragment(xmlReader, tagName);
                            stagedCount++;
                            String normRoot = matchedRoot.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                            String flatName = normRoot.endsWith("_flat") ? normRoot : normRoot + "_flat";
                            tableCounts.merge(flatName, 1L, Long::sum);

                            if (totalRead % 5000 == 0 || totalRead == resumeFromRecordIndex) {
                                log.info("Fast-skipping previously processed records: {} / {}...", totalRead, resumeFromRecordIndex);
                                if (progressCallback != null) {
                                    progressCallback.onProgress(totalRead, stagedCount, skippedUnchanged, new LinkedHashMap<>(tableCounts),
                                            "Fast-skipping to checkpoint (" + totalRead + " / " + resumeFromRecordIndex + ")");
                                }
                            }
                            continue;
                        }

                        try {
                            // Memory-efficient: StAX unmarshals only this isolated fragment
                            Map<String, Object> recordMap = dynamicXmlUnmarshaller.parseFragmentFromEventReader(xmlReader);
                            if (recordMap != null && !recordMap.isEmpty()) {
                                String identifier = extractRecordIdentifier(recordMap);
                                String action = extractValue(recordMap, "@action", "action", "status");
                                String recordDate = extractValue(recordMap, "@date", "date", "record_date");
                                String recXmlVersion = detectedDocVersion != null ? detectedDocVersion : extractValue(recordMap, "@version", "version", "feedVersion", "@date");
                                if (recXmlVersion == null) recXmlVersion = "v1.0";
                                String recSchemaVersion = (schemaVersion != null && !schemaVersion.trim().isEmpty()) ? schemaVersion : "v1.0.0";

                                String jsonPayload = objectMapper.writeValueAsString(recordMap);
                                String payloadHash = computeSha256(jsonPayload);
                                String bnppId = generateBnppId(matchedRoot, identifier, recordMap);
                                int batchVer = extractVersionNumber(recXmlVersion, xmlVersion);

                                StagingXmlRecord record = new StagingXmlRecord(
                                        jobExecutionId,
                                        matchedRoot,
                                        identifier,
                                        action,
                                        recordDate,
                                        jsonPayload,
                                        payloadHash
                                );
                                record.setBnppId(bnppId);
                                record.setTagName(matchedRoot);
                                record.setVersionNumber(batchVer);
                                record.setXmlVersion(recXmlVersion);
                                record.setSchemaVersion(recSchemaVersion);

                                // Delta Detection: check if previous identical hash exists
                                boolean isUnchanged = isRecordUnchanged(jdbcTemplate, matchedRoot, identifier, payloadHash);
                                if (isUnchanged) {
                                    record.setStatus("SKIPPED_UNCHANGED");
                                    skippedUnchanged++;
                                } else {
                                    Integer prevVer = getLatestRecordVersion(jdbcTemplate, matchedRoot, identifier);
                                    if (prevVer != null && prevVer >= batchVer) {
                                        batchVer = prevVer + 1;
                                    }
                                    record.setStatus("STAGED");
                                    stagedCount++;
                                    String normRoot = matchedRoot.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                                    String flatName = normRoot.endsWith("_flat") ? normRoot : normRoot + "_flat";
                                    tableCounts.merge(flatName, 1L, Long::sum);
                                }
                                record.setVersionNumber(batchVer);

                                buffer.add(record);
                                if (buffer.size() >= batchSize) {
                                    flushStagingBatchFaultTolerant(jdbcTemplate, buffer, jobExecutionId);
                                    buffer.clear();
                                    saveOrUpdateCheckpoint(jdbcTemplate, jobExecutionId, "stagedXmlPipelineJob_" + jobExecutionId, sourceXmlPath,
                                            "STAGE_1_STREAMING", stagedCount, 0, identifier, matchedRoot, "STREAMING");
                                    if (progressCallback != null) {
                                        progressCallback.onProgress(totalRead, stagedCount, skippedUnchanged, new LinkedHashMap<>(tableCounts), matchedRoot);
                                    }
                                } else if (progressCallback != null && totalRead % 50 == 0) {
                                    progressCallback.onProgress(totalRead, stagedCount, skippedUnchanged, new LinkedHashMap<>(tableCounts), matchedRoot);
                                }
                            }
                        } catch (Exception ex) {
                            failedCount++;
                            log.error("Failed to unmarshal XML fragment #{} for element '{}': {}. Skipping to next record...", totalRead, matchedRoot, ex.getMessage());
                            recordMigrationError(jdbcTemplate, jobExecutionId, "STAGE_1_UNMARSHAL",
                                    "{\"recordNumber\": " + totalRead + ", \"element\": \"" + matchedRoot + "\", \"error\": \"" + ex.getMessage().replace("\"", "\\\"") + "\"}", ex);
                        }
                        continue;
                    }
                }
                xmlReader.nextEvent();
            }

            if (!buffer.isEmpty()) {
                flushStagingBatchFaultTolerant(jdbcTemplate, buffer, jobExecutionId);
                buffer.clear();
            }
            saveOrUpdateCheckpoint(jdbcTemplate, jobExecutionId, "stagedXmlPipelineJob_" + jobExecutionId, sourceXmlPath,
                    "STAGE_1_COMPLETED", stagedCount, 0, null, null, "STAGE_1_DONE");
            if (progressCallback != null) {
                progressCallback.onProgress(totalRead, stagedCount, skippedUnchanged, new LinkedHashMap<>(tableCounts), "COMPLETED");
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("StAX Staging ingestion completed: totalRead={}, staged={}, skippedUnchanged={}, failed={}, elapsedMs={}",
                totalRead, stagedCount, skippedUnchanged, failedCount, elapsed);

        return new StagingSummary(totalRead, stagedCount, skippedUnchanged, failedCount, elapsed);
    }

    private void fastSkipFragment(XMLEventReader xmlReader, String rootTag) throws Exception {
        int depth = 1;
        while (xmlReader.hasNext() && depth > 0) {
            XMLEvent event = xmlReader.nextEvent();
            if (event.isStartElement()) {
                depth++;
            } else if (event.isEndElement()) {
                depth--;
            }
        }
    }

    private void flushStagingBatchFaultTolerant(JdbcTemplate jdbcTemplate, List<StagingXmlRecord> records, Long jobExecutionId) {
        try {
            flushStagingBatch(jdbcTemplate, records);
        } catch (Exception batchEx) {
            log.warn("Batch flush to staging failed ({}). Retrying row-by-row to isolate and skip failed records...", batchEx.getMessage());
            for (StagingXmlRecord rec : records) {
                try {
                    flushStagingBatch(jdbcTemplate, List.of(rec));
                } catch (Exception singleEx) {
                    rec.setStatus("FAILED");
                    recordMigrationError(jdbcTemplate, jobExecutionId, "STAGE_1_FLAT_WRITE", rec.getRawPayload(), singleEx);
                }
            }
        }
    }

    public void ensureCheckpointTableExists(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) return;
        try {
            String sql = "CREATE TABLE IF NOT EXISTS job_checkpoints (" +
                    "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "job_execution_id BIGINT, " +
                    "job_name VARCHAR(255), " +
                    "source_xml_path VARCHAR(1000), " +
                    "stage VARCHAR(50), " +
                    "records_streamed BIGINT DEFAULT 0, " +
                    "records_normalized BIGINT DEFAULT 0, " +
                    "last_record_identifier VARCHAR(255), " +
                    "last_tag_name VARCHAR(100), " +
                    "status VARCHAR(50), " +
                    "checkpoint_data TEXT, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sql);
        } catch (Exception ex) {
            log.debug("Notice ensuring job_checkpoints table exists: {}", ex.getMessage());
        }
    }

    public void saveOrUpdateCheckpoint(JdbcTemplate jdbcTemplate, Long jobExecutionId, String jobName,
                                       String sourceXmlPath, String stage, long streamed, long normalized,
                                       String lastId, String lastTag, String status) {
        if (jdbcTemplate == null || jobExecutionId == null) return;
        try {
            ensureCheckpointTableExists(jdbcTemplate);
            int updated = jdbcTemplate.update(
                    "UPDATE job_checkpoints SET stage = ?, records_streamed = ?, records_normalized = ?, " +
                            "last_record_identifier = ?, last_tag_name = ?, status = ?, updated_at = CURRENT_TIMESTAMP " +
                            "WHERE job_execution_id = ?",
                    stage, streamed, normalized, lastId, lastTag, status, jobExecutionId
            );
            if (updated == 0) {
                jdbcTemplate.update(
                        "INSERT INTO job_checkpoints (job_execution_id, job_name, source_xml_path, stage, " +
                                "records_streamed, records_normalized, last_record_identifier, last_tag_name, status, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                        jobExecutionId, jobName, sourceXmlPath, stage, streamed, normalized, lastId, lastTag, status
                );
            }
        } catch (Exception ex) {
            log.warn("Notice updating job checkpoint for job {}: {}", jobExecutionId, ex.getMessage());
        }
    }

    public com.enterprise.xmldbtool.model.JobCheckpoint getCheckpointByJobExecutionId(JdbcTemplate jdbcTemplate, Long jobExecutionId) {
        if (jdbcTemplate == null || jobExecutionId == null) return null;
        try {
            ensureCheckpointTableExists(jdbcTemplate);
            List<com.enterprise.xmldbtool.model.JobCheckpoint> list = jdbcTemplate.query(
                    "SELECT id, job_execution_id, job_name, source_xml_path, stage, records_streamed, " +
                            "records_normalized, last_record_identifier, last_tag_name, status, checkpoint_data, updated_at " +
                            "FROM job_checkpoints WHERE job_execution_id = ?",
                    (rs, rowNum) -> {
                        com.enterprise.xmldbtool.model.JobCheckpoint cp = new com.enterprise.xmldbtool.model.JobCheckpoint();
                        cp.setId(rs.getLong("id"));
                        cp.setJobExecutionId(rs.getLong("job_execution_id"));
                        cp.setJobName(rs.getString("job_name"));
                        cp.setSourceXmlPath(rs.getString("source_xml_path"));
                        cp.setStage(rs.getString("stage"));
                        cp.setRecordsStreamed(rs.getLong("records_streamed"));
                        cp.setRecordsNormalized(rs.getLong("records_normalized"));
                        cp.setLastRecordIdentifier(rs.getString("last_record_identifier"));
                        cp.setLastTagName(rs.getString("last_tag_name"));
                        cp.setStatus(rs.getString("status"));
                        cp.setCheckpointData(rs.getString("checkpoint_data"));
                        java.sql.Timestamp ts = rs.getTimestamp("updated_at");
                        if (ts != null) cp.setUpdatedAt(ts.toLocalDateTime());
                        return cp;
                    },
                    jobExecutionId
            );
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception ex) {
            return null;
        }
    }

    public com.enterprise.xmldbtool.model.JobCheckpoint getLatestCheckpoint(JdbcTemplate jdbcTemplate, String sourceXmlPath) {
        if (jdbcTemplate == null || sourceXmlPath == null) return null;
        try {
            ensureCheckpointTableExists(jdbcTemplate);
            List<com.enterprise.xmldbtool.model.JobCheckpoint> list = jdbcTemplate.query(
                    "SELECT id, job_execution_id, job_name, source_xml_path, stage, records_streamed, " +
                            "records_normalized, last_record_identifier, last_tag_name, status, checkpoint_data, updated_at " +
                            "FROM job_checkpoints WHERE source_xml_path = ? ORDER BY id DESC LIMIT 1",
                    (rs, rowNum) -> {
                        com.enterprise.xmldbtool.model.JobCheckpoint cp = new com.enterprise.xmldbtool.model.JobCheckpoint();
                        cp.setId(rs.getLong("id"));
                        cp.setJobExecutionId(rs.getLong("job_execution_id"));
                        cp.setJobName(rs.getString("job_name"));
                        cp.setSourceXmlPath(rs.getString("source_xml_path"));
                        cp.setStage(rs.getString("stage"));
                        cp.setRecordsStreamed(rs.getLong("records_streamed"));
                        cp.setRecordsNormalized(rs.getLong("records_normalized"));
                        cp.setLastRecordIdentifier(rs.getString("last_record_identifier"));
                        cp.setLastTagName(rs.getString("last_tag_name"));
                        cp.setStatus(rs.getString("status"));
                        cp.setCheckpointData(rs.getString("checkpoint_data"));
                        java.sql.Timestamp ts = rs.getTimestamp("updated_at");
                        if (ts != null) cp.setUpdatedAt(ts.toLocalDateTime());
                        return cp;
                    },
                    sourceXmlPath
            );
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception ex) {
            return null;
        }
    }

    public void recordMigrationError(JdbcTemplate jdbcTemplate, Long jobExecutionId, String phase, String recordData, Throwable t) {
        if (jdbcTemplate == null) return;
        try {
            ensureErrorTableExists(jdbcTemplate);
            Throwable root = t != null ? org.springframework.core.NestedExceptionUtils.getMostSpecificCause(t) : null;
            String errorMsg = root != null ? root.getMessage() : (t != null ? t.getMessage() : "Unknown batch exception");
            StringWriter sw = new StringWriter();
            if (t != null) t.printStackTrace(new PrintWriter(sw));
            String stackTrace = sw.toString();
            if (stackTrace.length() > 3000) stackTrace = stackTrace.substring(0, 3000);

            jdbcTemplate.update(
                    "INSERT INTO migration_errors (job_execution_id, step_name, phase, record_data, error_message, stack_trace, failed_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    jobExecutionId, "stagedPipelineJob", phase, recordData != null ? recordData : "{}", errorMsg, stackTrace
            );
        } catch (Exception ex) {
            log.debug("Notice recording migration error: {}", ex.getMessage());
        }
    }

    private void ensureErrorTableExists(JdbcTemplate jdbcTemplate) {
        try {
            String createTableSql = "CREATE TABLE IF NOT EXISTS migration_errors (" +
                    "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "job_execution_id BIGINT, " +
                    "step_name VARCHAR(100), " +
                    "phase VARCHAR(50), " +
                    "record_data TEXT, " +
                    "error_message TEXT, " +
                    "stack_trace TEXT, " +
                    "failed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(createTableSql);
        } catch (Exception ignored) {}
    }

    private void flushStagingBatch(JdbcTemplate jdbcTemplate, List<StagingXmlRecord> records) {
        if (records.isEmpty()) return;

        Set<String> cols = getTableColumns(jdbcTemplate, "staging_xml_records");
        boolean hasBnpp = cols.contains("bnpp_id");

        if (hasBnpp) {
            String insertSql = "INSERT INTO staging_xml_records (" +
                    "job_execution_id, bnpp_id, tag_name, version_number, entity_type, record_identifier, record_action, record_date, xml_version, schema_version, raw_payload, payload_hash, status, created_at, updated_at" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

            jdbcTemplate.batchUpdate(insertSql, records, records.size(), (ps, r) -> {
                if (r.getJobExecutionId() != null) ps.setLong(1, r.getJobExecutionId()); else ps.setNull(1, java.sql.Types.BIGINT);
                ps.setString(2, r.getBnppId());
                ps.setString(3, r.getTagName() != null ? r.getTagName() : r.getEntityType());
                ps.setInt(4, r.getVersionNumber() != null ? r.getVersionNumber() : 1);
                ps.setString(5, r.getEntityType());
                ps.setString(6, r.getRecordIdentifier());
                ps.setString(7, r.getRecordAction());
                ps.setString(8, r.getRecordDate());
                ps.setString(9, r.getXmlVersion());
                ps.setString(10, r.getSchemaVersion());
                ps.setString(11, r.getRawPayload());
                ps.setString(12, r.getPayloadHash());
                ps.setString(13, r.getStatus());
            });
        } else {
            String insertSql = "INSERT INTO staging_xml_records (" +
                    "job_execution_id, entity_type, record_identifier, record_action, record_date, xml_version, schema_version, raw_payload, payload_hash, status, created_at, updated_at" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

            jdbcTemplate.batchUpdate(insertSql, records, records.size(), (ps, r) -> {
                if (r.getJobExecutionId() != null) ps.setLong(1, r.getJobExecutionId()); else ps.setNull(1, java.sql.Types.BIGINT);
                ps.setString(2, r.getEntityType());
                ps.setString(3, r.getRecordIdentifier());
                ps.setString(4, r.getRecordAction());
                ps.setString(5, r.getRecordDate());
                ps.setString(6, r.getXmlVersion());
                ps.setString(7, r.getSchemaVersion());
                ps.setString(8, r.getRawPayload());
                ps.setString(9, r.getPayloadHash());
                ps.setString(10, r.getStatus());
            });
        }

        // Flush into specific root flattened table(s): e.g. <entityType>_flat
        flushToSpecificFlatTables(jdbcTemplate, records);
    }

    public void clearColumnCache() {
        cachedTableColumns.clear();
    }

    private final Map<String, Set<String>> cachedTableColumns = new ConcurrentHashMap<>();

    private Set<String> getTableColumns(JdbcTemplate jdbcTemplate, String tableName) {
        String key = tableName.toLowerCase();
        Set<String> cached = cachedTableColumns.get(key);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        Set<String> cols = new HashSet<>();
        try {
            jdbcTemplate.query("SELECT * FROM " + tableName + " WHERE 1=0", rs -> {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    cols.add(md.getColumnName(i).toLowerCase());
                }
                return null;
            });
        } catch (Exception ignored) {}
        if (!cols.isEmpty()) {
            cachedTableColumns.put(key, cols);
        }
        return cols;
    }

    private void flushToSpecificFlatTables(JdbcTemplate jdbcTemplate, List<StagingXmlRecord> records) {
        Map<String, List<StagingXmlRecord>> byEntity = new HashMap<>();
        for (StagingXmlRecord r : records) {
            if (r.getEntityType() != null) {
                byEntity.computeIfAbsent(r.getEntityType(), k -> new ArrayList<>()).add(r);
            }
        }

        for (Map.Entry<String, List<StagingXmlRecord>> entry : byEntity.entrySet()) {
            String entityType = entry.getKey();
            String norm = entityType.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
            String singular = com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller.singularize(norm);
            String plural = com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller.pluralize(norm);

            // Dynamically discover which flat table exists: singular_flat, norm_flat, or plural_flat
            String flatTable = null;
            Set<String> tableCols = Collections.emptySet();
            for (String candidate : List.of(singular + "_flat", norm + "_flat", plural + "_flat")) {
                Set<String> cols = getTableColumns(jdbcTemplate, candidate);
                if (cols.isEmpty()) cols = getTableColumns(jdbcTemplate, candidate.toUpperCase());
                if (!cols.isEmpty()) {
                    flatTable = candidate;
                    tableCols = cols;
                    break;
                }
            }

            if (tableCols.isEmpty()) {
                continue;
            }
            final Set<String> effectiveCols = tableCols;
            List<StagingXmlRecord> group = entry.getValue();

            try {
                List<String> insertCols = new ArrayList<>(List.of(
                        "job_execution_id", "bnpp_id", "tag_name", "version_number",
                        "record_identifier", "record_action", "record_date",
                        "xml_version", "schema_version", "payload_hash", "status", "raw_payload"
                ));
                insertCols.removeIf(c -> !effectiveCols.contains(c.toLowerCase()));

                List<String> extraCols = new ArrayList<>();
                for (String c : effectiveCols) {
                    if (!insertCols.contains(c) && !c.equalsIgnoreCase("id")
                            && !c.equalsIgnoreCase("created_at") && !c.equalsIgnoreCase("updated_at")
                            && !c.equalsIgnoreCase("extra_attributes") && !c.equalsIgnoreCase("error_message")) {
                        extraCols.add(c);
                    }
                }
                if (tableCols.contains("extra_attributes")) {
                    insertCols.add("extra_attributes");
                }
                insertCols.addAll(extraCols);

                List<StagingXmlRecord> activeRecords = group.stream()
                        .filter(r -> !"SKIPPED_UNCHANGED".equalsIgnoreCase(r.getStatus()))
                        .toList();
                if (activeRecords.isEmpty()) continue;

                // Delta handling: if record_identifier already exists in flat table, replace it with the new version
                if (effectiveCols.contains("record_identifier")) {
                    for (StagingXmlRecord r : activeRecords) {
                        if (r.getRecordIdentifier() != null && !r.getRecordIdentifier().trim().isEmpty()) {
                            try {
                                jdbcTemplate.update("DELETE FROM " + flatTable + " WHERE record_identifier = ?", r.getRecordIdentifier());
                            } catch (Exception ignored) {}
                        }
                    }
                }

                Set<String> reservedKeywords = Set.of(
                        "day", "month", "year", "date", "order", "group", "user", "value",
                        "key", "action", "role", "type", "desc", "asc", "limit", "table", "from", "select", "where"
                );
                List<String> quotedCols = insertCols.stream()
                        .map(c -> reservedKeywords.contains(c.toLowerCase()) ? "\"" + c + "\"" : c)
                        .toList();
                String colList = String.join(", ", quotedCols);
                String placeholders = String.join(", ", Collections.nCopies(insertCols.size(), "?"));
                String sql = "INSERT INTO " + flatTable + " (" + colList + ") VALUES (" + placeholders + ")";

                jdbcTemplate.batchUpdate(sql, activeRecords, activeRecords.size(), (ps, r) -> {
                    Map<String, Object> map = null;
                    try {
                        map = objectMapper.readValue(r.getRawPayload(), new TypeReference<Map<String, Object>>() {});
                    } catch (Exception ignored) {}

                    int idx = 1;
                    for (String col : insertCols) {
                        switch (col.toLowerCase()) {
                            case "job_execution_id" -> {
                                if (r.getJobExecutionId() != null) ps.setLong(idx++, r.getJobExecutionId());
                                else ps.setNull(idx++, java.sql.Types.BIGINT);
                            }
                            case "bnpp_id" -> ps.setString(idx++, r.getBnppId());
                            case "tag_name" -> ps.setString(idx++, r.getTagName() != null ? r.getTagName() : r.getEntityType());
                            case "version_number" -> ps.setInt(idx++, r.getVersionNumber() != null ? r.getVersionNumber() : 1);
                            case "record_identifier" -> ps.setString(idx++, r.getRecordIdentifier());
                            case "record_action" -> ps.setString(idx++, r.getRecordAction());
                            case "record_date" -> ps.setString(idx++, r.getRecordDate());
                            case "xml_version" -> ps.setString(idx++, r.getXmlVersion());
                            case "schema_version" -> ps.setString(idx++, r.getSchemaVersion());
                            case "payload_hash" -> ps.setString(idx++, r.getPayloadHash());
                            case "status" -> {
                                String attrVal = (map != null) ? resolveStageColumnValue(map, "status") : null;
                                ps.setString(idx++, attrVal != null ? attrVal : r.getStatus());
                            }
                            case "raw_payload" -> ps.setString(idx++, r.getRawPayload());
                            case "extra_attributes" -> ps.setString(idx++, extractValue(map, "extra_attributes", "unmapped_attributes"));
                            default -> {
                                String val = (map != null) ? resolveFlatColumnValue(map, col) : null;
                                ps.setString(idx++, val);
                            }
                        }
                    }
                });
            } catch (Exception ex) {
                log.error("Error flushing to specific flat table '{}': {}", flatTable, ex.getMessage(), ex);
            }
        }
    }

    private void flushToSpecificStageTables(JdbcTemplate jdbcTemplate, List<StagingXmlRecord> records) {
        Map<String, List<StagingXmlRecord>> byEntity = new HashMap<>();
        for (StagingXmlRecord r : records) {
            if (r.getEntityType() != null) {
                byEntity.computeIfAbsent(r.getEntityType(), k -> new ArrayList<>()).add(r);
            }
        }

        for (Map.Entry<String, List<StagingXmlRecord>> entry : byEntity.entrySet()) {
            String entityType = entry.getKey();
            String norm = entityType.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
            String singular = DynamicXmlUnmarshaller.singularize(norm);
            String plural = DynamicXmlUnmarshaller.pluralize(norm);

            String stageTable = null;
            Set<String> tableCols = Collections.emptySet();
            for (String candidate : List.of(singular + "_stage", norm + "_stage", plural + "_stage")) {
                Set<String> cols = getTableColumns(jdbcTemplate, candidate);
                if (cols.isEmpty()) cols = getTableColumns(jdbcTemplate, candidate.toUpperCase());
                if (!cols.isEmpty()) {
                    stageTable = candidate;
                    tableCols = cols;
                    break;
                }
            }

            if (stageTable == null || tableCols.isEmpty()) {
                continue;
            }

            List<StagingXmlRecord> group = entry.getValue();

            try {
                final Set<String> effectiveCols = tableCols;

                List<String> insertCols = new ArrayList<>(List.of(
                        "job_execution_id", "record_identifier", "record_action", "record_date",
                        "xml_version", "schema_version", "payload_hash", "status", "raw_payload"
                ));
                insertCols.removeIf(c -> !effectiveCols.contains(c.toLowerCase()));

                List<String> extraCols = new ArrayList<>();
                for (String c : effectiveCols) {
                    if (!insertCols.contains(c) && !c.equalsIgnoreCase("id")
                            && !c.equalsIgnoreCase("created_at") && !c.equalsIgnoreCase("updated_at")
                            && !c.equalsIgnoreCase("extra_attributes") && !c.equalsIgnoreCase("error_message")) {
                        extraCols.add(c);
                    }
                }
                if (tableCols.contains("extra_attributes")) {
                    insertCols.add("extra_attributes");
                }
                insertCols.addAll(extraCols);

                Set<String> reservedKeywords = Set.of(
                        "day", "month", "year", "date", "order", "group", "user", "value",
                        "key", "action", "role", "type", "desc", "asc", "limit", "table", "from", "select", "where"
                );
                List<String> quotedCols = insertCols.stream()
                        .map(c -> reservedKeywords.contains(c.toLowerCase()) ? "\"" + c + "\"" : c)
                        .toList();
                String colList = String.join(", ", quotedCols);
                String placeholders = String.join(", ", Collections.nCopies(insertCols.size(), "?"));
                String sql = "INSERT INTO " + stageTable + " (" + colList + ") VALUES (" + placeholders + ")";

                jdbcTemplate.batchUpdate(sql, group, group.size(), (ps, r) -> {
                    Map<String, Object> map = null;
                    try {
                        map = objectMapper.readValue(r.getRawPayload(), new TypeReference<Map<String, Object>>() {});
                    } catch (Exception ignored) {}

                    int idx = 1;
                    for (String col : insertCols) {
                        switch (col.toLowerCase()) {
                            case "job_execution_id" -> {
                                if (r.getJobExecutionId() != null) ps.setLong(idx++, r.getJobExecutionId());
                                else ps.setNull(idx++, java.sql.Types.BIGINT);
                            }
                            case "record_identifier" -> ps.setString(idx++, r.getRecordIdentifier());
                            case "record_action" -> ps.setString(idx++, r.getRecordAction());
                            case "record_date" -> ps.setString(idx++, r.getRecordDate());
                            case "xml_version" -> ps.setString(idx++, r.getXmlVersion());
                            case "schema_version" -> ps.setString(idx++, r.getSchemaVersion());
                            case "payload_hash" -> ps.setString(idx++, r.getPayloadHash());
                            case "status" -> {
                                String attrVal = (map != null) ? resolveStageColumnValue(map, "status") : null;
                                ps.setString(idx++, attrVal != null ? attrVal : r.getStatus());
                            }
                            case "raw_payload" -> ps.setString(idx++, r.getRawPayload());
                            case "extra_attributes" -> ps.setString(idx++, extractValue(map, "extra_attributes", "unmapped_attributes"));
                            default -> {
                                String val = (map != null) ? resolveStageColumnValue(map, col) : null;
                                ps.setString(idx++, val);
                            }
                        }
                    }
                });
            } catch (Exception ex) {
                log.error("Error flushing to specific stage table '{}': {}", stageTable, ex.getMessage(), ex);
            }
        }
    }

    private String resolveStageColumnValue(Map<String, Object> map, String colName) {
        if (map == null || colName == null) return null;
        String lowerCol = colName.toLowerCase();
        String singular = DynamicXmlUnmarshaller.singularize(lowerCol);

        // 1. Check if there are captured sub-tag entities (e.g. _entities_addresses, _entities_address)
        Object entities = map.get("_entities_" + lowerCol);
        if (entities == null && !singular.equals(lowerCol)) {
            entities = map.get("_entities_" + singular);
        }
        if (entities == null) {
            entities = map.get("_subtag_" + lowerCol);
        }
        if (entities == null && !singular.equals(lowerCol)) {
            entities = map.get("_subtag_" + singular);
        }
        if (entities != null) {
            try {
                return objectMapper.writeValueAsString(entities);
            } catch (Exception ignored) {}
        }

        // 2. Check if direct map value is complex structure (List or Map)
        Object val = findMapValue(map, colName);
        if (val instanceof List<?> || val instanceof Map<?, ?>) {
            try {
                return objectMapper.writeValueAsString(val);
            } catch (Exception ignored) {
                return String.valueOf(val);
            }
        }
        return val != null ? String.valueOf(val) : null;
    }

    private String resolveFlatColumnValue(Map<String, Object> map, String colName) {
        if (map == null || colName == null) return null;
        Object val = findMapValue(map, colName);
        if (val == null) {
            val = map.get("_subtag_xml_" + colName.toLowerCase());
        }
        if (val == null) return null;
        if (val instanceof String s) return s;
        if (val instanceof List<?> || val instanceof Map<?, ?>) {
            try {
                return objectMapper.writeValueAsString(val);
            } catch (Exception ignored) {
                return String.valueOf(val);
            }
        }
        return String.valueOf(val);
    }

    private Object findMapValue(Map<String, Object> map, String colName) {
        if (map == null || colName == null) return null;
        String lowerCol = colName.toLowerCase();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String k = entry.getKey().toLowerCase();
            if (k.equals(lowerCol) || k.equals("@" + lowerCol)) {
                return entry.getValue();
            }
            if (k.endsWith("/" + lowerCol) || k.endsWith("/@" + lowerCol)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Integer getLatestRecordVersion(JdbcTemplate jdbcTemplate, String entityType, String recordIdentifier) {
        if (recordIdentifier == null || recordIdentifier.trim().isEmpty()) {
            return null;
        }
        try {
            String sql = "SELECT MAX(version_number) FROM staging_xml_records WHERE entity_type = ? AND record_identifier = ?";
            Integer maxVer = jdbcTemplate.queryForObject(sql, Integer.class, entityType, recordIdentifier);
            return maxVer;
        } catch (Exception ignored) {
            return null;
        }
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
        if (recordMap == null || recordMap.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        for (String key : List.of("@id", "id", "@code", "code", "identifier", "sku", "uuid")) {
            Object val = recordMap.get(key);
            if (val != null && !String.valueOf(val).trim().isEmpty()) {
                return String.valueOf(val).trim();
            }
        }
        for (Map.Entry<String, Object> entry : recordMap.entrySet()) {
            String k = entry.getKey().toLowerCase();
            if (k.endsWith("/@id") || k.endsWith("/id") || k.endsWith("_id") || k.endsWith("/@code") || k.endsWith("/code")) {
                Object val = entry.getValue();
                if (val != null && !String.valueOf(val).trim().isEmpty() && !(val instanceof Map) && !(val instanceof List)) {
                    return String.valueOf(val).trim();
                }
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

    public static String generateBnppId(String entityType, String recordIdentifier, Map<String, Object> map) {
        return UUID.randomUUID().toString();
    }

    public static int extractVersionNumber(String... candidates) {
        if (candidates != null) {
            for (String c : candidates) {
                if (c == null || c.trim().isEmpty() || c.equalsIgnoreCase("auto")) continue;
                String s = c.trim().replaceAll("(?i)^v", "").trim();
                if (s.matches("^\\d+$")) {
                    try {
                        return Integer.parseInt(s);
                    } catch (Exception ignored) {}
                }
                if (s.matches("^\\d+\\.\\d+.*")) {
                    try {
                        String major = s.substring(0, s.indexOf("."));
                        return Integer.parseInt(major);
                    } catch (Exception ignored) {}
                }
            }
        }
        return 1;
    }

    private void ensureStagingTableExists(JdbcTemplate jdbcTemplate) {
        try {
            String sqlStaging = "CREATE TABLE IF NOT EXISTS staging_xml_records (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "job_execution_id BIGINT, " +
                    "bnpp_id VARCHAR(64), " +
                    "tag_name VARCHAR(100), " +
                    "version_number INT DEFAULT 1, " +
                    "entity_type VARCHAR(50) NOT NULL, " +
                    "record_identifier VARCHAR(100), " +
                    "record_action VARCHAR(50), " +
                    "record_date VARCHAR(50), " +
                    "xml_version VARCHAR(50), " +
                    "schema_version VARCHAR(50), " +
                    "raw_payload TEXT NOT NULL, " +
                    "payload_hash VARCHAR(64) NOT NULL, " +
                    "status VARCHAR(30) DEFAULT 'STAGED', " +
                    "error_message TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sqlStaging);
            try {
                jdbcTemplate.execute("ALTER TABLE staging_xml_records ADD COLUMN IF NOT EXISTS bnpp_id VARCHAR(64)");
                jdbcTemplate.execute("ALTER TABLE staging_xml_records ADD COLUMN IF NOT EXISTS tag_name VARCHAR(100)");
                jdbcTemplate.execute("ALTER TABLE staging_xml_records ADD COLUMN IF NOT EXISTS version_number INT DEFAULT 1");
                jdbcTemplate.execute("ALTER TABLE staging_xml_records ADD COLUMN IF NOT EXISTS xml_version VARCHAR(50)");
                jdbcTemplate.execute("ALTER TABLE staging_xml_records ADD COLUMN IF NOT EXISTS schema_version VARCHAR(50)");
                jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_staging_bnpp ON staging_xml_records(bnpp_id)");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_staging_hash ON staging_xml_records(entity_type, record_identifier, payload_hash)");
            } catch (Exception ignored) {}
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

