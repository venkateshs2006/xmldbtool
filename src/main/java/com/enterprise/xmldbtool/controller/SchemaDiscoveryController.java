package com.enterprise.xmldbtool.controller;

import com.enterprise.xmldbtool.model.SchemaDiscoveryRequest;
import com.enterprise.xmldbtool.model.SchemaDiscoveryResponse;
import com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
