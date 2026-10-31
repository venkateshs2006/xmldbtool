package com.enterprise.xmldbtool.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.oxm.Unmarshaller;
import org.springframework.oxm.XmlMappingException;
import org.springframework.stereotype.Component;
import org.springframework.util.xml.StaxUtils;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Source;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enterprise StAX Dynamic XML Unmarshaller.
 * Reads XML fragments into a Map<String, Object> dynamically without DOM or hardcoded JAXB POJOs.
 * All XML attributes are extracted and flattened with an '@' prefix (e.g., @id, @status).
 * Supports repeating collection entities by capturing each completed entity in '_entities_<tag>' lists.
 */
@Component
public class DynamicXmlUnmarshaller implements Unmarshaller {

    private static final Logger log = LoggerFactory.getLogger(DynamicXmlUnmarshaller.class);

    private static class ElementFrame {
        final String tagName;
        final ElementFrame parent;
        final Map<String, Object> data = new LinkedHashMap<>();
        final StringBuilder text = new StringBuilder();
        int childElementCount = 0;
        int childEntityCount = 0;
        boolean hasDirectAttributes = false;

        ElementFrame(String tagName, ElementFrame parent) {
            this.tagName = tagName;
            this.parent = parent;
        }
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return Map.class.isAssignableFrom(clazz) || clazz == Object.class;
    }

    @Override
    public Object unmarshal(Source source) throws IOException, XmlMappingException {
        try {
            XMLEventReader eventReader = extractEventReader(source);
            if (eventReader != null) {
                return parseFragmentFromEventReader(eventReader);
            }

            XMLStreamReader streamReader = extractStreamReader(source);
            if (streamReader != null) {
                return parseFragmentFromStreamReader(streamReader);
            }

            // Fallback for stream source
            if (source instanceof StreamSource streamSource) {
                XMLInputFactory factory = XMLInputFactory.newInstance();
                factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

                InputStream is = streamSource.getInputStream();
                if (is != null) {
                    XMLEventReader reader = factory.createXMLEventReader(is);
                    return parseFragmentFromEventReader(reader);
                }
                Reader r = streamSource.getReader();
                if (r != null) {
                    XMLEventReader reader = factory.createXMLEventReader(r);
                    return parseFragmentFromEventReader(reader);
                }
            }

            throw new IllegalArgumentException("Unsupported XML Source type: " + (source != null ? source.getClass().getName() : "null"));
        } catch (Exception ex) {
            throw new DynamicXmlParseException("Failed to dynamically unmarshal XML fragment: " + ex.getMessage(), ex);
        }
    }

    /**
     * Parses a single record fragment using XMLEventReader.
     * Extracts all elements and attributes with '@' prefix, preserving repeating entity collections.
     */
    public Map<String, Object> parseFragmentFromEventReader(XMLEventReader reader) throws Exception {
        Map<String, Object> recordMap = new LinkedHashMap<>();
        int depth = 0;
        String rootTagName = null;
        ElementFrame currentFrame = null;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartElement()) {
                StartElement startElement = event.asStartElement();
                String localName = startElement.getName().getLocalPart();

                if (depth == 0) {
                    rootTagName = localName;
                }
                depth++;

                ElementFrame frame = new ElementFrame(localName, currentFrame);
                if (currentFrame != null) {
                    currentFrame.childElementCount++;
                }

                // Extract all attributes and record both qualified (tag/@attr) and unqualified (@attr, attr)
                Iterator<?> attributes = startElement.getAttributes();
                while (attributes.hasNext()) {
                    Attribute attr = (Attribute) attributes.next();
                    String rawAttrName = attr.getName().getLocalPart();
                    String attrValue = attr.getValue();

                    frame.hasDirectAttributes = true;
                    frame.data.put("@" + rawAttrName, attrValue);
                    frame.data.put(rawAttrName, attrValue);
                    frame.data.put(localName + "/@" + rawAttrName, attrValue);
                    frame.data.put(localName + "/" + rawAttrName, attrValue);

                    recordMap.put(localName + "/@" + rawAttrName, attrValue);
                    if (depth == 1) {
                        recordMap.put("@" + rawAttrName, attrValue);
                        recordMap.put(rawAttrName, attrValue);
                    } else {
                        recordMap.putIfAbsent("@" + rawAttrName, attrValue);
                        recordMap.putIfAbsent(rawAttrName, attrValue);
                    }
                }

                // Inherit contextual identifiers from ancestor frames (e.g. person_id for nested address)
                ElementFrame anc = currentFrame;
                while (anc != null) {
                    String ancTag = anc.tagName.toLowerCase();
                    Object ancId = anc.data.get("id");
                    if (ancId == null) ancId = anc.data.get("@id");
                    if (ancId != null) {
                        frame.data.putIfAbsent(ancTag + "_id", ancId);
                        frame.data.putIfAbsent(ancTag + "id", ancId);
                        frame.data.putIfAbsent(ancTag + "/@id", ancId);
                        String sAnc = singularize(ancTag);
                        if (!sAnc.equals(ancTag)) {
                            frame.data.putIfAbsent(sAnc + "_id", ancId);
                            frame.data.putIfAbsent(sAnc + "id", ancId);
                            frame.data.putIfAbsent(sAnc + "/@id", ancId);
                        }
                    }
                    anc = anc.parent;
                }

                currentFrame = frame;
            } else if (event.isCharacters()) {
                Characters characters = event.asCharacters();
                if (!characters.isWhiteSpace() && currentFrame != null) {
                    currentFrame.text.append(characters.getData());
                }
            } else if (event.isEndElement()) {
                EndElement endElement = event.asEndElement();
                String closedTag = endElement.getName().getLocalPart();
                depth--;

                if (currentFrame != null) {
                    ElementFrame finishedFrame = currentFrame;
                    currentFrame = finishedFrame.parent;
                    String trimmedText = finishedFrame.text.toString().trim();

                    if (!trimmedText.isEmpty()) {
                        finishedFrame.data.put(closedTag, trimmedText);
                        finishedFrame.data.put("value", trimmedText);
                        recordMap.put(closedTag, trimmedText);
                    }

                    // Propagate values to parent frame
                    if (finishedFrame.parent != null) {
                        if (!trimmedText.isEmpty()) {
                            finishedFrame.parent.data.put(closedTag, trimmedText);
                            finishedFrame.parent.data.put(closedTag + "/value", trimmedText);
                        }
                        for (Map.Entry<String, Object> entry : finishedFrame.data.entrySet()) {
                            if (entry.getKey().contains("/")) {
                                finishedFrame.parent.data.putIfAbsent(entry.getKey(), entry.getValue());
                            } else {
                                finishedFrame.parent.data.putIfAbsent(closedTag + "/" + entry.getKey(), entry.getValue());
                            }
                        }
                    }

                    // Determine if this is an entity record (and not a container wrapper of child entities)
                    boolean isEntityRecord = (finishedFrame.hasDirectAttributes || (finishedFrame.childElementCount > 0 && finishedFrame.childEntityCount == 0))
                            && depth > 0;

                    if (isEntityRecord) {
                        if (finishedFrame.parent != null) {
                            finishedFrame.parent.childEntityCount++;
                        }
                        String tagLower = closedTag.toLowerCase();
                        recordEntity(recordMap, tagLower, finishedFrame.data);
                        String s = singularize(tagLower);
                        if (!s.equals(tagLower)) {
                            recordEntity(recordMap, s, finishedFrame.data);
                        }
                        String p = pluralize(tagLower);
                        if (!p.equals(tagLower) && !p.equals(s)) {
                            recordEntity(recordMap, p, finishedFrame.data);
                        }
                    }
                }

                // Reached the closing tag of the record fragment
                if (depth == 0 || (rootTagName != null && rootTagName.equals(closedTag) && depth <= 0)) {
                    break;
                }
            }
        }

        return recordMap;
    }

    /**
     * Parses a single record fragment using XMLStreamReader.
     */
    public Map<String, Object> parseFragmentFromStreamReader(XMLStreamReader reader) throws Exception {
        Map<String, Object> recordMap = new LinkedHashMap<>();
        int depth = 0;
        String rootTagName = null;
        ElementFrame currentFrame = null;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();
                if (depth == 0) {
                    rootTagName = localName;
                }
                depth++;

                ElementFrame frame = new ElementFrame(localName, currentFrame);
                if (currentFrame != null) {
                    currentFrame.childElementCount++;
                }

                int attrCount = reader.getAttributeCount();
                if (attrCount > 0) {
                    frame.hasDirectAttributes = true;
                }
                for (int i = 0; i < attrCount; i++) {
                    String rawAttrName = reader.getAttributeLocalName(i);
                    String attrValue = reader.getAttributeValue(i);

                    frame.data.put("@" + rawAttrName, attrValue);
                    frame.data.put(rawAttrName, attrValue);
                    frame.data.put(localName + "/@" + rawAttrName, attrValue);
                    frame.data.put(localName + "/" + rawAttrName, attrValue);

                    recordMap.put(localName + "/@" + rawAttrName, attrValue);
                    if (depth == 1) {
                        recordMap.put("@" + rawAttrName, attrValue);
                        recordMap.put(rawAttrName, attrValue);
                    } else {
                        recordMap.putIfAbsent("@" + rawAttrName, attrValue);
                        recordMap.putIfAbsent(rawAttrName, attrValue);
                    }
                }

                ElementFrame anc = currentFrame;
                while (anc != null) {
                    String ancTag = anc.tagName.toLowerCase();
                    Object ancId = anc.data.get("id");
                    if (ancId == null) ancId = anc.data.get("@id");
                    if (ancId != null) {
                        frame.data.putIfAbsent(ancTag + "_id", ancId);
                        frame.data.putIfAbsent(ancTag + "id", ancId);
                        frame.data.putIfAbsent(ancTag + "/@id", ancId);
                        String sAnc = singularize(ancTag);
                        if (!sAnc.equals(ancTag)) {
                            frame.data.putIfAbsent(sAnc + "_id", ancId);
                            frame.data.putIfAbsent(sAnc + "id", ancId);
                            frame.data.putIfAbsent(sAnc + "/@id", ancId);
                        }
                    }
                    anc = anc.parent;
                }

                currentFrame = frame;
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (!reader.isWhiteSpace() && currentFrame != null) {
                    currentFrame.text.append(reader.getText());
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String closedTag = reader.getLocalName();
                depth--;

                if (currentFrame != null) {
                    ElementFrame finishedFrame = currentFrame;
                    currentFrame = finishedFrame.parent;
                    String text = finishedFrame.text.toString().trim();

                    if (!text.isEmpty()) {
                        finishedFrame.data.put(closedTag, text);
                        finishedFrame.data.put("value", text);
                        recordMap.put(closedTag, text);
                    }

                    if (finishedFrame.parent != null) {
                        if (!text.isEmpty()) {
                            finishedFrame.parent.data.put(closedTag, text);
                            finishedFrame.parent.data.put(closedTag + "/value", text);
                        }
                        for (Map.Entry<String, Object> entry : finishedFrame.data.entrySet()) {
                            if (entry.getKey().contains("/")) {
                                finishedFrame.parent.data.putIfAbsent(entry.getKey(), entry.getValue());
                            } else {
                                finishedFrame.parent.data.putIfAbsent(closedTag + "/" + entry.getKey(), entry.getValue());
                            }
                        }
                    }

                    boolean isEntityRecord = (finishedFrame.hasDirectAttributes || (finishedFrame.childElementCount > 0 && finishedFrame.childEntityCount == 0))
                            && depth > 0;

                    if (isEntityRecord) {
                        if (finishedFrame.parent != null) {
                            finishedFrame.parent.childEntityCount++;
                        }
                        String tagLower = closedTag.toLowerCase();
                        recordEntity(recordMap, tagLower, finishedFrame.data);
                        String s = singularize(tagLower);
                        if (!s.equals(tagLower)) {
                            recordEntity(recordMap, s, finishedFrame.data);
                        }
                        String p = pluralize(tagLower);
                        if (!p.equals(tagLower) && !p.equals(s)) {
                            recordEntity(recordMap, p, finishedFrame.data);
                        }
                    }
                }

                if (depth == 0 || (rootTagName != null && rootTagName.equals(closedTag) && depth <= 0)) {
                    break;
                }
            }
        }

        return recordMap;
    }

    public static String singularize(String word) {
        if (word == null || word.length() <= 2) return word != null ? word : "";
        String w = word.toLowerCase();
        if (w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("ses") || w.endsWith("shes") || w.endsWith("ches") || w.endsWith("xes")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.matches(".*s\\d+$")) {
            return w.replaceAll("s(\\d+)$", "$1");
        }
        if (w.endsWith("s") && !w.endsWith("ss")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    public static String pluralize(String word) {
        if (word == null || word.isEmpty()) return word != null ? word : "";
        String w = word.toLowerCase();
        if (w.endsWith("s")) return w;
        if (w.endsWith("y") && !w.endsWith("ay") && !w.endsWith("ey") && !w.endsWith("oy") && !w.endsWith("uy")) {
            return w.substring(0, w.length() - 1) + "ies";
        }
        if (w.endsWith("ch") || w.endsWith("sh") || w.endsWith("x") || w.endsWith("z")) {
            return w + "es";
        }
        if (w.matches(".*\\d+$")) {
            return w.replaceAll("(\\d+)$", "s$1");
        }
        return w + "s";
    }

    @SuppressWarnings("unchecked")
    private void recordEntity(Map<String, Object> recordMap, String tag, Map<String, Object> data) {
        if (tag == null || tag.isEmpty() || data == null || data.isEmpty()) return;
        String key = "_entities_" + tag;
        List<Map<String, Object>> list = (List<Map<String, Object>>) recordMap.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(new LinkedHashMap<>(data));
    }

    private XMLEventReader extractEventReader(Source source) {
        if (StaxUtils.isStaxSource(source)) {
            return StaxUtils.getXMLEventReader(source);
        }
        if (source instanceof StAXSource staxSource) {
            return staxSource.getXMLEventReader();
        }
        try {
            Method getXMLEventReader = source.getClass().getMethod("getXMLEventReader");
            return (XMLEventReader) getXMLEventReader.invoke(source);
        } catch (Exception ignored) {
            return null;
        }
    }

    private XMLStreamReader extractStreamReader(Source source) {
        if (StaxUtils.isStaxSource(source)) {
            return StaxUtils.getXMLStreamReader(source);
        }
        if (source instanceof StAXSource staxSource) {
            return staxSource.getXMLStreamReader();
        }
        try {
            Method getXMLStreamReader = source.getClass().getMethod("getXMLStreamReader");
            return (XMLStreamReader) getXMLStreamReader.invoke(source);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static class DynamicXmlParseException extends XmlMappingException {
        public DynamicXmlParseException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
