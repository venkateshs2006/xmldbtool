package com.enterprise.xmldbtool.controller;

import com.enterprise.xmldbtool.service.StagingXmlStreamingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Controller handling user-initiated XML and ingestion configuration file uploads.
 */
@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);
    private final Path uploadDir = Paths.get("uploads").toAbsolutePath().normalize();

    public FileUploadController() {
        try {
            Files.createDirectories(uploadDir);
        } catch (Exception ex) {
            log.warn("Could not create uploads directory: {}", ex.getMessage());
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Uploaded file is empty."));
        }
        try {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "uploaded_file_" + System.currentTimeMillis() + ".xml";
            }
            Path targetPath = uploadDir.resolve(originalFilename);
            try (InputStream is = file.getInputStream()) {
                Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            long sizeBytes = Files.size(targetPath);
            long sizeKb = Math.max(1, sizeBytes / 1024);
            String fileHash = StagingXmlStreamingService.computeFileSha256(targetPath);

            log.info("File uploaded successfully: {} ({} KB, hash={}) -> {}", originalFilename, sizeKb, fileHash, targetPath);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filePath", targetPath.toString(),
                    "fileName", originalFilename,
                    "fileSizeKb", sizeKb,
                    "fileSizeBytes", sizeBytes,
                    "fileHash", fileHash
            ));
        } catch (Exception ex) {
            log.error("Failed to upload file", ex);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }
}
