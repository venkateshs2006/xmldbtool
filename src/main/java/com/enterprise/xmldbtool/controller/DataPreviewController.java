package com.enterprise.xmldbtool.controller;

import com.enterprise.xmldbtool.model.DataPreviewRequest;
import com.enterprise.xmldbtool.model.DataPreviewResponse;
import com.enterprise.xmldbtool.service.XmlPreviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/preview")
public class DataPreviewController {

    private final XmlPreviewService previewService;

    public DataPreviewController(XmlPreviewService previewService) {
        this.previewService = previewService;
    }

    @PostMapping("/generate")
    public ResponseEntity<DataPreviewResponse> generatePreview(@RequestBody DataPreviewRequest request) {
        DataPreviewResponse response = previewService.generatePreview(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }
}
