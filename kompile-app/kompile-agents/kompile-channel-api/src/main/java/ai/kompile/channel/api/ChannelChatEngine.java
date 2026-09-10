/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

/** Chat runtime used for inbound messages on a channel connection. */
public enum ChannelChatEngine {
    REACT,
    KOMPILE_CLI,
    WEB_CHAT
}
