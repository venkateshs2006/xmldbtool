package com.enterprise.xmldbtool.controller;

import com.enterprise.xmldbtool.model.SchemaRegistryVersion;
import com.enterprise.xmldbtool.service.SchemaRegistryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schema-registry")
public class SchemaRegistryController {

    private final SchemaRegistryService registryService;

    public SchemaRegistryController(SchemaRegistryService registryService) {
        this.registryService = registryService;
    }

    @GetMapping("/versions")
    public ResponseEntity<List<SchemaRegistryVersion>> getAllVersions() {
        return ResponseEntity.ok(registryService.getAllVersions());
    }

    @GetMapping("/versions/{schemaName}")
    public ResponseEntity<List<SchemaRegistryVersion>> getVersionsForSchema(@PathVariable String schemaName) {
        return ResponseEntity.ok(registryService.getVersionsForSchema(schemaName));
    }

    @GetMapping("/versions/{schemaName}/{version}")
    public ResponseEntity<?> getSpecificVersion(@PathVariable String schemaName, @PathVariable String version) {
        SchemaRegistryVersion ver = registryService.getVersion(schemaName, version);
        if (ver == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Schema version not found."));
        }
        return ResponseEntity.ok(ver);
    }

    @PostMapping("/versions")
    public ResponseEntity<?> registerVersion(@RequestBody SchemaRegistryVersion version) {
        try {
            SchemaRegistryVersion saved = registryService.registerVersion(version);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error registering schema: " + ex.getMessage()));
        }
    }
}

