package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TableRelationship {
    private String sourceTable;
    private String sourceColumn;
    private String targetTable;
    private String targetColumn;
    private String relationshipType; // ONE_TO_MANY, MANY_TO_ONE, etc.

    public TableRelationship() {}

    public TableRelationship(String sourceTable, String sourceColumn, String targetTable, String targetColumn) {
        this.sourceTable = sourceTable;
        this.sourceColumn = sourceColumn;
        this.targetTable = targetTable;
        this.targetColumn = targetColumn;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getSourceColumn() {
        return sourceColumn;
    }

    public void setSourceColumn(String sourceColumn) {
        this.sourceColumn = sourceColumn;
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

    public String getRelationshipType() {
        return relationshipType;
    }

    public void setRelationshipType(String relationshipType) {
        this.relationshipType = relationshipType;
    }
}
