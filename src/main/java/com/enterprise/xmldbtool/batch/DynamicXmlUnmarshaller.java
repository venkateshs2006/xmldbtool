package com.enterprise.xmldbtool.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.oxm.Unmarshaller;
import org.springframework.oxm.XmlMappingException;
import org.springframework.stereotype.Component;

import javax.xml.namespace.QName;
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
import org.springframework.util.xml.StaxUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enterprise StAX Dynamic XML Unmarshaller.
 * Reads XML fragments into a Map<String, Object> dynamically without DOM or hardcoded JAXB POJOs.
 * All XML attributes are extracted and flattened with an '@' prefix (e.g., @id, @status).
 */
@Component
public class DynamicXmlUnmarshaller implements Unmarshaller {

    private static final Logger log = LoggerFactory.getLogger(DynamicXmlUnmarshaller.class);

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
     * Extracts all elements and attributes with '@' prefix.
     */
    public Map<String, Object> parseFragmentFromEventReader(XMLEventReader reader) throws Exception {
        Map<String, Object> recordMap = new LinkedHashMap<>();
        int depth = 0;
        String rootTagName = null;
        StringBuilder currentText = new StringBuilder();
        Deque<String> tagStack = new Deque<>() {
            private final ArrayDeque<String> deque = new ArrayDeque<>();
            @Override public void addFirst(String s) { deque.addFirst(s); }
            @Override public void addLast(String s) { deque.addLast(s); }
            @Override public boolean offerFirst(String s) { return deque.offerFirst(s); }
            @Override public boolean offerLast(String s) { return deque.offerLast(s); }
            @Override public String removeFirst() { return deque.removeFirst(); }
            @Override public String removeLast() { return deque.removeLast(); }
            @Override public String pollFirst() { return deque.pollFirst(); }
            @Override public String pollLast() { return deque.pollLast(); }
            @Override public String getFirst() { return deque.getFirst(); }
            @Override public String getLast() { return deque.getLast(); }
            @Override public String peekFirst() { return deque.peekFirst(); }
            @Override public String peekLast() { return deque.peekLast(); }
            @Override public boolean removeFirstOccurrence(Object o) { return deque.removeFirstOccurrence(o); }
            @Override public boolean removeLastOccurrence(Object o) { return deque.removeLastOccurrence(o); }
            @Override public boolean add(String s) { return deque.add(s); }
            @Override public boolean offer(String s) { return deque.offer(s); }
            @Override public String remove() { return deque.remove(); }
            @Override public String poll() { return deque.poll(); }
            @Override public String element() { return deque.element(); }
            @Override public String peek() { return deque.peek(); }
            @Override public boolean addAll(java.util.Collection<? extends String> c) { return deque.addAll(c); }
            @Override public void push(String s) { deque.push(s); }
            @Override public String pop() { return deque.pop(); }
            @Override public boolean remove(Object o) { return deque.remove(o); }
            @Override public boolean contains(Object o) { return deque.contains(o); }
            @Override public int size() { return deque.size(); }
            @Override public Iterator<String> iterator() { return deque.iterator(); }
            @Override public Iterator<String> descendingIterator() { return deque.descendingIterator(); }
            @Override public boolean isEmpty() { return deque.isEmpty(); }
            @Override public Object[] toArray() { return deque.toArray(); }
            @Override public <T> T[] toArray(T[] a) { return deque.toArray(a); }
            @Override public boolean containsAll(java.util.Collection<?> c) { return deque.containsAll(c); }
            @Override public boolean removeAll(java.util.Collection<?> c) { return deque.removeAll(c); }
            @Override public boolean retainAll(java.util.Collection<?> c) { return deque.retainAll(c); }
            @Override public void clear() { deque.clear(); }
        };

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartElement()) {
                StartElement startElement = event.asStartElement();
                String localName = startElement.getName().getLocalPart();

                if (depth == 0) {
                    rootTagName = localName;
                }

                depth++;
                tagStack.push(localName);
                currentText.setLength(0); // reset character buffer

                // Extract all attributes and record both qualified (tag/@attr) and unqualified (@attr, attr)
                Iterator<?> attributes = startElement.getAttributes();
                while (attributes.hasNext()) {
                    Attribute attr = (Attribute) attributes.next();
                    String rawAttrName = attr.getName().getLocalPart();
                    String attrValue = attr.getValue();
                    recordMap.put(localName + "/@" + rawAttrName, attrValue);
                    recordMap.put("@" + rawAttrName, attrValue);
                    recordMap.put(rawAttrName, attrValue);
                }
            } else if (event.isCharacters()) {
                Characters characters = event.asCharacters();
                if (!characters.isWhiteSpace()) {
                    currentText.append(characters.getData());
                }
            } else if (event.isEndElement()) {
                EndElement endElement = event.asEndElement();
                String closedTag = endElement.getName().getLocalPart();
                depth--;

                if (!tagStack.isEmpty()) {
                    String currentTag = tagStack.pop();
                    String trimmedText = currentText.toString().trim();

                    // Only put non-empty content for child leaf elements (not the fragment root itself)
                    if (depth > 0 && !trimmedText.isEmpty()) {
                        recordMap.put(currentTag, trimmedText);
                    }
                }

                currentText.setLength(0);

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
        StringBuilder currentText = new StringBuilder();
        ArrayDeque<String> tagStack = new ArrayDeque<>();

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();
                if (depth == 0) {
                    rootTagName = localName;
                }
                depth++;
                tagStack.push(localName);
                currentText.setLength(0);

                // Flatten attributes with both qualified tag/@attr and @attr
                int attrCount = reader.getAttributeCount();
                for (int i = 0; i < attrCount; i++) {
                    String rawAttrName = reader.getAttributeLocalName(i);
                    String attrValue = reader.getAttributeValue(i);
                    recordMap.put(localName + "/@" + rawAttrName, attrValue);
                    recordMap.put("@" + rawAttrName, attrValue);
                    recordMap.put(rawAttrName, attrValue);
                }
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (!reader.isWhiteSpace()) {
                    currentText.append(reader.getText());
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String closedTag = reader.getLocalName();
                depth--;

                if (!tagStack.isEmpty()) {
                    String currentTag = tagStack.pop();
                    String text = currentText.toString().trim();
                    if (depth > 0 && !text.isEmpty()) {
                        recordMap.put(currentTag, text);
                    }
                }

                currentText.setLength(0);

                if (depth == 0 || (rootTagName != null && rootTagName.equals(closedTag) && depth <= 0)) {
                    break;
                }
            }
        }

        return recordMap;
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
