import { UnifiedChatComponent } from './unified-chat.component';

describe('UnifiedChat generated titles', () => {
  let component: UnifiedChatComponent;
  let view: any;
  let save: jasmine.Spy;

  beforeEach(() => {
    const unused = null as any;
    component = new UnifiedChatComponent(
      unused, unused, unused, unused, unused, unused, unused, unused, unused, unused,
      unused, unused, unused, unused, unused, unused, unused, unused, unused
    );
    view = component as any;
    view.cdr = { markForCheck: jasmine.createSpy('markForCheck') };
    save = spyOn(view, 'saveSessions');
    component.currentSession = { id: 'browser', name: 'Long first prompt', autoTitle: true,
      messages: [], createdAt: '', updatedAt: '' };
  });

  it('replaces the prompt fallback and saves it without creating a chat message', () => {
    view.applyGeneratedSessionTitle({ sessionId: 'browser', title: 'Fix login authentication' });
    expect(component.currentSession!.name).toBe('Fix login authentication');
    expect(component.currentSession!.messages).toEqual([]);
    expect(save).toHaveBeenCalledTimes(1);
  });

  it('keeps a manual rename even if it matches the provisional title', () => {
    component.editingSessionName = component.currentSession!.name;
    component.saveSessionName(component.currentSession!);
    save.calls.reset();
    view.applyGeneratedSessionTitle({ sessionId: 'browser', title: 'Generated' });
    expect(component.currentSession!.name).toBe('Long first prompt');
    expect(save).not.toHaveBeenCalled();
  });

  it('keeps an explicit New Chat name rather than treating it as a placeholder', () => {
    component.editingSessionName = 'New Chat';
    component.saveSessionName(component.currentSession!);
    component.messages = [{ id: 'u', role: 'user', content: 'Fix login', timestamp: new Date() }];
    view.updateCurrentSession();
    view.applyGeneratedSessionTitle({ sessionId: 'browser', title: 'Generated' });
    expect(component.currentSession!.name).toBe('New Chat');
  });

  it('does not rename a different session, a custom initial name, or with blank output', () => {
    view.applyGeneratedSessionTitle({ sessionId: 'another', title: 'Wrong' });
    view.applyGeneratedSessionTitle({ sessionId: 'browser', title: '  ' });
    component.currentSession!.autoTitle = false;
    view.applyGeneratedSessionTitle({ sessionId: 'browser', title: 'Wrong' });
    expect(component.currentSession!.name).toBe('Long first prompt');
    expect(save).not.toHaveBeenCalled();
  });
});
