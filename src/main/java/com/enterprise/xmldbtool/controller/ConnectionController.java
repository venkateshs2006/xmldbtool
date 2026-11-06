package com.enterprise.xmldbtool.controller;

import com.enterprise.xmldbtool.model.DbConnectionConfig;
import com.enterprise.xmldbtool.model.DbTestResponse;
import com.enterprise.xmldbtool.service.DynamicDataSourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping({"/api/db", "/api/connection"})
public class ConnectionController {

    private final DynamicDataSourceService dataSourceService;

    public ConnectionController(DynamicDataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @PostMapping("/test")
    public ResponseEntity<DbTestResponse> testConnection(@RequestBody DbConnectionConfig config) {
        DbTestResponse response = dataSourceService.testConnection(config);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/create-schema")
    public ResponseEntity<DbTestResponse> createSchema(@RequestBody DbConnectionConfig config) {
        DbTestResponse response = dataSourceService.createSchema(config);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetConnectionPools() {
        dataSourceService.resetAllPools();
        return ResponseEntity.ok(Map.of("success", true, "message", "Database connection pools reset successfully."));
    }
}
