package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller;
import com.enterprise.xmldbtool.model.DiscoveredElement;
import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.model.SchemaDiscoveryRequest;
import com.enterprise.xmldbtool.model.SchemaDiscoveryResponse;
import com.enterprise.xmldbtool.model.TableMetadata;
import com.enterprise.xmldbtool.model.TableRelationship;
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
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class XmlSchemaDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(XmlSchemaDiscoveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();
    private final DynamicDataSourceService dataSourceService;
    private final SchemaProvisioningService provisioningService;

    @org.springframework.beans.factory.annotation.Autowired
    public XmlSchemaDiscoveryService(DynamicDataSourceService dataSourceService, SchemaProvisioningService provisioningService) {
        this.dataSourceService = dataSourceService;
        this.provisioningService = provisioningService;
    }

    public XmlSchemaDiscoveryService(DynamicDataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
        this.provisioningService = new SchemaProvisioningService(dataSourceService);
    }

    /**
     * Executes schema discovery:
     * 1. Uses lightweight StAX to scan first 1,000 lines of XML for tags and attributes (@attr).
     * 2. Automatically provisions the flat staging table ('staging_xml_records') and relational tables if requested.
     * 3. Queries target database metadata for tables and columns.
     */
    public SchemaDiscoveryResponse discoverSchema(SchemaDiscoveryRequest request) {
        SchemaDiscoveryResponse response = new SchemaDiscoveryResponse();

        try {
            long sizeBytes = getFileSizeBytes(request.getSourceXmlPath());
            response.setFileSizeBytes(sizeBytes);
            response.setFileSizeFormatted(formatFileSize(sizeBytes));

            // Candidate Root Elements and Version detection
            List<String> candidates = detectCandidateRootElements(request.getSourceXmlPath());
            response.setCandidateRootElements(candidates);
            response.setDetectedXmlVersion(detectXmlFeedVersion(request.getSourceXmlPath()));
            response.setDetectedRootElement(detectRecordRootElement(request.getSourceXmlPath()));

            // 1. Scan XML using lightweight StAX (first 1,000 lines)
            List<DiscoveredElement> rawXmlElements = discoverXmlElements(request.getSourceXmlPath(), request.getSampleLinesLimit());

            // 2. Resolve field mappings: use Ingestion JSON if provided, otherwise dynamically derive from XML tags & attributes
            boolean hasUserMappings = hasExplicitUserMappings(request);
            List<FieldMapping> effectiveMappings = hasUserMappings
                    ? resolveEffectiveMappings(request, rawXmlElements)
                    : Collections.emptyList();

            // 3. Filter discovered XML elements:
            // Show only the root tag, related attributes, child tag, child of child tag, grandchild of tags.
            // Also if user mentions any tags in mapping file, show those too, and ignore other tags!
            List<DiscoveredElement> filteredElements = filterRelevantElements(
                    rawXmlElements,
                    request.getSourceXmlPath(),
                    request.getRecordRootElements(),
                    effectiveMappings
            );
            response.setDiscoveredXmlElements(filteredElements);

            // Re-generate dynamic mappings from filtered elements if no explicit user JSON mapping was provided
            if (!hasUserMappings) {
                effectiveMappings = generateDynamicMappings(request.getSourceXmlPath(), request.getRecordRootElements(), filteredElements);
            }

            response.setGeneratedMappings(effectiveMappings);
            response.setDiscoveredRelationships(deriveRelationshipsFromMappings(effectiveMappings));
            response.setTableOrder(deriveTableOrder(effectiveMappings));

            // 4. Query target DB schema metadata and provision tables if required
            if (request.getDbConfig() != null && request.getDbConfig().getUrl() != null) {
                if (provisioningService != null) {
                    if (request.isAutoCreateTables()) {
                        List<String> created = provisioningService.provisionDiscoveredTables(
                                request.getDbConfig(),
                                request.getSourceXmlPath(),
                                request.getRecordRootElements(),
                                hasUserMappings ? filteredElements : rawXmlElements,
                                hasUserMappings ? effectiveMappings : Collections.emptyList()
                        );
                        response.setCreatedTables(created);
                        response.setStagingTables(created.stream()
                                .filter(t -> t.toLowerCase().endsWith("_stage") || t.equalsIgnoreCase("staging_xml_records"))
                                .toList());
                        response.setCreatedFlatTables(created.stream()
                                .filter(t -> t.toLowerCase().endsWith("_flat") || t.equalsIgnoreCase("staging_xml_records"))
                                .toList());
                    } else {
                        List<String> staging = provisioningService.ensureStagingTable(request.getDbConfig());
                        response.setCreatedTables(staging);
                        response.setStagingTables(staging);
                        response.setCreatedFlatTables(staging);
                    }
                }

                // Query DB tables and filter out internal staging/flat tables so UI only shows canonical business tables
                List<TableMetadata> allTables = dataSourceService.retrieveDatabaseSchema(request.getDbConfig());
                List<TableMetadata> canonicalTables = allTables.stream()
                        .filter(t -> !isStagingTableName(t.getTableName()))
                        .toList();

                // If user mappings are provided, only show the tables mentioned in the mapping file
                if (hasUserMappings && effectiveMappings != null && !effectiveMappings.isEmpty()) {
                    Set<String> mappedTables = effectiveMappings.stream()
                            .map(m -> m.getTargetTable().toLowerCase())
                            .collect(Collectors.toSet());
                    canonicalTables = canonicalTables.stream()
                            .filter(t -> mappedTables.contains(t.getTableName().toLowerCase()))
                            .toList();
                }

                response.setDiscoveredTables(canonicalTables);
            }

            response.setSuccess(true);
            String tableMsg = response.getCreatedTables() != null && !response.getCreatedTables().isEmpty()
                    ? " Provisioned " + response.getCreatedTables().size() + " tables in target DB (including flat staging table)."
                    : "";
            response.setMessage("Schema discovery completed successfully." + tableMsg + " Source XML size: " + response.getFileSizeFormatted());
        } catch (Exception ex) {
            log.error("Schema discovery failed: {}", ex.getMessage(), ex);
            response.setSuccess(false);
            response.setMessage("Schema discovery error: " + ex.getMessage());
        }

        return response;
    }

    /**
     * Manually triggers table provisioning in the target database based on XML schema.
     */
    public SchemaDiscoveryResponse provisionTables(SchemaDiscoveryRequest request) {
        SchemaDiscoveryResponse response = new SchemaDiscoveryResponse();
        try {
            List<DiscoveredElement> rawXmlElements = discoverXmlElements(request.getSourceXmlPath(), request.getSampleLinesLimit());

            List<String> candidates = detectCandidateRootElements(request.getSourceXmlPath());
            response.setCandidateRootElements(candidates);
            response.setDetectedXmlVersion(detectXmlFeedVersion(request.getSourceXmlPath()));
            response.setDetectedRootElement(detectRecordRootElement(request.getSourceXmlPath()));

            boolean hasUserMappings = hasExplicitUserMappings(request);
            List<FieldMapping> effectiveMappings = hasUserMappings
                    ? resolveEffectiveMappings(request, rawXmlElements)
                    : Collections.emptyList();

            List<DiscoveredElement> filteredElements = filterRelevantElements(
                    rawXmlElements,
                    request.getSourceXmlPath(),
                    request.getRecordRootElements(),
                    effectiveMappings
            );
            response.setDiscoveredXmlElements(filteredElements);

            if (!hasUserMappings) {
                effectiveMappings = generateDynamicMappings(request.getSourceXmlPath(), request.getRecordRootElements(), filteredElements);
            }

            response.setGeneratedMappings(effectiveMappings);
            response.setDiscoveredRelationships(deriveRelationshipsFromMappings(effectiveMappings));
            response.setTableOrder(deriveTableOrder(effectiveMappings));

            if (request.getDbConfig() != null && request.getDbConfig().getUrl() != null) {
                if (provisioningService != null) {
                    List<String> created = provisioningService.provisionDiscoveredTables(
                            request.getDbConfig(),
                            request.getSourceXmlPath(),
                            request.getRecordRootElements(),
                            hasUserMappings ? filteredElements : rawXmlElements,
                            hasUserMappings ? effectiveMappings : Collections.emptyList()
                    );
                    response.setCreatedTables(created);
                    response.setStagingTables(created.stream()
                            .filter(t -> t.toLowerCase().endsWith("_stage") || t.equalsIgnoreCase("staging_xml_records"))
                            .toList());
                    response.setCreatedFlatTables(created.stream()
                            .filter(t -> t.toLowerCase().endsWith("_flat") || t.equalsIgnoreCase("staging_xml_records"))
                            .toList());
                }
                List<TableMetadata> allTables = dataSourceService.retrieveDatabaseSchema(request.getDbConfig());
                List<TableMetadata> canonicalTables = allTables.stream()
                        .filter(t -> !isStagingTableName(t.getTableName()))
                        .toList();

                if (hasUserMappings && effectiveMappings != null && !effectiveMappings.isEmpty()) {
                    Set<String> mappedTables = effectiveMappings.stream()
                            .map(m -> m.getTargetTable().toLowerCase())
                            .collect(Collectors.toSet());
                    canonicalTables = canonicalTables.stream()
                            .filter(t -> mappedTables.contains(t.getTableName().toLowerCase()))
                            .toList();
                }

                response.setDiscoveredTables(canonicalTables);
            }

            response.setSuccess(true);
            response.setMessage("Successfully provisioned " + response.getCreatedTables().size() + " tables in target database.");
        } catch (Exception ex) {
            log.error("Table provisioning failed: {}", ex.getMessage(), ex);
            response.setSuccess(false);
            response.setMessage("Table provisioning error: " + ex.getMessage());
        }
        return response;
    }

    public static boolean isStagingTableName(String tableName) {
        if (tableName == null) return false;
        String norm = tableName.toLowerCase();
        return norm.endsWith("_stage");
    }

    /**
     * Reads first N lines of XML and uses StAX to discover unique tags and @attributes with full hierarchy paths.
     */
    public List<DiscoveredElement> discoverXmlElements(String sourcePath, int sampleLinesLimit) throws Exception {
        Map<String, DiscoveredElement> discoveredMap = new LinkedHashMap<>();

        // Detect candidate roots to align path hierarchy accurately
        List<String> candidates = detectCandidateRootElements(sourcePath);
        Set<String> candidateSet = new HashSet<>();
        for (String c : candidates) {
            if (c != null) candidateSet.add(c.toLowerCase());
        }

        // Read up to sampleLinesLimit into a memory buffer with a 10MB safety cap
        StringBuilder xmlSampleBuffer = new StringBuilder();
        try (BufferedReader reader = openReaderForSource(sourcePath)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < sampleLinesLimit) {
                xmlSampleBuffer.append(line).append("\n");
                count++;
                if (xmlSampleBuffer.length() > 10 * 1024 * 1024) {
                    break;
                }
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
            Deque<String> tagStack = new ArrayDeque<>();

            while (streamReader.hasNext()) {
                try {
                    int event = streamReader.next();

                    if (event == XMLStreamConstants.START_ELEMENT) {
                        currentTag = streamReader.getLocalName();
                        tagStack.push(currentTag);
                        textBuffer.setLength(0);

                        final String enclosingTag = currentTag;
                        String parentTag = null;
                        if (tagStack.size() >= 2) {
                            Iterator<String> it = tagStack.iterator();
                            it.next(); // current element
                            if (it.hasNext()) {
                                parentTag = it.next();
                            }
                        }
                        final String finalParent = parentTag;
                        final String effectiveEnclosing = (parentTag != null && (parentTag.toLowerCase().endsWith("list") || parentTag.toLowerCase().endsWith("lists")))
                                ? parentTag : enclosingTag;

                        // Construct relative hierarchy path from candidate root entity
                        List<String> pathList = new ArrayList<>(tagStack);
                        Collections.reverse(pathList);

                        int startIdx = -1;
                        for (int i = 0; i < pathList.size(); i++) {
                            if (candidateSet.contains(pathList.get(i).toLowerCase())) {
                                startIdx = i;
                                break;
                            }
                        }
                        if (startIdx < 0) {
                            startIdx = (pathList.size() > 1) ? 1 : 0;
                            while (startIdx < pathList.size() - 1 && (pathList.get(startIdx).equalsIgnoreCase("record")
                                    || pathList.get(startIdx).equalsIgnoreCase("records"))) {
                                startIdx++;
                            }
                        }
                        String currentPath = String.join("/", pathList.subList(startIdx, pathList.size()));

                        // Register element tag itself in discoveredMap
                        discoveredMap.computeIfAbsent(currentPath, k -> new DiscoveredElement(
                                currentPath,
                                finalParent,
                                false,
                                null,
                                "VARCHAR(255)"
                        ));

                        // Extract XML attributes qualified with tag/@attr to identify enclosing tag
                        int attrCount = streamReader.getAttributeCount();
                        for (int i = 0; i < attrCount; i++) {
                            String rawAttrName = streamReader.getAttributeLocalName(i);
                            String fullAttrName = enclosingTag + "/@" + rawAttrName;
                            String pathAttrName = currentPath + "/@" + rawAttrName;
                            String attrVal = streamReader.getAttributeValue(i);

                            discoveredMap.computeIfAbsent(pathAttrName, k -> new DiscoveredElement(
                                    pathAttrName,
                                    effectiveEnclosing,
                                    true,
                                    attrVal,
                                    detectDataType(attrVal)
                            ));
                            discoveredMap.computeIfAbsent(fullAttrName, k -> new DiscoveredElement(
                                    fullAttrName,
                                    effectiveEnclosing,
                                    true,
                                    attrVal,
                                    detectDataType(attrVal)
                            ));
                            if (finalParent != null && (finalParent.toLowerCase().endsWith("list") || finalParent.toLowerCase().endsWith("lists"))) {
                                String listAttrName = finalParent + "/" + enclosingTag + "/@" + rawAttrName;
                                discoveredMap.computeIfAbsent(listAttrName, k -> new DiscoveredElement(
                                        listAttrName,
                                        finalParent,
                                        true,
                                        attrVal,
                                        detectDataType(attrVal)
                                ));
                            }
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

                            List<String> pathList = new ArrayList<>(tagStack);
                            Collections.reverse(pathList);

                            int startIdx = -1;
                            for (int i = 0; i < pathList.size(); i++) {
                                if (candidateSet.contains(pathList.get(i).toLowerCase())) {
                                    startIdx = i;
                                    break;
                                }
                            }
                            if (startIdx < 0) {
                                startIdx = (pathList.size() > 1) ? 1 : 0;
                                while (startIdx < pathList.size() - 1 && (pathList.get(startIdx).equalsIgnoreCase("record")
                                        || pathList.get(startIdx).equalsIgnoreCase("records"))) {
                                    startIdx++;
                                }
                            }
                            String currentPath = String.join("/", pathList.subList(startIdx, pathList.size()));

                            String parentTag = null;
                            if (tagStack.size() >= 2) {
                                Iterator<String> it = tagStack.iterator();
                                it.next(); // current element
                                if (it.hasNext()) {
                                    parentTag = it.next();
                                }
                            }

                            final String finalParent = parentTag;
                            discoveredMap.computeIfAbsent(currentPath, k -> new DiscoveredElement(
                                    currentPath,
                                    finalParent,
                                    false,
                                    sampleVal,
                                    detectDataType(sampleVal)
                            ));
                            discoveredMap.computeIfAbsent(closedTag, k -> new DiscoveredElement(
                                    closedTag,
                                    finalParent,
                                    false,
                                    sampleVal,
                                    detectDataType(sampleVal)
                            ));
                        }
                        if (!tagStack.isEmpty()) {
                            tagStack.pop();
                        }
                        textBuffer.setLength(0);
                    }
                } catch (Exception streamEx) {
                    log.debug("Reached end of sample stream: {}", streamEx.getMessage());
                    break;
                }
            }
        }

        return new ArrayList<>(discoveredMap.values());
    }

    public static boolean hasExplicitUserMappings(SchemaDiscoveryRequest request) {
        if (request == null) return false;
        return (request.getMappings() != null && !request.getMappings().isEmpty())
                || (request.getMappingJson() != null && !request.getMappingJson().trim().isEmpty())
                || (request.getIngestionJsonPath() != null && !request.getIngestionJsonPath().trim().isEmpty());
    }

    /**
     * Resolves effective field mappings:
     * - Uses user ingest JSON file / mappings if provided.
     * - Otherwise dynamically scans XML tags and attributes, identifying tables and columns with PK/FK constraints.
     */
    public List<FieldMapping> resolveEffectiveMappings(SchemaDiscoveryRequest request, List<DiscoveredElement> xmlElements) {
        if (request == null) return Collections.emptyList();

        // 1. Explicit in-memory mappings list
        if (request.getMappings() != null && !request.getMappings().isEmpty()) {
            return request.getMappings();
        }

        // 2. Ingest JSON mapping payload string (or file path string)
        if (request.getMappingJson() != null && !request.getMappingJson().trim().isEmpty()) {
            List<FieldMapping> parsed = parseMappingsFromJsonStringOrFile(request.getMappingJson().trim());
            if (parsed != null && !parsed.isEmpty()) {
                return parsed;
            }
        }

        // 3. Ingestion JSON file path
        if (request.getIngestionJsonPath() != null && !request.getIngestionJsonPath().trim().isEmpty()) {
            List<FieldMapping> parsed = loadMappingsFromFile(request.getIngestionJsonPath().trim());
            if (parsed != null && !parsed.isEmpty()) {
                return parsed;
            }
        }

        // 4. Fallback: Dynamically generate mappings by reading tags and attributes from XML
        return generateDynamicMappings(request.getSourceXmlPath(), request.getRecordRootElements(), xmlElements);
    }

    /**
     * Filters discovered XML elements to show:
     * - The root tag and related attributes (@attr).
     * - Child tags of the root tag.
     * - Child-of-child tags.
     * - Grandchild tags and deeper descendants.
     * - Any tags explicitly mentioned in the user's mapping file.
     * Ignores unrelated elements outside the root hierarchy to make mapping easy and clutter-free.
     */
    public List<DiscoveredElement> filterRelevantElements(List<DiscoveredElement> rawElements,
                                                          String sourceXmlPath,
                                                          List<String> recordRootElements,
                                                          List<FieldMapping> mappings) {
        if (rawElements == null || rawElements.isEmpty()) {
            return Collections.emptyList();
        }

        boolean hasUserMappings = mappings != null && !mappings.isEmpty();

        // 1. If user provided a mapping file/JSON:
        // Refer to the JSON file strictly: show ONLY tags mentioned in the mapping file,
        // ignore all other tags in the UI as well as when provisioning/storing in DB.
        if (hasUserMappings) {
            List<DiscoveredElement> filtered = new ArrayList<>();
            Set<String> seenFields = new LinkedHashSet<>();
            for (DiscoveredElement el : rawElements) {
                String matchedField = getMatchingMappingField(el, mappings);
                if (matchedField != null) {
                    String normField = matchedField.toLowerCase();
                    if (!seenFields.contains(normField)) {
                        seenFields.add(normField);
                        DiscoveredElement cleaned = new DiscoveredElement(
                                matchedField,
                                el.getEnclosingTag(),
                                el.isAttribute() || matchedField.contains("/@") || matchedField.startsWith("@"),
                                el.getSampleValue(),
                                el.getDetectedType()
                        );
                        filtered.add(cleaned);
                    }
                }
            }

            // Also ensure every mapped field in mappings is represented in discovered elements
            for (FieldMapping m : mappings) {
                if (m.getXmlField() != null && !m.getXmlField().trim().isEmpty()) {
                    String xf = m.getXmlField().trim().replaceAll("^/+", "");
                    String xfLower = xf.toLowerCase();
                    if (!seenFields.contains(xfLower)) {
                        seenFields.add(xfLower);
                        filtered.add(new DiscoveredElement(
                                xf,
                                m.getTargetTable(),
                                xf.contains("/@") || xf.startsWith("@"),
                                null,
                                "VARCHAR(255)"
                        ));
                    }
                }
            }

            return filtered;
        }

        // 2. Dynamic discovery mode (no mapping file provided yet):
        // Show tags belonging to any of the specified or detected root elements.
        // Ignore unrelated wrapper tags (e.g. canopyapp, countrylists, descriptionlists).
        Set<String> activeRoots = new LinkedHashSet<>();
        if (recordRootElements != null && !recordRootElements.isEmpty()) {
            for (String r : recordRootElements) {
                if (r != null && !r.trim().isEmpty() && !r.equalsIgnoreCase("record") && !r.equalsIgnoreCase("records")) {
                    activeRoots.add(r.trim().toLowerCase());
                }
            }
        }
        if (activeRoots.isEmpty() && sourceXmlPath != null) {
            String detected = detectRecordRootElement(sourceXmlPath);
            if (detected != null && !detected.trim().isEmpty() && !detected.equalsIgnoreCase("record") && !detected.equalsIgnoreCase("records")) {
                activeRoots.add(detected.trim().toLowerCase());
            }
        }
        if (activeRoots.isEmpty()) {
            activeRoots.add("person");
        }

        List<DiscoveredElement> filtered = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        for (DiscoveredElement el : rawElements) {
            String rawName = el.getName();
            String nameLower = rawName.toLowerCase().replaceAll("^/+", "");

            String matchedRoot = null;
            String normPath = rawName;

            for (String root : activeRoots) {
                if (nameLower.contains("/" + root + "/")) {
                    normPath = rawName.substring(nameLower.indexOf("/" + root + "/") + 1);
                    matchedRoot = root;
                    break;
                } else if (nameLower.startsWith(root + "/")) {
                    normPath = rawName;
                    matchedRoot = root;
                    break;
                } else if (nameLower.endsWith("/" + root) || nameLower.equals(root)) {
                    normPath = root;
                    matchedRoot = root;
                    break;
                }
            }

            if (matchedRoot != null) {
                String normLower = normPath.toLowerCase();
                boolean belongsToRoot = normLower.equals(matchedRoot)
                        || normLower.startsWith(matchedRoot + "/@")
                        || normLower.startsWith(matchedRoot + "/");

                if (belongsToRoot) {
                    boolean isContainer = !el.isAttribute() && !normPath.contains("/@") && (el.getSampleValue() == null || el.getSampleValue().trim().isEmpty());
                    boolean isIntermediateWrapper = isContainer && (normLower.endsWith("s") || normLower.endsWith("list") || normLower.endsWith("lists"));
                    if (isIntermediateWrapper && !normLower.equals(matchedRoot)) {
                        continue;
                    }

                    if (!seenNames.contains(normLower)) {
                        seenNames.add(normLower);
                        DiscoveredElement cleaned = new DiscoveredElement(
                                normPath,
                                el.getEnclosingTag(),
                                el.isAttribute() || normPath.contains("/@"),
                                el.getSampleValue(),
                                el.getDetectedType()
                        );
                        filtered.add(cleaned);
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            return rawElements;
        }

        return filtered;
    }

    /**
     * Finds matching field in mapping configurations, if any.
     */
    public static String getMatchingMappingField(DiscoveredElement el, List<FieldMapping> mappings) {
        if (el == null || mappings == null || mappings.isEmpty()) return null;
        String rawName = el.getName().trim().toLowerCase().replaceAll("^/+", "");
        String rawNoAt = rawName.replace("@", "");
        String enclosing = el.getEnclosingTag() != null ? el.getEnclosingTag().trim().toLowerCase() : "";

        for (FieldMapping m : mappings) {
            if (m.getXmlField() == null || m.getXmlField().trim().isEmpty()) continue;
            String xf = m.getXmlField().trim().replaceAll("^/+", "");
            String xfLower = xf.toLowerCase();
            String xfNoAt = xfLower.replace("@", "");

            // 1. Exact match
            if (rawName.equals(xfLower) || rawNoAt.equals(xfNoAt)) {
                return xf;
            }

            // 2. Path matching
            if (xfLower.contains("/")) {
                if (rawName.endsWith("/" + xfLower) || xfLower.endsWith("/" + rawName)) {
                    return xf;
                }
                if (rawNoAt.endsWith("/" + xfNoAt) || xfNoAt.endsWith("/" + rawNoAt)) {
                    return xf;
                }
            } else {
                String leaf = rawName.contains("/") ? rawName.substring(rawName.lastIndexOf("/") + 1) : rawName;
                if (leaf.replace("@", "").equals(xfNoAt)) {
                    if (m.getTargetTable() != null && !m.getTargetTable().trim().isEmpty()) {
                        String tbl = m.getTargetTable().trim().toLowerCase();
                        if (rawName.startsWith(tbl + "/") || enclosing.equals(tbl) || rawName.contains("/" + tbl + "/") || rawName.endsWith("/" + tbl) || rawName.equals(tbl)) {
                            return xf;
                        }
                    } else {
                        return xf;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Checks if a discovered XML element corresponds to any field specified in the mapping configuration.
     */
    public static boolean isElementMatchingMapping(DiscoveredElement el, List<FieldMapping> mappings) {
        return getMatchingMappingField(el, mappings) != null;
    }

    public List<FieldMapping> parseMappingsFromJsonStringOrFile(String jsonOrPath) {
        if (jsonOrPath == null || jsonOrPath.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String content = jsonOrPath.trim();
        try {
            // If it's a file path pointing to an existing file, read file content
            if (!content.startsWith("[") && !content.startsWith("{")) {
                Path p = Paths.get(content);
                if (Files.exists(p)) {
                    content = Files.readString(p, StandardCharsets.UTF_8).trim();
                } else {
                    Resource res = resourceLoader.getResource(content);
                    if (res.exists()) {
                        try (InputStream in = res.getInputStream()) {
                            content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                        }
                    }
                }
            }

            if (content.startsWith("[")) {
                return objectMapper.readValue(content, new TypeReference<List<FieldMapping>>() {});
            } else if (content.startsWith("{")) {
                JsonNode root = objectMapper.readTree(content);
                if (root.has("mappings") && root.get("mappings").isArray()) {
                    return objectMapper.readValue(root.get("mappings").toString(), new TypeReference<List<FieldMapping>>() {});
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to parse field mappings from JSON input: {}", ex.getMessage());
        }
        return Collections.emptyList();
    }

    public List<FieldMapping> loadMappingsFromFile(String path) {
        if (path == null || path.trim().isEmpty()) return Collections.emptyList();
        try {
            Path p = Paths.get(path.trim());
            if (Files.exists(p)) {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                return parseMappingsFromJsonStringOrFile(content);
            } else {
                Resource res = resourceLoader.getResource(path.trim());
                if (res.exists()) {
                    try (InputStream in = res.getInputStream()) {
                        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        return parseMappingsFromJsonStringOrFile(content);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Could not load mappings file from {}: {}", path, ex.getMessage());
        }
        return Collections.emptyList();
    }

    public List<String> deriveTableOrder(List<FieldMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) return Collections.emptyList();
        Set<String> allTables = new LinkedHashSet<>();
        Set<String> parents = new LinkedHashSet<>();
        Set<String> children = new LinkedHashSet<>();

        for (FieldMapping m : mappings) {
            if (m.getTargetTable() != null && !m.getTargetTable().trim().isEmpty()) {
                allTables.add(m.getTargetTable().trim());
            }
            if ("FK".equalsIgnoreCase(m.getConstraintType())) {
                if (m.getTargetTable() != null) children.add(m.getTargetTable().trim());
                if (m.getForeignKeyTable() != null) parents.add(m.getForeignKeyTable().trim());
            }
        }

        List<String> order = new ArrayList<>();
        for (String p : parents) {
            if (allTables.contains(p) && !order.contains(p)) order.add(p);
        }
        for (String t : allTables) {
            if (!order.contains(t) && !children.contains(t)) order.add(t);
        }
        for (String c : children) {
            if (allTables.contains(c) && !order.contains(c)) order.add(c);
        }
        return order;
    }

    /**
     * Generates canonical table field mappings dynamically from XML structure when user ingest JSON is not provided.
     * Maps root entities, nested child entities, primary keys (PK), foreign keys (FK), and attributes.
     */
    public List<FieldMapping> generateDynamicMappings(String sourceXmlPath,
                                                     List<String> recordRootElements,
                                                     List<DiscoveredElement> discoveredXmlElements) {
        List<FieldMapping> mappings = new ArrayList<>();
        if (discoveredXmlElements == null || discoveredXmlElements.isEmpty()) {
            return mappings;
        }

        List<String> effectiveRoots = (recordRootElements != null && !recordRootElements.isEmpty())
                ? recordRootElements
                : detectCandidateRootElements(sourceXmlPath);

        // Group discovered elements by table/entity
        Map<String, Set<String>> entityAttributes = new LinkedHashMap<>();
        Map<String, Set<String>> entityLeaves = new LinkedHashMap<>();
        Map<String, String> xmlPathForCol = new LinkedHashMap<>(); // table.col -> xmlField

        // Track child entities under roots (e.g. address under person)
        Map<String, String> childToParentMap = new LinkedHashMap<>(); // childEntity -> parentEntity

        for (DiscoveredElement el : discoveredXmlElements) {
            String rawName = el.getName();
            String[] parts = rawName.split("/");
            String enclosing = el.getEnclosingTag() != null ? el.getEnclosingTag() : "";

            if (parts.length >= 3) {
                // Nested element: e.g. person/addresses/address/@id or Person/NameDetails/Name/NameValue/FirstName
                String rootTag = parts[0];
                String entity = parts[parts.length - 2];
                String col = parts[parts.length - 1].replace("@", "");

                if (parts.length >= 4 && (parts[1].toLowerCase().endsWith("details") || parts[1].toLowerCase().endsWith("detail"))) {
                    entity = parts[0] + parts[2]; // e.g. PersonName
                } else if (entity.equalsIgnoreCase(col) && parts.length >= 4) {
                    entity = parts[parts.length - 3];
                } else if (entity.toLowerCase().endsWith("details") || entity.toLowerCase().endsWith("detail")) {
                    entity = parts[parts.length - 1].replace("@", "");
                }

                if (parts.length == 3 && entity.toLowerCase().endsWith("s") && !entity.toLowerCase().endsWith("ss")) {
                    entity = DynamicXmlUnmarshaller.singularize(entity);
                }

                String table = entity;
                childToParentMap.putIfAbsent(table.toLowerCase(), rootTag);

                if (el.isAttribute()) {
                    entityAttributes.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(col);
                } else {
                    entityLeaves.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(col);
                }
                xmlPathForCol.put(table.toLowerCase() + "." + col.toLowerCase(), rawName);
            } else if (parts.length == 2) {
                // Direct child of root: e.g. person/@id, person/name, country/@action
                String rootTag = parts[0];
                String col = parts[1].replace("@", "");
                if (el.isAttribute()) {
                    entityAttributes.computeIfAbsent(rootTag, k -> new LinkedHashSet<>()).add(col);
                } else {
                    entityLeaves.computeIfAbsent(rootTag, k -> new LinkedHashSet<>()).add(col);
                }
                xmlPathForCol.put(rootTag.toLowerCase() + "." + col.toLowerCase(), rawName);
            } else {
                // Simple leaf tag
                String table = enclosing.isEmpty() ? (effectiveRoots.isEmpty() ? "record" : effectiveRoots.get(0)) : enclosing;
                String col = rawName.replace("@", "");
                String key = table.toLowerCase() + "." + col.toLowerCase();
                if (!xmlPathForCol.containsKey(key)) {
                    if (el.isAttribute()) {
                        entityAttributes.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(col);
                    } else {
                        entityLeaves.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(col);
                    }
                    xmlPathForCol.put(key, rawName);
                }
            }
        }

        // Collect all distinct entities
        Set<String> allEntities = new LinkedHashSet<>();
        allEntities.addAll(entityAttributes.keySet());
        allEntities.addAll(entityLeaves.keySet());

        Set<String> generatedKeys = new HashSet<>();

        for (String entity : allEntities) {
            String normEntity = entity.toLowerCase();
            Set<String> attrs = entityAttributes.getOrDefault(entity, Collections.emptySet());
            Set<String> leaves = entityLeaves.getOrDefault(entity, Collections.emptySet());

            // 1. Primary Key
            String pkCol = null;
            if (attrs.contains("id") || attrs.contains("code") || attrs.contains(normEntity + "_id") || attrs.contains(normEntity + "id")) {
                pkCol = attrs.contains("id") ? "id" : (attrs.contains("code") ? "code" : attrs.iterator().next());
            } else if (leaves.contains("id") || leaves.contains("code")) {
                pkCol = leaves.contains("id") ? "id" : "code";
            } else {
                pkCol = "id";
            }

            String pkXmlPath = xmlPathForCol.get(normEntity + "." + pkCol.toLowerCase());
            if (pkXmlPath == null) pkXmlPath = entity + "/@" + pkCol;

            FieldMapping pkMapping = new FieldMapping(pkXmlPath, entity, pkCol, "NONE", "PK");
            mappings.add(pkMapping);
            generatedKeys.add(normEntity + "." + pkCol.toLowerCase());

            // 2. Parent Foreign Key (if child entity)
            String parentEntity = childToParentMap.get(normEntity);
            if (parentEntity != null && !parentEntity.equalsIgnoreCase(entity)) {
                String parentFkCol = parentEntity.toLowerCase() + "_id";
                String parentXmlPath = xmlPathForCol.get(parentEntity.toLowerCase() + ".id");
                if (parentXmlPath == null) parentXmlPath = parentEntity + "/@id";
                FieldMapping parentFkMapping = new FieldMapping(parentXmlPath, entity, parentFkCol, "NONE", "FK");
                parentFkMapping.setForeignKeyTable(parentEntity);
                parentFkMapping.setForeignKeyColumn("id");
                mappings.add(parentFkMapping);
                generatedKeys.add(normEntity + "." + parentFkCol);
            }

            // 3. Other attributes (ALL XML attributes included)
            for (String attr : attrs) {
                String key = normEntity + "." + attr.toLowerCase();
                if (generatedKeys.contains(key)) continue;
                generatedKeys.add(key);

                String xmlPath = xmlPathForCol.get(key);
                if (xmlPath == null) xmlPath = entity + "/@" + attr;
                mappings.add(new FieldMapping(xmlPath, entity, attr, "NONE", "NONE"));
            }

            // 4. Other leaf tags (ALL XML tags included, check for cross-table foreign keys ending in _id)
            for (String leaf : leaves) {
                String key = normEntity + "." + leaf.toLowerCase();
                if (generatedKeys.contains(key)) continue;
                generatedKeys.add(key);

                String xmlPath = xmlPathForCol.get(key);
                if (xmlPath == null) xmlPath = entity + "/" + leaf;

                if (leaf.toLowerCase().endsWith("_id") && !leaf.equalsIgnoreCase(normEntity + "_id")) {
                    String refEntity = leaf.substring(0, leaf.length() - 3);
                    FieldMapping fkMapping = new FieldMapping(xmlPath, entity, leaf, "NONE", "FK");
                    fkMapping.setForeignKeyTable(refEntity);
                    fkMapping.setForeignKeyColumn("id");
                    mappings.add(fkMapping);
                } else {
                    mappings.add(new FieldMapping(xmlPath, entity, leaf, "NONE", "NONE"));
                }
            }
        }

        return mappings;
    }

    /**
     * Derives table relationships from declarative FieldMapping list.
     */
    public List<TableRelationship> deriveRelationshipsFromMappings(List<FieldMapping> mappings) {
        List<TableRelationship> relationships = new ArrayList<>();
        if (mappings == null) return relationships;

        for (FieldMapping m : mappings) {
            if ("FK".equalsIgnoreCase(m.getConstraintType()) && m.getTargetTable() != null && m.getTargetColumn() != null) {
                String childTable = m.getTargetTable();
                String childCol = m.getTargetColumn();
                String parentTable = m.getForeignKeyTable();
                String parentCol = m.getForeignKeyColumn() != null ? m.getForeignKeyColumn() : "id";

                if (parentTable == null && childCol.toLowerCase().endsWith("_id")) {
                    parentTable = childCol.substring(0, childCol.length() - 3);
                }
                if (parentTable == null && m.getXmlField() != null && m.getXmlField().contains("/")) {
                    parentTable = m.getXmlField().split("/")[0];
                }

                if (parentTable != null && !parentTable.equalsIgnoreCase(childTable)) {
                    if (m.getForeignKeyTable() == null) {
                        m.setForeignKeyTable(parentTable);
                    }
                    if (m.getForeignKeyColumn() == null) {
                        m.setForeignKeyColumn(parentCol);
                    }
                    relationships.add(new TableRelationship(parentTable, parentCol, childTable, childCol, "ONE_TO_MANY"));
                }
            }
        }
        return relationships;
    }

    /**
     * Extracts all attributes of the document root element.
     * When repeating records (e.g. <record>) are unmarshalled individually, document-level
     * root attributes (e.g. <catalog exportId="..." version="...">) are otherwise skipped.
     */
    public Map<String, Object> extractDocumentRootAttributes(String sourcePath, String recordRootElement) {
        Map<String, Object> rootAttrs = new LinkedHashMap<>();
        if (sourcePath == null || sourcePath.trim().isEmpty()) return rootAttrs;

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        try (BufferedReader reader = openReaderForSource(sourcePath)) {
            XMLStreamReader streamReader = factory.createXMLStreamReader(reader);
            while (streamReader.hasNext()) {
                int event = streamReader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String rootName = streamReader.getLocalName();
                    // If the first element is already the record fragment root element, no parent root attributes exist
                    if (recordRootElement != null && rootName.equalsIgnoreCase(recordRootElement)) {
                        break;
                    }
                    int attrCount = streamReader.getAttributeCount();
                    for (int i = 0; i < attrCount; i++) {
                        String rawAttrName = streamReader.getAttributeLocalName(i);
                        String attrVal = streamReader.getAttributeValue(i);
                        rootAttrs.put(rootName + "/@" + rawAttrName, attrVal);
                        rootAttrs.put("@" + rawAttrName, attrVal);
                        rootAttrs.put(rawAttrName, attrVal);
                    }
                    break; // Captured the document root attributes
                }
            }
        } catch (Exception ex) {
            log.warn("Could not extract document root attributes from {}: {}", sourcePath, ex.getMessage());
        }
        return rootAttrs;
    }

    public String detectDocumentRootElement(String sourcePath) {
        if (sourcePath == null || sourcePath.trim().isEmpty()) return null;
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        try (BufferedReader reader = openReaderForSource(sourcePath)) {
            XMLStreamReader streamReader = factory.createXMLStreamReader(reader);
            while (streamReader.hasNext()) {
                int event = streamReader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    return streamReader.getLocalName();
                }
            }
        } catch (Exception ex) {
            log.warn("Could not detect document root element from {}: {}", sourcePath, ex.getMessage());
        }
        return null;
    }

    /**
     * Identifies whether a tag name represents a wrapper/container tag rather than an entity record fragment.
     * Container tags group repeating child records together and should not be treated as isolated entity records.
     */
    public static boolean isWrapperContainerTag(String tag, String docRoot) {
        if (tag == null || tag.trim().isEmpty()) return false;
        String norm = tag.trim().toLowerCase();
        if (docRoot != null && norm.equalsIgnoreCase(docRoot.trim())) return true;
        if (Set.of("records", "items", "data", "dataset", "entries", "feed", "catalog", "root",
                   "canopyapp", "pfa", "countries", "persons", "entities", "countrylists", "descriptionlists").contains(norm)) {
            return true;
        }
        if (norm.matches(".*lists\\d*") || norm.endsWith("lists")) {
            return true;
        }
        return false;
    }

    /**
     * Resolves a container / plural tag into its singular entity record tag name.
     * e.g., countrylists -> countrylist, descriptionlists1 -> descriptionlist1, countries -> country, persons -> person
     */
    public static String resolveEntityTag(String tag) {
        if (tag == null) return null;
        String norm = tag.trim().toLowerCase();
        if (norm.equals("countries")) return "country";
        if (norm.equals("persons")) return "person";
        if (norm.equals("entities")) return "entity";
        if (norm.equals("records")) return "record";
        if (norm.matches(".*lists\\d+$")) {
            return norm.replaceFirst("lists(\\d+)$", "list$1");
        }
        if (norm.endsWith("lists")) {
            return norm.substring(0, norm.length() - 1);
        }
        return tag.trim();
    }

    /**
     * Detects all candidate record fragment root element tags from the XML file.
     * Identifies repeating entities such as Person, CountryName, Occupation, Relationship, etc.
     */
    public List<String> detectCandidateRootElements(String sourcePath) {
        Set<String> candidates = new LinkedHashSet<>();
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            return new ArrayList<>(candidates);
        }

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        try (BufferedReader reader = openReaderForSource(sourcePath)) {
            XMLStreamReader streamReader = factory.createXMLStreamReader(reader);
            String docRoot = null;
            Deque<String> tagStack = new ArrayDeque<>();
            int entityDepth = 0;
            int events = 0;

            while (streamReader.hasNext() && events < 100000) {
                int event = streamReader.next();
                events++;

                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = streamReader.getLocalName();
                    if (tagStack.isEmpty()) {
                        docRoot = localName;
                    } else if (entityDepth == 0) {
                        int attrCount = streamReader.getAttributeCount();
                        String norm = localName.toLowerCase();
                        // Container tags are either known wrapper sets, end in 'lists', or end in 'list' with 0 attributes
                        boolean isContainer = isWrapperContainerTag(localName, docRoot)
                                || (localName.equalsIgnoreCase("record") && attrCount == 0)
                                || ((norm.endsWith("list") || norm.matches(".*list\\d+$")) && attrCount == 0);
                        if (!isContainer) {
                            String parent = tagStack.peek();
                            String parentNorm = parent != null ? parent.toLowerCase() : "";
                            boolean parentIsContainer = (parent != null && (isWrapperContainerTag(parent, docRoot)
                                    || parent.equalsIgnoreCase("record") || parent.equalsIgnoreCase(docRoot)
                                    || parentNorm.endsWith("list") || parentNorm.endsWith("lists")
                                    || parentNorm.matches(".*list\\d*$")));
                            if (parentIsContainer) {
                                candidates.add(resolveEntityTag(localName));
                                entityDepth = 1;
                            }
                        }
                    } else {
                        // Inside an entity record fragment (e.g. inside <person> or <country>)
                        entityDepth++;
                    }
                    tagStack.push(localName);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (!tagStack.isEmpty()) {
                        tagStack.pop();
                    }
                    if (entityDepth > 0) {
                        entityDepth--;
                    }
                }
            }

            if (docRoot != null) {
                candidates.remove(docRoot);
                candidates.remove(docRoot.toLowerCase());
                candidates.remove(docRoot.toUpperCase());
            }

            // If specific entity records were found, remove generic wrapper 'record' or 'records'
            if (candidates.size() > 1 && (candidates.contains("person") || candidates.contains("entity") || candidates.contains("countrylist") || candidates.contains("country"))) {
                candidates.remove("record");
                candidates.remove("records");
            }
        } catch (Exception ex) {
            log.warn("Could not detect candidate root elements from {}: {}", sourcePath, ex.getMessage());
        }

        if (candidates.isEmpty()) {
            candidates.add("record");
        }
        return new ArrayList<>(candidates);
    }

    /**
     * Detects XML feed version from document root attributes (e.g. date="202206192200", version="1.0").
     */
    public String detectXmlFeedVersion(String sourcePath) {
        if (sourcePath == null || sourcePath.trim().isEmpty()) return null;
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        try (BufferedReader reader = openReaderForSource(sourcePath)) {
            XMLStreamReader streamReader = factory.createXMLStreamReader(reader);
            while (streamReader.hasNext()) {
                int event = streamReader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    int attrCount = streamReader.getAttributeCount();
                    for (int i = 0; i < attrCount; i++) {
                        String name = streamReader.getAttributeLocalName(i).toLowerCase();
                        if (name.contains("date") || name.contains("version") || name.contains("export") || name.contains("timestamp") || name.contains("feed")) {
                            String val = streamReader.getAttributeValue(i);
                            if (val != null && !val.trim().isEmpty()) {
                                return val.trim();
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Exception ex) {
            log.warn("Could not detect XML feed version from {}: {}", sourcePath, ex.getMessage());
        }
        return null;
    }

    /**
     * Detects the repeating item / record fragment root element (e.g. "Person", "record", "item")
     * suitable for chunked StaxEventItemReader streaming without memory exhaustion.
     */
    public String detectRecordRootElement(String sourcePath) {
        if (sourcePath == null || sourcePath.trim().isEmpty()) return "record";
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        Map<String, Integer> tagCounts = new LinkedHashMap<>();

        try (BufferedReader reader = openReaderForSource(sourcePath)) {
            XMLStreamReader streamReader = factory.createXMLStreamReader(reader);
            String docRoot = null;
            int depth = 0;
            java.util.Deque<String> tagStack = new java.util.ArrayDeque<>();

            int events = 0;
            while (streamReader.hasNext() && events < 50000) {
                int event = streamReader.next();
                events++;
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = streamReader.getLocalName();
                    if (depth == 0) {
                        docRoot = localName;
                    } else {
                        String parent = tagStack.peek();
                        boolean parentIsList = parent != null && (parent.toLowerCase().endsWith("list") || parent.toLowerCase().endsWith("lists"));
                        if (parent != null && (parent.equalsIgnoreCase("records") || parent.equalsIgnoreCase("items")
                                || parent.equalsIgnoreCase("data") || parent.equalsIgnoreCase("entries")
                                || parent.equalsIgnoreCase("dataset"))) {
                            return localName;
                        }
                        if (!parentIsList && !localName.toLowerCase().endsWith("list") && !localName.toLowerCase().endsWith("lists")
                                && (depth == 1 || depth == 2)) {
                            tagCounts.put(localName, tagCounts.getOrDefault(localName, 0) + 1);
                        }
                    }
                    tagStack.push(localName);
                    depth++;
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (!tagStack.isEmpty()) {
                        tagStack.pop();
                    }
                    depth--;
                }
            }

            // Find candidate with repeats (> 1) that is not docRoot and not list
            for (Map.Entry<String, Integer> entry : tagCounts.entrySet()) {
                String tag = entry.getKey();
                if (entry.getValue() > 1 && !tag.equalsIgnoreCase(docRoot) && !tag.toLowerCase().endsWith("list") && !tag.toLowerCase().endsWith("lists")) {
                    return tag;
                }
            }

            for (String tag : tagCounts.keySet()) {
                if (!tag.toLowerCase().endsWith("list") && !tag.equalsIgnoreCase(docRoot)) {
                    return tag;
                }
            }
        } catch (Exception ex) {
            log.warn("Could not detect record root element from {}: {}", sourcePath, ex.getMessage());
        }

        return "record";
    }

    private BufferedReader openReaderForSource(String sourcePath) throws Exception {
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            throw new IllegalArgumentException("XML source path cannot be blank.");
        }

        Path directPath = Paths.get(sourcePath);
        if (Files.exists(directPath)) {
            return new BufferedReader(new InputStreamReader(Files.newInputStream(directPath), StandardCharsets.UTF_8), 128 * 1024);
        }

        Resource res = resourceLoader.getResource(sourcePath);
        if (res.exists()) {
            return new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8), 128 * 1024);
        }

        // Try relative to workspace
        Path relativePath = Paths.get("/home/sv/Projects/xmldbtool", sourcePath);
        if (Files.exists(relativePath)) {
            return new BufferedReader(new InputStreamReader(Files.newInputStream(relativePath), StandardCharsets.UTF_8), 128 * 1024);
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

    public long getFileSizeBytes(String sourcePath) {
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            return -1L;
        }
        try {
            Path directPath = Paths.get(sourcePath.trim());
            if (Files.exists(directPath)) {
                return Files.size(directPath);
            }
            Path relativePath = Paths.get("/home/sv/Projects/xmldbtool", sourcePath.trim());
            if (Files.exists(relativePath)) {
                return Files.size(relativePath);
            }
            Resource res = resourceLoader.getResource(sourcePath.trim());
            if (res.exists()) {
                return res.contentLength();
            }
        } catch (Exception ex) {
            log.warn("Could not determine size of file '{}': {}", sourcePath, ex.getMessage());
        }
        return -1L;
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 0) return "Unknown";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }
}
