package com.enterprise.xmldbtool;

import com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter;
import com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor;
import com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller;
import com.enterprise.xmldbtool.model.DataPreviewRequest;
import com.enterprise.xmldbtool.model.DataPreviewResponse;
import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.DiscoveredElement;
import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.model.DbConnectionConfig;
import com.enterprise.xmldbtool.model.DbTestResponse;
import com.enterprise.xmldbtool.model.JobLaunchRequest;
import com.enterprise.xmldbtool.model.JobLaunchResponse;
import com.enterprise.xmldbtool.service.DynamicBatchJobService;
import com.enterprise.xmldbtool.service.DynamicDataSourceService;
import com.enterprise.xmldbtool.service.DynamicSchemaResolutionService;
import com.enterprise.xmldbtool.service.SchemaProvisioningService;
import com.enterprise.xmldbtool.service.XmlPreviewService;
import com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private DynamicBatchJobService batchJobService;

    @Autowired
    private com.enterprise.xmldbtool.service.StagingXmlStreamingService stagingStreamingService;

    @Autowired
    private com.enterprise.xmldbtool.service.DynamicSchemaResolutionService schemaResolutionService;

    @Autowired
    private com.enterprise.xmldbtool.service.SchemaRegistryService schemaRegistryService;

    @Autowired
    private DynamicDataSourceService dataSourceService;

    @Autowired
    private SchemaProvisioningService provisioningService;

    @Autowired
    private com.enterprise.xmldbtool.controller.SchemaDiscoveryController schemaDiscoveryController;

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

    @Test
    @DisplayName("Verify parent and child attributes do not clobber each other")
    void testParentChildAttributeNoClobber() throws Exception {
        String xml = """
            <person id="1" status="active">
                <name>James Smith</name>
                <address id="101" type="home">
                    <street>123 Main St</street>
                </address>
            </person>
            """;
        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLEventReader reader = factory.createXMLEventReader(new StringReader(xml));

        Map<String, Object> map = unmarshaller.parseFragmentFromEventReader(reader);
        assertNotNull(map);
        assertEquals("1", map.get("person/@id"));
        assertEquals("101", map.get("address/@id"));
        assertEquals("1", map.get("@id"), "Root attribute @id must take precedence over child attribute");
        assertEquals("1", map.get("id"), "Root attribute id must take precedence over child attribute");
        assertEquals("home", map.get("address/@type"));
        assertEquals("home", map.get("@type"));
    }

    @Test
    @DisplayName("Verify qualified XML attributes do not leak to unrelated tags")
    void testQualifiedAttributeResolutionNoLeak() throws Exception {
        Map<String, Object> xmlRecord = new LinkedHashMap<>();
        xmlRecord.put("entity/@id", "20");
        xmlRecord.put("entity/@status", "active");
        xmlRecord.put("descriptionlist1_id", "20");

        List<FieldMapping> mappings = List.of(
                new FieldMapping("descriptionlist2/@id", "descriptionlists2", "id", "NONE"),
                new FieldMapping("descriptionlist1_id", "descriptionlists2", "descriptionlist1_id", "NONE")
        );

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor("descriptionlists2", mappings);
        DatabaseRowPayload payload = processor.process(xmlRecord);

        // descriptionlist2/@id should NOT resolve from entity/@id; descriptionlists2 payload should be skipped due to null id
        assertNull(payload, "Table payload should be omitted when its mapped id attribute is null in current fragment");
    }

    @Test
    @DisplayName("Verify numbered plural foreign key detection (descriptionlists1 for descriptionlist1_id)")
    void testNumberedPluralForeignKeys() {
        assertTrue(DynamicJdbcBatchWriter.isParentTableForFk("descriptionlists1", "descriptionlist1_id"));
        assertTrue(DynamicJdbcBatchWriter.isParentTableForFk("descriptionlists2", "descriptionlist2_id"));
        assertTrue(DynamicJdbcBatchWriter.isParentTableForFk("countries", "country_id"));
        assertTrue(DynamicJdbcBatchWriter.isParentTableForFk("countrylists", "countrylist_id"));

        List<String> tables = List.of("entities", "descriptionlists2", "descriptionlists1");
        List<FieldMapping> mappings = List.of(
                new FieldMapping("descriptionlist1_id", "descriptionlists2", "descriptionlist1_id", "NONE"),
                new FieldMapping("descriptionlist2_id", "entities", "descriptionlist2_id", "NONE")
        );

        List<String> sorted = DynamicJdbcBatchWriter.sortTablesByForeignKeys(tables, mappings);
        assertEquals(List.of("descriptionlists1", "descriptionlists2", "entities"), sorted,
                "Foreign key parent tables must precede child dependent tables");
    }

    @Test
    @DisplayName("Verify multi-record repeating collections unmarshal and process all records per table")
    void testMultiRecordCollectionUnmarshallingAndProcessing() throws Exception {
        StringBuilder xmlBuilder = new StringBuilder("<canopyapp><countrylists>\n");
        for (int i = 1; i <= 20; i++) {
            xmlBuilder.append(String.format("<countrylist id=\"%d\" action=\"active\">list%d</countrylist>\n", i, i));
        }
        xmlBuilder.append("</countrylists><countries>\n");
        for (int i = 1; i <= 20; i++) {
            xmlBuilder.append(String.format("""
                <country id="%d" action="create">
                    <countrylist_id>%d</countrylist_id>
                    <name>Country %d</name>
                </country>
            """, i, i, i));
        }
        xmlBuilder.append("</countries></canopyapp>");

        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLEventReader reader = factory.createXMLEventReader(new StringReader(xmlBuilder.toString()));

        Map<String, Object> recordMap = unmarshaller.parseFragmentFromEventReader(reader);

        assertNotNull(recordMap);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> countrylists = (List<Map<String, Object>>) recordMap.get("_entities_countrylist");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> countries = (List<Map<String, Object>>) recordMap.get("_entities_country");

        assertNotNull(countrylists, "_entities_countrylist must be populated");
        assertNotNull(countries, "_entities_country must be populated");
        assertEquals(20, countrylists.size(), "All 20 countrylists must be preserved");
        assertEquals(20, countries.size(), "All 20 countries must be preserved");

        // Verify id=17 is present
        assertEquals("17", countrylists.get(16).get("id"));
        assertEquals("17", countries.get(16).get("id"));
        assertEquals("17", countries.get(16).get("countrylist_id"));

        // Process with DynamicXmlItemProcessor
        List<FieldMapping> mappings = List.of(
                new FieldMapping("countrylist/@id", "countrylists", "id", "NONE"),
                new FieldMapping("countrylist/@action", "countrylists", "action", "NONE"),
                new FieldMapping("country/@id", "countries", "id", "NONE"),
                new FieldMapping("countrylist_id", "countries", "countrylist_id", "NONE"),
                new FieldMapping("name", "countries", "name", "NONE")
        );

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor("countrylists", mappings);
        DatabaseRowPayload payload = processor.process(recordMap);

        assertNotNull(payload);
        Map<String, List<Map<String, Object>>> multiRecords = payload.getMultiTableRecords();
        assertNotNull(multiRecords);
        assertEquals(20, multiRecords.get("countrylists").size(), "Target table countrylists must contain 20 rows");
        assertEquals(20, multiRecords.get("countries").size(), "Target table countries must contain 20 rows");
    }

    @Test
    @DisplayName("Verify DynamicJdbcBatchWriter batch-inserts all multi-table records into database")
    void testMultiTableBatchWriterExecutionWithFks() throws Exception {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS multi_parents (id INT PRIMARY KEY, name VARCHAR(50))");
            stmt.execute("CREATE TABLE IF NOT EXISTS multi_children (id INT PRIMARY KEY, parent_id INT, FOREIGN KEY (parent_id) REFERENCES multi_parents(id))");
            stmt.execute("DELETE FROM multi_children");
            stmt.execute("DELETE FROM multi_parents");
        }

        DatabaseRowPayload payload = new DatabaseRowPayload();
        List<Map<String, Object>> parentRows = new java.util.ArrayList<>();
        List<Map<String, Object>> childRows = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            parentRows.add(Map.of("id", i, "name", "Parent " + i));
            childRows.add(Map.of("id", i, "parent_id", i));
        }
        payload.setTableRecords("multi_parents", parentRows);
        payload.setTableRecords("multi_children", childRows);

        List<String> tableOrder = List.of("multi_parents", "multi_children");
        DynamicJdbcBatchWriter writer = new DynamicJdbcBatchWriter(dataSource, tableOrder);

        org.springframework.batch.item.Chunk<DatabaseRowPayload> chunk = new org.springframework.batch.item.Chunk<>(List.of(payload));
        writer.write(chunk);

        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM multi_parents")) {
                assertTrue(rs.next());
                assertEquals(20, rs.getInt(1), "multi_parents table must have all 20 records");
            }
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM multi_children")) {
                assertTrue(rs.next());
                assertEquals(20, rs.getInt(1), "multi_children table must have all 20 records");
            }
        }
    }

    @Test
    @DisplayName("Verify PFA Person fragment unmarshals and processes into multi-table payload")
    void testPfaPersonUnmarshallingAndProcessing() throws Exception {
        String personXml = """
        <Person id="1447992" action="add" date="21-Feb-2023">
            <Gender>Male</Gender>
            <ActiveStatus>Active</ActiveStatus>
            <Deceased>No</Deceased>
            <NameDetails>
                <Name NameType="Primary Name">
                    <NameValue>
                        <FirstName>Asim</FirstName>
                        <Surname>Sarajlić</Surname>
                    </NameValue>
                </Name>
                <Name NameType="Spelling Variation">
                    <NameValue>
                        <FirstName>Asim</FirstName>
                        <Surname>Sarajlich</Surname>
                    </NameValue>
                    <NameValue>
                        <FirstName>Asim</FirstName>
                        <Surname>Sarajlic</Surname>
                    </NameValue>
                </Name>
            </NameDetails>
            <Descriptions>
                <Description Description1="1"/>
                <Description Description1="3" Description2="1"/>
                <Description Description1="3" Description2="10"/>
            </Descriptions>
            <RoleDetail>
                <Roles RoleType="Primary Occupation">
                    <OccTitle OccCat="3">See Previous Roles</OccTitle>
                </Roles>
                <Roles RoleType="Previous Roles">
                    <OccTitle SinceDay="30" SinceMonth="Dec" SinceYear="2010" ToDay="07" ToMonth="Oct" ToYear="2018" OccCat="3">Member, BiH H</OccTitle>
                    <OccTitle SinceYear="2013" OccCat="16">Deputy Chairman, Party of Democratic Action (SDA)</OccTitle>
                    <OccTitle SinceDay="07" SinceMonth="Oct" SinceYear="2018" ToDay="28" ToMonth="Feb" ToYear="2019" OccCat="3">Member, Feder</OccTitle>
                    <OccTitle SinceDay="28" SinceMonth="Feb" SinceYear="2019" ToDay="02" ToMonth="Oct" ToYear="2022" OccCat="3">Member, BiH H</OccTitle>
                </Roles>
            </RoleDetail>
            <DateDetails>
                <Date DateType="Date of Birth">
                    <DateValue Day="03" Month="Oct" Year="1975"/>
                </Date>
                <Date DateType="Inactive as of (PEP)">
                    <DateValue Day="02" Month="Oct" Year="2022"/>
                </Date>
            </DateDetails>
            <BirthPlace>
                <Place name="Sarajevo,Bosnia and Herzegovina"/>
            </BirthPlace>
            <SanctionsReferences>
                <Reference SinceDay="11" SinceMonth="Apr" SinceYear="2022">1</Reference>
            </SanctionsReferences>
            <CountryDetails>
                <Country CountryType="Citizenship">
                    <CountryValue Code="BSHZG"/>
                </Country>
                <Country CountryType="Resident of">
                    <CountryValue Code="BSHZG"/>
                </Country>
                <Country CountryType="Jurisdiction">
                    <CountryValue Code="BSHZG"/>
                </Country>
                <Country CountryType="Country of Reported Allegation">
                    <CountryValue Code="BSHZG"/>
                </Country>
            </CountryDetails>
            <IDNumberTypes>
                <ID IDType="OFAC Program ID">
                    <IDValue>BALKANS-EO14033</IDValue>
                </ID>
                <ID IDType="OFAC Unique ID">
                    <IDValue>35956</IDValue>
                </ID>
            </IDNumberTypes>
            <ProfileNotes>PROFILE CREATED: 02-Aug-2022 UPDATE ADDED: 09-Feb-2023 </ProfileNotes>
            <SourceDescription>
                <Source name="https://home.treasury.gov/policy-issues/financial-sanctions/recent-actions/20220411"/>
                <Source name="https://www.treasury.gov/ofac/downloads/sdnlist.pdf"/>
            </SourceDescription>
            <Images>
                <Image URL="http://thebosniatimes.ba/vijestiSlike/vijestSlika_2015-04-29-04-11-48.jpg"/>
                <Image URL="http://imovinapoliticara.cin.ba/images/AsimSarajlic.jpg"/>
            </Images>
        </Person>
        """;

        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLEventReader reader = factory.createXMLEventReader(new StringReader(personXml));
        Map<String, Object> recordMap = unmarshaller.parseFragmentFromEventReader(reader);

        assertNotNull(recordMap);
        assertEquals("1447992", recordMap.get("@id"));
        assertEquals("add", recordMap.get("@action"));
        assertEquals("21-Feb-2023", recordMap.get("@date"));

        // Field mappings matching the DB schema provided by user
        List<FieldMapping> mappings = List.of(
                // Person main table
                new FieldMapping("@id", "Person", "person_id", "NONE"),
                new FieldMapping("@action", "Person", "action", "NONE"),
                new FieldMapping("@date", "Person", "record_date", "NONE"),
                new FieldMapping("Gender", "Person", "gender", "NONE"),
                new FieldMapping("ActiveStatus", "Person", "active_status", "NONE"),
                new FieldMapping("Deceased", "Person", "deceased", "NONE"),
                new FieldMapping("Place/@name", "Person", "birth_place", "NONE"),
                new FieldMapping("ProfileNotes", "Person", "profile_notes", "NONE"),

                // PersonName table
                new FieldMapping("person_id", "PersonName", "person_id", "NONE"),
                new FieldMapping("NameType", "PersonName", "name_type", "NONE"),
                new FieldMapping("FirstName", "PersonName", "first_name", "NONE"),
                new FieldMapping("Surname", "PersonName", "surname", "NONE"),

                // PersonDescription table
                new FieldMapping("person_id", "PersonDescription", "person_id", "NONE"),
                new FieldMapping("@Description1", "PersonDescription", "description_1_id", "NONE"),
                new FieldMapping("@Description2", "PersonDescription", "description_2_id", "NONE"),
                new FieldMapping("@Description3", "PersonDescription", "description_3_id", "NONE"),

                // PersonRole table
                new FieldMapping("person_id", "PersonRole", "person_id", "NONE"),
                new FieldMapping("RoleType", "PersonRole", "role_type", "NONE"),
                new FieldMapping("OccTitle", "PersonRole", "occ_title", "NONE"),
                new FieldMapping("@OccCat", "PersonRole", "occ_cat", "NONE"),
                new FieldMapping("@SinceDay", "PersonRole", "since_day", "NONE"),
                new FieldMapping("@SinceMonth", "PersonRole", "since_month", "NONE"),
                new FieldMapping("@SinceYear", "PersonRole", "since_year", "NONE"),
                new FieldMapping("@ToDay", "PersonRole", "to_day", "NONE"),
                new FieldMapping("@ToMonth", "PersonRole", "to_month", "NONE"),
                new FieldMapping("@ToYear", "PersonRole", "to_year", "NONE"),

                // PersonDate table
                new FieldMapping("person_id", "PersonDate", "person_id", "NONE"),
                new FieldMapping("DateType", "PersonDate", "date_type", "NONE"),
                new FieldMapping("@Day", "PersonDate", "day", "NONE"),
                new FieldMapping("@Month", "PersonDate", "month", "NONE"),
                new FieldMapping("@Year", "PersonDate", "year", "NONE"),

                // PersonSanctionsReference table
                new FieldMapping("person_id", "PersonSanctionsReference", "person_id", "NONE"),
                new FieldMapping("Reference", "PersonSanctionsReference", "reference_id", "NONE"),
                new FieldMapping("@SinceDay", "PersonSanctionsReference", "since_day", "NONE"),
                new FieldMapping("@SinceMonth", "PersonSanctionsReference", "since_month", "NONE"),
                new FieldMapping("@SinceYear", "PersonSanctionsReference", "since_year", "NONE"),

                // PersonCountry table
                new FieldMapping("person_id", "PersonCountry", "person_id", "NONE"),
                new FieldMapping("CountryType", "PersonCountry", "country_type", "NONE"),
                new FieldMapping("@Code", "PersonCountry", "country_code", "NONE"),

                // PersonIDNumber table
                new FieldMapping("person_id", "PersonIDNumber", "person_id", "NONE"),
                new FieldMapping("IDType", "PersonIDNumber", "id_type", "NONE"),
                new FieldMapping("IDValue", "PersonIDNumber", "id_value", "NONE"),

                // PersonSource table
                new FieldMapping("person_id", "PersonSource", "person_id", "NONE"),
                new FieldMapping("@name", "PersonSource", "source_url", "NONE"),

                // PersonImage table
                new FieldMapping("person_id", "PersonImage", "person_id", "NONE"),
                new FieldMapping("@URL", "PersonImage", "image_url", "NONE")
        );

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor("Person", mappings);
        DatabaseRowPayload payload = processor.process(recordMap);

        assertNotNull(payload, "Payload must not be null");
        Map<String, List<Map<String, Object>>> multiRecords = payload.getMultiTableRecords();
        assertNotNull(multiRecords);

        // Verify Person table
        List<Map<String, Object>> personRows = multiRecords.get("Person");
        assertNotNull(personRows);
        System.out.println("Person rows in multiRecords: " + personRows);
        assertEquals(1, personRows.size());
        assertEquals("1447992", personRows.get(0).get("person_id"));
        assertEquals("add", personRows.get(0).get("action"));
        assertEquals("21-Feb-2023", personRows.get(0).get("record_date"));
        assertEquals("Male", personRows.get(0).get("gender"));
        assertEquals("Sarajevo,Bosnia and Herzegovina", personRows.get(0).get("birth_place"));

        // Verify PersonName table (should have 3 rows with name_type, first_name, surname, person_id)
        List<Map<String, Object>> nameRows = multiRecords.get("PersonName");
        assertNotNull(nameRows, "PersonName rows must be present");
        assertTrue(nameRows.size() >= 2, "Expected at least 2 name rows, got: " + nameRows.size());
        for (Map<String, Object> nr : nameRows) {
            assertEquals("1447992", nr.get("person_id"));
            assertNotNull(nr.get("first_name"));
            assertNotNull(nr.get("surname"));
        }

        // Verify PersonRole table
        List<Map<String, Object>> roleRows = multiRecords.get("PersonRole");
        assertNotNull(roleRows, "PersonRole rows must be present");
        assertTrue(roleRows.size() >= 2);
        for (Map<String, Object> rr : roleRows) {
            assertEquals("1447992", rr.get("person_id"));
        }

        // Verify PersonDescription table
        List<Map<String, Object>> descRows = multiRecords.get("PersonDescription");
        assertNotNull(descRows, "PersonDescription rows must be present");
        assertEquals(3, descRows.size());
        for (Map<String, Object> dr : descRows) {
            assertEquals("1447992", dr.get("person_id"));
        }

        // Verify PersonDate table
        List<Map<String, Object>> dateRows = multiRecords.get("PersonDate");
        assertNotNull(dateRows, "PersonDate rows must be present");
        assertEquals(2, dateRows.size());

        // Verify PersonCountry table
        List<Map<String, Object>> countryRows = multiRecords.get("PersonCountry");
        assertNotNull(countryRows, "PersonCountry rows must be present");
        assertEquals(4, countryRows.size());
        for (Map<String, Object> cr : countryRows) {
            assertEquals("1447992", cr.get("person_id"));
            assertEquals("BSHZG", cr.get("country_code"));
        }

        // Verify PersonIDNumber table
        List<Map<String, Object>> idRows = multiRecords.get("PersonIDNumber");
        assertNotNull(idRows, "PersonIDNumber rows must be present");
        assertEquals(2, idRows.size());
        for (Map<String, Object> ir : idRows) {
            assertEquals("1447992", ir.get("person_id"));
        }

        // Verify PersonImage table
        List<Map<String, Object>> imgRows = multiRecords.get("PersonImage");
        assertNotNull(imgRows, "PersonImage rows must be present");
        assertEquals(2, imgRows.size());
        for (Map<String, Object> im : imgRows) {
            assertEquals("1447992", im.get("person_id"));
            assertNotNull(im.get("image_url"));
        }
    }

    @Test
    @DisplayName("Verify HeaderReferenceIngestionTasklet streams lookup tables before Person records")
    void testHeaderReferenceIngestionTasklet() throws Exception {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS CountryList (code VARCHAR(50) PRIMARY KEY, name VARCHAR(100), is_territory BOOLEAN, profile_url VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS OccupationList (code VARCHAR(50) PRIMARY KEY, name VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS RelationshipList (code VARCHAR(50) PRIMARY KEY, name VARCHAR(100))");
            stmt.execute("CREATE TABLE IF NOT EXISTS SanctionsReferencesList (reference_id VARCHAR(50) PRIMARY KEY, name VARCHAR(255), status VARCHAR(50), description_2_id VARCHAR(50))");
            stmt.execute("DELETE FROM CountryList");
            stmt.execute("DELETE FROM OccupationList");
            stmt.execute("DELETE FROM RelationshipList");
            stmt.execute("DELETE FROM SanctionsReferencesList");
        }

        List<FieldMapping> mappings = List.of(
                new FieldMapping("CountryName/@code", "CountryList", "code", "NONE"),
                new FieldMapping("CountryName/@name", "CountryList", "name", "NONE"),
                new FieldMapping("CountryName/@IsTerritory", "CountryList", "is_territory", "NONE"),
                new FieldMapping("CountryName/@ProfileURL", "CountryList", "profile_url", "NONE"),

                new FieldMapping("Occupation/@code", "OccupationList", "code", "NONE"),
                new FieldMapping("Occupation/@name", "OccupationList", "name", "NONE"),

                new FieldMapping("Relationship/@code", "RelationshipList", "code", "NONE"),
                new FieldMapping("Relationship/@name", "RelationshipList", "name", "NONE"),

                new FieldMapping("ReferenceName/@code", "SanctionsReferencesList", "reference_id", "NONE"),
                new FieldMapping("ReferenceName/@name", "SanctionsReferencesList", "name", "NONE"),
                new FieldMapping("ReferenceName/@status", "SanctionsReferencesList", "status", "NONE"),
                new FieldMapping("ReferenceName/@Description2Id", "SanctionsReferencesList", "description_2_id", "NONE")
        );

        com.enterprise.xmldbtool.batch.HeaderReferenceIngestionTasklet tasklet =
                new com.enterprise.xmldbtool.batch.HeaderReferenceIngestionTasklet(
                        "sample-data/pfa-sample.xml",
                        "Person",
                        mappings,
                        dataSource
                );

        org.springframework.batch.core.StepExecution stepExecution =
                org.springframework.batch.test.MetaDataInstanceFactory.createStepExecution();
        tasklet.beforeStep(stepExecution);

        org.springframework.batch.repeat.RepeatStatus status = tasklet.execute(null, null);
        assertEquals(org.springframework.batch.repeat.RepeatStatus.FINISHED, status);

        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM CountryList")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1, "CountryList must have records");
            }
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM OccupationList")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1, "OccupationList must have records");
            }
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM RelationshipList")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1, "RelationshipList must have records");
            }
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM SanctionsReferencesList")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1, "SanctionsReferencesList must have records");
            }
        }
    }

    @Test
    @DisplayName("Verify Full PFA Batch Job Ingestion across lookup tables and entity child tables")
    void testFullPfaBatchJobIngestion() throws Exception {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS CountryList (code VARCHAR(50) PRIMARY KEY, name VARCHAR(100), is_territory BOOLEAN, profile_url VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS OccupationList (code VARCHAR(50) PRIMARY KEY, name VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS RelationshipList (code VARCHAR(50) PRIMARY KEY, name VARCHAR(100))");
            stmt.execute("CREATE TABLE IF NOT EXISTS SanctionsReferencesList (reference_id VARCHAR(50) PRIMARY KEY, name VARCHAR(255), status VARCHAR(50), description_2_id VARCHAR(50))");

            stmt.execute("CREATE TABLE IF NOT EXISTS Person (person_id INT PRIMARY KEY, action VARCHAR(50), record_date DATE, gender VARCHAR(20), active_status VARCHAR(50), deceased VARCHAR(10), birth_place VARCHAR(255), profile_notes TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonName (id SERIAL PRIMARY KEY, person_id INT, name_type VARCHAR(100), first_name VARCHAR(100), surname VARCHAR(100))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonDescription (id SERIAL PRIMARY KEY, person_id INT, description_1_id VARCHAR(50), description_2_id VARCHAR(50), description_3_id VARCHAR(50))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonRole (id SERIAL PRIMARY KEY, person_id INT, role_type VARCHAR(100), occ_title VARCHAR(255), occ_cat VARCHAR(50), since_day VARCHAR(2), since_month VARCHAR(10), since_year VARCHAR(4), to_day VARCHAR(2), to_month VARCHAR(10), to_year VARCHAR(4))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonDate (id SERIAL PRIMARY KEY, person_id INT, date_type VARCHAR(100), \"day\" VARCHAR(2), \"month\" VARCHAR(10), \"year\" VARCHAR(4))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonSanctionsReference (id SERIAL PRIMARY KEY, person_id INT, reference_id VARCHAR(50), since_day VARCHAR(2), since_month VARCHAR(10), since_year VARCHAR(4))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonCountry (id SERIAL PRIMARY KEY, person_id INT, country_type VARCHAR(100), country_code VARCHAR(10))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonIDNumber (id SERIAL PRIMARY KEY, person_id INT, id_type VARCHAR(100), id_value VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonSource (id SERIAL PRIMARY KEY, person_id INT, source_url TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonImage (id SERIAL PRIMARY KEY, person_id INT, image_url TEXT)");

            stmt.execute("DELETE FROM PersonName");
            stmt.execute("DELETE FROM PersonDescription");
            stmt.execute("DELETE FROM PersonRole");
            stmt.execute("DELETE FROM PersonDate");
            stmt.execute("DELETE FROM PersonSanctionsReference");
            stmt.execute("DELETE FROM PersonCountry");
            stmt.execute("DELETE FROM PersonIDNumber");
            stmt.execute("DELETE FROM PersonSource");
            stmt.execute("DELETE FROM PersonImage");
            stmt.execute("DELETE FROM Person");
            stmt.execute("DELETE FROM CountryList");
            stmt.execute("DELETE FROM OccupationList");
            stmt.execute("DELETE FROM RelationshipList");
            stmt.execute("DELETE FROM SanctionsReferencesList");
        }

        List<FieldMapping> mappings = List.of(
                // Lookup tables
                new FieldMapping("CountryName/@code", "CountryList", "code", "NONE"),
                new FieldMapping("CountryName/@name", "CountryList", "name", "NONE"),
                new FieldMapping("CountryName/@IsTerritory", "CountryList", "is_territory", "NONE"),
                new FieldMapping("CountryName/@ProfileURL", "CountryList", "profile_url", "NONE"),

                new FieldMapping("Occupation/@code", "OccupationList", "code", "NONE"),
                new FieldMapping("Occupation/@name", "OccupationList", "name", "NONE"),

                new FieldMapping("Relationship/@code", "RelationshipList", "code", "NONE"),
                new FieldMapping("Relationship/@name", "RelationshipList", "name", "NONE"),

                new FieldMapping("ReferenceName/@code", "SanctionsReferencesList", "reference_id", "NONE"),
                new FieldMapping("ReferenceName/@name", "SanctionsReferencesList", "name", "NONE"),
                new FieldMapping("ReferenceName/@status", "SanctionsReferencesList", "status", "NONE"),
                new FieldMapping("ReferenceName/@Description2Id", "SanctionsReferencesList", "description_2_id", "NONE"),

                // Person main table
                new FieldMapping("@id", "Person", "person_id", "NONE"),
                new FieldMapping("@action", "Person", "action", "NONE"),
                new FieldMapping("@date", "Person", "record_date", "NONE"),
                new FieldMapping("Gender", "Person", "gender", "NONE"),
                new FieldMapping("ActiveStatus", "Person", "active_status", "NONE"),
                new FieldMapping("Deceased", "Person", "deceased", "NONE"),
                new FieldMapping("Place/@name", "Person", "birth_place", "NONE"),
                new FieldMapping("ProfileNotes", "Person", "profile_notes", "NONE"),

                // Child tables
                new FieldMapping("person_id", "PersonName", "person_id", "NONE"),
                new FieldMapping("NameType", "PersonName", "name_type", "NONE"),
                new FieldMapping("FirstName", "PersonName", "first_name", "NONE"),
                new FieldMapping("Surname", "PersonName", "surname", "NONE"),

                new FieldMapping("person_id", "PersonDescription", "person_id", "NONE"),
                new FieldMapping("@Description1", "PersonDescription", "description_1_id", "NONE"),
                new FieldMapping("@Description2", "PersonDescription", "description_2_id", "NONE"),
                new FieldMapping("@Description3", "PersonDescription", "description_3_id", "NONE"),

                new FieldMapping("person_id", "PersonRole", "person_id", "NONE"),
                new FieldMapping("RoleType", "PersonRole", "role_type", "NONE"),
                new FieldMapping("OccTitle", "PersonRole", "occ_title", "NONE"),
                new FieldMapping("@OccCat", "PersonRole", "occ_cat", "NONE"),
                new FieldMapping("@SinceDay", "PersonRole", "since_day", "NONE"),
                new FieldMapping("@SinceMonth", "PersonRole", "since_month", "NONE"),
                new FieldMapping("@SinceYear", "PersonRole", "since_year", "NONE"),
                new FieldMapping("@ToDay", "PersonRole", "to_day", "NONE"),
                new FieldMapping("@ToMonth", "PersonRole", "to_month", "NONE"),
                new FieldMapping("@ToYear", "PersonRole", "to_year", "NONE"),

                new FieldMapping("person_id", "PersonDate", "person_id", "NONE"),
                new FieldMapping("DateType", "PersonDate", "date_type", "NONE"),
                new FieldMapping("@Day", "PersonDate", "day", "NONE"),
                new FieldMapping("@Month", "PersonDate", "month", "NONE"),
                new FieldMapping("@Year", "PersonDate", "year", "NONE"),

                new FieldMapping("person_id", "PersonSanctionsReference", "person_id", "NONE"),
                new FieldMapping("Reference", "PersonSanctionsReference", "reference_id", "NONE"),
                new FieldMapping("@SinceDay", "PersonSanctionsReference", "since_day", "NONE"),
                new FieldMapping("@SinceMonth", "PersonSanctionsReference", "since_month", "NONE"),
                new FieldMapping("@SinceYear", "PersonSanctionsReference", "since_year", "NONE"),

                new FieldMapping("person_id", "PersonCountry", "person_id", "NONE"),
                new FieldMapping("CountryType", "PersonCountry", "country_type", "NONE"),
                new FieldMapping("@Code", "PersonCountry", "country_code", "NONE"),

                new FieldMapping("person_id", "PersonIDNumber", "person_id", "NONE"),
                new FieldMapping("IDType", "PersonIDNumber", "id_type", "NONE"),
                new FieldMapping("IDValue", "PersonIDNumber", "id_value", "NONE"),

                new FieldMapping("person_id", "PersonSource", "person_id", "NONE"),
                new FieldMapping("@name", "PersonSource", "source_url", "NONE"),

                new FieldMapping("person_id", "PersonImage", "person_id", "NONE"),
                new FieldMapping("@URL", "PersonImage", "image_url", "NONE")
        );

        com.enterprise.xmldbtool.model.JobLaunchRequest req = new com.enterprise.xmldbtool.model.JobLaunchRequest();
        req.setSourceXmlPath("sample-data/pfa-sample.xml");
        com.enterprise.xmldbtool.model.DbConnectionConfig dbConfig = new com.enterprise.xmldbtool.model.DbConnectionConfig();
        dbConfig.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dbConfig.setUsername("sa");
        dbConfig.setPassword("");
        dbConfig.setEngine("H2");
        req.setDbConfig(dbConfig);
        req.setRecordRootElement("Person");
        req.setTargetTable("Person");
        req.setTableOrder(List.of(
                "CountryList", "OccupationList", "RelationshipList", "SanctionsReferencesList",
                "Person", "PersonName", "PersonDescription", "PersonRole", "PersonDate",
                "PersonSanctionsReference", "PersonCountry", "PersonIDNumber", "PersonSource", "PersonImage"
        ));
        req.setMappings(mappings);
        req.setChunkSize(100);

        com.enterprise.xmldbtool.model.JobLaunchResponse resp = batchJobService.launchIngestionJob(req);
        assertTrue(resp.isSuccess(), "Job launch must succeed: " + resp.getMessage());
        assertNotNull(resp.getJobExecutionId());

        // Wait for async job completion
        long start = System.currentTimeMillis();
        com.enterprise.xmldbtool.model.JobTelemetryResponse tel = null;
        while (System.currentTimeMillis() - start < 15000) {
            tel = batchJobService.getJobTelemetry(resp.getJobExecutionId());
            if ("COMPLETED".equals(tel.getStatus()) || "FAILED".equals(tel.getStatus())) {
                break;
            }
            Thread.sleep(200);
        }

        assertNotNull(tel);
        System.out.println("Job Status: " + tel.getStatus() + ", Message: " + tel.getMessage());
        assertEquals("COMPLETED", tel.getStatus(), "Job should complete successfully! Error: " + tel.getMessage());

        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            for (String tbl : List.of("CountryList", "OccupationList", "RelationshipList", "SanctionsReferencesList",
                    "Person", "PersonName", "PersonDescription", "PersonRole", "PersonDate",
                    "PersonSanctionsReference", "PersonCountry", "PersonIDNumber", "PersonSource", "PersonImage")) {
                try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tbl)) {
                    assertTrue(rs.next());
                    int count = rs.getInt(1);
                    System.out.println("Table " + tbl + " count: " + count);
                    assertTrue(count >= 1, "Table " + tbl + " must have records, but found: " + count);
                }
            }
        }
    }

    @Test
    @DisplayName("Verify Full PFA Batch Job Ingestion with snake_case tables")
    void testFullPfaBatchJobIngestionSnakeCase() throws Exception {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS country_list (code VARCHAR(50) PRIMARY KEY, name VARCHAR(100), is_territory BOOLEAN, profile_url VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS occupation_list (code VARCHAR(50) PRIMARY KEY, name VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS relationship_list (code VARCHAR(50) PRIMARY KEY, name VARCHAR(100))");
            stmt.execute("CREATE TABLE IF NOT EXISTS sanctions_references_list (reference_id VARCHAR(50) PRIMARY KEY, name VARCHAR(255), status VARCHAR(50), description_2_id VARCHAR(50))");

            stmt.execute("CREATE TABLE IF NOT EXISTS person (person_id INT PRIMARY KEY, action VARCHAR(50), record_date DATE, gender VARCHAR(20), active_status VARCHAR(50), deceased VARCHAR(10), birth_place VARCHAR(255), profile_notes TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS person_name (id SERIAL PRIMARY KEY, person_id INT, name_type VARCHAR(100), first_name VARCHAR(100), surname VARCHAR(100))");
            stmt.execute("CREATE TABLE IF NOT EXISTS person_description (id SERIAL PRIMARY KEY, person_id INT, description_1_id VARCHAR(50), description_2_id VARCHAR(50), description_3_id VARCHAR(50))");
            stmt.execute("CREATE TABLE IF NOT EXISTS person_role (id SERIAL PRIMARY KEY, person_id INT, role_type VARCHAR(100), occ_title VARCHAR(255), occ_cat VARCHAR(50), since_day VARCHAR(2), since_month VARCHAR(10), since_year VARCHAR(4), to_day VARCHAR(2), to_month VARCHAR(10), to_year VARCHAR(4))");
            stmt.execute("CREATE TABLE IF NOT EXISTS person_date (id SERIAL PRIMARY KEY, person_id INT, date_type VARCHAR(100), \"day\" VARCHAR(2), \"month\" VARCHAR(10), \"year\" VARCHAR(4))");
            stmt.execute("CREATE TABLE IF NOT EXISTS person_sanctions_reference (id SERIAL PRIMARY KEY, person_id INT, reference_id VARCHAR(50), since_day VARCHAR(2), since_month VARCHAR(10), since_year VARCHAR(4))");
            stmt.execute("CREATE TABLE IF NOT EXISTS person_country (id SERIAL PRIMARY KEY, person_id INT, country_type VARCHAR(100), country_code VARCHAR(10))");
            stmt.execute("CREATE TABLE IF NOT EXISTS person_id_number (id SERIAL PRIMARY KEY, person_id INT, id_type VARCHAR(100), id_value VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS person_source (id SERIAL PRIMARY KEY, person_id INT, source_url TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS person_image (id SERIAL PRIMARY KEY, person_id INT, image_url TEXT)");

            stmt.execute("DELETE FROM person_name");
            stmt.execute("DELETE FROM person_description");
            stmt.execute("DELETE FROM person_role");
            stmt.execute("DELETE FROM person_date");
            stmt.execute("DELETE FROM person_sanctions_reference");
            stmt.execute("DELETE FROM person_country");
            stmt.execute("DELETE FROM person_id_number");
            stmt.execute("DELETE FROM person_source");
            stmt.execute("DELETE FROM person_image");
            stmt.execute("DELETE FROM person");
            stmt.execute("DELETE FROM country_list");
            stmt.execute("DELETE FROM occupation_list");
            stmt.execute("DELETE FROM relationship_list");
            stmt.execute("DELETE FROM sanctions_references_list");
        }

        List<FieldMapping> mappings = List.of(
                // Lookup tables
                new FieldMapping("CountryName/@code", "country_list", "code", "NONE"),
                new FieldMapping("CountryName/@name", "country_list", "name", "NONE"),
                new FieldMapping("CountryName/@IsTerritory", "country_list", "is_territory", "NONE"),
                new FieldMapping("CountryName/@ProfileURL", "country_list", "profile_url", "NONE"),

                new FieldMapping("Occupation/@code", "occupation_list", "code", "NONE"),
                new FieldMapping("Occupation/@name", "occupation_list", "name", "NONE"),

                new FieldMapping("Relationship/@code", "relationship_list", "code", "NONE"),
                new FieldMapping("Relationship/@name", "relationship_list", "name", "NONE"),

                new FieldMapping("ReferenceName/@code", "sanctions_references_list", "reference_id", "NONE"),
                new FieldMapping("ReferenceName/@name", "sanctions_references_list", "name", "NONE"),
                new FieldMapping("ReferenceName/@status", "sanctions_references_list", "status", "NONE"),
                new FieldMapping("ReferenceName/@Description2Id", "sanctions_references_list", "description_2_id", "NONE"),

                // Person main table
                new FieldMapping("@id", "person", "person_id", "NONE"),
                new FieldMapping("@action", "person", "action", "NONE"),
                new FieldMapping("@date", "person", "record_date", "NONE"),
                new FieldMapping("Gender", "person", "gender", "NONE"),
                new FieldMapping("ActiveStatus", "person", "active_status", "NONE"),
                new FieldMapping("Deceased", "person", "deceased", "NONE"),
                new FieldMapping("Place/@name", "person", "birth_place", "NONE"),
                new FieldMapping("ProfileNotes", "person", "profile_notes", "NONE"),

                // Child tables
                new FieldMapping("person_id", "person_name", "person_id", "NONE"),
                new FieldMapping("NameType", "person_name", "name_type", "NONE"),
                new FieldMapping("FirstName", "person_name", "first_name", "NONE"),
                new FieldMapping("Surname", "person_name", "surname", "NONE"),

                new FieldMapping("person_id", "person_description", "person_id", "NONE"),
                new FieldMapping("@Description1", "person_description", "description_1_id", "NONE"),
                new FieldMapping("@Description2", "person_description", "description_2_id", "NONE"),
                new FieldMapping("@Description3", "person_description", "description_3_id", "NONE"),

                new FieldMapping("person_id", "person_role", "person_id", "NONE"),
                new FieldMapping("RoleType", "person_role", "role_type", "NONE"),
                new FieldMapping("OccTitle", "person_role", "occ_title", "NONE"),
                new FieldMapping("@OccCat", "person_role", "occ_cat", "NONE"),
                new FieldMapping("@SinceDay", "person_role", "since_day", "NONE"),
                new FieldMapping("@SinceMonth", "person_role", "since_month", "NONE"),
                new FieldMapping("@SinceYear", "person_role", "since_year", "NONE"),
                new FieldMapping("@ToDay", "person_role", "to_day", "NONE"),
                new FieldMapping("@ToMonth", "person_role", "to_month", "NONE"),
                new FieldMapping("@ToYear", "person_role", "to_year", "NONE"),

                new FieldMapping("person_id", "person_date", "person_id", "NONE"),
                new FieldMapping("DateType", "person_date", "date_type", "NONE"),
                new FieldMapping("@Day", "person_date", "day", "NONE"),
                new FieldMapping("@Month", "person_date", "month", "NONE"),
                new FieldMapping("@Year", "person_date", "year", "NONE"),

                new FieldMapping("person_id", "person_sanctions_reference", "person_id", "NONE"),
                new FieldMapping("Reference", "person_sanctions_reference", "reference_id", "NONE"),
                new FieldMapping("@SinceDay", "person_sanctions_reference", "since_day", "NONE"),
                new FieldMapping("@SinceMonth", "person_sanctions_reference", "since_month", "NONE"),
                new FieldMapping("@SinceYear", "person_sanctions_reference", "since_year", "NONE"),

                new FieldMapping("person_id", "person_country", "person_id", "NONE"),
                new FieldMapping("CountryType", "person_country", "country_type", "NONE"),
                new FieldMapping("@Code", "person_country", "country_code", "NONE"),

                new FieldMapping("person_id", "person_id_number", "person_id", "NONE"),
                new FieldMapping("IDType", "person_id_number", "id_type", "NONE"),
                new FieldMapping("IDValue", "person_id_number", "id_value", "NONE"),

                new FieldMapping("person_id", "person_source", "person_id", "NONE"),
                new FieldMapping("@name", "person_source", "source_url", "NONE"),

                new FieldMapping("person_id", "person_image", "person_id", "NONE"),
                new FieldMapping("@URL", "person_image", "image_url", "NONE")
        );

        com.enterprise.xmldbtool.model.JobLaunchRequest req = new com.enterprise.xmldbtool.model.JobLaunchRequest();
        req.setSourceXmlPath("sample-data/pfa-sample.xml");
        com.enterprise.xmldbtool.model.DbConnectionConfig dbConfig = new com.enterprise.xmldbtool.model.DbConnectionConfig();
        dbConfig.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dbConfig.setUsername("sa");
        dbConfig.setPassword("");
        dbConfig.setEngine("H2");
        req.setDbConfig(dbConfig);
        req.setRecordRootElement("Person");
        req.setTargetTable("person");
        req.setTableOrder(List.of(
                "country_list", "occupation_list", "relationship_list", "sanctions_references_list",
                "person", "person_name", "person_description", "person_role", "person_date",
                "person_sanctions_reference", "person_country", "person_id_number", "person_source", "person_image"
        ));
        req.setMappings(mappings);
        req.setChunkSize(100);

        com.enterprise.xmldbtool.model.JobLaunchResponse resp = batchJobService.launchIngestionJob(req);
        assertTrue(resp.isSuccess(), "Job launch must succeed: " + resp.getMessage());
        assertNotNull(resp.getJobExecutionId());

        long start = System.currentTimeMillis();
        com.enterprise.xmldbtool.model.JobTelemetryResponse tel = null;
        while (System.currentTimeMillis() - start < 15000) {
            tel = batchJobService.getJobTelemetry(resp.getJobExecutionId());
            if ("COMPLETED".equals(tel.getStatus()) || "FAILED".equals(tel.getStatus())) {
                break;
            }
            Thread.sleep(200);
        }

        assertNotNull(tel);
        assertEquals("COMPLETED", tel.getStatus(), "Job should complete successfully! Error: " + tel.getMessage());

        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            for (String tbl : List.of("country_list", "occupation_list", "relationship_list", "sanctions_references_list",
                    "person", "person_name", "person_description", "person_role", "person_date",
                    "person_sanctions_reference", "person_country", "person_id_number", "person_source", "person_image")) {
                try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tbl)) {
                    assertTrue(rs.next());
                    int count = rs.getInt(1);
                    System.out.println("Table " + tbl + " count: " + count);
                    assertTrue(count >= 1, "Table " + tbl + " must have records, but found: " + count);
                }
            }
        }
    }

    @Test
    @DisplayName("Verify StAX Streaming into Flat Staging with SHA-256 Delta Hashing")
    void testStagedXmlStreamingWithDeltaHashing() throws Exception {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS staging_xml_records (" +
                    "id BIGSERIAL PRIMARY KEY, job_execution_id BIGINT, entity_type VARCHAR(50), " +
                    "record_identifier VARCHAR(100), record_action VARCHAR(50), record_date VARCHAR(50), " +
                    "raw_payload TEXT, payload_hash VARCHAR(64), status VARCHAR(30), error_message TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("DELETE FROM staging_xml_records");
        }

        // First ingestion run: should be STAGED
        var summary1 = stagingStreamingService.streamXmlToStaging(
                "sample-data/pfa-sample.xml", "Person", 101L, dataSource, 100);
        assertTrue(summary1.totalRead() >= 1, "Must read at least 1 record fragment");
        assertTrue(summary1.stagedCount() >= 1, "Must stage newly discovered records");
        assertEquals(0, summary1.skippedUnchangedCount(), "First run has no existing hashes to skip");

        // Verify database contents
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery("SELECT record_identifier, payload_hash, status FROM staging_xml_records WHERE status = 'STAGED'")) {
            assertTrue(rs.next());
            assertEquals("1447992", rs.getString("record_identifier"));
            String hash = rs.getString("payload_hash");
            assertNotNull(hash);
            assertEquals(64, hash.length(), "SHA-256 hash must be 64 characters hex");
            assertEquals("STAGED", rs.getString("status"));
        }

        // Second ingestion run with identical data: delta detector must mark as SKIPPED_UNCHANGED
        var summary2 = stagingStreamingService.streamXmlToStaging(
                "sample-data/pfa-sample.xml", "Person", 102L, dataSource, 100);
        assertTrue(summary2.skippedUnchangedCount() >= 1, "Subsequent run with identical hash must be SKIPPED_UNCHANGED");
    }

    @Test
    @DisplayName("Verify Schema Evolution handles unmapped and newly added XML attributes dynamically")
    void testSchemaEvolutionHandling() {
        Map<String, Object> rawRecord = new LinkedHashMap<>();
        rawRecord.put("@id", "9910");
        rawRecord.put("person_id", "9910");
        rawRecord.put("FirstName", "John");
        rawRecord.put("Surname", "Doe");
        // Newly evolved tags in XML schema not present in existing table mapping
        rawRecord.put("BiometricScore", "0.985");
        rawRecord.put("RiskClassification", "CRITICAL");
        rawRecord.put("ComplianceTier", "TIER-1");

        List<FieldMapping> mappings = List.of(
                new FieldMapping("@id", "Person", "person_id", "NONE"),
                new FieldMapping("FirstName", "PersonName", "first_name", "NONE"),
                new FieldMapping("Surname", "PersonName", "surname", "NONE")
        );

        schemaResolutionService.enrichWithSchemaEvolutionAttributes(rawRecord, mappings);

        assertTrue(rawRecord.containsKey("extra_attributes"), "Schema evolution must capture unmapped tags into extra_attributes JSON");
        String extraJson = (String) rawRecord.get("extra_attributes");
        assertNotNull(extraJson);
        assertTrue(extraJson.contains("BiometricScore"));
        assertTrue(extraJson.contains("RiskClassification"));
        assertTrue(extraJson.contains("ComplianceTier"));
    }

    @Test
    @DisplayName("Verify Full Two-Stage Staged Pipeline: StAX Flat Staging -> Canonical Relational Normalization")
    void testFullTwoStageStagedPipeline() throws Exception {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS CountryList (code VARCHAR(50) PRIMARY KEY, name VARCHAR(100), is_territory BOOLEAN, profile_url VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS OccupationList (code VARCHAR(50) PRIMARY KEY, name VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS RelationshipList (code VARCHAR(50) PRIMARY KEY, name VARCHAR(100))");
            stmt.execute("CREATE TABLE IF NOT EXISTS SanctionsReferencesList (reference_id VARCHAR(50) PRIMARY KEY, name VARCHAR(255), status VARCHAR(50), description_2_id VARCHAR(50))");

            stmt.execute("CREATE TABLE IF NOT EXISTS Person (person_id INT PRIMARY KEY, action VARCHAR(50), record_date DATE, gender VARCHAR(20), active_status VARCHAR(50), deceased VARCHAR(10), birth_place VARCHAR(255), profile_notes TEXT, extra_attributes TEXT)");
            try {
                stmt.execute("ALTER TABLE Person ADD COLUMN IF NOT EXISTS extra_attributes TEXT");
            } catch (Exception ignored) {}
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonName (id SERIAL PRIMARY KEY, person_id INT, name_type VARCHAR(100), first_name VARCHAR(100), surname VARCHAR(100))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonDescription (id SERIAL PRIMARY KEY, person_id INT, description_1_id VARCHAR(50), description_2_id VARCHAR(50), description_3_id VARCHAR(50))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonRole (id SERIAL PRIMARY KEY, person_id INT, role_type VARCHAR(100), occ_title VARCHAR(255), occ_cat VARCHAR(50), since_day VARCHAR(2), since_month VARCHAR(10), since_year VARCHAR(4), to_day VARCHAR(2), to_month VARCHAR(10), to_year VARCHAR(4))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonDate (id SERIAL PRIMARY KEY, person_id INT, date_type VARCHAR(100), \"day\" VARCHAR(2), \"month\" VARCHAR(10), \"year\" VARCHAR(4))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonSanctionsReference (id SERIAL PRIMARY KEY, person_id INT, reference_id VARCHAR(50), since_day VARCHAR(2), since_month VARCHAR(10), since_year VARCHAR(4))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonCountry (id SERIAL PRIMARY KEY, person_id INT, country_type VARCHAR(100), country_code VARCHAR(10))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonIDNumber (id SERIAL PRIMARY KEY, person_id INT, id_type VARCHAR(100), id_value VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonSource (id SERIAL PRIMARY KEY, person_id INT, source_url TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS PersonImage (id SERIAL PRIMARY KEY, person_id INT, image_url TEXT)");

            stmt.execute("DELETE FROM PersonName");
            stmt.execute("DELETE FROM PersonDescription");
            stmt.execute("DELETE FROM PersonRole");
            stmt.execute("DELETE FROM PersonDate");
            stmt.execute("DELETE FROM PersonSanctionsReference");
            stmt.execute("DELETE FROM PersonCountry");
            stmt.execute("DELETE FROM PersonIDNumber");
            stmt.execute("DELETE FROM PersonSource");
            stmt.execute("DELETE FROM PersonImage");
            stmt.execute("DELETE FROM Person");
            stmt.execute("DELETE FROM CountryList");
            stmt.execute("DELETE FROM OccupationList");
            stmt.execute("DELETE FROM RelationshipList");
            stmt.execute("DELETE FROM SanctionsReferencesList");
            stmt.execute("DELETE FROM staging_xml_records");
        }

        List<FieldMapping> mappings = List.of(
                // Lookup tables
                new FieldMapping("CountryName/@code", "CountryList", "code", "NONE"),
                new FieldMapping("CountryName/@name", "CountryList", "name", "NONE"),
                new FieldMapping("CountryName/@IsTerritory", "CountryList", "is_territory", "NONE"),
                new FieldMapping("CountryName/@ProfileURL", "CountryList", "profile_url", "NONE"),

                new FieldMapping("Occupation/@code", "OccupationList", "code", "NONE"),
                new FieldMapping("Occupation/@name", "OccupationList", "name", "NONE"),

                new FieldMapping("Relationship/@code", "RelationshipList", "code", "NONE"),
                new FieldMapping("Relationship/@name", "RelationshipList", "name", "NONE"),

                new FieldMapping("ReferenceName/@code", "SanctionsReferencesList", "reference_id", "NONE"),
                new FieldMapping("ReferenceName/@name", "SanctionsReferencesList", "name", "NONE"),
                new FieldMapping("ReferenceName/@status", "SanctionsReferencesList", "status", "NONE"),
                new FieldMapping("ReferenceName/@Description2Id", "SanctionsReferencesList", "description_2_id", "NONE"),

                // Person master table
                new FieldMapping("@id", "Person", "person_id", "NONE"),
                new FieldMapping("@action", "Person", "action", "NONE"),
                new FieldMapping("@date", "Person", "record_date", "NONE"),
                new FieldMapping("Gender", "Person", "gender", "NONE"),
                new FieldMapping("ActiveStatus", "Person", "active_status", "NONE"),
                new FieldMapping("Deceased", "Person", "deceased", "NONE"),
                new FieldMapping("Place/@name", "Person", "birth_place", "NONE"),
                new FieldMapping("ProfileNotes", "Person", "profile_notes", "NONE"),
                new FieldMapping("extra_attributes", "Person", "extra_attributes", "NONE"),

                // Child 1-to-N tables
                new FieldMapping("person_id", "PersonName", "person_id", "NONE"),
                new FieldMapping("NameType", "PersonName", "name_type", "NONE"),
                new FieldMapping("FirstName", "PersonName", "first_name", "NONE"),
                new FieldMapping("Surname", "PersonName", "surname", "NONE"),

                new FieldMapping("person_id", "PersonDescription", "person_id", "NONE"),
                new FieldMapping("@Description1", "PersonDescription", "description_1_id", "NONE"),
                new FieldMapping("@Description2", "PersonDescription", "description_2_id", "NONE"),
                new FieldMapping("@Description3", "PersonDescription", "description_3_id", "NONE"),

                new FieldMapping("person_id", "PersonRole", "person_id", "NONE"),
                new FieldMapping("RoleType", "PersonRole", "role_type", "NONE"),
                new FieldMapping("OccTitle", "PersonRole", "occ_title", "NONE"),
                new FieldMapping("@OccCat", "PersonRole", "occ_cat", "NONE"),
                new FieldMapping("@SinceDay", "PersonRole", "since_day", "NONE"),
                new FieldMapping("@SinceMonth", "PersonRole", "since_month", "NONE"),
                new FieldMapping("@SinceYear", "PersonRole", "since_year", "NONE"),
                new FieldMapping("@ToDay", "PersonRole", "to_day", "NONE"),
                new FieldMapping("@ToMonth", "PersonRole", "to_month", "NONE"),
                new FieldMapping("@ToYear", "PersonRole", "to_year", "NONE"),

                new FieldMapping("person_id", "PersonDate", "person_id", "NONE"),
                new FieldMapping("DateType", "PersonDate", "date_type", "NONE"),
                new FieldMapping("@Day", "PersonDate", "day", "NONE"),
                new FieldMapping("@Month", "PersonDate", "month", "NONE"),
                new FieldMapping("@Year", "PersonDate", "year", "NONE"),

                new FieldMapping("person_id", "PersonSanctionsReference", "person_id", "NONE"),
                new FieldMapping("Reference", "PersonSanctionsReference", "reference_id", "NONE"),
                new FieldMapping("@SinceDay", "PersonSanctionsReference", "since_day", "NONE"),
                new FieldMapping("@SinceMonth", "PersonSanctionsReference", "since_month", "NONE"),
                new FieldMapping("@SinceYear", "PersonSanctionsReference", "since_year", "NONE"),

                new FieldMapping("person_id", "PersonCountry", "person_id", "NONE"),
                new FieldMapping("CountryType", "PersonCountry", "country_type", "NONE"),
                new FieldMapping("@Code", "PersonCountry", "country_code", "NONE"),

                new FieldMapping("person_id", "PersonIDNumber", "person_id", "NONE"),
                new FieldMapping("IDType", "PersonIDNumber", "id_type", "NONE"),
                new FieldMapping("IDValue", "PersonIDNumber", "id_value", "NONE"),

                new FieldMapping("person_id", "PersonSource", "person_id", "NONE"),
                new FieldMapping("@name", "PersonSource", "source_url", "NONE"),

                new FieldMapping("person_id", "PersonImage", "person_id", "NONE"),
                new FieldMapping("@URL", "PersonImage", "image_url", "NONE")
        );

        com.enterprise.xmldbtool.model.JobLaunchRequest req = new com.enterprise.xmldbtool.model.JobLaunchRequest();
        req.setSourceXmlPath("sample-data/pfa-sample.xml");
        com.enterprise.xmldbtool.model.DbConnectionConfig dbConfig = new com.enterprise.xmldbtool.model.DbConnectionConfig();
        dbConfig.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dbConfig.setUsername("sa");
        dbConfig.setPassword("");
        dbConfig.setEngine("H2");
        req.setDbConfig(dbConfig);
        req.setRecordRootElement("Person");
        req.setTargetTable("Person");
        req.setTableOrder(List.of(
                "CountryList", "OccupationList", "RelationshipList", "SanctionsReferencesList",
                "Person", "PersonName", "PersonDescription", "PersonRole", "PersonDate",
                "PersonSanctionsReference", "PersonCountry", "PersonIDNumber", "PersonSource", "PersonImage"
        ));
        req.setMappings(mappings);
        req.setChunkSize(100);
        req.setStagedPipeline(true); // Trigger 2-Stage Staged Pipeline

        com.enterprise.xmldbtool.model.JobLaunchResponse resp = batchJobService.launchIngestionJob(req);
        assertTrue(resp.isSuccess(), "Staged Pipeline Job launch must succeed");
        assertNotNull(resp.getJobExecutionId());

        long start = System.currentTimeMillis();
        com.enterprise.xmldbtool.model.JobTelemetryResponse tel = null;
        while (System.currentTimeMillis() - start < 15000) {
            tel = batchJobService.getJobTelemetry(resp.getJobExecutionId());
            if ("COMPLETED".equals(tel.getStatus()) || "FAILED".equals(tel.getStatus())) {
                break;
            }
            Thread.sleep(200);
        }

        assertNotNull(tel);
        assertEquals("COMPLETED", tel.getStatus(), "Staged Pipeline job must complete: " + tel.getMessage());

        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            // Verify staging records were marked NORMALIZED
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM staging_xml_records WHERE status = 'NORMALIZED'")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1, "Staging records must be NORMALIZED");
            }

            // Verify all canonical tables have records
            for (String tbl : List.of("CountryList", "OccupationList", "RelationshipList", "SanctionsReferencesList",
                    "Person", "PersonName", "PersonDescription", "PersonRole", "PersonDate",
                    "PersonSanctionsReference", "PersonCountry", "PersonIDNumber", "PersonSource", "PersonImage")) {
                try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tbl)) {
                    assertTrue(rs.next());
                    int count = rs.getInt(1);
                    System.out.println("Staged Pipeline Table " + tbl + " count: " + count);
                    assertTrue(count >= 1, "Canonical table " + tbl + " must have records, found: " + count);
                }
            }
        }
    }

    @Test
    @DisplayName("Verify Schema Discovery automatically provisions flat staging table and canonical relational tables")
    void testSchemaDiscoveryProvisionsTables() throws Exception {
        com.enterprise.xmldbtool.model.DbConnectionConfig dbConfig = new com.enterprise.xmldbtool.model.DbConnectionConfig();
        dbConfig.setEngine("H2");
        dbConfig.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dbConfig.setUsername("sa");
        dbConfig.setPassword("");

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest request = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        request.setDbConfig(dbConfig);
        request.setSourceXmlPath("sample-data/pfa-sample.xml");
        request.setRecordRootElement("Person");
        request.setAutoCreateTables(true);

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse response = discoveryService.discoverSchema(request);
        assertTrue(response.isSuccess(), "Schema discovery should succeed");
        assertNotNull(response.getDiscoveredTables(), "Discovered tables should not be null");

        List<String> tableNames = response.getDiscoveredTables().stream()
                .map(t -> t.getTableName().toLowerCase())
                .toList();

        System.out.println("Discovered & Provisioned Tables: " + tableNames);

        // Verify flat staging table exists
        assertTrue(tableNames.contains("staging_xml_records"), "Must contain flat staging table 'staging_xml_records'");

        // Verify flattened Person table based on XML tags exists
        assertTrue(tableNames.contains("personflat") || tableNames.contains("person_flat"), "Must contain flattened table for Person");

        // Verify canonical Master and 1-to-N child tables exist
        assertTrue(tableNames.contains("person"), "Must contain Master Person table");
        assertTrue(tableNames.contains("personname"), "Must contain PersonName 1-to-N child table");
        assertTrue(tableNames.contains("personrole"), "Must contain PersonRole 1-to-N child table");
        assertTrue(tableNames.contains("persondescription"), "Must contain PersonDescription 1-to-N child table");
        assertTrue(tableNames.contains("persondate"), "Must contain PersonDate 1-to-N child table");
        assertTrue(tableNames.contains("personsanctionsreference"), "Must contain PersonSanctionsReference 1-to-N child table");
        assertTrue(tableNames.contains("personcountry"), "Must contain PersonCountry 1-to-N child table");
        assertTrue(tableNames.contains("personidnumber"), "Must contain PersonIDNumber 1-to-N child table");
        assertTrue(tableNames.contains("personsource"), "Must contain PersonSource 1-to-N child table");
        assertTrue(tableNames.contains("personimage"), "Must contain PersonImage 1-to-N child table");

        // Verify lookup reference tables exist based on header XML tags
        assertTrue(tableNames.contains("countrylist"), "Must contain CountryList lookup table");
        assertTrue(tableNames.contains("occupationlist"), "Must contain OccupationList lookup table");
        assertTrue(tableNames.contains("relationshiplist"), "Must contain RelationshipList lookup table");
        assertTrue(tableNames.contains("sanctionsreferenceslist"), "Must contain SanctionsReferencesList lookup table");
        assertTrue(tableNames.contains("description1list"), "Must contain Description1List lookup table");
        assertTrue(tableNames.contains("description2list"), "Must contain Description2List lookup table");
        assertTrue(tableNames.contains("description3list"), "Must contain Description3List lookup table");
        assertTrue(tableNames.contains("datetypelist"), "Must contain DateTypeList lookup table");
        assertTrue(tableNames.contains("nametypelist"), "Must contain NameTypeList lookup table");
        assertTrue(tableNames.contains("roletypelist"), "Must contain RoleTypeList lookup table");

        // Verify direct SQL query against provisioned tables succeeds
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM staging_xml_records")) {
                assertTrue(rs.next());
            }
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM Person")) {
                assertTrue(rs.next());
            }
            String flatPersonTable = tableNames.contains("personflat") ? "PersonFlat" : "person_flat";
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + flatPersonTable)) {
                assertTrue(rs.next());
            }
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM Description1List")) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    @DisplayName("Verify Generic XML schema discovery dynamically provisions flat table and canonical master table")
    void testGenericXmlSchemaDiscoveryProvisionsFlatAndCanonicalTables() throws Exception {
        com.enterprise.xmldbtool.model.DbConnectionConfig dbConfig = new com.enterprise.xmldbtool.model.DbConnectionConfig();
        dbConfig.setEngine("H2");
        dbConfig.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dbConfig.setUsername("sa");
        dbConfig.setPassword("");

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest request = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        request.setDbConfig(dbConfig);
        request.setSourceXmlPath("sample-data/large-sample.xml");
        request.setRecordRootElement("record");
        request.setAutoCreateTables(true);

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse response = discoveryService.discoverSchema(request);
        assertTrue(response.isSuccess(), "Schema discovery should succeed");
        assertNotNull(response.getDiscoveredTables(), "Discovered tables should not be null");

        List<String> tableNames = response.getDiscoveredTables().stream()
                .map(t -> t.getTableName().toLowerCase())
                .toList();

        // Must contain flattened table and canonical master table
        assertTrue(tableNames.contains("record_flat"), "Must contain generic flattened table 'record_flat'");
        assertTrue(tableNames.contains("record"), "Must contain generic canonical master table 'record'");
        assertTrue(tableNames.contains("staging_xml_records"), "Must contain flat staging table");
    }

    @Test
    @DisplayName("Verify Schema Registry pre-seeded versions and CRUD retrieval")
    void testSchemaRegistryPreSeededVersionsAndRetrieval() {
        assertNotNull(schemaRegistryService, "SchemaRegistryService must be injected");
        List<com.enterprise.xmldbtool.model.SchemaRegistryVersion> versions = schemaRegistryService.getAllVersions();
        assertNotNull(versions, "Registry versions should not be null");
        assertTrue(versions.size() >= 2, "Should have at least 2 seeded versions (PFA Sanctions and Product Catalog)");

        boolean hasPfa = versions.stream().anyMatch(v -> "PFA-WorldCheck-Sanctions".equals(v.getSchemaName()));
        boolean hasCatalog = versions.stream().anyMatch(v -> "Enterprise-Catalog-Products".equals(v.getSchemaName()));
        assertTrue(hasPfa, "Should contain PFA-WorldCheck-Sanctions schema");
        assertTrue(hasCatalog, "Should contain Enterprise-Catalog-Products schema");

        com.enterprise.xmldbtool.model.SchemaRegistryVersion pfaVer = versions.stream()
                .filter(v -> "PFA-WorldCheck-Sanctions".equals(v.getSchemaName()))
                .findFirst()
                .orElse(null);
        assertNotNull(pfaVer);
        assertEquals("Person", pfaVer.getRootElement());
        assertNotNull(pfaVer.getMappings());
        assertFalse(pfaVer.getMappings().isEmpty(), "PFA mappings must not be empty");

        // Verify primary key constraint is configured on person_id
        boolean hasPersonPk = pfaVer.getMappings().stream().anyMatch(m ->
                "Person".equalsIgnoreCase(m.getTargetTable()) &&
                "person_id".equalsIgnoreCase(m.getTargetColumn()) &&
                "PK".equalsIgnoreCase(m.getConstraintType())
        );
        assertTrue(hasPersonPk, "person_id on Person must have PK constraint in registry");
    }

    @Test
    @DisplayName("Verify Schema Registry version registration with PK and FK constraints")
    void testSchemaRegistrySaveAndLoadVersion() {
        com.enterprise.xmldbtool.model.SchemaRegistryVersion newVer = new com.enterprise.xmldbtool.model.SchemaRegistryVersion();
        newVer.setSchemaName("Customer-Ledger-Schema");
        newVer.setVersion("1.0.0");
        newVer.setEntityType("Customer");
        newVer.setRootElement("Customer");
        newVer.setDescription("Customer and Accounts relational schema with PK/FK constraints");
        newVer.setTableOrder(List.of("Customer", "Account"));

        List<FieldMapping> mappings = List.of(
                new FieldMapping("Customer/@id", "Customer", "customer_id", "NONE", "PK"),
                new FieldMapping("Customer/Name", "Customer", "customer_name", "NONE", "NONE"),
                new FieldMapping("Customer/@id", "Account", "customer_id", "NONE", "FK"),
                new FieldMapping("Customer/Account/AccountNumber", "Account", "account_number", "NONE", "PK")
        );
        newVer.setMappings(mappings);

        com.enterprise.xmldbtool.model.SchemaRegistryVersion saved = schemaRegistryService.saveVersion(newVer);
        assertNotNull(saved.getId(), "Saved version must have generated ID");
        assertEquals("Customer-Ledger-Schema", saved.getSchemaName());
        assertEquals("1.0.0", saved.getVersion());

        com.enterprise.xmldbtool.model.SchemaRegistryVersion retrieved = schemaRegistryService.getVersionById(saved.getId());
        assertNotNull(retrieved);
        assertEquals("Customer", retrieved.getRootElement());
        assertEquals(4, retrieved.getMappings().size());

        // Verify PK and FK constraints retained through JSON serialization
        FieldMapping custPk = retrieved.getMappings().stream()
                .filter(m -> "Customer".equals(m.getTargetTable()) && "customer_id".equals(m.getTargetColumn()))
                .findFirst().orElse(null);
        assertNotNull(custPk);
        assertEquals("PK", custPk.getConstraintType());

        FieldMapping acctFk = retrieved.getMappings().stream()
                .filter(m -> "Account".equals(m.getTargetTable()) && "customer_id".equals(m.getTargetColumn()))
                .findFirst().orElse(null);
        assertNotNull(acctFk);
        assertEquals("FK", acctFk.getConstraintType());
    }

    @Test
    @DisplayName("Verify DynamicXmlItemProcessor automatically resolves parent FK for 1-to-N child records")
    void testDynamicXmlItemProcessorFkResolution() throws Exception {
        Map<String, Object> xmlRecord = new LinkedHashMap<>();
        xmlRecord.put("id", "88219");
        xmlRecord.put("action", "add");
        xmlRecord.put("gender", "Female");

        // Child entities under _entities_name
        List<Map<String, Object>> childNames = List.of(
                Map.of("firstname", "Maria", "surname", "Santos")
        );
        xmlRecord.put("_entities_name", childNames);

        List<FieldMapping> mappings = List.of(
                new FieldMapping("Person/@id", "Person", "person_id", "NONE", "PK"),
                new FieldMapping("Person/Gender", "Person", "gender", "NONE", "NONE"),
                new FieldMapping("Person/NameDetails/Name/NameValue/FirstName", "PersonName", "first_name", "NONE", "NONE"),
                new FieldMapping("Person/NameDetails/Name/NameValue/Surname", "PersonName", "surname", "NONE", "NONE")
        );

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor(
                "Person",
                mappings,
                Map.of(),
                List.of("Person", "PersonName")
        );

        DatabaseRowPayload payload = processor.process(xmlRecord);
        assertNotNull(payload);
        assertTrue(payload.getRowsByTable().containsKey("Person"));
        assertTrue(payload.getRowsByTable().containsKey("PersonName"));

        List<Map<String, Object>> childRows = payload.getRowsByTable().get("PersonName");
        assertEquals(1, childRows.size());
        Map<String, Object> childRow = childRows.get(0);
        assertEquals("Maria", childRow.get("first_name"));
        assertEquals("Santos", childRow.get("surname"));
        // Foreign key person_id must be populated from master entity
        assertEquals("88219", String.valueOf(childRow.get("person_id")), "Child record must have parent foreign key injected");
    }

    @Test
    @DisplayName("Verify DynamicJdbcBatchWriter supports explicit primary key configuration")
    void testDynamicJdbcBatchWriterExplicitPrimaryKeys() {
        DynamicJdbcBatchWriter writer = new DynamicJdbcBatchWriter(dataSource);
        writer.setExplicitPrimaryKeys(Map.of("custom_table", Set.of("custom_key_id")));

        Set<String> pks = writer.findPrimaryKeys("custom_table", Set.of("custom_key_id", "data_value"));
        assertNotNull(pks);
        assertTrue(pks.contains("custom_key_id"), "Primary key set explicitly must be returned");
    }

    @Test
    @DisplayName("Verify Create Schema functionality executes CREATE SCHEMA DDL in target DB")
    void testCreateTargetDatabaseSchema() {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("POSTGRESQL");
        config.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("demo_compliance_schema");

        DbTestResponse response = dataSourceService.createSchema(config);
        assertNotNull(response);
        assertTrue(response.isSuccess(), "Schema creation should succeed: " + response.getMessage());

        // Verify that the schema actually exists in the database
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        List<String> schemas = jdbc.queryForList(
                "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE UPPER(SCHEMA_NAME) = 'DEMO_COMPLIANCE_SCHEMA'",
                String.class
        );
        assertEquals(1, schemas.size(), "Target schema 'demo_compliance_schema' must exist in database");
    }

    @Test
    @DisplayName("Verify delta XML schema evolution automatically issues ALTER TABLE ADD COLUMN during batch writing")
    void testDeltaXmlFileSchemaEvolutionWithAlterTable() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);

        // Step 1: Simulate table created from file1.xml (3 columns)
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_delta_person (person_id INT PRIMARY KEY, first_name VARCHAR(100), active_status VARCHAR(50))");
        jdbc.execute("DELETE FROM test_delta_person");
        jdbc.update("INSERT INTO test_delta_person (person_id, first_name, active_status) VALUES (5001, 'Original Name', 'ACTIVE')");

        // Step 2: Simulate file2.xml (delta file) introducing 5 brand new tags/columns:
        // risk_score, tax_id, mother_maiden_name, verification_date, pep_category
        DynamicJdbcBatchWriter writer = new DynamicJdbcBatchWriter(dataSource, List.of("test_delta_person"));
        writer.setExplicitPrimaryKeys(Map.of("test_delta_person", Set.of("person_id")));

        // Record 1: Updates existing person 5001 with delta columns
        Map<String, Object> deltaRow1 = new LinkedHashMap<>();
        deltaRow1.put("person_id", 5001);
        deltaRow1.put("first_name", "Original Name Updated");
        deltaRow1.put("active_status", "ACTIVE");
        deltaRow1.put("risk_score", "98.5");
        deltaRow1.put("tax_id", "TX-8899");
        deltaRow1.put("mother_maiden_name", "Smith");
        deltaRow1.put("verification_date", "2026-11-05");
        deltaRow1.put("pep_category", "PEP-Tier-1");

        // Record 2: Inserts new person 5002 with all delta columns
        Map<String, Object> deltaRow2 = new LinkedHashMap<>();
        deltaRow2.put("person_id", 5002);
        deltaRow2.put("first_name", "New Delta Person");
        deltaRow2.put("active_status", "ACTIVE");
        deltaRow2.put("risk_score", "72.0");
        deltaRow2.put("tax_id", "TX-9900");
        deltaRow2.put("mother_maiden_name", "Johnson");
        deltaRow2.put("verification_date", "2026-11-05");
        deltaRow2.put("pep_category", "PEP-Tier-2");

        DatabaseRowPayload payload1 = new DatabaseRowPayload("test_delta_person", deltaRow1);
        DatabaseRowPayload payload2 = new DatabaseRowPayload("test_delta_person", deltaRow2);

        // Batch execution must automatically detect the 5 missing columns and execute ALTER TABLE statements
        writer.write(new org.springframework.batch.item.Chunk<>(List.of(payload1, payload2)));

        // Step 3: Verify that records were upserted and the new columns contain the values from delta file
        Map<String, Object> row5001 = jdbc.queryForMap(
                "SELECT person_id, first_name, risk_score, tax_id, mother_maiden_name, pep_category FROM test_delta_person WHERE person_id = 5001"
        );
        assertEquals("Original Name Updated", row5001.get("first_name"));
        assertEquals("98.5", row5001.get("risk_score"));
        assertEquals("TX-8899", row5001.get("tax_id"));
        assertEquals("Smith", row5001.get("mother_maiden_name"));
        assertEquals("PEP-Tier-1", row5001.get("pep_category"));

        Map<String, Object> row5002 = jdbc.queryForMap(
                "SELECT person_id, first_name, risk_score, tax_id, mother_maiden_name, pep_category FROM test_delta_person WHERE person_id = 5002"
        );
        assertEquals("New Delta Person", row5002.get("first_name"));
        assertEquals("72.0", row5002.get("risk_score"));
        assertEquals("TX-9900", row5002.get("tax_id"));
        assertEquals("Johnson", row5002.get("mother_maiden_name"));
        assertEquals("PEP-Tier-2", row5002.get("pep_category"));
    }

    @Test
    @DisplayName("Verify SchemaProvisioningService evolves existing table schema with ALTER TABLE for delta XML tags")
    void testSchemaProvisioningDeltaEvolution() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_evolve_pfa (id INT PRIMARY KEY, name VARCHAR(100))");

        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {

            Set<String> newDeltaColumns = Set.of("id", "name", "delta_col_1", "delta_col_2", "profile_notes");
            List<String> added = provisioningService.evolveExistingTableSchema(stmt, "test_evolve_pfa", newDeltaColumns, false);

            assertTrue(added.contains("delta_col_1"), "delta_col_1 should have been added via ALTER TABLE");
            assertTrue(added.contains("delta_col_2"), "delta_col_2 should have been added via ALTER TABLE");
            assertTrue(added.contains("profile_notes"), "profile_notes should have been added via ALTER TABLE");
        }

        // Verify column can be selected
        jdbc.execute("INSERT INTO test_evolve_pfa (id, name, delta_col_1, delta_col_2, profile_notes) VALUES (1, 'Test', 'V1', 'V2', 'Notes')");
        Map<String, Object> row = jdbc.queryForMap("SELECT delta_col_1, delta_col_2, profile_notes FROM test_evolve_pfa WHERE id = 1");
        assertEquals("V1", row.get("delta_col_1"));
        assertEquals("V2", row.get("delta_col_2"));
        assertEquals("Notes", row.get("profile_notes"));
    }

    @Test
    @DisplayName("Verify candidate root elements discovery and XML feed version detection from root attributes")
    void testCandidateRootElementsDiscoveryAndFeedVersionDetection() {
        // 1. Detect candidate root elements from sample PFA feed
        List<String> candidates = discoveryService.detectCandidateRootElements("sample-data/pfa-sample.xml");
        assertNotNull(candidates);
        assertTrue(candidates.size() >= 3, "Should discover candidate repeating root elements from PFA");
        assertTrue(candidates.stream().anyMatch(c -> c.equalsIgnoreCase("Person")), "Candidates must include Person");
        assertTrue(candidates.stream().anyMatch(c -> c.equalsIgnoreCase("CountryName")), "Candidates must include CountryName");
        assertTrue(candidates.stream().anyMatch(c -> c.equalsIgnoreCase("Occupation")), "Candidates must include Occupation");

        // 2. Detect XML feed version from document root attribute date="202206192200"
        String detectedVersion = discoveryService.detectXmlFeedVersion("sample-data/pfa-sample.xml");
        assertNotNull(detectedVersion, "Detected XML version should not be null");
        assertEquals("202206192200", detectedVersion, "XML feed version must match date attribute in PFA root");
    }

    @Test
    @DisplayName("Verify SchemaProvisioningService creates multiple flattened tables for multiple root tags")
    void testMultiRootFlattenedTablesProvisioning() {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        List<String> rootTags = List.of("Person", "CountryName", "Occupation", "Relationship");
        List<String> createdTables = provisioningService.provisionDiscoveredTables(config, "sample-data/pfa-sample.xml", rootTags, Collections.emptyList());

        assertNotNull(createdTables);
        assertTrue(createdTables.contains("staging_xml_records"), "Must ensure central staging table exists");
        assertTrue(createdTables.stream().anyMatch(t -> t.equalsIgnoreCase("person_flat") || t.equalsIgnoreCase("PersonFlat")),
                "Must create flattened table for Person");
        assertTrue(createdTables.stream().anyMatch(t -> t.equalsIgnoreCase("countryname_flat")),
                "Must create flattened table for CountryName");
        assertTrue(createdTables.stream().anyMatch(t -> t.equalsIgnoreCase("occupation_flat")),
                "Must create flattened table for Occupation");
        assertTrue(createdTables.stream().anyMatch(t -> t.equalsIgnoreCase("relationship_flat")),
                "Must create flattened table for Relationship");

        // Verify tables exist in target DB
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM countryname_flat", Integer.class);
        assertNotNull(count, "countryname_flat table must exist and be queryable");
    }

    @Test
    @DisplayName("Verify StAX streaming ingestion stages multi-root tags with XML & schema versioning into dedicated flat tables")
    void testMultiRootStAXStreamingWithVersioningAndFlatTablePopulating() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);

        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        List<String> roots = List.of("Person", "CountryName", "Occupation");
        provisioningService.provisionDiscoveredTables(config, "sample-data/pfa-sample.xml", roots, Collections.emptyList());

        // Clean staging and flat tables for isolated test verification
        jdbc.execute("DELETE FROM staging_xml_records");
        for (String tbl : List.of("gs_person", "person_gs", "person_flat", "gs_countryname", "countryname_gs", "countryname_flat", "gs_occupation", "occupation_gs", "occupation_flat")) {
            try { jdbc.execute("DELETE FROM " + tbl); } catch (Exception ignored) {}
        }

        // Stream XML with XML version and Schema version
        String xmlVersion = "202206192200";
        String schemaVersion = "v1.2.0";

        com.enterprise.xmldbtool.service.StagingXmlStreamingService.StagingSummary summary = stagingStreamingService.streamXmlToStaging(
                "sample-data/pfa-sample.xml",
                roots,
                xmlVersion,
                schemaVersion,
                99901L,
                dataSource,
                500
        );

        assertNotNull(summary);
        assertTrue(summary.totalRead() > 0, "Should have streamed fragments for specified roots");
        assertTrue(summary.stagedCount() > 0, "Should have staged records");

        // Verify staging_xml_records has versioning columns populated
        List<Map<String, Object>> stagedRows = jdbc.queryForList(
                "SELECT entity_type, xml_version, schema_version, status FROM staging_xml_records WHERE job_execution_id = 99901"
        );
        assertFalse(stagedRows.isEmpty());
        for (Map<String, Object> r : stagedRows) {
            assertEquals(xmlVersion, r.get("xml_version"), "xml_version must be populated in staging");
            assertEquals(schemaVersion, r.get("schema_version"), "schema_version must be populated in staging");
        }

        // Verify that countryname_flat received staged records
        List<Map<String, Object>> countryFlatRows = jdbc.queryForList("SELECT * FROM countryname_flat WHERE job_execution_id = 99901");
        assertFalse(countryFlatRows.isEmpty(), "countryname_flat must receive rows streamed for CountryName");
        Map<String, Object> firstCountry = countryFlatRows.get(0);
        assertEquals(xmlVersion, firstCountry.get("xml_version"));
        assertEquals(schemaVersion, firstCountry.get("schema_version"));

        // Verify that person_flat received staged records
        List<Map<String, Object>> personFlatRows = jdbc.queryForList("SELECT * FROM person_flat WHERE job_execution_id = 99901");
        assertFalse(personFlatRows.isEmpty(), "person_flat must receive rows streamed for Person");
        Map<String, Object> firstPerson = personFlatRows.get(0);
        assertEquals(xmlVersion, firstPerson.get("xml_version"));
        assertEquals(schemaVersion, firstPerson.get("schema_version"));
    }

    @Test
    @DisplayName("Verify Stage 2 Canonical Resolution projects staged data into canonical tables via mapping JSON and delta upsert")
    void testDeltaProcessingWithMappingJsonAndStage2CanonicalNormalization() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);

        // Provision canonical and flat tables
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        provisioningService.provisionDiscoveredTables(config, "sample-data/pfa-sample.xml", List.of("Person"), Collections.emptyList());

        // Clean tables for isolated delta test
        jdbc.execute("DELETE FROM staging_xml_records");
        for (String tbl : List.of("gs_person", "person_gs", "person_flat", "Person", "PersonName")) {
            try { jdbc.execute("DELETE FROM " + tbl); } catch (Exception ignored) {}
        }

        // Stream initial feed
        stagingStreamingService.streamXmlToStaging(
                "sample-data/pfa-sample.xml",
                List.of("Person"),
                "v1.0",
                "v1.0.0",
                99902L,
                dataSource,
                500
        );

        // Mappings representing the mapping JSON
        List<FieldMapping> mappings = List.of(
                new FieldMapping("Person/@id", "Person", "person_id", "PK", "NONE"),
                new FieldMapping("Person/@action", "Person", "action", "NONE", "NONE"),
                new FieldMapping("Person/@date", "Person", "record_date", "NONE", "NONE"),
                new FieldMapping("Person/Gender", "Person", "gender", "NONE", "NONE"),
                new FieldMapping("Person/ActiveStatus", "Person", "active_status", "NONE", "NONE"),
                new FieldMapping("Person/NameDetails/Name/NamePart", "PersonName", "first_name", "NONE", "NONE")
        );

        // Run Stage 2 Canonical Normalization
        com.enterprise.xmldbtool.service.DynamicSchemaResolutionService.NormalizationSummary normSummary = schemaResolutionService.normalizeStagingToCanonical(
                99902L,
                dataSource,
                mappings,
                List.of("Person", "PersonName"),
                "Person",
                500
        );

        assertNotNull(normSummary);
        assertTrue(normSummary.normalizedCount() > 0, "Should have normalized staged records into canonical tables");

        // Verify Person canonical table has records populated
        Integer personCount = jdbc.queryForObject("SELECT COUNT(*) FROM Person", Integer.class);
        assertNotNull(personCount);
        assertTrue(personCount > 0, "Person table must contain normalized canonical records");

        // Verify records in staging_xml_records and person_flat are marked NORMALIZED
        Integer normalizedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM staging_xml_records WHERE job_execution_id = 99902 AND status = 'NORMALIZED'",
                Integer.class
        );
        assertEquals(personCount, normalizedCount, "All staged records must be marked NORMALIZED");

        // Now test Delta feed with unchanged records: re-running identical payload should SKIP
        com.enterprise.xmldbtool.service.StagingXmlStreamingService.StagingSummary deltaSummary = stagingStreamingService.streamXmlToStaging(
                "sample-data/pfa-sample.xml",
                List.of("Person"),
                "v1.1",
                "v1.0.0",
                99903L,
                dataSource,
                500
        );
        assertTrue(deltaSummary.skippedUnchangedCount() > 0, "Unchanged records should be skipped via delta hash fingerprint");
    }

    @Test
    @DisplayName("Verify _flat table creation, root tags/attributes in columns, sub-tags in raw XML format, and no unwanted _stage tables")
    void testStagingTableSuffixAndSubTagsInJsonFormat() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);

        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest request = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        request.setDbConfig(config);
        request.setSourceXmlPath("sample-data/test.xml");
        request.setRecordRootElement("person");
        request.setAutoCreateTables(true);

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse response = discoveryService.discoverSchema(request);
        assertTrue(response.isSuccess());

        // 1. Verify strictly person_flat is created, and NO unwanted _stage tables are created
        assertTrue(response.getCreatedTables().stream().anyMatch(t -> t.equalsIgnoreCase("person_flat")),
                "Must create dedicated flattened table 'person_flat' with _flat suffix");
        assertFalse(response.getCreatedTables().stream().anyMatch(t -> t.equalsIgnoreCase("person_stage")),
                "Must NOT create unwanted _stage tables like 'person_stage'");

        // 2. Verify _stage tables are NOT created or shown in discoveredTables
        assertFalse(response.getDiscoveredTables().stream().anyMatch(t -> t.getTableName().toLowerCase().endsWith("_stage")),
                "_stage tables must NOT be created or shown in discoveredTables");

        // 3. Verify person_flat table columns exist in DB
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'PERSON_FLAT'"
        );
        assertFalse(cols.isEmpty(), "person_flat columns must exist in database");
        Set<String> colNames = cols.stream().map(c -> String.valueOf(c.get("COLUMN_NAME")).toLowerCase()).collect(java.util.stream.Collectors.toSet());

        // Root attributes and leaf tags stored into columns
        assertTrue(colNames.contains("id"), "person_flat must contain 'id' column");
        assertTrue(colNames.contains("bnpp_id"), "person_flat must contain 'bnpp_id' column");
        assertTrue(colNames.contains("tag_name"), "person_flat must contain 'tag_name' column");
        assertTrue(colNames.contains("version_number"), "person_flat must contain 'version_number' column");
        assertTrue(colNames.contains("status"), "person_flat must contain 'status' column");
        assertTrue(colNames.contains("name"), "person_flat must contain 'name' column");
        assertTrue(colNames.contains("age"), "person_flat must contain 'age' column");

        // Sub-tag 'addresses' column exists to hold nested structures in raw XML format
        assertTrue(colNames.contains("addresses"), "person_flat must contain sub-tag 'addresses' column");

        // 4. Stream XML fragments and verify sub-tag is populated in raw XML format
        stagingStreamingService.streamXmlToStaging(
                "sample-data/test.xml",
                List.of("person"),
                "v1.0",
                "v1.0.0",
                88801L,
                dataSource,
                500
        );

        List<Map<String, Object>> flatRows = jdbc.queryForList("SELECT * FROM person_flat WHERE job_execution_id = 88801 ORDER BY id ASC");
        assertFalse(flatRows.isEmpty(), "person_flat must have records streamed into it");
        Map<String, Object> firstRow = flatRows.get(0);

        assertEquals("James Smith", firstRow.get("name"), "Leaf tag 'name' stored into column");
        assertEquals("active", firstRow.get("status"), "Attribute 'status' stored into column");
        assertEquals("37", firstRow.get("age"), "Leaf tag 'age' stored into column");

        // Verify sub-tag 'addresses' contains valid raw XML format
        Object addressesVal = firstRow.get("addresses");
        assertNotNull(addressesVal, "Sub-tag 'addresses' column must not be null");
        String addressesXml = String.valueOf(addressesVal).trim();
        assertTrue(addressesXml.startsWith("<addresses>") && addressesXml.endsWith("</addresses>"),
                "Sub-tag details must be stored in raw XML format, actual: " + addressesXml);
        assertTrue(addressesXml.contains("<street>123 Main St</street>"), "Sub-tag raw XML must contain street tag");
        assertTrue(addressesXml.contains("<city>New York</city>"), "Sub-tag raw XML must contain city tag");
    }

    @Test
    @DisplayName("Verify table config and mapping details generated from XML file with PK/FK relationships when no ingest JSON is provided")
    void testCanonicalTablesAndRelationshipsGenerationFromXmlFile() {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest request = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        request.setDbConfig(config);
        request.setSourceXmlPath("sample-data/test.xml");
        request.setRecordRootElement("person, country, countrylist, entity");
        request.setAutoCreateTables(true);

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse response = discoveryService.discoverSchema(request);
        assertTrue(response.isSuccess());

        // 1. Verify canonical tables exist in discoveredTables
        List<String> discoveredTableNames = response.getDiscoveredTables().stream()
                .map(t -> t.getTableName().toLowerCase())
                .toList();
        assertTrue(discoveredTableNames.contains("person"), "Discovered tables must include canonical 'person'");
        assertTrue(discoveredTableNames.contains("address") || discoveredTableNames.contains("person_address"), "Discovered tables must include child 'address'");
        assertTrue(discoveredTableNames.contains("country"), "Discovered tables must include canonical 'country'");

        // 2. Verify mapping details are generated with PK and FK constraints
        List<com.enterprise.xmldbtool.model.FieldMapping> mappings = response.getGeneratedMappings();
        assertNotNull(mappings);
        assertFalse(mappings.isEmpty(), "Generated mappings must not be empty");

        assertTrue(mappings.stream().anyMatch(m -> "PK".equalsIgnoreCase(m.getConstraintType())),
                "Generated mappings must contain Primary Key (PK) constraints");
        assertTrue(mappings.stream().anyMatch(m -> "FK".equalsIgnoreCase(m.getConstraintType())),
                "Generated mappings must contain Foreign Key (FK) constraints");

        // 3. Verify relationships are discovered
        List<com.enterprise.xmldbtool.model.TableRelationship> relationships = response.getDiscoveredRelationships();
        assertNotNull(relationships);
        assertFalse(relationships.isEmpty(), "Relationships must be discovered from XML hierarchy");
        assertTrue(relationships.stream().anyMatch(r -> r.getChildColumn().toLowerCase().endsWith("_id")),
                "Discovered relationships must link foreign key columns ending in _id");
    }

    @Test
    @DisplayName("Verify table config and mapping details created based on User Ingest JSON file")
    void testCanonicalTablesAndMappingsFromUserIngestJson() {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        List<com.enterprise.xmldbtool.model.FieldMapping> userMappings = List.of(
                new com.enterprise.xmldbtool.model.FieldMapping("Person/@id", "PersonCustom", "person_id", "NONE", "PK"),
                new com.enterprise.xmldbtool.model.FieldMapping("Person/Gender", "PersonCustom", "gender", "NONE", "NONE"),
                new com.enterprise.xmldbtool.model.FieldMapping("Person/NameDetails/Name/NameValue/FirstName", "PersonNameCustom", "first_name", "NONE", "NONE"),
                new com.enterprise.xmldbtool.model.FieldMapping("Person/@id", "PersonNameCustom", "person_id", "NONE", "FK")
        );

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest request = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        request.setDbConfig(config);
        request.setSourceXmlPath("sample-data/pfa-sample.xml");
        request.setRecordRootElement("Person");
        request.setMappings(userMappings);
        request.setAutoCreateTables(true);

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse response = discoveryService.discoverSchema(request);
        assertTrue(response.isSuccess());

        // 1. Verify tables provisioned directly from Ingest JSON
        List<String> discoveredTableNames = response.getDiscoveredTables().stream()
                .map(t -> t.getTableName().toLowerCase())
                .toList();
        assertTrue(discoveredTableNames.contains("personcustom"), "Must create PersonCustom table from user Ingest JSON");
        assertTrue(discoveredTableNames.contains("personnamecustom"), "Must create PersonNameCustom table from user Ingest JSON");

        // 2. Verify returned mappings match user Ingest JSON
        assertEquals(userMappings.size(), response.getGeneratedMappings().size());
        assertEquals("PersonCustom", response.getGeneratedMappings().get(0).getTargetTable());
        assertEquals("PK", response.getGeneratedMappings().get(0).getConstraintType());

        // 3. Verify relationships reflect User Ingest JSON PK-FK linking
        List<com.enterprise.xmldbtool.model.TableRelationship> rels = response.getDiscoveredRelationships();
        assertNotNull(rels);
        assertTrue(rels.stream().anyMatch(r -> r.getChildTable().equalsIgnoreCase("PersonNameCustom") && r.getChildColumn().equalsIgnoreCase("person_id")),
                "Must derive relationship from user Ingest JSON FK constraint");
    }

    @Test
    @DisplayName("Verify mapping creation using ingestion.json file with multi-table PK/FK constraints")
    void testMappingUsingIngestionJsonFile() {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest request = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        request.setDbConfig(config);
        request.setSourceXmlPath("sample-data/test.xml");
        request.setIngestionJsonPath("sample-data/ingestion.json");
        request.setAutoCreateTables(true);

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse response = discoveryService.discoverSchema(request);
        assertTrue(response.isSuccess());

        List<com.enterprise.xmldbtool.model.FieldMapping> mappings = response.getGeneratedMappings();
        assertNotNull(mappings);
        assertEquals(16, mappings.size(), "Must load all 16 mappings defined in sample-data/ingestion.json");

        // Verify target tables are identified and provisioned
        List<String> discoveredTableNames = response.getDiscoveredTables().stream()
                .map(t -> t.getTableName().toLowerCase())
                .toList();
        assertTrue(discoveredTableNames.contains("person"), "Must provision Person table from ingestion.json");
        assertTrue(discoveredTableNames.contains("address"), "Must provision Address child table from ingestion.json");
        assertTrue(discoveredTableNames.contains("country"), "Must provision Country table from ingestion.json");
        assertTrue(discoveredTableNames.contains("countrylist"), "Must provision CountryList table from ingestion.json");

        // Verify PK and FK constraints from ingestion.json
        assertTrue(mappings.stream().anyMatch(m -> m.getTargetTable().equalsIgnoreCase("Person") && m.getTargetColumn().equalsIgnoreCase("person_id") && "PK".equalsIgnoreCase(m.getConstraintType())));
        assertTrue(mappings.stream().anyMatch(m -> m.getTargetTable().equalsIgnoreCase("Address") && m.getTargetColumn().equalsIgnoreCase("id") && "PK".equalsIgnoreCase(m.getConstraintType())));
        assertTrue(mappings.stream().anyMatch(m -> m.getTargetTable().equalsIgnoreCase("Address") && m.getTargetColumn().equalsIgnoreCase("person_id") && "FK".equalsIgnoreCase(m.getConstraintType())));
        assertTrue(mappings.stream().anyMatch(m -> m.getTargetTable().equalsIgnoreCase("Address") && m.getTargetColumn().equalsIgnoreCase("country_id") && "FK".equalsIgnoreCase(m.getConstraintType())));

        // Verify child-parent relationships derived from ingestion.json
        List<com.enterprise.xmldbtool.model.TableRelationship> relationships = response.getDiscoveredRelationships();
        assertNotNull(relationships);
        assertTrue(relationships.stream().anyMatch(r -> r.getChildTable().equalsIgnoreCase("Address") && r.getParentTable().equalsIgnoreCase("Person")),
                "Must derive Address -> Person relationship");
        assertTrue(relationships.stream().anyMatch(r -> r.getChildTable().equalsIgnoreCase("Address") && r.getParentTable().equalsIgnoreCase("Country")),
                "Must derive Address -> Country cross-reference relationship");
    }

    @Test
    @DisplayName("Verify dynamic mapping generation from XML tags & attributes with table identification and child-parent relationships")
    void testDynamicMappingGenerationFromXmlTagsAndAttributesWithRelationships() {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest request = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        request.setDbConfig(config);
        request.setSourceXmlPath("sample-data/test.xml");
        request.setRecordRootElements(List.of("person", "country", "entity", "countrylist"));
        request.setAutoCreateTables(true);
        // Explicitly leave mappings and ingestionJsonPath empty to test dynamic XML tag scanning

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse response = discoveryService.discoverSchema(request);
        assertTrue(response.isSuccess());

        List<com.enterprise.xmldbtool.model.FieldMapping> mappings = response.getGeneratedMappings();
        assertNotNull(mappings);
        assertFalse(mappings.isEmpty(), "Dynamically generated mappings must not be empty");

        // 1. Table names identified from XML tags
        Set<String> mappedTables = mappings.stream().map(m -> m.getTargetTable().toLowerCase()).collect(java.util.stream.Collectors.toSet());
        assertTrue(mappedTables.contains("person"), "Table 'person' must be identified from XML");
        assertTrue(mappedTables.contains("address") || mappedTables.contains("person_address"), "Child table 'address' must be identified from XML");
        assertTrue(mappedTables.contains("country"), "Table 'country' must be identified from XML");
        assertTrue(mappedTables.contains("entity"), "Table 'entity' must be identified from XML");

        // 2. All XML attributes included in mappings
        assertTrue(mappings.stream().anyMatch(m -> m.getXmlField().toLowerCase().contains("@id")),
                "Must include @id attributes across entities");
        assertTrue(mappings.stream().anyMatch(m -> m.getXmlField().toLowerCase().contains("@status")),
                "Must include @status attributes (e.g. person/@status, entity/@status)");
        assertTrue(mappings.stream().anyMatch(m -> m.getXmlField().toLowerCase().contains("@action")),
                "Must include @action attributes (e.g. country/@action)");

        // 3. All XML leaf tags included in mappings
        assertTrue(mappings.stream().anyMatch(m -> m.getTargetColumn().equalsIgnoreCase("name")), "Must map 'name' tag");
        assertTrue(mappings.stream().anyMatch(m -> m.getTargetColumn().equalsIgnoreCase("age")), "Must map 'age' tag");
        assertTrue(mappings.stream().anyMatch(m -> m.getTargetColumn().equalsIgnoreCase("street")), "Must map 'street' tag");
        assertTrue(mappings.stream().anyMatch(m -> m.getTargetColumn().equalsIgnoreCase("city")), "Must map 'city' tag");
        assertTrue(mappings.stream().anyMatch(m -> m.getTargetColumn().equalsIgnoreCase("cost")), "Must map 'cost' tag");

        // 4. PK and FK constraints and relationships
        assertTrue(mappings.stream().anyMatch(m -> "PK".equalsIgnoreCase(m.getConstraintType())), "Must generate PK constraints");
        assertTrue(mappings.stream().anyMatch(m -> "FK".equalsIgnoreCase(m.getConstraintType())), "Must generate FK constraints");

        List<com.enterprise.xmldbtool.model.TableRelationship> rels = response.getDiscoveredRelationships();
        assertNotNull(rels);
        assertFalse(rels.isEmpty(), "Must discover child-parent relationships from XML hierarchy");

        // Address child links to Person parent
        assertTrue(rels.stream().anyMatch(r -> (r.getChildTable().equalsIgnoreCase("address") || r.getChildTable().equalsIgnoreCase("person_address"))
                        && r.getParentTable().equalsIgnoreCase("person")),
                "Must discover Address -> Person child-to-parent relationship");

        // Address cross-reference links to Country
        assertTrue(rels.stream().anyMatch(r -> (r.getChildTable().equalsIgnoreCase("address") || r.getChildTable().equalsIgnoreCase("person_address"))
                        && r.getParentTable().equalsIgnoreCase("country")),
                "Must discover Address -> Country cross-reference relationship");
    }

    @Test
    @DisplayName("Verify Discovery Flow 1: Filter unrelated tags outside root tag hierarchy (UI & Discovery)")
    void testDiscoveryFiltersUnrelatedTagsAndPreservesRootHierarchy() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:test_flow1;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest request = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        request.setDbConfig(config);
        request.setSourceXmlPath("sample-data/test.xml");
        request.setRecordRootElements(List.of("person"));
        request.setAutoCreateTables(true);

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse response = discoveryService.discoverSchema(request);
        assertTrue(response.isSuccess());

        List<com.enterprise.xmldbtool.model.DiscoveredElement> discovered = response.getDiscoveredXmlElements();
        assertNotNull(discovered);
        assertFalse(discovered.isEmpty(), "Must discover elements under root tag hierarchy");

        Set<String> discoveredElementNames = discovered.stream()
                .map(e -> e.getName().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());

        // 1. Root tag and related attributes, child tags, child-of-child, grandchild tags MUST be present
        assertTrue(discoveredElementNames.contains("person/@id") || discoveredElementNames.contains("person/id") || discoveredElementNames.contains("@id"),
                "Must discover person/@id attribute");
        assertTrue(discoveredElementNames.contains("person/@status") || discoveredElementNames.contains("person/status") || discoveredElementNames.contains("@status"),
                "Must discover person/@status attribute");
        assertTrue(discoveredElementNames.contains("person/name") || discoveredElementNames.contains("name"),
                "Must discover person/name tag");
        assertTrue(discoveredElementNames.contains("person/age") || discoveredElementNames.contains("age"),
                "Must discover person/age tag");
        assertTrue(discoveredElementNames.stream().anyMatch(n -> n.contains("addresses") || n.contains("address")),
                "Must discover address hierarchy under person");

        // 2. Unrelated tags outside 'person' hierarchy MUST be filtered out / ignored
        assertFalse(discoveredElementNames.contains("canopyapp"), "Must ignore top-level container canopyapp");
        assertFalse(discoveredElementNames.contains("countrylists"), "Must ignore unrelated countrylists container");
        assertFalse(discoveredElementNames.contains("countrylist"), "Must ignore unrelated countrylist elements");
        assertFalse(discoveredElementNames.contains("countries"), "Must ignore unrelated countries container");
        assertFalse(discoveredElementNames.contains("country"), "Must ignore unrelated country elements");
        assertFalse(discoveredElementNames.contains("entities"), "Must ignore unrelated entities container");
        assertFalse(discoveredElementNames.contains("entity"), "Must ignore unrelated entity elements");
        assertFalse(discoveredElementNames.contains("descriptionlist1s"), "Must ignore unrelated descriptionlist1s");
        assertFalse(discoveredElementNames.contains("descriptionlist1"), "Must ignore unrelated descriptionlist1");

        // 3. _stage tables must be hidden from UI canonical tables
        List<String> canonicalTables = response.getDiscoveredTables().stream()
                .map(t -> t.getTableName().toLowerCase())
                .toList();
        assertFalse(canonicalTables.contains("person_stage"), "person_stage must be hidden from canonical UI tables");
        assertFalse(canonicalTables.stream().anyMatch(t -> t.endsWith("_stage")), "No _stage table should be visible in UI canonical tables");
    }

    @Test
    @DisplayName("Verify Discovery Flow 2: Mapping JSON strict inclusion in UI and DB with unmapped tags ignored")
    void testDiscoveryIncludesMappedTagsAndIgnoresOthersInDbAndUi() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:test_flow2;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest request = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        request.setDbConfig(config);
        request.setSourceXmlPath("sample-data/test.xml");
        request.setIngestionJsonPath("sample-data/ingestion.json");
        request.setAutoCreateTables(true);

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse response = discoveryService.discoverSchema(request);
        assertTrue(response.isSuccess());

        // 1. In UI: discovered XML elements should only include mapped tags, other tags ignored
        List<com.enterprise.xmldbtool.model.DiscoveredElement> discovered = response.getDiscoveredXmlElements();
        assertNotNull(discovered);
        Set<String> discoveredNames = discovered.stream()
                .map(e -> e.getName().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());

        // Mapped elements present
        assertTrue(discoveredNames.stream().anyMatch(n -> n.contains("person")), "Mapped 'person' tags must be discovered");
        assertTrue(discoveredNames.stream().anyMatch(n -> n.contains("address")), "Mapped 'address' tags must be discovered");
        assertTrue(discoveredNames.stream().anyMatch(n -> n.contains("country")), "Mapped 'country' tags must be discovered");
        assertTrue(discoveredNames.stream().anyMatch(n -> n.contains("countrylist")), "Mapped 'countrylist' tags must be discovered");

        // Unmapped elements strictly ignored
        assertFalse(discoveredNames.contains("entities"), "Unmapped 'entities' container must be ignored");
        assertFalse(discoveredNames.contains("entity"), "Unmapped 'entity' elements must be ignored");
        assertFalse(discoveredNames.contains("descriptionlist1s"), "Unmapped 'descriptionlist1s' container must be ignored");
        assertFalse(discoveredNames.contains("descriptionlist1"), "Unmapped 'descriptionlist1' elements must be ignored");
        assertFalse(discoveredNames.contains("canopyapp"), "Unmapped document root 'canopyapp' must be ignored");

        // 2. In UI: canonical tables should only include mapped tables
        List<String> canonicalTables = response.getDiscoveredTables().stream()
                .map(t -> t.getTableName().toLowerCase())
                .toList();
        assertTrue(canonicalTables.contains("person"), "Discovered canonical tables must contain mapped Person table");
        assertTrue(canonicalTables.contains("address"), "Discovered canonical tables must contain mapped Address table");
        assertTrue(canonicalTables.contains("country"), "Discovered canonical tables must contain mapped Country table");
        assertTrue(canonicalTables.contains("countrylist"), "Discovered canonical tables must contain mapped CountryList table");
        assertFalse(canonicalTables.contains("entities"), "Unmapped 'entities' table must NOT be in canonical UI tables");
        assertFalse(canonicalTables.contains("descriptionlist1"), "Unmapped 'descriptionlist1' table must NOT be in canonical UI tables");

        // 3. In Target DB: unmapped tables must NOT be provisioned in the database schema
        List<com.enterprise.xmldbtool.model.TableMetadata> actualDbTables = dataSourceService.retrieveDatabaseSchema(config);
        Set<String> actualDbTableNames = actualDbTables.stream()
                .map(t -> t.getTableName().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(actualDbTableNames.contains("person"), "DB must have provisioned mapped table Person");
        assertTrue(actualDbTableNames.contains("address"), "DB must have provisioned mapped table Address");
        assertTrue(actualDbTableNames.contains("country"), "DB must have provisioned mapped table Country");
        assertTrue(actualDbTableNames.contains("countrylist"), "DB must have provisioned mapped table CountryList");
        assertFalse(actualDbTableNames.contains("entities"), "DB must NOT have provisioned unmapped table entities");
        assertFalse(actualDbTableNames.contains("entity"), "DB must NOT have provisioned unmapped table entity");
        assertFalse(actualDbTableNames.contains("descriptionlist1s"), "DB must NOT have provisioned unmapped table descriptionlist1s");
        assertFalse(actualDbTableNames.contains("descriptionlist1"), "DB must NOT have provisioned unmapped table descriptionlist1");
    }

    @Test
    @DisplayName("Verify end-to-end staged pipeline with bnpp_id, versioning, tag_name, deduplication, and delta update")
    void testStagedIngestionWithPartitionedFlatTablesAndBnppIdAndVersioning() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:bnpp_test_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        javax.sql.DataSource testDs = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(testDs);

        // Define field mappings for Person
        List<FieldMapping> mappings = List.of(
                new FieldMapping("person/@id", "Person", "person_id", "NONE", "PK"),
                new FieldMapping("person/@status", "Person", "status", "UPPER", "NONE"),
                new FieldMapping("person/name", "Person", "name", "NONE", "NONE"),
                new FieldMapping("person/age", "Person", "age", "NONE", "NONE")
        );

        // 1. Initial ingestion with file 1 (Contains Person 9001: Alice, Age 30)
        java.io.File fileV1 = java.io.File.createTempFile("person_v1_", ".xml");
        fileV1.deleteOnExit();
        java.nio.file.Files.writeString(fileV1.toPath(), """
            <dataset date="20261107">
                <persons>
                    <person id="9001" status="active">
                        <name>Alice</name>
                        <age>30</age>
                    </person>
                </persons>
            </dataset>
            """);

        com.enterprise.xmldbtool.model.JobLaunchRequest reqV1 = new com.enterprise.xmldbtool.model.JobLaunchRequest();
        reqV1.setDbConfig(config);
        reqV1.setSourceXmlPath(fileV1.getAbsolutePath());
        reqV1.setRecordRootElement("person");
        reqV1.setStagedPipeline(true);
        reqV1.setMappings(mappings);
        reqV1.setXmlVersion("v1.0");

        // Provision schema
        provisioningService.provisionDiscoveredTables(config, fileV1.getAbsolutePath(), List.of("person"), Collections.emptyList(), mappings);

        // Stream to flat staging
        stagingStreamingService.streamXmlToStaging(fileV1.getAbsolutePath(), List.of("person"), "v1.0", "v1.0.0", 101L, testDs, 100);

        // Check that person_flat contains bnpp_id and tag_name
        Map<String, Object> flatRow = jdbcTemplate.queryForMap("SELECT * FROM person_flat WHERE record_identifier = '9001'");
        assertNotNull(flatRow.get("bnpp_id"), "Flat table must contain bnpp_id");
        assertTrue(isValidUuid((String) flatRow.get("bnpp_id")), "Flat table bnpp_id must be a valid UUID");
        assertEquals("person", flatRow.get("tag_name"));

        // Normalize from staging to destination table Person
        DynamicSchemaResolutionService.NormalizationSummary normV1 = schemaResolutionService.normalizeStagingToCanonical(
                101L, testDs, mappings, List.of("Person"), "Person", 100
        );
        assertTrue(normV1.normalizedCount() >= 1, "Must normalize at least 1 record");

        // Verify destination table Person
        Map<String, Object> personV1 = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 9001");
        assertTrue(isValidUuid((String) personV1.get("bnpp_id")), "Destination table must hold unique UUID bnpp_id");
        assertEquals(flatRow.get("bnpp_id"), personV1.get("source_bnpp_id"), "Canonical table must reference flat table's bnpp_id");
        assertEquals("person", personV1.get("tag_name"), "Destination table must hold tag_name");
        assertEquals(1, ((Number) personV1.get("version_number")).intValue(), "Initial version must be 1");
        assertEquals("Alice", personV1.get("name"));
        assertNotNull(personV1.get("payload_hash"), "Must compute payload_hash");

        // 2. Upload the EXACT SAME file a second time: Verify duplicate is ignored / skipped
        stagingStreamingService.streamXmlToStaging(fileV1.getAbsolutePath(), List.of("person"), "v1.0", "v1.0.0", 102L, testDs, 100);
        DynamicSchemaResolutionService.NormalizationSummary normV2 = schemaResolutionService.normalizeStagingToCanonical(
                102L, testDs, mappings, List.of("Person"), "Person", 100
        );
        // Duplicate should be ignored, write count must be 0
        long countAfterDup = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Person WHERE person_id = 9001", Long.class);
        assertEquals(1, countAfterDup, "Duplicate re-upload must NOT insert duplicate row");
        Map<String, Object> personDup = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 9001");
        assertEquals(1, ((Number) personDup.get("version_number")).intValue(), "Version must remain 1 for unchanged duplicate");
        assertEquals(personV1.get("bnpp_id"), personDup.get("bnpp_id"), "Canonical UUID bnpp_id must not change on duplicate");

        // 3. Upload MODIFIED XML file (Person 9001 changed name to 'Alice Smith', plus NEW Person 9002 'Bob')
        java.io.File fileV2 = java.io.File.createTempFile("person_v2_", ".xml");
        fileV2.deleteOnExit();
        java.nio.file.Files.writeString(fileV2.toPath(), """
            <dataset date="20261108">
                <persons>
                    <person id="9001" status="active">
                        <name>Alice Smith</name>
                        <age>31</age>
                    </person>
                    <person id="9002" status="active">
                        <name>Bob</name>
                        <age>25</age>
                    </person>
                </persons>
            </dataset>
            """);

        stagingStreamingService.streamXmlToStaging(fileV2.getAbsolutePath(), List.of("person"), "v2.0", "v1.0.0", 103L, testDs, 100);
        DynamicSchemaResolutionService.NormalizationSummary normV3 = schemaResolutionService.normalizeStagingToCanonical(
                103L, testDs, mappings, List.of("Person"), "Person", 100
        );

        // Verify Person 9001 is updated with version 2
        Map<String, Object> personV2Updated = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 9001");
        assertEquals(personV1.get("bnpp_id"), personV2Updated.get("bnpp_id"), "Canonical UUID bnpp_id must be preserved across updates");
        assertEquals("Alice Smith", personV2Updated.get("name"), "Name must be updated to Alice Smith");
        assertEquals(2, ((Number) personV2Updated.get("version_number")).intValue(), "Changed record version_number must bump to 2");
        assertNotNull(personV2Updated.get("source_bnpp_id"), "source_bnpp_id must reference updated staging bnpp_id");

        // Verify Person 9002 is inserted with version 1
        Map<String, Object> personNew = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 9002");
        assertTrue(isValidUuid((String) personNew.get("bnpp_id")), "New record must have valid UUID bnpp_id");
        assertNotEquals(personV1.get("bnpp_id"), personNew.get("bnpp_id"), "New record must have distinct UUID");
        assertEquals("Bob", personNew.get("name"));
        assertEquals(1, ((Number) personNew.get("version_number")).intValue(), "New record must have version 1");
        assertNotNull(personNew.get("source_bnpp_id"), "New record must have source_bnpp_id");
    }

    @Test
    @DisplayName("Verify PFA XML streaming populates staging and destination tables without duplicate flat or stage tables")
    void testPfaXmlStreamingAndIngestionPopulatesTablesWithoutDuplicateOrEmptyTables() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:pfa_clean_test_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        javax.sql.DataSource testDs = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(testDs);

        String samplePath = "src/test/resources/pfa-sample.xml";

        // 1. Provision schema dynamically from pfa-sample.xml
        List<String> createdTables = provisioningService.provisionDiscoveredTables(
                config, samplePath, List.of("Person"), Collections.emptyList(), Collections.emptyList()
        );

        // Verify NO duplicate flat or stage tables were created
        List<String> createdLower = createdTables.stream().map(String::toLowerCase).toList();
        assertFalse(createdLower.contains("pfaflat"), "Must NOT create camel-case pfaflat table");
        assertFalse(createdLower.contains("pfa_flat"), "Must NOT create pfa_flat table for document root");
        assertFalse(createdLower.contains("pfa_stage"), "Must NOT create pfa_stage table");
        assertFalse(createdLower.contains("pfa"), "Must NOT create pfa table for document root");
        assertFalse(createdLower.contains("countriesflat"), "Must NOT create countriesflat");
        assertFalse(createdLower.contains("countries_stage"), "Must NOT create countries_stage");

        // Verify person_flat IS created
        assertTrue(createdLower.contains("person_flat"), "Must create person_flat partitioned staging table");

        // 2. Stream PFA XML to staging
        com.enterprise.xmldbtool.service.StagingXmlStreamingService.StagingSummary stagingSummary = stagingStreamingService.streamXmlToStaging(
                samplePath, List.of("Person"), "v1.0", "v1.0.0", 201L, testDs, 100
        );

        assertTrue(stagingSummary.totalRead() >= 1, "Must read at least 1 Person record from pfa-sample.xml");
        assertTrue(stagingSummary.stagedCount() >= 1, "Must stage at least 1 Person record");

        // Verify staging_xml_records is NOT empty
        long stagingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM staging_xml_records", Long.class);
        assertTrue(stagingCount >= 1, "staging_xml_records must NOT be empty");

        // Verify person_flat is NOT empty and has bnpp_id
        long flatCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM person_flat", Long.class);
        assertTrue(flatCount >= 1, "person_flat table must NOT be empty");
        Map<String, Object> flatRow = jdbcTemplate.queryForMap("SELECT * FROM person_flat WHERE record_identifier = '1447992'");
        assertTrue(isValidUuid((String) flatRow.get("bnpp_id")), "Flat table must hold UUID bnpp_id");

        // 3. Normalize from staging into destination tables
        DynamicSchemaResolutionService.NormalizationSummary normSummary = schemaResolutionService.normalizeStagingToCanonical(
                201L, testDs, Collections.emptyList(), List.of("Person"), "Person", 100
        );

        assertTrue(normSummary.normalizedCount() >= 1, "Must normalize records into destination tables");

        // Verify destination table Person is NOT empty
        long personCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Person", Long.class);
        assertTrue(personCount >= 1, "Destination table Person must NOT be empty");

        Map<String, Object> personRow = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 1447992");
        assertTrue(isValidUuid((String) personRow.get("bnpp_id")), "Canonical Person must hold UUID bnpp_id");
        assertEquals(flatRow.get("bnpp_id"), personRow.get("source_bnpp_id"), "Canonical Person must reference flat table's bnpp_id");
        assertEquals("Person", personRow.get("tag_name"));
        assertEquals(1, ((Number) personRow.get("version_number")).intValue());
    }

    @Test
    @DisplayName("Verify /api/schema/detect-roots endpoint detects candidate root tags immediately upon entering XML file")
    void testDetectCandidateRootsEndpoint() {
        assertNotNull(schemaDiscoveryController, "SchemaDiscoveryController must be autowired");

        // 1. Valid XML with candidate root elements
        Map<String, Object> req = Map.of("sourceXmlPath", "sample-data/pfa-sample.xml");
        org.springframework.http.ResponseEntity<Map<String, Object>> response = schemaDiscoveryController.detectRoots(req);
        assertEquals(200, response.getStatusCode().value());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));

        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) body.get("candidateRootElements");
        assertNotNull(candidates);
        assertTrue(candidates.contains("Person"), "Candidate roots must contain Person");
        assertTrue(candidates.contains("CountryName"), "Candidate roots must contain CountryName");
        assertNotNull(body.get("fileSizeFormatted"), "File size must be formatted");

        // 2. GET endpoint with query param
        org.springframework.http.ResponseEntity<Map<String, Object>> getResp = schemaDiscoveryController.detectRootsGet("sample-data/large-sample.xml", null);
        assertEquals(200, getResp.getStatusCode().value());
        assertNotNull(getResp.getBody());
        assertTrue((Boolean) getResp.getBody().get("success"));
        assertEquals("record", getResp.getBody().get("defaultRootElement"));
    }

    @Test
    @DisplayName("Verify multi-section test.xml streams 20 records into each dedicated flat table with no empty or extraneous tables")
    void testXmlMultiSectionStreamingPopulatesAllFlatTablesWithoutEmptyOrExtraneousTables() throws Exception {
        String testXmlPath = "sample-data/test.xml";

        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:test_xml_multi_section_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        javax.sql.DataSource testDs = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(testDs);

        // 1. Candidate Root Detection
        List<String> candidates = discoveryService.detectCandidateRootElements(testXmlPath);
        assertNotNull(candidates);
        List<String> expectedRoots = List.of(
                "countrylist", "country", "descriptionlist1", "descriptionlist2",
                "descriptionlist3", "descriptionlist4", "person", "entity"
        );
        for (String expected : expectedRoots) {
            assertTrue(candidates.contains(expected), "Candidates must contain entity root: " + expected);
        }
        // Verify container wrappers and leaf attributes are NOT in candidates
        assertFalse(candidates.contains("canopyapp"), "Must NOT contain docRoot canopyapp");
        assertFalse(candidates.contains("countrylists"), "Must NOT contain wrapper countrylists");
        assertFalse(candidates.contains("countries"), "Must NOT contain wrapper countries");
        assertFalse(candidates.contains("descriptionlists1"), "Must NOT contain wrapper descriptionlists1");
        assertFalse(candidates.contains("descriptionlists2"), "Must NOT contain wrapper descriptionlists2");
        assertFalse(candidates.contains("descriptionlists3"), "Must NOT contain wrapper descriptionlists3");
        assertFalse(candidates.contains("descriptionlists4"), "Must NOT contain wrapper descriptionlists4");
        assertFalse(candidates.contains("persons"), "Must NOT contain wrapper persons");
        assertFalse(candidates.contains("entities"), "Must NOT contain wrapper entities");
        assertFalse(candidates.contains("record"), "Must NOT contain wrapper record when child entities exist");
        assertFalse(candidates.contains("address"), "Must NOT contain child field address");
        assertFalse(candidates.contains("street"), "Must NOT contain child field street");
        assertFalse(candidates.contains("name"), "Must NOT contain child field name");

        // 2. Schema Provisioning: Provision tables for the detected entity roots
        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest discReq = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        discReq.setSourceXmlPath(testXmlPath);
        discReq.setRecordRootElements(candidates);
        discReq.setDbConfig(config);
        discReq.setAutoCreateTables(true);
        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse discResp = discoveryService.discoverSchema(discReq);
        assertTrue(discResp.isSuccess());

        List<String> createdTables = discResp.getCreatedTables();
        assertNotNull(createdTables);
        List<String> createdLower = createdTables.stream().map(String::toLowerCase).toList();

        // Verify exactly the 8 entity flat tables are created
        for (String expected : expectedRoots) {
            String flatTable = expected + "_flat";
            assertTrue(createdLower.contains(flatTable), "Must create dedicated flat table: " + flatTable);
        }

        // Verify NO concatenated tables (e.g. countrylist__descriptionlist1...) were created
        for (String t : createdLower) {
            assertFalse(t.contains("__"), "Table name must not contain '__': " + t);
            assertFalse(t.matches(".*lists\\d*_flat$"), "Must NOT create wrapper flat table ending in lists: " + t);
        }
        assertFalse(createdLower.contains("countrylists_flat"), "Must NOT create countrylists_flat");
        assertFalse(createdLower.contains("persons_flat"), "Must NOT create persons_flat");
        assertFalse(createdLower.contains("entities_flat"), "Must NOT create entities_flat");
        assertFalse(createdLower.contains("countries_flat"), "Must NOT create countries_flat");
        assertFalse(createdLower.contains("address_flat"), "Must NOT create address_flat");

        // 3. StAX XML Streaming: Stream all 8 sections into their respective flat tables
        long testJobExecutionId = 77701L;
        com.enterprise.xmldbtool.service.StagingXmlStreamingService.StagingSummary summary = stagingStreamingService.streamXmlToStaging(
                testXmlPath,
                candidates,
                "v1.0",
                "v1.0.0",
                testJobExecutionId,
                testDs,
                500
        );

        assertEquals(160, summary.totalRead(), "Must read all 160 records across 8 sections (20 records each)");
        assertEquals(160, summary.stagedCount(), "Must stage all 160 records");

        // 4. Verify EVERY single flat table is populated with 20 records (NONE are empty!)
        for (String expected : expectedRoots) {
            String flatTable = expected + "_flat";
            long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + flatTable + " WHERE job_execution_id = ?",
                    Long.class,
                    testJobExecutionId
            );
            assertEquals(20, count, "Flat table '" + flatTable + "' must have exactly 20 records (not empty!)");
        }

        // 5. Verify person_flat holds raw XML for addresses and proper metadata columns
        Map<String, Object> personRow1 = jdbcTemplate.queryForMap(
                "SELECT * FROM person_flat WHERE job_execution_id = ? AND record_identifier = '1'",
                testJobExecutionId
        );
        assertNotNull(personRow1);
        assertTrue(isValidUuid((String) personRow1.get("bnpp_id")), "person_flat bnpp_id must be a UUID");
        assertEquals("person", personRow1.get("tag_name"));
        assertEquals(1, ((Number) personRow1.get("version_number")).intValue());
        assertEquals("James Smith", personRow1.get("name"));
        assertEquals("active", personRow1.get("status"));

        String rawAddressesXml = (String) personRow1.get("addresses");
        assertNotNull(rawAddressesXml, "addresses column must hold raw XML");
        assertTrue(rawAddressesXml.contains("<addresses>"), "addresses raw XML must contain opening tag");
        assertTrue(rawAddressesXml.contains("123 Main St"), "addresses raw XML must contain address street content");
        assertTrue(rawAddressesXml.contains("</addresses>"), "addresses raw XML must contain closing tag");
    }

    @Test
    @DisplayName("Step 2: Verify normalization from partitioned flat tables to canonical relational tables using ingestion.json")
    void testStep2NormalizeFlatTablesToCanonicalTablesWithIngestionJson() throws Exception {
        String testXmlPath = "sample-data/test.xml";
        String mappingJsonPath = "sample-data/ingestion.json";
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:step2_norm_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        javax.sql.DataSource testDs = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(testDs);

        // Load ingestion.json mappings
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<FieldMapping> mappings = mapper.readValue(
                new java.io.File(mappingJsonPath),
                new com.fasterxml.jackson.core.type.TypeReference<List<FieldMapping>>() {}
        );
        assertNotNull(mappings);
        assertFalse(mappings.isEmpty());

        // 1. Provision target canonical tables (Person, Address, Country, CountryList) and flat tables
        List<String> candidates = discoveryService.detectCandidateRootElements(testXmlPath);
        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest discReq = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        discReq.setSourceXmlPath(testXmlPath);
        discReq.setRecordRootElements(candidates);
        discReq.setMappings(mappings);
        discReq.setDbConfig(config);
        discReq.setAutoCreateTables(true);
        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse discResp = discoveryService.discoverSchema(discReq);
        assertTrue(discResp.isSuccess());

        // 2. Phase 1: Stream XML into flat tables
        long jobExecId = 88801L;
        com.enterprise.xmldbtool.service.StagingXmlStreamingService.StagingSummary stagingSummary = stagingStreamingService.streamXmlToStaging(
                testXmlPath,
                candidates,
                "v1.0",
                "v1.0.0",
                jobExecId,
                testDs,
                500
        );
        assertEquals(160, stagingSummary.totalRead());
        assertEquals(160, stagingSummary.stagedCount());

        // Verify status in flat tables is initially un-normalized
        Long initialPersonCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person_flat WHERE status != 'NORMALIZED'", Long.class
        );
        assertEquals(20, initialPersonCount, "person_flat must have 20 un-normalized records");

        // 3. Phase 2: Execute Normalization from flat tables to canonical relational tables
        List<String> tableOrder = List.of("CountryList", "Country", "Person", "Address");
        com.enterprise.xmldbtool.service.DynamicSchemaResolutionService.NormalizationSummary normSummary =
                schemaResolutionService.normalizeStagingToCanonical(
                        jobExecId,
                        testDs,
                        mappings,
                        tableOrder,
                        "Person",
                        500
                );

        assertNotNull(normSummary);
        assertTrue(normSummary.normalizedCount() > 0, "Must normalize records into canonical tables");
        assertEquals(0, normSummary.failedCount(), "Failed count must be 0");

        // 4. Assert Person canonical table
        Long personCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Person", Long.class);
        assertEquals(20, personCount, "Person table must contain 20 records");

        Map<String, Object> person1 = jdbcTemplate.queryForMap(
                "SELECT * FROM Person WHERE person_id = 1"
        );
        assertTrue(isValidUuid((String) person1.get("bnpp_id")), "Person table must hold UUID bnpp_id");
        assertNotNull(person1.get("source_bnpp_id"), "Person table must reference flat staging bnpp_id");
        assertTrue(isValidUuid((String) person1.get("source_bnpp_id")), "source_bnpp_id must be a UUID");
        assertEquals("person", person1.get("tag_name"));
        assertEquals(1, ((Number) person1.get("version_number")).intValue());
        assertEquals("ACTIVE", person1.get("status"), "UPPER transform must convert active to ACTIVE");
        assertEquals("James Smith", person1.get("name"));
        assertEquals("37", String.valueOf(person1.get("age")));
        assertNotNull(person1.get("payload_hash"), "payload_hash must be present for delta tracking");

        // 5. Assert Address canonical table (unpacked 1-to-N from raw XML addresses column)
        Long addressCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Address", Long.class);
        assertEquals(20, addressCount, "Address table must contain 20 records unpacked from XML");

        Map<String, Object> address1 = jdbcTemplate.queryForMap(
                "SELECT * FROM Address WHERE id = 1"
        );
        assertTrue(isValidUuid((String) address1.get("bnpp_id")), "Address table must hold UUID bnpp_id");
        assertNotNull(address1.get("source_bnpp_id"), "Address table must reference flat staging bnpp_id");
        assertTrue(isValidUuid((String) address1.get("source_bnpp_id")), "Address source_bnpp_id must be a UUID");
        assertEquals("address", address1.get("tag_name"));
        assertEquals(1, ((Number) address1.get("version_number")).intValue());
        assertEquals("1", String.valueOf(address1.get("person_id")), "Address person_id FK must link to Person");
        assertEquals("1", String.valueOf(address1.get("country_id")), "Address country_id FK must link to Country");
        assertEquals("123 Main St", address1.get("street"));
        assertEquals("New York", address1.get("city"));

        // 6. Assert CountryList and Country canonical tables
        Long countryListCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CountryList", Long.class);
        assertEquals(20, countryListCount, "CountryList table must contain 20 records");

        Map<String, Object> cl1 = jdbcTemplate.queryForMap("SELECT * FROM CountryList WHERE id = 1");
        assertTrue(isValidUuid((String) cl1.get("bnpp_id")), "CountryList table must hold UUID bnpp_id");
        assertNotNull(cl1.get("source_bnpp_id"), "CountryList table must reference flat staging bnpp_id");
        assertTrue(isValidUuid((String) cl1.get("source_bnpp_id")), "CountryList source_bnpp_id must be a UUID");
        assertEquals("countrylist", cl1.get("tag_name"));
        assertEquals(1, ((Number) cl1.get("version_number")).intValue());
        assertEquals("countrylist1", cl1.get("name"));

        Long countryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Country", Long.class);
        assertEquals(20, countryCount, "Country table must contain 20 records");

        Map<String, Object> c1 = jdbcTemplate.queryForMap("SELECT * FROM Country WHERE id = 1");
        assertTrue(isValidUuid((String) c1.get("bnpp_id")), "Country table must hold UUID bnpp_id");
        assertNotNull(c1.get("source_bnpp_id"), "Country table must reference flat staging bnpp_id");
        assertTrue(isValidUuid((String) c1.get("source_bnpp_id")), "Country source_bnpp_id must be a UUID");
        assertEquals("country", c1.get("tag_name"));
        assertEquals(1, ((Number) c1.get("version_number")).intValue());
        assertEquals("1", String.valueOf(c1.get("countrylist_id")), "Country countrylist_id FK must link to CountryList");

        // 7. Assert status updated to 'NORMALIZED' in flat tables and staging_xml_records
        Long normalizedFlatCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person_flat WHERE status = 'NORMALIZED'", Long.class
        );
        assertEquals(20, normalizedFlatCount, "person_flat records must be updated to NORMALIZED");

        Long remainingStagedFlat = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person_flat WHERE status = 'STAGED'", Long.class
        );
        assertEquals(0, remainingStagedFlat, "No records should remain STAGED in person_flat");

        Long normalizedStagingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staging_xml_records WHERE tag_name = 'person' AND status = 'NORMALIZED'", Long.class
        );
        assertEquals(20, normalizedStagingCount, "staging_xml_records for person must be updated to NORMALIZED");
    }

    @Test
    @DisplayName("Step 2: Verify deduplication ignores duplicate file re-upload and increments version on delta changes")
    void testStep2DeduplicationOnSameFileUploadAndVersionIncrementOnDelta() throws Exception {
        String testXmlPath = "sample-data/test.xml";
        String mappingJsonPath = "sample-data/ingestion.json";
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:step2_dedup_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        javax.sql.DataSource testDs = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(testDs);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<FieldMapping> mappings = mapper.readValue(
                new java.io.File(mappingJsonPath),
                new com.fasterxml.jackson.core.type.TypeReference<List<FieldMapping>>() {}
        );

        // Provision schema
        List<String> candidates = discoveryService.detectCandidateRootElements(testXmlPath);
        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest discReq = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        discReq.setSourceXmlPath(testXmlPath);
        discReq.setRecordRootElements(candidates);
        discReq.setMappings(mappings);
        discReq.setDbConfig(config);
        discReq.setAutoCreateTables(true);
        discoveryService.discoverSchema(discReq);

        // Phase 1: Stream to staging
        long jobExecId = 88802L;
        stagingStreamingService.streamXmlToStaging(testXmlPath, candidates, "v1.0", "v1.0.0", jobExecId, testDs, 500);

        // Phase 2: First normalization
        List<String> tableOrder = List.of("CountryList", "Country", "Person", "Address");
        com.enterprise.xmldbtool.service.DynamicSchemaResolutionService.NormalizationSummary firstNorm =
                schemaResolutionService.normalizeStagingToCanonical(jobExecId, testDs, mappings, tableOrder, "Person", 500);

        assertTrue(firstNorm.normalizedCount() > 0, "First normalization must write records");
        assertEquals(20, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Person", Long.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT version_number FROM Person WHERE person_id = 1", Integer.class));

        // --- SUB-TEST A: Identical Duplicate Re-upload (Duplicate Ignored) ---
        // Simulate re-staging the same records with STAGED status
        jdbcTemplate.update("UPDATE person_flat SET status = 'STAGED'");

        com.enterprise.xmldbtool.service.DynamicSchemaResolutionService.NormalizationSummary duplicateNorm =
                schemaResolutionService.normalizeStagingToCanonical(jobExecId, testDs, mappings, tableOrder, "Person", 500);

        // Written count for Person table must be 0 because identical payload hashes are ignored!
        Long personWrites = duplicateNorm.tableWriteCounts().getOrDefault("Person", 0L);
        assertEquals(0L, personWrites, "Identical duplicate re-upload must be ignored (0 writes)");
        assertEquals(20, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Person", Long.class), "Person count must stay at 20");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT version_number FROM Person WHERE person_id = 1", Integer.class), "Version must stay at 1");

        // --- SUB-TEST B: Delta Change (Increment Version Number) ---
        // Modify person_flat record #1: Change age to 42 in raw_payload
        Map<String, Object> person1Raw = jdbcTemplate.queryForMap("SELECT raw_payload FROM person_flat WHERE record_identifier = '1'");
        String json = (String) person1Raw.get("raw_payload");
        Map<String, Object> map = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        map.put("age", "42");
        String updatedJson = mapper.writeValueAsString(map);

        jdbcTemplate.update(
                "UPDATE person_flat SET raw_payload = ?, payload_hash = 'changed_hash_123', status = 'STAGED' WHERE record_identifier = '1'",
                updatedJson
        );

        // Run normalization again
        com.enterprise.xmldbtool.service.DynamicSchemaResolutionService.NormalizationSummary deltaNorm =
                schemaResolutionService.normalizeStagingToCanonical(jobExecId, testDs, mappings, tableOrder, "Person", 500);

        // Assert record #1 was updated with version_number = 2 and age = 42
        Map<String, Object> updatedPerson1 = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 1");
        assertEquals(2, ((Number) updatedPerson1.get("version_number")).intValue(), "Delta record must have version_number incremented to 2");
        assertEquals("42", String.valueOf(updatedPerson1.get("age")), "Age must be updated to 42");

        // Record #2 should remain untouched at version_number = 1
        assertEquals(1, jdbcTemplate.queryForObject("SELECT version_number FROM Person WHERE person_id = 2", Integer.class), "Unchanged record #2 must remain at version 1");
    }

    @Test
    @DisplayName("Step 2: Verify normalization without ingestion.json auto-derives canonical tables and mappings")
    void testStep2WithoutIngestionJsonAutoDerivesCanonicalTables() throws Exception {
        String testXmlPath = "sample-data/test.xml";
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:step2_no_json_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        javax.sql.DataSource testDs = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(testDs);

        List<String> candidates = discoveryService.detectCandidateRootElements(testXmlPath);

        // Discover and provision without user mapping JSON (mappings = null)
        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest discReq = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        discReq.setSourceXmlPath(testXmlPath);
        discReq.setRecordRootElements(candidates);
        discReq.setDbConfig(config);
        discReq.setAutoCreateTables(true);
        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse discResp = discoveryService.discoverSchema(discReq);
        assertTrue(discResp.isSuccess());

        // Phase 1: Stream to staging
        long jobExecId = 88803L;
        stagingStreamingService.streamXmlToStaging(testXmlPath, candidates, "v1.0", "v1.0.0", jobExecId, testDs, 500);

        // Phase 2: Normalize with mappings = null (auto-derivation fallback)
        com.enterprise.xmldbtool.service.DynamicSchemaResolutionService.NormalizationSummary autoNorm =
                schemaResolutionService.normalizeStagingToCanonical(
                        jobExecId,
                        testDs,
                        null,
                        null,
                        "person",
                        500
                );

        assertNotNull(autoNorm);
        assertTrue(autoNorm.normalizedCount() > 0, "Must normalize records using auto-derived schema");

        // Verify person canonical table was populated
        Long personCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM person", Long.class);
        assertEquals(20, personCount, "Auto-derived person table must contain 20 records");

        Map<String, Object> p1 = jdbcTemplate.queryForMap("SELECT * FROM person LIMIT 1");
        assertNotNull(p1.get("bnpp_id"));
        assertTrue(isValidUuid((String) p1.get("bnpp_id")), "Auto-derived person row must have valid UUID bnpp_id");
        assertEquals(1, ((Number) p1.get("version_number")).intValue());
        assertNotNull(p1.get("tag_name"));
    }

    @Test
    @DisplayName("Verify 10GB Optimization: Preview threshold is at least 10GB and PostgreSQL Hikari config enables reWriteBatchedInserts")
    void testLargeFile10GbOptimizations() {
        // 1. Verify preview service allows files over 100MB and up to 10GB+ without skipping preview
        assertEquals(20L * 1024 * 1024 * 1024, com.enterprise.xmldbtool.service.XmlPreviewService.DEFAULT_MAX_PREVIEW_FILE_SIZE_BYTES);
        assertTrue(previewService.getMaxPreviewSizeLimit() >= 10L * 1024 * 1024 * 1024, "Preview limit must accommodate 10GB files");

        // 2. Verify PostgreSQL dynamic DataSource builds with high-throughput batching properties
        DbConnectionConfig pgConfig = new DbConnectionConfig();
        pgConfig.setEngine("POSTGRESQL");
        pgConfig.setUrl("jdbc:postgresql://localhost:5432/testdb");
        pgConfig.setUsername("testuser");
        pgConfig.setPassword("testpass");
        com.zaxxer.hikari.HikariConfig hikariConfig = dataSourceService.buildHikariConfig(pgConfig);

        assertEquals("true", hikariConfig.getDataSourceProperties().getProperty("reWriteBatchedInserts"),
                "PostgreSQL Hikari config must enable reWriteBatchedInserts for 10GB high-throughput batch writing");
        assertEquals("unspecified", hikariConfig.getDataSourceProperties().getProperty("stringtype"));

        // 3. Verify fast precompiled regex autoConvertType
        Object ts = com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter.autoConvertType("2026-10-01 09:15:00", "created_at");
        assertInstanceOf(java.sql.Timestamp.class, ts);

        Object num = com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter.autoConvertType("12345", "person_id");
        assertInstanceOf(Integer.class, num);

        // 4. Verify thread-safe SHA-256 computation
        String hash1 = com.enterprise.xmldbtool.service.StagingXmlStreamingService.computeSha256("test-payload-data");
        assertNotNull(hash1);
        assertEquals(64, hash1.length(), "SHA-256 hash must be 64 hexadecimal characters");
    }

    @Test
    @DisplayName("Verify Deeply Nested XML up to 10 Levels: unmarshalling, foreign key inheritance, and item processor mapping")
    void testDeeplyNestedXmlUpTo10LevelsStreamingAndExtraction() throws Exception {
        // Construct 10-level deeply nested XML fragment
        String nestedXml = """
                <Enterprise>
                    <Organization id="ORG-100" region="APAC">
                        <Division id="DIV-200">
                            <Department id="DEP-300">
                                <Group id="GRP-400">
                                    <Team id="TEA-500">
                                        <Project id="PRJ-600">
                                            <Task id="TSK-700">
                                                <Subtask id="SUB-800">
                                                    <Detail id="DET-900" status="COMPLETED">
                                                        <DetailName>Deep Level 10 Task</DetailName>
                                                        <EstimatedHours>42</EstimatedHours>
                                                    </Detail>
                                                </Subtask>
                                            </Task>
                                        </Project>
                                    </Team>
                                </Group>
                            </Department>
                        </Division>
                    </Organization>
                </Enterprise>
                """;

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        javax.xml.stream.XMLEventReader reader = factory.createXMLEventReader(new java.io.StringReader(nestedXml));
        Map<String, Object> recordMap = unmarshaller.parseFragmentFromEventReader(reader);

        assertNotNull(recordMap);

        // 1. Verify exact 10-level full path resolution
        String deepPath = "Enterprise/Organization/Division/Department/Group/Team/Project/Task/Subtask/Detail/DetailName";
        assertEquals("Deep Level 10 Task", recordMap.get(deepPath), "Full 10-level path must resolve directly in recordMap");

        // 2. Verify leaf element resolution
        assertEquals("Deep Level 10 Task", recordMap.get("DetailName"));
        assertEquals("42", recordMap.get("EstimatedHours"));

        // 3. Verify leaf entity captured in _entities_detail
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) recordMap.get("_entities_detail");
        assertNotNull(details, "_entities_detail must be populated for level 10 entity");
        assertEquals(1, details.size());

        Map<String, Object> detailEntity = details.get(0);
        assertEquals("DET-900", detailEntity.get("id"));
        assertEquals("COMPLETED", detailEntity.get("status"));
        assertEquals("Deep Level 10 Task", detailEntity.get("DetailName"));

        // 4. Verify ancestor foreign keys inherited down through all 10 nesting levels
        assertEquals("SUB-800", detailEntity.get("subtask_id"), "Level 9 parent ID must be inherited");
        assertEquals("TSK-700", detailEntity.get("task_id"), "Level 8 ancestor ID must be inherited");
        assertEquals("PRJ-600", detailEntity.get("project_id"), "Level 7 ancestor ID must be inherited");
        assertEquals("TEA-500", detailEntity.get("team_id"), "Level 6 ancestor ID must be inherited");
        assertEquals("GRP-400", detailEntity.get("group_id"), "Level 5 ancestor ID must be inherited");
        assertEquals("DEP-300", detailEntity.get("department_id"), "Level 4 ancestor ID must be inherited");
        assertEquals("DIV-200", detailEntity.get("division_id"), "Level 3 ancestor ID must be inherited");
        assertEquals("ORG-100", detailEntity.get("organization_id"), "Level 2 ancestor ID must be inherited");

        // 5. Verify DynamicXmlItemProcessor transforms deep 10th-level entity into database table row
        List<FieldMapping> mappings = List.of(
                new FieldMapping("Enterprise/Organization/Division/Department/Group/Team/Project/Task/Subtask/Detail/DetailName", "detail", "detail_name", "NONE", "NONE"),
                new FieldMapping("Detail/@status", "detail", "status_code", "NONE", "NONE"),
                new FieldMapping("subtask_id", "detail", "subtask_id", "NONE", "FK"),
                new FieldMapping("organization_id", "detail", "org_id", "NONE", "FK")
        );

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor("Enterprise", mappings);
        DatabaseRowPayload payload = processor.process(recordMap);

        assertNotNull(payload);
        List<Map<String, Object>> detailRows = payload.getMultiTableRecords().get("detail");
        assertNotNull(detailRows, "Must produce routed rows for 'detail' table");
        assertEquals(1, detailRows.size());

        Map<String, Object> row = detailRows.get(0);
        assertEquals("Deep Level 10 Task", row.get("detail_name"));
        assertEquals("COMPLETED", row.get("status_code"));
        assertEquals("SUB-800", row.get("subtask_id"));
        assertEquals("ORG-100", row.get("org_id"));
    }

    private boolean isValidUuid(String val) {
        if (val == null) return false;
        try {
            UUID.fromString(val);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void testJava21VirtualMultiThreadingDataLoad() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:vthread_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        javax.sql.DataSource ds = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jt = new org.springframework.jdbc.core.JdbcTemplate(ds);

        // Stream XML using Java 21 Virtual Threads
        com.enterprise.xmldbtool.service.StagingXmlStreamingService.StagingSummary summary =
                stagingStreamingService.streamXmlToStaging(
                        "sample-data/pfa-sample.xml",
                        List.of("Person"),
                        "v1.0",
                        "v1.0.0",
                        77701L,
                        ds,
                        10
                );

        assertNotNull(summary);
        assertTrue(summary.stagedCount() > 0, "Virtual threading streaming must stage records");
        assertEquals(0, summary.failedCount(), "Zero failures expected with virtual thread ingestion");

        Integer stagedInDb = jt.queryForObject("SELECT COUNT(*) FROM staging_xml_records WHERE job_execution_id = 77701", Integer.class);
        assertEquals(summary.stagedCount(), stagedInDb.longValue());
    }

    @Test
    void testMandatoryIngestionJsonEnforcementAndNoXmlTableGuessing() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:mand_json_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        // 1. Without ingestion.json and ingestionJsonMandatory = true, discovery must fail and not guess tables
        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest req = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        req.setDbConfig(config);
        req.setSourceXmlPath("sample-data/large-sample.xml");
        req.setIngestionJsonMandatory(true);
        req.setMappings(Collections.emptyList());
        req.setMappingJson("");
        req.setIngestionJsonPath("non-existent-ingestion.json");

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse resp = discoveryService.discoverSchema(req);
        assertFalse(resp.isSuccess(), "Schema discovery must fail when mandatory ingestion.json is missing");
        assertFalse(resp.isMappingsValidated());
        assertTrue(resp.getMessage().contains("mandatory"), "Error message must declare ingestion.json is mandatory");

        // Verify no canonical tables were provisioned based on raw XML guessing
        org.springframework.jdbc.core.JdbcTemplate jt = new org.springframework.jdbc.core.JdbcTemplate(dataSourceService.getOrCreateDataSource(config));
        Integer tableCount = jt.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'PUBLIC' AND table_name NOT IN ('STAGING_XML_RECORDS')",
                Integer.class
        );
        assertEquals(0, tableCount != null ? tableCount : 0, "No tables should be created based on raw XML heuristics");
    }

    @Test
    void testIngestionJsonColumnValidationAgainstXmlTags() throws Exception {
        // 1. Valid mappings: columns exist in XML
        List<FieldMapping> validMappings = List.of(
                new FieldMapping("person/@id", "Person", "person_id", "PK", "NONE"),
                new FieldMapping("person/name", "Person", "name", "NONE", "NONE"),
                new FieldMapping("person/age", "Person", "age", "NONE", "NONE")
        );

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest validReq = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        validReq.setSourceXmlPath("sample-data/test.xml");
        validReq.setRecordRootElement("person");
        validReq.setMappings(validMappings);
        validReq.setAutoCreateTables(false);

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse validResp = discoveryService.discoverSchema(validReq);
        assertTrue(validResp.isSuccess());
        assertTrue(validResp.isMappingsValidated(), "Mappings should validate successfully against XML elements");
        assertTrue(validResp.getMissingXmlFields().isEmpty(), "No missing XML fields expected");
        assertEquals(3, validResp.getValidatedXmlFields().size());

        // 2. Invalid mappings: unmapped bogus tag not in XML
        List<FieldMapping> invalidMappings = List.of(
                new FieldMapping("person/@id", "Person", "person_id", "PK", "NONE"),
                new FieldMapping("person/non_existent_xml_tag", "Person", "bogus_col", "NONE", "NONE")
        );

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest invalidReq = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        invalidReq.setSourceXmlPath("sample-data/test.xml");
        invalidReq.setRecordRootElement("person");
        invalidReq.setMappings(invalidMappings);
        invalidReq.setAutoCreateTables(false);

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse invalidResp = discoveryService.discoverSchema(invalidReq);
        assertFalse(invalidResp.isMappingsValidated(), "Validation must fail when mapping column is missing in XML tags");
        assertFalse(invalidResp.getMissingXmlFields().isEmpty(), "Missing fields must capture the invalid column");
        assertTrue(invalidResp.getMissingXmlFields().get(0).contains("non_existent_xml_tag"));
        assertTrue(invalidResp.getValidationMessage().contains("Validation failed"));
    }

    @Test
    void testFlattenedTablesUseGsSuffixGoingForward() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:gs_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        // Discovery & Provisioning
        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest req = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        req.setDbConfig(config);
        req.setSourceXmlPath("sample-data/test.xml");
        req.setRecordRootElement("person");
        req.setAutoCreateTables(true);
        req.setMappings(List.of(
                new FieldMapping("person/@id", "Person", "id", "PK", "NONE"),
                new FieldMapping("person/name", "Person", "name", "NONE", "NONE")
        ));

        com.enterprise.xmldbtool.model.SchemaDiscoveryResponse resp = discoveryService.discoverSchema(req);
        assertTrue(resp.isSuccess());

        // Verify _gs flattened table is created
        assertTrue(resp.getCreatedTables().stream().anyMatch(t -> t.equalsIgnoreCase("person_gs")),
                "Must create flattened table with _gs suffix: person_gs");
        assertTrue(resp.getCreatedFlatTables().stream().anyMatch(t -> t.equalsIgnoreCase("person_gs")),
                "Created flat tables list must include person_gs");

        javax.sql.DataSource ds = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jt = new org.springframework.jdbc.core.JdbcTemplate(ds);

        // Verify person_gs table columns in H2
        List<Map<String, Object>> cols = jt.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'PERSON_GS'"
        );
        assertFalse(cols.isEmpty(), "person_gs columns must exist in database");
        Set<String> colNames = cols.stream().map(c -> String.valueOf(c.get("COLUMN_NAME")).toLowerCase()).collect(java.util.stream.Collectors.toSet());
        assertTrue(colNames.contains("id"));
        assertTrue(colNames.contains("bnpp_id"));
        assertTrue(colNames.contains("tag_name"));
        assertTrue(colNames.contains("payload_hash"));
        assertTrue(colNames.contains("raw_payload"));

        // Stream records and verify person_gs gets populated
        stagingStreamingService.streamXmlToStaging(
                "sample-data/test.xml",
                List.of("person"),
                "v1.0",
                "v1.0.0",
                88801L,
                ds,
                50
        );

        Integer gsCount = jt.queryForObject("SELECT COUNT(*) FROM person_gs", Integer.class);
        assertNotNull(gsCount);
        assertTrue(gsCount > 0, "person_gs flattened table must be populated with streamed records");

        // Stage 2 Normalization: verify normalization reads from person_gs
        com.enterprise.xmldbtool.service.DynamicSchemaResolutionService.NormalizationSummary norm =
                schemaResolutionService.normalizeStagingToCanonical(
                        88801L,
                        ds,
                        req.getMappings(),
                        List.of("Person"),
                        "Person",
                        50
                );
        assertNotNull(norm);
        assertTrue(norm.normalizedCount() > 0, "Normalization must project from person_gs into Person table");

        Integer canonicalCount = jt.queryForObject("SELECT COUNT(*) FROM Person", Integer.class);
        assertEquals(gsCount, canonicalCount, "Canonical Person count must match person_gs record count");
    }

    @Test
    @DisplayName("Verify job start verifies DB connection and tables, provisioning strictly from job config")
    void testJobLaunchVerifiesDbConnectionAndTablesPresentAndAutoProvisionsFromJobConfig() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:jobstart_test_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        javax.sql.DataSource ds = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jt = new org.springframework.jdbc.core.JdbcTemplate(ds);

        // Ensure clean state: drop gs_person and canonical table
        try { jt.execute("DROP TABLE IF EXISTS gs_person CASCADE"); } catch (Exception ignored) {}
        try { jt.execute("DROP TABLE IF EXISTS PersonJobConfigTest CASCADE"); } catch (Exception ignored) {}

        // Build JobLaunchRequest with mappings from job config
        JobLaunchRequest request = new JobLaunchRequest();
        request.setDbConfig(config);
        request.setSourceXmlPath("sample-data/test.xml");
        request.setRecordRootElement("person");
        request.setRecordRootElements(List.of("person"));
        request.setMappings(List.of(
                new FieldMapping("person/@id", "PersonJobConfigTest", "person_id", "NONE", "PK"),
                new FieldMapping("person/name", "PersonJobConfigTest", "name", "NONE", "NONE"),
                new FieldMapping("person/age", "PersonJobConfigTest", "age", "NONE", "NONE")
        ));

        // Call verifyAndEnsureDatabaseAndTables on job start
        javax.sql.DataSource verifiedDs = batchJobService.verifyAndEnsureDatabaseAndTables(request, List.of("person"));
        assertNotNull(verifiedDs, "Verified DataSource must not be null");

        try (java.sql.Connection conn = verifiedDs.getConnection()) {
            assertTrue(conn.isValid(5), "Target DB connection must be verified and active");
        }

        // Verify flat table with gs_ prefix and no suffix was auto-provisioned
        Integer gsPersonCount = jt.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'GS_PERSON'", Integer.class
        );
        assertEquals(1, gsPersonCount, "Flat table gs_person must be created on job start");

        // Verify canonical table from job config was auto-provisioned
        Integer canonicalTblCount = jt.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'PERSONJOBCONFIGTEST'", Integer.class
        );
        assertEquals(1, canonicalTblCount, "Canonical table from job config must be created on job start");

        // Verify NO heuristic canonical tables dynamically guessed from XML tags (e.g. unmapped sub-elements) were created
        Integer unmappedGuessCount = jt.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'TEST_XML_UNMAPPED'", Integer.class
        );
        assertEquals(0, unmappedGuessCount, "No tables should be created based on raw XML heuristics");
    }

    @Test
    @DisplayName("Verify flat tables use prefix gs_ without suffix and store raw XML payload")
    void testFlatTablesPrefixGsWithoutSuffixAndStoreRawXml() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:gsprefix_test_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        javax.sql.DataSource ds = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jt = new org.springframework.jdbc.core.JdbcTemplate(ds);

        // Provision schema for person and country
        provisioningService.provisionDiscoveredTables(config, "sample-data/test.xml", List.of("person", "country"), Collections.emptyList());

        // Verify flat table names start with gs_ and have NO suffix (no _flat, no _gs)
        List<Map<String, Object>> gsTables = jt.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'GS_%'"
        );
        Set<String> tableNames = gsTables.stream()
                .map(m -> String.valueOf(m.get("TABLE_NAME")).toLowerCase())
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(tableNames.contains("gs_person"), "Flat table must have prefix gs_ and no suffix: gs_person");
        assertTrue(tableNames.contains("gs_country"), "Flat table must have prefix gs_ and no suffix: gs_country");

        // Stream records and verify raw_payload in gs_person stores raw XML fragment
        stagingStreamingService.streamXmlToStaging(
                "sample-data/test.xml",
                List.of("person"),
                "v1.0",
                "v1.0.0",
                77701L,
                ds,
                50
        );

        Map<String, Object> row = jt.queryForMap("SELECT * FROM gs_person WHERE job_execution_id = 77701 LIMIT 1");
        assertNotNull(row, "Record must exist in gs_person flat table");
        assertNotNull(row.get("raw_payload"), "Flat table must store raw_payload");
        String rawPayload = String.valueOf(row.get("raw_payload"));
        assertTrue(rawPayload.contains("person") || rawPayload.contains("id"), "raw_payload must store the raw XML content/structure");
        assertNotNull(row.get("payload_hash"), "payload_hash must be present for delta tracking");
        assertNotNull(row.get("bnpp_id"), "bnpp_id must be populated");
        assertEquals("person", String.valueOf(row.get("tag_name")).toLowerCase());
    }

    @Test
    @DisplayName("Verify canonical table data is clean format (no XML, no JSON) and reference tables store reference ID")
    void testCanonicalTableDataCleanFormatAndReferenceTableReferenceId() throws Exception {
        // 1. Verify cleanDataFormat strips XML tags
        String xmlFragment = "<detail><note>Critical Flag</note><priority>1</priority></detail>";
        Object cleanXml = com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor.cleanDataFormat(xmlFragment);
        assertEquals("Critical Flag 1", cleanXml, "Canonical data must strip all XML tags to clean scalar text");

        // 2. Verify cleanDataFormat cleans JSON formatting
        String jsonFragment = "{\"code\": \"US\", \"name\": \"United States\"}";
        Object cleanJson = com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor.cleanDataFormat(jsonFragment);
        assertFalse(cleanJson.toString().contains("{"), "Canonical data must not contain JSON curly braces");
        assertFalse(cleanJson.toString().contains("}"), "Canonical data must not contain JSON curly braces");
        assertFalse(cleanJson.toString().contains("\""), "Canonical data must not contain JSON quotes");

        // 3. Verify cleanDataFormat with JSON array
        String jsonArray = "[\"Tier1\", \"HighRisk\"]";
        Object cleanArray = com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor.cleanDataFormat(jsonArray);
        assertEquals("Tier1, HighRisk", cleanArray, "Canonical data must extract clean scalar values from JSON array");

        // 4. Verify cleanDataFormat preserves already clean scalar values
        assertEquals("Clean Text Value", com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor.cleanDataFormat("Clean Text Value"));
        assertEquals(42, com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor.cleanDataFormat(42));

        // 5. Verify reference tables store parent reference ID (clean scalar parent ID)
        List<FieldMapping> mappings = List.of(
                new FieldMapping("Person/@id", "Person", "person_id", "NONE", "PK"),
                new FieldMapping("Person/Name", "Person", "name", "NONE", "NONE"),
                new FieldMapping("Person/Address/Street", "PersonAddress", "street", "NONE", "NONE"),
                new FieldMapping("Person/Address/City", "PersonAddress", "city", "NONE", "NONE"),
                new FieldMapping("Person/@id", "PersonAddress", "person_id", "NONE", "FK")
        );

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor(
                "Person",
                mappings,
                Collections.emptyMap(),
                List.of("Person", "PersonAddress")
        );

        Map<String, Object> inputRecord = new LinkedHashMap<>();
        inputRecord.put("id", "10099");
        inputRecord.put("name", "<name>John Doe</name>"); // intentionally with embedded XML tag
        inputRecord.put("_entities_personaddress", List.of(
                Map.of("street", "<street>100 Main Street</street>", "city", "New York")
        ));

        DatabaseRowPayload payload = processor.process(inputRecord);
        assertNotNull(payload);
        assertNotNull(payload.getMultiTableRecords());

        // Master Person record: name should be clean (no XML tags)
        List<Map<String, Object>> personRows = payload.getMultiTableRecords().get("Person");
        assertNotNull(personRows);
        assertEquals(1, personRows.size());
        assertEquals("John Doe", personRows.get(0).get("name"), "Canonical table must have clean data with XML tags stripped");

        // Child PersonAddress record (reference table): must contain clean parent reference ID (person_id)
        List<Map<String, Object>> addressRows = payload.getMultiTableRecords().get("PersonAddress");
        assertNotNull(addressRows);
        assertEquals(1, addressRows.size());
        Map<String, Object> addrRow = addressRows.get(0);

        // street should be clean (no XML tags)
        assertEquals("100 Main Street", addrRow.get("street"), "Canonical child data must have XML tags stripped");
        assertEquals("New York", addrRow.get("city"));

        // Reference column person_id must be populated with parent reference ID
        assertEquals("10099", String.valueOf(addrRow.get("person_id")), "Reference table must store parent reference ID");
        assertFalse(String.valueOf(addrRow.get("person_id")).contains("<"), "Reference ID must be clean scalar, not XML");
        assertFalse(String.valueOf(addrRow.get("person_id")).contains("{"), "Reference ID must be clean scalar, not JSON");
    }

    @Test
    @DisplayName("Verify createFlatTableForEntity creates dedicated flat table gs_<entityname> without suffix")
    void testCreateFlatTableForEntityDirectly() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:createflat_test_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        // Explicitly create flat table for entity "Customer"
        List<String> created = provisioningService.createFlatTableForEntity(config, "Customer", "sample-data/test.xml");
        assertNotNull(created);
        assertTrue(created.stream().anyMatch(t -> t.equalsIgnoreCase("gs_customer")),
                "Must create flat table named gs_customer");

        javax.sql.DataSource ds = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jt = new org.springframework.jdbc.core.JdbcTemplate(ds);

        // Verify table structure in H2
        List<Map<String, Object>> cols = jt.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'GS_CUSTOMER'"
        );
        assertFalse(cols.isEmpty(), "Columns for gs_customer must exist in DB");
        Set<String> colNames = cols.stream().map(c -> String.valueOf(c.get("COLUMN_NAME")).toLowerCase()).collect(java.util.stream.Collectors.toSet());
        assertTrue(colNames.contains("id"));
        assertTrue(colNames.contains("bnpp_id"));
        assertTrue(colNames.contains("tag_name"));
        assertTrue(colNames.contains("version_number"));
        assertTrue(colNames.contains("status"));
        assertTrue(colNames.contains("raw_payload"));
        assertTrue(colNames.contains("payload_hash"));
    }

    @Test
    @DisplayName("Verify fastSkipFragment skips exactly one record fragment without consuming subsequent records")
    void testFastSkipFragmentSingleRecord() throws Exception {
        String xml = """
                <records>
                    <person id="1"><name>Alice</name></person>
                    <person id="2"><name>Bob</name></person>
                    <person id="3"><name>Charlie</name></person>
                </records>
                """;
        javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newInstance();
        javax.xml.stream.XMLEventReader reader = factory.createXMLEventReader(new java.io.StringReader(xml));

        // Advance to first person
        while (reader.hasNext()) {
            javax.xml.stream.events.XMLEvent ev = reader.peek();
            if (ev.isStartElement() && ev.asStartElement().getName().getLocalPart().equals("person")) {
                break;
            }
            reader.nextEvent();
        }

        // Use reflection to invoke private fastSkipFragment
        java.lang.reflect.Method skipMethod = com.enterprise.xmldbtool.service.StagingXmlStreamingService.class.getDeclaredMethod("fastSkipFragment", javax.xml.stream.XMLEventReader.class, String.class);
        skipMethod.setAccessible(true);
        skipMethod.invoke(stagingStreamingService, reader, "person");

        // After skipping person #1, next start element should be person #2 (NOT end of document)
        boolean foundPerson2 = false;
        while (reader.hasNext()) {
            javax.xml.stream.events.XMLEvent ev = reader.peek();
            if (ev.isStartElement() && ev.asStartElement().getName().getLocalPart().equals("person")) {
                foundPerson2 = true;
                break;
            }
            reader.nextEvent();
        }
        assertTrue(foundPerson2, "After skipping person #1, reader must be positioned at person #2, not consumed to EOF");
    }

    @Test
    @DisplayName("Verify resuming job does not delete flat or canonical data and resumes from checkpoint")
    void testResumeJobDoesNotDeleteDataAndResumesFromCheckpoint() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:resume_test_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        javax.sql.DataSource ds = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jt = new org.springframework.jdbc.core.JdbcTemplate(ds);

        // Provision flat table and canonical table
        jt.execute("CREATE TABLE IF NOT EXISTS gs_person (" +
                "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                "job_execution_id BIGINT, bnpp_id VARCHAR(64), tag_name VARCHAR(100), " +
                "version_number INT DEFAULT 1, record_identifier VARCHAR(100), " +
                "record_action VARCHAR(50), record_date VARCHAR(50), xml_version VARCHAR(50), " +
                "schema_version VARCHAR(50), payload_hash VARCHAR(64), status VARCHAR(30) DEFAULT 'STAGED', " +
                "raw_payload TEXT, extra_attributes TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        jt.execute("CREATE TABLE IF NOT EXISTS Person (" +
                "person_id VARCHAR(100) PRIMARY KEY, " +
                "name VARCHAR(255), " +
                "active_status VARCHAR(50))");

        // Seed 3 records in flat table: records 1 and 2 already normalized, record 3 STAGED
        jt.execute("DELETE FROM Person");
        jt.execute("DELETE FROM gs_person");

        jt.update("INSERT INTO Person (person_id, name, active_status) VALUES ('P1', 'Alice', 'Active')");
        jt.update("INSERT INTO Person (person_id, name, active_status) VALUES ('P2', 'Bob', 'Active')");

        jt.update("INSERT INTO gs_person (job_execution_id, record_identifier, status, raw_payload) VALUES (101, 'P1', 'NORMALIZED', '{\"@id\":\"P1\",\"name\":\"Alice\",\"active_status\":\"Active\"}')");
        jt.update("INSERT INTO gs_person (job_execution_id, record_identifier, status, raw_payload) VALUES (101, 'P2', 'NORMALIZED', '{\"@id\":\"P2\",\"name\":\"Bob\",\"active_status\":\"Active\"}')");
        jt.update("INSERT INTO gs_person (job_execution_id, record_identifier, status, raw_payload) VALUES (101, 'P3', 'STAGED', '{\"@id\":\"P3\",\"name\":\"Charlie\",\"active_status\":\"Active\"}')");

        // Seed checkpoint for job 101: Stage 1 completed (3 records streamed), 2 normalized, stopped during Stage 2
        stagingStreamingService.ensureCheckpointTableExists(jt);
        stagingStreamingService.saveOrUpdateCheckpoint(jt, 101L, "stagedXmlPipelineJob_101",
                "sample-data/synthetic_complex_pfa.xml", "STAGE_1_COMPLETED", 3, 2, "P2", "person", "STOPPED");

        // Prepare resume request
        JobLaunchRequest launchReq = new JobLaunchRequest();
        launchReq.setSourceXmlPath("sample-data/synthetic_complex_pfa.xml");
        launchReq.setRecordRootElement("person");
        launchReq.setTargetTable("Person");
        launchReq.setDbConfig(config);
        launchReq.setResumeFromJobExecutionId(101L);
        launchReq.setResumeFromCheckpoint(true);
        launchReq.setRetryFailedRecords(true);
        launchReq.setStagedPipeline(true);
        launchReq.setMappings(List.of(
                new FieldMapping("@id", "Person", "person_id", "PK"),
                new FieldMapping("name", "Person", "name", "NONE"),
                new FieldMapping("active_status", "Person", "active_status", "NONE")
        ));

        // Launch resume job
        JobLaunchResponse response = batchJobService.launchStagedPipelineJob(launchReq);
        assertNotNull(response);
        assertTrue(response.isSuccess());

        // Wait for asynchronous job completion
        Long execId = response.getJobExecutionId();
        assertNotNull(execId);
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            com.enterprise.xmldbtool.model.JobTelemetryResponse telemetry = batchJobService.getJobTelemetry(execId);
            if (telemetry != null && ("COMPLETED".equals(telemetry.getStatus()) || "FAILED".equals(telemetry.getStatus()))) {
                break;
            }
            Thread.sleep(100);
        }

        // Verify: P1 and P2 in canonical table Person were NOT deleted!
        Integer personCount = jt.queryForObject("SELECT COUNT(*) FROM Person", Integer.class);
        assertEquals(3, personCount, "All 3 persons must exist in canonical table (P1 and P2 not deleted, P3 added)");

        // Verify: P3 was normalized without deleting P1 or P2 from gs_person
        Integer stagedRemaining = jt.queryForObject("SELECT COUNT(*) FROM gs_person WHERE status = 'STAGED'", Integer.class);
        assertEquals(0, stagedRemaining, "All staged records must now be normalized");

        Integer normalizedTotal = jt.queryForObject("SELECT COUNT(*) FROM gs_person WHERE status = 'NORMALIZED'", Integer.class);
        assertEquals(3, normalizedTotal, "All 3 records in gs_person must be NORMALIZED");
    }

    @Test
    @DisplayName("Verify resuming job stopped midway through Stage 1 fast-skips and does not delete already-streamed records")
    void testResumeJobMidwayThroughStage1Streaming() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:resume_stage1_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        javax.sql.DataSource ds = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jt = new org.springframework.jdbc.core.JdbcTemplate(ds);

        // Provision flat table and canonical table for country
        jt.execute("CREATE TABLE IF NOT EXISTS gs_country (" +
                "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                "job_execution_id BIGINT, bnpp_id VARCHAR(64), tag_name VARCHAR(100), " +
                "version_number INT DEFAULT 1, record_identifier VARCHAR(100), " +
                "record_action VARCHAR(50), record_date VARCHAR(50), xml_version VARCHAR(50), " +
                "schema_version VARCHAR(50), payload_hash VARCHAR(64), status VARCHAR(30) DEFAULT 'STAGED', " +
                "raw_payload TEXT, extra_attributes TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        jt.execute("CREATE TABLE IF NOT EXISTS Country (" +
                "country_id VARCHAR(100) PRIMARY KEY, " +
                "name VARCHAR(255))");

        jt.execute("DELETE FROM Country");
        jt.execute("DELETE FROM gs_country");

        // Seed record 1 already streamed during the first run
        jt.update("INSERT INTO gs_country (job_execution_id, record_identifier, status, raw_payload) VALUES (201, '1', 'STAGED', '{\"@id\":\"1\",\"name\":\"country1\"}')");

        // Seed checkpoint for job 201: Stopped at record 1 during STAGE_1_STREAMING
        stagingStreamingService.ensureCheckpointTableExists(jt);
        stagingStreamingService.saveOrUpdateCheckpoint(jt, 201L, "stagedXmlPipelineJob_201",
                "sample-data/test.xml", "STAGE_1_STREAMING", 1, 0, "1", "country", "STOPPED");

        // Prepare resume request
        JobLaunchRequest launchReq = new JobLaunchRequest();
        launchReq.setSourceXmlPath("sample-data/test.xml");
        launchReq.setRecordRootElement("country");
        launchReq.setTargetTable("Country");
        launchReq.setDbConfig(config);
        launchReq.setResumeFromJobExecutionId(201L);
        launchReq.setResumeFromCheckpoint(true);
        launchReq.setRetryFailedRecords(true);
        launchReq.setStagedPipeline(true);
        launchReq.setMappings(List.of(
                new FieldMapping("@id", "Country", "country_id", "PK"),
                new FieldMapping("name", "Country", "name", "NONE")
        ));

        // Launch resume job
        JobLaunchResponse response = batchJobService.launchStagedPipelineJob(launchReq);
        assertNotNull(response);
        assertTrue(response.isSuccess());

        // Wait for asynchronous job completion
        Long execId = response.getJobExecutionId();
        assertNotNull(execId);
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            com.enterprise.xmldbtool.model.JobTelemetryResponse telemetry = batchJobService.getJobTelemetry(execId);
            if (telemetry != null && ("COMPLETED".equals(telemetry.getStatus()) || "FAILED".equals(telemetry.getStatus()))) {
                break;
            }
            Thread.sleep(100);
        }

        // Verify: record 1 was NOT deleted from gs_country, and all records were processed
        Integer flatCount = jt.queryForObject("SELECT COUNT(*) FROM gs_country", Integer.class);
        assertTrue(flatCount >= 20, "gs_country must retain existing records and contain all streamed records");

        // Verify all records in gs_country have reached NORMALIZED status
        Integer unnormalizedCount = jt.queryForObject("SELECT COUNT(*) FROM gs_country WHERE status = 'STAGED'", Integer.class);
        assertEquals(0, unnormalizedCount, "No STAGED records should remain unnormalized");

        // Verify all canonical records present
        Integer countryCount = jt.queryForObject("SELECT COUNT(*) FROM Country", Integer.class);
        assertEquals(20, countryCount, "All 20 countries must be in canonical table");
    }

    @Test
    void testCreateTablesRejectsWithoutIngestionJson() {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:ingest_mand_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest req = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        req.setDbConfig(config);
        req.setSourceXmlPath("sample-data/test.xml");
        req.setRecordRootElement("country");
        // No mappings provided (user did not upload or provide ingestion.json)

        org.springframework.http.ResponseEntity<com.enterprise.xmldbtool.model.SchemaDiscoveryResponse> resp =
                schemaDiscoveryController.createTables(req);

        assertNotNull(resp);
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertFalse(resp.getBody().isSuccess());
        assertTrue(resp.getBody().getMessage().contains("Ingestion JSON file is mandatory"),
                "Message must require ingestion.json");
    }

    @Test
    void testCreateTablesStrictlyFromIngestionJson() {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:ingest_mand_db2;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        com.enterprise.xmldbtool.model.SchemaDiscoveryRequest req = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
        req.setDbConfig(config);
        req.setSourceXmlPath("sample-data/test.xml");
        req.setRecordRootElement("country");
        req.setMappings(List.of(
                new FieldMapping("country/@id", "tbl_country_canonical", "id", "PK", "NONE"),
                new FieldMapping("country/name", "tbl_country_canonical", "name", "NONE", "NONE")
        ));

        org.springframework.http.ResponseEntity<com.enterprise.xmldbtool.model.SchemaDiscoveryResponse> resp =
                schemaDiscoveryController.createTables(req);

        assertNotNull(resp);
        assertEquals(org.springframework.http.HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().isSuccess());
        List<String> created = resp.getBody().getCreatedTables().stream().map(String::toLowerCase).toList();
        assertTrue(created.contains("tbl_country_canonical"), "Canonical table from ingestion.json must be created");
        assertTrue(created.contains("gs_country"), "Flat table gs_country must be created");
    }

    @Test
    void testPfaSampleIngestionPopulatesAllCanonicalTables() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:pfa_canon_test;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        javax.sql.DataSource testDs = dataSourceService.getOrCreateDataSource(config);
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(testDs);

        List<FieldMapping> mappings = List.of(
                // Person master
                new FieldMapping("Person/@id", "person", "person_id", "PK", "NONE"),
                new FieldMapping("Person/@action", "person", "action", "NONE", "NONE"),
                new FieldMapping("Person/@date", "person", "date", "NONE", "NONE"),
                new FieldMapping("Person/Gender", "person", "gender", "NONE", "NONE"),
                // occupation
                new FieldMapping("Occupation/@code", "occupation", "code", "PK", "NONE"),
                new FieldMapping("Occupation/@name", "occupation", "name", "NONE", "NONE"),
                // relationship
                new FieldMapping("Relationship/@code", "relationship", "code", "PK", "NONE"),
                new FieldMapping("Relationship/@name", "relationship", "name", "NONE", "NONE"),
                // sanctionsreference
                new FieldMapping("ReferenceName/@code", "sanctionsreference", "code", "PK", "NONE"),
                new FieldMapping("ReferenceName/@name", "sanctionsreference", "name", "NONE", "NONE"),
                // personname
                new FieldMapping("Person/NameDetails/Name/@NameType", "personname", "name_type", "NONE", "NONE"),
                new FieldMapping("Person/NameDetails/Name/NameValue/FirstName", "personname", "first_name", "NONE", "NONE"),
                new FieldMapping("Person/NameDetails/Name/NameValue/Surname", "personname", "surname", "NONE", "NONE"),
                // persondate
                new FieldMapping("Person/DateDetails/Date/@DateType", "persondate", "date_type", "NONE", "NONE"),
                new FieldMapping("Person/DateDetails/Date/DateValue/@Day", "persondate", "day", "NONE", "NONE"),
                new FieldMapping("Person/DateDetails/Date/DateValue/@Month", "persondate", "month", "NONE", "NONE"),
                new FieldMapping("Person/DateDetails/Date/DateValue/@Year", "persondate", "year", "NONE", "NONE"),
                // personrole
                new FieldMapping("Person/RoleDetail/Roles/@RoleType", "personrole", "role_type", "NONE", "NONE"),
                new FieldMapping("Person/RoleDetail/Roles/OccTitle", "personrole", "occ_title", "NONE", "NONE"),
                // personcountry
                new FieldMapping("Person/CountryDetails/Country/@CountryType", "personcountry", "country_type", "NONE", "NONE"),
                new FieldMapping("Person/CountryDetails/Country/CountryValue/@Code", "personcountry", "code", "NONE", "NONE"),
                // personidentification
                new FieldMapping("Person/IDNumberTypes/ID/@IDType", "personidentification", "id_type", "NONE", "NONE"),
                new FieldMapping("Person/IDNumberTypes/ID/IDValue", "personidentification", "id_value", "NONE", "NONE"),
                // personimage
                new FieldMapping("Person/Images/Image/@URL", "personimage", "url", "NONE", "NONE"),
                // personsource
                new FieldMapping("Person/SourceDescription/Source/@name", "personsource", "source_name", "NONE", "NONE")
        );

        List<String> roots = List.of("Person", "Occupation", "Relationship", "SanctionsReferencesList", "ReferenceName");

        // 1. Provision tables
        provisioningService.provisionDiscoveredTables(config, "sample-data/pfa-sample.xml", roots, Collections.emptyList(), mappings, false);

        // 2. Stream to staging
        stagingStreamingService.streamXmlToStaging("sample-data/pfa-sample.xml", roots, "v1.0", "v1.0.0", 301L, testDs, 100);

        List<Map<String, Object>> allTablesInDb = jdbcTemplate.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'");
        System.out.println("ALL DB TABLES: " + allTablesInDb);
        for (Map<String, Object> row : allTablesInDb) {
            String tName = (String) row.get("TABLE_NAME");
            try {
                long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tName, Long.class);
                System.out.println("DEBUG TABLE " + tName + " row count = " + c);
                if (tName.equalsIgnoreCase("GS_SANCTIONSREFERENCESLIST") && c > 0) {
                    Map<String, Object> firstRow = jdbcTemplate.queryForMap("SELECT * FROM " + tName + " LIMIT 1");
                    System.out.println("DEBUG GS_SANCTIONSREFERENCESLIST raw_payload: " + firstRow.get("raw_payload"));
                }
            } catch (Exception ignored) {}
        }

        // 3. Normalize to canonical
        List<String> tableOrder = List.of("person", "occupation", "relationship", "sanctionsreference",
                "personname", "persondate", "personrole", "personcountry", "personidentification", "personimage", "personsource");

        DynamicSchemaResolutionService.NormalizationSummary normSummary = schemaResolutionService.normalizeStagingToCanonical(
                301L, testDs, mappings, tableOrder, "person", 100
        );

        System.out.println("Normalized count: " + normSummary.normalizedCount() + ", failed: " + normSummary.failedCount());

        for (String tbl : tableOrder) {
            long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tbl, Long.class);
            System.out.println("Table " + tbl + " count = " + count);
            assertTrue(count > 0, "Table '" + tbl + "' should NOT be empty! Current count: " + count);
        }
    }
}

