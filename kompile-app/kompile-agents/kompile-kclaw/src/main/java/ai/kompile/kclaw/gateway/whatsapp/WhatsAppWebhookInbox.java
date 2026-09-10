/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.whatsapp;

import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.kclaw.gateway.channel.WhatsAppChannelAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Durable, at-most-once WhatsApp webhook inbox shared across runtime replacements. */
@Slf4j
public final class WhatsAppWebhookInbox implements AutoCloseable {

    private static final Duration RECEIPT_RETENTION = Duration.ofDays(7);

    private final Path root;
    private final ObjectMapper mapper;
    private final ChannelManager channelManager;
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "whatsapp-webhook-inbox");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Path> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    public WhatsAppWebhookInbox(Path root, ObjectMapper mapper, ChannelManager channelManager) {
        this.root = root;
        this.mapper = mapper;
        this.channelManager = channelManager;
    }

    public synchronized void start() {
        if (running) return;
        try {
            Files.createDirectories(root);
            restrictDirectory(root);
            running = true;
            recoverClaimsAndSchedulePending();
            cleanupReceipts();
        } catch (IOException e) {
            running = false;
            throw new IllegalStateException("Could not initialize WhatsApp webhook inbox", e);
        }
    }

    /** Persist before returning HTTP success. False means this event id was already accepted. */
    public synchronized boolean enqueue(
            String connectionName, String eventId, Map<String, Object> payload) {
        if (!running) throw new IllegalStateException("WhatsApp webhook inbox is not running");
        if (connectionName == null || !connectionName.matches("[a-z0-9][a-z0-9._-]{0,62}")) {
            throw new IllegalArgumentException("Invalid WhatsApp connection name");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("WhatsApp event id is required");
        }
        Path directory = root.resolve(connectionName);
        String stem = digest(eventId);
        Path pending = directory.resolve(stem + ".pending");
        try {
            Files.createDirectories(directory);
            restrictDirectory(directory);
            if (existsWithAnyState(directory, stem)) return false;
            Path temporary = Files.createTempFile(directory, ".whatsapp-", ".tmp");
            try {
                mapper.writeValue(temporary.toFile(),
                        new Envelope(1, connectionName, eventId, payload, Instant.now()));
                restrictFile(temporary);
                moveNew(temporary, pending);
                restrictFile(pending);
            } finally {
                Files.deleteIfExists(temporary);
            }
            schedule(pending, 0L);
            return true;
        } catch (FileAlreadyExistsException duplicate) {
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist WhatsApp webhook event", e);
        }
    }

    private void process(Path pending) {
        if (!running || !Files.isRegularFile(pending) || !inFlight.add(pending)) return;
        boolean retry = false;
        try {
            Envelope envelope = mapper.readValue(pending.toFile(), Envelope.class);
            WhatsAppChannelAdapter adapter = channelManager
                    .getConnectionAdapter(envelope.connectionName())
                    .filter(WhatsAppChannelAdapter.class::isInstance)
                    .map(WhatsAppChannelAdapter.class::cast)
                    .filter(WhatsAppChannelAdapter::isRunning)
                    .orElse(null);
            if (adapter == null) {
                retry = true;
                return;
            }

            Path claimed = replaceSuffix(pending, ".claimed");
            moveReplace(pending, claimed); // durable claim before any agent/tool side effect
            try {
                adapter.processWebhookPayload(envelope.payload());
                moveReplace(claimed, replaceSuffix(claimed, ".done"));
            } catch (RuntimeException executionFailure) {
                moveReplace(claimed, replaceSuffix(claimed, ".failed"));
                log.error("WhatsApp event {} failed after durable claim for connection {}",
                        envelope.eventId(), envelope.connectionName(), executionFailure);
            }
        } catch (Exception e) {
            retry = Files.isRegularFile(pending);
            log.warn("Could not process WhatsApp webhook inbox item {}: {}",
                    pending.getFileName(), e.getMessage());
        } finally {
            inFlight.remove(pending);
            if (retry && running) schedule(pending, 5L);
        }
    }

    private void recoverClaimsAndSchedulePending() throws IOException {
        try (var paths = Files.walk(root, 2)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".claimed")) {
                    moveReplace(path, replaceSuffix(path, ".failed"));
                } else if (name.endsWith(".pending")) {
                    schedule(path, 0L);
                }
            }
        }
    }

    private void cleanupReceipts() throws IOException {
        Instant cutoff = Instant.now().minus(RECEIPT_RETENTION);
        try (var paths = Files.walk(root, 2)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if ((name.endsWith(".done") || name.endsWith(".failed"))
                        && Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void schedule(Path pending, long delaySeconds) {
        worker.schedule(() -> process(pending), delaySeconds, TimeUnit.SECONDS);
    }

    private static boolean existsWithAnyState(Path directory, String stem) {
        return Files.exists(directory.resolve(stem + ".pending"))
                || Files.exists(directory.resolve(stem + ".claimed"))
                || Files.exists(directory.resolve(stem + ".done"))
                || Files.exists(directory.resolve(stem + ".failed"));
    }

    private static Path replaceSuffix(Path path, String replacement) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return path.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + replacement);
    }

    private static void moveNew(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictFile(target);
    }

    private static String digest(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void restrictDirectory(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (IOException | UnsupportedOperationException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setExecutable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
            path.toFile().setExecutable(true, true);
        }
    }

    private static void restrictFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Envelope(
            int version,
            String connectionName,
            String eventId,
            Map<String, Object> payload,
            Instant acceptedAt) {
    }
}
