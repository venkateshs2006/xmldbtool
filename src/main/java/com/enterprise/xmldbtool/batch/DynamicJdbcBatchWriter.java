package com.enterprise.xmldbtool.batch;

import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic Pure-JDBC Batch Item Writer with automated UPSERT support.
 * Executes high-throughput batch updates (INSERT ON CONFLICT DO UPDATE)
 * without JPA or Hibernate overhead, automatically resolving duplicate key collisions.
 */
public class DynamicJdbcBatchWriter implements ItemWriter<DatabaseRowPayload>, org.springframework.batch.core.StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(DynamicJdbcBatchWriter.class);

    private final DataSource dataSource;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Map<String, String> sqlCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> pkCache = new ConcurrentHashMap<>();
    private String databaseProductName = null;
    private List<String> tableOrder = new ArrayList<>();

    private boolean continueOnTableError = true;
    private DatabaseErrorSkipListener skipListener;

    private final java.util.concurrent.atomic.AtomicLong totalRowsWritten = new java.util.concurrent.atomic.AtomicLong(0);
    private final Map<String, Long> tableWriteCounts = new ConcurrentHashMap<>();
    private volatile org.springframework.batch.core.StepExecution stepExecution;

    public void clearCaches() {
        sqlCache.clear();
        pkCache.clear();
        tableColumnCaseMapCache.clear();
    }

    @Override
    public void beforeStep(org.springframework.batch.core.StepExecution stepExecution) {
        this.stepExecution = stepExecution;
        this.totalRowsWritten.set(0);
        this.tableWriteCounts.clear();
        if (stepExecution != null) {
            stepExecution.getExecutionContext().putLong("actualRowsWritten", 0L);
            if (this.tableOrder != null && !this.tableOrder.isEmpty()) {
                stepExecution.getExecutionContext().putString("targetTables", String.join(", ", this.tableOrder));
            }
        }
    }

    @Override
    public org.springframework.batch.core.ExitStatus afterStep(org.springframework.batch.core.StepExecution stepExecution) {
        if (stepExecution != null) {
            stepExecution.getExecutionContext().putLong("actualRowsWritten", totalRowsWritten.get());
            if (this.tableOrder != null && !this.tableOrder.isEmpty()) {
                stepExecution.getExecutionContext().putString("targetTables", String.join(", ", this.tableOrder));
            }
            stepExecution.getExecutionContext().put("tableWriteCounts", new LinkedHashMap<>(tableWriteCounts));
            if (this.skipListener != null) {
                stepExecution.getExecutionContext().put("tableErrorCounts", new LinkedHashMap<>(this.skipListener.getTableErrorCounts()));
            }
        }
        return null;
    }

    public long getTotalRowsWritten() {
        return totalRowsWritten.get();
    }

    public Map<String, Long> getTableWriteCounts() {
        return new LinkedHashMap<>(tableWriteCounts);
    }

    public void setExplicitPrimaryKeys(Map<String, Set<String>> explicitPks) {
        if (explicitPks != null) {
            for (Map.Entry<String, Set<String>> entry : explicitPks.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    this.pkCache.put(entry.getKey().toLowerCase(), new LinkedHashSet<>(entry.getValue()));
                }
            }
        }
    }

    public DynamicJdbcBatchWriter(DataSource dataSource) {
        this(dataSource, Collections.emptyList());
    }

    public DynamicJdbcBatchWriter(DataSource dataSource, List<String> tableOrder) {
        this(dataSource, tableOrder, null);
    }

    public DynamicJdbcBatchWriter(DataSource dataSource, List<String> tableOrder, DatabaseErrorSkipListener skipListener) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        this.tableOrder = tableOrder != null ? new ArrayList<>(tableOrder) : new ArrayList<>();
        this.skipListener = skipListener;
        detectDatabaseProduct(dataSource);
    }

    public DynamicJdbcBatchWriter(NamedParameterJdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Collections.emptyList());
    }

    public DynamicJdbcBatchWriter(NamedParameterJdbcTemplate jdbcTemplate, List<String> tableOrder) {
        this(jdbcTemplate, tableOrder, null);
    }

    public DynamicJdbcBatchWriter(NamedParameterJdbcTemplate jdbcTemplate, List<String> tableOrder, DatabaseErrorSkipListener skipListener) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = (jdbcTemplate != null && jdbcTemplate.getJdbcTemplate() != null)
                ? jdbcTemplate.getJdbcTemplate().getDataSource() : null;
        this.tableOrder = tableOrder != null ? new ArrayList<>(tableOrder) : new ArrayList<>();
        this.skipListener = skipListener;
        if (this.dataSource != null) {
            detectDatabaseProduct(this.dataSource);
        }
    }

    public boolean isContinueOnTableError() {
        return continueOnTableError;
    }

    public void setContinueOnTableError(boolean continueOnTableError) {
        this.continueOnTableError = continueOnTableError;
    }

    public DatabaseErrorSkipListener getSkipListener() {
        return skipListener;
    }

    public void setSkipListener(DatabaseErrorSkipListener skipListener) {
        this.skipListener = skipListener;
    }

    public List<String> getTableOrder() {
        return tableOrder;
    }

    public void setTableOrder(List<String> tableOrder) {
        this.tableOrder = tableOrder != null ? new ArrayList<>(tableOrder) : new ArrayList<>();
    }

    /**
     * Helper to compute deterministic table execution order sequence based on tag appearance in mappings,
     * while guaranteeing that foreign key parent tables are inserted before dependent child tables.
     */
    public static List<String> determineTableOrder(List<String> explicitOrder, List<com.enterprise.xmldbtool.model.FieldMapping> mappings, String defaultTable) {
        Set<String> ordered = new LinkedHashSet<>();
        if (explicitOrder != null) {
            for (String t : explicitOrder) {
                if (t != null && !t.trim().isEmpty()) {
                    ordered.add(t.trim());
                }
            }
        }
        if (mappings != null) {
            for (com.enterprise.xmldbtool.model.FieldMapping m : mappings) {
                String tbl = m.getTargetTable() != null && !m.getTargetTable().trim().isEmpty()
                        ? m.getTargetTable().trim() : defaultTable;
                if (tbl != null && !tbl.trim().isEmpty()) {
                    ordered.add(tbl);
                }
            }
        }
        if (ordered.isEmpty() && defaultTable != null && !defaultTable.trim().isEmpty()) {
            ordered.add(defaultTable.trim());
        }

        List<String> rawList = new ArrayList<>(ordered);
        return sortTablesByForeignKeys(rawList, mappings);
    }

    /**
     * Topologically sorts tables based on foreign key column naming conventions
     * (e.g. child table has 'country_id' -> parent table 'countries' must precede it).
     */
    public static List<String> sortTablesByForeignKeys(List<String> tables, List<com.enterprise.xmldbtool.model.FieldMapping> mappings) {
        if (tables == null || tables.size() <= 1 || mappings == null || mappings.isEmpty()) {
            return tables != null ? new ArrayList<>(tables) : new ArrayList<>();
        }

        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (String t : tables) {
            dependencies.put(t, new LinkedHashSet<>());
        }

        for (com.enterprise.xmldbtool.model.FieldMapping m : mappings) {
            String childTable = m.getTargetTable();
            String col = m.getTargetColumn();
            if (childTable == null || col == null || !dependencies.containsKey(childTable)) continue;

            String normCol = col.toLowerCase().trim();
            if (normCol.endsWith("_id") && normCol.length() > 3) {
                String refPrefix = normCol.substring(0, normCol.length() - 3).replace("_", "");
                for (String potentialParent : tables) {
                    if (potentialParent.equalsIgnoreCase(childTable)) continue;
                    String normParent = potentialParent.toLowerCase().replace("_", "");
                    if (isParentTableForFk(potentialParent, normCol)) {
                        dependencies.get(childTable).add(potentialParent);
                    }
                }
            }
        }

        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String t : tables) {
            visitTopological(t, dependencies, visited, new HashSet<>(), sorted);
        }

        return sorted;
    }

    private static void visitTopological(String node, Map<String, Set<String>> dependencies, Set<String> visited, Set<String> currentPath, List<String> result) {
        if (visited.contains(node)) return;
        if (currentPath.contains(node)) {
            visited.add(node);
            result.add(node);
            return;
        }

        currentPath.add(node);
        Set<String> parents = dependencies.getOrDefault(node, Collections.emptySet());
        for (String p : parents) {
            if (dependencies.containsKey(p)) {
                visitTopological(p, dependencies, visited, currentPath, result);
            }
        }
        currentPath.remove(node);
        visited.add(node);
        result.add(node);
    }

    public static boolean isParentTableForFk(String parentTable, String fkColumnName) {
        if (parentTable == null || fkColumnName == null) return false;
        String normCol = fkColumnName.toLowerCase().trim();
        if (!normCol.endsWith("_id") || normCol.length() <= 3) return false;

        String refPrefix = normCol.substring(0, normCol.length() - 3).replace("_", "");
        String normParent = parentTable.toLowerCase().replace("_", "");

        if (normParent.equals(refPrefix) ||
                normParent.equals(refPrefix + "s") ||
                normParent.equals(refPrefix + "es") ||
                (refPrefix.endsWith("y") && normParent.equals(refPrefix.substring(0, refPrefix.length() - 1) + "ies")) ||
                (normParent.endsWith("ies") && refPrefix.equals(normParent.substring(0, normParent.length() - 3) + "y"))) {
            return true;
        }

        // Support numbered plural tables like descriptionlists1 for FK descriptionlist1_id
        String pNoNumS = normParent.replaceAll("s(?=\\d*$)", "");
        String rNoNumS = refPrefix.replaceAll("s(?=\\d*$)", "");
        return pNoNumS.equals(rNoNumS);
    }

    private void detectDatabaseProduct(DataSource ds) {
        if (ds == null) return;
        try (Connection conn = ds.getConnection()) {
            this.databaseProductName = conn.getMetaData().getDatabaseProductName();
            log.info("DynamicJdbcBatchWriter detected target database engine: {}", this.databaseProductName);
        } catch (Exception ex) {
            log.warn("Could not determine database product name: {}", ex.getMessage());
        }
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

            Map<String, List<Map<String, Object>>> multiRecords = row.getMultiTableRecords();
            if (multiRecords != null && !multiRecords.isEmpty()) {
                for (Map.Entry<String, List<Map<String, Object>>> tableEntry : multiRecords.entrySet()) {
                    String tableName = tableEntry.getKey();
                    List<Map<String, Object>> rows = tableEntry.getValue();
                    if (rows == null || rows.isEmpty()) continue;

                    for (Map<String, Object> colVals : rows) {
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
            } else {
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
        }

        if (tableBatchMap.isEmpty()) {
            return;
        }

        // Determine table execution order sequence based on tag order sequence
        List<String> executionOrder = new ArrayList<>();
        if (this.tableOrder != null && !this.tableOrder.isEmpty()) {
            for (String tbl : this.tableOrder) {
                if (tableBatchMap.containsKey(tbl)) {
                    executionOrder.add(tbl);
                }
            }
        }
        for (String tbl : tableBatchMap.keySet()) {
            if (!executionOrder.contains(tbl)) {
                executionOrder.add(tbl);
            }
        }

        // Execute batchUpdate for each target table strictly in the tag order sequence
        for (int i = 0; i < executionOrder.size(); i++) {
            String tableName = executionOrder.get(i);
            List<MapSqlParameterSource> batchParams = tableBatchMap.get(tableName);
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

            java.sql.Connection conn = null;
            java.sql.Savepoint savepoint = null;
            if (this.dataSource != null) {
                try {
                    conn = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(this.dataSource);
                    if (!conn.getAutoCommit()) {
                        savepoint = conn.setSavepoint("sp_" + tableName.replaceAll("[^a-zA-Z0-9_]", "_") + "_" + i);
                    }
                } catch (Exception ignored) {
                    // Savepoints might not be supported on all drivers/modes
                }
            }
            // Dynamically ensure missing columns from delta XML files are added via ALTER TABLE
            ensureColumnsExist(tableName, columnNames);

            String insertOrUpsertSql = getOrCreateInsertSql(tableName, columnNames);

            try {
                MapSqlParameterSource[] batchArray = batchParams.toArray(new MapSqlParameterSource[0]);
                int[] updateCounts = jdbcTemplate.batchUpdate(insertOrUpsertSql, batchArray);
                long writtenCount = updateCounts.length;
                totalRowsWritten.addAndGet(writtenCount);
                tableWriteCounts.compute(tableName, (k, v) -> (v == null ? 0L : v) + writtenCount);

                if (this.stepExecution != null) {
                    this.stepExecution.getExecutionContext().putLong("actualRowsWritten", totalRowsWritten.get());
                    this.stepExecution.getExecutionContext().put("tableWriteCounts", new LinkedHashMap<>(tableWriteCounts));
                }

                if (log.isDebugEnabled()) {
                    log.debug("Batch written {} rows to table '{}' (Sequence #{} of {}) - Total written so far: {}",
                            updateCounts.length, tableName, (i + 1), executionOrder.size(), totalRowsWritten.get());
                }
                if (savepoint != null && conn != null) {
                    try {
                        conn.releaseSavepoint(savepoint);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ex) {
                // If write failed because of a missing column from delta schema evolution, attempt self-healing ALTER TABLE and retry once
                String exMsg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
                if (exMsg.contains("value too long")) {
                    try {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("column \"([^\"]+)\"").matcher(ex.getMessage());
                        if (m.find()) {
                            String colName = m.group(1).split(" ")[0];
                            boolean isOracleDb = databaseProductName != null && databaseProductName.toLowerCase().contains("oracle");
                            String alterSql = isOracleDb ? "ALTER TABLE " + tableName + " MODIFY (" + colName + " CLOB)"
                                    : "ALTER TABLE " + tableName + " ALTER COLUMN " + colName + " SET DATA TYPE TEXT";
                            jdbcTemplate.getJdbcOperations().execute(alterSql);
                            log.info("Expanded column '{}' in table '{}' to CLOB/TEXT due to value too long error", colName, tableName);
                            MapSqlParameterSource[] retryArray = batchParams.toArray(new MapSqlParameterSource[0]);
                            int[] updateCounts = jdbcTemplate.batchUpdate(insertOrUpsertSql, retryArray);
                            long writtenCount = updateCounts.length;
                            totalRowsWritten.addAndGet(writtenCount);
                            tableWriteCounts.compute(tableName, (k, v) -> (v == null ? 0L : v) + writtenCount);
                            if (savepoint != null && conn != null) {
                                try { conn.releaseSavepoint(savepoint); } catch (Exception ignored) {}
                            }
                            continue;
                        }
                    } catch (Exception retryEx) {
                        log.warn("Value too long self-healing retry failed for '{}': {}", tableName, retryEx.getMessage());
                    }
                } else if (exMsg.contains("not found") || exMsg.contains("does not exist") || exMsg.contains("ora-00904") || exMsg.contains("column")) {
                    try {
                        log.info("Attempting self-healing ALTER TABLE for table '{}' due to missing column error: {}", tableName, ex.getMessage());
                        ensureColumnsExist(tableName, columnNames);
                        insertOrUpsertSql = getOrCreateInsertSql(tableName, columnNames);
                        MapSqlParameterSource[] retryArray = batchParams.toArray(new MapSqlParameterSource[0]);
                        int[] updateCounts = jdbcTemplate.batchUpdate(insertOrUpsertSql, retryArray);
                        long writtenCount = updateCounts.length;
                        totalRowsWritten.addAndGet(writtenCount);
                        tableWriteCounts.compute(tableName, (k, v) -> (v == null ? 0L : v) + writtenCount);
                        if (savepoint != null && conn != null) {
                            try { conn.releaseSavepoint(savepoint); } catch (Exception ignored) {}
                        }
                        continue; // Self-healing succeeded, move to next table
                    } catch (Exception retryEx) {
                        log.warn("Self-healing retry for table '{}' failed: {}", tableName, retryEx.getMessage());
                    }
                }

                if (savepoint != null && conn != null) {
                    try {
                        conn.rollback(savepoint);
                    } catch (Exception rbEx) {
                        log.warn("Rollback to savepoint for table '{}' failed: {}", tableName, rbEx.getMessage());
                    }
                }
                Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ex);
                String rootMsg = root != null ? root.getMessage() : ex.getMessage();
                log.error("Batch write failed for table '{}' (Sequence #{}) with {} items. Root database reason: {}. Continuing with remaining tables...",
                        tableName, (i + 1), batchParams.size(), rootMsg);

                if (this.skipListener != null) {
                    this.skipListener.recordTableWriteFailure(tableName, (i + 1), batchParams.size(), rootMsg, ex);
                }

                if (!this.continueOnTableError) {
                    throw ex;
                }
                // Do not rethrow: Continue with other tables in executionOrder
            } finally {
                if (conn != null && this.dataSource != null) {
                    org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, this.dataSource);
                }
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

        // 1. Timestamp / Date conversion (e.g. created_at, "2026-10-01 09:15:00", "21-Feb-2023")
        if (normCol.contains("date") || normCol.contains("time") || normCol.contains("at") || normCol.contains("timestamp")
                || s.matches("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}.*") || s.matches("^\\d{4}-\\d{2}-\\d{2}$")
                || s.matches("^\\d{1,2}-[A-Za-z]{3}-\\d{4}$")) {
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
                if (s.matches("^\\d{1,2}-[A-Za-z]{3}-\\d{4}$")) {
                    java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("d-MMM-yyyy", java.util.Locale.ENGLISH);
                    java.time.LocalDate ld = java.time.LocalDate.parse(s, dtf);
                    return java.sql.Date.valueOf(ld);
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

    private final Map<String, Map<String, String>> tableColumnCaseMapCache = new ConcurrentHashMap<>();

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "day", "month", "year", "date", "time", "order", "group", "user", "value", "values",
            "key", "keys", "table", "tables", "from", "where", "select", "insert", "update",
            "delete", "create", "drop", "alter", "add", "constraint", "primary", "foreign",
            "check", "default", "case", "when", "then", "else", "end", "all", "and", "or",
            "not", "as", "by", "for", "in", "is", "like", "to", "with", "limit", "offset",
            "session", "current", "zone", "action", "role", "level", "position"
    );

    public Map<String, String> getActualColumnNames(String tableName) {
        if (tableName == null) return Collections.emptyMap();
        return tableColumnCaseMapCache.computeIfAbsent(tableName.toLowerCase(), tblKey -> {
            Map<String, String> colMap = new LinkedHashMap<>();
            if (dataSource != null) {
                try (Connection conn = dataSource.getConnection()) {
                    DatabaseMetaData meta = conn.getMetaData();
                    String schema = null;
                    try {
                        schema = conn.getSchema();
                    } catch (Throwable ignored) {}

                    for (String tName : List.of(tableName, tableName.toUpperCase(), tableName.toLowerCase())) {
                        try (ResultSet rs = meta.getColumns(null, schema, tName, null)) {
                            while (rs.next()) {
                                String colName = rs.getString("COLUMN_NAME");
                                if (colName != null) {
                                    colMap.put(colName.toLowerCase(), colName);
                                }
                            }
                        }
                        if (!colMap.isEmpty()) break;
                    }
                } catch (Exception ex) {
                    log.warn("Could not query column metadata for table '{}': {}", tableName, ex.getMessage());
                }
            }
            return colMap;
        });
    }

    public static String quoteIdentifier(String colName, String dbProduct, Map<String, String> actualCols) {
        if (colName == null) return "";
        String actualCol = (actualCols != null) ? actualCols.get(colName.toLowerCase()) : null;
        String effectiveName = (actualCol != null) ? actualCol : colName;

        String db = dbProduct != null ? dbProduct.toLowerCase() : "";
        boolean isMysql = db.contains("mysql") || db.contains("mariadb");

        boolean quote = RESERVED_KEYWORDS.contains(colName.toLowerCase())
                || (actualCol != null && !isMysql)
                || colName.contains(" ") || colName.contains("-");

        if (quote) {
            if (isMysql) {
                return "`" + effectiveName.replace("`", "``") + "`";
            }
            return "\"" + effectiveName.replace("\"", "\"\"") + "\"";
        }
        return effectiveName;
    }

    /**
     * Automatically detects and resolves schema evolution for delta files.
     * If incoming batch records contain columns that do not yet exist in the target table,
     * dynamically issues ALTER TABLE ... ADD COLUMN DDL statements across PostgreSQL, Oracle, and H2.
     */
    public synchronized void ensureColumnsExist(String tableName, Set<String> targetColumns) {
        if (dataSource == null || tableName == null || targetColumns == null || targetColumns.isEmpty()) {
            return;
        }
        Map<String, String> actualCols = getActualColumnNames(tableName);
        if (actualCols.isEmpty()) {
            // Table might not exist yet or no columns returned by metadata
            return;
        }

        Set<String> missingCols = new LinkedHashSet<>();
        for (String col : targetColumns) {
            if (col != null && !col.trim().isEmpty() && !actualCols.containsKey(col.toLowerCase().trim())) {
                missingCols.add(col.trim());
            }
        }

        if (missingCols.isEmpty()) {
            return;
        }

        boolean isOracle = databaseProductName != null && databaseProductName.toLowerCase().contains("oracle");
        boolean isPostgres = databaseProductName != null && databaseProductName.toLowerCase().contains("postgresql");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            for (String col : missingCols) {
                String safeCol = col.replaceAll("[^a-zA-Z0-9_]", "_");
                if (safeCol.isEmpty()) continue;

                String norm = safeCol.toLowerCase();
                String colType = "VARCHAR(255)";
                if (norm.contains("note") || norm.contains("desc") || norm.contains("payload")
                        || norm.contains("extra") || norm.contains("attribute") || norm.contains("json")
                        || norm.contains("roles") || norm.contains("source") || norm.contains("image")) {
                    colType = isOracle ? "CLOB" : "TEXT";
                }

                String quotedCol = RESERVED_KEYWORDS.contains(norm) ? "\"" + safeCol + "\"" : safeCol;
                String alterSql;
                if (isOracle) {
                    String oraType = colType.equals("TEXT") ? "CLOB" : (colType.equals("VARCHAR(255)") ? "VARCHAR2(255)" : colType);
                    alterSql = "ALTER TABLE " + tableName + " ADD (" + quotedCol + " " + oraType + ")";
                } else {
                    // PostgreSQL, H2 and others
                    alterSql = "ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS " + quotedCol + " " + colType;
                }

                try {
                    stmt.execute(alterSql);
                    log.info("Delta Feed Schema Evolution: Dynamically executed ALTER TABLE: '{}' to add missing column '{}'.", alterSql, safeCol);
                } catch (Exception ex) {
                    if (ex.getMessage() != null && (ex.getMessage().contains("already exists") || ex.getMessage().contains("duplicate column") || ex.getMessage().contains("ORA-01430"))) {
                        log.debug("Column '{}' already exists in table '{}'.", safeCol, tableName);
                    } else {
                        log.warn("Notice executing ALTER TABLE statement '{}': {}", alterSql, ex.getMessage());
                    }
                }
            }

            // Invalidate caches so newly added columns are recognized in UPSERT statements
            tableColumnCaseMapCache.remove(tableName.toLowerCase());
            sqlCache.entrySet().removeIf(e -> e.getKey().startsWith(tableName + ":"));
        } catch (Exception ex) {
            log.error("Failed to alter table '{}' for missing delta columns {}: {}", tableName, missingCols, ex.getMessage());
        }
    }

    /**
     * Constructs and caches dynamic SQL UPSERT statement (INSERT ... ON CONFLICT DO UPDATE).
     * If the record already exists, updates non-primary key columns; otherwise inserts.
     */
    public String getOrCreateInsertSql(String tableName, Set<String> columns) {
        String cacheKey = tableName + ":" + String.join(",", columns);
        return sqlCache.computeIfAbsent(cacheKey, k -> {
            Set<String> pkColumns = findPrimaryKeys(tableName, columns);
            Map<String, String> actualCols = getActualColumnNames(tableName);
            String generated = constructUpsertSql(tableName, columns, pkColumns, this.databaseProductName, actualCols);
            log.info("Constructed dynamic batch UPSERT statement for table '{}': {}", tableName, generated);
            return generated;
        });
    }

    /**
     * Retrieves or infers primary key column names for the given target table.
     */
    public Set<String> findPrimaryKeys(String tableName, Set<String> mappedColumns) {
        if (tableName == null) return Collections.emptySet();

        return pkCache.computeIfAbsent(tableName.toLowerCase(), tblKey -> {
            Set<String> pks = new LinkedHashSet<>();
            if (dataSource != null) {
                try (Connection conn = dataSource.getConnection()) {
                    DatabaseMetaData meta = conn.getMetaData();
                    String schema = null;
                    try {
                        schema = conn.getSchema();
                    } catch (Throwable ignored) {}

                    // 1. Try explicit primary keys from database metadata
                    for (String tName : List.of(tableName, tableName.toUpperCase(), tableName.toLowerCase())) {
                        try (ResultSet rs = meta.getPrimaryKeys(null, schema, tName)) {
                            while (rs.next()) {
                                String pkCol = rs.getString("COLUMN_NAME");
                                if (pkCol != null && !pkCol.isEmpty()) {
                                    pks.add(pkCol.toLowerCase());
                                }
                            }
                        }
                        if (!pks.isEmpty()) break;
                    }

                    // 2. If no PK found, try unique indexes
                    if (pks.isEmpty()) {
                        for (String tName : List.of(tableName, tableName.toUpperCase(), tableName.toLowerCase())) {
                            try (ResultSet rs = meta.getIndexInfo(null, schema, tName, true, false)) {
                                while (rs.next()) {
                                    String idxCol = rs.getString("COLUMN_NAME");
                                    if (idxCol != null && !idxCol.isEmpty()) {
                                        pks.add(idxCol.toLowerCase());
                                        break;
                                    }
                                }
                            }
                            if (!pks.isEmpty()) break;
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Could not query primary keys for table '{}': {}", tableName, ex.getMessage());
                }
            }

            // 3. Fallback: infer from mapped column naming conventions if pks is empty OR if pks has no match in mappedColumns
            boolean hasOverlap = false;
            if (mappedColumns != null && !pks.isEmpty()) {
                for (String pk : pks) {
                    if (mappedColumns.stream().anyMatch(c -> c.equalsIgnoreCase(pk))) {
                        hasOverlap = true;
                        break;
                    }
                }
            }
            if (!hasOverlap && mappedColumns != null) {
                pks.clear();
                pks.addAll(inferPrimaryKeys(tableName, mappedColumns));
            }

            return pks;
        });
    }

    /**
     * Infers likely primary key / unique constraint columns from column names.
     */
    public static Set<String> inferPrimaryKeys(String tableName, Set<String> columns) {
        Set<String> inferred = new LinkedHashSet<>();
        if (columns == null || columns.isEmpty()) return inferred;

        String normTable = tableName != null ? tableName.toLowerCase() : "";

        // Check common identifier column names FIRST
        for (String col : columns) {
            String norm = col.toLowerCase();
            if (norm.equals(normTable + "_id") || norm.equals(normTable + "id")
                    || norm.equals("id") || norm.equals("sku") || norm.equals("location_code")
                    || norm.equals("order_id") || norm.equals("code")
                    || (norm.endsWith("_id") && !norm.equalsIgnoreCase("source_bnpp_id") && !norm.equalsIgnoreCase("staging_bnpp_id"))
                    || norm.endsWith("_pk") || norm.endsWith("_code")) {
                inferred.add(norm);
                return inferred;
            }
        }

        // If no business key column is present, fallback to bnpp_id
        for (String col : columns) {
            if ("bnpp_id".equalsIgnoreCase(col)) {
                inferred.add(col);
                return inferred;
            }
        }

        // If none found by convention, use first column as primary identifier
        if (inferred.isEmpty() && !columns.isEmpty()) {
            inferred.add(columns.iterator().next().toLowerCase());
        }

        return inferred;
    }

    /**
     * Constructs cross-database UPSERT SQL statement (INSERT ... ON CONFLICT DO UPDATE).
     */
    public static String constructUpsertSql(String tableName, Set<String> columns, Set<String> pkColumns, String dbProduct) {
        return constructUpsertSql(tableName, columns, pkColumns, dbProduct, Collections.emptyMap());
    }

    /**
     * Constructs cross-database UPSERT SQL statement (INSERT ... ON CONFLICT DO UPDATE).
     */
    public static String constructUpsertSql(String tableName, Set<String> columns, Set<String> pkColumns, String dbProduct, Map<String, String> actualCols) {
        if (tableName == null || columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Table name and columns cannot be empty");
        }

        Set<String> matchedPks = new LinkedHashSet<>();
        Set<String> nonPkCols = new LinkedHashSet<>();

        for (String col : columns) {
            if (pkColumns != null && (pkColumns.contains(col.toLowerCase()) || pkColumns.contains(col))) {
                matchedPks.add(col);
            } else {
                nonPkCols.add(col);
            }
        }

        // Base INSERT statement
        StringBuilder insertSql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        StringBuilder placeholders = new StringBuilder(" VALUES (");

        int idx = 0;
        for (String col : columns) {
            if (idx > 0) {
                insertSql.append(", ");
                placeholders.append(", ");
            }
            insertSql.append(quoteIdentifier(col, dbProduct, actualCols));
            placeholders.append(":").append(col);
            idx++;
        }
        insertSql.append(")").append(placeholders).append(")");

        // If no primary key could be matched from metadata, fallback to bnpp_id if present
        if (matchedPks.isEmpty() && columns != null) {
            for (String col : columns) {
                if ("bnpp_id".equalsIgnoreCase(col)) {
                    matchedPks.add(col);
                    nonPkCols.remove(col);
                    break;
                }
            }
        }

        // If still no primary key could be identified, fallback to standard INSERT
        if (matchedPks.isEmpty()) {
            return insertSql.toString();
        }

        String db = dbProduct != null ? dbProduct.toLowerCase() : "";

        // 1. MySQL / MariaDB (ON DUPLICATE KEY UPDATE)
        if (db.contains("mysql") || db.contains("mariadb")) {
            if (nonPkCols.isEmpty()) {
                return insertSql.toString().replaceFirst("(?i)INSERT INTO", "INSERT IGNORE INTO");
            }
            StringBuilder onDup = new StringBuilder(" ON DUPLICATE KEY UPDATE ");
            int uIdx = 0;
            for (String col : nonPkCols) {
                if (col.equalsIgnoreCase("created_at")) continue;
                if (uIdx > 0) onDup.append(", ");
                String quoted = quoteIdentifier(col, dbProduct, actualCols);
                onDup.append(quoted).append(" = VALUES(").append(quoted).append(")");
                uIdx++;
            }
            return insertSql.append(onDup).toString();
        }

        // 2. H2 Database (MERGE INTO tableName (...) KEY (...) VALUES (...))
        if (db.contains("h2")) {
            StringBuilder mergeSql = new StringBuilder("MERGE INTO ").append(tableName).append(" (");
            StringBuilder mPlaceholders = new StringBuilder(" VALUES (");
            int mIdx = 0;
            for (String col : columns) {
                if (mIdx > 0) {
                    mergeSql.append(", ");
                    mPlaceholders.append(", ");
                }
                mergeSql.append(quoteIdentifier(col, dbProduct, actualCols));
                mPlaceholders.append(":").append(col);
                mIdx++;
            }
            mergeSql.append(") KEY (");
            int kIdx = 0;
            for (String pk : matchedPks) {
                if (kIdx > 0) mergeSql.append(", ");
                mergeSql.append(quoteIdentifier(pk, dbProduct, actualCols));
                kIdx++;
            }
            mergeSql.append(")").append(mPlaceholders).append(")");
            return mergeSql.toString();
        }

        // 3. PostgreSQL / SQLite / ANSI: ON CONFLICT (pk...) DO UPDATE SET col = EXCLUDED.col
            StringBuilder onConflict = new StringBuilder(" ON CONFLICT (");
            int pkIdx = 0;
            for (String pk : matchedPks) {
                if (pkIdx > 0) onConflict.append(", ");
                onConflict.append(quoteIdentifier(pk, dbProduct, actualCols));
                pkIdx++;
            }
            onConflict.append(")");

            List<String> updateCols = nonPkCols.stream().filter(c -> !c.equalsIgnoreCase("created_at")).toList();
            if (updateCols.isEmpty()) {
                onConflict.append(" DO NOTHING");
            } else {
                onConflict.append(" DO UPDATE SET ");
                int uIdx = 0;
                for (String col : updateCols) {
                    if (uIdx > 0) onConflict.append(", ");
                    String quoted = quoteIdentifier(col, dbProduct, actualCols);
                    onConflict.append(quoted).append(" = EXCLUDED.").append(quoted);
                    uIdx++;
                }
            }

        return insertSql.append(onConflict).toString();
    }

    public NamedParameterJdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }
}
