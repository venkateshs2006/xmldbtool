package com.enterprise.xmldbtool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DbConnectionConfig {
    private String engine; // POSTGRESQL, ORACLE, H2
    private String url;
    private String username;
    private String password;
    private String schema;

    public DbConnectionConfig() {}

    public DbConnectionConfig(String engine, String url, String username, String password, String schema) {
        this.engine = engine;
        this.url = url;
        this.username = username;
        this.password = password;
        this.schema = schema;
    }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
}
