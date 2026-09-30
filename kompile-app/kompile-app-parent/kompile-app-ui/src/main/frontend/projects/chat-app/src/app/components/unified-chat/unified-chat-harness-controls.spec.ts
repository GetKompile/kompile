import { HarnessControlReply, WorkflowApprovalOutcome, WorkflowTeam } from '@shared/services/local-agent-chat.service';
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

// A /command typed mid-run, through the component's real control path to a stubbed harness reply.
describe('Unified chat live /commands', () => {
  let component: UnifiedChatComponent;
  let harness: jasmine.Spy;
  let control: jasmine.Spy;
  const reply = (fields: Partial<HarnessControlReply>): HarnessControlReply =>
    ({ requestId: 'r1', action: 'command', ok: true, message: '', ...fields });
  /** Types text into the composer, sends it and returns the control call still applying its reply. */
  const submit = (text: string, answer: HarnessControlReply | Error): Promise<void> => {
    if (answer instanceof Error) harness.and.rejectWith(answer);
    else harness.and.resolveTo(answer);
    component.userInput = text;
    component.sendMessage();
    return control.calls.mostRecent().returnValue;
  };

  beforeEach(() => {
    component = Object.create(UnifiedChatComponent.prototype);
    Object.assign(component, { isStreaming: true, isLoading: false, harnessControlPending: false,
      harnessControlMessage: '', queuedMessages: [], liveCommandOutput: null });
    harness = jasmine.createSpy('sendHarnessControl');
    (component as any).agentChatService = { liveControlsReady: true, sendHarnessControl: harness,
      harnessActivity: { backgroundable: false, turnActive: true, processes: [], tasks: [] } };
    (component as any).cdr = jasmine.createSpyObj('ChangeDetectorRef', ['detectChanges', 'markForCheck']);
    control = spyOn(component, 'sendHarnessControl').and.callThrough();
  });

  it('prints the answer to a command the live harness runs and clears the composer', async () => {
    await submit(' /processes', reply({ message: 'bash-1  RUNNING  make  (shared)' }));
    expect(harness).toHaveBeenCalledOnceWith('command', undefined, ' /processes');
    expect(component.liveCommandOutput).toEqual({ command: '/processes', text: 'bash-1  RUNNING  make  (shared)', ok: true });
    expect(component.userInput).toBe('');
    expect(component.harnessControlMessage).toBe('');
    expect(component.harnessControlPending).toBeFalse();
    expect((component as any).cdr.detectChanges).toHaveBeenCalled();
  });

  it('holds a deferred built-in for the end of the run and leaves model-bound commands to the harness queue', async () => {
    await submit('/compact', reply({ ok: false, deferred: true, message: '/compact runs when the current run finishes' }));
    expect(component.queuedMessages).toEqual(['/compact']);
    expect(component.userInput).toBe('');
    expect(component.liveCommandOutput).toBeNull();
    expect(component.harnessControlMessage).toBe('/compact runs when the current run finishes');

    const pending = submit('/review src', reply({ queued: true, message: 'Queued for the next turn' }));
    component.userInput = 'next thought'; // typed while the command was in flight
    await pending;
    expect(component.queuedMessages).toEqual(['/compact']);
    expect(component.userInput).toBe('next thought');
    expect(component.liveCommandOutput).toBeNull();
    expect(component.harnessControlMessage).toBe('Queued for the next turn');
  });

  it('keeps a rejected command in the composer and shows why', async () => {
    await submit('/process-kill nope', reply({ ok: false, message: 'No process nope' }));
    expect(component.liveCommandOutput).toEqual({ command: '/process-kill nope', text: 'No process nope', ok: false });
    expect(component.userInput).toBe('/process-kill nope');
    expect(component.queuedMessages).toEqual([]);

    await submit('/jobs', new Error('Wait for pending controls'));
    expect(component.harnessControlMessage).toBe('Wait for pending controls');
    expect(component.userInput).toBe('/jobs');
    expect(component.harnessControlPending).toBeFalse();
  });

  it('sends other text as input and clears it once the harness accepts it', async () => {
    await submit('Also run the tests', reply({ action: 'input', message: 'Queued' }));
    expect(harness).toHaveBeenCalledOnceWith('input', undefined, 'Also run the tests');
    expect(component.userInput).toBe('');
    expect(component.liveCommandOutput).toBeNull();
  });
});

// A session started with a workflow team: the panel's rows, and gate approval through the component.
describe('Unified chat workflow team', () => {
  let component: UnifiedChatComponent;
  let approve: jasmine.Spy;
  const team: WorkflowTeam = { name: 'review', version: 2, lead: 'lead',
    participants: [
      { id: 'lead', role: 'planner', model: 'custom/lead-model', capabilities: ['plan'], delegatesTo: ['worker'] },
      { id: 'worker', role: 'implementer', model: 'custom/worker-model', capabilities: [] }],
    routing: { implement: 'worker', review: 'lead' },
    gates: { implementationRequires: 'design', completionRequires: 'ship', approved: ['design'] },
    maxConcurrentWorkers: 1 };

  beforeEach(() => {
    component = Object.create(UnifiedChatComponent.prototype);
    Object.assign(component, { isStreaming: false, workflowApprovalPending: false, workflowApprovalMessage: '',
      currentSession: { id: 'browser-1' } });
    approve = jasmine.createSpy('approveWorkflowGate');
    (component as any).agentChatService = { approveWorkflowGate: approve,
      getWorkflowTeam: (id: string | undefined) => id === 'browser-1' ? team : null };
    (component as any).cdr = jasmine.createSpyObj('ChangeDetectorRef', ['detectChanges', 'markForCheck']);
  });

  it('lists the gates in blocking order with those approved, and the routing, as the terminal summary does', () => {
    expect(component.workflowTeam).toBe(team);
    expect(component.workflowGates).toEqual([
      { label: 'Implementation begins after', name: 'design', approved: true },
      { label: 'Workflow completes after', name: 'ship', approved: false }]);
    expect(component.workflowGatesAwaiting).toBe(1);
    expect(component.workflowRoutes).toEqual([{ purpose: 'implement', target: 'worker' }, { purpose: 'review', target: 'lead' }]);
    (component as any).currentSession = { id: 'browser-2' };
    expect(component.workflowTeam).toBeNull();
    expect(component.workflowGates).toEqual([]);
    expect(component.workflowRoutes).toEqual([]);
  });

  it('approves a gate for the displayed session and shows the harness answer', async () => {
    approve.and.resolveTo({ ok: true, message: "Approved gate 'ship' for workflow 'review'." });
    await component.approveWorkflowGate('ship');
    expect(approve).toHaveBeenCalledOnceWith('browser-1', 'ship', undefined);
    expect(component.workflowApprovalMessage).toBe("Approved gate 'ship' for workflow 'review'.");
    expect(component.workflowApprovalPending).toBeFalse();
    expect((component as any).cdr.detectChanges).toHaveBeenCalled();
  });

  it('shows a failed approval, ignores a second click, and drops an answer for a session no longer shown', async () => {
    approve.and.rejectWith(new Error('Harness acknowledgement timed out'));
    await component.approveWorkflowGate();
    expect(approve).toHaveBeenCalledOnceWith('browser-1', undefined, undefined);
    expect(component.workflowApprovalMessage).toBe('Harness acknowledgement timed out');
    expect(component.workflowApprovalPending).toBeFalse();

    let answer!: (outcome: WorkflowApprovalOutcome) => void;
    approve.and.returnValue(new Promise<WorkflowApprovalOutcome>(resolve => answer = resolve));
    const pending = component.approveWorkflowGate('ship');
    expect(component.workflowApprovalPending).toBeTrue();
    await component.approveWorkflowGate('ship');
    expect(approve).toHaveBeenCalledTimes(2);
    (component as any).currentSession = { id: 'browser-2' };
    answer({ ok: true, message: 'Approved' });
    await pending;
    expect(component.workflowApprovalMessage).toBe('');
    expect(component.workflowApprovalPending).toBeFalse();
  });
});
