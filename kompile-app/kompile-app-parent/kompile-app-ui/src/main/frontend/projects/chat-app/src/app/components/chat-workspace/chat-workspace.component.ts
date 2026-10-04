import { ChangeDetectorRef, Component, Input, Output, EventEmitter, OnDestroy, OnInit, QueryList, ViewChild, ViewChildren } from '@angular/core';
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
interface WorkspaceView { enabled: boolean; workingDirectory?: string; projects: WorkspaceProject[]; }
interface OpenChat { project: WorkspaceProject; chat: WorkspaceChat; }

/** Each mounted pane owns its transport/state; hiding it never cancels its run. */
@Component({
  selector: 'app-workspace-chat-pane', standalone: false,
  providers: [LocalAgentChatService, AgentService, FolderService, ConversationalRagService],
  template: `<app-unified-chat [workingDirectory]="project.workingDirectory"
    [workspaceChat]="chat" [viewActive]="active" (workspaceNewChat)="newChatRequested.emit()"></app-unified-chat>`,
  styles: [':host { display:block; height:100%; min-height:0; }']
})
export class WorkspaceChatPaneComponent {
  @Input() project!: WorkspaceProject;
  @Input() chat!: WorkspaceChat;
  @Input() active = true;
  @Output() newChatRequested = new EventEmitter<void>();
  @ViewChild(UnifiedChatComponent) view?: UnifiedChatComponent;
  get busy(): boolean { return !!this.view?.lifecycleBusy; }
}

@Component({
  selector: 'app-chat-workspace', standalone: false,
  template: `
    <p *ngIf="loading" role="status">Loading chats…</p>
    <p *ngIf="!loading && !enabled && error" role="alert">{{ error }}</p>
    <!-- Hosted/single-folder launches keep their original chat without granting folder management. -->
    <app-unified-chat *ngIf="!loading && !enabled && !error"></app-unified-chat>
    <div class="workspace" *ngIf="enabled">
      <aside>
        <h2>Chats by folder</h2><a routerLink="/single-chat">Single chat</a>
        <p>Local project folders on the host running Kompile.</p>
        <div class="folder-actions">
          <button mat-stroked-button type="button" (click)="projectForm = projectForm === 'existing' ? '' : 'existing'" [disabled]="saving"
            [attr.aria-expanded]="projectForm === 'existing'" title="Add a folder that already exists on the host running Kompile">Add existing folder</button>
          <button mat-stroked-button type="button" (click)="projectForm = projectForm === 'new' ? '' : 'new'" [disabled]="saving"
            [attr.aria-expanded]="projectForm === 'new'" title="Create a new project folder on the host running Kompile">New project</button>
        </div>
        <form *ngIf="projectForm === 'existing'" (ngSubmit)="addProject()">
          <label for="workspace-directory">Existing folder</label>
          <input id="workspace-directory" name="directory" [(ngModel)]="directory" placeholder="/absolute/project/path" required>
          <button mat-flat-button color="primary" type="submit" [disabled]="saving || !directory.trim()">Add folder</button>
        </form>
        <form *ngIf="projectForm === 'new'" (ngSubmit)="createProject()">
          <label for="workspace-parent">Existing parent folder</label>
          <input id="workspace-parent" name="parentDirectory" [(ngModel)]="parentDirectory" placeholder="/absolute/parent/path" required>
          <label for="workspace-name">New project folder name</label>
          <input id="workspace-name" name="projectName" [(ngModel)]="projectName" placeholder="my-project" maxlength="128" required>
          <small>Creates a new folder without changing existing files. Chat uses this folder's CLI defaults.</small>
          <button mat-flat-button color="primary" type="submit" [disabled]="saving || !parentDirectory.trim() || !projectName.trim()">Create project</button>
        </form>
        <p role="alert" *ngIf="error">{{ error }}</p>
        <section *ngFor="let project of projects">
          <h3 [title]="project.workingDirectory">{{ project.name }}</h3>
          <small>{{ project.workingDirectory }}</small>
          <button mat-stroked-button type="button" (click)="newChat(project)" [disabled]="saving"
            title="Start another chat in this folder; open chats keep running"><mat-icon>add</mat-icon> New chat</button>
          <button mat-button type="button" class="chat" *ngFor="let chat of project.chats" (click)="open(project, chat)"
            [class.selected]="activeId === chat.id" [attr.aria-pressed]="activeId === chat.id"
            title="Open this chat; other open chats keep running">
            {{ chat.name }} <span *ngIf="busy(chat.id)">● Running</span>
          </button>
        </section>
      </aside>
      <main>
        <nav aria-label="Open chats">
          <span *ngFor="let item of opened">
            <button mat-stroked-button type="button" (click)="open(item.project, item.chat)" [class.selected]="activeId === item.chat.id"
              [title]="item.project.workingDirectory">
              {{ item.project.name }} / {{ item.chat.name }} {{ busy(item.chat.id) ? '●' : '' }}
            </button>
            <!-- disabledInteractive keeps the reason visible on a running chat; close() re-checks busy(). -->
            <button mat-icon-button type="button" class="close-chat" disabledInteractive (click)="close(item.chat.id)"
              [disabled]="busy(item.chat.id)" aria-label="Close chat"
              [title]="busy(item.chat.id) ? 'Stop the running chat before closing it' : 'Close this chat; it stays listed under its folder'"><mat-icon>close</mat-icon></button>
          </span>
        </nav>
        <p *ngIf="!opened.length" class="empty">Select a project chat or create one. Other open chats keep running when you switch.</p>
        <app-workspace-chat-pane *ngFor="let item of opened; trackBy: trackChat"
          [style.display]="activeId === item.chat.id ? 'block' : 'none'"
          [project]="item.project" [chat]="item.chat" [active]="activeId === item.chat.id"
          (newChatRequested)="newChat(item.project)">
        </app-workspace-chat-pane>
      </main>
    </div>`,
  styles: [`
    :host {
      display:block; height:100%; min-height:0;
      --mdc-text-button-container-height: 32px;
      --mdc-outlined-button-container-height: 32px;
      --mdc-filled-button-container-height: 32px;
      --mat-text-button-touch-target-display: none;
      --mat-outlined-button-touch-target-display: none;
      --mat-filled-button-touch-target-display: none;
      --mat-icon-button-touch-target-display: none;
    }
    /* Native inputs and scrollbars follow the page theme. */
    :host-context(body.dark-theme) { color-scheme: dark; }
    .workspace { display:flex; height:100%; min-height:0; }
    :host > app-unified-chat { display:block; height:100%; }
    aside { width:260px; flex-shrink:0; overflow:auto; padding:16px; border-right:1px solid var(--border-color, #dee2e6); }
    aside a { color: var(--color-primary, #1976d2); }
    main { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; }
    app-workspace-chat-pane { flex:1; min-height:0; }
    nav { display:flex; flex-wrap:wrap; gap:8px; padding:8px; border-bottom:1px solid var(--border-color, #dee2e6); }
    nav span { display:inline-flex; align-items:center; gap:2px; }
    section { margin-top:20px; } h3 { margin-bottom:4px; }
    small { display:block; overflow-wrap:anywhere; margin-bottom:8px; color: var(--text-secondary, #6c757d); }
    .folder-actions { display:flex; flex-wrap:wrap; gap:8px; }
    label, input { display:block; }
    input { box-sizing:border-box; width:100%; margin:8px 0; padding:6px 8px; font:inherit;
      border:1px solid var(--border-color, #dee2e6); border-radius:6px;
      background: var(--bg-body, #f8f9fa); color: var(--text-primary, #212529); }
    input:focus { outline:none; border-color: var(--color-primary, #1976d2); }
    .chat.mat-mdc-button { display:flex; width:100%; justify-content:flex-start; margin:2px 0; }
    .selected.mat-mdc-button-base {
      background: var(--color-primary-light, rgba(25, 118, 210, 0.1));
      --mdc-text-button-label-text-color: var(--color-primary, #1976d2);
      --mdc-outlined-button-label-text-color: var(--color-primary, #1976d2);
      --mdc-outlined-button-outline-color: var(--color-primary, #1976d2);
    }
    .close-chat.mat-mdc-icon-button.mat-mdc-button-base {
      --mdc-icon-button-state-layer-size: 28px; --mdc-icon-button-icon-size: 18px; padding: 5px;
    }
    .close-chat .mat-icon { width: 18px; height: 18px; font-size: 18px; line-height: 18px; }
    .empty { padding:24px; color: var(--text-secondary, #6c757d); }
    [role=alert] { color: var(--status-error-text, #c62828); overflow-wrap:anywhere; }
    @media(max-width:700px) { aside { width:170px; padding:8px; } }
  `]
})
export class ChatWorkspaceComponent extends BaseService implements OnInit, OnDestroy {
  @ViewChildren(WorkspaceChatPaneComponent) panes!: QueryList<WorkspaceChatPaneComponent>;
  projects: WorkspaceProject[] = [];
  opened: OpenChat[] = [];
  activeId = '';
  directory = '';
  parentDirectory = '';
  projectName = '';
  projectForm: '' | 'existing' | 'new' = '';
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
        this.parentDirectory = view.workingDirectory || '';
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
        if (this.enabled && !this.opened.length) {
          const project = this.projects.find(p => p.workingDirectory === view.workingDirectory);
          if (project) {
            if (project.chats.length) this.open(project, project.chats[0]);
            else this.newChat(project);
          }
        }
      }, error: err => { this.loading = false; this.fail(err); }
    }));
  }
  ngOnDestroy(): void { this.subscriptions.unsubscribe(); clearInterval(this.statusTimer); }
  addProject(): void {
    if (!this.enabled || this.saving || !this.directory.trim()) return;
    this.saving = true; this.error = '';
    this.subscriptions.add(this.http.post<WorkspaceProject>(`${this.url}/projects`, { workingDirectory: this.directory.trim() }).subscribe({
      next: project => {
        const existing = this.projects.find(p => p.id === project.id);
        if (existing) {
          existing.chats = project.chats;
          project = existing; // Keep existing panes and the folder list on the same project object.
        } else this.projects = [...this.projects, project];
        this.directory = ''; this.projectForm = ''; this.saving = false;
        if (project.chats.length) this.open(project, project.chats[0]);
        else this.newChat(project);
      }, error: err => { this.saving = false; this.fail(err); }
    }));
  }
  createProject(): void {
    if (!this.enabled || this.saving || !this.parentDirectory.trim() || !this.projectName.trim()) return;
    this.saving = true; this.error = '';
    this.subscriptions.add(this.http.post<WorkspaceProject>(`${this.url}/projects/new`, {
      parentDirectory: this.parentDirectory.trim(), name: this.projectName.trim()
    }).subscribe({
      next: project => {
        this.projects = [...this.projects, project];
        this.projectName = ''; this.projectForm = ''; this.saving = false;
        this.newChat(project);
      }, error: err => { this.saving = false; this.fail(err); }
    }));
  }
  newChat(project: WorkspaceProject): void {
    if (!this.enabled || this.saving) return;
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
    } else {
      // Re-open an idle pane from persisted CLI history, not its stale browser cache.
      this.panes?.find(pane => pane.chat.id === chat.id)?.view?.refreshWorkspaceTranscript();
    }
    this.activeId = chat.id; this.saveOpen();
  }
  close(id: string): void {
    if (this.busy(id)) return;
    this.opened = this.opened.filter(item => item.chat.id !== id);
    if (this.activeId === id) {
      const next = this.opened[0];
      this.activeId = '';
      if (next) this.open(next.project, next.chat);
    }
    this.saveOpen();
  }
  busy(id: string): boolean { return !!this.panes?.find(p => p.chat.id === id)?.busy; }
  trackChat(_: number, item: OpenChat): string { return item.chat.id; }
  private saveOpen(): void { sessionStorage.setItem(this.openKey, JSON.stringify(this.opened.map(item => item.chat.id))); }
  private fail(err: any): void { this.error = err?.error?.message || err?.message || 'Workspace request failed'; }
}
