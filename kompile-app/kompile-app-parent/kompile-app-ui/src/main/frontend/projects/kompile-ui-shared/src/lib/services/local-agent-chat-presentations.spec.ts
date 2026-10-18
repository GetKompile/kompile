import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ChatStorageService } from './chat-storage.service';
import { LocalAgentChatService, projectProductToolPresentations } from './local-agent-chat.service';
import { ToolPresentationContext } from './markdown-renderer.service';
import { ProductDisplayTurn } from '../models/api-models';

describe('product presentation transport and canonical history projection', () => {
  const turnId = '0123456789abcdef0123456789abcdef';
  const context: ToolPresentationContext = { sessionId: 'chat-1', registrations: [
    { kind: 'example.record', serverId: 'example', toolName: 'show_record', validateData: data => data['id'] === 7 }
  ] };
  const turn = (): ProductDisplayTurn => ({ role: 'ASSISTANT', content: 'Answer', productTurnId: turnId,
    toolPresentations: [{ callId: 'call-1', toolName: 'show_record', textOffset: 0,
      presentation: { version: 1, kind: 'example.record', data: { id: 7 },
        source: { serverId: 'example', toolName: 'show_record' },
        identity: { sessionId: 'chat-1', turnId, callId: 'call-1' } } }] });
  let service: any;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule], providers: [
      LocalAgentChatService, { provide: ChatStorageService, useValue: { updateSession: () => {} } }
    ] });
    service = TestBed.inject(LocalAgentChatService) as any;
  });

  it('projects successful canonical metadata once with stable identity on each reload', () => {
    const saved = turn(); saved.toolPresentations!.push(saved.toolPresentations![0]);
    const first = projectProductToolPresentations(saved, context);
    const reloaded = projectProductToolPresentations(JSON.parse(JSON.stringify(saved)), context);
    expect(first.length).toBe(1);
    expect(first[0]).toEqual(jasmine.objectContaining({ status: 'completed', ok: true, callId: 'call-1', textOffset: 0, input: '' }));
    expect(reloaded).toEqual(first);
    expect(saved.content).toBe('Answer'); // no model history blocks are manufactured
  });

  it('cannot project prose, wrong turns/sessions/calls/tools or user metadata', () => {
    for (const change of [
      (t: any) => t.role = 'USER', (t: any) => delete t.productTurnId,
      (t: any) => t.productTurnId = 'fedcba9876543210fedcba9876543210',
      (t: any) => t.toolPresentations[0].presentation.identity.sessionId = 'other',
      (t: any) => t.toolPresentations[0].presentation.identity.callId = 'wrong',
      (t: any) => t.toolPresentations[0].toolName = 'write_record',
      (t: any) => t.toolPresentations[0].textOffset = -1,
      (t: any) => t.toolPresentations[0].ok = false,
      (t: any) => delete t.toolPresentations
    ]) {
      const saved = turn(); change(saved);
      expect(projectProductToolPresentations(saved, context)).withContext(change.toString()).toEqual([]);
    }
    expect(projectProductToolPresentations({ role: 'ASSISTANT', content: JSON.stringify(turn()) }, context)).toEqual([]);
  });

  it('keeps metadata only on explicit successful completion and dedupes replay without reopening it', () => {
    const event = projectProductToolPresentations(turn(), context)[0];
    for (const change of [{ status: 'started' }, { ok: false }, { ok: undefined }, { detail: { ...event.detail, error: 'denied' } }]) {
      expect(service.toToolCall({ ...event, ...change }).detail?.presentation).toBeUndefined();
    }
    service.currentStreamingMessage = { toolUses: [] };
    service.recordToolCall(service.toToolCall({ ...event, status: 'started' }));
    service.recordToolCall(service.toToolCall(event));
    service.recordToolCall(service.toToolCall(event));
    service.recordToolCall(service.toToolCall({ ...event, status: 'started' }));
    expect(service.currentStreamingMessage.toolUses.length).toBe(1);
    expect(service.currentStreamingMessage.toolUses[0].status).toBe('completed');
    expect(service.currentStreamingMessage.toolUses[0].detail.presentation).toEqual(event.detail!.presentation);
  });
});
