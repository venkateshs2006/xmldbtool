package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.model.DiscoveredElement;
import com.enterprise.xmldbtool.model.SchemaDiscoveryRequest;
import com.enterprise.xmldbtool.model.SchemaDiscoveryResponse;
import com.enterprise.xmldbtool.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class XmlSchemaDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(XmlSchemaDiscoveryService.class);
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();
    private final DynamicDataSourceService dataSourceService;

    public XmlSchemaDiscoveryService(DynamicDataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    /**
     * Executes schema discovery:
     * 1. Uses lightweight StAX to scan first 1,000 lines of XML for tags and attributes (@attr).
     * 2. Simultaneously queries target database metadata for tables and columns.
     */
    public SchemaDiscoveryResponse discoverSchema(SchemaDiscoveryRequest request) {
        SchemaDiscoveryResponse response = new SchemaDiscoveryResponse();

        try {
            // 1. Scan XML using lightweight StAX (first 1,000 lines)
            List<DiscoveredElement> xmlElements = discoverXmlElements(request.getSourceXmlPath(), request.getSampleLinesLimit());
            response.setDiscoveredXmlElements(xmlElements);

            // 2. Query target DB schema metadata
            if (request.getDbConfig() != null && request.getDbConfig().getUrl() != null) {
                List<TableMetadata> tables = dataSourceService.retrieveDatabaseSchema(request.getDbConfig());
                response.setDiscoveredTables(tables);
            }

            response.setSuccess(true);
            response.setMessage("Schema discovery completed successfully.");
        } catch (Exception ex) {
            log.error("Schema discovery failed: {}", ex.getMessage(), ex);
            response.setSuccess(false);
            response.setMessage("Schema discovery error: " + ex.getMessage());
        }

        return response;
    }

    /**
     * Reads first N lines of XML and uses StAX to discover unique tags and @attributes.
     */
    public List<DiscoveredElement> discoverXmlElements(String sourcePath, int sampleLinesLimit) throws Exception {
        Map<String, DiscoveredElement> discoveredMap = new LinkedHashMap<>();

        // Read up to sampleLinesLimit into a memory buffer
        StringBuilder xmlSampleBuffer = new StringBuilder();
        try (BufferedReader reader = openReaderForSource(sourcePath)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < sampleLinesLimit) {
                xmlSampleBuffer.append(line).append("\n");
                count++;
            }
        }

        // Close unclosed tags if needed by wrapping in a synthetic root or parse as fragment stream
        String sampleXml = xmlSampleBuffer.toString();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        try (StringReader sr = new StringReader(sampleXml)) {
            XMLStreamReader streamReader = factory.createXMLStreamReader(sr);

            String currentTag = null;
            StringBuilder textBuffer = new StringBuilder();

            while (streamReader.hasNext()) {
                try {
                    int event = streamReader.next();

                    if (event == XMLStreamConstants.START_ELEMENT) {
                        currentTag = streamReader.getLocalName();
                        textBuffer.setLength(0);

                        // Extract XML attributes flattened with @
                        int attrCount = streamReader.getAttributeCount();
                        for (int i = 0; i < attrCount; i++) {
                            String attrName = "@" + streamReader.getAttributeLocalName(i);
                            String attrVal = streamReader.getAttributeValue(i);

                            discoveredMap.computeIfAbsent(attrName, k -> new DiscoveredElement(
                                    attrName,
                                    true,
                                    attrVal,
                                    detectDataType(attrVal)
                            ));
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                        if (!streamReader.isWhiteSpace()) {
                            textBuffer.append(streamReader.getText());
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String closedTag = streamReader.getLocalName();
                        String text = textBuffer.toString().trim();

                        if (!text.isEmpty()) {
                            final String sampleVal = text;
                            discoveredMap.computeIfAbsent(closedTag, k -> new DiscoveredElement(
                                    closedTag,
                                    false,
                                    sampleVal,
                                    detectDataType(sampleVal)
                            ));
                        }
                        textBuffer.setLength(0);
                    }
                } catch (Exception streamEx) {
                    // Reached end of truncated 1,000 lines buffer cleanly
                    log.debug("Reached end of 1000 lines sample stream: {}", streamEx.getMessage());
                    break;
                }
            }
        }

        return new ArrayList<>(discoveredMap.values());
    }

    private BufferedReader openReaderForSource(String sourcePath) throws Exception {
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            throw new IllegalArgumentException("XML source path cannot be blank.");
        }

        Path directPath = Paths.get(sourcePath);
        if (Files.exists(directPath)) {
            return Files.newBufferedReader(directPath, StandardCharsets.UTF_8);
        }

        Resource res = resourceLoader.getResource(sourcePath);
        if (res.exists()) {
            return new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8));
        }

        // Try relative to workspace
        Path relativePath = Paths.get("/home/sv/Projects/xmldbtool", sourcePath);
        if (Files.exists(relativePath)) {
            return Files.newBufferedReader(relativePath, StandardCharsets.UTF_8);
        }

        throw new IllegalArgumentException("Unable to locate XML source file: " + sourcePath);
    }

    private String detectDataType(String value) {
        if (value == null || value.trim().isEmpty()) return "VARCHAR";
        value = value.trim();
        if (value.matches("^-?\\d+$")) return "BIGINT";
        if (value.matches("^-?\\d+(\\.\\d+)?$")) return "NUMERIC";
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return "BOOLEAN";
        if (value.matches("^\\d{4}-\\d{2}-\\d{2}.*")) return "TIMESTAMP";
        return "VARCHAR";
    }
}
