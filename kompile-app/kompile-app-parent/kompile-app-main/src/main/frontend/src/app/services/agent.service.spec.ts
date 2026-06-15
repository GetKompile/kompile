/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { AgentService } from './agent.service';
import {
  AgentAvailabilityResponse,
  AgentCountSummary,
  AgentDiagnosticSummary,
  AgentProvider,
  ApiAgentConfigRequest,
  KompileLocalModelStatus
} from '../models/api-models';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeAgent(overrides: Partial<AgentProvider> = {}): AgentProvider {
  return {
    name: 'claude',
    displayName: 'Claude Code',
    command: 'claude',
    skipPermissionsFlag: '--dangerously-skip-permissions',
    skipPermissions: true,
    args: [],
    environment: {},
    available: true,
    isDefault: true,
    description: 'Anthropic Claude Code agent',
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────

describe('AgentService', () => {
  let service: AgentService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AgentService]
    });

    service = TestBed.inject(AgentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. getAllAgents()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAllAgents()', () => {
    it('should GET /api/agents and return agent list', () => {
      const mockAgents: AgentProvider[] = [
        makeAgent({ name: 'claude', isDefault: true }),
        makeAgent({ name: 'codex', displayName: 'OpenAI Codex', isDefault: false, available: false })
      ];

      service.getAllAgents().subscribe(agents => {
        expect(agents.length).toBe(2);
        expect(agents[0].name).toBe('claude');
        expect(agents[1].available).toBeFalse();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents'));
      expect(req.request.method).toBe('GET');
      req.flush(mockAgents);
    });

    it('should update agents$ BehaviorSubject after fetching', () => {
      const mockAgents = [makeAgent(), makeAgent({ name: 'gemini', isDefault: false })];
      const emissions: AgentProvider[][] = [];
      service.agents$.subscribe(a => emissions.push(a));

      service.getAllAgents().subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/agents'));
      req.flush(mockAgents);

      const last = emissions[emissions.length - 1];
      expect(last.length).toBe(2);
    });

    it('should auto-select the default available agent when none is selected', () => {
      const mockAgents = [
        makeAgent({ name: 'claude', isDefault: true, available: true })
      ];

      service.getAllAgents().subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/agents'));
      req.flush(mockAgents);

      expect(service.getSelectedAgent()?.name).toBe('claude');
    });

    it('should fall back to first available agent if no default is available', () => {
      const mockAgents = [
        makeAgent({ name: 'codex', isDefault: false, available: true }),
        makeAgent({ name: 'claude', isDefault: true, available: false })
      ];

      service.getAllAgents().subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/agents'));
      req.flush(mockAgents);

      expect(service.getSelectedAgent()?.name).toBe('codex');
    });

    it('should handle server error gracefully', () => {
      service.getAllAgents().subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. getAvailableAgents()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAvailableAgents()', () => {
    it('should GET /api/agents/available and return only available agents', () => {
      const mockAgents = [makeAgent({ name: 'claude', available: true })];

      service.getAvailableAgents().subscribe(agents => {
        expect(agents.length).toBe(1);
        expect(agents[0].available).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/available'));
      expect(req.request.method).toBe('GET');
      req.flush(mockAgents);
    });

    it('should return empty list when no agents are available', () => {
      service.getAvailableAgents().subscribe(agents => {
        expect(agents.length).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/available'));
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. getDefaultAgent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getDefaultAgent()', () => {
    it('should GET /api/agents/default and return the default agent', () => {
      const mockAgent = makeAgent({ name: 'claude', isDefault: true });

      service.getDefaultAgent().subscribe(agent => {
        expect(agent.name).toBe('claude');
        expect(agent.isDefault).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/default'));
      expect(req.request.method).toBe('GET');
      req.flush(mockAgent);
    });

    it('should handle 404 when no default agent is configured', () => {
      service.getDefaultAgent().subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/default'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. selectAgent() / getSelectedAgent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('selectAgent()', () => {
    it('should update selectedAgent$ when an agent is selected', () => {
      const agent = makeAgent({ name: 'gemini', displayName: 'Gemini CLI' });
      let emitted: AgentProvider | null = null;
      service.selectedAgent$.subscribe(a => (emitted = a));

      service.selectAgent(agent);

      expect((emitted as AgentProvider | null)?.name).toBe('gemini');
    });

    it('should set selectedAgent$ to null when null is passed', () => {
      const agent = makeAgent();
      service.selectAgent(agent);

      let emitted: AgentProvider | null = agent;
      service.selectedAgent$.subscribe(a => (emitted = a));

      service.selectAgent(null);

      expect(emitted).toBeNull();
    });
  });

  describe('getSelectedAgent()', () => {
    it('should return null initially', () => {
      expect(service.getSelectedAgent()).toBeNull();
    });

    it('should return selected agent after selectAgent is called', () => {
      const agent = makeAgent({ name: 'claude' });
      service.selectAgent(agent);
      expect(service.getSelectedAgent()?.name).toBe('claude');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. checkAgentAvailability()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('checkAgentAvailability()', () => {
    it('should POST to /api/agents/:agentName/check and return availability response', () => {
      const mockResponse: AgentAvailabilityResponse = {
        agentName: 'claude',
        available: true,
        timestamp: Date.now()
      };

      service.checkAgentAvailability('claude').subscribe(response => {
        expect(response.agentName).toBe('claude');
        expect(response.available).toBeTrue();
        expect(response.timestamp).toBeGreaterThan(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/claude/check'));
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });

    it('should report unavailable when agent CLI is not installed', () => {
      const mockResponse: AgentAvailabilityResponse = {
        agentName: 'codex',
        available: false,
        timestamp: Date.now()
      };

      service.checkAgentAvailability('codex').subscribe(response => {
        expect(response.available).toBeFalse();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/codex/check'));
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. getApiAgentConfigs()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getApiAgentConfigs()', () => {
    it('should GET /api/agents/api-config and return configured API agents', () => {
      const mockAgents: AgentProvider[] = [
        makeAgent({ name: 'my-openai', agentType: 'API', endpointUrl: 'https://api.openai.com/v1' })
      ];

      service.getApiAgentConfigs().subscribe(agents => {
        expect(agents.length).toBe(1);
        expect(agents[0].agentType).toBe('API');
        expect(agents[0].endpointUrl).toBe('https://api.openai.com/v1');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/api-config'));
      expect(req.request.method).toBe('GET');
      req.flush(mockAgents);
    });

    it('should return empty list when no API agents are configured', () => {
      service.getApiAgentConfigs().subscribe(agents => {
        expect(agents.length).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/api-config'));
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. addApiAgentConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('addApiAgentConfig()', () => {
    it('should POST to /api/agents/api-config with config request', () => {
      const config: ApiAgentConfigRequest = {
        name: 'my-api-agent',
        displayName: 'My API Agent',
        endpointUrl: 'https://myserver.com/v1',
        apiKey: 'sk-test-key',
        modelName: 'gpt-4',
        temperature: 0.7
      };
      const mockResponse = { success: true, name: 'my-api-agent' };

      service.addApiAgentConfig(config).subscribe(response => {
        expect(response.success).toBeTrue();
        expect(response.name).toBe('my-api-agent');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/api-config'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.endpointUrl).toBe('https://myserver.com/v1');
      expect(req.request.body.modelName).toBe('gpt-4');
      req.flush(mockResponse);
    });

    it('should handle 400 when endpoint URL is invalid', () => {
      const config: ApiAgentConfigRequest = { endpointUrl: 'not-a-url' };

      service.addApiAgentConfig(config).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/api-config'));
      req.flush({ error: 'Invalid URL' }, { status: 400, statusText: 'Bad Request' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. deleteApiAgentConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteApiAgentConfig()', () => {
    it('should DELETE /api/agents/api-config/:name', () => {
      const agentName = 'my-api-agent';
      const mockResponse = { deleted: true };

      service.deleteApiAgentConfig(agentName).subscribe(response => {
        expect(response.deleted).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/agents/api-config/${agentName}`));
      expect(req.request.method).toBe('DELETE');
      req.flush(mockResponse);
    });

    it('should handle 404 when agent config does not exist', () => {
      service.deleteApiAgentConfig('ghost-agent').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/api-config/ghost-agent'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. testApiEndpoint()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('testApiEndpoint()', () => {
    it('should POST to /api/agents/api-config/test-endpoint with config', () => {
      const config: ApiAgentConfigRequest = {
        endpointUrl: 'https://api.openai.com/v1',
        apiKey: 'sk-test',
        modelName: 'gpt-3.5-turbo'
      };
      const mockResponse = { reachable: true, latencyMs: 120, modelVerified: true };

      service.testApiEndpoint(config).subscribe(response => {
        expect(response.reachable).toBeTrue();
        expect(response.latencyMs).toBe(120);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/api-config/test-endpoint'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.endpointUrl).toBe('https://api.openai.com/v1');
      req.flush(mockResponse);
    });

    it('should handle unreachable endpoint error', () => {
      const config: ApiAgentConfigRequest = { endpointUrl: 'https://unreachable.example.com/v1' };

      service.testApiEndpoint(config).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/api-config/test-endpoint'));
      req.flush({ error: 'Connection refused' }, { status: 502, statusText: 'Bad Gateway' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. getKompileLocalStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getKompileLocalStatus()', () => {
    it('should GET /api/agents/kompile-local/status and return local model status', () => {
      const mockStatus: KompileLocalModelStatus = {
        connected: true,
        stagingUrl: 'http://localhost:8000',
        modelId: 'llama-3-8b',
        modelLoaded: true,
        agentRegistered: true,
        message: 'Connected and ready'
      };

      service.getKompileLocalStatus().subscribe(status => {
        expect(status.connected).toBeTrue();
        expect(status.stagingUrl).toBe('http://localhost:8000');
        expect(status.modelLoaded).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/kompile-local/status'));
      expect(req.request.method).toBe('GET');
      req.flush(mockStatus);
    });

    it('should reflect disconnected state correctly', () => {
      const mockStatus: KompileLocalModelStatus = {
        connected: false,
        stagingUrl: '',
        modelLoaded: false,
        agentRegistered: false,
        message: 'Not connected'
      };

      service.getKompileLocalStatus().subscribe(status => {
        expect(status.connected).toBeFalse();
        expect(status.modelLoaded).toBeFalse();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/kompile-local/status'));
      req.flush(mockStatus);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. connectKompileLocal()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('connectKompileLocal()', () => {
    it('should POST to /api/agents/kompile-local/connect with staging URL', () => {
      const stagingUrl = 'http://localhost:8000';
      const mockResponse = { connected: true, message: 'Connected to local model' };

      service.connectKompileLocal(stagingUrl).subscribe(response => {
        expect(response.connected).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/kompile-local/connect'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.stagingUrl).toBe(stagingUrl);
      req.flush(mockResponse);
    });

    it('should handle connection failure', () => {
      service.connectKompileLocal('http://unreachable:9999').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/kompile-local/connect'));
      req.flush({ error: 'Connection refused' }, { status: 503, statusText: 'Service Unavailable' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. disconnectKompileLocal()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('disconnectKompileLocal()', () => {
    it('should POST to /api/agents/kompile-local/disconnect', () => {
      const mockResponse = { disconnected: true };

      service.disconnectKompileLocal().subscribe(response => {
        expect(response.disconnected).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/kompile-local/disconnect'));
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. getDiagnosticSummary()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getDiagnosticSummary()', () => {
    it('should GET /api/agents/diagnostics/summary and return summary', () => {
      const mockSummary: AgentDiagnosticSummary = {
        hasActiveProcess: true,
        activeProcessId: 'proc-abc',
        activeAgentName: 'claude',
        recentProcessCount: 5,
        failedProcessCount: 1
      };

      service.getDiagnosticSummary().subscribe(summary => {
        expect(summary.hasActiveProcess).toBeTrue();
        expect(summary.activeProcessId).toBe('proc-abc');
        expect(summary.recentProcessCount).toBe(5);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/diagnostics/summary'));
      expect(req.request.method).toBe('GET');
      req.flush(mockSummary);
    });

    it('should update diagnosticSummary$ BehaviorSubject after fetching', () => {
      const mockSummary: AgentDiagnosticSummary = {
        hasActiveProcess: false,
        recentProcessCount: 0,
        failedProcessCount: 0
      };

      let emitted: AgentDiagnosticSummary | null = null;
      service.diagnosticSummary$.subscribe(s => (emitted = s));

      service.getDiagnosticSummary().subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/agents/diagnostics/summary'));
      req.flush(mockSummary);

      expect((emitted as AgentDiagnosticSummary | null)?.hasActiveProcess).toBeFalse();
    });

    it('should handle error when diagnostics endpoint is unavailable', () => {
      service.getDiagnosticSummary().subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/diagnostics/summary'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. agents$ / selectedAgent$ BehaviorSubjects
  // ─────────────────────────────────────────────────────────────────────────────

  describe('agents$', () => {
    it('should emit empty array initially', (done) => {
      service.agents$.subscribe(agents => {
        expect(agents).toEqual([]);
        done();
      });
    });
  });

  describe('selectedAgent$', () => {
    it('should emit null initially', (done) => {
      service.selectedAgent$.subscribe(agent => {
        expect(agent).toBeNull();
        done();
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 15. hasAvailableAgents() / getFirstAvailableAgent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('hasAvailableAgents()', () => {
    it('should return false when agents cache is empty', () => {
      expect(service.hasAvailableAgents()).toBeFalse();
    });

    it('should return true when at least one agent is available', () => {
      (service as any).agentsSubject.next([
        makeAgent({ name: 'claude', available: true }),
        makeAgent({ name: 'codex', available: false })
      ]);
      expect(service.hasAvailableAgents()).toBeTrue();
    });

    it('should return false when all agents are unavailable', () => {
      (service as any).agentsSubject.next([
        makeAgent({ name: 'claude', available: false }),
        makeAgent({ name: 'codex', available: false })
      ]);
      expect(service.hasAvailableAgents()).toBeFalse();
    });
  });

  describe('getFirstAvailableAgent()', () => {
    it('should return undefined when no agents in cache', () => {
      expect(service.getFirstAvailableAgent()).toBeUndefined();
    });

    it('should return first available agent', () => {
      (service as any).agentsSubject.next([
        makeAgent({ name: 'claude', available: false }),
        makeAgent({ name: 'codex', available: true }),
        makeAgent({ name: 'gemini', available: true })
      ]);
      const first = service.getFirstAvailableAgent();
      expect(first?.name).toBe('codex');
    });

    it('should return undefined when all agents are unavailable', () => {
      (service as any).agentsSubject.next([
        makeAgent({ name: 'claude', available: false })
      ]);
      expect(service.getFirstAvailableAgent()).toBeUndefined();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 16. getAgentCounts()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAgentCounts()', () => {
    it('should GET /api/agents/count and return count summary', () => {
      const mockCounts: AgentCountSummary = { total: 3, available: 2 };

      service.getAgentCounts().subscribe(counts => {
        expect(counts.total).toBe(3);
        expect(counts.available).toBe(2);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/count'));
      expect(req.request.method).toBe('GET');
      req.flush(mockCounts);
    });

    it('should handle zero agents case', () => {
      const mockCounts: AgentCountSummary = { total: 0, available: 0 };

      service.getAgentCounts().subscribe(counts => {
        expect(counts.total).toBe(0);
        expect(counts.available).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/agents/count'));
      req.flush(mockCounts);
    });
  });
});
