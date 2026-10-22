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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DynamicXmlIngestionTest {

    @Autowired
    private DynamicXmlUnmarshaller unmarshaller;

    @Autowired
    private XmlSchemaDiscoveryService discoveryService;

    @Autowired
    private XmlPreviewService previewService;

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

        boolean hasIdAttr = elements.stream().anyMatch(e -> e.getName().equals("@id") && e.isAttribute());
        boolean hasSkuTag = elements.stream().anyMatch(e -> e.getName().equals("sku") && !e.isAttribute());

        assertTrue(hasIdAttr, "Should have discovered @id attribute");
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
}
