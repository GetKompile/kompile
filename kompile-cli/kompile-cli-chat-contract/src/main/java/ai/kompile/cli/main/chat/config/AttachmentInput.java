/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/**
 * An attachment (file, image, etc.) to include with a chat message.
 */
public record AttachmentInput(String path, String mimeType, boolean isImage, String base64Data, String textContent) {
    public AttachmentInput(String path, String mimeType) {
        this(path, mimeType, false, null, null);
    }
}
