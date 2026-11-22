package com.bnpp.service;

import com.bnpp.model.DiscoveredElement;
import com.bnpp.model.FieldMapping;
import com.bnpp.model.SchemaDiscoveryRequest;
import com.bnpp.model.SchemaDiscoveryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class XmlSchemaDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(XmlSchemaDiscoveryService.class);

    public File resolvePath(String fileName, String hintPath) {
        if (fileName == null || fileName.trim().isEmpty()) {
            if (hintPath != null && !hintPath.trim().isEmpty()) {
                fileName = hintPath;
            } else {
                throw new IllegalArgumentException("fileName is required.");
            }
        }
        fileName = fileName.trim().replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");

        File f = new File(fileName);
        if (f.exists() && f.isFile()) return f.getAbsoluteFile();

        if (hintPath != null && !hintPath.trim().isEmpty()) {
            File hint = new File(hintPath.trim().replaceAll("^\"|\"$", ""));
            if (hint.exists() && hint.isFile()) return hint.getAbsoluteFile();
            if (hint.isDirectory()) {
                File nested = new File(hint, fileName);
                if (nested.exists() && nested.isFile()) return nested.getAbsoluteFile();
            }
        }

        File uploads = new File("uploads", fileName);
        if (uploads.exists() && uploads.isFile()) return uploads.getAbsoluteFile();

        File sample = new File("sample-data", fileName);
        if (sample.exists() && sample.isFile()) return sample.getAbsoluteFile();

        Path ws = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path p = ws.resolve(fileName);
        if (Files.exists(p) && Files.isRegularFile(p)) return p.toFile().getAbsoluteFile();

        // Search in uploads or sample-data by base name
        String baseName = Paths.get(fileName).getFileName().toString();
        File uBase = new File("uploads", baseName);
        if (uBase.exists() && uBase.isFile()) return uBase.getAbsoluteFile();
        File sBase = new File("sample-data", baseName);
        if (sBase.exists() && sBase.isFile()) return sBase.getAbsoluteFile();

        // Search in user home and Downloads
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            File hFile = new File(userHome, baseName);
            if (hFile.exists() && hFile.isFile()) return hFile.getAbsoluteFile();
            File dFile = new File(new File(userHome, "Downloads"), baseName);
            if (dFile.exists() && dFile.isFile()) return dFile.getAbsoluteFile();
        }

        File tmpFile = new File("/tmp", baseName);
        if (tmpFile.exists() && tmpFile.isFile()) return tmpFile.getAbsoluteFile();

        throw new IllegalArgumentException("Could not find file on local filesystem: " + fileName);
    }

    public List<String> detectRoots(String xmlFilePath) {
        File file = resolvePath(xmlFilePath, null);
        List<String> roots = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            XMLStreamReader reader = factory.createXMLStreamReader(br);
            Set<String> encountered = new LinkedHashSet<>();
            int depth = 0;
            while (reader.hasNext() && depth < 200) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = reader.getLocalName();
                    encountered.add(name);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                }
            }
            roots.addAll(encountered);
        } catch (Exception ex) {
            log.error("Failed to detect XML roots from {}: {}", xmlFilePath, ex.getMessage());
        }
        return roots;
    }

    public SchemaDiscoveryResponse discoverSchema(SchemaDiscoveryRequest req) {
        if (req == null || req.getXmlFilePath() == null) {
            return new SchemaDiscoveryResponse(false, "XML file path is required.");
        }
        File file = resolvePath(req.getXmlFilePath(), null);
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        Map<String, DiscoveredElement> elementMap = new LinkedHashMap<>();
        List<String> candidateRoots = new ArrayList<>();
        List<FieldMapping> suggestedMappings = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            XMLStreamReader reader = factory.createXMLStreamReader(br);
            Deque<String> pathStack = new ArrayDeque<>();
            long nodeCount = 0;
            int sampleLimit = req.getSampleLimit() > 0 ? req.getSampleLimit() : 200;

            while (reader.hasNext() && nodeCount < sampleLimit) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        nodeCount++;
                        String localName = reader.getLocalName();
                        pathStack.push(localName);
                        String currentPath = String.join("/", reverseDeque(pathStack));

                        if (pathStack.size() >= 2 && !candidateRoots.contains(localName)) {
                            candidateRoots.add(localName);
                        }

                        // Attributes
                        int attrCount = reader.getAttributeCount();
                        for (int i = 0; i < attrCount; i++) {
                            String attrName = reader.getAttributeLocalName(i);
                            String attrVal = reader.getAttributeValue(i);
                            String attrPath = currentPath + "/@" + attrName;

                            DiscoveredElement de = elementMap.computeIfAbsent(attrPath, k -> {
                                DiscoveredElement d = new DiscoveredElement(attrPath, "@" + attrName, "attribute", currentPath, attrVal, "STRING");
                                d.setTargetTable(capitalize(localName));
                                d.setTargetColumn(attrName.toLowerCase());
                                if ("id".equalsIgnoreCase(attrName) || "code".equalsIgnoreCase(attrName)) {
                                    d.setConstraintType("PK");
                                } else {
                                    d.setConstraintType("NONE");
                                }
                                d.setTransform("NONE");
                                return d;
                            });
                            de.setOccurrenceCount(de.getOccurrenceCount() + 1);
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (!pathStack.isEmpty() && !reader.isWhiteSpace()) {
                            String text = reader.getText().trim();
                            if (!text.isEmpty()) {
                                String currentPath = String.join("/", reverseDeque(pathStack));
                                String localName = pathStack.peek();
                                String parentPath = "";
                                if (pathStack.size() > 1) {
                                    List<String> list = reverseDeque(pathStack);
                                    parentPath = String.join("/", list.subList(0, list.size() - 1));
                                }

                                DiscoveredElement de = elementMap.computeIfAbsent(currentPath, k -> {
                                    DiscoveredElement d = new DiscoveredElement(currentPath, localName, "element", currentPath, text, "STRING");
                                    String targetTbl = pathStack.size() > 1 ? getParentName(pathStack) : localName;
                                    d.setTargetTable(capitalize(targetTbl));
                                    d.setTargetColumn(localName.toLowerCase());
                                    d.setConstraintType("NONE");
                                    d.setTransform("NONE");
                                    return d;
                                });
                                de.setOccurrenceCount(de.getOccurrenceCount() + 1);
                            }
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        if (!pathStack.isEmpty()) {
                            pathStack.pop();
                        }
                    }
                }
            }

            for (DiscoveredElement de : elementMap.values()) {
                FieldMapping fm = new FieldMapping();
                fm.setXmlField(de.getPath());
                fm.setTargetTable(de.getTargetTable());
                fm.setTargetColumn(de.getTargetColumn());
                fm.setConstraintType(de.getConstraintType());
                fm.setTransform(de.getTransform());
                suggestedMappings.add(fm);
            }

            SchemaDiscoveryResponse resp = new SchemaDiscoveryResponse(true, "Successfully inspected XML structure.");
            resp.setElements(new ArrayList<>(elementMap.values()));
            resp.setCandidateRootElements(candidateRoots);
            resp.setSuggestedMappings(suggestedMappings);
            resp.setTotalNodes(nodeCount);
            return resp;
        } catch (Exception ex) {
            log.error("Failed to discover schema from {}: {}", req.getXmlFilePath(), ex.getMessage());
            return new SchemaDiscoveryResponse(false, "Failed to inspect XML: " + ex.getMessage());
        }
    }

    private List<String> reverseDeque(Deque<String> deque) {
        List<String> list = new ArrayList<>(deque);
        Collections.reverse(list);
        return list;
    }

    private String getParentName(Deque<String> stack) {
        Iterator<String> it = stack.iterator();
        if (it.hasNext()) it.next(); // skip current
        if (it.hasNext()) return it.next();
        return "Record";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    public Map<String, Object> validateXmlTags(String xmlFilePath, List<FieldMapping> mappings) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> mismatches = new ArrayList<>();
        Set<String> allXmlTags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> allXmlAttributes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Set<String>> elementAttributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> allPaths = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        File file;
        try {
            file = resolvePath(xmlFilePath, null);
        } catch (Exception ex) {
            result.put("success", false);
            result.put("matched", false);
            result.put("message", "Could not locate XML file: " + ex.getMessage());
            result.put("mismatches", List.of("XML file not found: " + xmlFilePath));
            return result;
        }

        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            XMLStreamReader reader = factory.createXMLStreamReader(br);
            Deque<String> stack = new ArrayDeque<>();
            long nodeCount = 0;
            while (reader.hasNext() && nodeCount < 50000) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    nodeCount++;
                    String localName = reader.getLocalName();
                    allXmlTags.add(localName);
                    stack.push(localName);

                    String currentPath = String.join("/", reverseDeque(stack));
                    allPaths.add(currentPath);

                    int attrCount = reader.getAttributeCount();
                    Set<String> elemAttrs = elementAttributes.computeIfAbsent(localName, k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
                    for (int i = 0; i < attrCount; i++) {
                        String attrName = reader.getAttributeLocalName(i);
                        allXmlAttributes.add(attrName);
                        elemAttrs.add(attrName);
                        allPaths.add(currentPath + "/@" + attrName);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Error scanning XML file {}: {}", xmlFilePath, ex.getMessage());
            result.put("success", false);
            result.put("matched", false);
            result.put("message", "Error reading XML: " + ex.getMessage());
            result.put("mismatches", List.of("Failed to parse XML file: " + ex.getMessage()));
            return result;
        }

        if (mappings != null) {
            for (FieldMapping m : mappings) {
                if (m == null || m.getXmlField() == null) continue;
                String xmlField = m.getXmlField().trim();
                if (com.bnpp.batch.DynamicXmlItemProcessor.isAutogenerated(xmlField)) {
                    // Requirement 4: ignore autogenerated columns
                    continue;
                }

                String clean = xmlField.replaceAll("^/+", "").replaceAll("/+$", "");
                String[] parts = clean.split("/");

                // Check if full path matched directly
                boolean matched = false;
                for (String p : allPaths) {
                    if (p.equalsIgnoreCase(clean) || p.toLowerCase().endsWith("/" + clean.toLowerCase())) {
                        matched = true;
                        break;
                    }
                }
                if (matched) continue;

                // If full path didn't match directly, verify each element and attribute part
                for (int i = 0; i < parts.length; i++) {
                    String part = parts[i];
                    if (part.startsWith("@")) {
                        String attrName = part.substring(1);
                        String parentElem = i > 0 ? parts[i - 1] : null;
                        if (parentElem != null && elementAttributes.containsKey(parentElem)) {
                            if (!elementAttributes.get(parentElem).contains(attrName)) {
                                mismatches.add(String.format("Attribute '@%s' not found on element <%s> (field: %s)", attrName, parentElem, xmlField));
                            }
                        } else if (!allXmlAttributes.contains(attrName)) {
                            mismatches.add(String.format("Attribute '@%s' not found in XML file (field: %s)", attrName, xmlField));
                        }
                    } else {
                        if (!allXmlTags.contains(part)) {
                            mismatches.add(String.format("XML tag '<%s>' not found in XML file (field: %s)", part, xmlField));
                        }
                    }
                }
            }
        }

        result.put("success", true);
        result.put("matched", mismatches.isEmpty());
        result.put("mismatches", mismatches);
        result.put("detectedTags", allXmlTags);
        return result;
    }
}
