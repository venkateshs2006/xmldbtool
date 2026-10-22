package com.enterprise.xmldbtool.model;

public class DbTestResponse {
    private boolean success;
    private String message;
    private String databaseProductName;
    private String databaseProductVersion;

    public DbTestResponse() {}

    public DbTestResponse(boolean success, String message, String databaseProductName, String databaseProductVersion) {
        this.success = success;
        this.message = message;
        this.databaseProductName = databaseProductName;
        this.databaseProductVersion = databaseProductVersion;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getDatabaseProductName() { return databaseProductName; }
    public void setDatabaseProductName(String databaseProductName) { this.databaseProductName = databaseProductName; }

    public String getDatabaseProductVersion() { return databaseProductVersion; }
    public void setDatabaseProductVersion(String databaseProductVersion) { this.databaseProductVersion = databaseProductVersion; }
}
