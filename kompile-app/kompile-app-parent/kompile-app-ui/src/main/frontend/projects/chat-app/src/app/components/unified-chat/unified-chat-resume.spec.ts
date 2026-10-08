import { fakeAsync, tick } from '@angular/core/testing';
import { UnifiedChatComponent } from './unified-chat.component';

// A phone that slept or reloaded mid-turn: the saved run reconnects and the transcript fills the gaps.
describe('Unified chat resume after sleep', () => {
  let component: UnifiedChatComponent;
  let bookmark: unknown;
  let reconnect: jasmine.Spy;
  beforeEach(() => {
    component = Object.create(UnifiedChatComponent.prototype);
    bookmark = { runId: 'harness-1', agent: { name: 'coder' } };
    (component as any).agentChatService = { getReconnectBookmark: () => bookmark };
    (component as any).currentSession = { id: 'chat-1', messages: [] };
    reconnect = spyOn(component, 'reconnectCurrentRun');
  });

  it('reconnects a saved run when the page becomes visible', () => {
    component.resumeSavedRun();
    expect(reconnect).toHaveBeenCalledTimes(1);
  });

  it('leaves a live, busy or finished run alone', () => {
    component.isStreaming = true;
    component.resumeSavedRun();
    component.isStreaming = false;
    (component as any).workspaceTranscriptLoading = true;
    component.resumeSavedRun();
    (component as any).workspaceTranscriptLoading = false;
    bookmark = null;
    component.resumeSavedRun();
    expect(reconnect).not.toHaveBeenCalled();
  });

  it('reloads the workspace transcript once the resumed run has ended', fakeAsync(() => {
    const refresh = spyOn(component, 'refreshWorkspaceTranscript');
    (component as any).workspaceChat = { id: 'chat-1' };
    (component as any).reloadAfterRun('other-chat');
    tick();
    expect(refresh).not.toHaveBeenCalled();
    (component as any).reloadAfterRun('chat-1');
    tick();
    expect(refresh).toHaveBeenCalledTimes(1);
  }));
});
