import { ChangeDetectorRef, Component, Input, OnDestroy, OnInit, QueryList, ViewChild, ViewChildren } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BaseService } from '@shared/services/base.service';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { AgentService } from '@shared/services/agent.service';
import { FolderService } from '@shared/services/folder.service';
import { ConversationalRagService } from '@shared/services/conversational-rag.service';
import { UnifiedChatComponent } from '../unified-chat/unified-chat.component';
import { Subscription } from 'rxjs';

export interface WorkspaceChat { id: string; name: string; }
export interface WorkspaceProject { id: string; name: string; workingDirectory: string; chats: WorkspaceChat[]; }
interface WorkspaceView { enabled: boolean; projects: WorkspaceProject[]; }
interface OpenChat { project: WorkspaceProject; chat: WorkspaceChat; }

/** Each mounted pane owns its transport/state; hiding it never cancels its run. */
@Component({
  selector: 'app-workspace-chat-pane', standalone: false,
  providers: [LocalAgentChatService, AgentService, FolderService, ConversationalRagService],
  template: `<app-unified-chat [workingDirectory]="project.workingDirectory"
    [workspaceChat]="chat" [viewActive]="active"></app-unified-chat>`,
  styles: [':host { display:block; height:100%; min-height:0; }']
})
export class WorkspaceChatPaneComponent {
  @Input() project!: WorkspaceProject;
  @Input() chat!: WorkspaceChat;
  @Input() active = true;
  @ViewChild(UnifiedChatComponent) view?: UnifiedChatComponent;
  get busy(): boolean { return !!this.view?.lifecycleBusy; }
}

@Component({
  selector: 'app-chat-workspace', standalone: false,
  template: `
    <div class="workspace">
      <aside>
        <h2>Projects</h2><a routerLink="/chat">Single chat</a>
        <p *ngIf="!loading && !enabled">Launch <code>kompile chat --web --workspace</code> to manage multiple CLI projects.</p>
        <form *ngIf="enabled" (ngSubmit)="addProject()">
          <label for="workspace-directory">Project directory on this host</label>
          <input id="workspace-directory" name="directory" [(ngModel)]="directory" placeholder="/absolute/project/path" required>
          <button type="submit" [disabled]="saving || !directory.trim()">Add project</button>
        </form>
        <p *ngIf="loading">Loading workspace…</p>
        <p role="alert" *ngIf="error">{{ error }}</p>
        <section *ngFor="let project of projects">
          <h3 [title]="project.workingDirectory">{{ project.name }}</h3>
          <small>{{ project.workingDirectory }}</small>
          <button (click)="newChat(project)" [disabled]="saving">New chat</button>
          <button class="chat" *ngFor="let chat of project.chats" (click)="open(project, chat)"
            [class.selected]="activeId === chat.id" [attr.aria-pressed]="activeId === chat.id">
            {{ chat.name }} <span *ngIf="busy(chat.id)">● Running</span>
          </button>
        </section>
      </aside>
      <main>
        <nav aria-label="Open chats">
          <span *ngFor="let item of opened">
            <button (click)="activeId = item.chat.id" [class.selected]="activeId === item.chat.id">
              {{ item.project.name }} / {{ item.chat.name }} {{ busy(item.chat.id) ? '●' : '' }}
            </button>
            <button (click)="close(item.chat.id)" [disabled]="busy(item.chat.id)" aria-label="Close chat" title="Stop a running chat before closing">×</button>
          </span>
        </nav>
        <p *ngIf="!opened.length" class="empty">Select a project chat or create one. Other open chats keep running when you switch.</p>
        <app-workspace-chat-pane *ngFor="let item of opened; trackBy: trackChat"
          [style.display]="activeId === item.chat.id ? 'block' : 'none'"
          [project]="item.project" [chat]="item.chat" [active]="activeId === item.chat.id">
        </app-workspace-chat-pane>
      </main>
    </div>`,
  styles: [`
    :host { display:block; height:100%; min-height:0; }
    .workspace { display:flex; height:100%; min-height:0; }
    aside { width:260px; flex-shrink:0; overflow:auto; padding:16px; border-right:1px solid #8884; }
    main { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; }
    app-workspace-chat-pane { flex:1; min-height:0; }
    nav { display:flex; flex-wrap:wrap; gap:8px; padding:8px; border-bottom:1px solid #8884; }
    section { margin-top:20px; } h3 { margin-bottom:4px; }
    small { display:block; overflow-wrap:anywhere; margin-bottom:8px; }
    label, input, .chat { display:block; } input { width:95%; margin:8px 0; }
    button { cursor:pointer; padding:6px; } .chat { width:100%; text-align:left; margin:4px 0; }
    .selected { outline:2px solid #708de5; } .empty { padding:24px; }
    [role=alert] { color:#d44; overflow-wrap:anywhere; }
    @media(max-width:700px) { aside { width:170px; padding:8px; } }
  `]
})
export class ChatWorkspaceComponent extends BaseService implements OnInit, OnDestroy {
  @ViewChildren(WorkspaceChatPaneComponent) panes!: QueryList<WorkspaceChatPaneComponent>;
  projects: WorkspaceProject[] = [];
  opened: OpenChat[] = [];
  activeId = '';
  directory = '';
  enabled = false;
  loading = true;
  saving = false;
  error = '';
  private readonly subscriptions = new Subscription();
  private readonly url = `${this.backendUrl}/agents/chat/workspace`;
  private readonly openKey = `kompile-workspace-open:${this.backendUrl}`;
  // The child views throttle stream rendering outside Angular; poll only status badges.
  private statusTimer?: ReturnType<typeof setInterval>;
  constructor(private http: HttpClient, private cdr: ChangeDetectorRef) { super(); }
  ngOnInit(): void {
    this.statusTimer = setInterval(() => this.cdr.markForCheck(), 1000);
    this.subscriptions.add(this.http.get<WorkspaceView>(this.url).subscribe({
      next: view => {
        this.enabled = view.enabled;
        this.projects = view.projects;
        this.loading = false;
        try {
          const ids: unknown = JSON.parse(sessionStorage.getItem(this.openKey) || '[]');
          if (Array.isArray(ids)) for (const id of ids) {
            for (const project of this.projects) {
              const chat = project.chats.find(c => c.id === id);
              if (chat) this.open(project, chat);
            }
          }
        } catch { sessionStorage.removeItem(this.openKey); }
      }, error: err => { this.loading = false; this.fail(err); }
    }));
  }
  ngOnDestroy(): void { this.subscriptions.unsubscribe(); clearInterval(this.statusTimer); }
  addProject(): void {
    if (this.saving || !this.directory.trim()) return;
    this.saving = true; this.error = '';
    this.subscriptions.add(this.http.post<WorkspaceProject>(`${this.url}/projects`, { workingDirectory: this.directory.trim() }).subscribe({
      next: project => {
        if (!this.projects.some(p => p.id === project.id)) this.projects = [...this.projects, project];
        this.directory = ''; this.saving = false;
      }, error: err => { this.saving = false; this.fail(err); }
    }));
  }
  newChat(project: WorkspaceProject): void {
    if (this.saving) return;
    this.saving = true; this.error = '';
    this.subscriptions.add(this.http.post<WorkspaceChat>(`${this.url}/projects/${project.id}/chats`, { name: `Chat ${project.chats.length + 1}` }).subscribe({
      next: chat => { project.chats = [...project.chats, chat]; this.saving = false; this.open(project, chat); },
      error: err => { this.saving = false; this.fail(err); }
    }));
  }
  open(project: WorkspaceProject, chat: WorkspaceChat): void {
    if (!this.opened.some(item => item.chat.id === chat.id)) {
      if (this.opened.length >= 8) { this.error = 'Close an idle chat before opening another (8 open panes maximum).'; return; }
      this.opened = [...this.opened, { project, chat }];
    }
    this.activeId = chat.id; this.saveOpen();
  }
  close(id: string): void {
    if (this.busy(id)) return;
    this.opened = this.opened.filter(item => item.chat.id !== id);
    if (this.activeId === id) this.activeId = this.opened[0]?.chat.id || '';
    this.saveOpen();
  }
  busy(id: string): boolean { return !!this.panes?.find(p => p.chat.id === id)?.busy; }
  trackChat(_: number, item: OpenChat): string { return item.chat.id; }
  private saveOpen(): void { sessionStorage.setItem(this.openKey, JSON.stringify(this.opened.map(item => item.chat.id))); }
  private fail(err: any): void { this.error = err?.error?.message || err?.message || 'Workspace request failed'; }
}
