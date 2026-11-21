package com.bnpp.batch;

import com.bnpp.model.DatabaseRowPayload;
import com.bnpp.model.ErrorLogRecord;
import com.bnpp.model.MigrationErrorRecord;
import com.bnpp.service.AuditGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;

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
 * Logs to error_logs and keeps recent records for live telemetry polling.
 */
public class DatabaseErrorSkipListener implements SkipListener<Map<String, Object>, DatabaseRowPayload> {

    private static final Logger log = LoggerFactory.getLogger(DatabaseErrorSkipListener.class);

    private final String jobExecutionId;
    private final String stepName;
    private final AuditGovernanceService auditGovernanceService;
    private final com.bnpp.model.DbConnectionConfig dbConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<MigrationErrorRecord> recentErrors = new CopyOnWriteArrayList<>();

    public DatabaseErrorSkipListener(String jobExecutionId,
                                     String stepName,
                                     AuditGovernanceService auditGovernanceService,
                                     com.bnpp.model.DbConnectionConfig dbConfig) {
        this.jobExecutionId = jobExecutionId;
        this.stepName = stepName != null ? stepName : "xmlIngestionStep";
        this.auditGovernanceService = auditGovernanceService;
        this.dbConfig = dbConfig;
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
        log.warn("Record skipped during WRITE phase: {}", t.getMessage());
        Map<String, Object> data = (item != null) ? item.getColumnValues() : Collections.emptyMap();
        String payloadJson = serializeToJson(data);
        persistError("WRITE", payloadJson, t);
    }

    private void persistError(String phase, String recordDataJson, Throwable t) {
        String errorMsg = t != null ? t.getMessage() : "Unknown batch exception";
        String stackTrace = extractStackTrace(t);
        String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        MigrationErrorRecord record = new MigrationErrorRecord(
                null,
                stepName,
                phase,
                recordDataJson,
                errorMsg,
                nowStr
        );

        recentErrors.add(0, record);
        if (recentErrors.size() > 100) {
            recentErrors.remove(recentErrors.size() - 1);
        }

        if (auditGovernanceService != null && dbConfig != null) {
            ErrorLogRecord err = new ErrorLogRecord();
            err.setJobExecutionId(jobExecutionId);
            err.setEntityType(phase);
            err.setErrorSeverity("ERROR");
            err.setErrorType("BATCH_SKIP_" + phase);
            err.setErrorMessage(errorMsg);
            err.setStackTrace(stackTrace);
            err.setFailedPayload(recordDataJson);
            try {
                auditGovernanceService.logError(dbConfig, err);
            } catch (Exception ex) {
                log.warn("Failed to write to error_logs table: {}", ex.getMessage());
            }
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
