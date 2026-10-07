import { Component, Input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NewChatDialogComponent, ChatSetupCatalog } from './new-chat-dialog.component';
import { CliTerminalConsoleComponent } from '../cli-terminal-console/cli-terminal-console.component';

@Component({selector: 'app-cli-terminal-console', standalone: true, template: '<p>Complete CLI wizard</p>'})
class TerminalStub { @Input() workingDirectory = ''; @Input() visible = true; }

describe('NewChatDialogComponent', () => {
  let fixture: ComponentFixture<NewChatDialogComponent>;
  let http: HttpTestingController;
  let dialog: {close: jasmine.Spy; disableClose: boolean};
  const catalog = (): ChatSetupCatalog => ({available: true,
    defaults: {mode: 'standard', runtime: 'direct', vendor: 'openai', authMethod: 'api-key', authenticationScope: 'session', model: 'model-new', saveScope: 'session'},
    profiles: [{name: 'native', label: 'Native profile', mode: 'passthrough'}],
    runtimes: [{id: 'direct', label: 'Direct'}, {id: 'external-local', label: 'External local'}],
    frameworks: [{id: 'opencode', label: 'OpenCode', available: true}],
    vendors: [{id: 'openai', label: 'OpenAI', authMethods: [{id: 'api-key', label: 'API key'}]}],
    credentials: [{name: 'work', label: 'Work account'}], models: [{id: 'model-new', label: 'Discovered live model'}],
    thinkingOptions: [{value: 'high', label: 'high'}], workflows: [{name: 'review-team', label: 'Review team'}],
    judgeProfiles: [{name: 'strict', label: 'Strict judge', provider: 'openai', model: 'judge-model'}],
    fastModeSupported: true, ultracodeSupported: false});
  beforeEach(async () => {
    dialog = {close: jasmine.createSpy('close'), disableClose: false};
    await TestBed.configureTestingModule({imports: [NewChatDialogComponent, HttpClientTestingModule, NoopAnimationsModule],
      providers: [{provide: MAT_DIALOG_DATA, useValue: {url: '/workspace/projects/p1', workingDirectory: '/project', name: 'Chat 2'}},
        {provide: MatDialogRef, useValue: dialog}]}).overrideComponent(NewChatDialogComponent, {
          remove: {imports: [CliTerminalConsoleComponent]}, add: {imports: [TerminalStub]}
        }).compileComponents();
    fixture = TestBed.createComponent(NewChatDialogComponent); http = TestBed.inject(HttpTestingController);
    fixture.detectChanges(); http.expectOne('/workspace/projects/p1/chat-setup/options').flush(catalog()); fixture.detectChanges();
  });
  afterEach(() => {fixture.destroy(); http.verify();});
  it('renders dynamic models, accounts, thinking and judge defaults in an additive setup form', () => {
    const text = fixture.nativeElement.textContent;
    for (const label of ['Discovered live model', 'Work account', 'high', 'Strict judge', 'Runtime', 'Configuration save scope', 'Ultracode']) expect(text).toContain(label);
    expect(fixture.componentInstance.selection.model).toBe('model-new');
  });
  it('submits every selected browser option and pins a returned chat without closing on validation errors', () => {
    const c = fixture.componentInstance;
    c.selection = {...c.selection, thinking: 'high', credentialName: 'work', apiKey: 'secret', fastMode: true, saveProfile: 'saved', replaceProfile: true};
    c.judges['openai'] = 'strict'; c.create(); c.create();
    const request = http.expectOne('/workspace/projects/p1/chat-setup/create');
    expect(request.request.body.selection).toEqual(jasmine.objectContaining({model: 'model-new', thinking: 'high', credentialName: 'work', apiKey: 'secret', fastMode: true,
      saveProfile: 'saved', replaceProfile: true, judges: [{action: 'activate', provider: 'openai', profile: 'strict'}]}));
    expect(dialog.disableClose).toBeTrue();
    request.flush({message: 'Invalid credential'}, {status: 400, statusText: 'Bad request'});
    expect(dialog.close).not.toHaveBeenCalled(); expect(dialog.disableClose).toBeFalse(); expect(c.error).toBe('Invalid credential');
    c.create(); http.expectOne('/workspace/projects/p1/chat-setup/create').flush({id: 'created', name: 'Chat 2'});
    expect(dialog.close).toHaveBeenCalledWith({chat: {id: 'created', name: 'Chat 2'}}); expect(c.selection.apiKey).toBeUndefined();
  });
  it('never sends typed secrets in discovery and preserves profile capture options across refreshes', () => {
    const c = fixture.componentInstance;
    c.selection.apiKey = 'private'; c.selection.saveProfile = 'keep'; c.selection.replaceProfile = true;
    c.reload(); const first = http.expectOne('/workspace/projects/p1/chat-setup/options');
    expect(first.request.body.selection.apiKey).toBeUndefined(); c.reload();
    expect(first.cancelled).toBeTrue(); const second = http.expectOne('/workspace/projects/p1/chat-setup/options'); second.flush(catalog());
    expect(c.selection.apiKey).toBe('private'); expect(c.selection.saveProfile).toBe('keep'); expect(c.selection.replaceProfile).toBeTrue();
  });
  it('clears incompatible vendor secrets and generation selections before dynamic discovery', () => {
    const c = fixture.componentInstance;
    c.selection.apiKey = 'old-key'; c.selection.thinking = 'high'; c.selection.model = 'old-model'; c.selection.fastMode = true;
    c.selection.vendor = 'new-vendor'; c.change('vendor');
    const request = http.expectOne('/workspace/projects/p1/chat-setup/options');
    expect(request.request.body.selection.apiKey).toBeUndefined(); expect(request.request.body.selection.model).toBeUndefined();
    request.flush({...catalog(), defaults: {...catalog().defaults, vendor: 'new-vendor'}, fastModeSupported: false});
    expect(c.selection.apiKey).toBeUndefined(); expect(c.selection.fastMode).toBeFalse();
  });
  it('exposes the real complete wizard inside the modal for terminal-only setup and all resume modes', () => {
    const c = fixture.componentInstance;
    c.selection.mode = 'resume-all'; c.change('mode'); c.create(); fixture.detectChanges();
    expect(c.fullWizard).toBeTrue(); expect(fixture.nativeElement.textContent).toContain('Hugging Face');
    const terminal = fixture.debugElement.children; expect(terminal).toBeTruthy();
    http.expectNone('/workspace/projects/p1/chat-setup/create'); c.close(); expect(dialog.close).toHaveBeenCalledWith({refresh: true});
  });
  it('keeps native saved profiles on their native mode and discovers overrides', () => {
    const c = fixture.componentInstance; c.selection.profile = 'native'; c.change('profile');
    const request = http.expectOne('/workspace/projects/p1/chat-setup/options'); expect(request.request.body.selection.mode).toBe('passthrough');
    request.flush({...catalog(), defaults: {mode: 'passthrough', passthroughManaged: true, passthroughAgent: 'opencode'}});
    expect(c.nativeMode).toBeTrue(); expect(c.selection.passthroughAgent).toBe('opencode');
  });
});
