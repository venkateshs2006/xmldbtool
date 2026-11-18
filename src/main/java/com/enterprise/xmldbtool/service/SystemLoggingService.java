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

@Service
public class SystemLoggingService {

    private static final Logger log = LoggerFactory.getLogger(SystemLoggingService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Ensures file_history, audit_log, and error_log tables exist in the target database.
     */
    public void ensureSystemLoggingTables(DataSource dataSource) {
        if (dataSource == null) return;
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String dbProduct = (meta != null && meta.getDatabaseProductName() != null)
                    ? meta.getDatabaseProductName().toLowerCase() : "";
            boolean isOracle = dbProduct.contains("oracle");

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            // 1. file_history (Fulfills Requirement 2 with full forward & backward compatibility)
            String fileHistorySql;
            if (isOracle) {
                fileHistorySql = "CREATE TABLE file_history (" +
                        "file_id VARCHAR2(64) PRIMARY KEY, " +
                        "vendor_name VARCHAR2(100), " +
                        "vendor_id VARCHAR2(64), " +
                        "file_name VARCHAR2(255), " +
                        "xml_filename VARCHAR2(255), " +
                        "file_hash VARCHAR2(128), " +
                        "file_checksum VARCHAR2(128), " +
                        "file_size NUMBER(19) DEFAULT 0, " +
                        "file_size_kb NUMBER(19) DEFAULT 0, " +
                        "file_version_number NUMBER(10) DEFAULT 1, " +
                        "version_number NUMBER(10) DEFAULT 1, " +
                        "upload_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "metadata CLOB, " +
                        "ingestion_file_path VARCHAR2(500), " +
                        "ingestion_file_content CLOB, " +
                        "total_number_of_records NUMBER(19) DEFAULT 0, " +
                        "record_count NUMBER(19) DEFAULT 0, " +
                        "processing_status VARCHAR2(50) DEFAULT 'RECEIVED', " +
                        "triggered_by VARCHAR2(100) DEFAULT 'USER', " +
                        "created_by VARCHAR2(100) DEFAULT 'USER', " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
            } else {
                fileHistorySql = "CREATE TABLE IF NOT EXISTS file_history (" +
                        "file_id VARCHAR(64) PRIMARY KEY, " +
                        "vendor_name VARCHAR(100), " +
                        "vendor_id VARCHAR(64), " +
                        "file_name VARCHAR(255), " +
                        "xml_filename VARCHAR(255), " +
                        "file_hash VARCHAR(128), " +
                        "file_checksum VARCHAR(128), " +
                        "file_size BIGINT DEFAULT 0, " +
                        "file_size_kb BIGINT DEFAULT 0, " +
                        "file_version_number INT DEFAULT 1, " +
                        "version_number INT DEFAULT 1, " +
                        "upload_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "metadata TEXT, " +
                        "ingestion_file_path VARCHAR(500), " +
                        "ingestion_file_content TEXT, " +
                        "total_number_of_records BIGINT DEFAULT 0, " +
                        "record_count BIGINT DEFAULT 0, " +
                        "processing_status VARCHAR(50) DEFAULT 'RECEIVED', " +
                        "triggered_by VARCHAR(100) DEFAULT 'USER', " +
                        "created_by VARCHAR(100) DEFAULT 'USER', " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
            }
            executeSafe(jdbc, fileHistorySql, "file_history");

            // Ensure all columns exist if file_history was created with an alternate/partial schema
            if (!isOracle) {
                for (String addCol : List.of(
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS vendor_id VARCHAR(64)",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS xml_filename VARCHAR(255)",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS file_name VARCHAR(255)",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS file_hash VARCHAR(128)",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS file_checksum VARCHAR(128)",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS file_size BIGINT DEFAULT 0",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS file_size_kb BIGINT DEFAULT 0",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS file_version_number INT DEFAULT 1",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS version_number INT DEFAULT 1",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS metadata TEXT",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS ingestion_file_path VARCHAR(500)",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS ingestion_file_content TEXT",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS total_number_of_records BIGINT DEFAULT 0",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS record_count BIGINT DEFAULT 0",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS processing_status VARCHAR(50) DEFAULT 'RECEIVED'",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS triggered_by VARCHAR(100) DEFAULT 'USER'",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) DEFAULT 'USER'",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS upload_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                        "ALTER TABLE file_history ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                )) {
                    try { jdbc.execute(addCol); } catch (Exception ignored) {}
                }
            }

            // 2. audit_logs and audit_log (Fulfills Requirement 3 with dual table support)
            String auditLogsSql;
            String auditLogSql;
            if (isOracle) {
                auditLogsSql = "CREATE TABLE audit_logs (" +
                        "audit_id VARCHAR2(64) PRIMARY KEY, " +
                        "file_id VARCHAR2(64), " +
                        "job_execution_id VARCHAR2(64), " +
                        "event_type VARCHAR2(100), " +
                        "entity_name VARCHAR2(100), " +
                        "action_details CLOB, " +
                        "records_affected NUMBER(19) DEFAULT 0, " +
                        "triggered_by VARCHAR2(100) DEFAULT 'SYSTEM', " +
                        "execution_status VARCHAR2(50) DEFAULT 'SUCCESS', " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
                auditLogSql = "CREATE TABLE audit_log (" +
                        "audit_id VARCHAR2(64) PRIMARY KEY, " +
                        "file_id VARCHAR2(64), " +
                        "job_execution_id VARCHAR2(64), " +
                        "event_type VARCHAR2(100), " +
                        "entity_name VARCHAR2(100), " +
                        "action_details CLOB, " +
                        "records_affected NUMBER(19) DEFAULT 0, " +
                        "triggered_by VARCHAR2(100) DEFAULT 'SYSTEM', " +
                        "execution_status VARCHAR2(50) DEFAULT 'SUCCESS', " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
            } else {
                auditLogsSql = "CREATE TABLE IF NOT EXISTS audit_logs (" +
                        "audit_id VARCHAR(64) PRIMARY KEY, " +
                        "file_id VARCHAR(64), " +
                        "job_execution_id VARCHAR(64), " +
                        "event_type VARCHAR(100), " +
                        "entity_name VARCHAR(100), " +
                        "action_details TEXT, " +
                        "records_affected BIGINT DEFAULT 0, " +
                        "triggered_by VARCHAR(100) DEFAULT 'SYSTEM', " +
                        "execution_status VARCHAR(50) DEFAULT 'SUCCESS', " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
                auditLogSql = "CREATE TABLE IF NOT EXISTS audit_log (" +
                        "audit_id VARCHAR(64) PRIMARY KEY, " +
                        "file_id VARCHAR(64), " +
                        "job_execution_id VARCHAR(64), " +
                        "event_type VARCHAR(100), " +
                        "entity_name VARCHAR(100), " +
                        "action_details TEXT, " +
                        "records_affected BIGINT DEFAULT 0, " +
                        "triggered_by VARCHAR(100) DEFAULT 'SYSTEM', " +
                        "execution_status VARCHAR(50) DEFAULT 'SUCCESS', " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
            }
            executeSafe(jdbc, auditLogsSql, "audit_logs");
            executeSafe(jdbc, auditLogSql, "audit_log");

            // 3. error_log and error_logs
            String errorLogSql;
            String errorLogsSql;
            if (isOracle) {
                errorLogSql = "CREATE TABLE error_log (" +
                        "error_id VARCHAR2(64) PRIMARY KEY, " +
                        "file_id VARCHAR2(64), " +
                        "job_execution_id VARCHAR2(64), " +
                        "entity_type VARCHAR2(100), " +
                        "record_identifier VARCHAR2(255), " +
                        "error_severity VARCHAR2(50) DEFAULT 'ERROR', " +
                        "error_type VARCHAR2(100), " +
                        "error_message CLOB, " +
                        "stack_trace CLOB, " +
                        "failed_payload CLOB, " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
                errorLogsSql = "CREATE TABLE error_logs (" +
                        "error_id VARCHAR2(64) PRIMARY KEY, " +
                        "file_id VARCHAR2(64), " +
                        "job_execution_id VARCHAR2(64), " +
                        "entity_type VARCHAR2(100), " +
                        "record_identifier VARCHAR2(255), " +
                        "error_severity VARCHAR2(50) DEFAULT 'ERROR', " +
                        "error_type VARCHAR2(100), " +
                        "error_message CLOB, " +
                        "stack_trace CLOB, " +
                        "failed_payload CLOB, " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
            } else {
                errorLogSql = "CREATE TABLE IF NOT EXISTS error_log (" +
                        "error_id VARCHAR(64) PRIMARY KEY, " +
                        "file_id VARCHAR(64), " +
                        "job_execution_id VARCHAR(64), " +
                        "entity_type VARCHAR(100), " +
                        "record_identifier VARCHAR(255), " +
                        "error_severity VARCHAR(50) DEFAULT 'ERROR', " +
                        "error_type VARCHAR(100), " +
                        "error_message TEXT, " +
                        "stack_trace TEXT, " +
                        "failed_payload TEXT, " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
                errorLogsSql = "CREATE TABLE IF NOT EXISTS error_logs (" +
                        "error_id VARCHAR(64) PRIMARY KEY, " +
                        "file_id VARCHAR(64), " +
                        "job_execution_id VARCHAR(64), " +
                        "entity_type VARCHAR(100), " +
                        "record_identifier VARCHAR(255), " +
                        "error_severity VARCHAR(50) DEFAULT 'ERROR', " +
                        "error_type VARCHAR(100), " +
                        "error_message TEXT, " +
                        "stack_trace TEXT, " +
                        "failed_payload TEXT, " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
            }
            executeSafe(jdbc, errorLogSql, "error_log");
            executeSafe(jdbc, errorLogsSql, "error_logs");

            // Create non-unique indices for query performance
            try {
                if (!isOracle) {
                    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_file_history_vendor ON file_history(vendor_name, xml_filename)");
                    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_audit_logs_job ON audit_logs(job_execution_id)");
                    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_audit_logs_file ON audit_logs(file_id)");
                    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_audit_log_job ON audit_log(job_execution_id)");
                    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_audit_log_file ON audit_log(file_id)");
                    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_error_log_job ON error_log(job_execution_id)");
                    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_error_log_file ON error_log(file_id)");
                }
            } catch (Exception ignored) {}

        } catch (Exception ex) {
            log.warn("Notice ensuring system logging tables: {}", ex.getMessage());
        }
    }

    private void executeSafe(JdbcTemplate jdbc, String sql, String tableName) {
        try {
            jdbc.execute(sql);
        } catch (Exception ex) {
            // Already exists or syntax notice
            log.debug("Table creation notice for {}: {}", tableName, ex.getMessage());
        }
    }

    /**
     * Registers an uploaded file in file_history with auto-incrementing version number for the given vendor & file name.
     * Records vendor_id, xml_filename, file_hash, file_size, file_version_number, metadata,
     * ingestion_file_path, ingestion_file_content, total_number_of_records, and audit tracking.
     */
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
        if (dataSource == null) return null;
        ensureSystemLoggingTables(dataSource);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String safeVendor = (vendorName != null && !vendorName.trim().isEmpty()) ? vendorName.trim() : "DEFAULT_VENDOR";
            String safeVendorId = (vendorId != null && !vendorId.trim().isEmpty()) ? vendorId.trim() : ("VND-" + safeVendor.toUpperCase());
            String safeFile = (fileName != null && !fileName.trim().isEmpty()) ? fileName.trim() : "unknown.xml";
            String safeCreatedBy = (createdBy != null && !createdBy.trim().isEmpty()) ? createdBy.trim() : "USER";

            // Verify if user is re-executing with the identical file hash
            if (checksum != null && !checksum.trim().isEmpty()) {
                try {
                    Integer prevMatches = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM file_history WHERE (file_hash = ? OR file_checksum = ?) AND processing_status != 'FAILED'",
                            Integer.class, checksum, checksum);
                    if (prevMatches != null && prevMatches > 0) {
                        log.info("File verification check: File hash '{}' previously executed (seen {} time(s)). Incrementing version for tracking.",
                                checksum, prevMatches);
                    }
                } catch (Exception ignored) {}
            }

            int nextVersion = 1;
            try {
                Integer maxVer = jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version_number), 0) FROM file_history WHERE UPPER(vendor_name) = UPPER(?) AND UPPER(file_name) = UPPER(?)",
                        Integer.class, safeVendor, safeFile);
                if (maxVer != null && maxVer > 0) {
                    nextVersion = maxVer + 1;
                } else {
                    Integer maxVerAlt = jdbc.queryForObject(
                            "SELECT COALESCE(MAX(file_version_number), 0) FROM file_history WHERE UPPER(vendor_name) = UPPER(?) AND UPPER(xml_filename) = UPPER(?)",
                            Integer.class, safeVendor, safeFile);
                    if (maxVerAlt != null && maxVerAlt > 0) {
                        nextVersion = maxVerAlt + 1;
                    }
                }
            } catch (Exception ex) {
                log.debug("Notice getting next version for file_history: {}", ex.getMessage());
            }

            String fileId = BnppIdGenerator.generateId("FILE");
            String metaJson = null;
            if (metadata != null && !metadata.isEmpty()) {
                try {
                    metaJson = OBJECT_MAPPER.writeValueAsString(metadata);
                } catch (Exception ignored) {}
            }

            Timestamp now = new Timestamp(System.currentTimeMillis());
            long fileSizeBytes = fileSizeKb * 1024L;

            // Fulfill Requirement 2 file_history schema with robust fallback
            try {
                jdbc.update(
                        "INSERT INTO file_history (" +
                                "file_id, vendor_name, vendor_id, file_name, xml_filename, file_hash, file_checksum, " +
                                "file_size, file_size_kb, file_version_number, version_number, upload_date, " +
                                "metadata, ingestion_file_path, ingestion_file_content, total_number_of_records, record_count, " +
                                "processing_status, triggered_by, created_by, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        fileId, safeVendor, safeVendorId, safeFile, safeFile, checksum, checksum,
                        fileSizeBytes, fileSizeKb, nextVersion, nextVersion, now,
                        metaJson, ingestionFilePath, ingestionFileContent, 0L, 0L,
                        "PROCESSING", safeCreatedBy, safeCreatedBy, now, now
                );
            } catch (Exception exInsert) {
                // Fallback to strict Requirement 2 schema columns
                try {
                    jdbc.update(
                            "INSERT INTO file_history (" +
                                    "file_id, vendor_name, vendor_id, xml_filename, file_hash, " +
                                    "file_size, file_version_number, metadata, ingestion_file_path, " +
                                    "ingestion_file_content, total_number_of_records, processing_status, " +
                                    "triggered_by, created_at, updated_at) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            fileId, safeVendor, safeVendorId, safeFile, checksum,
                            fileSizeBytes, nextVersion, metaJson, ingestionFilePath,
                            ingestionFileContent, 0, "PROCESSING",
                            safeCreatedBy, now, now
                    );
                } catch (Exception exInsert2) {
                    // Minimal fallback
                    jdbc.update(
                            "INSERT INTO file_history (" +
                                    "file_id, vendor_name, xml_filename, file_hash, " +
                                    "file_size, file_version_number, processing_status, created_at, updated_at) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            fileId, safeVendor, safeFile, checksum,
                            fileSizeBytes, nextVersion, "PROCESSING", now, now
                    );
                }
            }

            log.info("Registered file in file_history: fileId={}, vendor={}, vendorId={}, fileName={}, version={}, fileHash={}",
                    fileId, safeVendor, safeVendorId, safeFile, nextVersion, checksum);
            return fileId;
        } catch (Exception ex) {
            log.error("Failed to register file in file_history: {}", ex.getMessage(), ex);
            return null;
        }
    }

    public String registerFile(DataSource dataSource,
                               String vendorName,
                               String fileName,
                               long fileSizeKb,
                               String checksum,
                               Map<String, Object> metadata) {
        return registerFile(dataSource, vendorName, null, fileName, fileSizeKb, checksum, null, null, "USER", metadata);
    }

    /**
     * Updates file status and final record count in file_history with resilient schema fallback.
     */
    public void updateFileStatus(DataSource dataSource, String fileId, String status, Long recordCount) {
        if (dataSource == null || fileId == null) return;
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Timestamp now = new Timestamp(System.currentTimeMillis());
            long safeCount = (recordCount != null && recordCount >= 0) ? recordCount : 0L;

            if (recordCount != null && recordCount >= 0) {
                try {
                    // 1. Try updating both record_count and total_number_of_records
                    jdbc.update("UPDATE file_history SET processing_status = ?, record_count = ?, total_number_of_records = ?, updated_at = ? WHERE file_id = ?",
                            status, safeCount, safeCount, now, fileId);
                } catch (Exception ex1) {
                    try {
                        // 2. Fallback to total_number_of_records only (Requirement 2 standard)
                        jdbc.update("UPDATE file_history SET processing_status = ?, total_number_of_records = ?, updated_at = ? WHERE file_id = ?",
                                status, safeCount, now, fileId);
                    } catch (Exception ex2) {
                        try {
                            // 3. Fallback to record_count only
                            jdbc.update("UPDATE file_history SET processing_status = ?, record_count = ?, updated_at = ? WHERE file_id = ?",
                                    status, safeCount, now, fileId);
                        } catch (Exception ex3) {
                            // 4. Fallback without counts
                            jdbc.update("UPDATE file_history SET processing_status = ?, updated_at = ? WHERE file_id = ?",
                                    status, now, fileId);
                        }
                    }
                }
            } else {
                try {
                    jdbc.update("UPDATE file_history SET processing_status = ?, updated_at = ? WHERE file_id = ?",
                            status, now, fileId);
                } catch (Exception ex) {
                    jdbc.update("UPDATE file_history SET processing_status = ? WHERE file_id = ?",
                            status, fileId);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to update file_history for fileId {}: {}", fileId, ex.getMessage());
        }
    }

    /**
     * Appends an audit event to audit_logs and audit_log (fulfilling Requirement 3).
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

            boolean anyWritten = false;
            // Write to audit_logs (Requirement 3) and audit_log (legacy compatibility)
            for (String tbl : List.of("audit_logs", "audit_log")) {
                try {
                    jdbc.update(
                            "INSERT INTO " + tbl + " (audit_id, file_id, job_execution_id, event_type, entity_name, action_details, records_affected, triggered_by, execution_status, timestamp) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            auditId, fileId, jobExecStr, eventType, entityName, actionDetails, recordsAffected, safeTrigger, safeStatus, now
                    );
                    anyWritten = true;
                } catch (Exception ex) {
                    // Try with jobExecutionId as Long in case column is BIGINT
                    try {
                        jdbc.update(
                                "INSERT INTO " + tbl + " (audit_id, file_id, job_execution_id, event_type, entity_name, action_details, records_affected, triggered_by, execution_status, timestamp) " +
                                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                auditId, fileId, jobExecutionId, eventType, entityName, actionDetails, recordsAffected, safeTrigger, safeStatus, now
                        );
                        anyWritten = true;
                    } catch (Exception ignored) {}
                }
            }
            if (!anyWritten) {
                log.debug("Notice: Could not write audit event {} to audit_logs or audit_log", eventType);
            }
        } catch (Exception ex) {
            log.warn("Failed to log audit event {}: {}", eventType, ex.getMessage());
        }
    }

    /**
     * Appends an error record to error_log and error_logs without throwing exceptions.
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

            for (String tbl : List.of("error_logs", "error_log")) {
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
     * Queries recent file_history records with adaptive column ordering.
     */
    public List<Map<String, Object>> getFileHistory(DataSource dataSource, int limit) {
        if (dataSource == null) return Collections.emptyList();
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
     * Queries recent audit_logs / audit_log records.
     */
    public List<Map<String, Object>> getAuditLogs(DataSource dataSource, Long jobExecutionId, int limit) {
        if (dataSource == null) return Collections.emptyList();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int safeLimit = Math.max(1, Math.min(limit, 500));
            String jobExecStr = jobExecutionId != null ? String.valueOf(jobExecutionId) : null;

            for (String tbl : List.of("audit_logs", "audit_log")) {
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
     * Queries recent error_log records across error_logs, error_log, migration_errors, and gs_error_records.
     */
    public List<Map<String, Object>> getErrorLogs(DataSource dataSource, Long jobExecutionId, int limit) {
        if (dataSource == null) return Collections.emptyList();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int safeLimit = Math.max(1, Math.min(limit, 500));
            String jobExecStr = jobExecutionId != null ? String.valueOf(jobExecutionId) : null;

            for (String tbl : List.of("error_logs", "error_log", "migration_errors", "gs_error_records")) {
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
