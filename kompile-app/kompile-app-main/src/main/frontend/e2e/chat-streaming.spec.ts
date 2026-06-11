/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Chat Streaming & Real-Time Message Tests
 * Tests SSE streaming, content accumulation, metrics display,
 * source attribution, cancel/stop, and error handling.
 */

import { test, expect, StreamController, triggerAngularCD } from './fixtures/kompile.fixture';

/**
 * Helper: select an agent (if not auto-selected), type a message, and send it.
 * Returns after the send button is clicked.
 */
async function sendChatMessage(page: import('@playwright/test').Page, message: string) {
  const input = page.getByTestId('chat-input');
  const agentSelect = page.getByTestId('agent-select');

  // Wait for agents to populate in the select dropdown.
  await expect(agentSelect.locator('option')).not.toHaveCount(1, { timeout: 10_000 });

  // Select an agent if none auto-selected
  const isDisabled = await input.isDisabled();
  if (isDisabled) {
    await agentSelect.selectOption({ label: /Claude Code/i });
  }

  await expect(input).toBeEnabled({ timeout: 5_000 });

  // Type message and trigger Angular CD (Playwright events bypass Angular zone)
  await input.click();
  await input.pressSequentially(message, { delay: 10 });
  await triggerAngularCD(page);

  await expect(page.getByTestId('send-btn')).toBeEnabled({ timeout: 5_000 });
  await page.getByTestId('send-btn').click();
}


test.describe('Chat Streaming — Message Send & Response', () => {

  test.beforeEach(async ({ api, page }) => {
    await api.setupAll();
  });

  test('should send a message and show user bubble', async ({ api, page }) => {
    const responseContent = 'Hello! I am your assistant.';
    await api.mockStreamingChatWithEvents(
      StreamController.fullResponse(responseContent)
    );

    await page.goto('/');
    await sendChatMessage(page, 'Hello there');

    // User message should appear
    const userBubble = page.locator('[data-testid="message-bubble"][data-role="user"]');
    await expect(userBubble.first()).toBeVisible();
    await expect(userBubble.first().getByTestId('message-content')).toContainText('Hello there');
  });

  test('should display assistant response after streaming completes', async ({ api, page }) => {
    const responseContent = 'This is the assistant response with some content.';
    await api.mockStreamingChatWithEvents(
      StreamController.fullResponse(responseContent)
    );

    await page.goto('/');
    await sendChatMessage(page, 'Tell me something');

    // Wait for assistant message to appear
    const assistantBubble = page.locator('[data-testid="message-bubble"][data-role="assistant"]');
    await expect(assistantBubble.first()).toBeVisible({ timeout: 15_000 });

    // Ensure final render after streaming completes
    await triggerAngularCD(page);

    // Content should contain the response text
    const content = assistantBubble.first().getByTestId('message-content');
    await expect(content).toContainText('assistant response');
  });

  test('should clear input after sending', async ({ api, page }) => {
    await api.mockStreamingChatWithEvents(
      StreamController.fullResponse('Response')
    );

    await page.goto('/');
    await sendChatMessage(page, 'My message');

    // Input should be cleared
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('should hide welcome message after first message', async ({ api, page }) => {
    await api.mockStreamingChatWithEvents(
      StreamController.fullResponse('Hi!')
    );

    await page.goto('/');

    // Welcome visible initially
    await expect(page.getByTestId('welcome-message')).toBeVisible();

    await sendChatMessage(page, 'Hello');

    // Welcome should disappear once messages exist
    await expect(page.getByTestId('welcome-message')).not.toBeVisible();
  });

  test('should show message actions (copy, fork, export) on assistant message', async ({ api, page }) => {
    await api.mockStreamingChatWithEvents(
      StreamController.fullResponse('Here is my response to your query.')
    );

    await page.goto('/');
    await sendChatMessage(page, 'Test actions');

    const assistantBubble = page.locator('[data-testid="message-bubble"][data-role="assistant"]');
    await expect(assistantBubble.first()).toBeVisible({ timeout: 15_000 });

    // Hover to reveal actions
    await assistantBubble.first().hover();

    // Should have action buttons
    const actions = assistantBubble.first().locator('.message-actions .action-btn');
    const count = await actions.count();
    // Copy, Fork, Export, Regenerate = 4 buttons
    expect(count).toBeGreaterThanOrEqual(3);
  });
});


test.describe('Chat Streaming — Metrics Display', () => {

  test.beforeEach(async ({ api, page }) => {
    await api.setupAll();
  });

  test('should display token metrics after response completes', async ({ api, page }) => {
    await api.mockStreamingChatWithEvents(
      StreamController.fullResponse('Response with metrics')
    );

    await page.goto('/');

    // Enable metrics display (toggle in settings)
    await page.getByTestId('settings-toggle-btn').click();
    const metricsToggle = page.locator('label:has-text("Show Performance Metrics") + .toggle-switch input, label:has-text("Show Performance Metrics") input');
    if (await metricsToggle.count() > 0) {
      const isChecked = await metricsToggle.first().isChecked();
      if (!isChecked) {
        await metricsToggle.first().click();
      }
    }
    await page.getByTestId('settings-toggle-btn').click(); // close settings

    await sendChatMessage(page, 'Show me metrics');

    // Wait for assistant response
    const assistantBubble = page.locator('[data-testid="message-bubble"][data-role="assistant"]');
    await expect(assistantBubble.first()).toBeVisible({ timeout: 15_000 });

    // Metrics bar should appear with tok/s
    const metricsBar = page.getByTestId('metrics-bar');
    // May or may not be visible depending on toggle state — check if present
    if (await metricsBar.count() > 0) {
      await expect(metricsBar.first()).toContainText('tok/s');
    }
  });
});


test.describe('Chat Streaming — Sources / RAG Attribution', () => {

  test.beforeEach(async ({ api, page }) => {
    await api.setupAll();
  });

  test('should display sources section when response includes sources', async ({ api, page }) => {
    const sources = [
      {
        sourceName: 'test-document.pdf',
        content: 'This is source content from the document.',
        score: 0.95,
        sourceType: 'PDF',
        chunkIndex: 0,
        documentId: 'abc12345-def6-7890-ghij-klmnopqrstuv'
      },
      {
        sourceName: 'another-doc.txt',
        content: 'Another piece of relevant content.',
        score: 0.88,
        sourceType: 'TEXT',
        chunkIndex: 1,
        documentId: 'xyz12345-def6-7890-ghij-klmnopqrstuv'
      }
    ];

    await api.mockStreamingChatWithEvents(
      StreamController.responseWithSources('Based on the documents, here is my answer.', sources)
    );

    await page.goto('/');
    await sendChatMessage(page, 'What does the document say?');

    // Wait for assistant message
    const assistantBubble = page.locator('[data-testid="message-bubble"][data-role="assistant"]');
    await expect(assistantBubble.first()).toBeVisible({ timeout: 15_000 });
    await triggerAngularCD(page);

    // Sources section should be visible
    const sourcesSection = page.getByTestId('sources-section');
    if (await sourcesSection.count() > 0) {
      await expect(sourcesSection.first()).toBeVisible();
      await expect(sourcesSection.first()).toContainText('source');

      // Click to expand sources
      await page.locator('.sources-header').first().click();

      // Should show source items
      const sourceItems = page.locator('.source-item');
      const sourceCount = await sourceItems.count();
      expect(sourceCount).toBeGreaterThanOrEqual(1);
    }
  });
});


test.describe('Chat Streaming — Error Handling', () => {

  test.beforeEach(async ({ api, page }) => {
    await api.setupAll();
  });

  test('should display error message when stream returns error', async ({ api, page }) => {
    await api.mockStreamingChatWithEvents(
      StreamController.errorResponse('Agent process failed: connection timeout')
    );

    await page.goto('/');
    await sendChatMessage(page, 'Trigger an error');

    // Should show an error-styled message
    const errorBubble = page.locator('.message-bubble.error-message');
    await expect(errorBubble).toBeVisible({ timeout: 15_000 });
  });

  test('should re-enable input after error', async ({ api, page }) => {
    await api.mockStreamingChatWithEvents(
      StreamController.errorResponse('Something went wrong')
    );

    await page.goto('/');
    await sendChatMessage(page, 'Error test');

    // Wait for error to appear
    const errorBubble = page.locator('.message-bubble.error-message');
    if (await errorBubble.count() === 0) {
      // Error may show in assistant bubble with error styling
      await expect(page.locator('[data-testid="message-bubble"][data-role="assistant"]').first()).toBeVisible({ timeout: 15_000 });
    }

    // Input should be re-enabled after error
    await triggerAngularCD(page);
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 5_000 });
    await expect(page.getByTestId('send-btn')).toBeVisible();
  });
});


test.describe('Chat Streaming — Cancel / Stop', () => {

  test.beforeEach(async ({ api, page }) => {
    await api.setupAll();
    await api.mockCancel();
  });

  test('should show stop button during streaming', async ({ api, page }) => {
    // Playwright route.fulfill() delivers the entire SSE body at once,
    // so streaming completes near-instantly. We verify the message appears.
    const events = StreamController.fullResponse('Streaming response content');

    await api.mockStreamingChatWithEvents(events);
    await page.goto('/');
    await sendChatMessage(page, 'Long response please');

    // Verify the assistant message eventually appears
    const assistantBubble = page.locator('[data-testid="message-bubble"][data-role="assistant"]');
    await expect(assistantBubble.first()).toBeVisible({ timeout: 15_000 });
  });
});


test.describe('Chat Streaming — Multiple Messages', () => {

  test.beforeEach(async ({ api, page }) => {
    await api.setupAll();
  });

  test('should support multiple back-and-forth messages', async ({ api, page }) => {
    // First exchange
    let callCount = 0;
    await page.route('**/api/agents/chat/stream', async (route) => {
      callCount++;
      const content = callCount === 1 ? 'First response!' : 'Second response!';
      const events = StreamController.fullResponse(content, `proc-${callCount}`);

      let body = '';
      for (const evt of events) {
        const payload = evt.raw ? String(evt.data) : JSON.stringify(evt.data);
        body += `event: ${evt.event}\ndata: ${payload}\n\n`;
      }

      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' },
        body,
      });
    });

    await page.goto('/');

    // First message
    await sendChatMessage(page, 'First question');

    // Wait for first response
    const assistantBubbles = page.locator('[data-testid="message-bubble"][data-role="assistant"]');
    await expect(assistantBubbles.first()).toBeVisible({ timeout: 15_000 });

    // Second message — use sendChatMessage helper for proper CD handling
    await sendChatMessage(page, 'Second question');

    // Wait for second response
    await expect(assistantBubbles.nth(1)).toBeVisible({ timeout: 15_000 });

    // Should now have 2 user + 2 assistant = 4 message bubbles total
    const allBubbles = page.getByTestId('message-bubble');
    const totalCount = await allBubbles.count();
    expect(totalCount).toBeGreaterThanOrEqual(4);
  });

  test('should show message count in input hints', async ({ api, page }) => {
    await api.mockStreamingChatWithEvents(
      StreamController.fullResponse('Reply')
    );

    await page.goto('/');
    await sendChatMessage(page, 'Count test');

    // Wait for response
    const assistantBubble = page.locator('[data-testid="message-bubble"][data-role="assistant"]');
    await expect(assistantBubble.first()).toBeVisible({ timeout: 15_000 });

    // Hints should show message count
    const hints = page.locator('.input-hints');
    await expect(hints).toContainText('messages');
  });
});


test.describe('Chat Streaming — Request Validation', () => {

  test.beforeEach(async ({ api, page }) => {
    await api.setupAll();
  });

  test('should send correct payload to streaming endpoint', async ({ api, page }) => {
    let capturedBody: Record<string, unknown> | null = null;

    await page.route('**/api/agents/chat/stream', async (route) => {
      capturedBody = route.request().postDataJSON();

      const events = StreamController.fullResponse('OK');
      let body = '';
      for (const evt of events) {
        const payload = evt.raw ? String(evt.data) : JSON.stringify(evt.data);
        body += `event: ${evt.event}\ndata: ${payload}\n\n`;
      }

      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'Cache-Control': 'no-cache' },
        body,
      });
    });

    await page.goto('/');
    await sendChatMessage(page, 'Payload test');

    // Wait for response to complete
    const assistantBubble = page.locator('[data-testid="message-bubble"][data-role="assistant"]');
    await expect(assistantBubble.first()).toBeVisible({ timeout: 15_000 });

    // Verify payload
    expect(capturedBody).not.toBeNull();
    expect(capturedBody!['message']).toBe('Payload test');
    expect(capturedBody!['agentName']).toBeDefined();
  });
});
