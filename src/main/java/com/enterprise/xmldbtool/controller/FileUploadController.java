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

    @GetMapping("/resolve-path")
    public ResponseEntity<?> resolveFilePath(@RequestParam("fileName") String fileName,
                                             @RequestParam(value = "hintPath", required = false) String hintPath) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "File name cannot be empty."));
        }
        fileName = fileName.trim().replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");

        // 1. Direct path check
        Path direct = Paths.get(fileName);
        if (Files.exists(direct)) {
            return returnPathInfo(direct.toAbsolutePath().normalize());
        }

        // 2. If hintPath was provided (e.g. directory from existing input value)
        if (hintPath != null && !hintPath.trim().isEmpty()) {
            try {
                Path hint = Paths.get(hintPath.trim().replaceAll("^\"|\"$", ""));
                Path dir = Files.isDirectory(hint) ? hint : hint.getParent();
                if (dir != null && Files.exists(dir)) {
                    Path candidate = dir.resolve(fileName);
                    if (Files.exists(candidate)) {
                        return returnPathInfo(candidate.toAbsolutePath().normalize());
                    }
                }
            } catch (Exception ignored) {}
        }

        Path wsRoot = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

        // 3. Check in uploads/ directory
        Path inUploads = uploadDir.resolve(fileName);
        if (Files.exists(inUploads)) {
            return returnPathInfo(inUploads.normalize());
        }

        // 4. Check current working directory / workspace root
        Path inWs = wsRoot.resolve(fileName);
        if (Files.exists(inWs)) {
            return returnPathInfo(inWs.normalize());
        }

        // 5. Check in sample-data/ directory
        Path inSample = wsRoot.resolve("sample-data").resolve(fileName);
        if (Files.exists(inSample)) {
            return returnPathInfo(inSample.normalize());
        }

        // Fallback: return direct path in workspace or uploads without uploading
        Path fallback = wsRoot.resolve(fileName).normalize();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "filePath", fallback.toString(),
                "fileName", fileName,
                "exists", false
        ));
    }

    @GetMapping("/info")
    public ResponseEntity<?> getFileInfo(@RequestParam("path") String pathStr) {
        if (pathStr == null || pathStr.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Path cannot be empty."));
        }
        pathStr = pathStr.trim().replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");
        Path path = Paths.get(pathStr).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            return ResponseEntity.ok(Map.of("success", false, "exists", false, "message", "File not found: " + pathStr));
        }
        return returnPathInfo(path);
    }

    private ResponseEntity<?> returnPathInfo(Path p) {
        try {
            long sizeBytes = Files.size(p);
            long sizeKb = Math.max(1, sizeBytes / 1024);
            String fileHash = StagingXmlStreamingService.computeFileSha256(p);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filePath", p.toAbsolutePath().normalize().toString(),
                    "fileName", p.getFileName().toString(),
                    "fileSizeKb", sizeKb,
                    "fileSizeBytes", sizeBytes,
                    "fileHash", fileHash,
                    "exists", true
            ));
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filePath", p.toAbsolutePath().normalize().toString(),
                    "fileName", p.getFileName().toString(),
                    "exists", true
            ));
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
