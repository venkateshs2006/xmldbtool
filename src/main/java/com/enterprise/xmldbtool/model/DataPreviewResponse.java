package com.enterprise.xmldbtool.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DataPreviewResponse {
    private boolean success;
    private String message;
    private List<String> columns = new ArrayList<>();
    private List<Map<String, Object>> previewRows = new ArrayList<>();
    private String generatedSqlTemplate;

    // Multi-table previews: TableName -> TablePreviewData
    private Map<String, TablePreviewData> tablePreviews = new LinkedHashMap<>();

    public DataPreviewResponse() {}

    public DataPreviewResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }

    public List<Map<String, Object>> getPreviewRows() { return previewRows; }
    public void setPreviewRows(List<Map<String, Object>> previewRows) { this.previewRows = previewRows; }

    public String getGeneratedSqlTemplate() { return generatedSqlTemplate; }
    public void setGeneratedSqlTemplate(String generatedSqlTemplate) { this.generatedSqlTemplate = generatedSqlTemplate; }

    public Map<String, TablePreviewData> getTablePreviews() { return tablePreviews; }
    public void setTablePreviews(Map<String, TablePreviewData> tablePreviews) { this.tablePreviews = tablePreviews; }
}
