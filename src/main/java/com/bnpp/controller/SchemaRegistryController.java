package com.bnpp.controller;

import com.bnpp.model.SchemaRegistryVersion;
import com.bnpp.service.SchemaRegistryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/schema-registry")
public class SchemaRegistryController {

    private final SchemaRegistryService schemaRegistryService;

    public SchemaRegistryController(SchemaRegistryService schemaRegistryService) {
        this.schemaRegistryService = schemaRegistryService;
    }

    @GetMapping("/versions")
    public ResponseEntity<List<SchemaRegistryVersion>> getAllVersions() {
        return ResponseEntity.ok(schemaRegistryService.getAllVersions());
    }

    @GetMapping("/versions/{id}")
    public ResponseEntity<?> getVersionById(@PathVariable Long id) {
        Optional<SchemaRegistryVersion> opt = schemaRegistryService.getVersionById(id);
        return opt.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }

    @PostMapping("/versions")
    public ResponseEntity<?> saveVersion(@RequestBody SchemaRegistryVersion version) {
        try {
            SchemaRegistryVersion saved = schemaRegistryService.saveVersion(version);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }
}
