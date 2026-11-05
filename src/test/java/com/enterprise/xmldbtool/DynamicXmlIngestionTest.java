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
import com.enterprise.xmldbtool.service.DynamicBatchJobService;
import com.enterprise.xmldbtool.service.DynamicDataSourceService;
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
import java.util.LinkedHashMap;
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
        assertTrue(tableNames.contains("personflat"), "Must contain PersonFlat flattened table");

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
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM PersonFlat")) {
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
}

