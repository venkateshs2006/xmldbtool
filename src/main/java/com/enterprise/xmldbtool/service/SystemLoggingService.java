package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.util.BnppIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.util.*;

/**
 * Enterprise service responsible for lifecycle logging and system tracking.
 * Maintains file ingestion history (file_history), event audit trails (audit_log),
 * and processing exceptions (error_log).
 */
@Service
public class SystemLoggingService {

    private static final Logger log = LoggerFactory.getLogger(SystemLoggingService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private record DialectTypes(String textType, String longType, String intType, String varcharType, boolean ifNotExistsSupported) {}

    private static DialectTypes resolveDialect(Connection conn) {
        try {
            DatabaseMetaData meta = conn.getMetaData();
            String dbProduct = (meta != null && meta.getDatabaseProductName() != null)
                    ? meta.getDatabaseProductName().toLowerCase() : "";
            if (dbProduct.contains("oracle")) {
                return new DialectTypes("CLOB", "NUMBER(19)", "NUMBER(10)", "VARCHAR2", false);
            }
        } catch (Exception ignored) {}
        return new DialectTypes("TEXT", "BIGINT", "INT", "VARCHAR", true);
    }

    /**
     * Ensures file_history, audit_log, and error_log tables and indexes exist in the target database.
     */
    public void ensureSystemLoggingTables(DataSource dataSource) {
        if (dataSource == null) return;
        try (Connection conn = dataSource.getConnection()) {
            SchemaProvisioningService.prepareConnection(conn, null);
            DialectTypes d = resolveDialect(conn);
            String ifNotExists = d.ifNotExistsSupported() ? "IF NOT EXISTS " : "";
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            // 1. file_history table
            String fileHistorySql = "CREATE TABLE " + ifNotExists + "file_history (" +
                    "file_id " + d.varcharType() + "(64) PRIMARY KEY, " +
                    "vendor_name " + d.varcharType() + "(100), " +
                    "vendor_id " + d.varcharType() + "(64), " +
                    "file_name " + d.varcharType() + "(255), " +
                    "xml_filename " + d.varcharType() + "(255), " +
                    "file_hash " + d.varcharType() + "(128), " +
                    "file_checksum " + d.varcharType() + "(128), " +
                    "file_size " + d.longType() + " DEFAULT 0, " +
                    "file_size_kb " + d.longType() + " DEFAULT 0, " +
                    "file_version_number " + d.intType() + " DEFAULT 1, " +
                    "version_number " + d.intType() + " DEFAULT 1, " +
                    "upload_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "metadata " + d.textType() + ", " +
                    "ingestion_file_path " + d.varcharType() + "(500), " +
                    "ingestion_file_content " + d.textType() + ", " +
                    "total_number_of_records " + d.longType() + " DEFAULT 0, " +
                    "total_number_of_record " + d.longType() + " DEFAULT 0, " +
                    "record_count " + d.longType() + " DEFAULT 0, " +
                    "processing_status " + d.varcharType() + "(50) DEFAULT 'RECEIVED', " +
                    "triggered_by " + d.varcharType() + "(100) DEFAULT 'USER', " +
                    "created_by " + d.varcharType() + "(100) DEFAULT 'USER', " +
                    "updated_by " + d.varcharType() + "(100) DEFAULT 'USER', " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            executeSafe(jdbc, fileHistorySql, "file_history");

            // Migration compatibility: add missing columns if table pre-existed
            if (d.ifNotExistsSupported()) {
                for (String col : List.of("vendor_id VARCHAR(64)", "xml_filename VARCHAR(255)", "file_name VARCHAR(255)",
                        "file_hash VARCHAR(128)", "file_checksum VARCHAR(128)", "file_size BIGINT DEFAULT 0",
                        "file_size_kb BIGINT DEFAULT 0", "file_version_number INT DEFAULT 1", "version_number INT DEFAULT 1",
                        "metadata TEXT", "ingestion_file_path VARCHAR(500)", "ingestion_file_content TEXT",
                        "total_number_of_records BIGINT DEFAULT 0", "total_number_of_record BIGINT DEFAULT 0",
                        "record_count BIGINT DEFAULT 0", "processing_status VARCHAR(50) DEFAULT 'RECEIVED'",
                        "triggered_by VARCHAR(100) DEFAULT 'USER'", "created_by VARCHAR(100) DEFAULT 'USER'",
                        "updated_by VARCHAR(100) DEFAULT 'USER'", "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                        "upload_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP")) {
                    try {
                        jdbc.execute("ALTER TABLE file_history ADD COLUMN IF NOT EXISTS " + col);
                    } catch (Exception ignored) {}
                }
            }

            // 2. audit_log & audit_logs tables
            for (String tbl : List.of("audit_log", "audit_logs")) {
                String auditSql = "CREATE TABLE " + ifNotExists + tbl + " (" +
                        "audit_id " + d.varcharType() + "(64) PRIMARY KEY, " +
                        "file_id " + d.varcharType() + "(64), " +
                        "job_execution_id " + d.varcharType() + "(64), " +
                        "event_type " + d.varcharType() + "(100), " +
                        "entity_name " + d.varcharType() + "(100), " +
                        "action_details " + d.textType() + ", " +
                        "records_affected " + d.longType() + " DEFAULT 0, " +
                        "triggered_by " + d.varcharType() + "(100) DEFAULT 'SYSTEM', " +
                        "execution_status " + d.varcharType() + "(50) DEFAULT 'SUCCESS', " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
                executeSafe(jdbc, auditSql, tbl);
            }

            // 3. error_log & error_logs tables
            for (String tbl : List.of("error_log", "error_logs")) {
                String errorSql = "CREATE TABLE " + ifNotExists + tbl + " (" +
                        "error_id " + d.varcharType() + "(64) PRIMARY KEY, " +
                        "file_id " + d.varcharType() + "(64), " +
                        "job_execution_id " + d.varcharType() + "(64), " +
                        "entity_type " + d.varcharType() + "(100), " +
                        "record_identifier " + d.varcharType() + "(255), " +
                        "error_severity " + d.varcharType() + "(50) DEFAULT 'ERROR', " +
                        "error_type " + d.varcharType() + "(100), " +
                        "error_message " + d.textType() + ", " +
                        "stack_trace " + d.textType() + ", " +
                        "failed_payload " + d.textType() + ", " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
                executeSafe(jdbc, errorSql, tbl);
            }

            // 4. Performance Indexes
            if (d.ifNotExistsSupported()) {
                for (String idx : List.of(
                        "CREATE INDEX IF NOT EXISTS idx_file_history_vendor ON file_history(vendor_name, xml_filename)",
                        "CREATE INDEX IF NOT EXISTS idx_audit_log_job ON audit_log(job_execution_id)",
                        "CREATE INDEX IF NOT EXISTS idx_audit_log_file ON audit_log(file_id)",
                        "CREATE INDEX IF NOT EXISTS idx_audit_logs_job ON audit_logs(job_execution_id)",
                        "CREATE INDEX IF NOT EXISTS idx_audit_logs_file ON audit_logs(file_id)",
                        "CREATE INDEX IF NOT EXISTS idx_error_log_job ON error_log(job_execution_id)",
                        "CREATE INDEX IF NOT EXISTS idx_error_log_file ON error_log(file_id)"
                )) {
                    try { jdbc.execute(idx); } catch (Exception ignored) {}
                }
            }

        } catch (Exception ex) {
            log.warn("Notice ensuring system logging tables: {}", ex.getMessage());
        }
    }

    private void executeSafe(JdbcTemplate jdbc, String sql, String tableName) {
        try {
            jdbc.execute(sql);
        } catch (Exception ex) {
            log.debug("Table check notice for {}: {}", tableName, ex.getMessage());
        }
    }

    /**
     * Registers an uploaded XML file in file_history.
     * Computes version increments and auto-generates sequential IDs.
     */
    public String registerFile(DataSource dataSource,
                               String vendorName,
                               String vendorId,
                               String fileName,
                               long fileSizeBytes,
                               long fileSizeKb,
                               String checksum,
                               String ingestionFilePath,
                               String ingestionFileContent,
                               String createdBy,
                               Map<String, Object> metadata) {
        if (dataSource == null) return null;
        ensureSystemLoggingTables(dataSource);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String safeVendor = (vendorName != null && !vendorName.trim().isEmpty()) ? vendorName.trim() : "DEFAULT_VENDOR";
            String safeVendorId = resolveOrCreateVendorId(jdbc, safeVendor, vendorId);
            String safeFile = (fileName != null && !fileName.trim().isEmpty()) ? fileName.trim() : "unknown.xml";
            String safeCreatedBy = (createdBy != null && !createdBy.trim().isEmpty()) ? createdBy.trim() : "USER";

            verifyFileHashExecution(jdbc, safeVendor, safeFile, checksum);
            int nextVersion = resolveNextFileVersion(jdbc, safeVendor, safeFile);

            String fileId = BnppIdGenerator.generateId("FILE");
            String metaJson = serializeMetadata(metadata);
            Timestamp now = new Timestamp(System.currentTimeMillis());
            long safeFileSizeBytes = fileSizeBytes > 0 ? fileSizeBytes : (fileSizeKb * 1024L);

            insertFileHistoryRecord(jdbc, fileId, safeVendor, safeVendorId, safeFile, checksum,
                    safeFileSizeBytes, fileSizeKb, nextVersion, metaJson, ingestionFilePath,
                    ingestionFileContent, safeCreatedBy, now);

            log.info("Registered file history record: fileId={}, vendor={}, vendorId={}, file={}, version={}, hash={}",
                    fileId, safeVendor, safeVendorId, safeFile, nextVersion, checksum);
            return fileId;
        } catch (Exception ex) {
            log.error("Failed to register file history: {}", ex.getMessage(), ex);
            return null;
        }
    }

    public String registerFile(DataSource dataSource,
                               String vendorName,
                               String vendorId,
                               String fileName,
                               long fileSizeKb,
                               String checksum,
                               String ingestionFilePath,
                               String ingestionFileContent,
                               String createdBy,
                               Map<String, Object> metadata) {
        return registerFile(dataSource, vendorName, vendorId, fileName, fileSizeKb * 1024L, fileSizeKb,
                checksum, ingestionFilePath, ingestionFileContent, createdBy, metadata);
    }

    public String registerFile(DataSource dataSource,
                               String vendorName,
                               String fileName,
                               long fileSizeKb,
                               String checksum,
                               Map<String, Object> metadata) {
        return registerFile(dataSource, vendorName, null, fileName, fileSizeKb * 1024L, fileSizeKb, checksum, null, null, "USER", metadata);
    }

    private String resolveOrCreateVendorId(JdbcTemplate jdbc, String vendorName, String providedVendorId) {
        if (providedVendorId != null && !providedVendorId.trim().isEmpty() && !"VND-DEFAULT".equalsIgnoreCase(providedVendorId.trim())) {
            return providedVendorId.trim();
        }
        try {
            List<String> existing = jdbc.queryForList(
                    "SELECT vendor_id FROM file_history WHERE UPPER(vendor_name) = UPPER(?) AND vendor_id IS NOT NULL",
                    String.class, vendorName);
            for (String id : existing) {
                if (id != null && !id.trim().isEmpty() && !"VND-DEFAULT".equalsIgnoreCase(id.trim())) {
                    return id.trim();
                }
            }
        } catch (Exception ignored) {}
        return BnppIdGenerator.generateId("VND");
    }

    private void verifyFileHashExecution(JdbcTemplate jdbc, String vendorName, String fileName, String checksum) {
        if (checksum == null || checksum.trim().isEmpty()) return;
        try {
            Integer matches = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM file_history WHERE (file_hash = ? OR file_checksum = ?) AND processing_status != 'FAILED'",
                    Integer.class, checksum, checksum);
            if (matches != null && matches > 0) {
                log.info("File hash verification: Content hash '{}' previously executed (seen {} time(s)). Incrementing version.",
                        checksum, matches);
            }
        } catch (Exception ignored) {}
    }

    private int resolveNextFileVersion(JdbcTemplate jdbc, String vendorName, String fileName) {
        try {
            Integer maxVer = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version_number), 0) FROM file_history WHERE UPPER(vendor_name) = UPPER(?) AND (UPPER(file_name) = UPPER(?) OR UPPER(xml_filename) = UPPER(?))",
                    Integer.class, vendorName, fileName, fileName);
            if (maxVer != null && maxVer > 0) {
                return maxVer + 1;
            }
            Integer maxVerAlt = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(file_version_number), 0) FROM file_history WHERE UPPER(vendor_name) = UPPER(?) AND (UPPER(file_name) = UPPER(?) OR UPPER(xml_filename) = UPPER(?))",
                    Integer.class, vendorName, fileName, fileName);
            if (maxVerAlt != null && maxVerAlt > 0) {
                return maxVerAlt + 1;
            }
        } catch (Exception ex) {
            log.debug("Notice resolving next file version: {}", ex.getMessage());
        }
        return 1;
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void insertFileHistoryRecord(JdbcTemplate jdbc, String fileId, String vendor, String vendorId,
                                         String file, String checksum, long fileSizeBytes, long fileSizeKb,
                                         int version, String metaJson, String ingestionFilePath,
                                         String ingestionFileContent, String createdBy, Timestamp now) {
        try {
            jdbc.update(
                    "INSERT INTO file_history (" +
                            "file_id, vendor_name, vendor_id, file_name, xml_filename, file_hash, file_checksum, " +
                            "file_size, file_size_kb, file_version_number, version_number, upload_date, " +
                            "metadata, ingestion_file_path, ingestion_file_content, total_number_of_records, total_number_of_record, record_count, " +
                            "processing_status, triggered_by, created_by, updated_by, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    fileId, vendor, vendorId, file, file, checksum, checksum,
                    fileSizeBytes, fileSizeKb, version, version, now,
                    metaJson, ingestionFilePath, ingestionFileContent, 0L, 0L, 0L,
                    "PROCESSING", createdBy, createdBy, createdBy, now, now
            );
        } catch (Exception exInsert) {
            // Adaptive fallback for minimal schemas
            try {
                jdbc.update(
                        "INSERT INTO file_history (" +
                                "file_id, vendor_name, vendor_id, xml_filename, file_hash, " +
                                "file_size, file_version_number, metadata, ingestion_file_path, " +
                                "ingestion_file_content, total_number_of_records, total_number_of_record, processing_status, " +
                                "triggered_by, created_by, updated_by, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        fileId, vendor, vendorId, file, checksum,
                        fileSizeBytes, version, metaJson, ingestionFilePath,
                        ingestionFileContent, 0L, 0L, "PROCESSING",
                        createdBy, createdBy, createdBy, now, now
                );
            } catch (Exception exInsert2) {
                jdbc.update(
                        "INSERT INTO file_history (file_id, vendor_name, xml_filename, file_hash, file_size, file_version_number, processing_status, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        fileId, vendor, file, checksum, fileSizeBytes, version, "PROCESSING", now, now
                );
            }
        }
    }

    /**
     * Updates file status and final record counts in file_history.
     */
    public void updateFileStatus(DataSource dataSource, String fileId, String status, Long recordCount) {
        if (dataSource == null || fileId == null) return;
        ensureSystemLoggingTables(dataSource);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Timestamp now = new Timestamp(System.currentTimeMillis());
            long safeCount = (recordCount != null && recordCount >= 0) ? recordCount : 0L;

            if (recordCount != null && recordCount >= 0) {
                try {
                    jdbc.update("UPDATE file_history SET processing_status = ?, record_count = ?, total_number_of_records = ?, total_number_of_record = ?, updated_at = ? WHERE file_id = ?",
                            status, safeCount, safeCount, safeCount, now, fileId);
                } catch (Exception ex1) {
                    try {
                        jdbc.update("UPDATE file_history SET processing_status = ?, total_number_of_records = ?, updated_at = ? WHERE file_id = ?",
                                status, safeCount, now, fileId);
                    } catch (Exception ex2) {
                        jdbc.update("UPDATE file_history SET processing_status = ?, updated_at = ? WHERE file_id = ?",
                                status, now, fileId);
                    }
                }
            } else {
                jdbc.update("UPDATE file_history SET processing_status = ?, updated_at = ? WHERE file_id = ?",
                        status, now, fileId);
            }
        } catch (Exception ex) {
            log.warn("Failed to update file_history status for fileId {}: {}", fileId, ex.getMessage());
        }
    }

    /**
     * Appends an audit event to audit_log (and audit_logs).
     */
    public void logAudit(DataSource dataSource,
                         String fileId,
                         Long jobExecutionId,
                         String eventType,
                         String entityName,
                         String actionDetails,
                         long recordsAffected,
                         String triggeredBy,
                         String executionStatus) {
        if (dataSource == null) return;
        ensureSystemLoggingTables(dataSource);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String auditId = BnppIdGenerator.generateId("AUDIT");
            Timestamp now = new Timestamp(System.currentTimeMillis());
            String safeTrigger = (triggeredBy != null && !triggeredBy.trim().isEmpty()) ? triggeredBy.trim() : "SYSTEM";
            String safeStatus = (executionStatus != null && !executionStatus.trim().isEmpty()) ? executionStatus.trim() : "SUCCESS";
            String jobExecStr = jobExecutionId != null ? String.valueOf(jobExecutionId) : null;

            for (String tbl : List.of("audit_log", "audit_logs")) {
                try {
                    jdbc.update(
                            "INSERT INTO " + tbl + " (audit_id, file_id, job_execution_id, event_type, entity_name, action_details, records_affected, triggered_by, execution_status, timestamp) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            auditId, fileId, jobExecStr, eventType, entityName, actionDetails, recordsAffected, safeTrigger, safeStatus, now
                    );
                } catch (Exception ex) {
                    try {
                        jdbc.update(
                                "INSERT INTO " + tbl + " (audit_id, file_id, job_execution_id, event_type, entity_name, action_details, records_affected, triggered_by, execution_status, timestamp) " +
                                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                auditId, fileId, jobExecutionId, eventType, entityName, actionDetails, recordsAffected, safeTrigger, safeStatus, now
                        );
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to log audit event {}: {}", eventType, ex.getMessage());
        }
    }

    /**
     * Appends an error record to error_log (and error_logs).
     */
    public void logError(DataSource dataSource,
                         String fileId,
                         Long jobExecutionId,
                         String entityType,
                         String recordIdentifier,
                         String errorSeverity,
                         String errorType,
                         String errorMessage,
                         Throwable throwable,
                         String failedPayload) {
        if (dataSource == null) return;
        ensureSystemLoggingTables(dataSource);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String errorId = BnppIdGenerator.generateId("ERR");
            Timestamp now = new Timestamp(System.currentTimeMillis());

            String stackTrace = null;
            if (throwable != null) {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                stackTrace = sw.toString();
            }

            String safeSeverity = (errorSeverity != null && !errorSeverity.trim().isEmpty()) ? errorSeverity.trim() : "ERROR";
            String safeType = (errorType != null && !errorType.trim().isEmpty()) ? errorType.trim() : "EXECUTION_ERROR";
            String jobExecStr = jobExecutionId != null ? String.valueOf(jobExecutionId) : null;

            for (String tbl : List.of("error_log", "error_logs")) {
                try {
                    jdbc.update(
                            "INSERT INTO " + tbl + " (error_id, file_id, job_execution_id, entity_type, record_identifier, error_severity, error_type, error_message, stack_trace, failed_payload, timestamp) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            errorId, fileId, jobExecStr, entityType, recordIdentifier, safeSeverity, safeType, errorMessage, stackTrace, failedPayload, now
                    );
                } catch (Exception ex) {
                    try {
                        jdbc.update(
                                "INSERT INTO " + tbl + " (error_id, file_id, job_execution_id, entity_type, record_identifier, error_severity, error_type, error_message, stack_trace, failed_payload, timestamp) " +
                                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                errorId, fileId, jobExecutionId, entityType, recordIdentifier, safeSeverity, safeType, errorMessage, stackTrace, failedPayload, now
                        );
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to write to error_log: {}", ex.getMessage());
        }
    }

    /**
     * Resolves the active target DataSource from dynamic connection pools or primary DataSource fallback.
     */
    public DataSource resolveDataSource(DataSource primaryDs, DynamicDataSourceService dsService) {
        if (dsService != null) {
            Map<String, com.zaxxer.hikari.HikariDataSource> pools = dsService.getActivePools();
            if (pools != null && !pools.isEmpty()) {
                for (com.zaxxer.hikari.HikariDataSource ds : pools.values()) {
                    if (ds != null && !ds.isClosed()) {
                        return ds;
                    }
                }
            }
        }
        return primaryDs;
    }

    /**
     * Queries recent file_history records.
     */
    public List<Map<String, Object>> getFileHistory(DataSource dataSource, int limit) {
        if (dataSource == null) return Collections.emptyList();
        ensureSystemLoggingTables(dataSource);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int safeLimit = Math.max(1, Math.min(limit, 500));
            try {
                return jdbc.queryForList("SELECT * FROM file_history ORDER BY upload_date DESC LIMIT " + safeLimit);
            } catch (Exception ex1) {
                try {
                    return jdbc.queryForList("SELECT * FROM file_history ORDER BY created_at DESC LIMIT " + safeLimit);
                } catch (Exception ex2) {
                    return jdbc.queryForList("SELECT * FROM file_history LIMIT " + safeLimit);
                }
            }
        } catch (Exception ex) {
            log.debug("Notice querying file_history: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Queries recent audit_log records.
     */
    public List<Map<String, Object>> getAuditLogs(DataSource dataSource, Long jobExecutionId, int limit) {
        if (dataSource == null) return Collections.emptyList();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int safeLimit = Math.max(1, Math.min(limit, 500));
            String jobExecStr = jobExecutionId != null ? String.valueOf(jobExecutionId) : null;

            for (String tbl : List.of("audit_log", "audit_logs")) {
                try {
                    if (jobExecutionId != null && jobExecutionId > 0) {
                        try {
                            return jdbc.queryForList("SELECT * FROM " + tbl + " WHERE job_execution_id = ? ORDER BY timestamp DESC LIMIT " + safeLimit, jobExecStr);
                        } catch (Exception exParam) {
                            return jdbc.queryForList("SELECT * FROM " + tbl + " WHERE job_execution_id = ? ORDER BY timestamp DESC LIMIT " + safeLimit, jobExecutionId);
                        }
                    }
                    return jdbc.queryForList("SELECT * FROM " + tbl + " ORDER BY timestamp DESC LIMIT " + safeLimit);
                } catch (Exception ignored) {}
            }
            return Collections.emptyList();
        } catch (Exception ex) {
            log.debug("Notice querying audit_log: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Queries recent error_log records.
     */
    public List<Map<String, Object>> getErrorLogs(DataSource dataSource, Long jobExecutionId, int limit) {
        if (dataSource == null) return Collections.emptyList();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int safeLimit = Math.max(1, Math.min(limit, 500));
            String jobExecStr = jobExecutionId != null ? String.valueOf(jobExecutionId) : null;

            for (String tbl : List.of("error_log", "error_logs", "migration_errors", "gs_error_records")) {
                try {
                    if (jobExecutionId != null && jobExecutionId > 0) {
                        try {
                            return jdbc.queryForList("SELECT * FROM " + tbl + " WHERE job_execution_id = ? LIMIT " + safeLimit, jobExecutionId);
                        } catch (Exception exParam) {
                            return jdbc.queryForList("SELECT * FROM " + tbl + " WHERE job_execution_id = ? LIMIT " + safeLimit, jobExecStr);
                        }
                    }
                    return jdbc.queryForList("SELECT * FROM " + tbl + " LIMIT " + safeLimit);
                } catch (Exception ignored) {}
            }
            return Collections.emptyList();
        } catch (Exception ex) {
            log.debug("Notice querying error_log: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }
}
