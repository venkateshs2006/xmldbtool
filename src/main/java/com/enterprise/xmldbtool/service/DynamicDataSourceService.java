package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.model.ColumnMetadata;
import com.enterprise.xmldbtool.model.DbConnectionConfig;
import com.enterprise.xmldbtool.model.DbTestResponse;
import com.enterprise.xmldbtool.model.TableMetadata;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DynamicDataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceService.class);

    private final Map<String, HikariDataSource> poolCache = new ConcurrentHashMap<>();

    /**
     * Tests and validates connection parameters using a temporary connection pool.
     */
    public DbTestResponse testConnection(DbConnectionConfig config) {
        HikariDataSource testDs = null;
        try {
            HikariConfig hikariConfig = buildHikariConfig(config);
            hikariConfig.setMaximumPoolSize(2);
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setConnectionTimeout(4000);
            hikariConfig.setInitializationFailTimeout(4000);
            hikariConfig.setPoolName("TestConnection-Pool-" + System.currentTimeMillis());

            testDs = new HikariDataSource(hikariConfig);
            try (Connection conn = testDs.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                String prodName = meta.getDatabaseProductName();
                String prodVersion = meta.getDatabaseProductVersion();

                return new DbTestResponse(true, "Successfully connected to " + prodName, prodName, prodVersion);
            }
        } catch (Exception ex) {
            log.error("Failed to connect to database at {}: {}", config.getUrl(), ex.getMessage());
            String detail = ex.getMessage();
            if (ex.getCause() != null && ex.getCause().getMessage() != null) {
                detail += " -> " + ex.getCause().getMessage();
            }
            if (detail != null && (detail.contains("Connection refused") || detail.contains("Communications link failure"))) {
                detail += " (Target host/port unreachable. Check if database service is running, or use 'Preset: Embedded Test DB' for instant in-memory testing).";
            }
            return new DbTestResponse(false, detail, null, null);
        } finally {
            if (testDs != null && !testDs.isClosed()) {
                try {
                    testDs.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Creates a target database schema (e.g. CREATE SCHEMA IF NOT EXISTS "my_schema")
     * in the target database based on configuration parameters.
     */
    public DbTestResponse createSchema(DbConnectionConfig config) {
        if (config == null || config.getUrl() == null || config.getUrl().trim().isEmpty()) {
            return new DbTestResponse(false, "Database connection URL is required.", null, null);
        }
        String schema = config.getSchema() != null ? config.getSchema().trim() : "";
        if (schema.isEmpty()) {
            return new DbTestResponse(false, "Target schema name cannot be empty.", null, null);
        }
        if (!schema.matches("^[a-zA-Z0-9_]+$")) {
            return new DbTestResponse(false, "Invalid schema name '" + schema + "': only alphanumeric characters and underscores are allowed.", null, null);
        }

        HikariDataSource testDs = null;
        try {
            // Build base config without requiring schema to exist beforehand
            DbConnectionConfig baseConfig = new DbConnectionConfig();
            baseConfig.setUrl(config.getUrl());
            baseConfig.setUsername(config.getUsername());
            baseConfig.setPassword(config.getPassword());
            baseConfig.setEngine(config.getEngine());
            baseConfig.setHost(config.getHost());
            baseConfig.setPort(config.getPort());
            baseConfig.setDatabaseName(config.getDatabaseName());
            baseConfig.setSchema(null);

            HikariConfig hikariConfig = buildHikariConfig(baseConfig);
            hikariConfig.setMaximumPoolSize(2);
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setConnectionTimeout(5000);
            hikariConfig.setInitializationFailTimeout(5000);
            hikariConfig.setPoolName("CreateSchema-Pool-" + System.currentTimeMillis());

            testDs = new HikariDataSource(hikariConfig);
            try (Connection conn = testDs.getConnection();
                 Statement stmt = conn.createStatement()) {

                DatabaseMetaData meta = conn.getMetaData();
                String prodName = meta.getDatabaseProductName();
                String url = config.getUrl().toLowerCase();

                if (url.contains(":postgresql:") || (prodName != null && prodName.toLowerCase().contains("postgresql"))) {
                    String ddl = "CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"";
                    stmt.execute(ddl);
                    log.info("Executed PostgreSQL DDL: {}", ddl);
                } else if (url.contains(":h2:") || (prodName != null && prodName.toLowerCase().contains("h2"))) {
                    String ddl = "CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"";
                    stmt.execute(ddl);
                    log.info("Executed H2 DDL: {}", ddl);
                } else if (url.contains(":oracle:") || (prodName != null && prodName.toLowerCase().contains("oracle"))) {
                    boolean exists = false;
                    try (ResultSet rs = stmt.executeQuery("SELECT USERNAME FROM ALL_USERS WHERE USERNAME = '" + schema.toUpperCase() + "'")) {
                        if (rs.next()) {
                            exists = true;
                        }
                    } catch (Exception ignored) {}

                    if (exists) {
                        log.info("Oracle schema (user) '{}' already exists in ALL_USERS.", schema.toUpperCase());
                    } else {
                        try {
                            String pwd = config.getPassword() != null && !config.getPassword().trim().isEmpty() ? config.getPassword() : "oracle";
                            stmt.execute("CREATE USER " + schema.toUpperCase() + " IDENTIFIED BY \"" + pwd + "\"");
                            stmt.execute("GRANT CONNECT, RESOURCE TO " + schema.toUpperCase());
                            log.info("Created Oracle user/schema '{}'.", schema.toUpperCase());
                        } catch (Exception oraEx) {
                            log.warn("Notice during Oracle CREATE USER (schema will be referenced via CURRENT_SCHEMA): {}", oraEx.getMessage());
                        }
                    }
                } else {
                    stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
                }

                return new DbTestResponse(true, "Target schema '" + schema + "' created successfully (or already exists) in " + prodName + ".", prodName, meta.getDatabaseProductVersion());
            }
        } catch (Exception ex) {
            log.error("Failed to create target schema '{}' at {}: {}", schema, config.getUrl(), ex.getMessage());
            String detail = ex.getMessage();
            if (ex.getCause() != null && ex.getCause().getMessage() != null) {
                detail += " -> " + ex.getCause().getMessage();
            }
            return new DbTestResponse(false, "Failed to create schema '" + schema + "': " + detail, null, null);
        } finally {
            if (testDs != null && !testDs.isClosed()) {
                try {
                    testDs.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Retrieves database schema (tables and columns) from target database metadata or information_schema.
     */
    public List<TableMetadata> retrieveDatabaseSchema(DbConnectionConfig config) throws Exception {
        DataSource ds = getOrCreateDataSource(config);
        List<TableMetadata> tables = new ArrayList<>();

        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String schemaPattern = config.getSchema() != null && !config.getSchema().trim().isEmpty()
                    ? config.getSchema() : null;

            // Retrieve tables
            String[] types = new String[]{"TABLE", "VIEW"};
            try (ResultSet rs = meta.getTables(null, schemaPattern, "%", types)) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String tableSchema = rs.getString("TABLE_SCHEM");

                    // Filter out internal system tables
                    if (tableName != null && !tableName.startsWith("SYSTEM_") && !tableName.startsWith("pg_") && !tableName.equalsIgnoreCase("migration_errors")) {
                        TableMetadata table = new TableMetadata(tableName, tableSchema, new ArrayList<>());
                        tables.add(table);
                    }
                }
            }

            // Retrieve columns for each discovered table
            for (TableMetadata table : tables) {
                try (ResultSet rsCols = meta.getColumns(null, table.getTableSchema(), table.getTableName(), "%")) {
                    while (rsCols.next()) {
                        String colName = rsCols.getString("COLUMN_NAME");
                        String typeName = rsCols.getString("TYPE_NAME");
                        int isNullableInt = rsCols.getInt("NULLABLE");
                        int colSize = rsCols.getInt("COLUMN_SIZE");

                        table.getColumns().add(new ColumnMetadata(
                                colName,
                                typeName != null ? typeName : "VARCHAR",
                                isNullableInt != DatabaseMetaData.columnNoNulls,
                                colSize
                        ));
                    }
                }
            }
        }

        return tables;
    }

    /**
     * Builds standard HikariConfig for the given DbConnectionConfig.
     */
    public HikariConfig buildHikariConfig(DbConnectionConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword() != null ? config.getPassword() : "");

        if (config.getSchema() != null && !config.getSchema().trim().isEmpty()) {
            hikariConfig.setSchema(config.getSchema().trim());
        }

        // Auto-detect driver class and optimizations
        String url = config.getUrl().toLowerCase();
        if (url.contains(":postgresql:")) {
            hikariConfig.setDriverClassName("org.postgresql.Driver");
            // Allows PostgreSQL to infer parameter types without casting errors (e.g. timestamps, uuids, ints)
            hikariConfig.addDataSourceProperty("stringtype", "unspecified");
        } else if (url.contains(":oracle:")) {
            hikariConfig.setDriverClassName("oracle.jdbc.OracleDriver");
        } else if (url.contains(":h2:")) {
            hikariConfig.setDriverClassName("org.h2.Driver");
        }

        return hikariConfig;
    }

    /**
     * Gets or creates a pooled HikariDataSource for the target database.
     */
    public DataSource getOrCreateDataSource(DbConnectionConfig config) {
        String cacheKey = config.getUrl() + ":" + config.getUsername();
        return poolCache.computeIfAbsent(cacheKey, k -> {
            HikariConfig hikariConfig = buildHikariConfig(config);
            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setMinimumIdle(2);
            hikariConfig.setConnectionTimeout(10000);
            hikariConfig.setIdleTimeout(30000);
            hikariConfig.setPoolName("TargetDB-Pool-" + System.currentTimeMillis());

            log.info("Initialized dynamic Hikari connection pool for target URL: {}", config.getUrl());
            return new HikariDataSource(hikariConfig);
        });
    }
}
