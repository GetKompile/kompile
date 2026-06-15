/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */

import { test as base, expect, Page, Route } from '@playwright/test';

/** SSE event helper — formats a Server-Sent Event line */
function sseEvent(event: string, data: unknown, raw = false): string {
  const payload = raw ? String(data) : JSON.stringify(data);
  return `event: ${event}\ndata: ${payload}\n\n`;
}

/** Typed agent provider matching backend AgentProvider */
interface MockAgent {
  name: string;
  displayName: string;
  command: string;
  skipPermissionsFlag: string;
  skipPermissions: boolean;
  args: string[];
  environment: Record<string, string>;
  available: boolean;
  isDefault: boolean;
  description: string;
  agentType?: 'CLI' | 'API';
}

const DEFAULT_AGENTS: MockAgent[] = [
  {
    name: 'claude-code',
    displayName: 'Claude Code',
    command: 'claude',
    skipPermissionsFlag: '--dangerously-skip-permissions',
    skipPermissions: true,
    args: [],
    environment: {},
    available: true,
    isDefault: true,
    description: 'Claude Code CLI agent',
    agentType: 'CLI',
  },
  {
    name: 'kompile',
    displayName: 'Kompile',
    command: 'kompile',
    skipPermissionsFlag: '',
    skipPermissions: false,
    args: [],
    environment: {},
    available: true,
    isDefault: false,
    description: 'Kompile CLI agent',
    agentType: 'CLI',
  },
];

/** Reusable API mock setup */
export class KompileApiMock {
  constructor(private page: Page) {}

  /** Mock the agent listing endpoints */
  async mockAgents(agents: MockAgent[] = DEFAULT_AGENTS): Promise<void> {
    const agentJson = JSON.stringify(agents);
    const availableJson = JSON.stringify(agents.filter(a => a.available));
    const defaultJson = JSON.stringify(agents.find(a => a.available) || agents[0]);

    // Use a single route handler that dispatches based on the path
    await this.page.route('**/api/agents**', (route) => {
      const url = new URL(route.request().url());
      const path = url.pathname;

      // Skip /api/agents/chat/* — those have their own handlers
      if (path.includes('/agents/chat/')) {
        route.fallback();
        return;
      }

      let body = agentJson; // default: GET /api/agents
      if (path.endsWith('/available')) {
        body = availableJson;
      } else if (path.endsWith('/default')) {
        body = defaultJson;
      } else if (path.includes('/diagnostics') || path.includes('/count')) {
        body = '{}';
      }

      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body,
      });
    });
  }

  /** Mock health endpoint */
  async mockHealth(): Promise<void> {
    await this.page.route('**/api/agents/chat/health', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"ok"}' })
    );
  }

  /** Mock fact sheets */
  async mockFactSheets(): Promise<void> {
    await this.page.route('**/api/fact-sheets/**', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    );
  }

  /** Mock config endpoint */
  async mockConfig(): Promise<void> {
    await this.page.route('**/api/config', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ appTitle: 'Kompile', theme: 'dark' }),
      })
    );
    await this.page.route('**/api/config/**', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({}),
      })
    );
  }

  /** Mock chat history */
  async mockChatHistory(): Promise<void> {
    await this.page.route('**/api/chat/sessions**', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    );
    await this.page.route('**/api/chat/history**', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    );
  }

  /** Mock model status */
  async mockModels(): Promise<void> {
    await this.page.route('**/api/models/**', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ models: [], status: 'ready' }),
      })
    );
  }

  /** Mock index status */
  async mockIndexStatus(): Promise<void> {
    await this.page.route('**/api/index/**', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ loaded: true, documentCount: 0 }),
      })
    );
  }

  /**
   * Mock the SSE streaming chat endpoint with controllable events.
   * Returns an object that lets the test push SSE events on demand.
   */
  async mockStreamingChat(): Promise<StreamController> {
    const controller = new StreamController();

    await this.page.route('**/api/agents/chat/stream', async (route) => {
      // Capture the request body for assertions
      const body = route.request().postDataJSON();
      controller._capturedRequest = body;

      // Start SSE response
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: {
          'Cache-Control': 'no-cache',
          'Connection': 'keep-alive',
        },
        body: controller._buildResponse(),
      });
    });

    return controller;
  }

  /**
   * Mock the streaming endpoint with a pre-built sequence of SSE events.
   * Useful for deterministic tests where you know the full response upfront.
   */
  async mockStreamingChatWithEvents(events: SseEventSpec[]): Promise<CapturedRequest> {
    const captured: CapturedRequest = { body: null };

    await this.page.route('**/api/agents/chat/stream', async (route) => {
      captured.body = route.request().postDataJSON();

      let body = '';
      for (const evt of events) {
        body += sseEvent(evt.event, evt.data, evt.raw);
      }

      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: {
          'Cache-Control': 'no-cache',
          'Connection': 'keep-alive',
        },
        body,
      });
    });

    return captured;
  }

  /** Mock cancel endpoint */
  async mockCancel(): Promise<void> {
    await this.page.route('**/api/agents/chat/cancel/**', route =>
      route.fulfill({ status: 200, body: '{}' })
    );
  }

  /** Catch-all: return empty JSON for any unhandled /api/ request */
  async mockFallbackApi(): Promise<void> {
    await this.page.route('**/api/**', route => {
      // Only handle if no other handler matched (Playwright calls handlers in registration order)
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: '{}',
      });
    });
  }

  /** Set up all standard mocks for a clean test environment */
  async setupAll(): Promise<void> {
    // Playwright checks routes in REVERSE registration order (last registered = first checked).
    // Register catch-all FIRST so it's checked LAST.
    await this.mockFallbackApi();
    // Then register specific mocks (checked before the catch-all)
    await this.mockHealth();
    await this.mockCancel();
    await this.mockFactSheets();
    await this.mockConfig();
    await this.mockChatHistory();
    await this.mockModels();
    await this.mockIndexStatus();
    // Agents mock last so it's checked first (highest priority)
    await this.mockAgents();
  }
}

export interface SseEventSpec {
  event: string;
  data: unknown;
  /** If true, data is sent as raw text (not JSON-serialized). Used for chunk events. */
  raw?: boolean;
}

export interface CapturedRequest {
  body: Record<string, unknown> | null;
}

/**
 * Controller for pushing SSE events in a pre-built response.
 * For simple tests, build the full response body at mock time.
 */
export class StreamController {
  _capturedRequest: Record<string, unknown> | null = null;
  private events: SseEventSpec[] = [];

  /** Queue an event to be included in the response body */
  pushEvent(event: string, data: unknown): this {
    this.events.push({ event, data });
    return this;
  }

  /** Build a complete SSE response body */
  _buildResponse(): string {
    return this.events.map(e => sseEvent(e.event, e.data, e.raw)).join('');
  }

  /** Convenience: build a standard chat response */
  static fullResponse(content: string, processId = 'test-proc-1'): SseEventSpec[] {
    // Send content as complete lines (matching real backend behavior)
    // The backend sends each output line as a separate chunk SSE event
    const chunks: SseEventSpec[] = [];

    chunks.push({ event: 'start', data: { processId } });

    // Send as a single chunk — the backend sends line-by-line output
    chunks.push({ event: 'chunk', data: content, raw: true });

    chunks.push({
      event: 'stats',
      data: {
        durationMs: 1234,
        tokenMetrics: {
          outputTokens: 42,
          inputTokens: 10,
          totalGenerationMs: 1200,
          tokensPerSecond: 35.0,
          model: 'test-model',
        }
      }
    });
    chunks.push({ event: 'complete', data: { processId } });

    return chunks;
  }

  /** Convenience: build an error response */
  static errorResponse(message: string, processId = 'test-proc-1'): SseEventSpec[] {
    return [
      { event: 'start', data: { processId } },
      { event: 'error', data: { error: message } },
    ];
  }

  /** Convenience: build a response with sources */
  static responseWithSources(content: string, sources: unknown[], processId = 'test-proc-1'): SseEventSpec[] {
    const events = StreamController.fullResponse(content, processId);
    // Insert sources event before stats
    const statsIdx = events.findIndex(e => e.event === 'stats');
    events.splice(statsIdx, 0, { event: 'sources', data: sources });
    return events;
  }
}

/**
 * Trigger Angular change detection on the chat component.
 * Playwright dispatches events at the CDP level, outside Angular's zone,
 * so markForCheck() calls from event handlers don't trigger a CD cycle.
 * This helper calls detectChanges() directly via Angular's debug API.
 */
export async function triggerAngularCD(page: Page): Promise<void> {
  await page.evaluate(() => {
    const chatEl = document.querySelector('app-unified-chat');
    const ng = (window as any).ng;
    if (ng && chatEl) {
      const comp = ng.getComponent(chatEl);
      if (comp?.cdr) comp.cdr.detectChanges();
    }
  });
}

/** Extended test fixture with KompileApiMock */
export const test = base.extend<{ api: KompileApiMock }>({
  api: async ({ page }, use) => {
    const api = new KompileApiMock(page);
    await use(api);
  },
});

export { expect };
