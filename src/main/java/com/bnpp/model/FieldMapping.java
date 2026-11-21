package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldMapping {
    private String xmlField;
    private String targetTable;
    private String targetColumn;
    private String constraintType; // PK, FK, NONE
    private String foreignKeyTable;
    private String foreignKeyColumn;
    private String transform; // NONE, UPPER, TO_INT, TO_LONG, TO_BOOLEAN, TO_DATE

    public FieldMapping() {}

    public FieldMapping(String xmlField, String targetTable, String targetColumn, String constraintType, String transform) {
        this.xmlField = xmlField;
        this.targetTable = targetTable;
        this.targetColumn = targetColumn;
        this.constraintType = constraintType;
        this.transform = transform;
    }

    public String getXmlField() {
        return xmlField;
    }

    public void setXmlField(String xmlField) {
        this.xmlField = xmlField;
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
        return constraintType != null ? constraintType : "NONE";
    }

    public void setConstraintType(String constraintType) {
        this.constraintType = constraintType;
    }

    public String getForeignKeyTable() {
        return foreignKeyTable;
    }

    public void setForeignKeyTable(String foreignKeyTable) {
        this.foreignKeyTable = foreignKeyTable;
    }

    public String getForeignKeyColumn() {
        return foreignKeyColumn;
    }

    public void setForeignKeyColumn(String foreignKeyColumn) {
        this.foreignKeyColumn = foreignKeyColumn;
    }

    public String getTransform() {
        return transform != null ? transform : "NONE";
    }

    public void setTransform(String transform) {
        this.transform = transform;
    }
}
