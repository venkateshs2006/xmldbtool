package com.bnpp.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.InputStream;
import java.util.*;

/**
 * High-performance StAX XML ItemReader supporting O(1) memory bounded consumption,
 * single or multi-root element streaming, and Spring Batch restartability.
 */
public class StaxXmlItemReader implements ItemStreamReader<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(StaxXmlItemReader.class);

    private final Resource resource;
    private final Set<String> rootElements = new HashSet<>();
    private final DynamicXmlUnmarshaller unmarshaller;

    private InputStream inputStream;
    private XMLEventReader eventReader;
    private long currentRecordIndex = 0;
    private long restartIndex = 0;

    public StaxXmlItemReader(Resource resource, List<String> rootElements, DynamicXmlUnmarshaller unmarshaller) {
        this.resource = resource;
        if (rootElements != null && !rootElements.isEmpty()) {
            for (String r : rootElements) {
                if (r != null && !r.trim().isEmpty()) {
                    this.rootElements.add(r.trim().toLowerCase());
                }
            }
        }
        this.unmarshaller = unmarshaller;
    }

    public StaxXmlItemReader(String filePath, List<String> rootElements, DynamicXmlUnmarshaller unmarshaller) {
        this(new FileSystemResource(filePath), rootElements, unmarshaller);
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            if (executionContext.containsKey("currentRecordIndex")) {
                this.restartIndex = executionContext.getLong("currentRecordIndex");
            }

            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
            factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);

            this.inputStream = resource.getInputStream();
            this.eventReader = factory.createXMLEventReader(inputStream, "UTF-8");
            this.currentRecordIndex = 0;

            log.info("Opened StAX XML Stream for {}, restarting from index {}", resource.getFilename(), restartIndex);
        } catch (Exception ex) {
            throw new ItemStreamException("Failed to open StAX XML stream: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Map<String, Object> read() throws Exception {
        if (eventReader == null) return null;

        while (eventReader.hasNext()) {
            XMLEvent event = eventReader.peek();

            if (event.isStartElement()) {
                StartElement start = event.asStartElement();
                String localName = start.getName().getLocalPart().toLowerCase();

                if (rootElements.isEmpty() || rootElements.contains(localName) || rootElements.contains(localName.replaceFirst("^gs_", ""))) {
                    currentRecordIndex++;
                    if (currentRecordIndex <= restartIndex) {
                        // Skip unmarshalling to resume fast
                        skipCurrentFragment(eventReader);
                        continue;
                    }

                    Map<String, Object> fragment = unmarshaller.parseFragmentFromEventReader(eventReader);
                    if (fragment != null && !fragment.isEmpty()) {
                        return fragment;
                    }
                } else {
                    eventReader.nextEvent();
                }
            } else {
                eventReader.nextEvent();
            }
        }

        return null;
    }

    private void skipCurrentFragment(XMLEventReader reader) throws Exception {
        int depth = 0;
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) depth++;
            else if (event.isEndElement()) {
                depth--;
                if (depth <= 0) break;
            }
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putLong("currentRecordIndex", currentRecordIndex);
    }

    @Override
    public void close() throws ItemStreamException {
        try {
            if (eventReader != null) {
                eventReader.close();
            }
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (Exception ex) {
            log.warn("Error closing StAX reader: {}", ex.getMessage());
        }
    }

    public long getCurrentRecordIndex() {
        return currentRecordIndex;
    }
}
