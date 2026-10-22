package com.enterprise.xmldbtool.batch;

import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic Pure-JDBC Batch Item Writer.
 * Uses NamedParameterJdbcTemplate.batchUpdate() for high-throughput multi-row INSERTs
 * without JPA or Hibernate overhead.
 */
public class DynamicJdbcBatchWriter implements ItemWriter<DatabaseRowPayload> {

    private static final Logger log = LoggerFactory.getLogger(DynamicJdbcBatchWriter.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Map<String, String> sqlCache = new ConcurrentHashMap<>();

    public DynamicJdbcBatchWriter(DataSource dataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    public DynamicJdbcBatchWriter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void write(Chunk<? extends DatabaseRowPayload> chunk) throws Exception {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }

        // Group batch params by target table (Preserving insertion order for parent/child tables)
        Map<String, List<MapSqlParameterSource>> tableBatchMap = new LinkedHashMap<>();
        Map<String, Set<String>> tableColumnsMap = new LinkedHashMap<>();

        for (DatabaseRowPayload row : chunk) {
            if (row == null) continue;

            Map<String, Map<String, Object>> multiPayloads = row.getMultiTablePayloads();
            for (Map.Entry<String, Map<String, Object>> tableEntry : multiPayloads.entrySet()) {
                String tableName = tableEntry.getKey();
                Map<String, Object> colVals = tableEntry.getValue();

                if (colVals != null && !colVals.isEmpty()) {
                    tableColumnsMap.computeIfAbsent(tableName, k -> new LinkedHashSet<>()).addAll(colVals.keySet());

                    MapSqlParameterSource paramSource = new MapSqlParameterSource();
                    for (Map.Entry<String, Object> colEntry : colVals.entrySet()) {
                        Object converted = autoConvertType(colEntry.getValue(), colEntry.getKey());
                        paramSource.addValue(colEntry.getKey(), converted);
                    }
                    tableBatchMap.computeIfAbsent(tableName, k -> new ArrayList<>()).add(paramSource);
                }
            }
        }

        if (tableBatchMap.isEmpty()) {
            return;
        }

        // Execute batchUpdate for each target table in order
        for (Map.Entry<String, List<MapSqlParameterSource>> entry : tableBatchMap.entrySet()) {
            String tableName = entry.getKey();
            List<MapSqlParameterSource> batchParams = entry.getValue();
            Set<String> columnNames = tableColumnsMap.get(tableName);

            if (batchParams.isEmpty() || columnNames == null || columnNames.isEmpty()) {
                continue;
            }

            // Ensure every parameter source contains an entry for all table columns in the statement
            for (MapSqlParameterSource paramSource : batchParams) {
                for (String col : columnNames) {
                    if (!paramSource.hasValue(col)) {
                        paramSource.addValue(col, null);
                    }
                }
            }

            String insertSql = getOrCreateInsertSql(tableName, columnNames);

            try {
                MapSqlParameterSource[] batchArray = batchParams.toArray(new MapSqlParameterSource[0]);
                int[] updateCounts = jdbcTemplate.batchUpdate(insertSql, batchArray);
                if (log.isDebugEnabled()) {
                    log.debug("Batch written {} rows to table '{}'", updateCounts.length, tableName);
                }
            } catch (Exception ex) {
                Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ex);
                String rootMsg = root != null ? root.getMessage() : ex.getMessage();
                log.error("Batch write failed for table '{}' with {} items. Root database reason: {}",
                        tableName, batchParams.size(), rootMsg);
                throw ex;
            }
        }
    }

    /**
     * Converts String values from XML into appropriate Java SQL types
     * (Timestamp, Integer/Long, BigDecimal, Boolean) to prevent SQL type mismatch errors.
     */
    public static Object autoConvertType(Object value, String columnName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String strVal)) {
            return value;
        }
        String s = strVal.trim();
        if (s.isEmpty()) {
            return null;
        }

        String normCol = columnName != null ? columnName.toLowerCase() : "";

        // 1. Timestamp / Date conversion (e.g. created_at, "2026-10-01 09:15:00")
        if (normCol.contains("date") || normCol.contains("time") || normCol.contains("at") || normCol.contains("timestamp")
                || s.matches("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}.*") || s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            try {
                if (s.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$")) {
                    return java.sql.Timestamp.valueOf(s);
                }
                if (s.contains("T")) {
                    java.time.Instant instant = java.time.Instant.parse(s.endsWith("Z") ? s : s + "Z");
                    return java.sql.Timestamp.from(instant);
                }
                if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                    return java.sql.Date.valueOf(s);
                }
            } catch (Exception ignored) {}
        }

        // 2. Integer / Long conversion (e.g. id, quantity)
        if (normCol.contains("id") || normCol.contains("quantity") || normCol.contains("qty") || normCol.contains("count") || normCol.contains("num")) {
            if (s.matches("^-?\\d+$")) {
                try {
                    long l = Long.parseLong(s);
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                        return (int) l;
                    }
                    return l;
                } catch (Exception ignored) {}
            }
        }

        // 3. Numeric / Decimal / Price conversion (e.g. unit_price)
        if (normCol.contains("price") || normCol.contains("cost") || normCol.contains("amount") || normCol.contains("rate") || normCol.contains("total")) {
            if (s.matches("^-?\\d+(\\.\\d+)?$")) {
                try {
                    return new java.math.BigDecimal(s);
                } catch (Exception ignored) {}
            }
        }

        // 4. Boolean conversion
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
            return Boolean.valueOf(s);
        }

        return s;
    }

    /**
     * Constructs and caches dynamic SQL INSERT statement.
     * Example: INSERT INTO products (id, name, price) VALUES (:id, :name, :price)
     */
    private String getOrCreateInsertSql(String tableName, Set<String> columns) {
        String cacheKey = tableName + ":" + String.join(",", columns);
        return sqlCache.computeIfAbsent(cacheKey, k -> {
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
            StringBuilder placeholders = new StringBuilder(" VALUES (");

            int idx = 0;
            for (String col : columns) {
                if (idx > 0) {
                    sql.append(", ");
                    placeholders.append(", ");
                }
                sql.append(col);
                placeholders.append(":").append(col);
                idx++;
            }

            sql.append(")").append(placeholders).append(")");
            String generated = sql.toString();
            log.info("Constructed dynamic batch INSERT statement: {}", generated);
            return generated;
        });
    }

    public NamedParameterJdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }
}
