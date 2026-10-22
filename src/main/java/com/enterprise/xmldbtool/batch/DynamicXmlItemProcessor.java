package com.enterprise.xmldbtool.batch;

import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.FieldMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dynamic ItemProcessor supporting One-to-Many XML-to-Database table mappings.
 * Transforms an unmarshalled XML Map<String, Object> into a DatabaseRowPayload
 * containing records routed to their respective target database tables.
 */
public class DynamicXmlItemProcessor implements ItemProcessor<Map<String, Object>, DatabaseRowPayload> {

    private static final Logger log = LoggerFactory.getLogger(DynamicXmlItemProcessor.class);

    private final String defaultTargetTable;
    private final List<FieldMapping> mappings;

    public DynamicXmlItemProcessor(String defaultTargetTable, List<FieldMapping> mappings) {
        this.defaultTargetTable = defaultTargetTable;
        this.mappings = mappings;
    }

    @Override
    public DatabaseRowPayload process(Map<String, Object> xmlRecord) throws Exception {
        if (xmlRecord == null || xmlRecord.isEmpty() || mappings == null || mappings.isEmpty()) {
            return null;
        }

        Map<String, Map<String, Object>> multiTableData = new LinkedHashMap<>();

        for (FieldMapping mapping : mappings) {
            String xmlField = mapping.getXmlField();
            String rowTargetTable = mapping.getTargetTable() != null && !mapping.getTargetTable().trim().isEmpty()
                    ? mapping.getTargetTable().trim() : this.defaultTargetTable;
            String targetCol = mapping.getTargetColumn();

            if (xmlField != null && targetCol != null && !targetCol.trim().isEmpty() && rowTargetTable != null) {
                Object rawValue = resolveXmlValue(xmlRecord, xmlField, targetCol);
                Object transformedValue = applyTransform(rawValue, mapping.getTransform());
                multiTableData.computeIfAbsent(rowTargetTable, k -> new LinkedHashMap<>()).put(targetCol, transformedValue);
            }
        }

        // Remove any target table payload if ALL mapped column values in this record are null or empty
        multiTableData.entrySet().removeIf(entry ->
                entry.getValue().values().stream().allMatch(val -> val == null || (val instanceof String s && s.trim().isEmpty()))
        );

        if (multiTableData.isEmpty()) {
            return null;
        }

        // Return multi-table dynamic database payload
        return new DatabaseRowPayload(multiTableData, xmlRecord);
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

        // 3. Attribute prefix toggle (e.g. "@region" vs "region")
        String altKey = xmlField.startsWith("@") ? xmlField.substring(1) : "@" + xmlField;
        val = xmlRecord.get(altKey);
        if (val != null) return val;
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(altKey)) {
                return entry.getValue();
            }
        }

        // 4. Normalized match (strip '@', '-', '_', case-insensitive)
        String normField = normalize(xmlField);
        for (Map.Entry<String, Object> entry : xmlRecord.entrySet()) {
            if (normalize(entry.getKey()).equals(normField)) {
                return entry.getValue();
            }
        }

        // 5. Semantic alias fallback based on target column name or xml field name
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

        return null;
    }

    private String normalize(String s) {
        return s == null ? "" : s.replace("@", "").replace("-", "").replace("_", "").toLowerCase();
    }
}
