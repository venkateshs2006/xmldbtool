package com.bnpp.service;

import com.bnpp.config.TablePrefixConfig;
import com.bnpp.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Service
public class CanonicalNormalizationService {

    private static final Logger log = LoggerFactory.getLogger(CanonicalNormalizationService.class);

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "year", "month", "day", "user", "order", "group", "table", "select", "from",
            "where", "values", "value", "key", "offset", "limit", "date", "time", "timestamp",
            "primary", "foreign", "check", "column", "constraint", "index", "references"
    );

    private final DynamicDataSourceService dataSourceService;
    private final AuditGovernanceService auditGovernanceService;
    private final ObjectMapper objectMapper;
    private final TablePrefixConfig tablePrefixConfig;
    private final SchemaProvisioningService schemaProvisioningService;

    @Autowired
    public CanonicalNormalizationService(DynamicDataSourceService dataSourceService,
                                         AuditGovernanceService auditGovernanceService,
                                         ObjectMapper objectMapper,
                                         @Autowired(required = false) TablePrefixConfig tablePrefixConfig,
                                         @Autowired(required = false) SchemaProvisioningService schemaProvisioningService) {
        this.dataSourceService = dataSourceService;
        this.auditGovernanceService = auditGovernanceService;
        this.objectMapper = objectMapper;
        this.tablePrefixConfig = tablePrefixConfig != null ? tablePrefixConfig : new TablePrefixConfig();
        this.schemaProvisioningService = schemaProvisioningService;
    }

    private String safeCol(String col) {
        if (col == null) return null;
        String lower = col.trim().toLowerCase();
        if (RESERVED_KEYWORDS.contains(lower)) {
            return "\"" + lower + "\"";
        }
        return lower;
    }

    public long normalizeAndUpsertCanonical(DbConnectionConfig config,
                                           FileHistoryRecord fileRecord,
                                           List<FieldMapping> mappings,
                                           String jobExecutionId) throws Exception {

        DataSource ds = dataSourceService.getOrCreateDataSource(config);
        Map<String, List<FieldMapping>> tableMap = groupMappingsByTable(mappings);

        long totalCanonicalUpserted = 0;
        long totalGsRecords = 0;

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);

            for (Map.Entry<String, List<FieldMapping>> entry : tableMap.entrySet()) {
                String rawTable = entry.getKey();
                String entityName = tablePrefixConfig.cleanEntityName(rawTable);
                String gsTable = tablePrefixConfig.getGoldenSourceTableName(entityName);
                String canonicalTable = tablePrefixConfig.getCanonicalTableName(entityName);
                List<FieldMapping> cols = entry.getValue();

                FieldMapping pkMapping = cols.stream()
                        .filter(m -> "PK".equalsIgnoreCase(m.getConstraintType()))
                        .findFirst()
                        .orElse(null);

                String pkCol = pkMapping != null ? pkMapping.getTargetColumn().toLowerCase() : "id";

                // Query golden source records for this job execution
                StringBuilder selectGsSql = new StringBuilder("SELECT bnpp_id, ");
                for (FieldMapping m : cols) {
                    selectGsSql.append(safeCol(m.getTargetColumn())).append(", ");
                }
                selectGsSql.append("file_id, file_version, vendor_id FROM ").append(gsTable)
                        .append(" WHERE job_execution_id = ? ORDER BY created_at ASC, bnpp_id ASC");

                List<GsRecordData> gsRecords = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(selectGsSql.toString())) {
                    ps.setString(1, jobExecutionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            GsRecordData data = new GsRecordData();
                            data.bnppId = rs.getString("bnpp_id");
                            data.fileId = rs.getString("file_id");
                            data.fileVersion = rs.getInt("file_version");
                            data.vendorId = rs.getString("vendor_id");
                            for (FieldMapping m : cols) {
                                String col = m.getTargetColumn().toLowerCase();
                                data.columnValues.put(col, rs.getObject(col));
                            }
                            gsRecords.add(data);
                        }
                    }
                }

                totalGsRecords += gsRecords.size();

                for (GsRecordData gs : gsRecords) {
                    Object pkVal = gs.columnValues.get(pkCol);
                    if (pkVal == null || String.valueOf(pkVal).trim().isEmpty()) {
                        log.warn("Skipping record with null/empty PK for table {}", canonicalTable);
                        continue;
                    }

                    // Check if canonical record already exists
                    String checkSql = "SELECT starting_bnpp_id, current_bnpp_id, historical_bnpp_ids FROM "
                            + canonicalTable + " WHERE " + safeCol(pkCol) + " = ?";

                    ExistingCanonical existing = null;
                    try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                        ps.setObject(1, pkVal);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                existing = new ExistingCanonical(
                                        rs.getString("starting_bnpp_id"),
                                        rs.getString("current_bnpp_id"),
                                        rs.getString("historical_bnpp_ids")
                                );
                            }
                        }
                    }

                    if (existing == null) {
                        // INSERT new canonical record
                        List<String> histList = new ArrayList<>();
                        histList.add(gs.bnppId);
                        String histJson = objectMapper.writeValueAsString(histList);

                        StringBuilder insertSql = new StringBuilder();
                        insertSql.append("INSERT INTO ").append(canonicalTable).append(" (");
                        for (FieldMapping m : cols) {
                            insertSql.append(safeCol(m.getTargetColumn())).append(", ");
                        }
                        insertSql.append("starting_bnpp_id, current_bnpp_id, file_id, file_version_number, vendor_id, historical_bnpp_ids, created_at, updated_at, created_by, updated_by) VALUES (");
                        insertSql.append("?, ".repeat(cols.size()));
                        insertSql.append("?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)");

                        try (PreparedStatement ps = conn.prepareStatement(insertSql.toString())) {
                            int idx = 1;
                            for (FieldMapping m : cols) {
                                ps.setObject(idx++, gs.columnValues.get(m.getTargetColumn().toLowerCase()));
                            }
                            ps.setString(idx++, gs.bnppId); // starting_bnpp_id
                            ps.setString(idx++, gs.bnppId); // current_bnpp_id
                            ps.setString(idx++, fileRecord.getFileId());
                            ps.setInt(idx++, fileRecord.getFileVersionNumber());
                            ps.setString(idx++, fileRecord.getVendorId());
                            ps.setString(idx++, histJson);
                            ps.setString(idx++, fileRecord.getTriggeredBy());
                            ps.setString(idx++, fileRecord.getTriggeredBy());
                            ps.executeUpdate();
                        }
                    } else {
                        // UPDATE existing canonical record
                        List<String> histList;
                        try {
                            histList = objectMapper.readValue(
                                    existing.historicalBnppIds != null ? existing.historicalBnppIds : "[]",
                                    new TypeReference<List<String>>() {}
                            );
                        } catch (Exception e) {
                            histList = new ArrayList<>();
                        }
                        if (!histList.contains(gs.bnppId)) {
                            histList.add(gs.bnppId);
                        }
                        String histJson = objectMapper.writeValueAsString(histList);

                        StringBuilder updateSql = new StringBuilder();
                        updateSql.append("UPDATE ").append(canonicalTable).append(" SET ");
                        for (FieldMapping m : cols) {
                            if (!m.getTargetColumn().equalsIgnoreCase(pkCol)) {
                                updateSql.append(safeCol(m.getTargetColumn())).append(" = ?, ");
                            }
                        }
                        updateSql.append("current_bnpp_id = ?, file_id = ?, file_version_number = ?, vendor_id = ?, historical_bnpp_ids = ?, updated_at = CURRENT_TIMESTAMP, updated_by = ? WHERE ").append(safeCol(pkCol)).append(" = ?");

                        try (PreparedStatement ps = conn.prepareStatement(updateSql.toString())) {
                            int idx = 1;
                            for (FieldMapping m : cols) {
                                if (!m.getTargetColumn().equalsIgnoreCase(pkCol)) {
                                    ps.setObject(idx++, gs.columnValues.get(m.getTargetColumn().toLowerCase()));
                                }
                            }
                            ps.setString(idx++, gs.bnppId); // update current_bnpp_id
                            ps.setString(idx++, fileRecord.getFileId());
                            ps.setInt(idx++, fileRecord.getFileVersionNumber());
                            ps.setString(idx++, fileRecord.getVendorId());
                            ps.setString(idx++, histJson);
                            ps.setString(idx++, fileRecord.getTriggeredBy());
                            ps.setObject(idx++, pkVal);
                            ps.executeUpdate();
                        }
                    }

                    totalCanonicalUpserted++;
                }
            }

            conn.commit();
        }

        // Audit: NORMALIZATION_COMPLETED
        AuditLogRecord normAudit = new AuditLogRecord();
        normAudit.setFileId(fileRecord.getFileId());
        normAudit.setJobExecutionId(jobExecutionId);
        normAudit.setEventType("NORMALIZATION_COMPLETED");
        normAudit.setEntityName("CANONICAL_PIPELINE");
        normAudit.setActionDetails("Promoted and normalized " + totalCanonicalUpserted + " records into Canonical tables.");
        normAudit.setRecordsAffected(totalCanonicalUpserted);
        normAudit.setExecutionStatus("NORMALIZATION_COMPLETED");
        normAudit.setTriggeredBy(fileRecord.getTriggeredBy());
        auditGovernanceService.logAudit(config, normAudit);

        // Update file_history execution statistics
        auditGovernanceService.updateFileHistoryStats(
                config,
                fileRecord.getFileId(),
                totalGsRecords,
                totalGsRecords,
                totalCanonicalUpserted,
                "COMPLETED"
        );

        log.info("Canonical normalization completed: {} records normalized into canonical tables.", totalCanonicalUpserted);
        return totalCanonicalUpserted;
    }

    private Map<String, List<FieldMapping>> groupMappingsByTable(List<FieldMapping> mappings) {
        Map<String, List<FieldMapping>> map = new LinkedHashMap<>();
        if (mappings == null) return map;
        for (FieldMapping m : mappings) {
            if (m.getTargetTable() == null || m.getTargetTable().trim().isEmpty()) continue;
            map.computeIfAbsent(m.getTargetTable().trim(), k -> new ArrayList<>()).add(m);
        }
        return map;
    }

    private static class GsRecordData {
        String bnppId;
        String fileId;
        int fileVersion;
        String vendorId;
        Map<String, Object> columnValues = new HashMap<>();
    }

    private record ExistingCanonical(String startingBnppId, String currentBnppId, String historicalBnppIds) {}
}
