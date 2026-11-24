package com.bnpp.service;

import com.bnpp.batch.DynamicXmlItemProcessor;
import com.bnpp.config.TablePrefixConfig;
import com.bnpp.model.*;
import com.bnpp.util.BnppIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class XmlStreamingParserService {

    private static final Logger log = LoggerFactory.getLogger(XmlStreamingParserService.class);

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "year", "month", "day", "user", "order", "group", "table", "select", "from",
            "where", "values", "value", "key", "offset", "limit", "date", "time", "timestamp",
            "primary", "foreign", "check", "column", "constraint", "index", "references"
    );

    private final DynamicDataSourceService dataSourceService;
    private final AuditGovernanceService auditGovernanceService;
    private final SchemaProvisioningService schemaProvisioningService;
    private final ObjectMapper objectMapper;
    private final TablePrefixConfig tablePrefixConfig;

    private final Map<String, AtomicBoolean> stopRequests = new ConcurrentHashMap<>();
    private final Map<String, JobTelemetryResponse> telemetryStore = new ConcurrentHashMap<>();

    public XmlStreamingParserService(DynamicDataSourceService dataSourceService,
                                     AuditGovernanceService auditGovernanceService,
                                     SchemaProvisioningService schemaProvisioningService,
                                     ObjectMapper objectMapper,
                                     @org.springframework.beans.factory.annotation.Autowired(required = false) TablePrefixConfig tablePrefixConfig) {
        this.dataSourceService = dataSourceService;
        this.auditGovernanceService = auditGovernanceService;
        this.schemaProvisioningService = schemaProvisioningService;
        this.objectMapper = objectMapper;
        this.tablePrefixConfig = tablePrefixConfig != null ? tablePrefixConfig : (schemaProvisioningService != null ? schemaProvisioningService.getTablePrefixConfig() : new TablePrefixConfig());
    }

    private String safeCol(String col) {
        if (col == null) return null;
        String lower = col.trim().toLowerCase();
        if (RESERVED_KEYWORDS.contains(lower)) {
            return "\"" + lower + "\"";
        }
        return lower;
    }

    public void requestStop(String jobExecutionId) {
        if (jobExecutionId != null) {
            stopRequests.computeIfAbsent(jobExecutionId, k -> new AtomicBoolean(false)).set(true);
            log.info("Requested STOP for execution {}", jobExecutionId);
        }
    }

    public boolean isStopRequested(String jobExecutionId) {
        if (jobExecutionId == null) return false;
        AtomicBoolean flag = stopRequests.get(jobExecutionId);
        return flag != null && flag.get();
    }

    public void clearStopRequest(String jobExecutionId) {
        if (jobExecutionId != null) {
            stopRequests.remove(jobExecutionId);
        }
    }

    public JobTelemetryResponse getTelemetry(String jobExecutionId) {
        return telemetryStore.get(jobExecutionId);
    }

    public JobTelemetryResponse streamAndIngest(DbConnectionConfig config,
                                               FileHistoryRecord fileRecord,
                                               List<FieldMapping> mappings,
                                               String jobExecutionId,
                                               long startRecordIndex) throws Exception {

        clearStopRequest(jobExecutionId);
        long startTime = System.currentTimeMillis();

        JobTelemetryResponse telemetry = new JobTelemetryResponse();
        telemetry.setJobExecutionId(jobExecutionId);
        telemetry.setStatus("RUNNING");
        telemetryStore.put(jobExecutionId, telemetry);

        // Audit: JOB_STARTED
        AuditLogRecord startAudit = new AuditLogRecord();
        startAudit.setFileId(fileRecord.getFileId());
        startAudit.setJobExecutionId(jobExecutionId);
        startAudit.setEventType("JOB_STARTED");
        startAudit.setEntityName("STAGING_PIPELINE");
        startAudit.setActionDetails("Initiated StAX streaming for file " + fileRecord.getXmlFilename());
        startAudit.setExecutionStatus("RUNNING");
        startAudit.setTriggeredBy(fileRecord.getTriggeredBy());
        auditGovernanceService.logAudit(config, startAudit);

        DataSource ds = dataSourceService.getOrCreateDataSource(config);

        // Identify table definitions and their matching entity XML tag names
        Map<String, EntityDefinition> entityDefs = resolveEntityDefinitions(mappings);

        File xmlFile = resolvePhysicalFile(fileRecord.getIngestionFilePath());
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);

        AtomicLong totalRead = new AtomicLong(0);
        AtomicLong totalWritten = new AtomicLong(0);
        Map<String, AtomicLong> tableCounts = new ConcurrentHashMap<>();
        for (String entity : entityDefs.keySet()) {
            String entityClean = tablePrefixConfig.cleanEntityName(entity);
            tableCounts.put(tablePrefixConfig.getCanonicalTableName(entityClean), new AtomicLong(0));
            tableCounts.put(tablePrefixConfig.getGoldenSourceTableName(entityClean), new AtomicLong(0));
        }

        // Virtual threads executor for concurrent JDBC writes throttled by semaphore
        int maxConcurrency = 10;
        Semaphore dbSemaphore = new Semaphore(maxConcurrency);
        ExecutorService writerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> writeTasks = new ArrayList<>();

        Map<String, List<GoldenSourceRow>> gsBuffers = new ConcurrentHashMap<>();
        List<StagingRecordRow> stagingBuffer = Collections.synchronizedList(new ArrayList<>());
        final int CHUNK_SIZE = 500;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(xmlFile.toPath()), StandardCharsets.UTF_8), 128 * 1024)) {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(reader);

            Deque<ElementFrame> stack = new ArrayDeque<>();
            long recordCounter = 0;
            String lastIdentifier = "";

            while (xmlReader.hasNext()) {
                if (isStopRequested(jobExecutionId)) {
                    log.warn("Job execution {} stopped by user at record index {}", jobExecutionId, recordCounter);
                    telemetry.setStatus("STOPPED");
                    auditGovernanceService.saveCheckpoint(config, new JobCheckpointRecord(jobExecutionId, fileRecord.getFileId(), lastIdentifier, recordCounter, "STOPPED"));
                    break;
                }

                int event = xmlReader.next();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String localName = xmlReader.getLocalName();
                        ElementFrame frame = new ElementFrame(localName);

                        int attrCount = xmlReader.getAttributeCount();
                        for (int i = 0; i < attrCount; i++) {
                            frame.attributes.put(xmlReader.getAttributeLocalName(i), xmlReader.getAttributeValue(i));
                        }
                        stack.push(frame);
                    }

                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (!stack.isEmpty() && !xmlReader.isWhiteSpace()) {
                            ElementFrame top = stack.peek();
                            top.textBuffer.append(xmlReader.getText());
                        }
                    }

                    case XMLStreamConstants.END_ELEMENT -> {
                        String localName = xmlReader.getLocalName();
                        if (!stack.isEmpty()) {
                            ElementFrame finished = stack.pop();
                            String text = finished.textBuffer.toString().trim();
                            if (!stack.isEmpty()) {
                                ElementFrame parent = stack.peek();
                                parent.childTexts.put(localName, text);
                                parent.childFrames.computeIfAbsent(localName, k -> new ArrayList<>()).add(finished);
                            }

                            // Check if this finished element corresponds to any entity definition
                            for (EntityDefinition def : entityDefs.values()) {
                                if (def.isEntityElement(localName, stack)) {
                                    recordCounter++;
                                    if (recordCounter <= startRecordIndex) {
                                        continue; // Resume support: skip already streamed records
                                    }

                                    totalRead.incrementAndGet();
                                    Map<String, Object> rowValues = def.extractValues(finished, stack);
                                    String pkVal = def.extractPrimaryKey(rowValues);
                                    if (pkVal != null && !pkVal.isEmpty()) {
                                        lastIdentifier = pkVal;
                                    }

                                    String stagingBnppId = BnppIdGenerator.generateBnppId();
                                    String gsBnppId = BnppIdGenerator.generateBnppId();

                                    Map<String, Object> rawFragment = new LinkedHashMap<>();
                                    for (Map.Entry<String, String> attr : finished.attributes.entrySet()) {
                                        rawFragment.put("@" + attr.getKey(), attr.getValue());
                                        rawFragment.put(attr.getKey(), attr.getValue());
                                    }
                                    for (Map.Entry<String, String> ct : finished.childTexts.entrySet()) {
                                        rawFragment.put(ct.getKey(), ct.getValue());
                                    }
                                    if (finished.childTexts.isEmpty() && !text.isEmpty()) {
                                        rawFragment.put("value", text);
                                    }
                                    for (Map.Entry<String, Object> rv : rowValues.entrySet()) {
                                        rawFragment.putIfAbsent(rv.getKey(), rv.getValue());
                                    }

                                    String rawPayloadJson;
                                    try {
                                        rawPayloadJson = objectMapper.writeValueAsString(rawFragment);
                                    } catch (Exception e) {
                                        rawPayloadJson = "{}";
                                    }
                                    String payloadHash = computeSha256String(rawPayloadJson);
                                    String nowStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

                                    // Staging Row
                                    StagingRecordRow sRow = new StagingRecordRow(
                                            jobExecutionId, fileRecord.getFileId(), stagingBnppId,
                                            localName, fileRecord.getFileVersionNumber(), def.entityName,
                                            pkVal, "INSERT", nowStr, fileRecord.getFileVersionNumber(),
                                            rawPayloadJson, payloadHash, "STAGED"
                                    );
                                    stagingBuffer.add(sRow);

                                    // Golden Source Row
                                    GoldenSourceRow gsRow = new GoldenSourceRow(
                                            gsBnppId, fileRecord.getFileId(), fileRecord.getFileVersionNumber(),
                                            fileRecord.getVendorId(), jobExecutionId, stagingBnppId,
                                            rawPayloadJson, localName, "element", rowValues
                                    );
                                    gsBuffers.computeIfAbsent(def.entityName, k -> Collections.synchronizedList(new ArrayList<>())).add(gsRow);

                                    String gsTbl = tablePrefixConfig.getGoldenSourceTableName(def.entityName);
                                    AtomicLong tblCount = tableCounts.get(gsTbl);
                                    if (tblCount != null) tblCount.incrementAndGet();

                                    // Periodic flush when buffers hit CHUNK_SIZE
                                    if (stagingBuffer.size() >= CHUNK_SIZE) {
                                        flushBuffers(config, ds, stagingBuffer, gsBuffers, entityDefs,
                                                writerExecutor, dbSemaphore, writeTasks, totalWritten);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Flush remaining buffered records
            flushBuffers(config, ds, stagingBuffer, gsBuffers, entityDefs,
                    writerExecutor, dbSemaphore, writeTasks, totalWritten);

            // Wait for all concurrent database write tasks to finish
            for (Future<?> f : writeTasks) {
                f.get();
            }

            auditGovernanceService.saveCheckpoint(config, new JobCheckpointRecord(
                    jobExecutionId, fileRecord.getFileId(), lastIdentifier, recordCounter,
                    isStopRequested(jobExecutionId) ? "STOPPED" : "STREAMING_COMPLETED"
            ));

        } finally {
            writerExecutor.shutdown();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        long written = totalWritten.get();
        double throughput = (elapsed > 0) ? (written * 1000.0 / elapsed) : written;

        telemetry.setRecordsRead(totalRead.get());
        telemetry.setRecordsWritten(written);
        telemetry.setTotalRecords(totalRead.get());
        telemetry.setThroughput(Math.round(throughput * 100.0) / 100.0);
        telemetry.setReadThroughput(telemetry.getThroughput());
        telemetry.setWriteThroughput(telemetry.getThroughput());
        telemetry.setElapsedTimeMs(elapsed);

        Map<String, Long> finalCounts = new HashMap<>();
        tableCounts.forEach((k, v) -> finalCounts.put(k, v.get()));
        telemetry.setTableCounts(finalCounts);
        telemetry.setTableRowCounts(finalCounts);

        if (!"STOPPED".equals(telemetry.getStatus())) {
            telemetry.setStatus("STAGING_COMPLETED");
            // Audit: STAGING_COMPLETED
            AuditLogRecord stgAudit = new AuditLogRecord();
            stgAudit.setFileId(fileRecord.getFileId());
            stgAudit.setJobExecutionId(jobExecutionId);
            stgAudit.setEventType("STAGING_COMPLETED");
            stgAudit.setEntityName("STAGING_PIPELINE");
            stgAudit.setActionDetails("Staged " + written + " records across Golden Source tables.");
            stgAudit.setRecordsAffected(written);
            stgAudit.setExecutionStatus("STAGING_COMPLETED");
            stgAudit.setTriggeredBy(fileRecord.getTriggeredBy());
            auditGovernanceService.logAudit(config, stgAudit);
        }

        return telemetry;
    }

    private void flushBuffers(DbConnectionConfig config,
                              DataSource ds,
                              List<StagingRecordRow> stagingBuffer,
                              Map<String, List<GoldenSourceRow>> gsBuffers,
                              Map<String, EntityDefinition> entityDefs,
                              ExecutorService executor,
                              Semaphore semaphore,
                              List<Future<?>> tasks,
                              AtomicLong totalWritten) {

        List<StagingRecordRow> stagingSnapshot;
        synchronized (stagingBuffer) {
            if (stagingBuffer.isEmpty()) {
                stagingSnapshot = Collections.emptyList();
            } else {
                stagingSnapshot = new ArrayList<>(stagingBuffer);
                stagingBuffer.clear();
            }
        }

        Map<String, List<GoldenSourceRow>> gsSnapshots = new HashMap<>();
        for (Map.Entry<String, List<GoldenSourceRow>> entry : gsBuffers.entrySet()) {
            List<GoldenSourceRow> list = entry.getValue();
            synchronized (list) {
                if (!list.isEmpty()) {
                    gsSnapshots.put(entry.getKey(), new ArrayList<>(list));
                    list.clear();
                }
            }
        }

        if (stagingSnapshot.isEmpty() && gsSnapshots.isEmpty()) return;

        tasks.add(executor.submit(() -> {
            try {
                semaphore.acquire();
                try (Connection conn = ds.getConnection()) {
                    conn.setAutoCommit(false);

                    // 1. Batch insert staging
                    if (!stagingSnapshot.isEmpty()) {
                        insertStagingBatch(conn, stagingSnapshot);
                    }

                    // 2. Batch insert golden source per entity
                    for (Map.Entry<String, List<GoldenSourceRow>> entry : gsSnapshots.entrySet()) {
                        String entity = entry.getKey();
                        EntityDefinition def = entityDefs.get(entity);
                        if (def != null) {
                            insertGoldenSourceBatch(conn, def, entry.getValue());
                            totalWritten.addAndGet(entry.getValue().size());
                        }
                    }

                    conn.commit();
                } catch (Exception ex) {
                    log.error("Batch write execution error: {}", ex.getMessage(), ex);
                    ErrorLogRecord err = new ErrorLogRecord();
                    err.setJobExecutionId(stagingSnapshot.isEmpty() ? "UNKNOWN" : stagingSnapshot.get(0).jobExecutionId);
                    err.setErrorSeverity("FATAL");
                    err.setErrorType("JDBC_BATCH_FAILURE");
                    err.setErrorMessage(ex.getMessage());
                    auditGovernanceService.logError(config, err);
                    throw ex;
                } finally {
                    semaphore.release();
                }
            } catch (Exception e) {
                log.error("Virtual thread JDBC batch failed: {}", e.getMessage());
            }
        }));
    }

    private void insertStagingBatch(Connection conn, List<StagingRecordRow> rows) throws Exception {
        String sql = """
            INSERT INTO staging_xml_records (
                job_execution_id, file_id, bnpp_id, tag_name, version_number,
                entity_type, record_identifier, record_action, record_date, xml_file_version,
                raw_payload, payload_hash, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (StagingRecordRow r : rows) {
                ps.setString(1, r.jobExecutionId);
                ps.setString(2, r.fileId);
                ps.setString(3, r.bnppId);
                ps.setString(4, r.tagName);
                ps.setInt(5, r.versionNumber);
                ps.setString(6, r.entityType);
                ps.setString(7, r.recordIdentifier);
                ps.setString(8, r.recordAction);
                ps.setString(9, r.recordDate);
                ps.setInt(10, r.xmlFileVersion);
                ps.setString(11, r.rawPayload);
                ps.setString(12, r.payloadHash);
                ps.setString(13, r.status);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertGoldenSourceBatch(Connection conn, EntityDefinition def, List<GoldenSourceRow> rows) throws Exception {
        String tableName = tablePrefixConfig.getGoldenSourceTableName(def.entityName);
        List<FieldMapping> mappings = def.mappings;

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(tableName).append(" (bnpp_id, ");
        for (FieldMapping m : mappings) {
            sql.append(safeCol(m.getTargetColumn())).append(", ");
        }
        sql.append("file_id, file_version, vendor_id, job_execution_id, staging_bnpp_id, raw_payload, tag_name, tag_type, created_at, updated_at) VALUES (");

        sql.append("?, ".repeat(mappings.size() + 1)); // bnpp_id + mapped columns
        sql.append("?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (GoldenSourceRow r : rows) {
                int idx = 1;
                ps.setString(idx++, r.bnppId);

                for (FieldMapping m : mappings) {
                    Object val = r.rowValues.get(m.getTargetColumn().toLowerCase());
                    setParameterValue(ps, idx++, val, m.getTransform());
                }

                ps.setString(idx++, r.fileId);
                ps.setInt(idx++, r.fileVersion);
                ps.setString(idx++, r.vendorId);
                ps.setString(idx++, r.jobExecutionId);
                ps.setString(idx++, r.stagingBnppId);
                ps.setString(idx++, r.rawPayload);
                ps.setString(idx++, r.tagName);
                ps.setString(idx++, r.tagType);

                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void setParameterValue(PreparedStatement ps, int idx, Object val, String transform) throws Exception {
        if (val == null) {
            ps.setObject(idx, null);
            return;
        }
        String str = String.valueOf(val).trim();
        String t = transform != null ? transform.toUpperCase() : "NONE";
        switch (t) {
            case "TO_INT" -> {
                try {
                    ps.setInt(idx, Integer.parseInt(str.replaceAll("[^0-9-]", "")));
                } catch (Exception e) {
                    ps.setObject(idx, null);
                }
            }
            case "TO_LONG" -> {
                try {
                    ps.setLong(idx, Long.parseLong(str.replaceAll("[^0-9-]", "")));
                } catch (Exception e) {
                    ps.setObject(idx, null);
                }
            }
            case "TO_BOOLEAN" -> {
                boolean b = "true".equalsIgnoreCase(str) || "1".equals(str) || "yes".equalsIgnoreCase(str);
                ps.setBoolean(idx, b);
            }
            case "UPPER" -> ps.setString(idx, str.toUpperCase());
            default -> ps.setString(idx, str);
        }
    }

    private Map<String, EntityDefinition> resolveEntityDefinitions(List<FieldMapping> mappings) {
        Map<String, List<FieldMapping>> grouped = new LinkedHashMap<>();
        for (FieldMapping m : mappings) {
            if (m.getTargetTable() == null || m.getTargetTable().trim().isEmpty()) continue;
            grouped.computeIfAbsent(m.getTargetTable().trim(), k -> new ArrayList<>()).add(m);
        }

        Map<String, EntityDefinition> defs = new LinkedHashMap<>();
        for (Map.Entry<String, List<FieldMapping>> entry : grouped.entrySet()) {
            EntityDefinition def = new EntityDefinition(entry.getKey(), entry.getValue());
            defs.put(entry.getKey(), def);
        }
        return defs;
    }

    private File resolvePhysicalFile(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new IllegalArgumentException("XML path is blank.");
        }
        String clean = rawPath.trim().replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");
        File f = new File(clean);
        if (f.exists() && f.isFile()) return f;

        File inUploads = new File("uploads", clean);
        if (inUploads.exists() && inUploads.isFile()) return inUploads;

        File inSample = new File("sample-data", clean);
        if (inSample.exists() && inSample.isFile()) return inSample;

        Path p = Paths.get(clean);
        if (Files.exists(p)) return p.toFile();

        throw new IllegalArgumentException("Cannot find XML file: " + rawPath);
    }

    private String computeSha256String(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            return String.valueOf(input.hashCode());
        }
    }

    private static class ElementFrame {
        String name;
        Map<String, String> attributes = new HashMap<>();
        Map<String, String> childTexts = new HashMap<>();
        Map<String, List<ElementFrame>> childFrames = new HashMap<>();
        StringBuilder textBuffer = new StringBuilder();

        ElementFrame(String name) {
            this.name = name;
        }
    }

    private static class EntityDefinition {
        String entityName;
        List<FieldMapping> mappings;
        String entityElementName;

        EntityDefinition(String entityName, List<FieldMapping> mappings) {
            this.entityName = entityName;
            this.mappings = mappings;
            this.entityElementName = detectEntityElementName(entityName, mappings);
        }

        private String detectEntityElementName(String entityName, List<FieldMapping> mappings) {
            String bestTag = null;
            // 1. Direct match with XML path tag
            for (FieldMapping m : mappings) {
                String path = m.getXmlField();
                if (path == null || DynamicXmlItemProcessor.isAutogenerated(path)) continue;
                String[] parts = path.split("/");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String part = parts[i];
                    if (!part.startsWith("@")) {
                        if (part.equalsIgnoreCase(entityName)) {
                            return part;
                        }
                    }
                }
            }
            // 2. PK field's entity tag
            for (FieldMapping m : mappings) {
                if ("PK".equalsIgnoreCase(m.getConstraintType())) {
                    String path = m.getXmlField();
                    if (path == null || DynamicXmlItemProcessor.isAutogenerated(path)) continue;
                    String[] parts = path.split("/");
                    for (int i = parts.length - 1; i >= 0; i--) {
                        if (!parts[i].startsWith("@")) {
                            return parts[i];
                        }
                    }
                }
            }
            // 3. Any non-attribute tag in mappings
            for (FieldMapping m : mappings) {
                String path = m.getXmlField();
                if (path == null || DynamicXmlItemProcessor.isAutogenerated(path)) continue;
                String[] parts = path.split("/");
                for (int i = parts.length - 1; i >= 0; i--) {
                    if (!parts[i].startsWith("@")) {
                        bestTag = parts[i];
                        break;
                    }
                }
                if (bestTag != null) break;
            }
            return bestTag != null ? bestTag : entityName;
        }

        boolean isEntityElement(String localName, Deque<ElementFrame> stack) {
            return localName.equalsIgnoreCase(entityElementName);
        }

        String extractPrimaryKey(Map<String, Object> values) {
            for (FieldMapping m : mappings) {
                if ("PK".equalsIgnoreCase(m.getConstraintType())) {
                    Object val = values.get(m.getTargetColumn().toLowerCase());
                    if (val != null) return String.valueOf(val);
                }
            }
            return "";
        }

        Map<String, Object> extractValues(ElementFrame element, Deque<ElementFrame> stack) {
            Map<String, Object> values = new LinkedHashMap<>();

            for (FieldMapping m : mappings) {
                String col = m.getTargetColumn().toLowerCase();
                String path = m.getXmlField();
                if (DynamicXmlItemProcessor.isAutogenerated(path)) {
                    values.put(col, DynamicXmlItemProcessor.generateAutogeneratedValue());
                    continue;
                }
                Object val = resolveValue(path, element, stack);
                if (val != null) {
                    values.put(col, applyTransform(val, m.getTransform()));
                }
            }
            return values;
        }

        private Object resolveValue(String xmlPath, ElementFrame element, Deque<ElementFrame> stack) {
            if (xmlPath == null || xmlPath.isEmpty()) return null;
            if (DynamicXmlItemProcessor.isAutogenerated(xmlPath)) {
                return DynamicXmlItemProcessor.generateAutogeneratedValue();
            }
            String[] parts = xmlPath.split("/");
            String lastSegment = parts[parts.length - 1];

            // 1. Attribute on current element
            if (lastSegment.startsWith("@")) {
                String attrName = lastSegment.substring(1);
                // Check if path refers to current element or an ancestor
                if (parts.length >= 2) {
                    String targetElem = parts[parts.length - 2];
                    if (targetElem.equalsIgnoreCase(element.name) && element.attributes.containsKey(attrName)) {
                        return element.attributes.get(attrName);
                    }
                    // Check ancestor stack
                    for (ElementFrame ancestor : stack) {
                        if (ancestor.name.equalsIgnoreCase(targetElem) && ancestor.attributes.containsKey(attrName)) {
                            return ancestor.attributes.get(attrName);
                        }
                    }
                }
                if (element.attributes.containsKey(attrName)) {
                    return element.attributes.get(attrName);
                }
                for (ElementFrame ancestor : stack) {
                    if (ancestor.attributes.containsKey(attrName)) {
                        return ancestor.attributes.get(attrName);
                    }
                }
            }

            // 2. Child text of current element
            if (element.childTexts.containsKey(lastSegment)) {
                return element.childTexts.get(lastSegment);
            }

            // 3. Child text of ancestor
            for (ElementFrame ancestor : stack) {
                if (ancestor.childTexts.containsKey(lastSegment)) {
                    return ancestor.childTexts.get(lastSegment);
                }
            }

            return null;
        }

        private Object applyTransform(Object val, String transform) {
            if (val == null) return null;
            String str = String.valueOf(val).trim();
            String t = transform != null ? transform.toUpperCase() : "NONE";
            return switch (t) {
                case "UPPER" -> str.toUpperCase();
                case "TO_INT" -> {
                    try {
                        yield Integer.parseInt(str.replaceAll("[^0-9-]", ""));
                    } catch (Exception e) {
                        yield null;
                    }
                }
                case "TO_LONG" -> {
                    try {
                        yield Long.parseLong(str.replaceAll("[^0-9-]", ""));
                    } catch (Exception e) {
                        yield null;
                    }
                }
                case "TO_BOOLEAN" -> "true".equalsIgnoreCase(str) || "1".equals(str) || "yes".equalsIgnoreCase(str);
                default -> str;
            };
        }
    }

    private record StagingRecordRow(
            String jobExecutionId, String fileId, String bnppId,
            String tagName, int versionNumber, String entityType,
            String recordIdentifier, String recordAction, String recordDate,
            int xmlFileVersion, String rawPayload, String payloadHash, String status
    ) {}

    private record GoldenSourceRow(
            String bnppId, String fileId, int fileVersion,
            String vendorId, String jobExecutionId, String stagingBnppId,
            String rawPayload, String tagName, String tagType, Map<String, Object> rowValues
    ) {}
}
