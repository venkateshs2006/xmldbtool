package com.enterprise.xmldbtool.batch;

import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.FieldMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

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

        for (String tableName : targetTables) {
            List<FieldMapping> tblMappings = tableMappings.get(tableName);
            if (tblMappings == null || tblMappings.isEmpty()) continue;

            boolean hasIdMapped = tblMappings.stream().anyMatch(m -> "id".equalsIgnoreCase(m.getTargetColumn()));

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

                    if (isValidRow(rowMap, hasIdMapped)) {
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

                if (isValidRow(rowMap, hasIdMapped)) {
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

    private boolean isValidRow(Map<String, Object> cols, boolean hasIdMapped) {
        if (cols == null || cols.isEmpty()) {
            return false;
        }
        if (cols.values().stream().allMatch(val -> val == null || (val instanceof String s && s.trim().isEmpty()))) {
            return false;
        }
        if (hasIdMapped) {
            Object idVal = cols.get("id");
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

        // 1. Check if any mapping has an explicit tag prefix in xmlField (e.g. "countrylist/@id", "person/@status", "entity/@id")
        for (FieldMapping m : tblMappings) {
            String f = m.getXmlField();
            if (f != null && f.contains("/")) {
                String tag = f.substring(0, f.indexOf("/")).toLowerCase().trim();
                Object list = xmlRecord.get("_entities_" + tag);
                if (list instanceof List<?> l && !l.isEmpty()) {
                    return (List<Map<String, Object>>) list;
                }
            }
        }

        // 2. Check by exact table name
        String normTable = tableName.toLowerCase().trim();
        Object list = xmlRecord.get("_entities_" + normTable);
        if (list instanceof List<?> l && !l.isEmpty()) {
            return (List<Map<String, Object>>) list;
        }

        // 3. Check by singular table name
        String singular = DynamicXmlUnmarshaller.singularize(normTable);
        list = xmlRecord.get("_entities_" + singular);
        if (list instanceof List<?> l && !l.isEmpty()) {
            return (List<Map<String, Object>>) list;
        }

        // 4. Fuzzy match against any _entities_* keys in xmlRecord
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

        // 3. If xmlField is qualified with enclosing tag (e.g. "record/@id", "catalog/@exportId", "person/name")
        if (xmlField.contains("/")) {
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
