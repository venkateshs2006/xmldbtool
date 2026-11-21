package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DiscoveredElement {
    private String path;
    private String name;
    private String type; // element, attribute
    private String parentPath;
    private String sampleValue;
    private String dataType;
    private long occurrenceCount;
    private String targetTable;
    private String targetColumn;
    private String constraintType;
    private String transform;

    public DiscoveredElement() {}

    public DiscoveredElement(String path, String name, String type, String parentPath, String sampleValue, String dataType) {
        this.path = path;
        this.name = name;
        this.type = type;
        this.parentPath = parentPath;
        this.sampleValue = sampleValue;
        this.dataType = dataType;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getParentPath() {
        return parentPath;
    }

    public void setParentPath(String parentPath) {
        this.parentPath = parentPath;
    }

    public String getSampleValue() {
        return sampleValue;
    }

    public void setSampleValue(String sampleValue) {
        this.sampleValue = sampleValue;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public long getOccurrenceCount() {
        return occurrenceCount;
    }

    public void setOccurrenceCount(long occurrenceCount) {
        this.occurrenceCount = occurrenceCount;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public String getTargetColumn() {
        return targetColumn;
    }

    public void setTargetColumn(String targetColumn) {
        this.targetColumn = targetColumn;
    }

    public String getConstraintType() {
        return constraintType;
    }

    public void setConstraintType(String constraintType) {
        this.constraintType = constraintType;
    }

    public String getTransform() {
        return transform;
    }

    public void setTransform(String transform) {
        this.transform = transform;
    }
}
