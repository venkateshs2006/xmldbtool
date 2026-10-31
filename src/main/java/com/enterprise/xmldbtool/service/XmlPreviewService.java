package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter;
import com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor;
import com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller;
import com.enterprise.xmldbtool.model.DataPreviewRequest;
import com.enterprise.xmldbtool.model.DataPreviewResponse;
import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.model.TablePreviewData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class XmlPreviewService {

    private static final Logger log = LoggerFactory.getLogger(XmlPreviewService.class);
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();
    private final DynamicXmlUnmarshaller unmarshaller;
    private final XmlSchemaDiscoveryService discoveryService;

    public XmlPreviewService(DynamicXmlUnmarshaller unmarshaller, XmlSchemaDiscoveryService discoveryService) {
        this.unmarshaller = unmarshaller;
        this.discoveryService = discoveryService;
    }

    public static final long DEFAULT_MAX_PREVIEW_FILE_SIZE_BYTES = 100L * 1024 * 1024; // 100 MB
    private long maxPreviewSizeLimit = DEFAULT_MAX_PREVIEW_FILE_SIZE_BYTES;

    public void setMaxPreviewSizeLimit(long maxPreviewSizeLimit) {
        this.maxPreviewSizeLimit = maxPreviewSizeLimit;
    }

    public long getMaxPreviewSizeLimit() {
        return maxPreviewSizeLimit;
    }

    /**
     * Parses the first N (e.g. 5) XML records using StAX and applies mapping transformations
     * across single or multiple target tables (One-to-Many).
     * If source XML file size exceeds 100MB, skips sample record parsing for optimal performance.
     */
    public DataPreviewResponse generatePreview(DataPreviewRequest request) {
        DataPreviewResponse response = new DataPreviewResponse();
        int limit = request.getPreviewLimit() > 0 ? request.getPreviewLimit() : 5;
        String rootTag = request.getRecordRootElement() != null ? request.getRecordRootElement() : "record";
        String docRoot = discoveryService.detectDocumentRootElement(request.getSourceXmlPath());
        if (docRoot != null && !docRoot.trim().isEmpty()) {
            if ("record".equalsIgnoreCase(rootTag)) {
                boolean hasNonRecordTags = request.getMappings() != null && request.getMappings().stream().anyMatch(m -> {
                    String f = m.getXmlField() != null ? m.getXmlField().toLowerCase() : "";
                    return !f.startsWith("record/") && !f.startsWith("record/@") && !f.equals("record") && !f.equals("@record");
                });
                if (hasNonRecordTags && !docRoot.equalsIgnoreCase(rootTag)) {
                    rootTag = docRoot;
                }
            }
        }

        long fileSize = discoveryService.getFileSizeBytes(request.getSourceXmlPath());
        response.setFileSizeBytes(fileSize);
        response.setFileSizeFormatted(XmlSchemaDiscoveryService.formatFileSize(fileSize));

        Map<String, Object> rootAttrs = (fileSize > this.maxPreviewSizeLimit)
                ? Collections.emptyMap()
                : discoveryService.extractDocumentRootAttributes(request.getSourceXmlPath(), rootTag);

        List<String> tableOrder = DynamicJdbcBatchWriter.determineTableOrder(
                request.getTableOrder(),
                request.getMappings(),
                request.getTargetTable()
        );

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor(
                request.getTargetTable(),
                request.getMappings(),
                rootAttrs,
                tableOrder
        );

        Map<String, List<Map<String, Object>>> multiTableRows = new LinkedHashMap<>();
        Map<String, Set<String>> multiTableCols = new LinkedHashMap<>();

        // Pre-register all mapped tables and columns in tableOrder sequence
        for (String tbl : tableOrder) {
            multiTableCols.put(tbl, new LinkedHashSet<>());
            multiTableRows.put(tbl, new ArrayList<>());
        }

        for (FieldMapping m : request.getMappings()) {
            String tbl = m.getTargetTable() != null && !m.getTargetTable().trim().isEmpty()
                    ? m.getTargetTable().trim() : request.getTargetTable();
            if (tbl != null && m.getTargetColumn() != null && !m.getTargetColumn().trim().isEmpty()) {
                multiTableCols.computeIfAbsent(tbl, k -> new LinkedHashSet<>()).add(m.getTargetColumn().trim());
                multiTableRows.computeIfAbsent(tbl, k -> new ArrayList<>());
            }
        }

        // If file size exceeds 100MB threshold, do NOT read the actual XML file.
        // Instead, generate simulated dummy data based on the tags.
        if (fileSize > this.maxPreviewSizeLimit) {
            String sizeStr = response.getFileSizeFormatted();
            log.info("XML file size is {} (> 100 MB threshold). Generating dummy data based on tags without reading actual file.", sizeStr);
            response.setPreviewSkipped(true);
            response.setDummyData(true);
            response.setSkipReason("XML file size is " + sizeStr + " (> 100 MB threshold). Displaying tag-generated dummy preview data without reading the actual file.");

            int dummyCount = limit > 0 ? limit : 5;
            Map<String, TablePreviewData> tablePreviews = generateSimulatedMultiTablePreviews(tableOrder, request.getMappings(), processor, dummyCount);

            response.setSuccess(true);
            response.setMessage("XML file size is " + sizeStr + " (> 100 MB). Displaying tag-generated dummy preview data (5 records) without reading the actual file.");
            response.setTablePreviews(tablePreviews);
            response.setTableOrder(new ArrayList<>(tablePreviews.keySet()));

            if (!tablePreviews.isEmpty()) {
                String firstTable = tablePreviews.keySet().iterator().next();
                TablePreviewData firstData = tablePreviews.get(firstTable);
                response.setColumns(firstData.getColumns());
                response.setPreviewRows(firstData.getRows());
                response.setGeneratedSqlTemplate(firstData.getSqlTemplate());
            }

            return response;
        }

        int recordsProcessed = 0;

        try (InputStream is = openStreamForSource(request.getSourceXmlPath())) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

            XMLEventReader reader = factory.createXMLEventReader(is);

            while (reader.hasNext() && recordsProcessed < limit) {
                try {
                    XMLEvent event = reader.peek();
                    if (event != null && event.isStartElement()) {
                        StartElement startElement = event.asStartElement();
                        if (startElement.getName().getLocalPart().equalsIgnoreCase(rootTag)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> xmlMap = unmarshaller.parseFragmentFromEventReader(reader);

                            // Merge document root attributes so they are present in preview
                            if (rootAttrs != null && !rootAttrs.isEmpty()) {
                                for (Map.Entry<String, Object> e : rootAttrs.entrySet()) {
                                    xmlMap.putIfAbsent(e.getKey(), e.getValue());
                                }
                            }

                            DatabaseRowPayload rowPayload = processor.process(xmlMap);
                            if (rowPayload != null && !rowPayload.getMultiTableRecords().isEmpty()) {
                                for (Map.Entry<String, List<Map<String, Object>>> entry : rowPayload.getMultiTableRecords().entrySet()) {
                                    String tbl = entry.getKey();
                                    List<Map<String, Object>> rows = entry.getValue();
                                    if (rows != null && !rows.isEmpty()) {
                                        for (Map<String, Object> cols : rows) {
                                            if (multiTableRows.computeIfAbsent(tbl, k -> new ArrayList<>()).size() < limit) {
                                                multiTableRows.get(tbl).add(cols);
                                                multiTableCols.computeIfAbsent(tbl, k -> new LinkedHashSet<>()).addAll(cols.keySet());
                                            }
                                        }
                                    }
                                }
                                recordsProcessed++;
                            }
                            continue;
                        }
                    }
                    reader.nextEvent();
                } catch (XMLStreamException xse) {
                    log.warn("XML parsing notice at row {}: {}. Finishing preview with records extracted so far.", recordsProcessed, xse.getMessage());
                    break;
                }
            }

            // Populate tablePreviews map strictly in tag order sequence
            Map<String, TablePreviewData> tablePreviews = new LinkedHashMap<>();
            String firstTable = null;

            // 1. First populate configured tableOrder
            for (String tbl : tableOrder) {
                Set<String> colSet = multiTableCols.get(tbl);
                if (colSet != null && !colSet.isEmpty()) {
                    if (firstTable == null) firstTable = tbl;
                    List<String> cols = new ArrayList<>(colSet);
                    List<Map<String, Object>> rows = multiTableRows.getOrDefault(tbl, new ArrayList<>());
                    String sql = buildPreparedSqlTemplate(tbl, colSet);
                    tablePreviews.put(tbl, new TablePreviewData(tbl, cols, rows, sql));
                }
            }

            // 2. Any additional tables (if any)
            for (Map.Entry<String, Set<String>> entry : multiTableCols.entrySet()) {
                String tbl = entry.getKey();
                if (!tablePreviews.containsKey(tbl) && !entry.getValue().isEmpty()) {
                    if (firstTable == null) firstTable = tbl;
                    List<String> cols = new ArrayList<>(entry.getValue());
                    List<Map<String, Object>> rows = multiTableRows.getOrDefault(tbl, new ArrayList<>());
                    String sql = buildPreparedSqlTemplate(tbl, entry.getValue());
                    tablePreviews.put(tbl, new TablePreviewData(tbl, cols, rows, sql));
                }
            }

            // If zero records matched the fragment rootTag or file was partially unclosed, fallback to simulated preview
            if (tablePreviews.isEmpty()) {
                log.info("No matching records found for root tag '{}'; generating simulated preview for configured mappings.", rootTag);
                tablePreviews = generateSimulatedMultiTablePreviews(tableOrder, request.getMappings(), processor, limit > 0 ? limit : 5);
                response.setDummyData(true);
                response.setMessage("Showing simulated preview data for mapped columns (root tag '" + rootTag + "' fragment preview).");
            } else {
                response.setMessage("Successfully generated preview for " + recordsProcessed + " XML records across " + tablePreviews.size() + " tables.");
            }

            response.setSuccess(true);
            response.setTablePreviews(tablePreviews);
            response.setTableOrder(new ArrayList<>(tablePreviews.keySet()));

            // Set primary table defaults for backwards compatibility
            if (!tablePreviews.isEmpty()) {
                String primaryTbl = firstTable != null && tablePreviews.containsKey(firstTable) ? firstTable : tablePreviews.keySet().iterator().next();
                TablePreviewData firstData = tablePreviews.get(primaryTbl);
                response.setColumns(firstData.getColumns());
                response.setPreviewRows(firstData.getRows());
                response.setGeneratedSqlTemplate(firstData.getSqlTemplate());
            }

        } catch (Exception ex) {
            log.error("Failed to generate preview from XML stream: {}", ex.getMessage());
            Map<String, TablePreviewData> simulatedPreviews = generateSimulatedMultiTablePreviews(tableOrder, request.getMappings(), processor, limit > 0 ? limit : 5);
            response.setSuccess(true);
            response.setDummyData(true);
            response.setMessage("XML parsing notice: " + ex.getMessage() + ". Showing simulated preview data based on your tag mappings.");
            response.setTablePreviews(simulatedPreviews);
            response.setTableOrder(new ArrayList<>(simulatedPreviews.keySet()));

            if (!simulatedPreviews.isEmpty()) {
                String firstTbl = simulatedPreviews.keySet().iterator().next();
                TablePreviewData firstData = simulatedPreviews.get(firstTbl);
                response.setColumns(firstData.getColumns());
                response.setPreviewRows(firstData.getRows());
                response.setGeneratedSqlTemplate(firstData.getSqlTemplate());
            }
        }

        return response;
    }

    /**
     * Generates simulated multi-table preview data for the configured mappings.
     */
    public Map<String, TablePreviewData> generateSimulatedMultiTablePreviews(
            List<String> tableOrder,
            List<FieldMapping> mappings,
            DynamicXmlItemProcessor processor,
            int count) {

        Map<String, List<Map<String, Object>>> multiTableRows = new LinkedHashMap<>();
        Map<String, Set<String>> multiTableCols = new LinkedHashMap<>();

        for (int r = 0; r < count; r++) {
            Map<String, Object> dummyXmlRecord = new LinkedHashMap<>();

            for (FieldMapping m : mappings) {
                String xmlField = m.getXmlField();
                if (xmlField != null && !xmlField.trim().isEmpty()) {
                    Object dummyVal = generateDummyValueForTag(xmlField, m.getTargetColumn(), r);
                    dummyXmlRecord.put(xmlField, dummyVal);

                    if (xmlField.contains("/@")) {
                        String bare = "@" + xmlField.substring(xmlField.indexOf("/@") + 2);
                        dummyXmlRecord.putIfAbsent(bare, dummyVal);
                        dummyXmlRecord.putIfAbsent(bare.substring(1), dummyVal);
                    } else if (xmlField.contains("/")) {
                        String bare = xmlField.substring(xmlField.indexOf("/") + 1);
                        dummyXmlRecord.putIfAbsent(bare, dummyVal);
                    } else if (xmlField.startsWith("@")) {
                        dummyXmlRecord.putIfAbsent(xmlField.substring(1), dummyVal);
                    }
                }
            }

            try {
                DatabaseRowPayload rowPayload = processor.process(dummyXmlRecord);
                if (rowPayload != null && !rowPayload.getMultiTablePayloads().isEmpty()) {
                    for (Map.Entry<String, Map<String, Object>> entry : rowPayload.getMultiTablePayloads().entrySet()) {
                        String tbl = entry.getKey();
                        Map<String, Object> cols = entry.getValue();

                        multiTableRows.computeIfAbsent(tbl, k -> new ArrayList<>()).add(cols);
                        multiTableCols.computeIfAbsent(tbl, k -> new LinkedHashSet<>()).addAll(cols.keySet());
                    }
                }
            } catch (Exception ex) {
                log.warn("Error processing simulated dummy record: {}", ex.getMessage());
            }
        }

        Map<String, TablePreviewData> tablePreviews = new LinkedHashMap<>();
        for (String tbl : tableOrder) {
            Set<String> colSet = multiTableCols.get(tbl);
            if (colSet != null && !colSet.isEmpty()) {
                List<String> cols = new ArrayList<>(colSet);
                List<Map<String, Object>> rows = multiTableRows.getOrDefault(tbl, new ArrayList<>());
                String sql = buildPreparedSqlTemplate(tbl, colSet);
                tablePreviews.put(tbl, new TablePreviewData(tbl, cols, rows, sql));
            }
        }

        for (Map.Entry<String, Set<String>> entry : multiTableCols.entrySet()) {
            String tbl = entry.getKey();
            if (!tablePreviews.containsKey(tbl) && !entry.getValue().isEmpty()) {
                List<String> cols = new ArrayList<>(entry.getValue());
                List<Map<String, Object>> rows = multiTableRows.getOrDefault(tbl, new ArrayList<>());
                String sql = buildPreparedSqlTemplate(tbl, entry.getValue());
                tablePreviews.put(tbl, new TablePreviewData(tbl, cols, rows, sql));
            }
        }

        return tablePreviews;
    }

    private String buildPreparedSqlTemplate(String tableName, Set<String> columns) {
        if (tableName == null || columns == null || columns.isEmpty()) {
            return "-- No mapping configured --";
        }
        Set<String> pks = DynamicJdbcBatchWriter.inferPrimaryKeys(tableName, columns);
        return DynamicJdbcBatchWriter.constructUpsertSql(tableName, columns, pks, "PostgreSQL");
    }

    private InputStream openStreamForSource(String sourcePath) throws Exception {
        Path directPath = Paths.get(sourcePath);
        if (Files.exists(directPath)) {
            return Files.newInputStream(directPath);
        }
        Resource res = resourceLoader.getResource(sourcePath);
        if (res.exists()) {
            return res.getInputStream();
        }
        Path relativePath = Paths.get("/home/sv/Projects/xmldbtool", sourcePath);
        if (Files.exists(relativePath)) {
            return Files.newInputStream(relativePath);
        }
        throw new IllegalArgumentException("Unable to open source XML: " + sourcePath);
    }

    /**
     * Generates realistic simulated dummy data for a mapped XML tag when file size > 100MB,
     * allowing the user to preview data transformations without reading the actual file.
     */
    public static Object generateDummyValueForTag(String xmlField, String targetCol, int rowIndex) {
        String key = (xmlField != null ? xmlField : (targetCol != null ? targetCol : "")).toLowerCase();
        String norm = key.replace("@", "").replace("-", "").replace("_", "").toLowerCase();

        // 1. Export ID / Root Document IDs
        if (norm.contains("export")) {
            return "EXP-2026-X00" + (rowIndex + 1);
        }

        // 2. IDs and primary keys
        if (norm.endsWith("id") || norm.equals("id") || norm.contains("identifier") || (norm.contains("code") && !norm.contains("location") && !norm.contains("supplier") && !norm.contains("sku"))) {
            if (norm.contains("uuid") || norm.contains("guid")) {
                return java.util.UUID.nameUUIDFromBytes(("dummy-row-" + rowIndex).getBytes()).toString();
            }
            return String.valueOf(1001 + rowIndex);
        }

        // 3. Status
        if (norm.contains("status") || norm.contains("state")) {
            String[] statuses = {"ACTIVE", "PENDING", "COMPLETED", "ACTIVE", "PROCESSING"};
            return statuses[rowIndex % statuses.length];
        }

        // 4. Priority
        if (norm.contains("priority")) {
            String[] priorities = {"HIGH", "MEDIUM", "CRITICAL", "LOW", "NORMAL"};
            return priorities[rowIndex % priorities.length];
        }

        // 5. Region
        if (norm.contains("region")) {
            String[] regions = {"US-EAST", "EU-WEST", "APAC-SOUTH", "US-WEST", "EU-CENTRAL"};
            return regions[rowIndex % regions.length];
        }

        // 6. SKU / Product SKU
        if (norm.contains("sku")) {
            String[] skus = {"SKU-99214-A", "SKU-88320-B", "SKU-77401-C", "SKU-66512-D", "SKU-55419-E"};
            return skus[rowIndex % skus.length];
        }

        // 7. Product Name / Title / Description
        if (norm.contains("productname") || norm.contains("product") || norm.contains("name") || norm.contains("title") || norm.contains("desc")) {
            String[] names = {
                "Enterprise Server Blade Gen11",
                "100GbE QSFP28 Optical Transceiver",
                "PCIe NVMe Enterprise SSD 7.68TB",
                "Smart PDU Rack Power Distribution 30A",
                "DDR5 Registered ECC Memory 64GB"
            };
            return names[rowIndex % names.length];
        }

        // 8. Category / Department
        if (norm.contains("category") || norm.contains("dept") || (norm.contains("type") && !norm.contains("datatype"))) {
            String[] cats = {"Hardware", "Networking", "Storage", "Power", "Memory"};
            return cats[rowIndex % cats.length];
        }

        // 9. Price / Cost / Amount / Rate
        if (norm.contains("price") || norm.contains("cost") || norm.contains("amount") || norm.contains("rate") || norm.contains("total")) {
            String[] prices = {"4899.50", "450.00", "1120.75", "780.00", "320.50"};
            return prices[rowIndex % prices.length];
        }

        // 10. Quantity / Count / Units
        if (norm.contains("quantity") || norm.contains("qty") || norm.contains("count") || norm.contains("units") || norm.contains("stock")) {
            String[] quantities = {"12", "64", "30", "8", "45"};
            return quantities[rowIndex % quantities.length];
        }

        // 11. Warehouse / Location
        if (norm.contains("warehouse") || norm.contains("location")) {
            String[] locations = {"WH-VA-04", "WH-FRA-01", "WH-SIN-02", "WH-CA-03", "WH-LON-01"};
            return locations[rowIndex % locations.length];
        }

        // 12. Supplier / Vendor
        if (norm.contains("supplier") || norm.contains("vendor")) {
            String[] suppliers = {"SUPP-DELL-88", "SUPP-CISCO-12", "SUPP-SAMS-05", "SUPP-APC-22", "SUPP-HP-14"};
            return suppliers[rowIndex % suppliers.length];
        }

        // 13. Date / Time / Timestamp
        if (norm.contains("date") || norm.contains("time") || norm.contains("at") || norm.contains("created") || norm.contains("timestamp")) {
            String[] timestamps = {
                "2026-10-01 09:15:00",
                "2026-10-02 11:30:20",
                "2026-10-03 14:45:10",
                "2026-10-04 08:20:00",
                "2026-10-05 16:00:00"
            };
            return timestamps[rowIndex % timestamps.length];
        }

        // 14. Email
        if (norm.contains("email") || norm.contains("mail")) {
            return "user" + (rowIndex + 1) + "@enterprise.com";
        }

        // 15. Boolean
        if (norm.contains("bool") || norm.contains("flag") || norm.startsWith("is") || norm.startsWith("has")) {
            return rowIndex % 2 == 0 ? "true" : "false";
        }

        // Fallback: clean tag label with index
        String cleanTag = xmlField != null ? xmlField.replace("@", "").replace("/", "_") : "val";
        return cleanTag + "_" + (rowIndex + 1);
    }
}
