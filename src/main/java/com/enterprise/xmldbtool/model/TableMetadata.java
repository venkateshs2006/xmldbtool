package com.enterprise.xmldbtool.model;

import java.util.ArrayList;
import java.util.List;

public class TableMetadata {
    private String tableName;
    private String tableSchema;
    private List<ColumnMetadata> columns = new ArrayList<>();

    public TableMetadata() {}

    public TableMetadata(String tableName, String tableSchema, List<ColumnMetadata> columns) {
        this.tableName = tableName;
        this.tableSchema = tableSchema;
        this.columns = columns;
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getTableSchema() { return tableSchema; }
    public void setTableSchema(String tableSchema) { this.tableSchema = tableSchema; }

    public List<ColumnMetadata> getColumns() { return columns; }
    public void setColumns(List<ColumnMetadata> columns) { this.columns = columns; }
}
