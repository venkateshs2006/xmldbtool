package com.enterprise.xmldbtool.model;

public class FieldMapping {
    private String xmlField;
    private String targetTable;
    private String targetColumn;
    @com.fasterxml.jackson.annotation.JsonAlias({"transformation", "transform"})
    private String transform = "NONE";

    public FieldMapping() {}

    public FieldMapping(String xmlField, String targetTable, String targetColumn, String transform) {
        this.xmlField = xmlField;
        this.targetTable = targetTable;
        this.targetColumn = targetColumn;
        this.transform = transform != null ? transform : "NONE";
    }

    public FieldMapping(String xmlField, String targetColumn, String transform) {
        this.xmlField = xmlField;
        this.targetColumn = targetColumn;
        this.transform = transform != null ? transform : "NONE";
    }

    public String getXmlField() { return xmlField; }
    public void setXmlField(String xmlField) { this.xmlField = xmlField; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public String getTargetColumn() { return targetColumn; }
    public void setTargetColumn(String targetColumn) { this.targetColumn = targetColumn; }

    public String getTransform() { return transform; }
    public void setTransform(String transform) { this.transform = transform; }

    public String getTransformation() { return transform; }
    public void setTransformation(String transformation) { this.transform = transformation; }
}
