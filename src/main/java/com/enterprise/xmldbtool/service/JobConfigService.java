package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.model.DbConnectionConfig;
import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.model.SavedJobConfig;
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
import java.util.Collections;
import java.util.List;

@Service
public class JobConfigService {

    private static final Logger log = LoggerFactory.getLogger(JobConfigService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JobConfigService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<SavedJobConfig> jobConfigRowMapper = new RowMapper<>() {
        @Override
        public SavedJobConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            SavedJobConfig config = new SavedJobConfig();
            config.setId(rs.getLong("id"));
            config.setJobName(rs.getString("job_name"));
            config.setDescription(rs.getString("description"));
            config.setSourceXmlPath(rs.getString("source_xml_path"));
            config.setRecordRootElement(rs.getString("record_root_element"));
            config.setTargetTable(rs.getString("target_table"));
            config.setChunkSize(rs.getInt("chunk_size"));
            config.setCronExpression(rs.getString("cron_expression"));
            config.setStatus(rs.getString("status"));
            config.setCreatedBy(rs.getString("created_by"));

            if (rs.getTimestamp("created_at") != null) {
                config.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            if (rs.getTimestamp("updated_at") != null) {
                config.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            if (rs.getTimestamp("last_run_at") != null) {
                config.setLastRunAt(rs.getTimestamp("last_run_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            config.setLastRunStatus(rs.getString("last_run_status"));
            long execId = rs.getLong("last_job_execution_id");
            if (!rs.wasNull()) {
                config.setLastJobExecutionId(execId);
            }

            // Parse JSON fields
            String dbJson = rs.getString("db_config_json");
            if (dbJson != null && !dbJson.trim().isEmpty()) {
                try {
                    config.setDbConfig(objectMapper.readValue(dbJson, DbConnectionConfig.class));
                } catch (Exception ex) {
                    log.warn("Failed to parse db_config_json for job config #{}: {}", config.getId(), ex.getMessage());
                }
            }

            String mappingsJson = rs.getString("mappings_json");
            if (mappingsJson != null && !mappingsJson.trim().isEmpty()) {
                try {
                    config.setMappings(objectMapper.readValue(mappingsJson, new TypeReference<List<FieldMapping>>() {}));
                } catch (Exception ex) {
                    log.warn("Failed to parse mappings_json for job config #{}: {}", config.getId(), ex.getMessage());
                }
            }

            String tableOrderJson = rs.getString("table_order_json");
            if (tableOrderJson != null && !tableOrderJson.trim().isEmpty()) {
                try {
                    config.setTableOrder(objectMapper.readValue(tableOrderJson, new TypeReference<List<String>>() {}));
                } catch (Exception ex) {
                    config.setTableOrder(Collections.emptyList());
                }
            }

            return config;
        }
    };

    public List<SavedJobConfig> getAllJobConfigs() {
        return jdbcTemplate.query("SELECT * FROM saved_job_configs ORDER BY id DESC", jobConfigRowMapper);
    }

    public SavedJobConfig getJobConfigById(Long id) {
        List<SavedJobConfig> list = jdbcTemplate.query("SELECT * FROM saved_job_configs WHERE id = ?", jobConfigRowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public SavedJobConfig saveJobConfig(SavedJobConfig config) {
        if (config.getJobName() == null || config.getJobName().trim().isEmpty()) {
            throw new IllegalArgumentException("Job Name is required.");
        }
        if (config.getSourceXmlPath() == null || config.getSourceXmlPath().trim().isEmpty()) {
            throw new IllegalArgumentException("Source XML Path is required.");
        }

        if (config.getId() != null) {
            SavedJobConfig existing = getJobConfigById(config.getId());
            if (existing != null) {
                return updateJobConfig(config.getId(), config);
            }
        }

        // Check if a job with the same name already exists to prevent duplicate key constraint violations
        List<SavedJobConfig> existingByName = jdbcTemplate.query(
                "SELECT * FROM saved_job_configs WHERE LOWER(job_name) = LOWER(?)",
                jobConfigRowMapper,
                config.getJobName().trim()
        );
        if (!existingByName.isEmpty()) {
            Long existingId = existingByName.get(0).getId();
            log.info("Job configuration with name '{}' already exists (ID: {}). Updating existing configuration.",
                    config.getJobName().trim(), existingId);
            return updateJobConfig(existingId, config);
        }

        try {
            String dbJson = objectMapper.writeValueAsString(config.getDbConfig());
            String mappingsJson = objectMapper.writeValueAsString(config.getMappings() != null ? config.getMappings() : Collections.emptyList());
            String tableOrderJson = objectMapper.writeValueAsString(config.getTableOrder() != null ? config.getTableOrder() : Collections.emptyList());

            String sql = "INSERT INTO saved_job_configs (" +
                    "job_name, description, source_xml_path, record_root_element, target_table, table_order_json, chunk_size, " +
                    "db_config_json, mappings_json, cron_expression, status, created_by) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(
                    sql,
                    config.getJobName().trim(),
                    config.getDescription(),
                    config.getSourceXmlPath().trim(),
                    config.getRecordRootElement() != null ? config.getRecordRootElement().trim() : "record",
                    config.getTargetTable(),
                    tableOrderJson,
                    config.getChunkSize() > 0 ? config.getChunkSize() : 1000,
                    dbJson,
                    mappingsJson,
                    config.getCronExpression(),
                    "READY",
                    config.getCreatedBy() != null ? config.getCreatedBy() : "admin"
            );

            return jdbcTemplate.queryForObject(
                    "SELECT * FROM saved_job_configs WHERE LOWER(job_name) = LOWER(?)",
                    jobConfigRowMapper,
                    config.getJobName().trim()
            );
        } catch (Exception ex) {
            log.error("Failed to persist job configuration: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error saving job configuration: " + ex.getMessage(), ex);
        }
    }

    public SavedJobConfig updateJobConfig(Long id, SavedJobConfig config) {
        SavedJobConfig existing = getJobConfigById(id);
        if (existing == null) {
            throw new IllegalArgumentException("Job configuration with ID " + id + " does not exist.");
        }

        try {
            String dbJson = config.getDbConfig() != null ? objectMapper.writeValueAsString(config.getDbConfig()) : objectMapper.writeValueAsString(existing.getDbConfig());
            String mappingsJson = config.getMappings() != null ? objectMapper.writeValueAsString(config.getMappings()) : objectMapper.writeValueAsString(existing.getMappings());
            String tableOrderJson = config.getTableOrder() != null ? objectMapper.writeValueAsString(config.getTableOrder()) : objectMapper.writeValueAsString(existing.getTableOrder());

            String sql = "UPDATE saved_job_configs SET " +
                    "job_name = ?, description = ?, source_xml_path = ?, record_root_element = ?, " +
                    "target_table = ?, table_order_json = ?, chunk_size = ?, db_config_json = ?, " +
                    "mappings_json = ?, cron_expression = ?, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE id = ?";

            jdbcTemplate.update(
                    sql,
                    config.getJobName() != null ? config.getJobName().trim() : existing.getJobName(),
                    config.getDescription() != null ? config.getDescription() : existing.getDescription(),
                    config.getSourceXmlPath() != null ? config.getSourceXmlPath().trim() : existing.getSourceXmlPath(),
                    config.getRecordRootElement() != null ? config.getRecordRootElement().trim() : existing.getRecordRootElement(),
                    config.getTargetTable() != null ? config.getTargetTable() : existing.getTargetTable(),
                    tableOrderJson,
                    config.getChunkSize() > 0 ? config.getChunkSize() : existing.getChunkSize(),
                    dbJson,
                    mappingsJson,
                    config.getCronExpression() != null ? config.getCronExpression() : existing.getCronExpression(),
                    id
            );

            return getJobConfigById(id);
        } catch (Exception ex) {
            throw new RuntimeException("Error updating job configuration: " + ex.getMessage(), ex);
        }
    }

    public void updateExecutionStatus(Long id, Long executionId, String status) {
        try {
            jdbcTemplate.update(
                    "UPDATE saved_job_configs SET last_run_at = CURRENT_TIMESTAMP, last_run_status = ?, last_job_execution_id = ?, status = ? WHERE id = ?",
                    status, executionId, status, id
            );
        } catch (Exception ex) {
            log.warn("Failed to update execution status for job #{}: {}", id, ex.getMessage());
        }
    }

    public boolean deleteJobConfig(Long id) {
        int rows = jdbcTemplate.update("DELETE FROM saved_job_configs WHERE id = ?", id);
        return rows > 0;
    }
}
