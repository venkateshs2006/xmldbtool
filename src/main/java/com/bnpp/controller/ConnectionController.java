package com.bnpp.controller;

import com.bnpp.model.DbConnectionConfig;
import com.bnpp.model.DbTestResponse;
import com.bnpp.service.DynamicDataSourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping({"/api/connection", "/api/db"})
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
