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

                boolean tableRelevant = tblMappings.stream().anyMatch(m -> {
                    String xf = m.getXmlField();
                    if (xf != null) {
                        String clean = xf.replace("@", "").toLowerCase().replaceAll("[^a-zA-Z0-9/]", "");
                        String firstTag = clean.contains("/") ? clean.substring(0, clean.indexOf("/")) : clean;
                        if (firstTag.equals(normEntity) || firstTag.equals(singEntity) || firstTag.equals(plurEntity)
                                || firstTag.startsWith(normEntity) || firstTag.startsWith(singEntity)) {
                            return true;
                        }
                    }
                    String tt = m.getTargetTable();
                    if (tt != null) {
                        String normT = tt.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
                        if (normT.equals(normEntity) || normT.equals(singEntity) || normT.equals(plurEntity)
                                || normT.startsWith(normEntity) || normT.startsWith(singEntity)) {
                            return true;
                        }
                    }
                    return false;
                }) || tableName.toLowerCase().startsWith(normEntity) || tableName.toLowerCase().startsWith(singEntity);

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
                        String fkCol = this.defaultTargetTable.toLowerCase() + "_id";
                        if (!rowMap.containsKey(fkCol) || rowMap.get(fkCol) == null) {
                            Object parentId = xmlRecord.get(fkCol);
                            if (parentId == null) parentId = xmlRecord.get("id");
                            if (parentId == null) parentId = xmlRecord.get("@id");
                            if (parentId == null) parentId = xmlRecord.get("code");
                            if (parentId == null) parentId = xmlRecord.get("@code");
                            if (parentId == null && this.mappings != null) {
                                for (FieldMapping m : this.mappings) {
                                    if ("PK".equalsIgnoreCase(m.getConstraintType()) && m.getTargetTable() != null && m.getTargetTable().equalsIgnoreCase(this.defaultTargetTable)) {
                                        parentId = xmlRecord.get(m.getTargetColumn());
                                        if (parentId == null && m.getXmlField() != null) {
                                            parentId = resolveXmlValue(xmlRecord, m.getXmlField(), m.getTargetColumn());
                                        }
                                        if (parentId != null) break;
                                    }
                                }
                            }
                            if (parentId != null) {
                                rowMap.put(fkCol, parentId);
                            }
                        }
                    }

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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findEntityRecordsForTable(String tableName, List<FieldMapping> tblMappings, Map<String, Object> xmlRecord) {
        if (xmlRecord == null || tblMappings == null || tblMappings.isEmpty()) {
            return Collections.emptyList();
        }

        String normTable = tableName.toLowerCase().trim();
        String singular = DynamicXmlUnmarshaller.singularize(normTable);
        String plural = DynamicXmlUnmarshaller.pluralize(normTable);

        // 1. Check exact table name, singular, or plural in _entities_*
        Object list = xmlRecord.get("_entities_" + normTable);
        if (list instanceof List<?> l && !l.isEmpty()) {
            return (List<Map<String, Object>>) list;
        }
        list = xmlRecord.get("_entities_" + singular);
        if (list instanceof List<?> l && !l.isEmpty()) {
            return (List<Map<String, Object>>) list;
        }
        list = xmlRecord.get("_entities_" + plural);
        if (list instanceof List<?> l && !l.isEmpty()) {
            return (List<Map<String, Object>>) list;
        }

        // 2. Check if sub-tag column contains raw XML (e.g. <addresses> inside person_flat) or JSON
        for (String key : List.of(normTable, singular, plural,
                "_subtag_xml_" + normTable, "_subtag_xml_" + singular, "_subtag_xml_" + plural,
                "_subtag_" + normTable, "_subtag_" + singular, "_subtag_" + plural)) {
            Object subTagVal = xmlRecord.get(key);
            if (subTagVal instanceof String str && !str.trim().isEmpty()) {
                String trimmed = str.trim();
                if (trimmed.startsWith("<")) {
                    List<Map<String, Object>> parsed = parseRawXmlSubTagEntities(trimmed, normTable, xmlRecord);
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
            } else if (subTagVal instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
                return (List<Map<String, Object>>) subTagVal;
            }
        }

        // 2. If table is main entity and no direct _entities_* was found, it's a single record
        String defaultNorm = this.defaultTargetTable != null ? this.defaultTargetTable.toLowerCase().trim() : "record";
        if (normTable.equals(defaultNorm) || normTable.equals("record") || normTable.equals("records")) {
            return Collections.emptyList();
        }

        // 3. Strip entity prefix (e.g. "order_item" or "orderitem" -> "item", "person_name" -> "name")
        String prefixRegex = "^" + java.util.regex.Pattern.quote(defaultNorm) + "_?";
        String stripped = normTable.replaceFirst(prefixRegex, "").replace("_", "");
        if (!stripped.isEmpty() && !stripped.equals(normTable.replace("_", ""))) {
            list = xmlRecord.get("_entities_" + stripped);
            if (list instanceof List<?> l && !l.isEmpty()) {
                return (List<Map<String, Object>>) list;
            }
            // Check composite child entities (e.g. namevalue for personname, occtitle for personrole)
            for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                if (entry.getKey().startsWith("_entities_") && entry.getValue() instanceof List<?> l && !l.isEmpty()) {
                    String entTag = entry.getKey().substring("_entities_".length());
                    if (entTag.contains(stripped) || stripped.contains(entTag)
                            || (stripped.contains("name") && entTag.equals("namevalue"))
                            || (stripped.contains("role") && entTag.equals("occtitle"))
                            || (stripped.contains("date") && entTag.equals("datevalue"))
                            || (stripped.contains("country") && entTag.equals("countryvalue"))
                            || (stripped.contains("id") && (entTag.equals("idvalue") || entTag.equals("id") || entTag.equals("ids")))
                            || (stripped.contains("sanctions") && (entTag.equals("reference") || entTag.equals("references")))
                            || (stripped.contains("reference") && (entTag.equals("reference") || entTag.equals("references")))) {
                        return (List<Map<String, Object>>) entry.getValue();
                    }
                }
            }
        }

        // 4. Check if any mapping has an explicit tag prefix that corresponds to this child table
        for (FieldMapping m : tblMappings) {
            String f = m.getXmlField();
            if (f != null && !f.trim().isEmpty()) {
                String tag = f.contains("/") ? f.substring(0, f.indexOf("/")).toLowerCase().trim() : f.toLowerCase().trim();
                if (tag.startsWith("@")) tag = tag.substring(1);
                if (normTable.contains(tag) || tag.contains(normTable) || (!stripped.isEmpty() && (stripped.contains(tag) || tag.contains(stripped)))) {
                    list = xmlRecord.get("_entities_" + tag);
                    if (list instanceof List<?> l && !l.isEmpty()) {
                        return (List<Map<String, Object>>) list;
                    }
                }
            }
        }

        // 5. Fuzzy match against any _entities_* keys in xmlRecord
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            if (entry.getKey().startsWith("_entities_") && entry.getValue() instanceof List<?> l && !l.isEmpty()) {
                String entTag = entry.getKey().substring("_entities_".length());
                if (normTable.contains(entTag) || entTag.contains(singular)) {
                    return (List<Map<String, Object>>) entry.getValue();
                }
            }
        }

        return Collections.emptyList();
    }

    /**
     * Parses nested raw XML fragments stored in flat tables (e.g. <addresses><address id="1">... inside person_flat)
     * and extracts repeating child entity maps for multi-table relational projection.
     */
    public static List<Map<String, Object>> parseRawXmlSubTagEntities(String rawXml, String expectedEntityTag, Map<String, Object> parentRecord) {
        if (rawXml == null || rawXml.trim().isEmpty() || !rawXml.trim().startsWith("<")) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            XMLStreamReader reader = SHARED_XML_FACTORY.createXMLStreamReader(new StringReader(rawXml));
            int depth = 0;
            Map<String, Object> currentEntity = null;
            int entityDepth = -1;
            StringBuilder textBuffer = new StringBuilder();

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    depth++;
                    textBuffer.setLength(0);

                    boolean isStartOfEntity = false;
                    if (currentEntity == null) {
                        String norm = localName.toLowerCase();
                        String normExpected = expectedEntityTag != null ? expectedEntityTag.toLowerCase() : "";
                        String singExpected = DynamicXmlUnmarshaller.singularize(normExpected);
                        String plurExpected = DynamicXmlUnmarshaller.pluralize(normExpected);
                        boolean matchesExpected = !normExpected.isEmpty() && (norm.equals(normExpected) || norm.equals(singExpected) || norm.equals(plurExpected));
                        if (matchesExpected || (expectedEntityTag == null && depth == 2) || (depth == 2 && normExpected.isEmpty())) {
                            isStartOfEntity = true;
                        }
                    }

                    if (isStartOfEntity) {
                        currentEntity = new LinkedHashMap<>();
                        entityDepth = depth;
                        if (parentRecord != null) {
                            for (Map.Entry<String, Object> pEntry : parentRecord.entrySet()) {
                                String pk = pEntry.getKey().toLowerCase();
                                if (pk.equals("id") || pk.equals("@id") || pk.endsWith("_id")) {
                                    currentEntity.put("parent_" + pk, pEntry.getValue());
                                }
                            }
                        }
                        int attrCount = reader.getAttributeCount();
                        for (int i = 0; i < attrCount; i++) {
                            String aName = reader.getAttributeLocalName(i);
                            String aVal = reader.getAttributeValue(i);
                            currentEntity.put("@" + aName, aVal);
                            currentEntity.put(aName, aVal);
                            currentEntity.put(localName + "/@" + aName, aVal);
                            currentEntity.put(localName + "/" + aName, aVal);
                        }
                    } else if (currentEntity != null && depth > entityDepth) {
                        int attrCount = reader.getAttributeCount();
                        for (int i = 0; i < attrCount; i++) {
                            String aName = reader.getAttributeLocalName(i);
                            String aVal = reader.getAttributeValue(i);
                            currentEntity.put("@" + aName, aVal);
                            currentEntity.put(aName, aVal);
                            currentEntity.put(localName + "/@" + aName, aVal);
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (!reader.isWhiteSpace()) {
                        textBuffer.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String localName = reader.getLocalName();
                    String text = textBuffer.toString().trim();
                    if (currentEntity != null && !text.isEmpty()) {
                        currentEntity.put(localName, text);
                        currentEntity.put(localName.toLowerCase(), text);
                    }
                    if (currentEntity != null && depth == entityDepth) {
                        results.add(currentEntity);
                        currentEntity = null;
                        entityDepth = -1;
                    }
                    depth--;
                }
            }
        } catch (Exception ex) {
            log.debug("Notice parsing raw XML sub-tag entities: {}", ex.getMessage());
        }
        return results;
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

        // 1. Direct key match
        Object val = xmlRecord.get(xmlField);
        if (val != null) return val;

        // 2. Case-insensitive key match
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(xmlField)) {
                return entry.getValue();
            }
        }

        // 3. If xmlField is qualified with enclosing tag (e.g. "record/@id", "catalog/@exportId", "person/name", or deep 10-level paths)
        if (xmlField.contains("/")) {
            String entityTag = (String) xmlRecord.get("_entity_tag");
            if (entityTag == null) entityTag = (String) xmlRecord.get("_tag_name");

            // Check if entity matches any tag in the multi-level hierarchy path (e.g. "Detail" in "Enterprise/.../Detail/DetailName")
            if (entityTag != null) {
                String normEnt = entityTag.toLowerCase();
                String singEnt = DynamicXmlUnmarshaller.singularize(normEnt);
                String plurEnt = DynamicXmlUnmarshaller.pluralize(normEnt);
                String[] segments = xmlField.split("/");

                for (int i = 0; i < segments.length; i++) {
                    String segNorm = segments[i].replace("@", "").toLowerCase();
                    if (segNorm.equals(normEnt) || segNorm.equals(singEnt) || segNorm.equals(plurEnt)) {
                        // Found matching entity segment in path: resolve relative path under this entity
                        if (i < segments.length - 1) {
                            StringBuilder rel = new StringBuilder();
                            for (int j = i + 1; j < segments.length; j++) {
                                if (rel.length() > 0) rel.append("/");
                                rel.append(segments[j]);
                            }
                            String relPath = rel.toString();
                            Object relVal = xmlRecord.get(relPath);
                            if (relVal != null) return relVal;
                            for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                                if (entry.getKey().equalsIgnoreCase(relPath)) {
                                    return entry.getValue();
                                }
                            }
                            // Also check leaf element
                            String leaf = segments[segments.length - 1].replace("@", "");
                            Object leafVal = xmlRecord.get(leaf);
                            if (leafVal != null) return leafVal;
                            for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                                if (entry.getKey().equalsIgnoreCase(leaf)) {
                                    return entry.getValue();
                                }
                            }
                        } else {
                            val = xmlRecord.get(segments[i]);
                            if (val != null) return val;
                        }
                    }
                }
            }

            String tagPrefix = xmlField.substring(0, xmlField.indexOf("/"));
            String rawAttr = xmlField.contains("/@")
                    ? xmlField.substring(xmlField.indexOf("/@") + 2)
                    : xmlField.substring(xmlField.indexOf("/") + 1);

            // Check if xmlRecord has the qualified tag/@attr or tag/attr
            for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                String k = entry.getKey();
                if (k.equalsIgnoreCase(tagPrefix + "/@" + rawAttr) || k.equalsIgnoreCase(tagPrefix + "/" + rawAttr)) {
                    return entry.getValue();
                }
            }

            boolean tagMatches = (entityTag == null) || entityTag.equalsIgnoreCase(tagPrefix)
                    || DynamicXmlUnmarshaller.singularize(entityTag).equalsIgnoreCase(DynamicXmlUnmarshaller.singularize(tagPrefix))
                    || DynamicXmlUnmarshaller.pluralize(entityTag).equalsIgnoreCase(DynamicXmlUnmarshaller.pluralize(tagPrefix))
                    || tagPrefix.equalsIgnoreCase(this.defaultTargetTable)
                    || tagPrefix.equalsIgnoreCase("record");

            if (tagMatches) {
                // If the record fragment has an unqualified attribute matching rawAttr, check if no conflicting qualified tags exist
                val = xmlRecord.get("@" + rawAttr);
                if (val != null) {
                    boolean hasOtherTagAttr = xmlRecord.keySet().stream().anyMatch(k ->
                            k.endsWith("/@" + rawAttr) && !k.toLowerCase().startsWith(tagPrefix.toLowerCase() + "/"));
                    if (!hasOtherTagAttr) {
                        return val;
                    }
                }

                val = xmlRecord.get(rawAttr);
                if (val != null) {
                    boolean hasOtherTagAttr = xmlRecord.keySet().stream().anyMatch(k ->
                            k.endsWith("/@" + rawAttr) && !k.toLowerCase().startsWith(tagPrefix.toLowerCase() + "/"));
                    if (!hasOtherTagAttr) {
                        return val;
                    }
                }

                // Check leaf element for nested entity records (e.g. "Person/NameDetails/Name/NameValue/FirstName" -> "FirstName")
                String leaf = xmlField.substring(xmlField.lastIndexOf("/") + 1).replace("@", "");
                if (!leaf.isEmpty()) {
                    val = xmlRecord.get(leaf);
                    if (val != null) return val;
                    for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(leaf)) {
                            return entry.getValue();
                        }
                    }
                }
            }

            // Do NOT fall back to stealing an attribute from an unrelated tag!
            return null;
        }

        // 4. Attribute prefix toggle (e.g. "@region" vs "region")
        String stripped = xmlField;
        String altKey = stripped.startsWith("@") ? stripped.substring(1) : "@" + stripped;
        val = xmlRecord.get(altKey);
        if (val != null) return val;
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(altKey)) {
                return entry.getValue();
            }
        }

        // 5. Look up by attribute name suffix (for unqualified fields)
        String rawAttr = stripped.startsWith("@") ? stripped.substring(1) : stripped;
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            if (entry.getKey().endsWith("/@" + rawAttr) || entry.getKey().equalsIgnoreCase("@" + rawAttr) || entry.getKey().equalsIgnoreCase(rawAttr)) {
                return entry.getValue();
            }
        }

        // 6. Normalized match (strip '@', '-', '_', case-insensitive)
        String normField = normalize(xmlField);
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            if (normalize(entry.getKey()).equals(normField)) {
                return entry.getValue();
            }
        }

        // 7. Semantic alias fallback based on target column name or xml field name
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

        return null;
    }

    private String normalize(String s) {
        return s == null ? "" : s.replace("@", "").replace("-", "").replace("_", "").toLowerCase();
    }
}
