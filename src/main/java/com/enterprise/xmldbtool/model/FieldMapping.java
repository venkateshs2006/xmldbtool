package com.enterprise.xmldbtool.model;

public class FieldMapping {
    private String xmlField;
    private String targetTable;
    private String targetColumn;
    @com.fasterxml.jackson.annotation.JsonAlias({"transformation", "transform"})
    private String transform = "NONE";
    private String constraintType = "NONE"; // NONE, PK, FK
    private String foreignKeyTable;
    private String foreignKeyColumn;

    public FieldMapping() {}

    public FieldMapping(String xmlField, String targetTable, String targetColumn, String transform) {
        this.xmlField = xmlField;
        this.targetTable = targetTable;
        this.targetColumn = targetColumn;
        this.transform = transform != null ? transform : "NONE";
    }

    public FieldMapping(String xmlField, String targetTable, String targetColumn, String transform, String constraintType) {
        this.xmlField = xmlField;
        this.targetTable = targetTable;
        this.targetColumn = targetColumn;
        this.transform = transform != null ? transform : "NONE";
        this.constraintType = constraintType != null ? constraintType : "NONE";
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

    public String getConstraintType() { return constraintType; }
    public void setConstraintType(String constraintType) { this.constraintType = constraintType; }

    public String getForeignKeyTable() { return foreignKeyTable; }
    public void setForeignKeyTable(String foreignKeyTable) { this.foreignKeyTable = foreignKeyTable; }

    public String getForeignKeyColumn() { return foreignKeyColumn; }
    public void setForeignKeyColumn(String foreignKeyColumn) { this.foreignKeyColumn = foreignKeyColumn; }
}
