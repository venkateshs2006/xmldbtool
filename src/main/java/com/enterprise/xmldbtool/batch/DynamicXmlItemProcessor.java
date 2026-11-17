package com.enterprise.xmldbtool.batch;

import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.FieldMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Dynamic ItemProcessor supporting One-to-Many XML-to-Database table mappings.
 * Transforms an unmarshalled XML Map<String, Object> into a DatabaseRowPayload
 * containing records routed to their respective target database tables,
 * supporting multiple rows per table for repeating collection elements.
 */
public class DynamicXmlItemProcessor implements ItemProcessor<Map<String, Object>, DatabaseRowPayload>, org.springframework.batch.core.StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(DynamicXmlItemProcessor.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper SHARED_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final XMLInputFactory SHARED_XML_FACTORY;
    static {
        SHARED_XML_FACTORY = XMLInputFactory.newInstance();
        SHARED_XML_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        SHARED_XML_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
    }

    private final String defaultTargetTable;
    private final List<FieldMapping> mappings;
    private final Map<String, Object> rootAttributes;
    private final List<String> tableOrder;

    private final java.util.concurrent.atomic.AtomicLong totalRowsRead = new java.util.concurrent.atomic.AtomicLong(0);
    private volatile org.springframework.batch.core.StepExecution stepExecution;

    @Override
    public void beforeStep(org.springframework.batch.core.StepExecution stepExecution) {
        this.stepExecution = stepExecution;
        this.totalRowsRead.set(0);
        if (stepExecution != null) {
            stepExecution.getExecutionContext().putLong("actualRowsRead", 0L);
        }
    }

    @Override
    public org.springframework.batch.core.ExitStatus afterStep(org.springframework.batch.core.StepExecution stepExecution) {
        if (stepExecution != null) {
            stepExecution.getExecutionContext().putLong("actualRowsRead", totalRowsRead.get());
        }
        return null;
    }

    public long getTotalRowsRead() {
        return totalRowsRead.get();
    }

    public DynamicXmlItemProcessor(String defaultTargetTable, List<FieldMapping> mappings) {
        this(defaultTargetTable, mappings, Collections.emptyMap(), Collections.emptyList());
    }

    public DynamicXmlItemProcessor(String defaultTargetTable, List<FieldMapping> mappings, Map<String, Object> rootAttributes) {
        this(defaultTargetTable, mappings, rootAttributes, Collections.emptyList());
    }

    public DynamicXmlItemProcessor(String defaultTargetTable, List<FieldMapping> mappings, Map<String, Object> rootAttributes, List<String> tableOrder) {
        this.defaultTargetTable = defaultTargetTable;
        this.mappings = mappings;
        this.rootAttributes = rootAttributes != null ? rootAttributes : Collections.emptyMap();
        this.tableOrder = (tableOrder != null && !tableOrder.isEmpty())
                ? new ArrayList<>(tableOrder)
                : DynamicJdbcBatchWriter.determineTableOrder(null, mappings, defaultTargetTable);
    }

    @Override
    public DatabaseRowPayload process(Map<String, Object> xmlRecord) throws Exception {
        if (xmlRecord == null || xmlRecord.isEmpty() || mappings == null || mappings.isEmpty()) {
            return null;
        }

        // Merge document-level root attributes (e.g. catalog/@exportId) into record
        if (rootAttributes != null && !rootAttributes.isEmpty()) {
            for (Map.Entry<String, Object> entry : rootAttributes.entrySet()) {
                xmlRecord.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        // Group mappings by target table
        Map<String, List<FieldMapping>> tableMappings = new LinkedHashMap<>();
        for (FieldMapping m : mappings) {
            String rowTargetTable = m.getTargetTable() != null && !m.getTargetTable().trim().isEmpty()
                    ? m.getTargetTable().trim() : this.defaultTargetTable;
            if (rowTargetTable != null && !rowTargetTable.trim().isEmpty()) {
                tableMappings.computeIfAbsent(rowTargetTable, k -> new ArrayList<>()).add(m);
            }
        }

        // Determine table sequence: ordered according to tableOrder, followed by any remaining mapped tables
        List<String> targetTables = new ArrayList<>();
        if (tableOrder != null) {
            for (String tbl : tableOrder) {
                if (tableMappings.containsKey(tbl) && !targetTables.contains(tbl)) {
                    targetTables.add(tbl);
                }
            }
        }
        for (String tbl : tableMappings.keySet()) {
            if (!targetTables.contains(tbl)) {
                targetTables.add(tbl);
            }
        }

        Map<String, List<Map<String, Object>>> multiTableRecords = new LinkedHashMap<>();
        String entityTag = (String) xmlRecord.get("_entity_tag");
        if (entityTag == null) entityTag = (String) xmlRecord.get("_tag_name");
        if (entityTag == null) entityTag = (String) xmlRecord.get("tag_name");
        if (entityTag == null) entityTag = (String) xmlRecord.get("entity_type");
        boolean hasMultiEntities = xmlRecord.keySet().stream().anyMatch(k -> k.startsWith("_entities_"));
        if (entityTag == null && !hasMultiEntities) {
            entityTag = this.defaultTargetTable;
        }

        for (String tableName : targetTables) {
            List<FieldMapping> tblMappings = tableMappings.get(tableName);
            if (tblMappings == null || tblMappings.isEmpty()) continue;

            if (entityTag != null && !entityTag.isEmpty()) {
                String normEntity = entityTag.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
                String strippedEntity = normEntity.startsWith("gs") ? normEntity.substring(2) : normEntity;
                String singEntity = DynamicXmlUnmarshaller.singularize(strippedEntity);
                String plurEntity = DynamicXmlUnmarshaller.pluralize(strippedEntity);
                String baseEnt = strippedEntity.replaceAll("(?i)lists?$", "");
                String singBase = DynamicXmlUnmarshaller.singularize(baseEnt);

                String normTbl = tableName.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
                String strippedTbl = normTbl.startsWith("gs") ? normTbl.substring(2) : normTbl;
                String singTbl = DynamicXmlUnmarshaller.singularize(strippedTbl);
                String baseTbl = strippedTbl.replaceAll("(?i)lists?$", "");
                String singBaseTbl = DynamicXmlUnmarshaller.singularize(baseTbl);

                // Guard against cross-entity contamination
                if (strippedTbl.startsWith("person") && !strippedEntity.startsWith("person")) {
                    continue; // Child table of Person belongs exclusively to Person
                }
                if (strippedTbl.startsWith("country") && !strippedEntity.startsWith("country")) {
                    continue;
                }
                if (strippedTbl.startsWith("country") && strippedEntity.startsWith("country")) {
                    if (strippedEntity.contains("list") != strippedTbl.contains("list")) {
                        continue;
                    }
                }
                if (strippedTbl.startsWith("occupation") && !strippedEntity.startsWith("occupation")) {
                    continue;
                }
                if (strippedTbl.startsWith("relationship") && !strippedEntity.startsWith("relation")) {
                    continue;
                }

                boolean tableRelevant = strippedTbl.equals(strippedEntity)
                        || strippedTbl.equals(singEntity)
                        || strippedTbl.equals(plurEntity)
                        || singTbl.equals(strippedEntity)
                        || singTbl.equals(singEntity)
                        || strippedTbl.equals(baseEnt)
                        || strippedTbl.equals(singBase)
                        || baseTbl.equals(baseEnt)
                        || singBaseTbl.equals(singBase)
                        || (strippedTbl.startsWith(strippedEntity) && !strippedTbl.equals(strippedEntity))
                        || (singTbl.startsWith(singEntity) && !singTbl.equals(singEntity));

                if (!tableRelevant) {
                    tableRelevant = tblMappings.stream().anyMatch(m -> {
                        String xf = m.getXmlField();
                        if (xf != null) {
                            String clean = xf.replace("@", "").toLowerCase().replaceAll("[^a-zA-Z0-9/]", "");
                            if (clean.contains("/person/") && !strippedEntity.equals("person")) {
                                return false;
                            }
                            for (String part : clean.split("/")) {
                                if (part.equals(strippedEntity) || part.equals(singEntity) || part.equals(plurEntity)
                                        || part.equals(baseEnt) || part.equals(singBase)
                                        || xmlRecord.containsKey("_entities_" + part)
                                        || xmlRecord.containsKey("_subtag_" + part)
                                        || xmlRecord.containsKey("_subtag_xml_" + part)) {
                                    return true;
                                }
                            }
                        }
                        String tt = m.getTargetTable();
                        if (tt != null) {
                            String normT = tt.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
                            String strippedT = normT.startsWith("gs") ? normT.substring(2) : normT;
                            String singT = DynamicXmlUnmarshaller.singularize(strippedT);
                            String baseT = strippedT.replaceAll("(?i)lists?$", "");
                            String singBaseT = DynamicXmlUnmarshaller.singularize(baseT);

                            if (strippedT.equals(strippedEntity) || strippedT.equals(singEntity) || strippedT.equals(plurEntity)
                                    || singT.equals(strippedEntity) || singT.equals(singEntity)
                                    || strippedT.equals(baseEnt) || strippedT.equals(singBase)
                                    || baseT.equals(baseEnt) || singBaseT.equals(singBase)
                                    || (strippedT.startsWith(strippedEntity) && !strippedT.equals(strippedEntity))) {
                                return true;
                            }
                        }
                        return false;
                    });
                }

                if (!tableRelevant) {
                    continue; // Skip tables that are completely irrelevant to this XML entity fragment
                }
            }

            String pkCol = null;
            for (FieldMapping m : tblMappings) {
                if ("PK".equalsIgnoreCase(m.getConstraintType()) && m.getTargetColumn() != null) {
                    pkCol = m.getTargetColumn().trim();
                    break;
                }
            }
            if (pkCol == null) {
                for (FieldMapping m : tblMappings) {
                    if ("id".equalsIgnoreCase(m.getTargetColumn())) {
                        pkCol = m.getTargetColumn().trim();
                        break;
                    }
                }
            }

            // Find if unmarshaller captured a repeating entity collection for this table
            List<Map<String, Object>> entityList = findEntityRecordsForTable(tableName, tblMappings, xmlRecord);

            List<Map<String, Object>> validRows = new ArrayList<>();

            if (entityList != null && !entityList.isEmpty()) {
                // Multi-record mapping: process each entity in the collection
                for (Map<String, Object> entityRecord : entityList) {
                    Map<String, Object> rowMap = new LinkedHashMap<>();
                    for (FieldMapping mapping : tblMappings) {
                        String xmlField = mapping.getXmlField();
                        String targetCol = mapping.getTargetColumn();
                        if (xmlField != null && targetCol != null && !targetCol.trim().isEmpty()) {
                            Object rawValue = resolveXmlValue(entityRecord, xmlField, targetCol);
                            if (rawValue == null) {
                                rawValue = resolveXmlValue(xmlRecord, xmlField, targetCol);
                            }
                            Object transformedValue = applyTransform(rawValue, mapping.getTransform());
                            rowMap.put(targetCol, transformedValue);
                        }
                    }

                    // Enforce primary-foreign key relationship: link child row to master entity ID if missing
                    if (!tableName.equalsIgnoreCase(this.defaultTargetTable)) {
                        ensureReferenceId(rowMap, tableName, xmlRecord, tblMappings);
                    }

                    // Ensure canonical table data is in clean data format (no xml or no json)
                    cleanCanonicalRow(rowMap);

                    // Ensure enterprise traceability & tracking metadata (bnpp_id, source_bnpp_id, staging_bnpp_id, history_ids)
                    enrichRowEnterpriseIdentity(rowMap, tableName, xmlRecord);

                    if (isValidRow(rowMap, pkCol)) {
                        validRows.add(rowMap);
                    }
                }
            } else {
                // Single-record mapping: process directly against top-level xmlRecord
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (FieldMapping mapping : tblMappings) {
                    String xmlField = mapping.getXmlField();
                    String targetCol = mapping.getTargetColumn();
                    if (xmlField != null && targetCol != null && !targetCol.trim().isEmpty()) {
                        Object rawValue = resolveXmlValue(xmlRecord, xmlField, targetCol);
                        Object transformedValue = applyTransform(rawValue, mapping.getTransform());
                        rowMap.put(targetCol, transformedValue);
                    }
                }

                // Enforce primary-foreign key relationship: link child/reference row to master entity ID if missing
                if (!tableName.equalsIgnoreCase(this.defaultTargetTable)) {
                    ensureReferenceId(rowMap, tableName, xmlRecord, tblMappings);
                }

                // Ensure canonical table data is in clean data format (no xml or no json)
                cleanCanonicalRow(rowMap);

                // Ensure enterprise traceability & tracking metadata (bnpp_id, source_bnpp_id, staging_bnpp_id, history_ids)
                enrichRowEnterpriseIdentity(rowMap, tableName, xmlRecord);

                if (isValidRow(rowMap, pkCol)) {
                    validRows.add(rowMap);
                }
            }

            if (!validRows.isEmpty()) {
                multiTableRecords.put(tableName, validRows);
            }
        }

        if (multiTableRecords.isEmpty()) {
            return null;
        }

        long extractedCount = 0;
        for (List<Map<String, Object>> rows : multiTableRecords.values()) {
            if (rows != null) {
                extractedCount += rows.size();
            }
        }
        if (extractedCount == 0) {
            extractedCount = 1;
        }
        totalRowsRead.addAndGet(extractedCount);
        if (this.stepExecution != null) {
            this.stepExecution.getExecutionContext().putLong("actualRowsRead", totalRowsRead.get());
        }

        DatabaseRowPayload payload = new DatabaseRowPayload();
        payload.setMultiTableRecords(multiTableRecords);
        payload.setOriginalXmlMap(xmlRecord);
        return payload;
    }

    private boolean isValidRow(Map<String, Object> cols, String pkCol) {
        if (cols == null || cols.isEmpty()) {
            return false;
        }
        if (cols.values().stream().allMatch(val -> val == null || (val instanceof String s && s.trim().isEmpty()))) {
            return false;
        }
        if (pkCol != null) {
            Object idVal = cols.get(pkCol);
            if (idVal == null || (idVal instanceof String s && s.trim().isEmpty())) {
                return false;
            }
        }
        return true;
    }

    private static Object getCaseInsensitive(Map<String, Object> map, String key) {
        if (map == null || key == null) return null;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void putCaseInsensitive(Map<String, Object> map, String key, Object value) {
        if (map == null || key == null) return;
        for (String existingKey : map.keySet()) {
            if (existingKey.equalsIgnoreCase(key)) {
                map.put(existingKey, value);
                return;
            }
        }
        map.put(key, value);
    }

    /**
     * Enriches a table row with complete enterprise traceability identifiers:
     * - bnpp_id: Sequential alphanumeric unique identifier
     * - current_id: Active/latest version identifier
     * - source_id: Originating/root version identifier
     * - source_bnpp_id: Originating/parent BNPP ID
     * - staging_bnpp_id: Staging BNPP ID linking back to the historical/staging source row
     * - history_ids: History backtracking array/reference
     */
    private void enrichRowEnterpriseIdentity(Map<String, Object> rowMap, String tableName, Map<String, Object> xmlRecord) {
        if (rowMap == null) return;

        // 1. bnpp_id and current_id
        String bid = (String) getCaseInsensitive(rowMap, "current_id");
        if (bid == null || bid.trim().isEmpty()) {
            bid = (String) getCaseInsensitive(rowMap, "bnpp_id");
        }
        if ((bid == null || bid.trim().isEmpty()) && xmlRecord != null) {
            bid = (String) getCaseInsensitive(xmlRecord, "current_id");
            if (bid == null) bid = (String) getCaseInsensitive(xmlRecord, "bnpp_id");
            if (bid == null) bid = (String) getCaseInsensitive(xmlRecord, "_bnpp_id");
        }
        if (bid == null || bid.trim().isEmpty()) {
            bid = com.enterprise.xmldbtool.util.BnppIdGenerator.generateId("BNPP");
        }
        putCaseInsensitive(rowMap, "bnpp_id", bid);
        putCaseInsensitive(rowMap, "current_id", bid);

        // 2. Resolve source / staging bnpp_id from parent entity or record's bnpp_id
        String parentBnpp = (xmlRecord != null) ? (String) getCaseInsensitive(xmlRecord, "_parent_bnpp_id") : null;
        if (parentBnpp == null && xmlRecord != null) parentBnpp = (String) getCaseInsensitive(xmlRecord, "source_id");
        if (parentBnpp == null && xmlRecord != null) parentBnpp = (String) getCaseInsensitive(xmlRecord, "source_bnpp_id");
        if (parentBnpp == null && xmlRecord != null) parentBnpp = (String) getCaseInsensitive(xmlRecord, "current_id");
        if (parentBnpp == null && xmlRecord != null) parentBnpp = (String) getCaseInsensitive(xmlRecord, "bnpp_id");
        if (parentBnpp == null && xmlRecord != null) parentBnpp = (String) getCaseInsensitive(xmlRecord, "_bnpp_id");
        if (parentBnpp == null && xmlRecord != null) parentBnpp = (String) getCaseInsensitive(xmlRecord, "staging_bnpp_id");

        String effectiveSource = parentBnpp != null ? parentBnpp : bid;
        if (getCaseInsensitive(rowMap, "source_id") == null) {
            putCaseInsensitive(rowMap, "source_id", effectiveSource);
        }
        if (getCaseInsensitive(rowMap, "source_bnpp_id") == null) {
            putCaseInsensitive(rowMap, "source_bnpp_id", effectiveSource);
        }
        if (getCaseInsensitive(rowMap, "staging_bnpp_id") == null) {
            putCaseInsensitive(rowMap, "staging_bnpp_id", effectiveSource);
        }

        // 3. history_ids: seed with source_id or current_id
        if (getCaseInsensitive(rowMap, "history_ids") == null) {
            String histId = (String) getCaseInsensitive(rowMap, "source_id");
            if (histId == null) histId = (String) getCaseInsensitive(rowMap, "source_bnpp_id");
            if (histId == null) histId = bid;
            putCaseInsensitive(rowMap, "history_ids", "[" + histId + "]");
        }

        // 4. file_id
        if (getCaseInsensitive(rowMap, "file_id") == null && xmlRecord != null && getCaseInsensitive(xmlRecord, "file_id") != null) {
            putCaseInsensitive(rowMap, "file_id", getCaseInsensitive(xmlRecord, "file_id"));
        }

        // 5. version_number
        if (getCaseInsensitive(rowMap, "version_number") == null) {
            Object ver = (xmlRecord != null) ? getCaseInsensitive(xmlRecord, "version_number") : null;
            putCaseInsensitive(rowMap, "version_number", ver != null ? ver : 1);
        }

        // 6. tag_name
        if (getCaseInsensitive(rowMap, "tag_name") == null) {
            putCaseInsensitive(rowMap, "tag_name", tableName != null ? tableName.toLowerCase() : "record");
        }

        // 7. created_at & updated_at
        if (getCaseInsensitive(rowMap, "created_at") == null) {
            putCaseInsensitive(rowMap, "created_at", new java.sql.Timestamp(System.currentTimeMillis()));
        }
        if (getCaseInsensitive(rowMap, "updated_at") == null) {
            putCaseInsensitive(rowMap, "updated_at", new java.sql.Timestamp(System.currentTimeMillis()));
        }
    }

    /**
     * Cleans rowMap for canonical destination tables:
     * - Strips XML tags (<...>) so no raw XML is stored in canonical tables.
     * - Cleans JSON objects and arrays so data is in scalar clean format (no {...} or [...]).
     */
    private void cleanCanonicalRow(Map<String, Object> rowMap) {
        if (rowMap == null || rowMap.isEmpty()) return;
        for (Map.Entry<String, Object> entry : rowMap.entrySet()) {
            entry.setValue(cleanDataFormat(entry.getValue()));
        }
    }

    public static Object cleanDataFormat(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) {
                return trimmed;
            }
            // Strip XML tags if present
            if ((trimmed.startsWith("<") && trimmed.endsWith(">")) || (trimmed.contains("<") && trimmed.contains(">") && trimmed.matches("(?s).*<[a-zA-Z0-9_:-]+[^>]*>.*"))) {
                return stripXmlTags(trimmed);
            }
            // Extract clean scalar data if JSON formatted
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                return extractCleanDataFromJson(trimmed);
            }
            return trimmed;
        } else if (val instanceof Map<?, ?> map) {
            if (map.containsKey("id")) return cleanDataFormat(map.get("id"));
            if (map.containsKey("@id")) return cleanDataFormat(map.get("@id"));
            if (map.containsKey("code")) return cleanDataFormat(map.get("code"));
            if (map.containsKey("@code")) return cleanDataFormat(map.get("@code"));
            if (map.containsKey("value")) return cleanDataFormat(map.get("value"));
            if (map.containsKey("content")) return cleanDataFormat(map.get("content"));
            List<String> values = new ArrayList<>();
            for (Object v : map.values()) {
                Object c = cleanDataFormat(v);
                if (c != null && !c.toString().isEmpty()) values.add(c.toString());
            }
            return String.join(", ", values);
        } else if (val instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                Object c = cleanDataFormat(item);
                if (c != null && !c.toString().isEmpty()) values.add(c.toString());
            }
            return String.join(", ", values);
        }
        return val;
    }

    public static String stripXmlTags(String xml) {
        if (xml == null) return null;
        String stripped = xml.replaceAll("<[^>]+>", " ").trim();
        return stripped.replaceAll("\\s+", " ").trim();
    }

    public static String extractCleanDataFromJson(String json) {
        if (json == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = SHARED_MAPPER.readTree(json);
            if (node.isValueNode()) {
                return node.asText();
            }
            List<String> leafTexts = new ArrayList<>();
            collectLeafTexts(node, leafTexts);
            return String.join(", ", leafTexts);
        } catch (Exception ex) {
            return json.replaceAll("[\\[\\]{}\"\"]", "").replaceAll("[,:]+", " ").replaceAll("\\s+", " ").trim();
        }
    }

    private static void collectLeafTexts(com.fasterxml.jackson.databind.JsonNode node, List<String> leaves) {
        if (node == null || node.isNull()) return;
        if (node.isValueNode()) {
            String text = node.asText();
            if (text != null && !text.trim().isEmpty()) {
                leaves.add(text.trim());
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode elem : node) {
                collectLeafTexts(elem, leaves);
            }
        } else if (node.isObject()) {
            if (node.hasNonNull("id")) {
                leaves.add(node.get("id").asText().trim());
            } else if (node.hasNonNull("code")) {
                leaves.add(node.get("code").asText().trim());
            } else if (node.hasNonNull("value")) {
                leaves.add(node.get("value").asText().trim());
            } else {
                node.fields().forEachRemaining(entry -> collectLeafTexts(entry.getValue(), leaves));
            }
        }
    }

    /**
     * Enforces primary-foreign key relationship for child/reference tables:
     * populates reference id (e.g. person_id, master_id) with clean scalar parent ID.
     */
    private void ensureReferenceId(Map<String, Object> rowMap, String tableName, Map<String, Object> xmlRecord, List<FieldMapping> tblMappings) {
        String masterTable = (this.defaultTargetTable != null ? this.defaultTargetTable.toLowerCase() : "record");
        String baseMaster = masterTable.replaceFirst("^gs_", "");
        String defaultFkCol = baseMaster + "_id";
        Set<String> fkCols = new LinkedHashSet<>();

        String normTbl = tableName != null ? tableName.toLowerCase() : "";
        String baseTbl = normTbl.replaceFirst("^gs_", "");
        boolean isChildOfMaster = baseTbl.startsWith(baseMaster) && !baseTbl.equals(baseMaster);

        if (tblMappings != null) {
            for (FieldMapping m : tblMappings) {
                String tgt = m.getTargetColumn();
                if (tgt == null) continue;
                String tgtLower = tgt.toLowerCase();
                if (tgtLower.equals("current_id") || tgtLower.equals("source_id") || tgtLower.equals("bnpp_id")
                        || tgtLower.equals("source_bnpp_id") || tgtLower.equals("staging_bnpp_id")
                        || tgtLower.equals("history_ids") || tgtLower.equals("file_id")) {
                    continue;
                }
                if ("FK".equalsIgnoreCase(m.getConstraintType()) || m.getForeignKeyTable() != null) {
                    fkCols.add(tgtLower);
                } else if (tgtLower.endsWith("_id") && !tgtLower.equals("id") && !"PK".equalsIgnoreCase(m.getConstraintType())) {
                    fkCols.add(tgtLower);
                }
            }
        }
        if (isChildOfMaster) {
            fkCols.add(defaultFkCol);
        }

        if (fkCols.isEmpty()) {
            return;
        }

        Object parentId = resolveParentReferenceId(xmlRecord);
        if (parentId != null) {
            Object cleanRefId = cleanDataFormat(parentId);
            for (String fkCol : fkCols) {
                Object existingVal = getCaseInsensitive(rowMap, fkCol);
                if (existingVal == null || String.valueOf(existingVal).trim().isEmpty()) {
                    putCaseInsensitive(rowMap, fkCol, cleanRefId);
                } else {
                    putCaseInsensitive(rowMap, fkCol, cleanDataFormat(existingVal));
                }
            }
        }
    }

    private Object resolveParentReferenceId(Map<String, Object> xmlRecord) {
        if (xmlRecord == null) return null;
        String defaultFkCol = (this.defaultTargetTable != null ? this.defaultTargetTable.toLowerCase() : "record") + "_id";
        Object parentId = xmlRecord.get(defaultFkCol);
        if (parentId == null) parentId = xmlRecord.get(defaultFkCol.replaceFirst("^gs_", ""));
        if (parentId == null) parentId = xmlRecord.get("person_id");
        if (parentId == null) parentId = xmlRecord.get("record_identifier");
        if (parentId == null) parentId = xmlRecord.get("id");
        if (parentId == null) parentId = xmlRecord.get("@id");
        if (parentId == null) parentId = xmlRecord.get("Id");
        if (parentId == null) parentId = xmlRecord.get("@Id");
        if (parentId == null) parentId = xmlRecord.get("ID");
        if (parentId == null) parentId = xmlRecord.get("@ID");
        if (parentId == null) parentId = xmlRecord.get("code");
        if (parentId == null) parentId = xmlRecord.get("@code");
        if (parentId == null) parentId = xmlRecord.get("_bnpp_id");
        if (parentId == null) parentId = xmlRecord.get("bnpp_id");
        if (parentId == null) parentId = xmlRecord.get("_record_id");
        if (parentId == null && this.mappings != null) {
            for (FieldMapping m : this.mappings) {
                if ("PK".equalsIgnoreCase(m.getConstraintType())
                        && m.getTargetTable() != null
                        && (m.getTargetTable().equalsIgnoreCase(this.defaultTargetTable)
                            || m.getTargetTable().equalsIgnoreCase("gs_" + this.defaultTargetTable)
                            || ("gs_" + m.getTargetTable()).equalsIgnoreCase(this.defaultTargetTable)
                            || m.getTargetTable().replaceFirst("(?i)^gs_", "").equalsIgnoreCase(this.defaultTargetTable.replaceFirst("(?i)^gs_", "")))) {
                    parentId = xmlRecord.get(m.getTargetColumn());
                    if (parentId == null && m.getXmlField() != null) {
                        parentId = resolveXmlValue(xmlRecord, m.getXmlField(), m.getTargetColumn());
                    }
                    if (parentId != null) break;
                }
            }
        }
        return parentId;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findEntityRecordsForTable(String tableName, List<FieldMapping> tblMappings, Map<String, Object> xmlRecord) {
        if (xmlRecord == null || tblMappings == null || tblMappings.isEmpty()) {
            return Collections.emptyList();
        }

        String normTable = tableName.toLowerCase().trim();
        String singular = DynamicXmlUnmarshaller.singularize(normTable);
        String plural = DynamicXmlUnmarshaller.pluralize(normTable);

        // 1. Direct match on exact table name, singular, or plural in _entities_*
        Object list = xmlRecord.get("_entities_" + normTable);
        if (list instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
            return (List<Map<String, Object>>) list;
        }
        list = xmlRecord.get("_entities_" + singular);
        if (list instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
            return (List<Map<String, Object>>) list;
        }
        list = xmlRecord.get("_entities_" + plural);
        if (list instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
            return (List<Map<String, Object>>) list;
        }

        String defaultNorm = this.defaultTargetTable != null ? this.defaultTargetTable.toLowerCase().trim() : "record";
        String entityTag = (String) xmlRecord.get("_entity_tag");
        if (entityTag == null) entityTag = (String) xmlRecord.get("_tag_name");
        if (entityTag == null) entityTag = (String) xmlRecord.get("tag_name");
        if (entityTag == null) entityTag = (String) xmlRecord.get("entity_type");
        if (entityTag == null) entityTag = this.defaultTargetTable;
        String normEntity = entityTag != null ? entityTag.toLowerCase().trim() : defaultNorm;
        String baseEntity = normEntity.replaceFirst("^gs_", "");
        String baseTable = normTable.replaceFirst("^gs_", "");

        // If table is the main root master entity itself and no direct _entities_* was found, it is a single master record
        if (baseTable.equals(baseEntity) || baseTable.equals(defaultNorm)
                || baseTable.equals(DynamicXmlUnmarshaller.singularize(baseEntity))
                || baseTable.equals(DynamicXmlUnmarshaller.pluralize(baseEntity))) {
            return Collections.emptyList();
        }

        // 2. Gather all candidate tag identifiers from table name and mapping xmlFields
        Set<String> candidateTags = new LinkedHashSet<>();
        candidateTags.add(normTable);
        candidateTags.add(singular);
        candidateTags.add(plural);
        candidateTags.add(baseTable);
        candidateTags.add(DynamicXmlUnmarshaller.singularize(baseTable));
        candidateTags.add(DynamicXmlUnmarshaller.pluralize(baseTable));

        // Strip entity prefixes (e.g. "person_name" -> "name", "personname" -> "name", "personcountry" -> "country")
        for (String prefix : List.of(normEntity + "_", normEntity, baseEntity + "_", baseEntity, defaultNorm + "_", defaultNorm, "person_", "person")) {
            if (baseTable.startsWith(prefix) && baseTable.length() > prefix.length()) {
                String stripped = baseTable.substring(prefix.length());
                candidateTags.add(stripped);
                candidateTags.add(DynamicXmlUnmarshaller.singularize(stripped));
                candidateTags.add(DynamicXmlUnmarshaller.pluralize(stripped));
            }
            if (normTable.startsWith(prefix) && normTable.length() > prefix.length()) {
                String stripped = normTable.substring(prefix.length());
                candidateTags.add(stripped);
                candidateTags.add(DynamicXmlUnmarshaller.singularize(stripped));
                candidateTags.add(DynamicXmlUnmarshaller.pluralize(stripped));
            }
        }

        // Special composite entity aliases
        if (normTable.contains("name") || baseTable.contains("name")) {
            candidateTags.addAll(List.of("namevalue", "name", "names", "namedetails"));
        } else if (normTable.contains("date") || baseTable.contains("date")) {
            candidateTags.addAll(List.of("datevalue", "date", "dates", "datedetails", "datetype", "datetypelist"));
        } else if (normTable.contains("country") || baseTable.contains("country")) {
            candidateTags.addAll(List.of("countryvalue", "country", "countries", "countrydetails", "countrylist", "countryname"));
        } else if (normTable.contains("role") || baseTable.contains("role") || normTable.contains("occupation") || baseTable.contains("occupation")) {
            candidateTags.addAll(List.of("occtitle", "roles", "role", "roledetail", "roledetails", "occupation", "occupationlist"));
        } else if (normTable.contains("sanction") || baseTable.contains("sanction") || normTable.contains("reference") || baseTable.contains("reference")) {
            candidateTags.addAll(List.of("reference", "references", "sanctionsreferences", "referencename", "sanctionsreferenceslist"));
        } else if (normTable.contains("id") || baseTable.contains("id") || normTable.contains("identification") || baseTable.contains("identification")) {
            candidateTags.addAll(List.of("idvalue", "id", "ids", "iddetails", "idnumbertypes"));
        } else if (normTable.contains("source") || baseTable.contains("source")) {
            candidateTags.addAll(List.of("source", "sources", "sourcedetails", "sourcedescription"));
        } else if (normTable.contains("image") || baseTable.contains("image")) {
            candidateTags.addAll(List.of("image", "images"));
        } else if (normTable.contains("description") || baseTable.contains("description")) {
            candidateTags.addAll(List.of("description", "descriptions", "description1name", "description2name", "description3name"));
        } else if (normTable.contains("address") || baseTable.contains("address")) {
            candidateTags.addAll(List.of("address", "addresses"));
        } else if (normTable.contains("relation") || baseTable.contains("relation")) {
            candidateTags.addAll(List.of("relationship", "relationshiplist"));
        }

        // Also inspect xmlField paths in tblMappings for explicit tag references
        for (FieldMapping m : tblMappings) {
            String f = m.getXmlField();
            if (f != null && !f.trim().isEmpty()) {
                String clean = f.replace("@", "").toLowerCase();
                String[] parts = clean.split("/");
                for (String part : parts) {
                    if (!part.equals(normEntity) && !part.equals(baseEntity) && !part.equals(defaultNorm) && !part.isEmpty()
                            && !part.equals("pfa") && !part.equals("records") && !part.equals("canopyapp")) {
                        candidateTags.add(part);
                        candidateTags.add(DynamicXmlUnmarshaller.singularize(part));
                        candidateTags.add(DynamicXmlUnmarshaller.pluralize(part));
                    }
                }
            }
        }

        // 2. Check direct entity collections already in xmlRecord (_entities_<tag>)
        for (String tag : candidateTags) {
            Object directList = xmlRecord.get("_entities_" + tag);
            if (directList instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
                return (List<Map<String, Object>>) directList;
            }
        }

        // 3. Check raw XML stored for candidate sub-tags in flat table columns or _subtag_xml_*
        for (String tag : candidateTags) {
            for (String key : List.of(tag, "_subtag_xml_" + tag, "_subtag_" + tag)) {
                Object val = xmlRecord.get(key);
                if (val instanceof String str && !str.trim().isEmpty()) {
                    String trimmed = str.trim();
                    if (trimmed.startsWith("<")) {
                        List<Map<String, Object>> parsed = parseRawXmlSubTagEntities(trimmed, tag, tblMappings, xmlRecord);
                        if (!parsed.isEmpty()) return parsed;
                    } else if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                        try {
                            if (trimmed.startsWith("[")) {
                                List<Map<String, Object>> parsed = SHARED_MAPPER.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                                if (!parsed.isEmpty()) return parsed;
                            } else {
                                Map<String, Object> parsed = SHARED_MAPPER.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                                if (!parsed.isEmpty()) return List.of(parsed);
                            }
                        } catch (Exception ignored) {}
                    }
                } else if (val instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
                    return (List<Map<String, Object>>) val;
                } else if (val instanceof Map<?, ?> m) {
                    for (String sub : List.of(tag, singular, plural, "item", "entry", "record")) {
                        Object subVal = m.get(sub);
                        if (subVal == null) subVal = m.get(sub.toLowerCase());
                        if (subVal == null) subVal = m.get(sub.toUpperCase());
                        if (subVal instanceof List<?> sl && !sl.isEmpty() && sl.get(0) instanceof Map) {
                            return (List<Map<String, Object>>) sl;
                        } else if (subVal instanceof Map<?, ?> sm) {
                            return List.of((Map<String, Object>) sm);
                        }
                    }
                }
            }
        }

        // 4. Check any entry in xmlRecord whose key matches any candidate tag where value is raw XML
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            String k = entry.getKey().toLowerCase();
            for (String tag : candidateTags) {
                if (k.equals(tag) || k.contains(tag)) {
                    Object val = entry.getValue();
                    if (val instanceof String str && str.trim().startsWith("<")) {
                        List<Map<String, Object>> parsed = parseRawXmlSubTagEntities(str.trim(), tag, tblMappings, xmlRecord);
                        if (!parsed.isEmpty()) return parsed;
                    }
                }
            }
        }

        // 5. Check if raw_payload is raw XML containing the subtag
        Object rawPayload = xmlRecord.get("raw_payload");
        if (rawPayload instanceof String str && str.trim().startsWith("<")) {
            List<Map<String, Object>> parsed = parseRawXmlSubTagEntities(str.trim(), normTable, tblMappings, xmlRecord);
            if (!parsed.isEmpty()) return parsed;
        }

        // 6. Fuzzy fallback match against any _entities_* keys in xmlRecord
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            if (entry.getKey().startsWith("_entities_") && entry.getValue() instanceof List<?> l && !l.isEmpty()) {
                String entTag = entry.getKey().substring("_entities_".length());
                if (candidateTags.contains(entTag) || normTable.contains(entTag) || entTag.contains(normTable)) {
                    return (List<Map<String, Object>>) entry.getValue();
                }
            }
        }

        return Collections.emptyList();
    }

    public static List<Map<String, Object>> parseRawXmlSubTagEntities(String rawXml, String expectedEntityTag, Map<String, Object> parentRecord) {
        return parseRawXmlSubTagEntities(rawXml, expectedEntityTag, Collections.emptyList(), parentRecord);
    }

    public static List<Map<String, Object>> parseRawXmlSubTagEntities(String rawXml, String expectedEntityTag, List<FieldMapping> tblMappings, Map<String, Object> parentRecord) {
        if (rawXml == null || rawXml.trim().isEmpty() || !rawXml.trim().startsWith("<")) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            XMLStreamReader reader = SHARED_XML_FACTORY.createXMLStreamReader(new StringReader(rawXml));
            int depth = 0;
            ParseFrame currentFrame = null;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    depth++;
                    ParseFrame frame = new ParseFrame(localName, currentFrame, depth);
                    if (currentFrame != null) {
                        currentFrame.childElementCount++;
                    }

                    int attrCount = reader.getAttributeCount();
                    if (attrCount > 0) {
                        frame.hasDirectAttributes = true;
                    }
                    for (int i = 0; i < attrCount; i++) {
                        String aName = reader.getAttributeLocalName(i);
                        String aVal = reader.getAttributeValue(i);
                        frame.data.put("@" + aName, aVal);
                        frame.data.put(aName, aVal);
                        frame.data.put("@" + aName.toLowerCase(), aVal);
                        frame.data.put(aName.toLowerCase(), aVal);
                        frame.data.put(localName + "/@" + aName, aVal);
                        frame.data.put(localName + "/" + aName, aVal);
                    }

                    // Inherit contextual attributes from ancestor frames
                    ParseFrame anc = currentFrame;
                    while (anc != null) {
                        for (Map.Entry<String, Object> entry : anc.data.entrySet()) {
                            String key = entry.getKey();
                            if (key.startsWith("@") && !key.contains("/")) {
                                frame.data.putIfAbsent(key, entry.getValue());
                                frame.data.putIfAbsent(key.toLowerCase(), entry.getValue());
                                String clean = key.substring(1);
                                frame.data.putIfAbsent(clean, entry.getValue());
                                frame.data.putIfAbsent(clean.toLowerCase(), entry.getValue());
                                frame.data.putIfAbsent(anc.tagName + "/" + key, entry.getValue());
                                frame.data.putIfAbsent(anc.tagName + "/" + clean, entry.getValue());
                            }
                        }
                        anc = anc.parent;
                    }

                    // Inherit contextual IDs from parent record
                    if (parentRecord != null) {
                        for (Map.Entry<String, Object> pEntry : parentRecord.entrySet()) {
                            String pk = pEntry.getKey().toLowerCase();
                            if (pk.equals("id") || pk.equals("@id") || pk.endsWith("_id")) {
                                frame.data.putIfAbsent("parent_" + pk, pEntry.getValue());
                                frame.data.putIfAbsent(pk, pEntry.getValue());
                            }
                        }
                    }

                    currentFrame = frame;
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (currentFrame != null && !reader.isWhiteSpace()) {
                        currentFrame.text.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String localName = reader.getLocalName();
                    String text = currentFrame != null ? currentFrame.text.toString().trim() : "";

                    if (currentFrame != null) {
                        if (!text.isEmpty()) {
                            currentFrame.data.put(localName, text);
                            currentFrame.data.put(localName.toLowerCase(), text);
                            currentFrame.data.put("value", text);
                            currentFrame.data.put("content", text);
                        }

                        // Propagate values to parent frame
                        if (currentFrame.parent != null) {
                            if (!text.isEmpty()) {
                                currentFrame.parent.data.put(localName, text);
                                currentFrame.parent.data.put(localName.toLowerCase(), text);
                                currentFrame.parent.data.put(localName + "/value", text);
                            }
                            for (Map.Entry<String, Object> entry : currentFrame.data.entrySet()) {
                                if (entry.getKey().contains("/")) {
                                    currentFrame.parent.data.putIfAbsent(entry.getKey(), entry.getValue());
                                } else {
                                    currentFrame.parent.data.putIfAbsent(localName + "/" + entry.getKey(), entry.getValue());
                                }
                            }
                        }

                        // Determine if currentFrame is a discrete entity record to emit:
                        // Emits when: depth > 1 (not top wrapper), childEntityCount == 0 (leaf entity item),
                        // and has direct attributes or text or child elements
                        boolean isEntity = currentFrame.depth > 1
                                && currentFrame.childEntityCount == 0
                                && (currentFrame.hasDirectAttributes || !text.isEmpty() || currentFrame.childElementCount > 0);

                        if (isEntity) {
                            if (currentFrame.parent != null) {
                                currentFrame.parent.childEntityCount++;
                            }
                            results.add(new LinkedHashMap<>(currentFrame.data));
                        }

                        currentFrame = currentFrame.parent;
                    }
                    depth--;
                }
            }
        } catch (Exception ex) {
            log.debug("Notice parsing raw XML sub-tag entities: {}", ex.getMessage());
        }
        return results;
    }

    private static class ParseFrame {
        final String tagName;
        final ParseFrame parent;
        final int depth;
        final Map<String, Object> data = new LinkedHashMap<>();
        final StringBuilder text = new StringBuilder();
        int childEntityCount = 0;
        int childElementCount = 0;
        boolean hasDirectAttributes = false;

        ParseFrame(String tagName, ParseFrame parent, int depth) {
            this.tagName = tagName;
            this.parent = parent;
            this.depth = depth;
        }
    }

    private Object applyTransform(Object value, String transform) {
        if (value == null) {
            return null;
        }
        String str = String.valueOf(value).trim();
        if ("UPPER".equalsIgnoreCase(transform)) {
            return str.toUpperCase();
        } else if ("LOWER".equalsIgnoreCase(transform)) {
            return str.toLowerCase();
        } else if ("TRIM".equalsIgnoreCase(transform)) {
            return str;
        }
        return str;
    }

    private Object resolveXmlValue(Map<String, Object> xmlRecord, String xmlField, String targetCol) {
        if (xmlField == null || xmlRecord == null) return null;

        // 1. Direct key match (exact case)
        Object val = xmlRecord.get(xmlField);
        if (val != null) return val;

        // 2. Case-insensitive key match
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(xmlField)) {
                return entry.getValue();
            }
        }

        String leaf = xmlField.contains("/") ? xmlField.substring(xmlField.lastIndexOf("/") + 1) : xmlField;
        String rawLeaf = leaf.startsWith("@") ? leaf.substring(1) : leaf;
        String attrLeaf = leaf.startsWith("@") ? leaf : "@" + leaf;

        // 3. Qualified path match (e.g. "Person/NameDetails/Name/@NameType", "descriptionlist2/@id")
        if (xmlField.contains("/")) {
            String[] segments = xmlField.split("/");
            String immediateParent = segments.length >= 2 ? segments[segments.length - 2] : segments[0];
            String tagPrefix = segments[0];

            // 3a. Check relative sub-paths from most specific to least specific
            // (e.g. for "person/addresses/address/@id": "addresses/address/@id", "address/@id", "@id")
            for (int i = 1; i < segments.length; i++) {
                StringBuilder rel = new StringBuilder();
                for (int j = i; j < segments.length; j++) {
                    if (rel.length() > 0) rel.append("/");
                    rel.append(segments[j]);
                }
                String relPath = rel.toString();

                // When relPath is just the leaf (i == segments.length - 1), only match if the record's entity tag matches immediateParent
                if (i == segments.length - 1) {
                    String entityTag = (String) xmlRecord.get("_entity_tag");
                    if (entityTag == null) entityTag = (String) xmlRecord.get("_tag_name");
                    boolean matches = (entityTag == null)
                            || entityTag.equalsIgnoreCase(immediateParent)
                            || DynamicXmlUnmarshaller.singularize(entityTag).equalsIgnoreCase(DynamicXmlUnmarshaller.singularize(immediateParent))
                            || DynamicXmlUnmarshaller.pluralize(entityTag).equalsIgnoreCase(DynamicXmlUnmarshaller.pluralize(immediateParent));
                    if (!matches) {
                        continue;
                    }
                }

                Object rVal = xmlRecord.get(relPath);
                if (rVal != null) return rVal;
                for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(relPath)) return entry.getValue();
                }
            }

            // Check if xmlRecord has qualified immediateParent/@rawLeaf or immediateParent/rawLeaf
            for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                String k = entry.getKey();
                if (k.equalsIgnoreCase(immediateParent + "/@" + rawLeaf) || k.equalsIgnoreCase(immediateParent + "/" + rawLeaf)) {
                    return entry.getValue();
                }
            }

            // Check if xmlRecord has qualified tagPrefix/@rawLeaf or tagPrefix/rawLeaf (only when segments.length == 2)
            if (segments.length == 2) {
                for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                    String k = entry.getKey();
                    if (k.equalsIgnoreCase(tagPrefix + "/@" + rawLeaf) || k.equalsIgnoreCase(tagPrefix + "/" + rawLeaf)) {
                        return entry.getValue();
                    }
                }
            }

            // Check if xmlRecord is an entity fragment matching immediateParent or tagPrefix
            String entityTag = (String) xmlRecord.get("_entity_tag");
            if (entityTag == null) entityTag = (String) xmlRecord.get("_tag_name");

            boolean tagMatches = (entityTag == null)
                    || entityTag.equalsIgnoreCase(immediateParent)
                    || DynamicXmlUnmarshaller.singularize(entityTag).equalsIgnoreCase(DynamicXmlUnmarshaller.singularize(immediateParent))
                    || DynamicXmlUnmarshaller.pluralize(entityTag).equalsIgnoreCase(DynamicXmlUnmarshaller.pluralize(immediateParent))
                    || (segments.length == 2 && (entityTag.equalsIgnoreCase(tagPrefix)
                            || DynamicXmlUnmarshaller.singularize(entityTag).equalsIgnoreCase(DynamicXmlUnmarshaller.singularize(tagPrefix))
                            || DynamicXmlUnmarshaller.pluralize(entityTag).equalsIgnoreCase(DynamicXmlUnmarshaller.pluralize(tagPrefix))))
                    || immediateParent.equalsIgnoreCase(this.defaultTargetTable)
                    || immediateParent.equalsIgnoreCase("record");

            if (!tagMatches && entityTag != null) {
                String normEnt = entityTag.toLowerCase();
                String singEnt = DynamicXmlUnmarshaller.singularize(normEnt);
                String plurEnt = DynamicXmlUnmarshaller.pluralize(normEnt);
                String baseEnt = normEnt.replaceFirst("^gs_", "");
                if (normEnt.equalsIgnoreCase(immediateParent) || singEnt.equalsIgnoreCase(immediateParent)
                        || plurEnt.equalsIgnoreCase(immediateParent) || baseEnt.equalsIgnoreCase(immediateParent)
                        || DynamicXmlUnmarshaller.singularize(baseEnt).equalsIgnoreCase(immediateParent)) {
                    tagMatches = true;
                }
            }

            if (tagMatches) {
                // If unqualified attribute exists, ensure no conflicting qualified tags from other entities exist
                val = xmlRecord.get(attrLeaf);
                if (val != null) {
                    boolean hasOtherTagAttr = xmlRecord.keySet().stream().anyMatch(k ->
                            k.endsWith("/@" + rawLeaf) && !k.toLowerCase().startsWith(immediateParent.toLowerCase() + "/"));
                    if (!hasOtherTagAttr) {
                        return val;
                    }
                }

                val = xmlRecord.get(rawLeaf);
                if (val != null) {
                    boolean hasOtherTagAttr = xmlRecord.keySet().stream().anyMatch(k ->
                            k.endsWith("/@" + rawLeaf) && !k.toLowerCase().startsWith(immediateParent.toLowerCase() + "/"));
                    if (!hasOtherTagAttr) {
                        return val;
                    }
                }

                // Check leaf
                for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                    String k = entry.getKey();
                    if (k.equalsIgnoreCase(rawLeaf) || k.equalsIgnoreCase(attrLeaf)) {
                        if (!k.contains("/") || k.toLowerCase().startsWith(immediateParent.toLowerCase() + "/")) {
                            return entry.getValue();
                        }
                    }
                }
            }

            // Do not steal attribute from unrelated tag
            return null;
        }

        // 4. Unqualified fields: direct check on leaf and @leaf
        val = xmlRecord.get(rawLeaf);
        if (val != null) return val;
        val = xmlRecord.get(attrLeaf);
        if (val != null) return val;
        val = xmlRecord.get(rawLeaf.toLowerCase());
        if (val != null) return val;
        val = xmlRecord.get(attrLeaf.toLowerCase());
        if (val != null) return val;

        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            String k = entry.getKey();
            if (k.equalsIgnoreCase(rawLeaf) || k.equalsIgnoreCase(attrLeaf)) {
                return entry.getValue();
            }
        }

        // 5. Look up by attribute name suffix
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            String k = entry.getKey();
            if (k.endsWith("/@" + rawLeaf) || k.endsWith("/" + rawLeaf) || k.equalsIgnoreCase("@" + rawLeaf) || k.equalsIgnoreCase(rawLeaf)) {
                return entry.getValue();
            }
        }

        // 6. Normalized match
        String normField = normalize(rawLeaf);
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            if (normalize(entry.getKey()).equals(normField)) {
                return entry.getValue();
            }
        }

        // 7. Semantic alias fallbacks (location, warehouse, region, sku)
        String normTarget = targetCol != null ? normalize(targetCol) : "";
        if (normField.contains("location") || normField.contains("warehouse") ||
                normTarget.contains("location") || normTarget.contains("warehouse")) {
            for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                String k = normalize(entry.getKey());
                if (k.contains("warehouselocation") || k.contains("warehouse") || k.contains("location")) {
                    return entry.getValue();
                }
            }
        }

        if (normField.contains("region") || normTarget.contains("region")) {
            for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                if (normalize(entry.getKey()).contains("region")) {
                    return entry.getValue();
                }
            }
        }

        if (normField.contains("sku") || normTarget.contains("sku")) {
            for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                if (normalize(entry.getKey()).contains("sku")) {
                    return entry.getValue();
                }
            }
        }

        // 8. If parent identifier is needed (e.g. person_id or id)
        if (rawLeaf.equalsIgnoreCase("id") || rawLeaf.endsWith("_id")) {
            val = xmlRecord.get("parent_id");
            if (val != null) return val;
            val = xmlRecord.get("person_id");
            if (val != null) return val;
            val = xmlRecord.get("id");
            if (val != null) return val;
            val = xmlRecord.get("@id");
            if (val != null) return val;
        }

        return null;
    }

    private String normalize(String s) {
        return s == null ? "" : s.replace("@", "").replace("-", "").replace("_", "").toLowerCase();
    }
}
