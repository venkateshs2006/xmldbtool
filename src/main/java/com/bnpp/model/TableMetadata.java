package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TableMetadata {
    private String tableName;
    private String tableType; // CANONICAL, GOLDEN_SOURCE, STAGING, AUDIT, GOVERNANCE
    private String entityName;
    private List<ColumnMetadata> columns = new ArrayList<>();
    private String primaryKeyColumn;
    private String ddlScript;
    private long estimatedRows;

    public TableMetadata() {}

    public TableMetadata(String tableName, String tableType, String entityName) {
        this.tableName = tableName;
        this.tableType = tableType;
        this.entityName = entityName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getTableType() {
        return tableType;
    }

    public void setTableType(String tableType) {
        this.tableType = tableType;
    }

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public List<ColumnMetadata> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnMetadata> columns) {
        this.columns = columns;
    }

    public String getPrimaryKeyColumn() {
        return primaryKeyColumn;
    }

    public void setPrimaryKeyColumn(String primaryKeyColumn) {
        this.primaryKeyColumn = primaryKeyColumn;
    }

    public String getDdlScript() {
        return ddlScript;
    }

    public void setDdlScript(String ddlScript) {
        this.ddlScript = ddlScript;
    }

    public long getEstimatedRows() {
        return estimatedRows;
    }

    public void setEstimatedRows(long estimatedRows) {
        this.estimatedRows = estimatedRows;
    }
}
