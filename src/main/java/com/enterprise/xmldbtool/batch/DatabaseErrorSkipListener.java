package com.enterprise.xmldbtool.batch;

import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.MigrationErrorRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Step-scoped SkipListener that captures all failed rows across READ, PROCESS, and WRITE phases.
 * Failed rows are persisted as JSONB in the target DB 'migration_errors' table and kept in memory
 * for live telemetry polling.
 */
public class DatabaseErrorSkipListener implements SkipListener<Map<String, Object>, DatabaseRowPayload> {

    private static final Logger log = LoggerFactory.getLogger(DatabaseErrorSkipListener.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Long jobExecutionId;
    private final String stepName;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<MigrationErrorRecord> recentErrors = new CopyOnWriteArrayList<>();

    public DatabaseErrorSkipListener(DataSource dataSource, Long jobExecutionId, String stepName) {
        this.jdbcTemplate = dataSource != null ? new NamedParameterJdbcTemplate(dataSource) : null;
        this.jobExecutionId = jobExecutionId != null ? jobExecutionId : -1L;
        this.stepName = stepName != null ? stepName : "xmlIngestionStep";
        ensureErrorTableExists();
    }

    public DatabaseErrorSkipListener(NamedParameterJdbcTemplate jdbcTemplate, Long jobExecutionId, String stepName) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobExecutionId = jobExecutionId != null ? jobExecutionId : -1L;
        this.stepName = stepName != null ? stepName : "xmlIngestionStep";
        ensureErrorTableExists();
    }

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("Record skipped during XML StAX READ phase: {}", t.getMessage());
        persistError("READ", "{\"error\": \"Malformed XML or unreadable fragment\"}", t);
    }

    @Override
    public void onSkipInProcess(Map<String, Object> item, Throwable t) {
        log.warn("Record skipped during PROCESS phase: {}", t.getMessage());
        String payloadJson = serializeToJson(item);
        persistError("PROCESS", payloadJson, t);
    }

    @Override
    public void onSkipInWrite(DatabaseRowPayload item, Throwable t) {
        log.warn("Record skipped during WRITE phase (SQL/Type mismatch): {}", t.getMessage());
        Map<String, Object> data = item != null && !item.getColumnValues().isEmpty()
                ? item.getColumnValues()
                : (item != null ? item.getOriginalXmlMap() : Collections.emptyMap());
        String payloadJson = serializeToJson(data);
        persistError("WRITE", payloadJson, t);
    }

    private void persistError(String phase, String recordDataJson, Throwable t) {
        Throwable root = t != null ? org.springframework.core.NestedExceptionUtils.getMostSpecificCause(t) : null;
        String errorMsg = root != null ? root.getMessage() : (t != null ? t.getMessage() : "Unknown batch exception");
        String stackTrace = extractStackTrace(t);
        String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        MigrationErrorRecord record = new MigrationErrorRecord(
                jobExecutionId,
                stepName,
                phase,
                recordDataJson,
                errorMsg,
                nowStr
        );

        recentErrors.add(0, record); // Keep newest first
        if (recentErrors.size() > 100) {
            recentErrors.remove(recentErrors.size() - 1);
        }

        if (jdbcTemplate == null) {
            return;
        }

        try {
            // Check if PostgreSQL JSONB casting is applicable or standard text
            String insertSql = "INSERT INTO migration_errors (job_execution_id, step_name, phase, record_data, error_message, stack_trace, failed_at) " +
                    "VALUES (:jobId, :step, :phase, :recordData, :errorMsg, :stackTrace, CURRENT_TIMESTAMP)";

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("jobId", jobExecutionId)
                    .addValue("step", stepName)
                    .addValue("phase", phase)
                    .addValue("recordData", recordDataJson)
                    .addValue("errorMsg", errorMsg)
                    .addValue("stackTrace", stackTrace);

            jdbcTemplate.update(insertSql, params);
        } catch (Exception ex) {
            log.error("Failed to persist skip record into migration_errors table: {}", ex.getMessage());
        }
    }

    private void ensureErrorTableExists() {
        if (jdbcTemplate == null) return;
        try {
            String createTableSql = "CREATE TABLE IF NOT EXISTS migration_errors (" +
                    "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "job_execution_id BIGINT, " +
                    "step_name VARCHAR(100), " +
                    "phase VARCHAR(50), " +
                    "record_data TEXT, " +
                    "error_message TEXT, " +
                    "stack_trace TEXT, " +
                    "failed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.getJdbcTemplate().execute(createTableSql);
            log.info("Verified/Created 'migration_errors' table for fault-tolerant error tracking.");
        } catch (Exception ex) {
            log.warn("Note: Unable to auto-create migration_errors table (might already exist or permission limited): {}", ex.getMessage());
        }
    }

    private String serializeToJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String extractStackTrace(Throwable t) {
        if (t == null) return "";
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String full = sw.toString();
        return full.length() > 4000 ? full.substring(0, 4000) : full;
    }

    public List<MigrationErrorRecord> getRecentErrors() {
        return Collections.unmodifiableList(new ArrayList<>(recentErrors));
    }
}
