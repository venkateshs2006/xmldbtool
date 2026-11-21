package com.bnpp.batch;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.bnpp.model.DatabaseRowPayload;
import com.bnpp.util.BnppIdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dynamic JDBC Batch Writer for high-throughput streaming writes across all 4
 * database layers: 1. Layer 2: Universal Staging (staging_xml_records) 2. Layer
 * 3: Golden Source Historical Tables (gs_<entity>) - Pure Append-Only INSERT 3.
 * Layer 4: Canonical Tables (<entity>) - UPSERT / Merge preserving BNPP ID
 * enterprise lineage
 */
public class DynamicJdbcBatchWriter implements ItemWriter<DatabaseRowPayload>, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(DynamicJdbcBatchWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AtomicLong totalRowsWritten = new AtomicLong(0);
    private final Map<String, Long> tableWriteCounts = new ConcurrentHashMap<>();
    private final Map<String, String> pkColumnCache = new ConcurrentHashMap<>();

    private List<String> tableOrder = new ArrayList<>();
    private DatabaseErrorSkipListener skipListener;
    private List<com.bnpp.model.FieldMapping> mappings = new ArrayList<>();

    public DynamicJdbcBatchWriter(DataSource dataSource) {
        this(dataSource, Collections.emptyList(), null, null);
    }

    public DynamicJdbcBatchWriter(DataSource dataSource, List<String> tableOrder) {
        this(dataSource, tableOrder, null, null);
    }

    public DynamicJdbcBatchWriter(DataSource dataSource, List<String> tableOrder, DatabaseErrorSkipListener skipListener) {
        this(dataSource, tableOrder, skipListener, null);
    }

    public DynamicJdbcBatchWriter(DataSource dataSource, List<String> tableOrder, DatabaseErrorSkipListener skipListener, List<com.bnpp.model.FieldMapping> mappings) {
        this.dataSource = dataSource;
        this.jdbcTemplate = (dataSource != null) ? new NamedParameterJdbcTemplate(dataSource) : null;
        this.tableOrder = tableOrder != null ? new ArrayList<>(tableOrder) : new ArrayList<>();
        this.skipListener = skipListener;
        this.mappings = mappings != null ? new ArrayList<>(mappings) : new ArrayList<>();
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.totalRowsWritten.set(0);
        this.tableWriteCounts.clear();
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (stepExecution != null) {
            stepExecution.getExecutionContext().putLong("actualRowsWritten", totalRowsWritten.get());
        }
        return ExitStatus.COMPLETED;
    }

    @Override
    public void write(Chunk<? extends DatabaseRowPayload> chunk) throws Exception {
        if (chunk == null || chunk.isEmpty() || dataSource == null) {
            return;
        }

        List<Map<String, Object>> stagingBatch = new ArrayList<>();
        Map<String, List<Map<String, Object>>> gsBatch = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> canBatch = new LinkedHashMap<>();

        for (DatabaseRowPayload payload : chunk) {
            if (payload == null) {
                continue;
            }

            stagingBatch.addAll(payload.getStagingRecords());

            for (Map.Entry<String, List<Map<String, Object>>> entry : payload.getGoldenSourceRecords().entrySet()) {
                gsBatch.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }

            for (Map.Entry<String, List<Map<String, Object>>> entry : payload.getCanonicalRecords().entrySet()) {
                canBatch.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // 1. Universal Staging
            if (!stagingBatch.isEmpty()) {
                writeStagingRecords(conn, stagingBatch);
            }

            // 2. Layer 3 Golden Source
            for (Map.Entry<String, List<Map<String, Object>>> entry : gsBatch.entrySet()) {
                String tableName = entry.getKey();
                List<Map<String, Object>> rows = entry.getValue();
                writeGoldenSourceRows(conn, tableName, rows);
                tableWriteCounts.compute(tableName, (k, v) -> (v == null ? 0L : v) + rows.size());
                totalRowsWritten.addAndGet(rows.size());
            }

            // 3. Layer 4 Canonical Tables
            for (Map.Entry<String, List<Map<String, Object>>> entry : canBatch.entrySet()) {
                String tableName = entry.getKey();
                List<Map<String, Object>> rows = entry.getValue();
                System.out.println("==========================================");
                System.out.println(rows.toString());
                System.out.println("==========================================");
                writeCanonicalRows(conn, tableName, rows);
                tableWriteCounts.compute(tableName, (k, v) -> (v == null ? 0L : v) + rows.size());
            }

            conn.commit();
        } catch (Exception ex) {
            log.error("Batch write failure: {}", ex.getMessage(), ex);
            if (skipListener != null) {
                skipListener.onSkipInWrite(chunk.iterator().next(), ex);
            }
            throw ex;
        }
    }

    private void writeStagingRecords(Connection conn, List<Map<String, Object>> rows) throws Exception {
        String sql = """
            INSERT INTO staging_xml_records (
                job_execution_id, file_id, bnpp_id, tag_name, version_number,
                entity_type, record_identifier, record_action, record_date, xml_file_version,
                raw_payload, payload_hash, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, Object> r : rows) {
                ps.setString(1, (String) r.get("job_execution_id"));
                ps.setString(2, (String) r.get("file_id"));
                ps.setString(3, (String) r.get("bnpp_id"));
                ps.setString(4, (String) r.get("tag_name"));
                ps.setInt(5, r.get("version_number") instanceof Number n ? n.intValue() : 1);
                ps.setString(6, (String) r.get("entity_type"));
                ps.setString(7, (String) r.get("record_identifier"));
                ps.setString(8, (String) r.get("record_action"));
                ps.setString(9, (String) r.get("record_date"));
                ps.setInt(10, r.get("xml_file_version") instanceof Number n ? n.intValue() : 1);
                ps.setString(11, (String) r.get("raw_payload"));
                ps.setString(12, (String) r.get("payload_hash"));
                ps.setString(13, (String) r.get("status"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void writeGoldenSourceRows(Connection conn, String tableName, List<Map<String, Object>> rows) throws Exception {
        if (rows.isEmpty()) {
            return;
        }

        Map<String, Object> sample = rows.get(0);
        List<String> cols = new ArrayList<>(sample.keySet());

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        sql.append(String.join(", ", cols));
        sql.append(") VALUES (");
        sql.append("?, ".repeat(cols.size() - 1)).append("?)");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (Map<String, Object> r : rows) {
                int idx = 1;
                for (String c : cols) {
                    ps.setObject(idx++, r.get(c));
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void writeCanonicalRows(Connection conn, String tableName, List<Map<String, Object>> rows) throws Exception {
        if (rows.isEmpty()) {
            return;
        }

        String pkCol = resolvePrimaryKeyColumn(conn, tableName, rows.get(0));

        for (Map<String, Object> r : rows) {
            if (r.get(pkCol) == null) {
                for (String k : r.keySet()) {
                    if (k.equalsIgnoreCase(pkCol)) {
                        r.put(pkCol, r.get(k));
                        break;
                    }
                }
            }
            if (r.get(pkCol) == null) {
                Object fallbackId = r.getOrDefault("starting_bnpp_id", r.getOrDefault("current_bnpp_id", BnppIdGenerator.generateBnppId()));
                r.put(pkCol, fallbackId);
            }

            Object pkVal = r.get(pkCol);
            if (pkVal == null) {
                continue;
            }

            String selectSql = "SELECT starting_bnpp_id, current_bnpp_id, historical_bnpp_ids FROM "
                    + tableName + " WHERE " + pkCol + " = ?";

            String startingBnpp = null;
            String currentBnpp = (String) r.get("current_bnpp_id");
            String histJson = null;

            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setObject(1, pkVal);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        startingBnpp = rs.getString("starting_bnpp_id");
                        histJson = rs.getString("historical_bnpp_ids");
                    }
                }
            }

            if (startingBnpp == null) {
                // New record: INSERT
                List<String> cols = new ArrayList<>(r.keySet());
                StringBuilder insertSql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
                insertSql.append(String.join(", ", cols)).append(") VALUES (");
                insertSql.append("?, ".repeat(cols.size() - 1)).append("?)");

                try (PreparedStatement ps = conn.prepareStatement(insertSql.toString())) {
                    int idx = 1;
                    for (String c : cols) {
                        ps.setObject(idx++, r.get(c));
                    }
                    ps.executeUpdate();
                }
            } else {
                // Existing record: UPDATE latest values while preserving starting_bnpp_id and appending to historical_bnpp_ids
                List<String> histList;
                try {
                    histList = MAPPER.readValue(histJson != null ? histJson : "[]", new TypeReference<List<String>>() {
                    });
                } catch (Exception e) {
                    histList = new ArrayList<>();
                }
                if (!histList.contains(currentBnpp)) {
                    histList.add(currentBnpp);
                }
                String updatedHistJson = MAPPER.writeValueAsString(histList);

                List<String> updateCols = new ArrayList<>();
                for (String c : r.keySet()) {
                    if (!c.equalsIgnoreCase(pkCol) && !c.equalsIgnoreCase("starting_bnpp_id") && !c.equalsIgnoreCase("historical_bnpp_ids")) {
                        updateCols.add(c);
                    }
                }

                StringBuilder updateSql = new StringBuilder("UPDATE ").append(tableName).append(" SET ");
                for (String c : updateCols) {
                    updateSql.append(c).append(" = ?, ");
                }
                updateSql.append("historical_bnpp_ids = ?, updated_at = CURRENT_TIMESTAMP WHERE ").append(pkCol).append(" = ?");

                try (PreparedStatement ps = conn.prepareStatement(updateSql.toString())) {
                    int idx = 1;
                    for (String c : updateCols) {
                        ps.setObject(idx++, r.get(c));
                    }
                    ps.setString(idx++, updatedHistJson);
                    ps.setObject(idx++, pkVal);
                    ps.executeUpdate();
                }
            }
        }
    }

    private String resolvePrimaryKeyColumn(Connection conn, String tableName, Map<String, Object> sampleRow) {
        String cached = pkColumnCache.get(tableName.toLowerCase());
        if (cached != null) {
            return cached;
        }

        // 1. Explicit PK from mappings
        if (mappings != null && !mappings.isEmpty()) {
            for (com.bnpp.model.FieldMapping m : mappings) {
                if (tableName.equalsIgnoreCase(m.getTargetTable()) && "PK".equalsIgnoreCase(m.getConstraintType())) {
                    String col = m.getTargetColumn().toLowerCase();
                    if (sampleRow != null) {
                        for (String k : sampleRow.keySet()) {
                            if (k.equalsIgnoreCase(col)) {
                                pkColumnCache.put(tableName.toLowerCase(), k);
                                return k;
                            }
                        }
                    }
                    pkColumnCache.put(tableName.toLowerCase(), col);
                    return col;
                }
            }
        }

        // 2. Query DatabaseMetaData
        if (conn != null) {
            try {
                DatabaseMetaData meta = conn.getMetaData();
                String schema = null;
                try {
                    schema = conn.getSchema();
                } catch (Throwable ignored) {}

                for (String tName : List.of(tableName, tableName.toUpperCase(), tableName.toLowerCase())) {
                    try (ResultSet rs = meta.getPrimaryKeys(null, schema, tName)) {
                        if (rs.next()) {
                            String pk = rs.getString("COLUMN_NAME");
                            if (pk != null && !pk.isEmpty()) {
                                if (sampleRow != null) {
                                    for (String k : sampleRow.keySet()) {
                                        if (k.equalsIgnoreCase(pk)) {
                                            pkColumnCache.put(tableName.toLowerCase(), k);
                                            return k;
                                        }
                                    }
                                }
                                pkColumnCache.put(tableName.toLowerCase(), pk.toLowerCase());
                                return pk.toLowerCase();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Fallback: match sampleRow keys for entity-specific PK, then 'id'
        if (sampleRow != null) {
            String entityId = tableName.toLowerCase() + "_id";
            for (String k : sampleRow.keySet()) {
                if (k.equalsIgnoreCase(entityId)) {
                    pkColumnCache.put(tableName.toLowerCase(), k);
                    return k;
                }
            }
            for (String k : sampleRow.keySet()) {
                if (k.equalsIgnoreCase("id")) {
                    pkColumnCache.put(tableName.toLowerCase(), k);
                    return k;
                }
            }
        }

        pkColumnCache.put(tableName.toLowerCase(), "id");
        return "id";
    }

    public long getTotalRowsWritten() {
        return totalRowsWritten.get();
    }

    public Map<String, Long> getTableWriteCounts() {
        return Collections.unmodifiableMap(tableWriteCounts);
    }
}
