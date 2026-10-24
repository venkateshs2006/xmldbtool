package com.enterprise.xmldbtool;

import com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor;
import com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller;
import com.enterprise.xmldbtool.model.DataPreviewRequest;
import com.enterprise.xmldbtool.model.DataPreviewResponse;
import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.DiscoveredElement;
import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.service.XmlPreviewService;
import com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DynamicXmlIngestionTest {

    @Autowired
    private DynamicXmlUnmarshaller unmarshaller;

    @Autowired
    private XmlSchemaDiscoveryService discoveryService;

    @Autowired
    private XmlPreviewService previewService;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Test
    @DisplayName("Verify StAX Dynamic Unmarshaller extracts tags and flattens @attributes")
    void testDynamicXmlUnmarshalling() throws Exception {
        String fragment = """
            <record id="9981" status="ACTIVE" region="US-EAST">
                <sku>SKU-PROD-99</sku>
                <productName>HyperScale Server Blade</productName>
                <unitPrice>5299.00</unitPrice>
                <quantity>10</quantity>
            </record>
            """;

        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLEventReader reader = factory.createXMLEventReader(new StringReader(fragment));

        Map<String, Object> recordMap = unmarshaller.parseFragmentFromEventReader(reader);

        assertNotNull(recordMap);
        assertEquals("9981", recordMap.get("@id"), "Attribute id must be prefixed with @");
        assertEquals("ACTIVE", recordMap.get("@status"), "Attribute status must be prefixed with @");
        assertEquals("US-EAST", recordMap.get("@region"), "Attribute region must be prefixed with @");
        assertEquals("SKU-PROD-99", recordMap.get("sku"));
        assertEquals("HyperScale Server Blade", recordMap.get("productName"));
        assertEquals("5299.00", recordMap.get("unitPrice"));
        assertEquals("10", recordMap.get("quantity"));
    }

    @Test
    @DisplayName("Verify Dynamic ItemProcessor transforms Map into DatabaseRowPayload")
    void testDynamicItemProcessor() throws Exception {
        Map<String, Object> xmlRecord = Map.of(
                "@id", "1001",
                "@status", "ACTIVE",
                "sku", "SKU-TEST-1",
                "productName", "SSD Enterprise"
        );

        List<FieldMapping> mappings = List.of(
                new FieldMapping("@id", "id", "NONE"),
                new FieldMapping("@status", "status", "UPPER"),
                new FieldMapping("sku", "product_sku", "NONE"),
                new FieldMapping("productName", "name", "TRIM")
        );

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor("target_products", mappings);
        DatabaseRowPayload payload = processor.process(xmlRecord);

        assertNotNull(payload);
        assertEquals("target_products", payload.getTargetTable());
        assertEquals("1001", payload.getColumnValues().get("id"));
        assertEquals("ACTIVE", payload.getColumnValues().get("status"));
        assertEquals("SKU-TEST-1", payload.getColumnValues().get("product_sku"));
        assertEquals("SSD Enterprise", payload.getColumnValues().get("name"));
    }

    @Test
    @DisplayName("Verify location_code automatically resolves from warehouseLocation XML tag and @region")
    void testLocationCodeResolutionFromWarehouseLocation() throws Exception {
        Map<String, Object> xmlRecord = Map.of(
                "@id", "1006",
                "@region", "EU-WEST",
                "warehouseLocation", "WH-LON-01"
        );

        List<FieldMapping> mappings = List.of(
                new FieldMapping("location_code", "warehouses", "location_code", "NONE"),
                new FieldMapping("region", "warehouses", "region", "NONE")
        );

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor("warehouses", mappings);
        DatabaseRowPayload payload = processor.process(xmlRecord);

        assertNotNull(payload);
        var warehouseData = payload.getMultiTablePayloads().get("warehouses");
        assertNotNull(warehouseData);
        assertEquals("WH-LON-01", warehouseData.get("location_code"), "location_code should resolve from warehouseLocation");
        assertEquals("EU-WEST", warehouseData.get("region"), "region should resolve from @region");
    }

    @Test
    @DisplayName("Verify sku is strictly distinguished from productName to preserve FK constraints")
    void testSkuResolutionDistinction() throws Exception {
        Map<String, Object> xmlRecord = Map.of(
                "sku", "SKU-44391-F",
                "productName", "2U Rackmount Chassis Hot-Swap"
        );

        List<FieldMapping> mappings = List.of(
                new FieldMapping("sku", "inventory_records", "sku", "NONE")
        );

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor("inventory_records", mappings);
        DatabaseRowPayload payload = processor.process(xmlRecord);

        assertNotNull(payload);
        var invData = payload.getMultiTablePayloads().get("inventory_records");
        assertNotNull(invData);
        assertEquals("SKU-44391-F", invData.get("sku"), "Column sku must resolve to actual SKU code, not product name");
    }

    @Test
    @DisplayName("Verify autoConvertType converts strings to SQL Timestamp, Integer, and BigDecimal")
    void testAutoConvertType() {
        Object ts = com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter.autoConvertType("2026-10-01 09:15:00", "created_at");
        assertTrue(ts instanceof java.sql.Timestamp, "Timestamp string should convert to java.sql.Timestamp");

        Object intVal = com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter.autoConvertType("12", "quantity");
        assertEquals(12, intVal);

        Object priceVal = com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter.autoConvertType("4899.50", "unit_price");
        assertTrue(priceVal instanceof java.math.BigDecimal);
    }

    @Test
    @DisplayName("Verify Schema Discovery reads sample XML and discovers tags and attributes")
    void testSampleXmlDiscovery() throws Exception {
        List<DiscoveredElement> elements = discoveryService.discoverXmlElements("sample-data/large-sample.xml", 1000);

        assertNotNull(elements);
        assertFalse(elements.isEmpty(), "Discovered elements should not be empty");

        boolean hasIdAttr = elements.stream().anyMatch(e -> (e.getName().equals("record/@id") || e.getName().equals("@id")) && e.isAttribute());
        boolean hasCatalogExportId = elements.stream().anyMatch(e -> e.getName().equals("catalog/@exportId") && e.isAttribute());
        boolean hasSkuTag = elements.stream().anyMatch(e -> e.getName().equals("sku") && !e.isAttribute());

        assertTrue(hasIdAttr, "Should have discovered record/@id attribute");
        assertTrue(hasCatalogExportId, "Should have discovered root catalog/@exportId attribute");
        assertTrue(hasSkuTag, "Should have discovered sku tag");
    }

    @Test
    @DisplayName("Verify XML Preview generates 5 dry-run rows and prepared SQL statement")
    void testXmlPreviewGeneration() {
        DataPreviewRequest request = new DataPreviewRequest();
        request.setSourceXmlPath("sample-data/large-sample.xml");
        request.setRecordRootElement("record");
        request.setTargetTable("products");
        request.setPreviewLimit(5);
        request.setMappings(List.of(
                new FieldMapping("@id", "id", "NONE"),
                new FieldMapping("sku", "sku", "NONE"),
                new FieldMapping("productName", "product_name", "NONE")
        ));

        DataPreviewResponse response = previewService.generatePreview(request);

        assertTrue(response.isSuccess());
        assertFalse(response.getPreviewRows().isEmpty());
        assertTrue(response.getPreviewRows().size() <= 5);
        assertNotNull(response.getGeneratedSqlTemplate());
        assertTrue(response.getGeneratedSqlTemplate().contains("INSERT INTO products"));
    }

    @Test
    @DisplayName("Verify One-to-Many Multi-Table XML mapping and preview generation")
    void testMultiTableOneToManyMappingAndPreview() {
        DataPreviewRequest request = new DataPreviewRequest();
        request.setSourceXmlPath("sample-data/large-sample.xml");
        request.setRecordRootElement("record");
        request.setTargetTable("orders");
        request.setPreviewLimit(5);
        request.setMappings(List.of(
                // Parent table mappings
                new FieldMapping("@id", "orders", "order_id", "NONE"),
                new FieldMapping("@status", "orders", "status", "UPPER"),
                // Child table mappings (1:N foreign key reuse of @id)
                new FieldMapping("@id", "order_items", "order_fk", "NONE"),
                new FieldMapping("sku", "order_items", "product_sku", "NONE"),
                new FieldMapping("unitPrice", "order_items", "unit_price", "NONE")
        ));

        DataPreviewResponse response = previewService.generatePreview(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getTablePreviews());
        assertEquals(2, response.getTablePreviews().size(), "Should have previews for both 'orders' and 'order_items'");

        // Verify parent table preview
        assertTrue(response.getTablePreviews().containsKey("orders"));
        var parentPreview = response.getTablePreviews().get("orders");
        assertTrue(parentPreview.getColumns().contains("order_id"));
        assertTrue(parentPreview.getColumns().contains("status"));
        assertTrue(parentPreview.getSqlTemplate().contains("INSERT INTO orders"));

        // Verify child table preview
        assertTrue(response.getTablePreviews().containsKey("order_items"));
        var childPreview = response.getTablePreviews().get("order_items");
        assertTrue(childPreview.getColumns().contains("order_fk"));
        assertTrue(childPreview.getColumns().contains("product_sku"));
        assertTrue(childPreview.getSqlTemplate().contains("INSERT INTO order_items"));
    }

    @Test
    @DisplayName("Test StaxEventItemReader reads large-sample.xml with qualified tag/@attr and @attr")
    void testStaxEventItemReader() throws Exception {
        org.springframework.batch.item.xml.StaxEventItemReader<Map<String, Object>> reader = new org.springframework.batch.item.xml.StaxEventItemReader<>();
        reader.setName("testReader");
        reader.setResource(new org.springframework.core.io.FileSystemResource("sample-data/large-sample.xml"));
        reader.setFragmentRootElementName("record");
        reader.setUnmarshaller(unmarshaller);
        reader.open(new org.springframework.batch.item.ExecutionContext());
        Map<String, Object> item = reader.read();
        assertNotNull(item);
        assertEquals("1001", item.get("record/@id"));
        assertEquals("1001", item.get("@id"));
        assertEquals("ACTIVE", item.get("record/@status"));
        assertEquals("US-EAST", item.get("record/@region"));
        reader.close();
    }

    @Test
    @DisplayName("Verify root tag (catalog) and 2nd root tag (record) attributes are both readable in Data Preview")
    void testRootAndSecondRootAttributePreview() {
        DataPreviewRequest request = new DataPreviewRequest();
        request.setSourceXmlPath("sample-data/large-sample.xml");
        request.setRecordRootElement("record");
        request.setTargetTable("products");
        request.setPreviewLimit(2);
        request.setMappings(List.of(
                new FieldMapping("catalog/@exportId", "products", "export_id", "NONE"),
                new FieldMapping("record/@id", "products", "id", "NONE"),
                new FieldMapping("record/@region", "products", "region", "NONE"),
                new FieldMapping("sku", "products", "sku", "NONE")
        ));

        DataPreviewResponse response = previewService.generatePreview(request);
        assertTrue(response.isSuccess());
        var rows = response.getPreviewRows();
        assertFalse(rows.isEmpty());
        assertEquals("EXP-2026-X889", rows.get(0).get("export_id"), "Root tag attribute catalog/@exportId must be readable");
        assertEquals("1001", rows.get(0).get("id"), "2nd root tag attribute record/@id must be readable");
        assertEquals("US-EAST", rows.get(0).get("region"), "2nd root tag attribute record/@region must be readable");
    }

    @Test
    @DisplayName("Verify constructUpsertSql builds PostgreSQL ON CONFLICT DO UPDATE statement")
    void testUpsertSqlConstructionPostgres() {
        Set<String> columns = Set.of("location_code", "region");
        Set<String> pks = Set.of("location_code");
        String sql = com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter.constructUpsertSql(
                "warehouses", columns, pks, "PostgreSQL"
        );

        assertTrue(sql.contains("INSERT INTO warehouses"));
        assertTrue(sql.contains("ON CONFLICT (location_code) DO UPDATE SET region = EXCLUDED.region"));
    }

    @Test
    @DisplayName("Verify constructUpsertSql builds MySQL ON DUPLICATE KEY UPDATE statement")
    void testUpsertSqlConstructionMySQL() {
        Set<String> columns = Set.of("id", "status", "quantity");
        Set<String> pks = Set.of("id");
        String sql = com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter.constructUpsertSql(
                "inventory_records", columns, pks, "MySQL"
        );

        assertTrue(sql.contains("INSERT INTO inventory_records"));
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(sql.contains("status = VALUES(status)"));
        assertTrue(sql.contains("quantity = VALUES(quantity)"));
    }

    @Test
    @DisplayName("Verify DynamicJdbcBatchWriter updates existing record in place on collision")
    void testLiveUpsertExecution() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_upsert_products (id INT PRIMARY KEY, name VARCHAR(100), price DECIMAL(10,2))");
        jdbc.execute("DELETE FROM test_upsert_products");

        // Initial insert
        jdbc.update("INSERT INTO test_upsert_products (id, name, price) VALUES (101, 'Original Server', 1000.00)");

        // Now run DynamicJdbcBatchWriter with updated name and price for existing id 101
        com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter writer = new com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter(dataSource);
        DatabaseRowPayload payload = new DatabaseRowPayload(
                Map.of("test_upsert_products", Map.of("id", 101, "name", "Updated Server Gen2", "price", "1999.99")),
                Map.of()
        );

        writer.write(new org.springframework.batch.item.Chunk<>(List.of(payload)));

        // Verify the existing row was updated
        Map<String, Object> updatedRow = jdbc.queryForMap("SELECT id, name, price FROM test_upsert_products WHERE id = 101");
        assertEquals("Updated Server Gen2", updatedRow.get("name"));
        assertEquals(new java.math.BigDecimal("1999.99"), new java.math.BigDecimal(String.valueOf(updatedRow.get("price"))));
    }

    @Test
    @DisplayName("Verify determineTableOrder strictly adheres to tag sequence from mappings")
    void testTableInsertionOrderSequenceFromTagMappings() {
        List<FieldMapping> mappings = List.of(
                new FieldMapping("warehouseLocation", "warehouses", "location_code", "NONE"),
                new FieldMapping("sku", "products", "sku", "NONE"),
                new FieldMapping("record/@id", "inventory_records", "id", "NONE"),
                new FieldMapping("quantity", "inventory_records", "quantity", "NONE")
        );

        List<String> order = com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter.determineTableOrder(
                null, mappings, "default_table"
        );

        assertNotNull(order);
        assertEquals(3, order.size());
        assertEquals("warehouses", order.get(0), "warehouses must be 1st because warehouseLocation appears 1st");
        assertEquals("products", order.get(1), "products must be 2nd because sku appears 2nd");
        assertEquals("inventory_records", order.get(2), "inventory_records must be 3rd because record/@id appears 3rd");
    }

    @Test
    @DisplayName("Verify batch writer executes parent table before child table based on tag sequence to satisfy FKs")
    void testStrictTagOrderSequenceForeignKeysLiveBatch() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_fk_parents (id INT PRIMARY KEY, name VARCHAR(100))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_fk_children (id INT PRIMARY KEY, parent_id INT, FOREIGN KEY (parent_id) REFERENCES test_fk_parents(id))");
        jdbc.execute("DELETE FROM test_fk_children");
        jdbc.execute("DELETE FROM test_fk_parents");

        // Order sequence strictly specifies parent before child
        List<String> tableOrder = List.of("test_fk_parents", "test_fk_children");
        com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter writer =
                new com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter(dataSource, tableOrder);

        // Construct payload with child table appearing before parent table in payload map
        java.util.Map<String, java.util.Map<String, Object>> multiPayloads = new java.util.LinkedHashMap<>();
        multiPayloads.put("test_fk_children", Map.of("id", 2001, "parent_id", 1001));
        multiPayloads.put("test_fk_parents", Map.of("id", 1001, "name", "Main Branch"));

        DatabaseRowPayload payload = new DatabaseRowPayload(multiPayloads, Map.of());

        // Batch execution must insert test_fk_parents FIRST, preventing Foreign Key constraint violation
        assertDoesNotThrow(() -> {
            writer.write(new org.springframework.batch.item.Chunk<>(List.of(payload)));
        }, "Writer must respect tableOrder sequence, executing parent table before child table");

        Integer parentCount = jdbc.queryForObject("SELECT COUNT(*) FROM test_fk_parents WHERE id = 1001", Integer.class);
        Integer childCount = jdbc.queryForObject("SELECT COUNT(*) FROM test_fk_children WHERE id = 2001", Integer.class);
        assertEquals(1, parentCount);
        assertEquals(1, childCount);
    }

    @Test
    @DisplayName("Verify that when one table fails during batch write, other tables continue execution and are committed")
    void testTableFailureDoesNotHaltOtherTables() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_resilient_tbl1 (id INT PRIMARY KEY, name VARCHAR(100))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_resilient_tbl2 (id INT PRIMARY KEY, title VARCHAR(100))");
        jdbc.execute("DELETE FROM test_resilient_tbl1");
        jdbc.execute("DELETE FROM test_resilient_tbl2");

        // Execution order: Table 1 (valid), Table 2 (invalid / intentionally fails), Table 3 (valid)
        List<String> tableOrder = List.of("test_resilient_tbl1", "non_existent_fail_table", "test_resilient_tbl2");
        com.enterprise.xmldbtool.batch.DatabaseErrorSkipListener skipListener =
                new com.enterprise.xmldbtool.batch.DatabaseErrorSkipListener(dataSource, 999L, "testStep");

        com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter writer =
                new com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter(dataSource, tableOrder, skipListener);

        java.util.Map<String, java.util.Map<String, Object>> multiPayloads = new java.util.LinkedHashMap<>();
        multiPayloads.put("test_resilient_tbl1", Map.of("id", 101, "name", "Valid Record 1"));
        multiPayloads.put("non_existent_fail_table", Map.of("id", 202, "bad_col", "Will Fail"));
        multiPayloads.put("test_resilient_tbl2", Map.of("id", 303, "title", "Valid Record 2"));

        DatabaseRowPayload payload = new DatabaseRowPayload(multiPayloads, Map.of());

        // Must not throw exception; must continue and write table 1 and table 3
        assertDoesNotThrow(() -> {
            writer.write(new org.springframework.batch.item.Chunk<>(List.of(payload)));
        }, "Writer must NOT halt on table failure; it must continue writing remaining tables");

        Integer count1 = jdbc.queryForObject("SELECT COUNT(*) FROM test_resilient_tbl1 WHERE id = 101", Integer.class);
        Integer count2 = jdbc.queryForObject("SELECT COUNT(*) FROM test_resilient_tbl2 WHERE id = 303", Integer.class);

        assertEquals(1, count1, "Table 1 must be committed successfully");
        assertEquals(1, count2, "Table 3 must be committed successfully despite Table 2 failure");
        assertTrue(skipListener.getTableErrorCount() > 0, "Skip listener must capture the failed table error");
        assertFalse(skipListener.getRecentErrors().isEmpty(), "Recent errors must contain the failed table event");
        assertTrue(skipListener.getRecentErrors().get(0).getPhase().contains("WRITE_TABLE_non_existent_fail_table"));
    }

    @Test
    @DisplayName("Verify preview shows tag-based dummy data without reading file when XML file size exceeds 100MB threshold")
    void testSkipPreviewForLargeXmlFile() {
        DataPreviewRequest request = new DataPreviewRequest();
        request.setSourceXmlPath("sample-data/large-sample.xml");
        request.setRecordRootElement("record");
        request.setTargetTable("products");
        request.setPreviewLimit(5);
        request.setMappings(List.of(
                new FieldMapping("record/@id", "products", "id", "NONE"),
                new FieldMapping("sku", "products", "sku", "NONE"),
                new FieldMapping("unitPrice", "products", "unit_price", "NONE")
        ));

        try {
            // Temporarily set limit to 100 bytes so large-sample.xml (2.8 KB) exceeds threshold
            previewService.setMaxPreviewSizeLimit(100L);

            DataPreviewResponse response = previewService.generatePreview(request);

            assertTrue(response.isSuccess());
            assertTrue(response.isPreviewSkipped(), "Preview should be marked as skipped when exceeding limit");
            assertTrue(response.isDummyData(), "Preview should be marked as dummy data when exceeding limit");
            assertNotNull(response.getPreviewRows(), "Preview rows should not be null");
            assertEquals(5, response.getPreviewRows().size(), "Should have generated 5 dummy records based on tags");

            // Verify simulated values match tag-based conventions
            Map<String, Object> firstRow = response.getPreviewRows().get(0);
            assertEquals("1001", firstRow.get("id"), "Dummy ID should be generated for record/@id");
            assertEquals("SKU-99214-A", firstRow.get("sku"), "Dummy SKU should be generated for sku");
            assertEquals("4899.50", firstRow.get("unit_price"), "Dummy price should be generated for unitPrice");

            assertNotNull(response.getSkipReason());
            assertTrue(response.getSkipReason().contains("Displaying tag-generated dummy preview data"));
            assertNotNull(response.getGeneratedSqlTemplate());
            assertTrue(response.getGeneratedSqlTemplate().contains("INSERT INTO products"));
        } finally {
            // Restore default 100MB limit
            previewService.setMaxPreviewSizeLimit(com.enterprise.xmldbtool.service.XmlPreviewService.DEFAULT_MAX_PREVIEW_FILE_SIZE_BYTES);
        }

        // Test normal preview under default 100MB limit
        DataPreviewResponse normalResponse = previewService.generatePreview(request);
        assertTrue(normalResponse.isSuccess());
        assertFalse(normalResponse.isPreviewSkipped(), "Preview should NOT be skipped when file size is under 100MB");
        assertFalse(normalResponse.isDummyData(), "Normal preview should NOT be dummy data");
        assertFalse(normalResponse.getPreviewRows().isEmpty(), "Preview rows should be populated from actual XML");
    }
}

