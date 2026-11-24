package com.bnpp.service;

import com.bnpp.model.DiscoveredElement;
import com.bnpp.model.FieldMapping;
import com.bnpp.model.SchemaDiscoveryRequest;
import com.bnpp.model.SchemaDiscoveryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static class EntityDef {
        String table;
        String pathStr;
        EntityDef parent;
        Map<String, String> attrs = new LinkedHashMap<>();
        Map<String, FieldInfo> fields = new LinkedHashMap<>();
        List<String> fieldOrder = new ArrayList<>();
        boolean hasDirectText = false;
        String directTextVal = "";
    }

    private static class FieldInfo {
        String pathStr;
        String sampleVal;
        FieldInfo(String pathStr, String sampleVal) {
            this.pathStr = pathStr;
            this.sampleVal = sampleVal;
        }
    }

    public List<FieldMapping> generateIngestionJson(String xmlFilePathOrContent) {
        if (xmlFilePathOrContent == null || xmlFilePathOrContent.trim().isEmpty()) {
            throw new IllegalArgumentException("XML input cannot be empty.");
        }
        String trimmed = xmlFilePathOrContent.trim();
        if (trimmed.startsWith("<")) {
            return generateIngestionJsonFromContent(trimmed);
        }
        File file = resolvePath(trimmed, null);
        try {
            return parseAndGenerateIngestionJson(new org.xml.sax.InputSource(new java.io.FileInputStream(file)));
        } catch (Exception ex) {
            log.error("Failed to generate ingestion.json from {}: {}", xmlFilePathOrContent, ex.getMessage(), ex);
            throw new RuntimeException("Failed to generate ingestion.json: " + ex.getMessage(), ex);
        }
    }

    public List<FieldMapping> generateIngestionJsonFromContent(String xmlContent) {
        try {
            return parseAndGenerateIngestionJson(new org.xml.sax.InputSource(new java.io.StringReader(xmlContent)));
        } catch (Exception ex) {
            log.error("Failed to generate ingestion.json from XML content: {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to generate ingestion.json: " + ex.getMessage(), ex);
        }
    }

    private List<FieldMapping> parseAndGenerateIngestionJson(org.xml.sax.InputSource inputSource) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {}

            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(inputSource);
            Element root = doc.getDocumentElement();

            Map<String, EntityDef> entityDefs = new LinkedHashMap<>();
            List<String> tableOrder = new ArrayList<>();

            scanElement(root, null, doc, new ArrayList<>(), null, entityDefs, tableOrder);

            List<FieldMapping> mappings = new ArrayList<>();

            for (String tbl : tableOrder) {
                EntityDef edef = entityDefs.get(tbl);
                String pkAttr = null;
                for (String a : edef.attrs.keySet()) {
                    if ("id".equalsIgnoreCase(a) || "code".equalsIgnoreCase(a) || (tbl + "_id").equalsIgnoreCase(a)) {
                        pkAttr = a;
                        break;
                    }
                }

                String pkChildField = null;
                if (pkAttr == null) {
                    for (String f : edef.fieldOrder) {
                        if ("id".equalsIgnoreCase(f) || "code".equalsIgnoreCase(f) || (tbl + "_id").equalsIgnoreCase(f) || (tbl + "id").equalsIgnoreCase(f)) {
                            pkChildField = f;
                            break;
                        }
                    }
                }

                // 1. Primary key (attribute or child field)
                if (pkAttr != null) {
                    FieldMapping pk = new FieldMapping();
                    pk.setXmlField(edef.pathStr + "/@" + pkAttr);
                    pk.setTargetTable(tbl);
                    pk.setTargetColumn(pkAttr.toLowerCase());
                    pk.setConstraintType("PK");
                    pk.setTransform(detectTransform(edef.attrs.get(pkAttr)));
                    mappings.add(pk);
                } else if (pkChildField != null) {
                    FieldInfo pkInfo = edef.fields.get(pkChildField);
                    FieldMapping pk = new FieldMapping();
                    pk.setXmlField(pkInfo.pathStr);
                    pk.setTargetTable(tbl);
                    pk.setTargetColumn(pkChildField.toLowerCase());
                    pk.setConstraintType("PK");
                    pk.setTransform(detectTransform(pkInfo.sampleVal));
                    mappings.add(pk);
                }

                // 2. Other attributes
                for (Map.Entry<String, String> entry : edef.attrs.entrySet()) {
                    String a = entry.getKey();
                    if (a.equalsIgnoreCase(pkAttr)) continue;
                    FieldMapping m = new FieldMapping();
                    m.setXmlField(edef.pathStr + "/@" + a);
                    m.setTargetTable(tbl);
                    m.setTargetColumn(a.toLowerCase());
                    if (a.toLowerCase().endsWith("_id") && !a.equalsIgnoreCase("id")) {
                        String refTable = a.substring(0, a.length() - 3);
                        m.setConstraintType("FK");
                        m.setReferences(refTable + ".id");
                    } else {
                        m.setConstraintType("NONE");
                    }
                    m.setTransform(detectTransform(entry.getValue()));
                    mappings.add(m);
                }

                // 3. Direct text content
                if (edef.hasDirectText) {
                    FieldMapping m = new FieldMapping();
                    m.setXmlField(edef.pathStr);
                    m.setTargetTable(tbl);
                    m.setTargetColumn("name");
                    m.setConstraintType("NONE");
                    m.setTransform(detectTransform(edef.directTextVal));
                    mappings.add(m);
                }

                // 4. Parent foreign key (refer parent id as foreign key)
                if (edef.parent != null) {
                    EntityDef parent = edef.parent;
                    String parentPkAttr = null;
                    for (String pa : parent.attrs.keySet()) {
                        if ("id".equalsIgnoreCase(pa) || "code".equalsIgnoreCase(pa) || (parent.table + "_id").equalsIgnoreCase(pa)) {
                            parentPkAttr = pa;
                            break;
                        }
                    }
                    String parentPkChild = null;
                    if (parentPkAttr == null) {
                        for (String pf : parent.fieldOrder) {
                            if ("id".equalsIgnoreCase(pf) || "code".equalsIgnoreCase(pf) || (parent.table + "_id").equalsIgnoreCase(pf)) {
                                parentPkChild = pf;
                                break;
                            }
                        }
                    }

                    String parentPkCol = parentPkAttr != null ? parentPkAttr.toLowerCase() : (parentPkChild != null ? parentPkChild.toLowerCase() : "id");
                    String fkColName = parent.table + "_" + parentPkCol;

                    if (!edef.fields.containsKey(fkColName)) {
                        FieldMapping parentFk = new FieldMapping();
                        if (parentPkAttr != null) {
                            parentFk.setXmlField(parent.pathStr + "/@" + parentPkAttr);
                            String tf = detectTransform(parent.attrs.getOrDefault(parentPkAttr, "1"));
                            parentFk.setTransform("NONE".equals(tf) ? "TO_INT" : tf);
                        } else if (parentPkChild != null) {
                            FieldInfo pfi = parent.fields.get(parentPkChild);
                            parentFk.setXmlField(pfi.pathStr);
                            String tf = detectTransform(pfi.sampleVal);
                            parentFk.setTransform("NONE".equals(tf) ? "TO_INT" : tf);
                        } else {
                            parentFk.setXmlField(parent.pathStr + "/@" + parentPkCol);
                            parentFk.setTransform("TO_INT");
                        }
                        parentFk.setTargetTable(tbl);
                        parentFk.setTargetColumn(fkColName);
                        parentFk.setConstraintType("FK");
                        parentFk.setReferences(parent.table + "." + parentPkCol);
                        mappings.add(parentFk);
                    }
                }

                // 5. Child fields
                List<String> orderedFields = groupFields(edef.fieldOrder);
                for (String fName : orderedFields) {
                    if (fName.equalsIgnoreCase(pkChildField)) {
                        // Already added as PK in step 1!
                        continue;
                    }
                    FieldInfo fInfo = edef.fields.get(fName);
                    FieldMapping fm = new FieldMapping();
                    fm.setXmlField(fInfo.pathStr);
                    fm.setTargetTable(tbl);
                    fm.setTargetColumn(fName);

                    if (fName.endsWith("_id") && !fName.equalsIgnoreCase("id")) {
                        String refTable = fName.substring(0, fName.length() - 3);
                        fm.setConstraintType("FK");
                        fm.setReferences(refTable + ".id");
                        fm.setTransform(detectTransform(fInfo.sampleVal));
                    } else {
                        fm.setConstraintType("NONE");
                        fm.setTransform(detectTransform(fInfo.sampleVal));
                    }
                    mappings.add(fm);
                }
            }

            return mappings;
        } catch (Exception ex) {
            log.error("Failed to generate ingestion.json: {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to generate ingestion.json: " + ex.getMessage(), ex);
        }
    }

    private void scanElement(Element elem, Element parent, Document doc, List<String> pathParts, EntityDef parentEntity,
                             Map<String, EntityDef> entityDefs, List<String> tableOrder) {
        List<String> rawPath = new ArrayList<>(pathParts);
        rawPath.add(elem.getTagName());
        List<String> normPath = collapseConsecutiveDuplicates(rawPath);
        String pathStr = String.join("/", normPath);

        boolean isEntity = isEntityElement(elem, parent, doc);
        EntityDef currentEntity = parentEntity;

        if (isEntity) {
            String tbl = elem.getTagName().toLowerCase();
            EntityDef edef = entityDefs.get(tbl);
            if (edef == null) {
                edef = new EntityDef();
                edef.table = tbl;
                edef.pathStr = pathStr;
                edef.parent = parentEntity;
                entityDefs.put(tbl, edef);
                tableOrder.add(tbl);
            }
            NamedNodeMap attrMap = elem.getAttributes();
            if (attrMap != null) {
                for (int i = 0; i < attrMap.getLength(); i++) {
                    Node attr = attrMap.item(i);
                    String k = attr.getNodeName();
                    String v = attr.getNodeValue() != null ? attr.getNodeValue().trim() : "";
                    if (!edef.attrs.containsKey(k) || (edef.attrs.get(k).isEmpty() && !v.isEmpty())) {
                        edef.attrs.put(k, v);
                    }
                }
            }

            List<Element> childElements = getChildElements(elem);
            String text = getDirectTextContent(elem);
            if (!text.isEmpty() && childElements.isEmpty()) {
                edef.hasDirectText = true;
                if (edef.directTextVal.isEmpty()) {
                    edef.directTextVal = text;
                }
            }
            currentEntity = edef;
        }

        List<Element> children = getChildElements(elem);
        for (Element child : children) {
            boolean childIsEntity = isEntityElement(child, elem, doc);
            List<Element> grandChildren = getChildElements(child);
            if (currentEntity != null && !childIsEntity && grandChildren.isEmpty()) {
                List<String> cRaw = new ArrayList<>(rawPath);
                cRaw.add(child.getTagName());
                String cPath = String.join("/", collapseConsecutiveDuplicates(cRaw));
                String fName = child.getTagName().toLowerCase();
                String val = child.getTextContent() != null ? child.getTextContent().trim() : "";
                if (!currentEntity.fields.containsKey(fName)) {
                    currentEntity.fields.put(fName, new FieldInfo(cPath, val));
                    currentEntity.fieldOrder.add(fName);
                } else if (currentEntity.fields.get(fName).sampleVal.isEmpty() && !val.isEmpty()) {
                    currentEntity.fields.get(fName).sampleVal = val;
                }

                // Also inspect leaf child attributes if any (e.g. currency, unit)
                NamedNodeMap childAttrs = child.getAttributes();
                if (childAttrs != null && childAttrs.getLength() > 0) {
                    for (int i = 0; i < childAttrs.getLength(); i++) {
                        Node cattr = childAttrs.item(i);
                        String aName = cattr.getNodeName().toLowerCase();
                        String aVal = cattr.getNodeValue() != null ? cattr.getNodeValue().trim() : "";
                        String aPath = cPath + "/@" + aName;
                        String fCol = fName + "_" + aName;
                        if (!currentEntity.fields.containsKey(fCol)) {
                            currentEntity.fields.put(fCol, new FieldInfo(aPath, aVal));
                            currentEntity.fieldOrder.add(fCol);
                        }
                    }
                }
            }
            scanElement(child, elem, doc, rawPath, currentEntity, entityDefs, tableOrder);
        }
    }

    private boolean isLeafElement(Element e) {
        if (e == null) return false;
        NamedNodeMap attrs = e.getAttributes();
        if (attrs != null && attrs.getLength() > 0) return false;
        return getChildElements(e).isEmpty();
    }

    private boolean hasIdChild(Element elem) {
        List<Element> children = getChildElements(elem);
        for (Element c : children) {
            String tag = c.getTagName();
            if (isLeafElement(c) && ("id".equalsIgnoreCase(tag) || "code".equalsIgnoreCase(tag) || (elem.getTagName() + "_id").equalsIgnoreCase(tag) || (elem.getTagName() + "id").equalsIgnoreCase(tag))) {
                return true;
            }
        }
        return false;
    }

    private boolean isEntityElement(Element elem, Element parent, Document doc) {
        if (elem == null) return false;

        // 1. Root element is never an entity unless it is a single-record document (all children are leaves)
        if (doc != null && elem == doc.getDocumentElement()) {
            List<Element> children = getChildElements(elem);
            if (children.isEmpty()) return false;
            boolean allLeaves = true;
            for (Element c : children) {
                if (!isLeafElement(c)) {
                    allLeaves = false;
                    break;
                }
            }
            return allLeaves && (hasIdAttribute(elem) || hasIdChild(elem));
        }

        // 2. Explicit ID or Code attribute
        if (hasIdAttribute(elem)) {
            return true;
        }

        // 3. Explicit ID or Code child element
        if (hasIdChild(elem)) {
            return true;
        }

        // 4. Repeated sibling element under parent with non-leaf content
        if (parent != null) {
            List<Element> siblings = getChildElements(parent);
            int sameTagCount = 0;
            for (Element s : siblings) {
                if (s.getTagName().equalsIgnoreCase(elem.getTagName())) {
                    sameTagCount++;
                }
            }
            if (sameTagCount > 1 && (!getChildElements(elem).isEmpty() || (elem.getAttributes() != null && elem.getAttributes().getLength() > 0))) {
                return true;
            }

            // 5. Parent tag name indicates a collection / list container of this element
            String pTag = parent.getTagName().toLowerCase();
            String eTag = elem.getTagName().toLowerCase();
            boolean isPluralMatch = pTag.equals(eTag + "s") ||
                                    pTag.equals(eTag + "es") ||
                                    pTag.equals(eTag + "list") ||
                                    pTag.equals(eTag + "lists") ||
                                    pTag.endsWith("s") ||
                                    pTag.endsWith("list") ||
                                    pTag.endsWith("lists") ||
                                    pTag.endsWith("records") ||
                                    pTag.endsWith("items") ||
                                    pTag.endsWith("data") ||
                                    pTag.endsWith("collection");
            if (isPluralMatch && (!getChildElements(elem).isEmpty() || (elem.getAttributes() != null && elem.getAttributes().getLength() > 0))) {
                return true;
            }
        }

        // 6. All children are leaf elements (meaning this element is a record containing columns)
        List<Element> children = getChildElements(elem);
        if (!children.isEmpty()) {
            boolean allChildrenAreLeaves = true;
            for (Element c : children) {
                if (!isLeafElement(c)) {
                    allChildrenAreLeaves = false;
                    break;
                }
            }
            if (allChildrenAreLeaves) {
                return true;
            }
        }

        return false;
    }

    private boolean hasIdAttribute(Element elem) {
        NamedNodeMap attrMap = elem.getAttributes();
        if (attrMap == null) return false;
        for (int i = 0; i < attrMap.getLength(); i++) {
            String name = attrMap.item(i).getNodeName();
            if ("id".equalsIgnoreCase(name) || "code".equalsIgnoreCase(name) || (elem.getTagName() + "_id").equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private List<Element> getChildElements(Element elem) {
        List<Element> list = new ArrayList<>();
        NodeList nl = elem.getChildNodes();
        if (nl == null) return list;
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                list.add((Element) n);
            }
        }
        return list;
    }

    private String getDirectTextContent(Element elem) {
        StringBuilder sb = new StringBuilder();
        NodeList nl = elem.getChildNodes();
        if (nl == null) return "";
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString().trim();
    }

    public static List<String> collapseConsecutiveDuplicates(List<String> pathParts) {
        List<String> res = new ArrayList<>();
        for (String p : pathParts) {
            if (res.isEmpty() || !res.get(res.size() - 1).equalsIgnoreCase(p)) {
                res.add(p);
            }
        }
        return res;
    }

    public static String detectTransform(String val) {
        if (val == null) return "NONE";
        String s = val.trim();
        if (s.isEmpty()) return "NONE";
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return "TO_BOOLEAN";
        }
        if (s.matches("^-?\\d+$")) {
            return "TO_INT";
        }
        if (s.matches("^-?\\d*\\.\\d+$")) {
            return "TO_DECIMAL";
        }
        return "NONE";
    }

    public static List<String> groupFields(List<String> fields) {
        Pattern pattern = Pattern.compile("^(.*?)(\\d+)(.*)$");
        Map<String, List<int[]>> familyIndices = new LinkedHashMap<>();
        Map<String, List<String>> familyItems = new LinkedHashMap<>();
        Map<String, Integer> familyFirstIndex = new LinkedHashMap<>();
        List<String[]> nonFamily = new ArrayList<>();

        for (int i = 0; i < fields.size(); i++) {
            String f = fields.get(i);
            Matcher m = pattern.matcher(f);
            if (m.matches()) {
                String prefix = m.group(1);
                int num = Integer.parseInt(m.group(2));
                String suffix = m.group(3);
                String famKey = prefix + "##" + suffix;
                if (!familyFirstIndex.containsKey(famKey)) {
                    familyFirstIndex.put(famKey, i);
                    familyItems.put(famKey, new ArrayList<>());
                    familyIndices.put(famKey, new ArrayList<>());
                }
                familyItems.get(famKey).add(f);
                familyIndices.get(famKey).add(new int[]{num, familyItems.get(famKey).size() - 1});
            } else {
                nonFamily.add(new String[]{String.valueOf(i), f});
            }
        }

        class Slot {
            final int firstIndex;
            final List<String> items = new ArrayList<>();
            Slot(int firstIndex) { this.firstIndex = firstIndex; }
        }

        List<Slot> slots = new ArrayList<>();
        for (Map.Entry<String, List<int[]>> entry : familyIndices.entrySet()) {
            String famKey = entry.getKey();
            int firstIdx = familyFirstIndex.get(famKey);
            List<int[]> pairs = entry.getValue();
            pairs.sort(Comparator.comparingInt(p -> p[0]));
            List<String> rawList = familyItems.get(famKey);
            Slot slot = new Slot(firstIdx);
            for (int[] p : pairs) {
                slot.items.add(rawList.get(p[1]));
            }
            slots.add(slot);
        }

        for (String[] nf : nonFamily) {
            Slot slot = new Slot(Integer.parseInt(nf[0]));
            slot.items.add(nf[1]);
            slots.add(slot);
        }

        slots.sort(Comparator.comparingInt(s -> s.firstIndex));
        List<String> result = new ArrayList<>();
        for (Slot slot : slots) {
            result.addAll(slot.items);
        }
        return result;
    }
}
