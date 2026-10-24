package com.enterprise.xmldbtool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DbConnectionConfig {
    private String engine; // POSTGRESQL, ORACLE, H2
    private String host;
    private Integer port;
    private String databaseName;
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

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getUrl() {
        if (url != null && !url.trim().isEmpty()) {
            return url.trim();
        }
        String h = (host != null && !host.trim().isEmpty()) ? host.trim() : "localhost";
        String db = (databaseName != null && !databaseName.trim().isEmpty()) ? databaseName.trim() : "enterprise_db";
        String eng = (engine != null) ? engine.trim().toUpperCase() : "POSTGRESQL";

        if ("ORACLE".equals(eng)) {
            int p = (port != null && port > 0) ? port : 1521;
            return "jdbc:oracle:thin:@//" + h + ":" + p + "/" + db;
        } else if ("H2".equals(eng)) {
            return "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        } else {
            // Default PostgreSQL
            int p = (port != null && port > 0) ? port : 5432;
            return "jdbc:postgresql://" + h + ":" + p + "/" + db;
        }
    }

    public void setUrl(String url) { this.url = url; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
}
