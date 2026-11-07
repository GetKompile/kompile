import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SafetySettingsComponent } from './safety-settings.component';

describe('SafetySettingsComponent', () => {
  let fixture: ComponentFixture<SafetySettingsComponent>;
  let component: SafetySettingsComponent;
  const permissions = { menu: 'permissions', canManage: true, rows: [
    { key: 'bash', defaultLevel: 'allow', effectiveLevel: 'allow', overrideLevel: null, roleLevel: null },
    { key: 'write', defaultLevel: 'allow', effectiveLevel: 'deny', overrideLevel: null, roleLevel: 'deny' }
  ] };
  const hooks = { menu: 'hooks', canManage: true, events: ['PreToolUse', 'PostToolUse'], hooks: [
    { id: 'saved', scope: 'global', event: 'PreToolUse', matcher: '^read$', enabled: true, hooks: [{ command: '<img src=x onerror=bad()>', timeout: 30 }] },
    { id: 'multi', scope: 'project', event: 'PostToolUse', matcher: '.*', enabled: true, hooks: [{ command: 'one', timeout: 10 }, { command: 'two', timeout: 10 }] }
  ] };
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [SafetySettingsComponent] }).compileComponents();
    fixture = TestBed.createComponent(SafetySettingsComponent); component = fixture.componentInstance;
    fixture.componentRef.setInput('permissionMenu', permissions); fixture.componentRef.setInput('hooksMenu', hooks);
    fixture.detectChanges();
  });
  function outcome(command: string, ok: boolean, data: unknown = null): void {
    fixture.componentRef.setInput('outcome', { command, ok, text: ok ? 'Saved' : 'Refused', data }); fixture.detectChanges();
  }
  it('shows effective levels and role ceilings without executing anything', async () => {
    expect(fixture.nativeElement.textContent).toContain('deny');
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    component.editPermissions(); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-permission="write"]').disabled).toBeTrue();
    expect(fixture.nativeElement.querySelector('[data-permission="bash"]').disabled).toBeFalse();
  });
  it('requires explicit confirmation and only emits known permission overrides', () => {
    const dispatch = spyOn(component.dispatch, 'emit'); component.editPermissions();
    component.permissionDraft!['bash'] = 'ask'; component.savePermissions(); expect(dispatch).not.toHaveBeenCalled();
    component.confirmed = true; component.savePermissions(); component.savePermissions();
    expect(dispatch).toHaveBeenCalledOnceWith('/permissions save ' + JSON.stringify({ overrides: { bash: 'ask' }, confirmed: true }));
    outcome('permissions', true, permissions); expect(component.permissionDraft).toBeNull();
  });
  it('keeps a refused permission draft but requires renewed confirmation', () => {
    component.editPermissions(); component.permissionDraft!['bash'] = 'deny'; component.confirmed = true; component.savePermissions();
    outcome('permissions', false);
    expect(component.permissionDraft!['bash']).toBe('deny'); expect(component.confirmed).toBeFalse();
    expect(component.error).toBe('Refused');
  });
  it('stages hook shell authorization and global scope before a single save', () => {
    const dispatch = spyOn(component.dispatch, 'emit'); component.editHook();
    component.hookDraft!.hooks[0].command = 'check-script'; component.hookDraft!.scope = 'global';
    component.saveHook(); expect(dispatch).not.toHaveBeenCalled();
    component.confirmed = true; component.saveHook(); component.saveHook();
    expect(dispatch).toHaveBeenCalledOnceWith('/hooks apply ' + JSON.stringify({ action: 'add', scope: 'global', confirmed: true,
      event: 'PreToolUse', matcher: '.*', hooks: [{ type: 'command', command: 'check-script', timeout: 30 }] }));
  });
  it('stages multi-command toggles without replacing any commands', () => {
    const dispatch = spyOn(component.dispatch, 'emit'); component.editHook(hooks.hooks[1], 'disable');
    component.confirmed = true; component.saveHook();
    expect(dispatch).toHaveBeenCalledOnceWith('/hooks apply ' + JSON.stringify({ action: 'disable', scope: 'project', confirmed: true, id: 'multi' }));
  });
  it('edits the complete ordered command list through DOM controls without mutating persisted state', async () => {
    const dispatch = spyOn(component.dispatch, 'emit');
    fixture.nativeElement.querySelector('[data-hook-id="multi"] button').click(); fixture.detectChanges();
    await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('[data-testid="hook-command-row"]').length).toBe(2);
    const timeout = fixture.nativeElement.querySelectorAll('[data-testid="hook-timeout"]')[1] as HTMLInputElement;
    timeout.value = '52'; timeout.dispatchEvent(new Event('input')); fixture.detectChanges();
    const confirm = fixture.nativeElement.querySelector('[data-testid="hook-confirm"]') as HTMLInputElement;
    confirm.click(); fixture.detectChanges(); expect(component.confirmed).toBeTrue();
    fixture.nativeElement.querySelectorAll('[data-testid="hook-command-up"]')[1].click(); fixture.detectChanges();
    expect(component.confirmed).toBeFalse();
    await fixture.whenStable(); fixture.detectChanges(); expect(confirm.checked).toBeFalse();
    fixture.nativeElement.querySelector('[data-testid="hook-save"]').click(); expect(dispatch).not.toHaveBeenCalled();
    expect(hooks.hooks[1].hooks).toEqual([{ command: 'one', timeout: 10 }, { command: 'two', timeout: 10 }]);
    confirm.click(); fixture.detectChanges(); fixture.nativeElement.querySelector('[data-testid="hook-save"]').click();
    expect(dispatch).toHaveBeenCalledOnceWith('/hooks apply ' + JSON.stringify({ action: 'edit', scope: 'project', confirmed: true, id: 'multi',
      event: 'PostToolUse', matcher: '.*', hooks: [{ type: 'command', command: 'two', timeout: 52 }, { type: 'command', command: 'one', timeout: 10 }] }));
    outcome('hooks', true, hooks); expect(component.hookDraft).toBeNull();
  });
  it('adds and removes draft commands, requires reconfirmation and cancels without dispatch', async () => {
    const dispatch = spyOn(component.dispatch, 'emit'); component.editHook(hooks.hooks[1], 'edit'); fixture.detectChanges();
    await fixture.whenStable(); fixture.detectChanges();
    component.confirmed = true; fixture.nativeElement.querySelector('[data-testid="hook-command-add"]').click(); fixture.detectChanges();
    await fixture.whenStable(); fixture.detectChanges();
    expect(component.confirmed).toBeFalse(); expect(component.hookDraft!.hooks.length).toBe(3);
    component.confirmed = true; expect(component.canSaveHook()).toBeFalse();
    const command = fixture.nativeElement.querySelectorAll('[data-testid="hook-command"]')[2] as HTMLTextAreaElement;
    command.value = 'three'; command.dispatchEvent(new Event('input')); fixture.detectChanges();
    expect(component.confirmed).toBeFalse();
    fixture.nativeElement.querySelectorAll('[data-testid="hook-command-remove"]')[0].click(); fixture.detectChanges();
    expect(component.hookDraft!.hooks.map(item => item.command)).toEqual(['two', 'three']);
    component.removeHookCommand(1); component.removeHookCommand(0); expect(component.hookDraft!.hooks.length).toBe(1);
    component.cancel(); expect(dispatch).not.toHaveBeenCalled(); expect(hooks.hooks[1].hooks.length).toBe(2);
  });
  it('retains every command and individual timeout on refusal, and freezes command edits while saving', () => {
    component.editHook(hooks.hooks[1], 'edit'); component.hookDraft!.hooks[0].timeout = 23; component.confirmed = true;
    component.saveHook(); component.addHookCommand(); component.removeHookCommand(0); component.moveHookCommand(1, -1);
    expect(component.hookDraft!.hooks).toEqual([{ command: 'one', timeout: 23 }, { command: 'two', timeout: 10 }]);
    outcome('hooks', false); expect(component.hookDraft!.hooks.length).toBe(2); expect(component.hookDraft!.hooks[0].timeout).toBe(23);
    expect(component.confirmed).toBeFalse(); expect(component.error).toBe('Refused');
  });
  it('rejects invalid command rows and ignores stale or disabled editor operations', () => {
    component.editHook(hooks.hooks[1], 'edit'); component.confirmed = true;
    for (const timeout of [0, 601, 1.5, NaN]) { component.hookDraft!.hooks[1].timeout = timeout; expect(component.canSaveHook()).toBeFalse(); }
    component.hookDraft!.hooks[1].timeout = 10; component.hookDraft!.hooks[1].command = ' '; expect(component.canSaveHook()).toBeFalse();
    component.hookDraft!.hooks[1].command = 'two'; expect(component.canSaveHook()).toBeTrue();
    component.moveHookCommand(-1, 1); component.moveHookCommand(0, -1); component.moveHookCommand(0, 2);
    component.removeHookCommand(9); component.removeHookCommand(NaN); expect(component.hookDraft!.hooks.length).toBe(2);
    component.disabled = true; component.addHookCommand(); component.removeHookCommand(0); component.moveHookCommand(1, -1);
    expect(component.hookDraft!.hooks.map(item => item.command)).toEqual(['one', 'two']);
  });
  it('cancel and busy/restricted state never dispatch changes', () => {
    const dispatch = spyOn(component.dispatch, 'emit'); component.editHook(); component.cancel(); expect(dispatch).not.toHaveBeenCalled();
    component.disabled = true; component.refresh('hooks'); component.editPermissions(); expect(dispatch).not.toHaveBeenCalled();
    component.disabled = false; fixture.componentRef.setInput('hooksMenu', { ...hooks, canManage: false }); fixture.detectChanges();
    component.editHook(); expect(component.hookDraft).toBeNull();
  });
  it('does not silently discard drafts through refresh or unrelated outcomes', () => {
    component.editPermissions(); component.permissionDraft!['bash'] = 'deny';
    const dispatch = spyOn(component.dispatch, 'emit'); component.refresh('hooks'); expect(dispatch).not.toHaveBeenCalled();
    outcome('hooks', true, hooks); expect(component.permissionDraft!['bash']).toBe('deny');
  });
});
