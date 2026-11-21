package com.bnpp.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.oxm.Unmarshaller;
import org.springframework.oxm.XmlMappingException;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Dynamic XML StAX unmarshaller implementing Spring OXM Unmarshaller.
 * Reads XML fragments into a Map<String, Object> dynamically with O(1) memory overhead.
 */
@Component
public class DynamicXmlUnmarshaller implements Unmarshaller {

    private static final Logger log = LoggerFactory.getLogger(DynamicXmlUnmarshaller.class);

    private static class ElementFrame {
        final String tagName;
        final ElementFrame parent;
        final String path;
        final Map<String, Object> data = new LinkedHashMap<>();
        final StringBuilder text = new StringBuilder();
        final StringBuilder rawXml;
        int childElementCount = 0;

        ElementFrame(String tagName, ElementFrame parent) {
            this.tagName = tagName;
            this.parent = parent;
            this.path = (parent == null) ? tagName : parent.path + "/" + tagName;
            this.rawXml = new StringBuilder();
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

            throw new IllegalArgumentException("Unsupported XML Source: " + (source != null ? source.getClass().getName() : "null"));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to dynamically unmarshal XML fragment: " + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> parseFragmentFromEventReader(XMLEventReader reader) throws Exception {
        Map<String, Object> recordMap = new LinkedHashMap<>();
        int depth = 0;
        ElementFrame currentFrame = null;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartElement()) {
                StartElement startElement = event.asStartElement();
                String localName = startElement.getName().getLocalPart();
                depth++;

                ElementFrame frame = new ElementFrame(localName, currentFrame);
                if (currentFrame != null) {
                    currentFrame.childElementCount++;
                }

                frame.rawXml.append("<").append(localName);

                Iterator<?> attributes = startElement.getAttributes();
                while (attributes.hasNext()) {
                    Attribute attr = (Attribute) attributes.next();
                    String rawAttrName = attr.getName().getLocalPart();
                    String attrValue = attr.getValue();

                    frame.rawXml.append(" ").append(rawAttrName).append("=\"").append(escapeXml(attrValue)).append("\"");

                    frame.data.put("@" + rawAttrName, attrValue);
                    frame.data.put(rawAttrName, attrValue);
                }
                frame.rawXml.append(">");
                currentFrame = frame;
            } else if (event.isCharacters() || event.getEventType() == XMLEvent.CDATA) {
                String text = event.asCharacters().getData();
                if (currentFrame != null) {
                    currentFrame.text.append(text);
                    currentFrame.rawXml.append(escapeXml(text));
                }
            } else if (event.isEndElement()) {
                String localName = event.asEndElement().getName().getLocalPart();
                depth--;

                if (currentFrame != null) {
                    currentFrame.rawXml.append("</").append(localName).append(">");
                    String text = currentFrame.text.toString().trim();

                    ElementFrame parent = currentFrame.parent;
                    if (parent != null) {
                        parent.rawXml.append(currentFrame.rawXml);

                        // If element is a simple leaf with no attributes and text
                        Object childData;
                        if (currentFrame.childElementCount == 0 && currentFrame.data.isEmpty()) {
                            childData = text;
                        } else {
                            if (!text.isEmpty()) {
                                currentFrame.data.put("value", text);
                            }
                            childData = currentFrame.data;
                        }

                        // Support repeating child elements as list
                        Object existing = parent.data.get(localName);
                        if (existing == null) {
                            parent.data.put(localName, childData);
                        } else if (existing instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Object> list = (List<Object>) existing;
                            list.add(childData);
                        } else {
                            List<Object> list = new ArrayList<>();
                            list.add(existing);
                            list.add(childData);
                            parent.data.put(localName, list);
                        }

                        currentFrame = parent;
                    } else {
                        // Root of the fragment
                        if (!text.isEmpty()) {
                            currentFrame.data.put("value", text);
                            currentFrame.data.put("_text", text);
                            currentFrame.data.put(localName, text);
                        }
                        recordMap.putAll(currentFrame.data);
                        if (!text.isEmpty()) {
                            recordMap.put("value", text);
                            recordMap.put("_text", text);
                            recordMap.put(localName, text);
                        }
                        recordMap.put("_rootTag", localName);
                        recordMap.put("_rawXml", currentFrame.rawXml.toString());
                        break;
                    }
                }

                if (depth <= 0) {
                    break;
                }
            }
        }

        return recordMap;
    }

    public Map<String, Object> parseFragmentFromStreamReader(XMLStreamReader reader) throws Exception {
        Map<String, Object> recordMap = new LinkedHashMap<>();
        int depth = 0;
        ElementFrame currentFrame = null;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamReader.START_ELEMENT) {
                String localName = reader.getLocalName();
                depth++;

                ElementFrame frame = new ElementFrame(localName, currentFrame);
                if (currentFrame != null) {
                    currentFrame.childElementCount++;
                }

                frame.rawXml.append("<").append(localName);

                int attrCount = reader.getAttributeCount();
                for (int i = 0; i < attrCount; i++) {
                    String attrName = reader.getAttributeLocalName(i);
                    String attrVal = reader.getAttributeValue(i);

                    frame.rawXml.append(" ").append(attrName).append("=\"").append(escapeXml(attrVal)).append("\"");

                    frame.data.put("@" + attrName, attrVal);
                    frame.data.put(attrName, attrVal);
                }
                frame.rawXml.append(">");
                currentFrame = frame;
            } else if (event == XMLStreamReader.CHARACTERS || event == XMLStreamReader.CDATA) {
                String text = reader.getText();
                if (currentFrame != null && text != null) {
                    currentFrame.text.append(text);
                    currentFrame.rawXml.append(escapeXml(text));
                }
            } else if (event == XMLStreamReader.END_ELEMENT) {
                String localName = reader.getLocalName();
                depth--;

                if (currentFrame != null) {
                    currentFrame.rawXml.append("</").append(localName).append(">");
                    String text = currentFrame.text.toString().trim();

                    ElementFrame parent = currentFrame.parent;
                    if (parent != null) {
                        parent.rawXml.append(currentFrame.rawXml);

                        // If element is a simple leaf with no attributes and text
                        Object childData;
                        if (currentFrame.childElementCount == 0 && currentFrame.data.isEmpty()) {
                            childData = text;
                        } else {
                            if (!text.isEmpty()) {
                                currentFrame.data.put("value", text);
                            }
                            childData = currentFrame.data;
                        }

                        // Support repeating child elements as list
                        Object existing = parent.data.get(localName);
                        if (existing == null) {
                            parent.data.put(localName, childData);
                        } else if (existing instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Object> list = (List<Object>) existing;
                            list.add(childData);
                        } else {
                            List<Object> list = new ArrayList<>();
                            list.add(existing);
                            list.add(childData);
                            parent.data.put(localName, list);
                        }

                        currentFrame = parent;
                    } else {
                        // Root of the fragment
                        if (!text.isEmpty()) {
                            currentFrame.data.put("value", text);
                            currentFrame.data.put("_text", text);
                            currentFrame.data.put(localName, text);
                        }
                        recordMap.putAll(currentFrame.data);
                        if (!text.isEmpty()) {
                            recordMap.put("value", text);
                            recordMap.put("_text", text);
                            recordMap.put(localName, text);
                        }
                        recordMap.put("_rootTag", localName);
                        recordMap.put("_rawXml", currentFrame.rawXml.toString());
                        break;
                    }
                }

                if (depth <= 0) {
                    break;
                }
            }
        }

        return recordMap;
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private XMLEventReader extractEventReader(Source source) {
        if (source == null) return null;
        try {
            Method m = source.getClass().getMethod("getXMLEventReader");
            return (XMLEventReader) m.invoke(source);
        } catch (Exception ignored) {
            return null;
        }
    }

    private XMLStreamReader extractStreamReader(Source source) {
        if (source == null) return null;
        try {
            Method m = source.getClass().getMethod("getXMLStreamReader");
            return (XMLStreamReader) m.invoke(source);
        } catch (Exception ignored) {
            return null;
        }
    }
}
