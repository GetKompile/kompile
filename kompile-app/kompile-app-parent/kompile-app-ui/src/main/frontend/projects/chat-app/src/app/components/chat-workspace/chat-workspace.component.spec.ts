import { Component, Input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterTestingModule } from '@angular/router/testing';
import { By } from '@angular/platform-browser';
import { ChatWorkspaceComponent, WorkspaceChatPaneComponent, WorkspaceProject } from './chat-workspace.component';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { AgentService } from '@shared/services/agent.service';

@Component({ selector: 'app-unified-chat', standalone: false, template: '' })
class StubChat {
  @Input() workingDirectory?: string;
  @Input() workspaceChat?: { id: string; name: string };
  @Input() viewActive = true;
  lifecycleBusy = false;
}

describe('Chat workspace', () => {
  let fixture: ComponentFixture<ChatWorkspaceComponent>;
  let http: HttpTestingController;
  let projects: WorkspaceProject[];
  beforeEach(async () => {
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [CommonModule, FormsModule, RouterTestingModule, HttpClientTestingModule],
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

  it('creates chats in the selected project and persists open selections', () => {
    const workspace = fixture.componentInstance;
    workspace.newChat(projects[1]);
    http.expectOne(r => r.url.endsWith('/workspace/projects/p2/chats')).flush({ id: 'c3', name: 'Chat 2' });
    fixture.detectChanges();
    expect(workspace.activeId).toBe('c3');
    expect(workspace.opened[0].project.workingDirectory).toBe('/projects/two');
    expect(sessionStorage.getItem(`kompile-workspace-open:${workspace.backendUrl}`)).toBe('["c3"]');
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
