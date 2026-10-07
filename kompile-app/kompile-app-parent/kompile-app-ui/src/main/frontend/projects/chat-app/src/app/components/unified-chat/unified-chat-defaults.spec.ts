import { fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { AgentProvider } from '@shared/models/api-models';
import { UnifiedChatComponent } from './unified-chat.component';

describe('UnifiedChat numeric defaults and display boundaries', () => {
  let component: UnifiedChatComponent;

  beforeEach(() => {
    const unused = null as any;
    const cdr = jasmine.createSpyObj('cdr', ['markForCheck']);
    component = new UnifiedChatComponent(
      unused, unused, unused, unused, unused, unused, unused, unused, unused,
      unused, unused, unused, cdr, unused, unused, unused, unused, unused, unused
    );
  });

  it('preserves the millisecond-to-second display boundary', () => {
    expect(component.formatDuration(0)).toBe('0ms');
    expect(component.formatDuration(999)).toBe('999ms');
    expect(component.formatDuration(1000)).toBe('1.0s');
    expect(component.formatDuration(1250)).toBe('1.3s');
  });

  it('preserves token count abbreviation boundaries', () => {
    expect(component.formatTokenCount(999)).toBe('999');
    expect(component.formatTokenCount(1000)).toBe('1.0k');
    expect(component.formatTokenCount(999999)).toBe('1000.0k');
    expect(component.formatTokenCount(1000000)).toBe('1.0M');
  });

  it('uses the same API agent defaults initially, on reset, and when editing without values', () => {
    const expectDefaults = () => {
      expect(component.apiAgentTemperature).toBe(0.7);
      expect(component.apiAgentMaxTokens).toBe(4096);
    };
    expectDefaults();
    component.apiAgentTemperature = 0.2;
    component.apiAgentMaxTokens = 200;
    component.resetApiAgentForm();
    expectDefaults();
    component.editApiAgent({ name: 'agent', displayName: 'Agent' } as AgentProvider);
    expectDefaults();
  });

  it('preserves explicit API agent values including zero', () => {
    component.editApiAgent({ name: 'agent', displayName: 'Agent',
      temperature: 0, maxTokens: 256 } as AgentProvider);
    expect(component.apiAgentTemperature).toBe(0);
    expect(component.apiAgentMaxTokens).toBe(256);
  });

  it('keeps timeout choices in seconds with the five-minute default', () => {
    expect(component.timeoutSeconds).toBe(300);
    expect(component.timeoutOptions.map(option => option.value))
      .toEqual([0, 60, 120, 300, 600, 900, 1800]);
  });

  it('only truncates session previews above 50 characters, including the ellipsis', () => {
    const session = (content: string) => ({ messages: [{ role: 'user', content }] } as any);
    expect(component.getSessionPreview(session('a'.repeat(50)))).toBe('a'.repeat(50));
    expect(component.getSessionPreview(session('a'.repeat(51)))).toBe('a'.repeat(47) + '...');
  });

  it('clears copied-message feedback after two seconds', fakeAsync(() => {
    spyOn(navigator.clipboard, 'writeText').and.resolveTo();
    component.copyToClipboardPublic('hello', 3);
    flushMicrotasks();
    expect(component.copiedIndex).toBe(3);
    tick(1999);
    expect(component.copiedIndex).toBe(3);
    tick(1);
    expect(component.copiedIndex).toBeNull();
  }));
});
