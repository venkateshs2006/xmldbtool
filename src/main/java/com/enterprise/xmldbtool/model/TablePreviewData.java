package com.enterprise.xmldbtool.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TablePreviewData {
    private String tableName;
    private List<String> columns = new ArrayList<>();
    private List<Map<String, Object>> rows = new ArrayList<>();
    private String sqlTemplate;

    public TablePreviewData() {}

    public TablePreviewData(String tableName, List<String> columns, List<Map<String, Object>> rows, String sqlTemplate) {
        this.tableName = tableName;
        this.columns = columns;
        this.rows = rows;
        this.sqlTemplate = sqlTemplate;
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }

    public List<Map<String, Object>> getRows() { return rows; }
    public void setRows(List<Map<String, Object>> rows) { this.rows = rows; }

    public String getSqlTemplate() { return sqlTemplate; }
    public void setSqlTemplate(String sqlTemplate) { this.sqlTemplate = sqlTemplate; }
}
