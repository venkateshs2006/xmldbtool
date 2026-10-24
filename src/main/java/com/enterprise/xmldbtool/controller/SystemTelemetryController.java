package com.enterprise.xmldbtool.controller;

import com.enterprise.xmldbtool.model.SystemMetrics;
import com.enterprise.xmldbtool.service.SystemMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemTelemetryController {

    private final SystemMetricsService systemMetricsService;

    public SystemTelemetryController(SystemMetricsService systemMetricsService) {
        this.systemMetricsService = systemMetricsService;
    }

    @GetMapping("/metrics")
    public ResponseEntity<SystemMetrics> getSystemMetrics() {
        return ResponseEntity.ok(systemMetricsService.getSystemMetrics());
    }
}
