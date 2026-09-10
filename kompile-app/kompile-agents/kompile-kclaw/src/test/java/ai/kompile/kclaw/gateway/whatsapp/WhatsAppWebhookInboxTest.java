/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.whatsapp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.kclaw.gateway.channel.WhatsAppChannelAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppWebhookInboxTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndDeduplicatesBeforeBackgroundDispatch() throws Exception {
        ChannelManager manager = new ChannelManager();
        WhatsAppChannelAdapter adapter = mock(WhatsAppChannelAdapter.class);
        CountDownLatch dispatched = new CountDownLatch(1);
        when(adapter.getChannelName()).thenReturn("whatsapp");
        when(adapter.isRunning()).thenReturn(true);
        doAnswer(ignored -> {
            dispatched.countDown();
            return null;
        }).when(adapter).processWebhookPayload(any());
        manager.registerAdapter("support", adapter);
        WhatsAppWebhookInbox inbox = new WhatsAppWebhookInbox(
                tempDir.resolve("inbox"), JsonUtils.standardMapper(), manager);
        inbox.start();
        try {
            Map<String, Object> payload = Map.of("entry", java.util.List.of());
            assertTrue(inbox.enqueue("support", "message:wamid.1", payload));
            assertFalse(inbox.enqueue("support", "message:wamid.1", payload));
            assertTrue(dispatched.await(2, TimeUnit.SECONDS));
            assertFalse(inbox.enqueue("support", "message:wamid.1", payload));
            verify(adapter, times(1)).processWebhookPayload(any());
        } finally {
            inbox.close();
        }
    }
}
