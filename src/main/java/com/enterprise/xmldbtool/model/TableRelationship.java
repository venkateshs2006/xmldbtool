package com.enterprise.xmldbtool.model;

/**
 * Model representing a discovered or configured relationship between canonical tables.
 */
public class TableRelationship {
    private String parentTable;
    private String parentColumn;
    private String childTable;
    private String childColumn;
    private String relationshipType = "ONE_TO_MANY"; // ONE_TO_MANY, MANY_TO_ONE

    public TableRelationship() {}

    public TableRelationship(String parentTable, String parentColumn, String childTable, String childColumn) {
        this(parentTable, parentColumn, childTable, childColumn, "ONE_TO_MANY");
    }

    public TableRelationship(String parentTable, String parentColumn, String childTable, String childColumn, String relationshipType) {
        this.parentTable = parentTable;
        this.parentColumn = parentColumn;
        this.childTable = childTable;
        this.childColumn = childColumn;
        this.relationshipType = relationshipType != null ? relationshipType : "ONE_TO_MANY";
    }

    public String getParentTable() { return parentTable; }
    public void setParentTable(String parentTable) { this.parentTable = parentTable; }

    public String getParentColumn() { return parentColumn; }
    public void setParentColumn(String parentColumn) { this.parentColumn = parentColumn; }

    public String getChildTable() { return childTable; }
    public void setChildTable(String childTable) { this.childTable = childTable; }

    public String getChildColumn() { return childColumn; }
    public void setChildColumn(String childColumn) { this.childColumn = childColumn; }

    public String getRelationshipType() { return relationshipType; }
    public void setRelationshipType(String relationshipType) { this.relationshipType = relationshipType; }
}

