import { UnifiedChatComponent } from './unified-chat.component';

// Exercise actual component handlers without booting unrelated graph/config services.
describe('Unified chat harness hotkeys', () => {
  let component: UnifiedChatComponent;
  let control: jasmine.Spy;
  let stop: jasmine.Spy;
  beforeEach(() => {
    component = Object.create(UnifiedChatComponent.prototype);
    component.isStreaming = true;
    (component as any).agentChatService = {
      liveControlsReady: true,
      harnessActivity: { backgroundable: true, turnActive: true, processes: [], tasks: [] }
    };
    control = spyOn(component, 'sendHarnessControl').and.resolveTo();
    stop = spyOn(component, 'cancelStreaming');
  });

  it('forwards Ctrl+B exactly once and prevents browser formatting', () => {
    const event = new KeyboardEvent('keydown', { key: 'b', ctrlKey: true, cancelable: true });
    component.handleHarnessHotkey(event);
    expect(event.defaultPrevented).toBeTrue();
    expect(control).toHaveBeenCalledOnceWith('background');
    component.handleHarnessHotkey(event);
    expect(control).toHaveBeenCalledTimes(1);
    expect(stop).not.toHaveBeenCalled();
  });

  it('rejects ineligible tasks locally and never turns Ctrl+B into cancellation', () => {
    (component as any).agentChatService.harnessActivity.backgroundable = false;
    component.handleHarnessHotkey(new KeyboardEvent('keydown', { key: 'B', ctrlKey: true, cancelable: true }));
    expect(control).not.toHaveBeenCalled();
    expect(stop).not.toHaveBeenCalled();
    expect(component.harnessControlMessage).toContain('No blocking');
  });

  it('preserves copy, composition, repeat, modifiers, idle state and dialog keys', () => {
    for (const init of [
      { key: 'c', ctrlKey: true }, { key: 'b', ctrlKey: true, repeat: true },
      { key: 'b', ctrlKey: true, isComposing: true }, { key: 'b', ctrlKey: true, altKey: true },
      { key: 'b', ctrlKey: true, shiftKey: true }
    ]) {
      const event = new KeyboardEvent('keydown', { ...init, cancelable: true });
      component.handleHarnessHotkey(event);
      expect(event.defaultPrevented).toBeFalse();
    }
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    const event = new KeyboardEvent('keydown', { key: 'b', ctrlKey: true, cancelable: true });
    Object.defineProperty(event, 'target', { value: dialog });
    component.handleHarnessHotkey(event);
    component.isStreaming = false;
    component.handleHarnessHotkey(new KeyboardEvent('keydown', { key: 'b', ctrlKey: true }));
    expect(control).not.toHaveBeenCalled();
  });

  it('routes child Enter and summary Delete to that child without deleting composer text or stopping parent', () => {
    (component as any).agentChatService.harnessActivity.subagents = [{ id: 'child', canSend: true, canCancel: true }];
    component.subagentInputs = { child: 'follow up' };
    component.handleSubagentKey(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }), 'child', true);
    expect(control).toHaveBeenCalledWith('subagent_input', 'child', 'follow up');
    const deleteText = new KeyboardEvent('keydown', { key: 'Delete', cancelable: true });
    component.handleSubagentKey(deleteText, 'child', true);
    expect(deleteText.defaultPrevented).toBeFalse();
    component.handleSubagentKey(new KeyboardEvent('keydown', { key: 'Delete', cancelable: true }), 'child', false);
    expect(control).toHaveBeenCalledWith('subagent_cancel', 'child');
    expect(stop).not.toHaveBeenCalled();
  });

  it('Escape stops the run even when the composer is not focused', () => {
    const event = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true });
    component.handleHarnessHotkey(event);
    expect(event.defaultPrevented).toBeTrue();
    expect(stop).toHaveBeenCalledTimes(1);
  });

  it('queues follow-up text without launching or aborting a second stream', () => {
    component.userInput = 'Follow up';
    component.isLoading = false;
    component.sendMessage();
    expect(control).toHaveBeenCalledOnceWith('input', undefined, 'Follow up');
    expect(component.userInput).toBe('Follow up'); // clear only after CLI acknowledgement
    expect(stop).not.toHaveBeenCalled();
  });
});
