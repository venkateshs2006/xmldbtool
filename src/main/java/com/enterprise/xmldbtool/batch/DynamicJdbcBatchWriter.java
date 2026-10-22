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
                        paramSource.addValue(colEntry.getKey(), colEntry.getValue());
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
                log.error("Batch write failed for table '{}' with {} items. Reason: {}",
                        tableName, batchParams.size(), ex.getMessage());
                throw ex;
            }
        }
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
