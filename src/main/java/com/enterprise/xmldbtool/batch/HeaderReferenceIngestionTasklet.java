package com.enterprise.xmldbtool.batch;

import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.FieldMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import javax.sql.DataSource;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Lightweight, streaming StAX Tasklet that ingests Header Reference / Lookup Tables
 * (e.g., CountryList, OccupationList, RelationshipList, SanctionsReferencesList)
 * directly from the beginning of large XML documents (up to 10GB+) before the main
 * record element collection is streamed, consuming negligible heap memory.
 */
public class HeaderReferenceIngestionTasklet implements Tasklet, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(HeaderReferenceIngestionTasklet.class);

    private final String sourceXmlPath;
    private final String recordRootElement;
    private final List<FieldMapping> mappings;
    private final DataSource dataSource;
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    private StepExecution stepExecution;

    public HeaderReferenceIngestionTasklet(String sourceXmlPath,
                                          String recordRootElement,
                                          List<FieldMapping> mappings,
                                          DataSource dataSource) {
        this.sourceXmlPath = sourceXmlPath;
        this.recordRootElement = recordRootElement != null ? recordRootElement : "Person";
        this.mappings = mappings != null ? mappings : Collections.emptyList();
        this.dataSource = dataSource;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.stepExecution = stepExecution;
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        return ExitStatus.COMPLETED;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        if (mappings.isEmpty()) {
            return RepeatStatus.FINISHED;
        }

        // Identify which tables are lookup / reference tables (not main Person and not child Person* tables)
        Set<String> lookupTables = new LinkedHashSet<>();
        Map<String, List<FieldMapping>> lookupMappings = new LinkedHashMap<>();

        for (FieldMapping m : mappings) {
            String tbl = m.getTargetTable();
            if (tbl == null || tbl.trim().isEmpty()) continue;
            String norm = tbl.trim().toLowerCase();
            boolean isLookup = norm.endsWith("list") || norm.endsWith("lists")
                    || (!norm.equals("person") && !norm.startsWith("person")
                        && !norm.equals(recordRootElement.toLowerCase()));
            if (isLookup) {
                lookupTables.add(tbl);
                lookupMappings.computeIfAbsent(tbl, k -> new ArrayList<>()).add(m);
            }
        }

        if (lookupTables.isEmpty()) {
            log.info("No header reference / lookup tables identified for pre-streaming ingestion.");
            return RepeatStatus.FINISHED;
        }

        log.info("Streaming header reference lists for target tables: {}", lookupTables);

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        Map<String, List<Map<String, Object>>> extractedTableRows = new LinkedHashMap<>();
        for (String tbl : lookupTables) {
            extractedTableRows.put(tbl, new ArrayList<>());
        }

        try (BufferedReader reader = openReader(sourceXmlPath)) {
            XMLStreamReader stream = factory.createXMLStreamReader(reader);
            Deque<String> tagStack = new ArrayDeque<>();
            Map<String, Object> currentAttributes = new LinkedHashMap<>();
            StringBuilder currentText = new StringBuilder();
            String docRoot = null;

            while (stream.hasNext()) {
                int event = stream.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    String tag = stream.getLocalName();
                    if (docRoot == null) {
                        docRoot = tag;
                    }

                    // If we reached Records or the main record element, stop header scan immediately
                    if (tag.equalsIgnoreCase("records") || (tag.equalsIgnoreCase(recordRootElement) && !recordRootElement.toLowerCase().endsWith("list"))) {
                        log.info("Reached records collection tag '{}', stopping header reference stream.", tag);
                        break;
                    }

                    tagStack.push(tag);
                    currentAttributes.clear();
                    currentText.setLength(0);

                    int attrCount = stream.getAttributeCount();
                    for (int i = 0; i < attrCount; i++) {
                        String attrName = stream.getAttributeLocalName(i);
                        String attrVal = stream.getAttributeValue(i);
                        currentAttributes.put(attrName, attrVal);
                        currentAttributes.put("@" + attrName, attrVal);
                        currentAttributes.put(tag + "/@" + attrName, attrVal);
                        currentAttributes.put(tag + "/" + attrName, attrVal);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (!stream.isWhiteSpace()) {
                        currentText.append(stream.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (!tagStack.isEmpty()) {
                        String closedTag = tagStack.pop();
                        String parentTag = tagStack.peek();

                        if (!closedTag.toLowerCase().endsWith("list") && !closedTag.equalsIgnoreCase(docRoot)) {
                            String text = currentText.toString().trim();
                            if (!text.isEmpty()) {
                                currentAttributes.put(closedTag, text);
                                currentAttributes.put("name", text);
                                currentAttributes.put("value", text);
                            }

                            // Match element to target lookup tables
                            for (String tbl : lookupTables) {
                                List<FieldMapping> tblMaps = lookupMappings.get(tbl);
                                if (tblMaps == null || tblMaps.isEmpty()) continue;

                                if (isTagForTable(closedTag, parentTag, tbl, tblMaps)) {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    for (FieldMapping m : tblMaps) {
                                        String xmlField = m.getXmlField();
                                        String col = m.getTargetColumn();
                                        Object val = resolveField(currentAttributes, xmlField, col);
                                        if (val != null) {
                                            row.put(col, val);
                                        }
                                    }
                                    if (!row.isEmpty()) {
                                        extractedTableRows.get(tbl).add(row);
                                    }
                                }
                            }
                        }
                        currentAttributes.clear();
                        currentText.setLength(0);
                    }
                }
            }
        }

        // Batch insert extracted header reference data
        long totalHeaderRows = 0;
        DatabaseRowPayload payload = new DatabaseRowPayload();
        for (Map.Entry<String, List<Map<String, Object>>> entry : extractedTableRows.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                payload.setTableRecords(entry.getKey(), entry.getValue());
                totalHeaderRows += entry.getValue().size();
                log.info("Extracted {} records for header lookup table '{}'", entry.getValue().size(), entry.getKey());
            }
        }

        if (totalHeaderRows > 0 && dataSource != null) {
            List<String> order = new ArrayList<>(extractedTableRows.keySet());
            DynamicJdbcBatchWriter writer = new DynamicJdbcBatchWriter(dataSource, order);
            writer.write(new Chunk<>(List.of(payload)));
            log.info("Successfully persisted {} header reference rows across {} lookup tables.",
                    totalHeaderRows, order.size());

            if (stepExecution != null) {
                stepExecution.getExecutionContext().putLong("actualRowsWritten", totalHeaderRows);
                stepExecution.getExecutionContext().putLong("actualRowsRead", totalHeaderRows);
            }
            if (contribution != null) {
                contribution.incrementWriteCount((int) totalHeaderRows);
                for (long i = 0; i < totalHeaderRows; i++) {
                    contribution.incrementReadCount();
                }
            }
        }

        return RepeatStatus.FINISHED;
    }

    private boolean isTagForTable(String elementTag, String containerTag, String tableName, List<FieldMapping> tblMaps) {
        String normEl = elementTag.toLowerCase();
        String normTbl = tableName.toLowerCase();
        String cleanEl = normEl.replace("_", "").replace("-", "");
        String cleanTbl = normTbl.replace("_", "").replace("-", "");
        String cleanContainer = containerTag != null ? containerTag.toLowerCase().replace("_", "").replace("-", "") : "";

        // 1. Check if mappings explicitly reference elementTag
        for (FieldMapping m : tblMaps) {
            String f = m.getXmlField();
            if (f != null) {
                String normF = f.toLowerCase().replace("_", "").replace("-", "");
                if (normF.startsWith(cleanEl + "/") || normF.startsWith(cleanEl + "/@") || normF.equals(cleanEl)) {
                    return true;
                }
            }
        }

        // 2. Container name match (e.g. container CountryList -> table CountryList or country_list)
        if (!cleanContainer.isEmpty() && (cleanContainer.equals(cleanTbl) || cleanTbl.contains(cleanContainer) || cleanContainer.contains(cleanTbl))) {
            return true;
        }

        // 3. Name similarity (e.g. CountryName -> CountryList, Occupation -> OccupationList)
        String tblBase = cleanTbl.replace("list", "").replace("lists", "");
        if (cleanTbl.contains(cleanEl) || (!tblBase.isEmpty() && cleanEl.contains(tblBase))) {
            return true;
        }

        return false;
    }

    private Object resolveField(Map<String, Object> data, String xmlField, String targetCol) {
        if (xmlField == null || data == null) return null;

        // Direct key
        if (data.containsKey(xmlField)) return data.get(xmlField);

        // Case-insensitive key
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (e.getKey().equalsIgnoreCase(xmlField)) return e.getValue();
        }

        // Suffix match (e.g. @code or code)
        String clean = xmlField.contains("/") ? xmlField.substring(xmlField.lastIndexOf("/") + 1) : xmlField;
        if (clean.startsWith("@")) clean = clean.substring(1);

        for (Map.Entry<String, Object> e : data.entrySet()) {
            String k = e.getKey();
            if (k.equalsIgnoreCase(clean) || k.equalsIgnoreCase("@" + clean)) {
                return e.getValue();
            }
        }

        // Match by targetCol name
        if (targetCol != null) {
            for (Map.Entry<String, Object> e : data.entrySet()) {
                if (e.getKey().equalsIgnoreCase(targetCol) || e.getKey().equalsIgnoreCase("@" + targetCol)) {
                    return e.getValue();
                }
            }
        }

        return null;
    }

    private BufferedReader openReader(String path) throws Exception {
        Path direct = Paths.get(path);
        if (Files.exists(direct)) {
            return Files.newBufferedReader(direct, StandardCharsets.UTF_8);
        }
        Resource res = resourceLoader.getResource(path);
        if (res.exists()) {
            return new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8));
        }
        Path relative = Paths.get("/home/sv/Projects/xmldbtool", path);
        if (Files.exists(relative)) {
            return Files.newBufferedReader(relative, StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("Unable to find XML source file: " + path);
    }
}
