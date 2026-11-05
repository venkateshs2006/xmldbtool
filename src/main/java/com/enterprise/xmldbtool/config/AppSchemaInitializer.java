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
        log.info("Initializing PostgreSQL schema for User information, Job Details, and Staging configuration...");
        createUsersTable();
        seedDefaultUsers();
        createJobDetailsTable();
        seedDefaultJobs();
        createStagingTable();
        createSchemaRegistryTable();
        seedSchemaRegistryVersions();
        log.info("Application schema initialized successfully.");
    }

    private void createStagingTable() {
        try {
            String sqlStaging = "CREATE TABLE IF NOT EXISTS staging_xml_records (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "job_execution_id BIGINT, " +
                    "entity_type VARCHAR(50) NOT NULL, " +
                    "record_identifier VARCHAR(100), " +
                    "record_action VARCHAR(50), " +
                    "record_date VARCHAR(50), " +
                    "raw_payload TEXT NOT NULL, " +
                    "payload_hash VARCHAR(64) NOT NULL, " +
                    "status VARCHAR(30) DEFAULT 'STAGED', " +
                    "error_message TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sqlStaging);
            try {
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_staging_hash ON staging_xml_records(entity_type, record_identifier, payload_hash)");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_staging_status ON staging_xml_records(status)");
            } catch (Exception ignored) {}
            log.info("Verified/Created 'staging_xml_records' table for memory-efficient staging ingestion.");
        } catch (Exception ex) {
            log.warn("Error creating staging_xml_records table: {}", ex.getMessage());
        }
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
                    "last_job_execution_id BIGINT, " +
                    "staged_pipeline BOOLEAN DEFAULT FALSE" +
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
                    "last_job_execution_id BIGINT, " +
                    "staged_pipeline BOOLEAN DEFAULT FALSE" +
                    ")";
            jdbcTemplate.execute(sqlSavedJobs);

            try {
                jdbcTemplate.execute("ALTER TABLE job_details ADD COLUMN IF NOT EXISTS staged_pipeline BOOLEAN DEFAULT FALSE");
                jdbcTemplate.execute("ALTER TABLE saved_job_configs ADD COLUMN IF NOT EXISTS staged_pipeline BOOLEAN DEFAULT FALSE");
            } catch (Exception ignored) {}

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

    private void createSchemaRegistryTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS schema_registry_versions (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "schema_name VARCHAR(100) NOT NULL, " +
                    "version VARCHAR(50) NOT NULL, " +
                    "entity_type VARCHAR(100) NOT NULL, " +
                    "root_element VARCHAR(100) DEFAULT 'record', " +
                    "mapping_config_json TEXT NOT NULL, " +
                    "table_order_json TEXT, " +
                    "ddl_script TEXT, " +
                    "description VARCHAR(500), " +
                    "status VARCHAR(50) DEFAULT 'ACTIVE', " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "created_by VARCHAR(50) DEFAULT 'admin', " +
                    "UNIQUE (schema_name, version)" +
                    ")";
            jdbcTemplate.execute(sql);
            log.info("Verified/Created 'schema_registry_versions' table in PostgreSQL.");
        } catch (Exception ex) {
            log.warn("Error creating schema_registry_versions table: {}", ex.getMessage());
        }
    }

    private void seedSchemaRegistryVersions() {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_registry_versions", Integer.class);
            if (count == null || count == 0) {
                // Seed PFA Sanctions v2.4
                String pfaMappingsJson = "[" +
                        "{\"xmlField\":\"Person/@id\",\"targetTable\":\"Person\",\"targetColumn\":\"person_id\",\"transform\":\"NONE\",\"constraintType\":\"PK\"}," +
                        "{\"xmlField\":\"Person/@action\",\"targetTable\":\"Person\",\"targetColumn\":\"action\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Person/@date\",\"targetTable\":\"Person\",\"targetColumn\":\"record_date\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Gender\",\"targetTable\":\"Person\",\"targetColumn\":\"gender\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"ActiveStatus\",\"targetTable\":\"Person\",\"targetColumn\":\"active_status\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Deceased\",\"targetTable\":\"Person\",\"targetColumn\":\"deceased\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Place/@name\",\"targetTable\":\"Person\",\"targetColumn\":\"birth_place\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"ProfileNotes\",\"targetTable\":\"Person\",\"targetColumn\":\"profile_notes\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"NameValue/FirstName\",\"targetTable\":\"PersonName\",\"targetColumn\":\"first_name\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"NameValue/Surname\",\"targetTable\":\"PersonName\",\"targetColumn\":\"surname\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Name/@NameType\",\"targetTable\":\"PersonName\",\"targetColumn\":\"name_type\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Person/@id\",\"targetTable\":\"PersonName\",\"targetColumn\":\"person_id\",\"transform\":\"NONE\",\"constraintType\":\"FK\",\"foreignKeyTable\":\"Person\",\"foreignKeyColumn\":\"person_id\"}," +
                        "{\"xmlField\":\"Roles/OccTitle\",\"targetTable\":\"PersonRole\",\"targetColumn\":\"occ_title\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Roles/OccTitle/@OccCat\",\"targetTable\":\"PersonRole\",\"targetColumn\":\"occ_cat\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Roles/@RoleType\",\"targetTable\":\"PersonRole\",\"targetColumn\":\"role_type\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Person/@id\",\"targetTable\":\"PersonRole\",\"targetColumn\":\"person_id\",\"transform\":\"NONE\",\"constraintType\":\"FK\",\"foreignKeyTable\":\"Person\",\"foreignKeyColumn\":\"person_id\"}," +
                        "{\"xmlField\":\"DateValue/@Year\",\"targetTable\":\"PersonDate\",\"targetColumn\":\"year\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Date/@DateType\",\"targetTable\":\"PersonDate\",\"targetColumn\":\"date_type\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Person/@id\",\"targetTable\":\"PersonDate\",\"targetColumn\":\"person_id\",\"transform\":\"NONE\",\"constraintType\":\"FK\",\"foreignKeyTable\":\"Person\",\"foreignKeyColumn\":\"person_id\"}," +
                        "{\"xmlField\":\"CountryName/@code\",\"targetTable\":\"CountryList\",\"targetColumn\":\"code\",\"transform\":\"NONE\",\"constraintType\":\"PK\"}," +
                        "{\"xmlField\":\"CountryName/@name\",\"targetTable\":\"CountryList\",\"targetColumn\":\"name\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"Occupation/@code\",\"targetTable\":\"OccupationList\",\"targetColumn\":\"code\",\"transform\":\"NONE\",\"constraintType\":\"PK\"}," +
                        "{\"xmlField\":\"Occupation/@name\",\"targetTable\":\"OccupationList\",\"targetColumn\":\"name\",\"transform\":\"NONE\"}" +
                        "]";
                String pfaTableOrder = "[\"CountryList\",\"OccupationList\",\"RelationshipList\",\"SanctionsReferencesList\",\"Person\",\"PersonName\",\"PersonRole\",\"PersonDate\",\"PersonDescription\",\"PersonCountry\",\"PersonIDNumber\",\"PersonSource\",\"PersonImage\"]";

                jdbcTemplate.update(
                        "INSERT INTO schema_registry_versions (schema_name, version, entity_type, root_element, mapping_config_json, table_order_json, description, status, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        "PFA-WorldCheck-Sanctions", "2.4.0", "Person", "Person",
                        pfaMappingsJson, pfaTableOrder,
                        "Standard PFA PEP and Sanctions normalized canonical schema with 1-to-N relationships and lookup tables",
                        "ACTIVE", "admin"
                );

                // Seed Catalog Products v1.0
                String catalogMappingsJson = "[" +
                        "{\"xmlField\":\"record/@id\",\"targetTable\":\"products\",\"targetColumn\":\"id\",\"transform\":\"NONE\",\"constraintType\":\"PK\"}," +
                        "{\"xmlField\":\"sku\",\"targetTable\":\"products\",\"targetColumn\":\"sku\",\"transform\":\"NONE\"}," +
                        "{\"xmlField\":\"productName\",\"targetTable\":\"products\",\"targetColumn\":\"name\",\"transform\":\"TRIM\"}," +
                        "{\"xmlField\":\"unitPrice\",\"targetTable\":\"products\",\"targetColumn\":\"price\",\"transform\":\"NONE\"}" +
                        "]";
                jdbcTemplate.update(
                        "INSERT INTO schema_registry_versions (schema_name, version, entity_type, root_element, mapping_config_json, table_order_json, description, status, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        "Enterprise-Catalog-Products", "1.0.0", "Product", "record",
                        catalogMappingsJson, "[\"products\"]",
                        "Canonical enterprise catalog products schema version 1.0",
                        "ACTIVE", "admin"
                );

                log.info("Pre-seeded Schema Registry versions: 'PFA-WorldCheck-Sanctions' (v2.4.0) and 'Enterprise-Catalog-Products' (v1.0.0).");
            }
        } catch (Exception ex) {
            log.warn("Error seeding schema registry versions: {}", ex.getMessage());
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
