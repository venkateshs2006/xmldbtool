package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldMapping {
    @JsonProperty("xmlField")
    private String xmlField;

    @JsonProperty("targetTable")
    private String targetTable;

    @JsonProperty("targetColumn")
    private String targetColumn;

    @JsonProperty("constraintType")
    private String constraintType; // PK, FK, NONE

    @JsonProperty("references")
    private String references;

    @JsonProperty("foreignKeyTable")
    private String foreignKeyTable;

    @JsonProperty("foreignKeyColumn")
    private String foreignKeyColumn;

    @JsonProperty("transform")
    private String transform; // NONE, UPPER, TO_INT, TO_LONG, TO_BOOLEAN, TO_DATE, TO_DECIMAL

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

    @JsonProperty("references")
    public String getReferences() {
        if (references != null && !references.trim().isEmpty()) return references.trim();
        if (foreignKeyTable != null && !foreignKeyTable.trim().isEmpty()) {
            return foreignKeyTable.trim() + "." + (foreignKeyColumn != null && !foreignKeyColumn.trim().isEmpty() ? foreignKeyColumn.trim() : "id");
        }
        return null;
    }

    @JsonProperty("references")
    public void setReferences(String references) {
        this.references = references;
    }

    @JsonProperty("foreignKeyTable")
    public String getForeignKeyTable() {
        if (foreignKeyTable != null && !foreignKeyTable.trim().isEmpty()) return foreignKeyTable.trim();
        if (references != null && !references.trim().isEmpty()) {
            String clean = references.trim();
            if (clean.contains(".")) {
                return clean.split("\\.", 2)[0].trim();
            } else if (clean.contains("(")) {
                return clean.substring(0, clean.indexOf('(')).trim();
            } else {
                return clean;
            }
        }
        return null;
    }

    @JsonProperty("foreignKeyTable")
    public void setForeignKeyTable(String foreignKeyTable) {
        this.foreignKeyTable = foreignKeyTable;
    }

    @JsonProperty("foreignKeyColumn")
    public String getForeignKeyColumn() {
        if (foreignKeyColumn != null && !foreignKeyColumn.trim().isEmpty()) return foreignKeyColumn.trim();
        if (references != null && !references.trim().isEmpty()) {
            String clean = references.trim();
            if (clean.contains(".")) {
                return clean.split("\\.", 2)[1].trim();
            } else if (clean.contains("(")) {
                return clean.substring(clean.indexOf('(') + 1, clean.indexOf(')')).trim();
            } else {
                return "id";
            }
        }
        return null;
    }

    @JsonProperty("foreignKeyColumn")
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
