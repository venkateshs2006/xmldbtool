package com.enterprise.xmldbtool.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dynamic row payload prepared by ItemProcessor for dynamic batch insertion.
 * Supports One-to-Many XML-to-Database table mappings where a single XML fragment
 * generates inserts across multiple target tables (e.g. parent orders + child order_items),
 * and multiple rows per target table for repeating collection elements.
 */
public class DatabaseRowPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private String targetTable;
    private Map<String, Object> columnValues = new LinkedHashMap<>();
    private Map<String, Object> originalXmlMap = new LinkedHashMap<>();

    // Multi-table payloads: TableName -> (ColumnName -> ColumnValue) (legacy 1-row compatibility)
    private Map<String, Map<String, Object>> multiTablePayloads = new LinkedHashMap<>();

    // Multi-table records: TableName -> List of (ColumnName -> ColumnValue) (multi-row batch insertion)
    private Map<String, List<Map<String, Object>>> multiTableRecords = new LinkedHashMap<>();

    public DatabaseRowPayload() {}

    public DatabaseRowPayload(String targetTable, Map<String, Object> columnValues) {
        this.targetTable = targetTable;
        if (columnValues != null) {
            this.columnValues.putAll(columnValues);
            this.multiTablePayloads.put(targetTable, new LinkedHashMap<>(columnValues));
            this.multiTableRecords.computeIfAbsent(targetTable, k -> new ArrayList<>()).add(new LinkedHashMap<>(columnValues));
        }
    }

    public DatabaseRowPayload(String targetTable, Map<String, Object> columnValues, Map<String, Object> originalXmlMap) {
        this.targetTable = targetTable;
        if (columnValues != null) {
            this.columnValues.putAll(columnValues);
            this.multiTablePayloads.put(targetTable, new LinkedHashMap<>(columnValues));
            this.multiTableRecords.computeIfAbsent(targetTable, k -> new ArrayList<>()).add(new LinkedHashMap<>(columnValues));
        }
        if (originalXmlMap != null) {
            this.originalXmlMap.putAll(originalXmlMap);
        }
    }

    public DatabaseRowPayload(Map<String, Map<String, Object>> multiTablePayloads, Map<String, Object> originalXmlMap) {
        if (multiTablePayloads != null) {
            this.multiTablePayloads.putAll(multiTablePayloads);
            for (Map.Entry<String, Map<String, Object>> entry : multiTablePayloads.entrySet()) {
                this.multiTableRecords.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(new LinkedHashMap<>(entry.getValue()));
            }
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
            this.multiTableRecords.computeIfAbsent(tableName, k -> new ArrayList<>()).add(new LinkedHashMap<>(values));
            this.multiTablePayloads.put(tableName, new LinkedHashMap<>(values));
            if (this.targetTable == null) {
                this.targetTable = tableName;
                this.columnValues.putAll(values);
            }
        }
    }

    public void setTableRecords(String tableName, List<Map<String, Object>> records) {
        if (tableName != null && records != null && !records.isEmpty()) {
            this.multiTableRecords.put(tableName, new ArrayList<>(records));
            this.multiTablePayloads.put(tableName, new LinkedHashMap<>(records.get(0)));
            if (this.targetTable == null) {
                this.targetTable = tableName;
                this.columnValues.putAll(records.get(0));
            }
        }
    }

    public String getTargetTable() {
        if (targetTable != null) return targetTable;
        if (!multiTableRecords.isEmpty()) {
            return multiTableRecords.keySet().iterator().next();
        }
        if (!multiTablePayloads.isEmpty()) {
            return multiTablePayloads.keySet().iterator().next();
        }
        return null;
    }

    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public Map<String, Object> getColumnValues() {
        if (!columnValues.isEmpty()) return columnValues;
        if (!multiTableRecords.isEmpty() && !multiTableRecords.values().iterator().next().isEmpty()) {
            return multiTableRecords.values().iterator().next().get(0);
        }
        if (!multiTablePayloads.isEmpty()) {
            return multiTablePayloads.values().iterator().next();
        }
        return columnValues;
    }

    public void setColumnValues(Map<String, Object> columnValues) { this.columnValues = columnValues; }

    public Map<String, List<Map<String, Object>>> getMultiTableRecords() {
        if (multiTableRecords.isEmpty() && !multiTablePayloads.isEmpty()) {
            for (Map.Entry<String, Map<String, Object>> entry : multiTablePayloads.entrySet()) {
                multiTableRecords.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(new LinkedHashMap<>(entry.getValue()));
            }
        }
        return multiTableRecords;
    }

    public void setMultiTableRecords(Map<String, List<Map<String, Object>>> records) {
        this.multiTableRecords = records != null ? new LinkedHashMap<>(records) : new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : this.multiTableRecords.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                this.multiTablePayloads.put(entry.getKey(), new LinkedHashMap<>(entry.getValue().get(0)));
            }
        }
    }

    public Map<String, Map<String, Object>> getMultiTablePayloads() {
        if (multiTablePayloads.isEmpty() && !multiTableRecords.isEmpty()) {
            for (Map.Entry<String, List<Map<String, Object>>> entry : multiTableRecords.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    multiTablePayloads.put(entry.getKey(), new LinkedHashMap<>(entry.getValue().get(0)));
                }
            }
        } else if (multiTablePayloads.isEmpty() && targetTable != null && !columnValues.isEmpty()) {
            multiTablePayloads.put(targetTable, new LinkedHashMap<>(columnValues));
        }
        return multiTablePayloads;
    }

    public void setMultiTablePayloads(Map<String, Map<String, Object>> multiTablePayloads) {
        this.multiTablePayloads = multiTablePayloads != null ? multiTablePayloads : new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : this.multiTablePayloads.entrySet()) {
            this.multiTableRecords.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(new LinkedHashMap<>(entry.getValue()));
        }
    }

    public Map<String, Object> getOriginalXmlMap() { return originalXmlMap; }
    public void setOriginalXmlMap(Map<String, Object> originalXmlMap) { this.originalXmlMap = originalXmlMap; }

    @Override
    public String toString() {
        return "DatabaseRowPayload{" +
                "targetTable='" + targetTable + '\'' +
                ", multiTableRecords=" + multiTableRecords +
                '}';
    }
}
