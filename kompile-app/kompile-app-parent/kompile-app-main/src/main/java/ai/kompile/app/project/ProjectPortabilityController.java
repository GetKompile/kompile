/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.project;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Portable knowledge-base maintenance endpoints used by the crawl Source Maintenance screen.
 */
@RestController
@RequestMapping("/api/projects/current/portability")
public class ProjectPortabilityController {

    private final ProjectPortabilityJobService jobs;

    public ProjectPortabilityController(ProjectPortabilityJobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping("/exports")
    public ResponseEntity<ProjectPortabilityJobService.PortableJob> startExport() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobs.startExport());
    }

    @PostMapping(path = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectPortabilityJobService.PortableArchive inspect(
            @RequestParam("file") MultipartFile file) throws IOException {
        Path upload = saveUpload(file);
        try {
            return jobs.inspect(upload);
        } finally {
            cleanupUpload(upload);
        }
    }

    @PostMapping(path = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProjectPortabilityJobService.PortableJob> startImport(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetName", required = false) String targetName) throws IOException {
        Path upload = saveUpload(file);
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(jobs.startImport(upload, targetName));
        } catch (RuntimeException failure) {
            cleanupUpload(upload);
            throw failure;
        }
    }

    @GetMapping("/jobs")
    public List<ProjectPortabilityJobService.PortableJob> listJobs() {
        return jobs.listJobs();
    }

    @GetMapping("/jobs/{id}")
    public ProjectPortabilityJobService.PortableJob getJob(@PathVariable String id) {
        return jobs.getJob(id);
    }

    @GetMapping("/jobs/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) throws IOException {
        ProjectPortabilityJobService.PortableJob job = jobs.getJob(id);
        Path path = jobs.downloadPath(id);
        FileSystemResource resource = new FileSystemResource(path);
        String filename = job.artifactName() == null ? path.getFileName().toString()
                : job.artifactName();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(path))
                .body(resource);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleRejectedOperation(RuntimeException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", message(failure)));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleInvalidArchive(IOException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", message(failure)));
    }

    private static Path saveUpload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("A non-empty .kproject archive is required");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null
                || !originalName.toLowerCase(Locale.ROOT).endsWith(".kproject")) {
            throw new IOException("Portable archives must use the .kproject extension");
        }
        Path directory = Files.createTempDirectory("kompile-portable-upload-");
        Path upload = directory.resolve("uploaded.kproject");
        try {
            file.transferTo(upload);
            return upload;
        } catch (Exception failure) {
            cleanupUpload(upload);
            if (failure instanceof IOException io) throw io;
            throw new IOException("Could not store uploaded project archive", failure);
        }
    }

    private static void cleanupUpload(Path upload) {
        if (upload == null) return;
        try {
            Files.deleteIfExists(upload);
            Path parent = upload.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().startsWith("kompile-portable-upload-")) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException ignored) {
            // Best-effort cleanup of request-scoped temporary files.
        }
    }

    private static String message(Exception failure) {
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
