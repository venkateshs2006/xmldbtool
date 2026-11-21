package com.bnpp.model;

import java.io.Serializable;
import java.util.*;

/**
 * Dynamic row payload containing processed rows across the 4 database layers:
 * Layer 2 (staging_xml_records), Layer 3 (gs_<entity>), Layer 4 (canonical <entity>).
 */
public class DatabaseRowPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private String targetTable;
    private Map<String, Object> columnValues = new LinkedHashMap<>();
    private Map<String, Object> originalXmlMap = new LinkedHashMap<>();

    // Multi-table records: TableName -> List of (ColumnName -> ColumnValue)
    private Map<String, List<Map<String, Object>>> multiTableRecords = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> multiTablePayloads = new LinkedHashMap<>();

    // Explicit staging records: List of staging row attribute maps
    private List<Map<String, Object>> stagingRecords = new ArrayList<>();

    // Explicit golden source records: TableName -> List of column maps
    private Map<String, List<Map<String, Object>>> goldenSourceRecords = new LinkedHashMap<>();

    // Explicit canonical records: TableName -> List of column maps
    private Map<String, List<Map<String, Object>>> canonicalRecords = new LinkedHashMap<>();

    public DatabaseRowPayload() {}

    public DatabaseRowPayload(String targetTable, Map<String, Object> columnValues) {
        this.targetTable = targetTable;
        if (columnValues != null) {
            this.columnValues.putAll(columnValues);
            this.multiTablePayloads.put(targetTable, new LinkedHashMap<>(columnValues));
            this.multiTableRecords.computeIfAbsent(targetTable, k -> new ArrayList<>()).add(new LinkedHashMap<>(columnValues));
        }
    }

    public void addStagingRecord(Map<String, Object> stagingRow) {
        if (stagingRow != null && !stagingRow.isEmpty()) {
            this.stagingRecords.add(new LinkedHashMap<>(stagingRow));
        }
    }

    public List<Map<String, Object>> getStagingRecords() {
        return stagingRecords;
    }

    public void setStagingRecords(List<Map<String, Object>> stagingRecords) {
        this.stagingRecords = stagingRecords != null ? stagingRecords : new ArrayList<>();
    }

    public void addGoldenSourceRecord(String gsTable, Map<String, Object> values) {
        if (gsTable != null && values != null && !values.isEmpty()) {
            this.goldenSourceRecords.computeIfAbsent(gsTable, k -> new ArrayList<>()).add(new LinkedHashMap<>(values));
        }
    }

    public Map<String, List<Map<String, Object>>> getGoldenSourceRecords() {
        return goldenSourceRecords;
    }

    public void setGoldenSourceRecords(Map<String, List<Map<String, Object>>> goldenSourceRecords) {
        this.goldenSourceRecords = goldenSourceRecords != null ? goldenSourceRecords : new LinkedHashMap<>();
    }

    public void addCanonicalRecord(String table, Map<String, Object> values) {
        if (table != null && values != null && !values.isEmpty()) {
            this.canonicalRecords.computeIfAbsent(table, k -> new ArrayList<>()).add(new LinkedHashMap<>(values));
        }
    }

    public Map<String, List<Map<String, Object>>> getCanonicalRecords() {
        return canonicalRecords;
    }

    public void setCanonicalRecords(Map<String, List<Map<String, Object>>> canonicalRecords) {
        this.canonicalRecords = canonicalRecords != null ? canonicalRecords : new LinkedHashMap<>();
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

    public String getTargetTable() {
        if (targetTable != null) return targetTable;
        if (!multiTableRecords.isEmpty()) return multiTableRecords.keySet().iterator().next();
        return null;
    }

    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public Map<String, Object> getColumnValues() {
        if (!columnValues.isEmpty()) return columnValues;
        if (!multiTableRecords.isEmpty() && !multiTableRecords.values().iterator().next().isEmpty()) {
            return multiTableRecords.values().iterator().next().get(0);
        }
        return columnValues;
    }

    public void setColumnValues(Map<String, Object> columnValues) { this.columnValues = columnValues; }

    public Map<String, List<Map<String, Object>>> getMultiTableRecords() {
        return multiTableRecords;
    }

    public Map<String, List<Map<String, Object>>> getRowsByTable() {
        return getMultiTableRecords();
    }

    public void setMultiTableRecords(Map<String, List<Map<String, Object>>> records) {
        this.multiTableRecords = records != null ? new LinkedHashMap<>(records) : new LinkedHashMap<>();
    }

    public Map<String, Map<String, Object>> getMultiTablePayloads() {
        return multiTablePayloads;
    }

    public void setMultiTablePayloads(Map<String, Map<String, Object>> multiTablePayloads) {
        this.multiTablePayloads = multiTablePayloads != null ? multiTablePayloads : new LinkedHashMap<>();
    }

    public Map<String, Object> getOriginalXmlMap() { return originalXmlMap; }
    public void setOriginalXmlMap(Map<String, Object> originalXmlMap) { this.originalXmlMap = originalXmlMap; }
}
