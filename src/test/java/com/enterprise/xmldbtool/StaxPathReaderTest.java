package com.enterprise.xmldbtool;

import com.enterprise.xmldbtool.controller.FileUploadController;
import com.enterprise.xmldbtool.model.DiscoveredElement;
import com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class StaxPathReaderTest {

    @Autowired
    private FileUploadController fileUploadController;

    @Autowired
    private XmlSchemaDiscoveryService discoveryService;

    @Test
    void testResolveFilePathWithoutUpload() {
        ResponseEntity<?> resp = fileUploadController.resolveFilePath("tester.xml", null);
        assertNotNull(resp);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
        assertTrue((Boolean) body.get("exists"));
        String filePath = (String) body.get("filePath");
        assertNotNull(filePath);
        assertTrue(filePath.endsWith("tester.xml"));
        assertTrue(new File(filePath).exists());
    }

    @Test
    void testStaxReaderDirectlyFromPathWithoutUpload() throws Exception {
        String path = "uploads/tester.xml";
        assertTrue(new File(path).exists());

        // Test candidate roots detection via StAX streaming
        List<String> roots = discoveryService.detectCandidateRootElements(path);
        assertNotNull(roots);
        assertFalse(roots.isEmpty(), "StAX reader should discover candidate roots from tester.xml path");
        System.out.println("Discovered roots in tester.xml: " + roots);

        // Test element discovery via StAX streaming
        List<DiscoveredElement> elements = discoveryService.discoverXmlElements(path, 500);
        assertNotNull(elements);
        assertFalse(elements.isEmpty(), "StAX reader should discover XML elements from tester.xml path");
        System.out.println("Discovered element count in tester.xml: " + elements.size());

        // Test root element and version detection
        String recordRoot = discoveryService.detectRecordRootElement(path);
        assertNotNull(recordRoot);
        System.out.println("Detected record root in tester.xml: " + recordRoot);

        long sizeBytes = discoveryService.getFileSizeBytes(path);
        assertTrue(sizeBytes > 0);
    }
}
