package com.enterprise.xmldbtool.model;

public class ColumnMetadata {
    private String columnName;
    private String dataType;
    private boolean nullable;
    private int columnSize;

    public ColumnMetadata() {}

    public ColumnMetadata(String columnName, String dataType, boolean nullable, int columnSize) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.nullable = nullable;
        this.columnSize = columnSize;
    }

    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public boolean isNullable() { return nullable; }
    public void setNullable(boolean nullable) { this.nullable = nullable; }

    public int getColumnSize() { return columnSize; }
    public void setColumnSize(int columnSize) { this.columnSize = columnSize; }
}
