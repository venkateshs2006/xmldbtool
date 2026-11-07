package com.enterprise.xmldbtool.controller;

import com.enterprise.xmldbtool.model.SchemaDiscoveryRequest;
import com.enterprise.xmldbtool.model.SchemaDiscoveryResponse;
import com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schema")
public class SchemaDiscoveryController {

    private final XmlSchemaDiscoveryService discoveryService;

    public SchemaDiscoveryController(XmlSchemaDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @PostMapping("/discover")
    public ResponseEntity<SchemaDiscoveryResponse> discoverSchema(@RequestBody SchemaDiscoveryRequest request) {
        SchemaDiscoveryResponse response = discoveryService.discoverSchema(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/create-tables")
    public ResponseEntity<SchemaDiscoveryResponse> createTables(@RequestBody SchemaDiscoveryRequest request) {
        SchemaDiscoveryResponse response = discoveryService.provisionTables(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Fast XML structure inspection to detect repeating candidate root element tags,
     * default root element, XML feed version, and file size immediately upon file entry.
     */
    @PostMapping("/detect-roots")
    public ResponseEntity<Map<String, Object>> detectRoots(@RequestBody(required = false) Map<String, Object> body) {
        String path = null;
        if (body != null) {
            Object p = body.get("sourceXmlPath");
            if (p == null) p = body.get("filePath");
            if (p == null) p = body.get("path");
            if (p != null) path = String.valueOf(p).trim();
        }
        if (path == null || path.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Source XML path cannot be blank.",
                    "candidateRootElements", List.of()
            ));
        }
        try {
            List<String> candidates = discoveryService.detectCandidateRootElements(path);
            String defaultRoot = discoveryService.detectRecordRootElement(path);
            String xmlVersion = discoveryService.detectXmlFeedVersion(path);
            long fileSizeBytes = discoveryService.getFileSizeBytes(path);
            String formattedSize = XmlSchemaDiscoveryService.formatFileSize(fileSizeBytes);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("candidateRootElements", candidates != null ? candidates : List.of());
            result.put("defaultRootElement", defaultRoot);
            result.put("detectedXmlVersion", xmlVersion);
            result.put("fileSizeBytes", fileSizeBytes);
            result.put("fileSizeFormatted", formattedSize);
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Unable to detect candidate roots: " + ex.getMessage(),
                    "candidateRootElements", List.of()
            ));
        }
    }

    @GetMapping("/detect-roots")
    public ResponseEntity<Map<String, Object>> detectRootsGet(@RequestParam(value = "path", required = false) String path,
                                                              @RequestParam(value = "sourceXmlPath", required = false) String sourceXmlPath) {
        String effectivePath = (path != null && !path.trim().isEmpty()) ? path.trim() : (sourceXmlPath != null ? sourceXmlPath.trim() : null);
        return detectRoots(effectivePath != null ? Map.of("sourceXmlPath", effectivePath) : null);
    }
}
