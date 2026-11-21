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
                    "fileName", f.getName(),
                    "sizeBytes", f.length()
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    @PostMapping("/api/schema/detect-roots")
    public ResponseEntity<?> detectRoots(@RequestBody Map<String, String> body) {
        String path = body.get("xmlFilePath");
        if (path == null) path = body.get("path");
        try {
            List<String> roots = xmlSchemaDiscoveryService.detectRoots(path);
            return ResponseEntity.ok(Map.of("success", true, "roots", roots));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
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

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Target database tables successfully provisioned across all 4 layers.",
                    "discoveredTables", discoveredTables,
                    "createdFlatTables", createdFlatTables,
                    "stagingTables", stagingTables
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }
}
