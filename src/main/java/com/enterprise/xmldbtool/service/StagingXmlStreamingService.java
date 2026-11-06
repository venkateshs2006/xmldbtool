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
        return streamXmlToStaging(sourceXmlPath, roots, null, null, jobExecutionId, targetDataSource, chunkSize);
    }

    /**
     * Memory-efficient streaming of XML file into flat staging tables with multi-root tag and version support.
     */
    public StagingSummary streamXmlToStaging(String sourceXmlPath,
                                            List<String> recordRootElements,
                                            String xmlVersion,
                                            String schemaVersion,
                                            Long jobExecutionId,
                                            DataSource targetDataSource,
                                            int chunkSize) throws Exception {
        long startTime = System.currentTimeMillis();
        clearColumnCache();
        List<String> effectiveRoots = (recordRootElements != null && !recordRootElements.isEmpty())
                ? recordRootElements
                : List.of("record");

        Map<String, String> rootTagLookup = new LinkedHashMap<>();
        for (String r : effectiveRoots) {
            if (r != null && !r.trim().isEmpty()) {
                rootTagLookup.put(r.trim().toLowerCase(), r.trim());
            }
        }
        if (rootTagLookup.isEmpty()) {
            rootTagLookup.put("record", "record");
        }

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
        String detectedDocVersion = xmlVersion;

        try (BufferedReader reader = openReader(sourceXmlPath)) {
            XMLEventReader xmlReader = factory.createXMLEventReader(reader);

            while (xmlReader.hasNext()) {
                XMLEvent event = xmlReader.peek();
                if (event != null && event.isStartElement()) {
                    StartElement startElement = event.asStartElement();
                    String tagName = startElement.getName().getLocalPart();

                    // Detect document root version attribute if not provided
                    if (detectedDocVersion == null && startElement.getAttributeByName(new javax.xml.namespace.QName("date")) != null) {
                        detectedDocVersion = startElement.getAttributeByName(new javax.xml.namespace.QName("date")).getValue();
                    }

                    String matchedRoot = rootTagLookup.get(tagName.toLowerCase());
                    if (matchedRoot != null) {
                        totalRead++;
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

                                StagingXmlRecord record = new StagingXmlRecord(
                                        jobExecutionId,
                                        matchedRoot,
                                        identifier,
                                        action,
                                        recordDate,
                                        jsonPayload,
                                        payloadHash
                                );
                                record.setXmlVersion(recXmlVersion);
                                record.setSchemaVersion(recSchemaVersion);

                                // Delta Detection: check if previous identical hash exists
                                boolean isUnchanged = isRecordUnchanged(jdbcTemplate, matchedRoot, identifier, payloadHash);
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
                            log.error("Failed to unmarshal XML fragment #{} for element '{}': {}", totalRead, matchedRoot, ex.getMessage());
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

        // Flush into specific root staging table(s): e.g. <entityType>_stage
        flushToSpecificStageTables(jdbcTemplate, records);

        // Also flush into specific root flattened table(s): e.g. <entityType>_flat
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
            String flatTable = entityType.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase() + "_flat";
            List<StagingXmlRecord> group = entry.getValue();

            try {
                Set<String> tableCols = getTableColumns(jdbcTemplate, flatTable);
                if (tableCols.isEmpty()) {
                    tableCols = getTableColumns(jdbcTemplate, flatTable.toUpperCase());
                }
                if (tableCols.isEmpty()) {
                    continue;
                }
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
                String sql = "INSERT INTO " + flatTable + " (" + colList + ") VALUES (" + placeholders + ")";

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
                                Object val = (map != null) ? findMapValue(map, col) : null;
                                ps.setString(idx++, val != null ? String.valueOf(val) : null);
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
            String stageTable = entityType.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase() + "_stage";
            List<StagingXmlRecord> group = entry.getValue();

            try {
                Set<String> tableCols = getTableColumns(jdbcTemplate, stageTable);
                if (tableCols.isEmpty()) {
                    tableCols = getTableColumns(jdbcTemplate, stageTable.toUpperCase());
                }
                if (tableCols.isEmpty()) {
                    continue;
                }
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

    private void ensureStagingTableExists(JdbcTemplate jdbcTemplate) {
        try {
            String sqlStaging = "CREATE TABLE IF NOT EXISTS staging_xml_records (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "job_execution_id BIGINT, " +
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
                jdbcTemplate.execute("ALTER TABLE staging_xml_records ADD COLUMN IF NOT EXISTS xml_version VARCHAR(50)");
                jdbcTemplate.execute("ALTER TABLE staging_xml_records ADD COLUMN IF NOT EXISTS schema_version VARCHAR(50)");
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

