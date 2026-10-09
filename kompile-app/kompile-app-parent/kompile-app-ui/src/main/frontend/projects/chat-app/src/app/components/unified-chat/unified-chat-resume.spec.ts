import { fakeAsync, tick } from '@angular/core/testing';
import { UnifiedChatComponent } from './unified-chat.component';
import { Subject } from 'rxjs';

// A phone that slept or reloaded mid-turn: the saved run reconnects and the transcript fills the gaps.
describe('Unified chat resume after sleep', () => {
  let component: UnifiedChatComponent;
  let bookmark: unknown;
  let reconnect: jasmine.Spy;
  beforeEach(() => {
    component = Object.create(UnifiedChatComponent.prototype);
    bookmark = { runId: 'harness-1', agent: { name: 'coder' } };
    (component as any).agentChatService = { getReconnectBookmark: () => bookmark,
      detachStreaming: jasmine.createSpy('detach'), cancelStreaming: jasmine.createSpy('cancel') };
    (component as any).activeStreamingSubs = [];
    (component as any).cdr = jasmine.createSpyObj('cdr', ['reattach']);
    (component as any).currentSession = { id: 'chat-1', messages: [] };
    reconnect = spyOn(component, 'reconnectCurrentRun');
  });

  it('reconnects a saved run when the page becomes visible', () => {
    component.resumeSavedRun();
    expect(reconnect).toHaveBeenCalledTimes(1);
  });

  it('detaches on pagehide and reconnects on return without cancelling or resubmitting', () => {
    component.isStreaming = true;
    component.isLoading = true;
    (component as any).isDetached = true;
    component.detachRunView();
    expect((component as any).agentChatService.detachStreaming).toHaveBeenCalledTimes(1);
    expect((component as any).agentChatService.cancelStreaming).not.toHaveBeenCalled();
    expect(component.isStreaming).toBeFalse();
    expect(component.isLoading).toBeFalse();
    expect((component as any).cdr.reattach).toHaveBeenCalledTimes(1);
    component.resumeSavedRun();
    expect(reconnect).toHaveBeenCalledTimes(1);
  });

  it('does not invalidate a transcript load when an idle page enters the back/forward cache', () => {
    (component as any).lifecycleRevision = 7;
    (component as any).workspaceTranscriptLoading = true;
    component.detachRunView();
    expect((component as any).lifecycleRevision).toBe(7);
    expect((component as any).workspaceTranscriptLoading).toBeTrue();
  });

  it('never falls back to cancellation when a view is destroyed', () => {
    (component as any).unregisterSessionConfigOpener = () => {};
    (component as any).subscriptions = [];
    (component as any).destroy$ = new Subject<void>();
    spyOn<any>(component, 'teardownMonitorSubscription');
    spyOn<any>(component, 'stopSyncPolling');
    component.isStreaming = true;
    component.ngOnDestroy();
    expect((component as any).agentChatService.detachStreaming).toHaveBeenCalledTimes(1);
    expect((component as any).agentChatService.cancelStreaming).not.toHaveBeenCalled();
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
