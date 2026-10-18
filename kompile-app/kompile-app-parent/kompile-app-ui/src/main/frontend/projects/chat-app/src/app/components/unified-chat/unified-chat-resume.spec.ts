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
    (component as any).cdr = jasmine.createSpyObj('cdr', ['reattach', 'markForCheck']);
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

  it('reloads a cached workspace page after a run finishes while no checkpoint is available', () => {
    bookmark = null;
    (component as any).workspaceChat = { id: 'chat-1', name: 'Chat' };
    component.isStreaming = true;
    component.detachRunView();
    const refresh = spyOn(component, 'refreshWorkspaceTranscript');
    component.resumeSavedRun();
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(reconnect).not.toHaveBeenCalled();
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

  it('recovers a run from the server when the browser checkpoint is gone', async () => {
    bookmark = null;
    const saved = { runId: 'harness-found', messageStart: 0, messageStartIndex: 2, seed: { messages: [{ content: 'build it' }] } };
    const discover = jasmine.createSpy('discover').and.callFake(async () => { bookmark = saved; return saved; });
    (component as any).agentChatService.discoverRun = discover;
    spyOn<any>(component, 'agentWorkingDirectory').and.returnValue('/project');
    component.messages = [{ role: 'user', content: 'older' }, { role: 'assistant', content: 'old reply' },
      { role: 'user', content: 'build it' }, { role: 'assistant', content: 'first result' },
      { role: 'user', content: 'build it' }] as any; // repeated text is not the run boundary
    component.resumeSavedRun();
    component.resumeSavedRun(); // repeated pageshow/visibility events share one discovery
    expect(component.lifecycleBusy).toBeTrue();
    await Promise.resolve(); await Promise.resolve();
    expect(discover).toHaveBeenCalledOnceWith('chat-1', '/project');
    expect(reconnect).toHaveBeenCalledTimes(1);
    expect((component.currentSession as any).harnessRunStartIndex).toBe(2);
    expect(component.lifecycleBusy).toBeFalse();
  });

  it('ignores a discovery that finishes after its view left', async () => {
    bookmark = null;
    let found!: (value: any) => void;
    (component as any).agentChatService.discoverRun = () => new Promise(resolve => { found = resolve; });
    spyOn<any>(component, 'agentWorkingDirectory').and.returnValue('/project');
    component.resumeSavedRun();
    component.detachRunView();
    found({ runId: 'harness-too-late' });
    await Promise.resolve(); await Promise.resolve();
    expect(reconnect).not.toHaveBeenCalled();
    expect(component.lifecycleBusy).toBeFalse();
  });

  it('can discover again on pageshow without a late lookup releasing the new busy state', async () => {
    bookmark = null;
    const replies: ((value: any) => void)[] = [];
    (component as any).agentChatService.discoverRun = () => new Promise(resolve => replies.push(resolve));
    spyOn<any>(component, 'agentWorkingDirectory').and.returnValue('/project');
    component.resumeSavedRun();
    component.detachRunView();
    component.resumeSavedRun();
    expect(replies.length).toBe(2);
    replies[0](null);
    await Promise.resolve(); await Promise.resolve();
    expect(component.lifecycleBusy).toBeTrue();
    replies[1](null);
    await Promise.resolve(); await Promise.resolve();
    expect(component.lifecycleBusy).toBeFalse();
  });

  it('releases the busy state after a failed discovery and shows the failure', async () => {
    bookmark = null;
    (component as any).agentChatService.discoverRun = () => Promise.reject(new Error('Network down'));
    spyOn<any>(component, 'agentWorkingDirectory').and.returnValue('/project');
    component.resumeSavedRun();
    await Promise.resolve(); await Promise.resolve();
    expect(component.lifecycleBusy).toBeFalse();
    expect(component.harnessControlMessage).toBe('Network down');
    expect(reconnect).not.toHaveBeenCalled();
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
