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

        for (String tableName : targetTables) {
            List<FieldMapping> tblMappings = tableMappings.get(tableName);
            if (tblMappings == null || tblMappings.isEmpty()) continue;

            if (entityTag != null && !entityTag.isEmpty()) {
                String normEntity = entityTag.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
                String singEntity = DynamicXmlUnmarshaller.singularize(normEntity);
                String plurEntity = DynamicXmlUnmarshaller.pluralize(normEntity);
                String baseEnt = normEntity.replaceAll("(?i)lists?$", "");
                String singBase = DynamicXmlUnmarshaller.singularize(baseEnt);

                boolean tableRelevant = tblMappings.stream().anyMatch(m -> {
                    String xf = m.getXmlField();
                    if (xf != null) {
                        String clean = xf.replace("@", "").toLowerCase().replaceAll("[^a-zA-Z0-9/]", "");
                        if (clean.contains("/")) {
                            String firstTag = clean.substring(0, clean.indexOf("/"));
                            if (firstTag.equals(normEntity) || firstTag.equals(singEntity) || firstTag.equals(plurEntity)
                                    || firstTag.equals(baseEnt) || firstTag.equals(singBase)
                                    || xmlRecord.containsKey("_entities_" + firstTag)
                                    || xmlRecord.containsKey("_subtag_" + firstTag)
                                    || xmlRecord.containsKey("_subtag_xml_" + firstTag)) {
                                return true;
                            }
                        } else {
                            if (clean.equals(normEntity) || clean.equals(singEntity) || clean.equals(plurEntity)
                                    || clean.equals(baseEnt) || clean.equals(singBase)) {
                                return true;
                            }
                        }
                    }
                    String tt = m.getTargetTable();
                    if (tt != null) {
                        String normT = tt.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
                        String singT = DynamicXmlUnmarshaller.singularize(normT);
                        String baseT = normT.replaceAll("(?i)lists?$", "");
                        String singBaseT = DynamicXmlUnmarshaller.singularize(baseT);

                        if (normT.equals(normEntity) || normT.equals(singEntity) || normT.equals(plurEntity)
                                || singT.equals(normEntity) || singT.equals(singEntity)
                                || normT.equals(baseEnt) || normT.equals(singBase)
                                || baseT.equals(baseEnt) || singBaseT.equals(singBase)) {
                            return true;
                        }
                    }
                    return false;
                });

                if (!tableRelevant) {
                    String normTbl = tableName.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
                    String singTbl = DynamicXmlUnmarshaller.singularize(normTbl);
                    String baseTbl = normTbl.replaceAll("(?i)lists?$", "");
                    String singBaseTbl = DynamicXmlUnmarshaller.singularize(baseTbl);

                    tableRelevant = normTbl.equals(normEntity) || normTbl.equals(singEntity) || normTbl.equals(plurEntity)
                            || singTbl.equals(normEntity) || singTbl.equals(singEntity)
                            || normTbl.equals(baseEnt) || normTbl.equals(singBase)
                            || baseTbl.equals(baseEnt) || singBaseTbl.equals(singBase)
                            || (normTbl.startsWith(normEntity) && !normTbl.equals(normEntity));
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
        String defaultFkCol = masterTable + "_id";
        Set<String> fkCols = new LinkedHashSet<>();

        boolean isChildOfMaster = tableName != null && tableName.toLowerCase().startsWith(masterTable);
        if (tblMappings != null) {
            for (FieldMapping m : tblMappings) {
                if ("FK".equalsIgnoreCase(m.getConstraintType()) || m.getForeignKeyTable() != null) {
                    if (m.getTargetColumn() != null) {
                        fkCols.add(m.getTargetColumn().toLowerCase());
                    }
                } else if (m.getTargetColumn() != null && m.getTargetColumn().toLowerCase().endsWith("_id")
                        && !m.getTargetColumn().equalsIgnoreCase("id")
                        && !"PK".equalsIgnoreCase(m.getConstraintType())) {
                    fkCols.add(m.getTargetColumn().toLowerCase());
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
                if (!rowMap.containsKey(fkCol) || rowMap.get(fkCol) == null || String.valueOf(rowMap.get(fkCol)).trim().isEmpty()) {
                    rowMap.put(fkCol, cleanRefId);
                } else {
                    rowMap.put(fkCol, cleanDataFormat(rowMap.get(fkCol)));
                }
            }
        }
    }

    private Object resolveParentReferenceId(Map<String, Object> xmlRecord) {
        if (xmlRecord == null) return null;
        String defaultFkCol = (this.defaultTargetTable != null ? this.defaultTargetTable.toLowerCase() : "record") + "_id";
        Object parentId = xmlRecord.get(defaultFkCol);
        if (parentId == null) parentId = xmlRecord.get("id");
        if (parentId == null) parentId = xmlRecord.get("@id");
        if (parentId == null) parentId = xmlRecord.get("Id");
        if (parentId == null) parentId = xmlRecord.get("@Id");
        if (parentId == null) parentId = xmlRecord.get("ID");
        if (parentId == null) parentId = xmlRecord.get("@ID");
        if (parentId == null) parentId = xmlRecord.get("code");
        if (parentId == null) parentId = xmlRecord.get("@code");
        if (parentId == null) parentId = xmlRecord.get("_bnpp_id");
        if (parentId == null) parentId = xmlRecord.get("_record_id");
        if (parentId == null && this.mappings != null) {
            for (FieldMapping m : this.mappings) {
                if ("PK".equalsIgnoreCase(m.getConstraintType())
                        && m.getTargetTable() != null
                        && m.getTargetTable().equalsIgnoreCase(this.defaultTargetTable)) {
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
        String normEntity = entityTag != null ? entityTag.toLowerCase().trim() : defaultNorm;

        // If table is the main root master entity itself and no direct _entities_* was found, it is a single master record
        if (normTable.equals(normEntity) || normTable.equals(defaultNorm)
                || normTable.equals(DynamicXmlUnmarshaller.singularize(normEntity))
                || normTable.equals(DynamicXmlUnmarshaller.pluralize(normEntity))) {
            return Collections.emptyList();
        }

        // 2. Gather all candidate tag identifiers from table name and mapping xmlFields
        Set<String> candidateTags = new LinkedHashSet<>();
        candidateTags.add(normTable);
        candidateTags.add(singular);
        candidateTags.add(plural);

        // Strip entity prefixes (e.g. "person_name" -> "name", "personname" -> "name", "personcountry" -> "country")
        for (String prefix : List.of(normEntity + "_", normEntity, defaultNorm + "_", defaultNorm, "person_", "person")) {
            if (normTable.startsWith(prefix) && normTable.length() > prefix.length()) {
                String stripped = normTable.substring(prefix.length());
                candidateTags.add(stripped);
                candidateTags.add(DynamicXmlUnmarshaller.singularize(stripped));
                candidateTags.add(DynamicXmlUnmarshaller.pluralize(stripped));
            }
        }

        // Special composite entity aliases
        if (normTable.contains("name")) {
            candidateTags.addAll(List.of("namevalue", "name", "names", "namedetails"));
        } else if (normTable.contains("date")) {
            candidateTags.addAll(List.of("datevalue", "date", "dates", "datedetails", "datetype", "datetypelist"));
        } else if (normTable.contains("country")) {
            candidateTags.addAll(List.of("countryvalue", "country", "countries", "countrydetails", "countrylist", "countryname"));
        } else if (normTable.contains("role") || normTable.contains("occupation")) {
            candidateTags.addAll(List.of("occtitle", "roles", "role", "roledetail", "roledetails", "occupation", "occupationlist"));
        } else if (normTable.contains("sanction") || normTable.contains("reference")) {
            candidateTags.addAll(List.of("reference", "references", "sanctionsreferences", "referencename", "sanctionsreferenceslist"));
        } else if (normTable.contains("id") || normTable.contains("identification")) {
            candidateTags.addAll(List.of("idvalue", "id", "ids", "iddetails", "idnumbertypes"));
        } else if (normTable.contains("source")) {
            candidateTags.addAll(List.of("source", "sources", "sourcedetails"));
        } else if (normTable.contains("image")) {
            candidateTags.addAll(List.of("image", "images"));
        } else if (normTable.contains("description")) {
            candidateTags.addAll(List.of("description", "descriptions", "description1name", "description2name", "description3name"));
        } else if (normTable.contains("address")) {
            candidateTags.addAll(List.of("address", "addresses"));
        } else if (normTable.contains("relation")) {
            candidateTags.addAll(List.of("relationship", "relationshiplist"));
        }

        // Also inspect xmlField paths in tblMappings for explicit tag references
        for (FieldMapping m : tblMappings) {
            String f = m.getXmlField();
            if (f != null && !f.trim().isEmpty()) {
                String clean = f.replace("@", "").toLowerCase();
                String[] parts = clean.split("/");
                for (String part : parts) {
                    if (!part.equals(normEntity) && !part.equals(defaultNorm) && !part.isEmpty()) {
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
            String tagPrefix = xmlField.substring(0, xmlField.indexOf("/"));

            // Check if xmlRecord has qualified tagPrefix/@rawLeaf or tagPrefix/rawLeaf
            for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                String k = entry.getKey();
                if (k.equalsIgnoreCase(tagPrefix + "/@" + rawLeaf) || k.equalsIgnoreCase(tagPrefix + "/" + rawLeaf)) {
                    return entry.getValue();
                }
            }

            // Check if xmlRecord is an entity fragment matching tagPrefix or any path segment
            String entityTag = (String) xmlRecord.get("_entity_tag");
            if (entityTag == null) entityTag = (String) xmlRecord.get("_tag_name");

            boolean tagMatches = (entityTag == null)
                    || entityTag.equalsIgnoreCase(tagPrefix)
                    || DynamicXmlUnmarshaller.singularize(entityTag).equalsIgnoreCase(DynamicXmlUnmarshaller.singularize(tagPrefix))
                    || DynamicXmlUnmarshaller.pluralize(entityTag).equalsIgnoreCase(DynamicXmlUnmarshaller.pluralize(tagPrefix))
                    || tagPrefix.equalsIgnoreCase(this.defaultTargetTable)
                    || tagPrefix.equalsIgnoreCase("record");

            if (!tagMatches && entityTag != null) {
                String normEnt = entityTag.toLowerCase();
                String singEnt = DynamicXmlUnmarshaller.singularize(normEnt);
                String plurEnt = DynamicXmlUnmarshaller.pluralize(normEnt);
                for (String part : xmlField.replace("@", "").toLowerCase().split("/")) {
                    if (part.equals(normEnt) || part.equals(singEnt) || part.equals(plurEnt)) {
                        tagMatches = true;
                        break;
                    }
                }
            }

            if (!tagMatches && (xmlRecord.containsKey("parent_id") || xmlRecord.containsKey("parent_@id"))) {
                tagMatches = true;
            }

            if (tagMatches) {
                // If unqualified attribute exists, ensure no conflicting qualified tags from other entities exist
                val = xmlRecord.get(attrLeaf);
                if (val != null) {
                    boolean hasOtherTagAttr = xmlRecord.keySet().stream().anyMatch(k ->
                            k.endsWith("/@" + rawLeaf) && !k.toLowerCase().startsWith(tagPrefix.toLowerCase() + "/"));
                    if (!hasOtherTagAttr) {
                        return val;
                    }
                }

                val = xmlRecord.get(rawLeaf);
                if (val != null) {
                    boolean hasOtherTagAttr = xmlRecord.keySet().stream().anyMatch(k ->
                            k.endsWith("/@" + rawLeaf) && !k.toLowerCase().startsWith(tagPrefix.toLowerCase() + "/"));
                    if (!hasOtherTagAttr) {
                        return val;
                    }
                }

                // Check relative sub-paths (e.g. "NameValue/FirstName", "FirstName")
                String[] segments = xmlField.split("/");
                for (int i = 1; i < segments.length; i++) {
                    StringBuilder rel = new StringBuilder();
                    for (int j = i; j < segments.length; j++) {
                        if (rel.length() > 0) rel.append("/");
                        rel.append(segments[j]);
                    }
                    String relPath = rel.toString();
                    Object rVal = xmlRecord.get(relPath);
                    if (rVal != null) return rVal;
                    for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(relPath)) return entry.getValue();
                    }
                }

                // Check leaf
                for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                    String k = entry.getKey();
                    if (k.equalsIgnoreCase(rawLeaf) || k.equalsIgnoreCase(attrLeaf)) {
                        if (!k.contains("/") || k.toLowerCase().startsWith(tagPrefix.toLowerCase() + "/")) {
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
