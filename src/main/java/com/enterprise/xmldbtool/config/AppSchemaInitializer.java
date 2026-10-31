package com.enterprise.xmldbtool.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AppSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(AppSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public AppSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        log.info("Initializing PostgreSQL schema for User information and Job Details configuration...");
        createUsersTable();
        seedDefaultUsers();
        createJobDetailsTable();
        seedDefaultJobs();
        log.info("Application schema initialized successfully.");
    }

    private void createUsersTable() {
        try {
            // 1. Primary 'users' table to store user information
            String sqlUsers = "CREATE TABLE IF NOT EXISTS users (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "username VARCHAR(50) UNIQUE NOT NULL, " +
                    "password_hash VARCHAR(255) NOT NULL, " +
                    "full_name VARCHAR(100), " +
                    "email VARCHAR(150), " +
                    "role VARCHAR(50) NOT NULL, " +
                    "enabled BOOLEAN DEFAULT TRUE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sqlUsers);

            // Backward compatibility table/view for app_users
            String sqlAppUsers = "CREATE TABLE IF NOT EXISTS app_users (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "username VARCHAR(50) UNIQUE NOT NULL, " +
                    "password_hash VARCHAR(255) NOT NULL, " +
                    "full_name VARCHAR(100), " +
                    "email VARCHAR(150), " +
                    "role VARCHAR(50) NOT NULL, " +
                    "enabled BOOLEAN DEFAULT TRUE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sqlAppUsers);
            log.info("Verified/Created 'users' and 'app_users' tables in PostgreSQL.");
        } catch (Exception ex) {
            log.warn("Error creating users table: {}", ex.getMessage());
        }
    }

    private void seedDefaultUsers() {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
            if (count == null || count == 0) {
                String adminHash = hashPassword("admin123");
                String operatorHash = hashPassword("operator123");

                // Seed users table
                jdbcTemplate.update(
                        "INSERT INTO users (username, password_hash, full_name, email, role, enabled) VALUES (?, ?, ?, ?, ?, ?)",
                        "admin", adminHash, "System Administrator", "admin@enterprise.com", "ADMIN", true
                );

                jdbcTemplate.update(
                        "INSERT INTO users (username, password_hash, full_name, email, role, enabled) VALUES (?, ?, ?, ?, ?, ?)",
                        "operator", operatorHash, "Batch Operations Specialist", "operator@enterprise.com", "OPERATOR", true
                );

                // Also seed app_users table
                jdbcTemplate.update(
                        "INSERT INTO app_users (username, password_hash, full_name, email, role, enabled) VALUES (?, ?, ?, ?, ?, ?)",
                        "admin", adminHash, "System Administrator", "admin@enterprise.com", "ADMIN", true
                );

                jdbcTemplate.update(
                        "INSERT INTO app_users (username, password_hash, full_name, email, role, enabled) VALUES (?, ?, ?, ?, ?, ?)",
                        "operator", operatorHash, "Batch Operations Specialist", "operator@enterprise.com", "OPERATOR", true
                );

                log.info("Pre-seeded default users in PostgreSQL: 'admin' (role: ADMIN) and 'operator' (role: OPERATOR).");
            }
        } catch (Exception ex) {
            log.warn("Error seeding default users: {}", ex.getMessage());
        }
    }

    private void createJobDetailsTable() {
        try {
            // 2. Primary 'job_details' table to store configuration of XML and DB
            String sqlJobDetails = "CREATE TABLE IF NOT EXISTS job_details (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "job_name VARCHAR(100) UNIQUE NOT NULL, " +
                    "description VARCHAR(500), " +
                    "source_xml_path VARCHAR(500) NOT NULL, " +
                    "record_root_element VARCHAR(100) NOT NULL, " +
                    "target_table VARCHAR(100), " +
                    "table_order_json TEXT, " +
                    "chunk_size INT DEFAULT 1000, " +
                    "db_config_json TEXT NOT NULL, " +
                    "mappings_json TEXT NOT NULL, " +
                    "cron_expression VARCHAR(100), " +
                    "status VARCHAR(50) DEFAULT 'READY', " +
                    "created_by VARCHAR(50) DEFAULT 'admin', " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_run_at TIMESTAMP, " +
                    "last_run_status VARCHAR(50), " +
                    "last_job_execution_id BIGINT" +
                    ")";
            jdbcTemplate.execute(sqlJobDetails);

            // Backward compatibility table for saved_job_configs
            String sqlSavedJobs = "CREATE TABLE IF NOT EXISTS saved_job_configs (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "job_name VARCHAR(100) UNIQUE NOT NULL, " +
                    "description VARCHAR(500), " +
                    "source_xml_path VARCHAR(500) NOT NULL, " +
                    "record_root_element VARCHAR(100) NOT NULL, " +
                    "target_table VARCHAR(100), " +
                    "table_order_json TEXT, " +
                    "chunk_size INT DEFAULT 1000, " +
                    "db_config_json TEXT NOT NULL, " +
                    "mappings_json TEXT NOT NULL, " +
                    "cron_expression VARCHAR(100), " +
                    "status VARCHAR(50) DEFAULT 'READY', " +
                    "created_by VARCHAR(50) DEFAULT 'admin', " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_run_at TIMESTAMP, " +
                    "last_run_status VARCHAR(50), " +
                    "last_job_execution_id BIGINT" +
                    ")";
            jdbcTemplate.execute(sqlSavedJobs);
            log.info("Verified/Created 'job_details' and 'saved_job_configs' tables in PostgreSQL.");
        } catch (Exception ex) {
            log.warn("Error creating job_details table: {}", ex.getMessage());
        }
    }

    private void seedDefaultJobs() {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_details", Integer.class);
            if (count == null || count == 0) {
                String dbConfigJson = "{\"dbType\":\"POSTGRESQL\",\"host\":\"localhost\",\"port\":5432,\"databaseName\":\"enterprise_db\",\"username\":\"postgres\",\"password\":\"postgres\",\"schema\":\"public\"}";
                String mappingsJson = "[" +
                        "{\"xmlField\":\"record/@id\",\"targetTable\":\"products\",\"targetColumn\":\"id\",\"transformation\":\"NONE\"}," +
                        "{\"xmlField\":\"sku\",\"targetTable\":\"products\",\"targetColumn\":\"sku\",\"transformation\":\"NONE\"}," +
                        "{\"xmlField\":\"productName\",\"targetTable\":\"products\",\"targetColumn\":\"name\",\"transformation\":\"TRIM\"}," +
                        "{\"xmlField\":\"unitPrice\",\"targetTable\":\"products\",\"targetColumn\":\"price\",\"transformation\":\"NONE\"}" +
                        "]";
                String tableOrderJson = "[\"products\"]";

                // Seed job_details table
                jdbcTemplate.update(
                        "INSERT INTO job_details (job_name, description, source_xml_path, record_root_element, target_table, table_order_json, chunk_size, db_config_json, mappings_json, status, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        "Enterprise Products Ingestion",
                        "High-throughput batch ingestion of catalog products XML into PostgreSQL database",
                        "sample-data/large-sample.xml",
                        "record",
                        "products",
                        tableOrderJson,
                        1000,
                        dbConfigJson,
                        mappingsJson,
                        "READY",
                        "admin"
                );

                // Also seed saved_job_configs table
                jdbcTemplate.update(
                        "INSERT INTO saved_job_configs (job_name, description, source_xml_path, record_root_element, target_table, table_order_json, chunk_size, db_config_json, mappings_json, status, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        "Enterprise Products Ingestion",
                        "High-throughput batch ingestion of catalog products XML into PostgreSQL database",
                        "sample-data/large-sample.xml",
                        "record",
                        "products",
                        tableOrderJson,
                        1000,
                        dbConfigJson,
                        mappingsJson,
                        "READY",
                        "admin"
                );
                log.info("Pre-seeded default sample job configuration in PostgreSQL: 'Enterprise Products Ingestion'.");
            }
        } catch (Exception ex) {
            log.warn("Error seeding default jobs: {}", ex.getMessage());
        }
    }

    public static String hashPassword(String password) {
        if (password == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : encoded) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(password.hashCode());
        }
    }
}
