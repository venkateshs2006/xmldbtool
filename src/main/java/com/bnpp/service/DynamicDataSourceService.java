package com.bnpp.service;

import com.bnpp.model.DbConnectionConfig;
import com.bnpp.model.DbTestResponse;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DynamicDataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceService.class);

    private final Map<String, HikariDataSource> poolCache = new ConcurrentHashMap<>();
    private final Map<String, Long> failureBackoff = new ConcurrentHashMap<>();

    public DbTestResponse testConnection(DbConnectionConfig config) {
        if (config == null || config.getUrl() == null || config.getUrl().trim().isEmpty()) {
            return new DbTestResponse(false, "Database connection URL is required.");
        }
        HikariDataSource testDs = null;
        try {
            HikariConfig hikariConfig = buildHikariConfig(config);
            hikariConfig.setMaximumPoolSize(2);
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setConnectionTimeout(4000);
            hikariConfig.setInitializationFailTimeout(4000);
            hikariConfig.setPoolName("TestConn-Pool-" + System.currentTimeMillis());

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
            return new DbTestResponse(false, detail, null, null);
        } finally {
            if (testDs != null && !testDs.isClosed()) {
                try {
                    testDs.close();
                } catch (Exception ignored) {}
            }
        }
    }

    public DbTestResponse createSchema(DbConnectionConfig config) {
        if (config == null || config.getUrl() == null || config.getUrl().trim().isEmpty()) {
            return new DbTestResponse(false, "Database connection URL is required.");
        }
        String schema = config.getSchema() != null ? config.getSchema().trim() : "";
        if (schema.isEmpty()) {
            return new DbTestResponse(false, "Target schema name cannot be empty.");
        }
        if (!schema.matches("^[a-zA-Z0-9_]+$")) {
            return new DbTestResponse(false, "Invalid schema name '" + schema + "': only alphanumeric characters and underscores are allowed.");
        }

        HikariDataSource testDs = null;
        try {
            DbConnectionConfig baseConfig = new DbConnectionConfig();
            baseConfig.setUrl(config.getUrl());
            baseConfig.setUsername(config.getUsername());
            baseConfig.setPassword(config.getPassword());
            baseConfig.setEngine(config.getEngine());
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

                String url = (config.getUrl() != null) ? config.getUrl().toLowerCase() : "";
                if (url.contains(":postgresql:") || url.contains(":h2:")) {
                    stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
                } else if (url.contains(":oracle:")) {
                    stmt.execute("CREATE USER " + schema.toUpperCase() + " IDENTIFIED BY \"" + config.getPassword() + "\"");
                    stmt.execute("GRANT CONNECT, RESOURCE TO " + schema.toUpperCase());
                } else {
                    stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
                }
            }
            return new DbTestResponse(true, "Schema '" + schema + "' created or verified successfully.");
        } catch (Exception ex) {
            log.error("Failed to create schema '{}': {}", schema, ex.getMessage());
            return new DbTestResponse(false, "Failed to create schema '" + schema + "': " + ex.getMessage());
        } finally {
            if (testDs != null && !testDs.isClosed()) {
                try {
                    testDs.close();
                } catch (Exception ignored) {}
            }
        }
    }

    public DataSource getOrCreateDataSource(DbConnectionConfig config) {
        if (config == null || config.getUrl() == null || config.getUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("Database URL is required.");
        }
        String schema = (config.getSchema() != null && !config.getSchema().trim().isEmpty())
                ? config.getSchema().trim().toLowerCase() : "public";
        String cacheKey = config.getUrl() + ":" + config.getUsername() + ":" + schema;

        HikariDataSource cached = poolCache.get(cacheKey);
        if (cached != null && !cached.isClosed()) {
            return cached;
        }

        Long lastFail = failureBackoff.get(cacheKey);
        if (lastFail != null && (System.currentTimeMillis() - lastFail) < 5_000) {
            throw new IllegalStateException("Connection pool to " + config.getUrl() + " recently failed.");
        }

        try {
            HikariDataSource ds = poolCache.computeIfAbsent(cacheKey, k -> {
                HikariConfig hikariConfig = buildHikariConfig(config);
                hikariConfig.setMaximumPoolSize(20);
                hikariConfig.setMinimumIdle(2);
                hikariConfig.setConnectionTimeout(10000);
                hikariConfig.setIdleTimeout(30000);
                hikariConfig.setPoolName("TargetDB-Pool-" + System.currentTimeMillis());

                log.info("Initialized dynamic Hikari connection pool for target URL: {} (Schema: {})", config.getUrl(), schema);
                return new HikariDataSource(hikariConfig);
            });
            failureBackoff.remove(cacheKey);
            return ds;
        } catch (Exception ex) {
            failureBackoff.put(cacheKey, System.currentTimeMillis());
            throw ex;
        }
    }

    public void resetAllPools() {
        for (Map.Entry<String, HikariDataSource> entry : poolCache.entrySet()) {
            HikariDataSource ds = entry.getValue();
            if (ds != null && !ds.isClosed()) {
                try {
                    ds.close();
                } catch (Exception ignored) {}
            }
        }
        poolCache.clear();
        log.info("Reset all cached dynamic connection pools.");
    }

    public void resetAllConnections() {
        resetAllPools();
    }

    public Map<String, Long> getTableRowCounts(DataSource dataSource, String schema, List<String> tableNames) {
        Map<String, Long> counts = new HashMap<>();
        if (dataSource == null || tableNames == null || tableNames.isEmpty()) {
            return counts;
        }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String tbl : tableNames) {
                if (tbl == null || tbl.trim().isEmpty()) continue;
                String qualified = (schema != null && !schema.trim().isEmpty()) ? "\"" + schema + "\"." + tbl : tbl;
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + qualified)) {
                    if (rs.next()) {
                        counts.put(tbl, rs.getLong(1));
                    }
                } catch (Exception ex) {
                    // Try without schema qualification or uppercase
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tbl)) {
                        if (rs.next()) {
                            counts.put(tbl, rs.getLong(1));
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ex) {
            log.debug("Error querying table row counts: {}", ex.getMessage());
        }
        return counts;
    }

    private HikariConfig buildHikariConfig(DbConnectionConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword() != null ? config.getPassword() : "");

        String url = config.getUrl().toLowerCase();
        String schema = (config.getSchema() != null && !config.getSchema().trim().isEmpty())
                ? config.getSchema().trim() : "public";

        if (url.contains(":postgresql:")) {
            hikariConfig.setDriverClassName("org.postgresql.Driver");
            hikariConfig.setConnectionInitSql("DO $$ BEGIN CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"; EXCEPTION WHEN OTHERS THEN NULL; END $$; SET search_path TO \"" + schema + "\", public;");
            hikariConfig.addDataSourceProperty("stringtype", "unspecified");
            hikariConfig.addDataSourceProperty("reWriteBatchedInserts", "true");
        } else if (url.contains(":oracle:")) {
            hikariConfig.setDriverClassName("oracle.jdbc.OracleDriver");
            hikariConfig.setConnectionInitSql("ALTER SESSION SET CURRENT_SCHEMA = " + schema.toUpperCase());
        } else if (url.contains(":h2:")) {
            hikariConfig.setDriverClassName("org.h2.Driver");
            hikariConfig.setConnectionInitSql("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"; SET SCHEMA \"" + schema + "\";");
        }

        return hikariConfig;
    }
}
