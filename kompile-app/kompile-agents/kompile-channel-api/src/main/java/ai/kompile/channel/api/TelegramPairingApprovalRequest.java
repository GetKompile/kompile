/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

/** TOCTOU guard for approving a discovered Telegram chat. */
public record TelegramPairingApprovalRequest(long expectedChatId) {
}
