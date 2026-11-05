package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.model.SchemaRegistryVersion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service for registering, versioning, and managing XML schema configurations.
 * Allows users to track schema evolution across semantic versions (e.g., v1.0.0, v2.4.0)
 * and reload standardized canonical mappings dynamically.
 */
@Service
public class SchemaRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SchemaRegistryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<SchemaRegistryVersion> versionRowMapper = new RowMapper<>() {
        @Override
        public SchemaRegistryVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
            SchemaRegistryVersion ver = new SchemaRegistryVersion();
            ver.setId(rs.getLong("id"));
            ver.setSchemaName(rs.getString("schema_name"));
            ver.setVersion(rs.getString("version"));
            ver.setEntityType(rs.getString("entity_type"));
            ver.setRootElement(rs.getString("root_element"));
            ver.setDescription(rs.getString("description"));
            ver.setStatus(rs.getString("status"));
            ver.setDdlScript(rs.getString("ddl_script"));
            ver.setCreatedBy(rs.getString("created_by"));

            if (rs.getTimestamp("created_at") != null) {
                ver.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }

            String mappingJson = rs.getString("mapping_config_json");
            if (mappingJson != null && !mappingJson.trim().isEmpty()) {
                try {
                    ver.setMappings(objectMapper.readValue(mappingJson, new TypeReference<List<FieldMapping>>() {}));
                } catch (Exception ex) {
                    log.warn("Failed to parse mapping_config_json for schema version #{}: {}", ver.getId(), ex.getMessage());
                }
            }

            String tableOrderJson = rs.getString("table_order_json");
            if (tableOrderJson != null && !tableOrderJson.trim().isEmpty()) {
                try {
                    ver.setTableOrder(objectMapper.readValue(tableOrderJson, new TypeReference<List<String>>() {}));
                } catch (Exception ex) {
                    ver.setTableOrder(Collections.emptyList());
                }
            }

            return ver;
        }
    };

    public List<SchemaRegistryVersion> getAllVersions() {
        try {
            ensureTableExists();
            return jdbcTemplate.query("SELECT * FROM schema_registry_versions ORDER BY schema_name ASC, id DESC", versionRowMapper);
        } catch (Exception ex) {
            log.error("Failed to fetch schema registry versions: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<SchemaRegistryVersion> getVersionsForSchema(String schemaName) {
        try {
            ensureTableExists();
            return jdbcTemplate.query("SELECT * FROM schema_registry_versions WHERE LOWER(schema_name) = LOWER(?) ORDER BY id DESC", versionRowMapper, schemaName.trim());
        } catch (Exception ex) {
            log.error("Failed to fetch schema versions for '{}': {}", schemaName, ex.getMessage());
            return Collections.emptyList();
        }
    }

    public SchemaRegistryVersion getVersion(String schemaName, String version) {
        try {
            ensureTableExists();
            List<SchemaRegistryVersion> list = jdbcTemplate.query(
                    "SELECT * FROM schema_registry_versions WHERE LOWER(schema_name) = LOWER(?) AND LOWER(version) = LOWER(?)",
                    versionRowMapper, schemaName.trim(), version.trim());
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception ex) {
            log.error("Failed to fetch schema version '{}:{}': {}", schemaName, version, ex.getMessage());
            return null;
        }
    }

    public SchemaRegistryVersion getVersionById(Long id) {
        if (id == null) return null;
        try {
            ensureTableExists();
            List<SchemaRegistryVersion> list = jdbcTemplate.query(
                    "SELECT * FROM schema_registry_versions WHERE id = ?",
                    versionRowMapper, id);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception ex) {
            log.error("Failed to fetch schema version #{}: {}", id, ex.getMessage());
            return null;
        }
    }

    public SchemaRegistryVersion saveVersion(SchemaRegistryVersion version) {
        return registerVersion(version);
    }

    public SchemaRegistryVersion registerVersion(SchemaRegistryVersion version) {
        if (version.getSchemaName() == null || version.getSchemaName().trim().isEmpty()) {
            throw new IllegalArgumentException("Schema Name is required.");
        }
        if (version.getVersion() == null || version.getVersion().trim().isEmpty()) {
            throw new IllegalArgumentException("Version identifier is required (e.g. 1.0.0).");
        }

        ensureTableExists();

        try {
            String mappingJson = objectMapper.writeValueAsString(version.getMappings() != null ? version.getMappings() : Collections.emptyList());
            String tableOrderJson = objectMapper.writeValueAsString(version.getTableOrder() != null ? version.getTableOrder() : Collections.emptyList());

            // Check if existing version exists
            SchemaRegistryVersion existing = getVersion(version.getSchemaName(), version.getVersion());
            if (existing != null) {
                // Update existing
                String sql = "UPDATE schema_registry_versions SET entity_type = ?, root_element = ?, " +
                        "mapping_config_json = ?, table_order_json = ?, ddl_script = ?, description = ?, status = ? " +
                        "WHERE id = ?";
                jdbcTemplate.update(sql,
                        version.getEntityType() != null ? version.getEntityType() : existing.getEntityType(),
                        version.getRootElement() != null ? version.getRootElement() : existing.getRootElement(),
                        mappingJson,
                        tableOrderJson,
                        version.getDdlScript() != null ? version.getDdlScript() : existing.getDdlScript(),
                        version.getDescription() != null ? version.getDescription() : existing.getDescription(),
                        version.getStatus() != null ? version.getStatus() : existing.getStatus(),
                        existing.getId());
                return getVersion(version.getSchemaName(), version.getVersion());
            }

            // Insert new version
            String insertSql = "INSERT INTO schema_registry_versions (" +
                    "schema_name, version, entity_type, root_element, mapping_config_json, table_order_json, ddl_script, description, status, created_by) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.update(insertSql,
                    version.getSchemaName().trim(),
                    version.getVersion().trim(),
                    version.getEntityType() != null ? version.getEntityType().trim() : "Entity",
                    version.getRootElement() != null ? version.getRootElement().trim() : "record",
                    mappingJson,
                    tableOrderJson,
                    version.getDdlScript(),
                    version.getDescription(),
                    version.getStatus() != null ? version.getStatus() : "ACTIVE",
                    version.getCreatedBy() != null ? version.getCreatedBy() : "admin");

            log.info("Registered new schema version: '{}' (v{})", version.getSchemaName(), version.getVersion());
            return getVersion(version.getSchemaName(), version.getVersion());
        } catch (Exception ex) {
            log.error("Failed to register schema version: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error registering schema version: " + ex.getMessage(), ex);
        }
    }

    public synchronized void ensureTableExists() {
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
        } catch (Exception ex) {
            log.debug("Schema registry table check: {}", ex.getMessage());
        }
    }
}
