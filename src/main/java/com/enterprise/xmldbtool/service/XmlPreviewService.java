package com.enterprise.xmldbtool.service;

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
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    public XmlPreviewService(DynamicXmlUnmarshaller unmarshaller) {
        this.unmarshaller = unmarshaller;
    }

    /**
     * Parses the first N (e.g. 5) XML records using StAX and applies mapping transformations
     * across single or multiple target tables (One-to-Many).
     */
    public DataPreviewResponse generatePreview(DataPreviewRequest request) {
        DataPreviewResponse response = new DataPreviewResponse();
        int limit = request.getPreviewLimit() > 0 ? request.getPreviewLimit() : 5;
        String rootTag = request.getRecordRootElement() != null ? request.getRecordRootElement() : "record";

        DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor(
                request.getTargetTable(),
                request.getMappings()
        );

        Map<String, List<Map<String, Object>>> multiTableRows = new LinkedHashMap<>();
        Map<String, Set<String>> multiTableCols = new LinkedHashMap<>();

        // Pre-register all mapped tables and columns
        for (FieldMapping m : request.getMappings()) {
            String tbl = m.getTargetTable() != null && !m.getTargetTable().trim().isEmpty()
                    ? m.getTargetTable().trim() : request.getTargetTable();
            if (tbl != null && m.getTargetColumn() != null && !m.getTargetColumn().trim().isEmpty()) {
                multiTableCols.computeIfAbsent(tbl, k -> new LinkedHashSet<>()).add(m.getTargetColumn().trim());
                multiTableRows.computeIfAbsent(tbl, k -> new ArrayList<>());
            }
        }

        int recordsProcessed = 0;

        try (InputStream is = openStreamForSource(request.getSourceXmlPath())) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

            XMLEventReader reader = factory.createXMLEventReader(is);

            while (reader.hasNext() && recordsProcessed < limit) {
                XMLEvent event = reader.peek();
                if (event != null && event.isStartElement()) {
                    StartElement startElement = event.asStartElement();
                    if (startElement.getName().getLocalPart().equalsIgnoreCase(rootTag)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> xmlMap = unmarshaller.parseFragmentFromEventReader(reader);

                        DatabaseRowPayload rowPayload = processor.process(xmlMap);
                        if (rowPayload != null && !rowPayload.getMultiTablePayloads().isEmpty()) {
                            for (Map.Entry<String, Map<String, Object>> entry : rowPayload.getMultiTablePayloads().entrySet()) {
                                String tbl = entry.getKey();
                                Map<String, Object> cols = entry.getValue();

                                multiTableRows.computeIfAbsent(tbl, k -> new ArrayList<>()).add(cols);
                                multiTableCols.computeIfAbsent(tbl, k -> new LinkedHashSet<>()).addAll(cols.keySet());
                            }
                            recordsProcessed++;
                        }
                        continue;
                    }
                }
                reader.nextEvent();
            }

            // Populate tablePreviews map
            Map<String, TablePreviewData> tablePreviews = new LinkedHashMap<>();
            String firstTable = null;

            for (Map.Entry<String, Set<String>> entry : multiTableCols.entrySet()) {
                String tbl = entry.getKey();
                if (firstTable == null) firstTable = tbl;

                List<String> cols = new ArrayList<>(entry.getValue());
                List<Map<String, Object>> rows = multiTableRows.getOrDefault(tbl, new ArrayList<>());
                String sql = buildPreparedSqlTemplate(tbl, entry.getValue());

                tablePreviews.put(tbl, new TablePreviewData(tbl, cols, rows, sql));
            }

            response.setSuccess(true);
            response.setMessage("Successfully generated preview for " + recordsProcessed + " XML records across " + tablePreviews.size() + " tables.");
            response.setTablePreviews(tablePreviews);

            // Set primary table defaults for backwards compatibility
            if (firstTable != null && tablePreviews.containsKey(firstTable)) {
                TablePreviewData firstData = tablePreviews.get(firstTable);
                response.setColumns(firstData.getColumns());
                response.setPreviewRows(firstData.getRows());
                response.setGeneratedSqlTemplate(firstData.getSqlTemplate());
            }

        } catch (Exception ex) {
            log.error("Failed to generate preview: {}", ex.getMessage(), ex);
            response.setSuccess(false);
            response.setMessage("Preview generation error: " + ex.getMessage());
        }

        return response;
    }

    private String buildPreparedSqlTemplate(String tableName, Set<String> columns) {
        if (tableName == null || columns == null || columns.isEmpty()) {
            return "-- No mapping configured --";
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (\n    ");
        StringBuilder params = new StringBuilder(" VALUES (\n    ");

        int i = 0;
        for (String col : columns) {
            if (i > 0) {
                sql.append(", ");
                params.append(", ");
                if (i % 5 == 0) {
                    sql.append("\n    ");
                    params.append("\n    ");
                }
            }
            sql.append(col);
            params.append(":").append(col);
            i++;
        }
        sql.append("\n)");
        params.append("\n);");
        return sql.append(params).toString();
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
}
