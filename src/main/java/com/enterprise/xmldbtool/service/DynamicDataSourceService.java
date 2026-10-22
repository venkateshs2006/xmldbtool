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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DynamicDataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceService.class);

    private final Map<String, HikariDataSource> poolCache = new ConcurrentHashMap<>();

    /**
     * Tests and validates connection parameters.
     */
    public DbTestResponse testConnection(DbConnectionConfig config) {
        try {
            DataSource ds = getOrCreateDataSource(config);
            try (Connection conn = ds.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                String prodName = meta.getDatabaseProductName();
                String prodVersion = meta.getDatabaseProductVersion();

                return new DbTestResponse(true, "Successfully connected to " + prodName, prodName, prodVersion);
            }
        } catch (Exception ex) {
            log.error("Failed to connect to database: {}", ex.getMessage());
            return new DbTestResponse(false, "Connection error: " + ex.getMessage(), null, null);
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
     * Gets or creates a pooled HikariDataSource for the target database.
     */
    public DataSource getOrCreateDataSource(DbConnectionConfig config) {
        String cacheKey = config.getUrl() + ":" + config.getUsername();
        return poolCache.computeIfAbsent(cacheKey, k -> {
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(config.getUrl());
            hikariConfig.setUsername(config.getUsername());
            hikariConfig.setPassword(config.getPassword() != null ? config.getPassword() : "");
            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setMinimumIdle(2);
            hikariConfig.setConnectionTimeout(10000);
            hikariConfig.setIdleTimeout(30000);
            hikariConfig.setPoolName("TargetDB-Pool-" + System.currentTimeMillis());

            // Auto-detect driver class
            if (config.getUrl().contains(":postgresql:")) {
                hikariConfig.setDriverClassName("org.postgresql.Driver");
            } else if (config.getUrl().contains(":oracle:")) {
                hikariConfig.setDriverClassName("oracle.jdbc.OracleDriver");
            }

            log.info("Initialized dynamic Hikari connection pool for target URL: {}", config.getUrl());
            return new HikariDataSource(hikariConfig);
        });
    }
}
