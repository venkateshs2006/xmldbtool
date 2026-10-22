package com.enterprise.xmldbtool.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dynamic row payload prepared by ItemProcessor for dynamic batch insertion.
 * Supports One-to-Many XML-to-Database table mappings where a single XML fragment
 * generates inserts across multiple target tables (e.g. parent orders + child order_items).
 */
public class DatabaseRowPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private String targetTable;
    private Map<String, Object> columnValues = new LinkedHashMap<>();
    private Map<String, Object> originalXmlMap = new LinkedHashMap<>();

    // Multi-table payloads: TableName -> (ColumnName -> ColumnValue)
    private Map<String, Map<String, Object>> multiTablePayloads = new LinkedHashMap<>();

    public DatabaseRowPayload() {}

    public DatabaseRowPayload(String targetTable, Map<String, Object> columnValues) {
        this.targetTable = targetTable;
        if (columnValues != null) {
            this.columnValues.putAll(columnValues);
            this.multiTablePayloads.put(targetTable, new LinkedHashMap<>(columnValues));
        }
    }

    public DatabaseRowPayload(String targetTable, Map<String, Object> columnValues, Map<String, Object> originalXmlMap) {
        this.targetTable = targetTable;
        if (columnValues != null) {
            this.columnValues.putAll(columnValues);
            this.multiTablePayloads.put(targetTable, new LinkedHashMap<>(columnValues));
        }
        if (originalXmlMap != null) {
            this.originalXmlMap.putAll(originalXmlMap);
        }
    }

    public DatabaseRowPayload(Map<String, Map<String, Object>> multiTablePayloads, Map<String, Object> originalXmlMap) {
        if (multiTablePayloads != null) {
            this.multiTablePayloads.putAll(multiTablePayloads);
            if (!multiTablePayloads.isEmpty()) {
                Map.Entry<String, Map<String, Object>> first = multiTablePayloads.entrySet().iterator().next();
                this.targetTable = first.getKey();
                this.columnValues.putAll(first.getValue());
            }
        }
        if (originalXmlMap != null) {
            this.originalXmlMap.putAll(originalXmlMap);
        }
    }

    public void addTableRecord(String tableName, Map<String, Object> values) {
        if (tableName != null && values != null && !values.isEmpty()) {
            this.multiTablePayloads.computeIfAbsent(tableName, k -> new LinkedHashMap<>()).putAll(values);
            if (this.targetTable == null) {
                this.targetTable = tableName;
                this.columnValues.putAll(values);
            }
        }
    }

    public String getTargetTable() {
        if (targetTable != null) return targetTable;
        if (!multiTablePayloads.isEmpty()) {
            return multiTablePayloads.keySet().iterator().next();
        }
        return null;
    }

    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public Map<String, Object> getColumnValues() {
        if (!columnValues.isEmpty()) return columnValues;
        if (!multiTablePayloads.isEmpty()) {
            return multiTablePayloads.values().iterator().next();
        }
        return columnValues;
    }

    public void setColumnValues(Map<String, Object> columnValues) { this.columnValues = columnValues; }

    public Map<String, Map<String, Object>> getMultiTablePayloads() {
        if (multiTablePayloads.isEmpty() && targetTable != null && !columnValues.isEmpty()) {
            multiTablePayloads.put(targetTable, new LinkedHashMap<>(columnValues));
        }
        return multiTablePayloads;
    }

    public void setMultiTablePayloads(Map<String, Map<String, Object>> multiTablePayloads) {
        this.multiTablePayloads = multiTablePayloads;
    }

    public Map<String, Object> getOriginalXmlMap() { return originalXmlMap; }
    public void setOriginalXmlMap(Map<String, Object> originalXmlMap) { this.originalXmlMap = originalXmlMap; }

    @Override
    public String toString() {
        return "DatabaseRowPayload{" +
                "targetTable='" + targetTable + '\'' +
                ", multiTablePayloads=" + multiTablePayloads +
                '}';
    }
}
