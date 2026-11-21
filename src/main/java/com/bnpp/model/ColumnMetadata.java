package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ColumnMetadata {
    private String columnName;
    private String dataType;
    private boolean primaryKey;
    private boolean foreignKey;
    private boolean nullable = true;
    private String foreignKeyTable;
    private String foreignKeyColumn;
    private String description;

    public ColumnMetadata() {}

    public ColumnMetadata(String columnName, String dataType, boolean primaryKey) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.primaryKey = primaryKey;
    }

    public ColumnMetadata(String columnName, String dataType, boolean primaryKey, boolean foreignKey, String foreignKeyTable, String foreignKeyColumn) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.primaryKey = primaryKey;
        this.foreignKey = foreignKey;
        this.foreignKeyTable = foreignKeyTable;
        this.foreignKeyColumn = foreignKeyColumn;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
    }

    public boolean isForeignKey() {
        return foreignKey;
    }

    public void setForeignKey(boolean foreignKey) {
        this.foreignKey = foreignKey;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
