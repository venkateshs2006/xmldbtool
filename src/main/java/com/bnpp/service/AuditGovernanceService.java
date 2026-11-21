package com.bnpp.service;

import com.bnpp.model.AuditLogRecord;
import com.bnpp.model.DbConnectionConfig;
import com.bnpp.model.ErrorLogRecord;
import com.bnpp.model.FileHistoryRecord;
import com.bnpp.model.JobCheckpointRecord;
import com.bnpp.util.BnppIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.*;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class AuditGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(AuditGovernanceService.class);

    private final DynamicDataSourceService dataSourceService;

    public AuditGovernanceService(DynamicDataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    public void ensureGovernanceTablesExist(DbConnectionConfig config) {
        DataSource ds = dataSourceService.getOrCreateDataSource(config);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // file_history
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS file_history (
                    file_id VARCHAR(64) PRIMARY KEY,
                    vendor_name VARCHAR(128),
                    vendor_id VARCHAR(64),
                    xml_filename VARCHAR(256),
                    filename_hash VARCHAR(128),
                    file_checksum VARCHAR(128),
                    file_size_kb BIGINT,
                    file_version_number INT DEFAULT 1,
                    upload_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    file_metadata TEXT,
                    ingestion_file_path VARCHAR(512),
                    ingestion_file_content TEXT,
                    total_number_of_records BIGINT DEFAULT 0,
                    gs_record_count_in_db BIGINT DEFAULT 0,
                    src_record_count BIGINT DEFAULT 0,
                    processing_status VARCHAR(64) DEFAULT 'REGISTERED',
                    triggered_by VARCHAR(128),
                    created_by VARCHAR(128),
                    updated_by VARCHAR(128),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            // audit_logs
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS audit_logs (
                    audit_id VARCHAR(64) PRIMARY KEY,
                    file_id VARCHAR(64),
                    job_execution_id VARCHAR(64),
                    event_type VARCHAR(64),
                    entity_name VARCHAR(128),
                    action_details TEXT,
                    records_affected BIGINT DEFAULT 0,
                    triggered_by VARCHAR(128),
                    execution_status VARCHAR(64),
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            // error_logs
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS error_logs (
                    error_id VARCHAR(64) PRIMARY KEY,
                    file_id VARCHAR(64),
                    job_execution_id VARCHAR(64),
                    entity_type VARCHAR(128),
                    record_identifier VARCHAR(128),
                    error_severity VARCHAR(32),
                    error_type VARCHAR(64),
                    error_message TEXT,
                    stack_trace TEXT,
                    failed_payload TEXT,
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            // job_checkpoints
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS job_checkpoints (
                    job_execution_id VARCHAR(64) PRIMARY KEY,
                    file_id VARCHAR(64),
                    last_record_identifier VARCHAR(128),
                    records_streamed BIGINT DEFAULT 0,
                    status VARCHAR(64),
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            log.info("Governance tables verified/created in target database.");
        } catch (Exception ex) {
            log.error("Failed to ensure governance tables: {}", ex.getMessage(), ex);
            throw new RuntimeException("Could not initialize governance tables: " + ex.getMessage(), ex);
        }
    }

    public FileHistoryRecord registerOrResolveFile(DbConnectionConfig config, String filePath, String vendorName,
                                                  String vendorId, String ingestionJson, String user) {
        ensureGovernanceTablesExist(config);
        DataSource ds = dataSourceService.getOrCreateDataSource(config);

        File file = resolvePhysicalFile(filePath);
        String fileName = file.getName();
        long sizeKb = Math.max(1, file.length() / 1024);

        String checksum = computeSha256(file);
        String filenameHash = computeHash(fileName);

        String effectiveVendorName = (vendorName != null && !vendorName.trim().isEmpty()) ? vendorName.trim() : "DEFAULT_VENDOR";
        String effectiveVendorId = (vendorId != null && !vendorId.trim().isEmpty()) ? vendorId.trim() : null;
        String effectiveUser = (user != null && !user.trim().isEmpty()) ? user.trim() : "SYSTEM";

        int nextVersion = 1;
        String reusableIngestionJson = ingestionJson;

        try (Connection conn = ds.getConnection()) {
            if (effectiveVendorId == null) {
                // Check if vendor already exists by name
                String findVndSql = "SELECT vendor_id FROM file_history WHERE vendor_name = ? ORDER BY created_at DESC";
                try (PreparedStatement ps = conn.prepareStatement(findVndSql)) {
                    ps.setString(1, effectiveVendorName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            effectiveVendorId = rs.getString("vendor_id");
                        }
                    }
                }
                if (effectiveVendorId == null) {
                    effectiveVendorId = BnppIdGenerator.generateVendorId(effectiveVendorName);
                }
            }

            // Look up existing file history for this vendor to determine versioning and reuse ingestion.json
            String histSql = "SELECT file_version_number, file_checksum, ingestion_file_content FROM file_history WHERE vendor_id = ? ORDER BY file_version_number DESC";
            try (PreparedStatement ps = conn.prepareStatement(histSql)) {
                ps.setString(1, effectiveVendorId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int latestVersion = rs.getInt("file_version_number");
                        String latestChecksum = rs.getString("file_checksum");
                        String latestJson = rs.getString("ingestion_file_content");

                        if (checksum != null && !checksum.equalsIgnoreCase(latestChecksum)) {
                            nextVersion = latestVersion + 1;
                        } else {
                            nextVersion = latestVersion;
                        }

                        if ((reusableIngestionJson == null || reusableIngestionJson.trim().isEmpty() || "[]".equals(reusableIngestionJson.trim()))
                                && latestJson != null && !latestJson.trim().isEmpty()) {
                            reusableIngestionJson = latestJson;
                        }
                    }
                }
            }

            String fileId = BnppIdGenerator.generateFileId();
            Timestamp now = new Timestamp(System.currentTimeMillis());

            String insertSql = """
                INSERT INTO file_history (
                    file_id, vendor_name, vendor_id, xml_filename, filename_hash, file_checksum,
                    file_size_kb, file_version_number, upload_date, file_metadata, ingestion_file_path,
                    ingestion_file_content, total_number_of_records, gs_record_count_in_db, src_record_count,
                    processing_status, triggered_by, created_by, updated_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 'REGISTERED', ?, ?, ?, ?, ?)
            """;

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, fileId);
                ps.setString(2, effectiveVendorName);
                ps.setString(3, effectiveVendorId);
                ps.setString(4, fileName);
                ps.setString(5, filenameHash);
                ps.setString(6, checksum);
                ps.setLong(7, sizeKb);
                ps.setInt(8, nextVersion);
                ps.setTimestamp(9, now);
                ps.setString(10, "{\"fileName\":\"" + fileName + "\",\"sizeKb\":" + sizeKb + "}");
                ps.setString(11, file.getAbsolutePath());
                ps.setString(12, reusableIngestionJson);
                ps.setString(13, effectiveUser);
                ps.setString(14, effectiveUser);
                ps.setString(15, effectiveUser);
                ps.setTimestamp(16, now);
                ps.setTimestamp(17, now);
                ps.executeUpdate();
            }

            FileHistoryRecord record = new FileHistoryRecord();
            record.setFileId(fileId);
            record.setVendorName(effectiveVendorName);
            record.setVendorId(effectiveVendorId);
            record.setXmlFilename(fileName);
            record.setFilenameHash(filenameHash);
            record.setFileChecksum(checksum);
            record.setFileSizeKb(sizeKb);
            record.setFileVersionNumber(nextVersion);
            record.setUploadDate(now);
            record.setIngestionFilePath(file.getAbsolutePath());
            record.setIngestionFileContent(reusableIngestionJson);
            record.setProcessingStatus("REGISTERED");
            record.setTriggeredBy(effectiveUser);
            record.setCreatedBy(effectiveUser);
            record.setUpdatedBy(effectiveUser);
            record.setCreatedAt(now);
            record.setUpdatedAt(now);

            log.info("Registered file_history: ID={}, Version={}, Vendor={}({})", fileId, nextVersion, effectiveVendorName, effectiveVendorId);
            return record;
        } catch (Exception ex) {
            log.error("Failed to register file history: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error registering file history: " + ex.getMessage(), ex);
        }
    }

    public void logAudit(DbConnectionConfig config, AuditLogRecord audit) {
        if (audit == null) return;
        ensureGovernanceTablesExist(config);
        DataSource ds = dataSourceService.getOrCreateDataSource(config);

        if (audit.getAuditId() == null || audit.getAuditId().trim().isEmpty()) {
            audit.setAuditId(BnppIdGenerator.generateAuditId());
        }
        if (audit.getTimestamp() == null) {
            audit.setTimestamp(new Timestamp(System.currentTimeMillis()));
        }

        String sql = """
            INSERT INTO audit_logs (
                audit_id, file_id, job_execution_id, event_type, entity_name,
                action_details, records_affected, triggered_by, execution_status, timestamp
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, audit.getAuditId());
            ps.setString(2, audit.getFileId());
            ps.setString(3, audit.getJobExecutionId());
            ps.setString(4, audit.getEventType());
            ps.setString(5, audit.getEntityName());
            ps.setString(6, audit.getActionDetails());
            ps.setLong(7, audit.getRecordsAffected());
            ps.setString(8, audit.getTriggeredBy());
            ps.setString(9, audit.getExecutionStatus());
            ps.setTimestamp(10, audit.getTimestamp());
            ps.executeUpdate();
        } catch (Exception ex) {
            log.error("Failed to write audit log {}: {}", audit.getAuditId(), ex.getMessage());
        }
    }

    public List<AuditLogRecord> listAuditLogs(DbConnectionConfig config, String jobExecutionId, int limit) {
        ensureGovernanceTablesExist(config);
        DataSource ds = dataSourceService.getOrCreateDataSource(config);
        List<AuditLogRecord> list = new ArrayList<>();

        String sql = (jobExecutionId != null && !jobExecutionId.trim().isEmpty())
                ? "SELECT * FROM audit_logs WHERE job_execution_id = ? ORDER BY timestamp DESC LIMIT ?"
                : "SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT ?";

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (jobExecutionId != null && !jobExecutionId.trim().isEmpty()) {
                ps.setString(1, jobExecutionId.trim());
                ps.setInt(2, limit > 0 ? limit : 50);
            } else {
                ps.setInt(1, limit > 0 ? limit : 50);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AuditLogRecord rec = new AuditLogRecord();
                    rec.setAuditId(rs.getString("audit_id"));
                    rec.setFileId(rs.getString("file_id"));
                    rec.setJobExecutionId(rs.getString("job_execution_id"));
                    rec.setEventType(rs.getString("event_type"));
                    rec.setEntityName(rs.getString("entity_name"));
                    rec.setActionDetails(rs.getString("action_details"));
                    rec.setRecordsAffected(rs.getLong("records_affected"));
                    rec.setTriggeredBy(rs.getString("triggered_by"));
                    rec.setExecutionStatus(rs.getString("execution_status"));
                    rec.setTimestamp(rs.getTimestamp("timestamp"));
                    list.add(rec);
                }
            }
        } catch (Exception ex) {
            log.error("Failed to list audit logs: {}", ex.getMessage());
        }
        return list;
    }

    public void logError(DbConnectionConfig config, ErrorLogRecord error) {
        if (error == null) return;
        ensureGovernanceTablesExist(config);
        DataSource ds = dataSourceService.getOrCreateDataSource(config);

        if (error.getErrorId() == null || error.getErrorId().trim().isEmpty()) {
            error.setErrorId(BnppIdGenerator.generateErrorId());
        }
        if (error.getTimestamp() == null) {
            error.setTimestamp(new Timestamp(System.currentTimeMillis()));
        }

        String sql = """
            INSERT INTO error_logs (
                error_id, file_id, job_execution_id, entity_type, record_identifier,
                error_severity, error_type, error_message, stack_trace, failed_payload, timestamp
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, error.getErrorId());
            ps.setString(2, error.getFileId());
            ps.setString(3, error.getJobExecutionId());
            ps.setString(4, error.getEntityType());
            ps.setString(5, error.getRecordIdentifier());
            ps.setString(6, error.getErrorSeverity());
            ps.setString(7, error.getErrorType());
            ps.setString(8, error.getErrorMessage());
            ps.setString(9, error.getStackTrace());
            ps.setString(10, error.getFailedPayload());
            ps.setTimestamp(11, error.getTimestamp());
            ps.executeUpdate();
        } catch (Exception ex) {
            log.error("Failed to write error log {}: {}", error.getErrorId(), ex.getMessage());
        }
    }

    public List<ErrorLogRecord> listErrorLogs(DbConnectionConfig config, String jobExecutionId, int limit) {
        ensureGovernanceTablesExist(config);
        DataSource ds = dataSourceService.getOrCreateDataSource(config);
        List<ErrorLogRecord> list = new ArrayList<>();

        String sql = (jobExecutionId != null && !jobExecutionId.trim().isEmpty())
                ? "SELECT * FROM error_logs WHERE job_execution_id = ? ORDER BY timestamp DESC LIMIT ?"
                : "SELECT * FROM error_logs ORDER BY timestamp DESC LIMIT ?";

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (jobExecutionId != null && !jobExecutionId.trim().isEmpty()) {
                ps.setString(1, jobExecutionId.trim());
                ps.setInt(2, limit > 0 ? limit : 50);
            } else {
                ps.setInt(1, limit > 0 ? limit : 50);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ErrorLogRecord rec = new ErrorLogRecord();
                    rec.setErrorId(rs.getString("error_id"));
                    rec.setFileId(rs.getString("file_id"));
                    rec.setJobExecutionId(rs.getString("job_execution_id"));
                    rec.setEntityType(rs.getString("entity_type"));
                    rec.setRecordIdentifier(rs.getString("record_identifier"));
                    rec.setErrorSeverity(rs.getString("error_severity"));
                    rec.setErrorType(rs.getString("error_type"));
                    rec.setErrorMessage(rs.getString("error_message"));
                    rec.setStackTrace(rs.getString("stack_trace"));
                    rec.setFailedPayload(rs.getString("failed_payload"));
                    rec.setTimestamp(rs.getTimestamp("timestamp"));
                    list.add(rec);
                }
            }
        } catch (Exception ex) {
            log.error("Failed to list error logs: {}", ex.getMessage());
        }
        return list;
    }

    public List<FileHistoryRecord> listFileHistory(DbConnectionConfig config, int limit) {
        ensureGovernanceTablesExist(config);
        DataSource ds = dataSourceService.getOrCreateDataSource(config);
        List<FileHistoryRecord> list = new ArrayList<>();

        String sql = "SELECT * FROM file_history ORDER BY created_at DESC LIMIT ?";

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit > 0 ? limit : 50);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FileHistoryRecord rec = new FileHistoryRecord();
                    rec.setFileId(rs.getString("file_id"));
                    rec.setVendorName(rs.getString("vendor_name"));
                    rec.setVendorId(rs.getString("vendor_id"));
                    rec.setXmlFilename(rs.getString("xml_filename"));
                    rec.setFilenameHash(rs.getString("filename_hash"));
                    rec.setFileChecksum(rs.getString("file_checksum"));
                    rec.setFileSizeKb(rs.getLong("file_size_kb"));
                    rec.setFileVersionNumber(rs.getInt("file_version_number"));
                    rec.setUploadDate(rs.getTimestamp("upload_date"));
                    rec.setFileMetadata(rs.getString("file_metadata"));
                    rec.setIngestionFilePath(rs.getString("ingestion_file_path"));
                    rec.setIngestionFileContent(rs.getString("ingestion_file_content"));
                    rec.setTotalNumberOfRecords(rs.getLong("total_number_of_records"));
                    rec.setGsRecordCountInDb(rs.getLong("gs_record_count_in_db"));
                    rec.setSrcRecordCount(rs.getLong("src_record_count"));
                    rec.setProcessingStatus(rs.getString("processing_status"));
                    rec.setTriggeredBy(rs.getString("triggered_by"));
                    rec.setCreatedBy(rs.getString("created_by"));
                    rec.setUpdatedBy(rs.getString("updated_by"));
                    rec.setCreatedAt(rs.getTimestamp("created_at"));
                    rec.setUpdatedAt(rs.getTimestamp("updated_at"));
                    list.add(rec);
                }
            }
        } catch (Exception ex) {
            log.error("Failed to list file history: {}", ex.getMessage());
        }
        return list;
    }

    public void updateFileHistoryStats(DbConnectionConfig config, String fileId, long totalRecs, long gsCount, long srcCount, String status) {
        if (fileId == null) return;
        ensureGovernanceTablesExist(config);
        DataSource ds = dataSourceService.getOrCreateDataSource(config);

        String sql = """
            UPDATE file_history
            SET total_number_of_records = ?,
                gs_record_count_in_db = ?,
                src_record_count = ?,
                processing_status = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE file_id = ?
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, totalRecs);
            ps.setLong(2, gsCount);
            ps.setLong(3, srcCount);
            ps.setString(4, status);
            ps.setString(5, fileId);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.error("Failed to update file history stats for {}: {}", fileId, ex.getMessage());
        }
    }

    public void saveCheckpoint(DbConnectionConfig config, JobCheckpointRecord checkpoint) {
        if (checkpoint == null || checkpoint.getJobExecutionId() == null) return;
        ensureGovernanceTablesExist(config);
        DataSource ds = dataSourceService.getOrCreateDataSource(config);

        String sql = """
            MERGE INTO job_checkpoints (job_execution_id, file_id, last_record_identifier, records_streamed, status, updated_at)
            KEY (job_execution_id)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;

        try (Connection conn = ds.getConnection()) {
            String dbUrl = config.getUrl() != null ? config.getUrl().toLowerCase() : "";
            if (dbUrl.contains(":postgresql:")) {
                sql = """
                    INSERT INTO job_checkpoints (job_execution_id, file_id, last_record_identifier, records_streamed, status, updated_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (job_execution_id) DO UPDATE SET
                        last_record_identifier = EXCLUDED.last_record_identifier,
                        records_streamed = EXCLUDED.records_streamed,
                        status = EXCLUDED.status,
                        updated_at = CURRENT_TIMESTAMP
                """;
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, checkpoint.getJobExecutionId());
                ps.setString(2, checkpoint.getFileId());
                ps.setString(3, checkpoint.getLastRecordIdentifier());
                ps.setLong(4, checkpoint.getRecordsStreamed());
                ps.setString(5, checkpoint.getStatus());
                ps.executeUpdate();
            }
        } catch (Exception ex) {
            log.warn("Failed to save checkpoint for {}: {}", checkpoint.getJobExecutionId(), ex.getMessage());
        }
    }

    public JobCheckpointRecord getCheckpoint(DbConnectionConfig config, String jobExecutionId) {
        if (jobExecutionId == null) return null;
        ensureGovernanceTablesExist(config);
        DataSource ds = dataSourceService.getOrCreateDataSource(config);

        String sql = "SELECT * FROM job_checkpoints WHERE job_execution_id = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobExecutionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JobCheckpointRecord rec = new JobCheckpointRecord();
                    rec.setJobExecutionId(rs.getString("job_execution_id"));
                    rec.setFileId(rs.getString("file_id"));
                    rec.setLastRecordIdentifier(rs.getString("last_record_identifier"));
                    rec.setRecordsStreamed(rs.getLong("records_streamed"));
                    rec.setStatus(rs.getString("status"));
                    rec.setUpdatedAt(rs.getTimestamp("updated_at"));
                    return rec;
                }
            }
        } catch (Exception ex) {
            log.error("Failed to read checkpoint for {}: {}", jobExecutionId, ex.getMessage());
        }
        return null;
    }

    private File resolvePhysicalFile(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be blank.");
        }
        String clean = rawPath.trim().replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");

        File f = new File(clean);
        if (f.exists() && f.isFile()) return f;

        File inUploads = new File("uploads", clean);
        if (inUploads.exists() && inUploads.isFile()) return inUploads;

        File inSample = new File("sample-data", clean);
        if (inSample.exists() && inSample.isFile()) return inSample;

        Path ws = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path p = ws.resolve(clean);
        if (Files.exists(p) && Files.isRegularFile(p)) return p.toFile();

        throw new IllegalArgumentException("Could not locate XML file at disk path: " + rawPath);
    }

    private String computeSha256(File file) {
        try (InputStream is = Files.newInputStream(file.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = is.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception ex) {
            return "CHECKSUM-" + file.getName() + "-" + file.length();
        }
    }

    private String computeHash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes()));
        } catch (Exception ex) {
            return String.valueOf(text.hashCode());
        }
    }
}
