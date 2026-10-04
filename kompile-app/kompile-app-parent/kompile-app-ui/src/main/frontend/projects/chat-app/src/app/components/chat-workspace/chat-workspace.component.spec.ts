import { Component, Input, Output, EventEmitter } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterTestingModule } from '@angular/router/testing';
import { By } from '@angular/platform-browser';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ChatWorkspaceComponent, WorkspaceChatPaneComponent, WorkspaceProject } from './chat-workspace.component';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { AgentService } from '@shared/services/agent.service';

@Component({ selector: 'app-unified-chat', standalone: false, template: '' })
class StubChat {
  @Input() workingDirectory?: string;
  @Input() workspaceChat?: { id: string; name: string };
  @Input() viewActive = true;
  @Output() workspaceNewChat = new EventEmitter<void>();
  lifecycleBusy = false;
  refreshWorkspaceTranscript(): void { }
}

describe('Chat workspace', () => {
  let fixture: ComponentFixture<ChatWorkspaceComponent>;
  let http: HttpTestingController;
  let projects: WorkspaceProject[];
  beforeEach(async () => {
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [CommonModule, FormsModule, RouterTestingModule, HttpClientTestingModule, MatButtonModule, MatIconModule],
      declarations: [ChatWorkspaceComponent, WorkspaceChatPaneComponent, StubChat]
    }).compileComponents();
    fixture = TestBed.createComponent(ChatWorkspaceComponent);
    http = TestBed.inject(HttpTestingController);
    projects = [
      { id: 'p1', name: 'One', workingDirectory: '/projects/one', chats: [{ id: 'c1', name: 'First' }] },
      { id: 'p2', name: 'Two', workingDirectory: '/projects/two', chats: [{ id: 'c2', name: 'Second' }] }
    ];
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('/agents/chat/workspace')).flush({ enabled: true, projects });
    fixture.detectChanges();
  });
  afterEach(() => { fixture.destroy(); http.verify(); sessionStorage.clear(); });

  it('keeps both panes mounted with independent transports and directory contexts', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    const first = fixture.debugElement.query(By.directive(WorkspaceChatPaneComponent));
    const transport = first.injector.get(LocalAgentChatService);
    workspace.open(projects[1], projects[1].chats[0]); fixture.detectChanges();
    const panes = fixture.debugElement.queryAll(By.directive(WorkspaceChatPaneComponent));
    expect(panes.length).toBe(2);
    expect(panes[0].componentInstance).toBe(first.componentInstance);
    expect(panes[1].injector.get(LocalAgentChatService)).not.toBe(transport);
    expect(panes[0].injector.get(AgentService)).not.toBe(panes[1].injector.get(AgentService));
    const chats = fixture.debugElement.queryAll(By.directive(StubChat)).map(d => d.componentInstance as StubChat);
    expect(chats.map(c => c.workingDirectory)).toEqual(['/projects/one', '/projects/two']);
    expect(chats.map(c => c.viewActive)).toEqual([false, true]);
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    expect(fixture.debugElement.queryAll(By.directive(WorkspaceChatPaneComponent)).length).toBe(2);
    expect(chats[0].viewActive).toBeTrue();
  });

  it('refreshes an already mounted pane every time it is opened', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    const refresh = jasmine.createSpy('refreshWorkspaceTranscript');
    workspace.panes.first.view = { lifecycleBusy: false, refreshWorkspaceTranscript: refresh } as any;
    workspace.open(projects[0], projects[0].chats[0]);
    workspace.open(projects[0], projects[0].chats[0]);
    expect(refresh).toHaveBeenCalledTimes(2);
    expect(workspace.opened.length).toBe(1);
  });

  it('creates chats in the selected project and persists open selections', () => {
    const workspace = fixture.componentInstance;
    workspace.newChat(projects[1]);
    http.expectOne(r => r.url.endsWith('/workspace/projects/p2/chats')).flush({ id: 'c3', name: 'Chat 2' });
    fixture.detectChanges();
    expect(workspace.activeId).toBe('c3');
    expect(workspace.opened[0].project.workingDirectory).toBe('/projects/two');
    expect(sessionStorage.getItem(`kompile-workspace-open:${workspace.backendUrl}`)).toBe('["c3"]');
  });

  it('registers an in-pane New request with the same folder and keeps its old pane', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[1], projects[1].chats[0]); fixture.detectChanges();
    (fixture.debugElement.query(By.directive(StubChat)).componentInstance as StubChat).workspaceNewChat.emit();
    http.expectOne(r => r.url.endsWith('/workspace/projects/p2/chats')).flush({ id: 'c3', name: 'Chat 2' });
    fixture.detectChanges();
    expect(workspace.activeId).toBe('c3');
    expect(workspace.opened.map(item => item.chat.id)).toEqual(['c2', 'c3']);
    expect(projects[1].chats.map(chat => chat.id)).toEqual(['c2', 'c3']);
  });

  it('creates a project folder then opens its first independent chat', () => {
    const workspace = fixture.componentInstance;
    workspace.projectForm = 'new';
    workspace.parentDirectory = '/projects'; workspace.projectName = ' Three ';
    workspace.createProject();
    const request = http.expectOne(r => r.url.endsWith('/workspace/projects/new'));
    expect(request.request.body).toEqual({ parentDirectory: '/projects', name: 'Three' });
    request.flush({ id: 'p3', name: 'Three', workingDirectory: '/projects/Three', chats: [] });
    const chatRequest = http.expectOne(r => r.url.endsWith('/workspace/projects/p3/chats'));
    expect(chatRequest.request.body.name).toBe('Chat 1');
    chatRequest.flush({ id: 'c3', name: 'Chat 1' });
    fixture.detectChanges();
    expect(workspace.projects.length).toBe(3);
    expect(workspace.activeId).toBe('c3');
    expect(workspace.opened[0].project.workingDirectory).toBe('/projects/Three');
    expect(workspace.projectName).toBe('');
    expect(workspace.saving).toBeFalse();
  });

  it('keeps the creation form and error after a rejected folder without creating a chat', () => {
    const workspace = fixture.componentInstance;
    workspace.projectForm = 'new'; workspace.parentDirectory = '/projects'; workspace.projectName = 'existing';
    workspace.createProject(); workspace.createProject();
    http.expectOne(r => r.url.endsWith('/workspace/projects/new')).flush({ message: 'Folder already exists' },
      { status: 400, statusText: 'Bad request' });
    expect(workspace.error).toBe('Folder already exists');
    expect(workspace.projectName).toBe('existing');
    expect(workspace.saving).toBeFalse();
    expect(workspace.projects.length).toBe(2);
  });

  it('adds existing folders and opens existing native chats without creating copies', () => {
    const workspace = fixture.componentInstance;
    workspace.directory = '/projects/three';
    workspace.addProject();
    http.expectOne(r => r.url.endsWith('/workspace/projects')).flush({
      id: 'p3', name: 'Three', workingDirectory: '/projects/three', chats: [{ id: 'native-session', name: 'Existing' }]
    });
    expect(workspace.activeId).toBe('native-session');
    expect(workspace.opened[0].project.id).toBe('p3');
  });

  it('keeps re-added aliases on the same folder object when creating subsequent chats', () => {
    const workspace = fixture.componentInstance;
    workspace.directory = '/alias/one';
    workspace.addProject();
    http.expectOne(r => r.url.endsWith('/workspace/projects')).flush({ ...projects[0], chats: [] });
    http.expectOne(r => r.url.endsWith('/workspace/projects/p1/chats')).flush({ id: 'fresh', name: 'Chat 1' });
    expect(workspace.projects.length).toBe(2);
    expect(workspace.opened[0].project).toBe(projects[0]);
    expect(workspace.projects[0].chats[0].id).toBe('fresh');
  });

  it('opens a first chat in the launch folder so normal web entry is ready to use', () => {
    const extra = TestBed.createComponent(ChatWorkspaceComponent);
    extra.detectChanges();
    http.expectOne(r => r.url.endsWith('/agents/chat/workspace')).flush({
      enabled: true, workingDirectory: '/projects/one', projects: [{ ...projects[0], chats: [] }]
    });
    http.expectOne(r => r.url.endsWith('/workspace/projects/p1/chats')).flush({ id: 'first', name: 'Chat 1' });
    expect(extra.componentInstance.activeId).toBe('first');
    expect(extra.componentInstance.parentDirectory).toBe('/projects/one');
    extra.destroy();
  });

  it('uses ordinary chat on hosted or single launches without exposing folder mutations', () => {
    const extra = TestBed.createComponent(ChatWorkspaceComponent);
    extra.detectChanges();
    http.expectOne(r => r.url.endsWith('/agents/chat/workspace')).flush({ enabled: false, projects: [] });
    extra.detectChanges();
    expect(extra.debugElement.query(By.directive(StubChat))).not.toBeNull();
    expect(extra.debugElement.query(By.css('.workspace'))).toBeNull();
    extra.componentInstance.parentDirectory = '/projects'; extra.componentInstance.projectName = 'test';
    extra.componentInstance.createProject();
    extra.destroy();
  });

  it('does not close a busy pane and closing an idle pane keeps its registry chat', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    workspace.panes.first.view = { lifecycleBusy: true } as any;
    workspace.close('c1'); expect(workspace.opened.length).toBe(1);
    workspace.panes.first.view = { lifecycleBusy: false } as any;
    workspace.close('c1'); expect(workspace.opened.length).toBe(0);
    expect(workspace.projects[0].chats.length).toBe(1);
  });
});
