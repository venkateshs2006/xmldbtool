package com.bnpp.controller;

import com.bnpp.model.*;
import com.bnpp.service.SchemaProvisioningService;
import com.bnpp.service.XmlSchemaDiscoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
public class SchemaDiscoveryController {

    private final XmlSchemaDiscoveryService xmlSchemaDiscoveryService;
    private final SchemaProvisioningService schemaProvisioningService;

    public SchemaDiscoveryController(XmlSchemaDiscoveryService xmlSchemaDiscoveryService,
                                     SchemaProvisioningService schemaProvisioningService) {
        this.xmlSchemaDiscoveryService = xmlSchemaDiscoveryService;
        this.schemaProvisioningService = schemaProvisioningService;
    }

    @GetMapping("/api/files/resolve-path")
    public ResponseEntity<?> resolveFilePath(@RequestParam("fileName") String fileName,
                                             @RequestParam(value = "hintPath", required = false) String hintPath) {
        try {
            File f = xmlSchemaDiscoveryService.resolvePath(fileName, hintPath);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "resolvedPath", f.getAbsolutePath(),
                    "filePath", f.getAbsolutePath(),
                    "fileName", f.getName(),
                    "sizeBytes", f.length()
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    @PostMapping("/api/files/upload-xml")
    public ResponseEntity<?> uploadXmlFile(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Uploaded XML file is empty."));
            }
            File uploadDir = new File("uploads");
            if (!uploadDir.exists()) uploadDir.mkdirs();
            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) originalName = "uploaded_" + System.currentTimeMillis() + ".xml";
            File dest = new File(uploadDir, new File(originalName).getName()).getAbsoluteFile();
            file.transferTo(dest);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "resolvedPath", dest.getAbsolutePath(),
                    "filePath", dest.getAbsolutePath(),
                    "fileName", dest.getName(),
                    "sizeBytes", dest.length()
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    @PostMapping("/api/schema/detect-roots")
    public ResponseEntity<?> detectRoots(@RequestBody Map<String, String> body) {
        String path = body.get("xmlFilePath");
        if (path == null) path = body.get("sourceXmlPath");
        if (path == null) path = body.get("path");
        try {
            List<String> roots = xmlSchemaDiscoveryService.detectRoots(path);
            return ResponseEntity.ok(Map.of("success", true, "roots", roots, "candidateRootElements", roots));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    @PostMapping("/api/schema/validate-tags")
    public ResponseEntity<?> validateTags(@RequestBody Map<String, Object> body) {
        try {
            String xmlFilePath = (String) body.get("xmlFilePath");
            if (xmlFilePath == null) xmlFilePath = (String) body.get("sourceXmlPath");
            if (xmlFilePath == null) xmlFilePath = (String) body.get("path");

            Object mapObj = body.get("mappings");
            List<FieldMapping> mappings;
            if (mapObj instanceof String s) {
                mappings = schemaProvisioningService.parseMappings(s);
            } else if (mapObj != null) {
                mappings = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(mapObj,
                        new com.fasterxml.jackson.core.type.TypeReference<List<FieldMapping>>() {});
            } else {
                mappings = List.of();
            }

            Map<String, Object> resp = xmlSchemaDiscoveryService.validateXmlTags(xmlFilePath, mappings);
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "matched", false,
                    "message", ex.getMessage(),
                    "mismatches", List.of("Tag validation failed: " + ex.getMessage())
            ));
        }
    }

    @PostMapping("/api/schema/discover")
    public ResponseEntity<SchemaDiscoveryResponse> discoverSchema(@RequestBody SchemaDiscoveryRequest req) {
        SchemaDiscoveryResponse resp = xmlSchemaDiscoveryService.discoverSchema(req);
        if (resp.isSuccess()) {
            return ResponseEntity.ok(resp);
        } else {
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @GetMapping("/api/schema/table-prefixes")
    public ResponseEntity<?> getTablePrefixes() {
        var cfg = schemaProvisioningService.getTablePrefixConfig();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "goldenSource", cfg.getGoldenSource(),
                "canonical", cfg.getCanonical(),
                "goldenSourcePrefix", cfg.getGoldenSource(),
                "canonicalPrefix", cfg.getCanonical()
        ));
    }

    @PostMapping("/api/schema/preview-tables")
    public ResponseEntity<?> previewTables(@RequestBody Map<String, Object> body) {
        try {
            String json = (String) body.get("ingestionJson");
            List<FieldMapping> mappings = schemaProvisioningService.parseMappings(json);
            TablePreviewData preview = schemaProvisioningService.generatePreviewTables(mappings);
            return ResponseEntity.ok(preview);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    @PostMapping("/api/schema/create-tables")
    public ResponseEntity<?> createTables(@RequestBody Map<String, Object> body) {
        try {
            Object dbObj = body.get("dbConfig");
            DbConnectionConfig dbConfig = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(dbObj, DbConnectionConfig.class);

            Object mapObj = body.get("mappings");
            List<FieldMapping> mappings;
            if (mapObj instanceof String s) {
                mappings = schemaProvisioningService.parseMappings(s);
            } else {
                mappings = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(mapObj,
                        new com.fasterxml.jackson.core.type.TypeReference<List<FieldMapping>>() {});
            }

            schemaProvisioningService.provisionAllTables(dbConfig, mappings);
            TablePreviewData preview = schemaProvisioningService.generatePreviewTables(mappings);
            List<TableMetadata> discoveredTables = preview.getTables().stream()
                    .filter(t -> "CANONICAL".equalsIgnoreCase(t.getTableType()))
                    .toList();
            List<String> createdFlatTables = preview.getTables().stream()
                    .filter(t -> "GOLDEN_SOURCE".equalsIgnoreCase(t.getTableType()))
                    .map(TableMetadata::getTableName)
                    .toList();
            List<String> stagingTables = List.of("staging_xml_records");

            var cfg = schemaProvisioningService.getTablePrefixConfig();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Target database tables successfully provisioned across all 4 layers.",
                    "discoveredTables", discoveredTables,
                    "createdFlatTables", createdFlatTables,
                    "stagingTables", stagingTables,
                    "goldenSourcePrefix", cfg.getGoldenSource(),
                    "canonicalPrefix", cfg.getCanonical()
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }
}
