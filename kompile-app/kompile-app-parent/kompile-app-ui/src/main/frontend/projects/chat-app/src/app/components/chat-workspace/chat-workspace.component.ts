import { ChangeDetectorRef, Component, ElementRef, Input, Output, EventEmitter, OnDestroy, OnInit, QueryList, ViewChild, ViewChildren } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BaseService } from '@shared/services/base.service';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { AgentService } from '@shared/services/agent.service';
import { FolderService } from '@shared/services/folder.service';
import { ConversationalRagService } from '@shared/services/conversational-rag.service';
import { UnifiedChatComponent } from '../unified-chat/unified-chat.component';
import { ChatActivity, IDLE_CHAT_ACTIVITY } from '../chat-activity-indicator/chat-activity-indicator.component';
import { Subscription } from 'rxjs';

export interface WorkspaceChat { id: string; name: string; framework?: string | null; model?: string | null; nativeSource?: string | null; }
interface NativeChat { sessionId: string; title: string; workingDirectory?: string | null; }
interface NativeSource { source: string; name: string; }
interface NativeFolder extends NativeSource { chats: NativeChat[]; error?: string | null; loading: boolean; }
interface NativeFramework { id: string; displayName: string; available: boolean; }
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
  get activity(): ChatActivity { return this.view?.activityIndicator || IDLE_CHAT_ACTIVITY; }
}

@Component({
  selector: 'app-chat-workspace', standalone: false,
  template: `
    <p *ngIf="loading" role="status">Loading chats…</p>
    <p *ngIf="!loading && !enabled && error" role="alert">{{ error }}</p>
    <!-- Hosted/single-folder launches keep their original chat without granting folder management. -->
    <app-unified-chat *ngIf="!loading && !enabled && !error"></app-unified-chat>
    <div class="workspace" *ngIf="enabled" (keydown.escape)="closeFolders()">
      <div class="mobile-folder-toolbar">
        <button #folderToggle mat-stroked-button type="button" (click)="foldersOpen = !foldersOpen"
          aria-controls="workspace-folders" [attr.aria-expanded]="foldersOpen">
          <mat-icon>folder</mat-icon> Chats & vendors
        </button>
      </div>
      <button type="button" class="folder-backdrop" [class.visible]="foldersOpen"
        tabindex="-1" aria-label="Close chat folders" (click)="closeFolders()"></button>
      <aside id="workspace-folders" [class.mobile-open]="foldersOpen" aria-label="Project chats by vendor">
        <button mat-icon-button type="button" class="close-folders" aria-label="Close chat folders"
          (click)="closeFolders()"><mat-icon>close</mat-icon></button>
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
        <div class="chat-search">
          <label for="workspace-chat-search">Search chat titles</label>
          <input id="workspace-chat-search" type="search" [(ngModel)]="titleSearch" placeholder="Filter chats by title…">
          <button mat-button type="button" *ngIf="titleSearch" (click)="titleSearch = ''">Clear search</button>
        </div>
        <section *ngFor="let project of projects; trackBy: trackProject">
          <h3 [title]="project.workingDirectory">{{ project.name }}</h3>
          <small>{{ project.workingDirectory }}</small>
          <button mat-stroked-button type="button" (click)="beginChat(project)" [disabled]="saving"
            title="Choose a chat framework; open chats keep running"><mat-icon>add</mat-icon> New chat</button>
          <form *ngIf="newChatProject === project.id" (ngSubmit)="newChat(project, newFramework || undefined, newModel.trim() || undefined)">
            <label [for]="'framework-' + project.id">Chat framework</label>
            <select [id]="'framework-' + project.id" name="framework" [(ngModel)]="newFramework">
              <option value="">Folder default</option><option value="standard">Kompile standard (configured vendor)</option>
              <option *ngFor="let framework of frameworks" [value]="framework.id" [disabled]="!framework.available">
                {{ framework.displayName }}{{ framework.available ? '' : ' (not installed)' }}
              </option>
            </select>
            <label [for]="'model-' + project.id">Model (optional)</label>
            <input [id]="'model-' + project.id" name="model" [(ngModel)]="newModel" maxlength="256" placeholder="Default, or e.g. zai/glm-5 for OpenCode">
            <small>Native frameworks use their own vendor login. The framework is pinned to this chat; other chats are unchanged.</small>
            <button mat-button type="submit" [disabled]="saving">Create chat</button>
            <button mat-button type="button" (click)="newChatProject = ''" [disabled]="saving">Cancel</button>
          </form>
          <details class="vendor-folder kompile-folder" [open]="!vendorCollapsed(project.id, 'kompile')"
            (toggle)="rememberVendorCollapse(project.id, 'kompile', $event)">
            <summary>Kompile ({{ workspaceChats(project).length }}{{ titleSearch.trim() ? ' / ' + workspaceChatCount(project) : '' }})</summary>
          <ng-container *ngFor="let chat of workspaceChats(project); trackBy: trackWorkspaceChat">
          <div class="chat-row">
            <button mat-button type="button" class="chat" (click)="open(project, chat)"
              [class.selected]="activeId === chat.id" [attr.aria-pressed]="activeId === chat.id"
              [title]="chat.name"><span class="chat-title">{{ chat.name }}</span> <small *ngIf="chat.framework">{{ chat.framework }}{{ chat.model ? ' / ' + chat.model : '' }}</small> <app-chat-activity-indicator [activity]="activity(chat.id)"></app-chat-activity-indicator></button>
            <button mat-icon-button type="button" (click)="startRename(chat)" [disabled]="saving"
              aria-label="Rename chat" title="Rename chat"><mat-icon>edit</mat-icon></button>
            <form class="rename-chat" *ngIf="editingId === chat.id" (ngSubmit)="rename(project, chat)">
              <label [for]="'chat-title-' + chat.id">Chat title</label>
              <textarea [id]="'chat-title-' + chat.id" name="title" [(ngModel)]="editingTitle" required></textarea>
              <button mat-button type="submit" [disabled]="saving || !editingTitle.trim()">Save</button>
              <button mat-button type="button" (click)="editingId = ''" [disabled]="saving">Cancel</button>
            </form>
          </div>
          </ng-container>
            <small *ngIf="!workspaceChats(project).length" role="status">{{ titleSearch.trim() ? 'No matching chat titles.' : 'No Kompile chats for this project.' }}</small>
          </details>
          <button mat-stroked-button type="button" (click)="refreshNativeFolders(project)" [disabled]="nativeLoading || saving">Refresh vendor chats</button>
          <p *ngIf="nativeLoading" role="status">Loading vendor folders…</p>
          <p *ngIf="nativeError" role="alert">{{ nativeError }}</p>
          <details *ngFor="let folder of nativeFolders[project.id]; trackBy: trackVendor" class="vendor-folder native-folder"
            [open]="!vendorCollapsed(project.id, folder.source)" (toggle)="rememberVendorCollapse(project.id, folder.source, $event)"
            [attr.aria-busy]="folder.loading">
            <summary>{{ folder.name }} {{ folder.loading ? '— Loading chats…' : '(' + nativeChats(folder).length + (titleSearch.trim() ? ' / ' + folder.chats.length : '') + ')' }}</summary>
            <p *ngIf="folder.loading" role="status">Loading {{ folder.name }} chats for this project…</p>
            <p *ngIf="folder.error" role="alert">{{ folder.error }}</p>
            <small *ngIf="!folder.loading && !nativeChats(folder).length && !folder.error" role="status">{{ titleSearch.trim() ? 'No matching chat titles.' : 'No chats for this project.' }}</small>
            <button mat-button type="button" class="chat" *ngFor="let chat of nativeChats(folder); trackBy: trackNativeChat"
              [disabled]="saving" (click)="openNative(folder, chat)" [title]="chat.title || chat.sessionId">
              {{ chat.title || chat.sessionId }}
            </button>
          </details>
        </section>
      </aside>
      <main>
        <nav aria-label="Open chats">
          <span *ngFor="let item of opened">
            <button mat-stroked-button type="button" (click)="open(item.project, item.chat)" [class.selected]="activeId === item.chat.id"
              [title]="item.project.workingDirectory">
              {{ item.project.name }} / {{ item.chat.name }}
              <app-chat-activity-indicator [activity]="activity(item.chat.id)"></app-chat-activity-indicator>
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
          (newChatRequested)="beginChat(item.project)">
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
    .workspace { position:relative; display:flex; height:100%; min-height:0; }
    .mobile-folder-toolbar, .folder-backdrop, .close-folders { display:none; }
    :host > app-unified-chat { display:block; height:100%; }
    aside { width:260px; flex-shrink:0; overflow:auto; padding:16px; border-right:1px solid var(--border-color, #dee2e6); }
    aside a { color: var(--color-primary, #1976d2); }
    main { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; }
    app-workspace-chat-pane { flex:1; min-height:0; }
    nav { display:flex; flex-shrink:0; flex-wrap:wrap; gap:8px; padding:8px; border-bottom:1px solid var(--border-color, #dee2e6); }
    nav span { display:inline-flex; align-items:center; gap:2px; min-width:0; max-width:100%; }
    nav button.mat-mdc-outlined-button { height:auto; min-height:32px; padding:8px; white-space:normal; overflow-wrap:anywhere; min-width:0; }
    section { margin-top:20px; } h3 { margin-bottom:4px; }
    small { display:block; overflow-wrap:anywhere; margin-bottom:8px; color: var(--text-secondary, #6c757d); }
    .folder-actions { display:flex; flex-wrap:wrap; gap:8px; }
    .chat-search { margin-top:16px; }
    .vendor-folder { margin:12px 0; border-bottom:1px solid var(--border-color, #dee2e6); padding-bottom:8px; }
    .vendor-folder > summary { cursor:pointer; font-weight:600; padding:8px 0; overflow-wrap:anywhere; }
    .vendor-folder > summary:focus-visible { outline:2px solid var(--color-primary, #1976d2); outline-offset:2px; }
    label, input { display:block; }
    input { box-sizing:border-box; width:100%; margin:8px 0; padding:6px 8px; font:inherit;
      border:1px solid var(--border-color, #dee2e6); border-radius:6px;
      background: var(--bg-body, #f8f9fa); color: var(--text-primary, #212529); }
    input:focus { outline:none; border-color: var(--color-primary, #1976d2); }
    .chat-row { display:flex; flex-wrap:wrap; align-items:flex-start; }
    .chat.mat-mdc-button { display:flex; flex:1; min-width:0; height:auto; min-height:32px; padding:8px; text-align:left; white-space:normal; overflow-wrap:anywhere; justify-content:flex-start; margin:2px 0; }
    .rename-chat { width:100%; }
    textarea { box-sizing:border-box; width:100%; min-height:64px; font:inherit; background:var(--bg-body); color:var(--text-primary); }
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
    @media(max-width:768px) {
      .workspace { flex-direction:column; }
      .mobile-folder-toolbar { display:flex; flex-shrink:0; padding:4px 8px; }
      aside { display:none; position:absolute; top:52px; bottom:0; left:0; z-index:20;
        box-sizing:border-box; width:min(90%, 320px); padding:12px;
        background:var(--bg-surface, #fff); overscroll-behavior:contain; }
      aside.mobile-open { display:block; }
      .close-folders { display:block; float:right; }
      .folder-backdrop.visible { display:block; position:absolute; inset:52px 0 0; z-index:19;
        border:0; background:rgba(0, 0, 0, .35); }
      nav { flex-wrap:nowrap; overflow-x:auto; overscroll-behavior-inline:contain; }
      nav span { flex:0 0 auto; max-width:85%; }
      nav button.mat-mdc-outlined-button { white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
      .mat-mdc-button-base, .chat.mat-mdc-button, nav button.mat-mdc-outlined-button { min-height:44px; }
      nav span > button.mat-mdc-outlined-button { flex:1; }
      .close-chat.mat-mdc-icon-button.mat-mdc-button-base, .close-folders.mat-mdc-icon-button.mat-mdc-button-base {
        --mdc-icon-button-state-layer-size:44px; width:44px; height:44px; flex-shrink:0; padding:12px;
      }
      .vendor-folder > summary { box-sizing:border-box; min-height:44px; padding:12px 0; }
      input, select, textarea { font-size:16px; }
      input, select { min-height:44px; }
    }
  `]
})
export class ChatWorkspaceComponent extends BaseService implements OnInit, OnDestroy {
  @ViewChildren(WorkspaceChatPaneComponent) panes!: QueryList<WorkspaceChatPaneComponent>;
  @ViewChild('folderToggle', { read: ElementRef }) folderToggle?: ElementRef<HTMLButtonElement>;
  foldersOpen = false;
  closeFolders(): void {
    if (!this.foldersOpen) return;
    this.foldersOpen = false;
    this.folderToggle?.nativeElement.focus();
  }
  projects: WorkspaceProject[] = [];
  opened: OpenChat[] = [];
  activeId = '';
  editingId = '';
  editingTitle = '';
  titleSearch = '';
  private readonly collapsedVendors: Record<string, Record<string, boolean>> = {};
  private titleRevision = 0;
  directory = '';
  parentDirectory = '';
  projectName = '';
  projectForm: '' | 'existing' | 'new' = '';
  newChatProject = '';
  newFramework = '';
  newModel = '';
  frameworks: NativeFramework[] = [];
  nativeFolders: Record<string, NativeFolder[]> = {};
  private nativeSources: NativeSource[] = [];
  private nativeSourcesLoaded = false;
  nativeLoading = false;
  nativeError = '';
  enabled = false;
  loading = true;
  saving = false;
  error = '';
  private readonly subscriptions = new Subscription();
  private readonly url = `${this.backendUrl}/agents/chat/workspace`;
  private readonly openKey = `kompile-workspace-open:${this.backendUrl}`;
  // The child views throttle stream rendering outside Angular; poll only status badges.
  private statusTimer?: ReturnType<typeof setInterval>;
  private titleTimer?: ReturnType<typeof setInterval>;
  private titleReadPending = false;
  constructor(private http: HttpClient, private cdr: ChangeDetectorRef) { super(); }
  ngOnInit(): void {
    this.statusTimer = setInterval(() => this.cdr.markForCheck(), 1000);
    this.titleTimer = setInterval(() => this.refreshTitles(), 10_000);
    this.subscriptions.add(this.http.get<WorkspaceView>(this.url).subscribe({
      next: view => {
        this.enabled = view.enabled;
        this.projects = view.projects;
        this.parentDirectory = view.workingDirectory || '';
        this.loading = false;
        if (this.enabled) this.loadNativeSources();
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
  ngOnDestroy(): void {
    this.subscriptions.unsubscribe(); clearInterval(this.statusTimer); clearInterval(this.titleTimer);
  }
  private refreshTitles(): void {
    if (!this.enabled || this.loading || this.saving || this.titleReadPending || document.visibilityState === 'hidden') return;
    this.titleReadPending = true;
    const revision = this.titleRevision;
    this.subscriptions.add(this.http.get<WorkspaceView>(this.url).subscribe({
      next: view => {
        this.titleReadPending = false;
        if (this.saving || revision !== this.titleRevision) return;
        // Keep live pane inputs and transports mounted; update titles on their existing objects.
        this.projects = view.projects.map(incoming => {
          const project = this.projects.find(p => p.id === incoming.id);
          if (!project) return incoming;
          project.name = incoming.name;
          project.chats = incoming.chats.map(chat => {
            const existing = project.chats.find(c => c.id === chat.id);
            if (existing) { existing.name = chat.name; return existing; }
            return chat;
          });
          return project;
        });
        this.syncNativeProjects();
        this.cdr.markForCheck();
      },
      error: () => { this.titleReadPending = false; } // Retry on the next visible interval; retain the last list.
    }));
  }
  private loadNativeSources(): void {
    if (this.nativeLoading) return;
    this.nativeLoading = true; this.nativeError = '';
    this.subscriptions.add(this.http.get<NativeSource[]>(`${this.url}/native-sources`).subscribe({
      next: sources => {
        this.nativeSources = sources; this.nativeSourcesLoaded = true; this.nativeLoading = false;
        this.syncNativeProjects(); this.cdr.markForCheck();
      },
      error: err => {
        this.nativeLoading = false;
        this.nativeError = err?.error?.message || err?.message || 'Cannot read vendor folders';
        this.cdr.markForCheck();
      }
    }));
  }
  private syncNativeProjects(): void {
    if (!this.nativeSourcesLoaded) return;
    for (const project of this.projects) {
      if (!this.nativeFolders[project.id]) this.refreshNativeFolders(project);
    }
  }
  refreshNativeFolders(project?: WorkspaceProject): void {
    if (!this.enabled) return;
    if (!this.nativeSourcesLoaded) { this.loadNativeSources(); return; }
    for (const target of project ? [project] : this.projects) {
      const folders = this.nativeFolders[target.id] ||= this.nativeSources.map(source => ({ ...source, chats: [], loading: false }));
      for (const folder of folders) {
        if (folder.loading) continue;
        folder.loading = true; folder.error = '';
        this.subscriptions.add(this.http.get<NativeFolder>(
          `${this.url}/projects/${encodeURIComponent(target.id)}/native-folders/${encodeURIComponent(folder.source)}`).subscribe({
          next: result => {
            // Preserve the last successful list on failure, and keep live panes mounted.
            if (!result.error) folder.chats = result.chats;
            folder.error = result.error; folder.loading = false; this.cdr.markForCheck();
          },
          error: err => {
            folder.error = err?.error?.message || err?.message || 'Cannot read vendor chats';
            folder.loading = false; this.cdr.markForCheck();
          }
        }));
      }
    }
    this.cdr.markForCheck();
  }
  workspaceChats(project: WorkspaceProject): WorkspaceChat[] {
    const query = this.titleSearch.trim().toLocaleLowerCase();
    return project.chats.filter(chat => !chat.nativeSource && chat.name.toLocaleLowerCase().includes(query));
  }
  workspaceChatCount(project: WorkspaceProject): number {
    return project.chats.filter(chat => !chat.nativeSource).length;
  }
  nativeChats(folder: NativeFolder): NativeChat[] {
    const query = this.titleSearch.trim().toLocaleLowerCase();
    return folder.chats.filter(chat => (chat.title || chat.sessionId).toLocaleLowerCase().includes(query));
  }
  vendorCollapsed(projectId: string, source: string): boolean {
    return !!this.collapsedVendors[projectId]?.[source];
  }
  rememberVendorCollapse(projectId: string, source: string, event: Event): void {
    const folders = this.collapsedVendors[projectId] ||= {};
    folders[source] = !(event.target as HTMLDetailsElement).open;
  }
  trackProject(_index: number, project: WorkspaceProject): string { return project.id; }
  trackVendor(_index: number, folder: NativeSource): string { return folder.source; }
  trackWorkspaceChat(_index: number, chat: WorkspaceChat): string { return chat.id; }
  trackNativeChat(_index: number, chat: NativeChat): string { return chat.sessionId; }
  openNative(folder: NativeFolder, native: NativeChat): void {
    if (!this.enabled || this.saving) return;
    // open() enforces the pane limit after resolving the reference, allowing an existing pane to reopen.
    this.saving = true; this.error = ''; this.titleRevision++;
    this.subscriptions.add(this.http.post<{ project: WorkspaceProject; chat: WorkspaceChat }>(`${this.url}/native/open`, {
      source: folder.source, sessionId: native.sessionId
    }).subscribe({
      next: selected => {
        let project = this.projects.find(p => p.id === selected.project.id);
        if (project) project.chats = selected.project.chats;
        else { project = selected.project; this.projects = [...this.projects, project]; }
        this.saving = false;
        this.open(project, selected.chat);
        this.cdr.markForCheck();
      }, error: err => { this.saving = false; this.fail(err); }
    }));
  }
  startRename(chat: WorkspaceChat): void {
    this.editingId = chat.id;
    this.editingTitle = chat.name;
    this.error = '';
  }
  rename(project: WorkspaceProject, chat: WorkspaceChat): void {
    if (this.saving || !this.editingTitle.trim()) return;
    this.saving = true; this.error = ''; this.titleRevision++;
    this.subscriptions.add(this.http.put<WorkspaceChat>(
      `${this.url}/projects/${encodeURIComponent(project.id)}/chats/${encodeURIComponent(chat.id)}/title`,
      { name: this.editingTitle.trim() }).subscribe({
      next: updated => {
        chat.name = updated.name;
        this.titleRevision++; this.saving = false; this.editingId = '';
        this.cdr.markForCheck();
      }, error: err => { this.saving = false; this.fail(err); }
    }));
  }
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
        this.syncNativeProjects();
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
        this.syncNativeProjects();
        this.projectName = ''; this.projectForm = ''; this.saving = false;
        this.newChat(project);
      }, error: err => { this.saving = false; this.fail(err); }
    }));
  }
  beginChat(project: WorkspaceProject): void {
    this.foldersOpen = true;
    this.newChatProject = project.id; this.newFramework = ''; this.newModel = ''; this.frameworks = []; this.error = '';
    this.subscriptions.add(this.http.get<{ frameworks?: NativeFramework[] }>(`${this.backendUrl}/agents/chat/capabilities`,
      { params: { workingDirectory: project.workingDirectory } }).subscribe({
      next: report => { if (this.newChatProject === project.id) this.frameworks = report.frameworks || []; },
      error: err => this.fail(err)
    }));
  }
  newChat(project: WorkspaceProject, framework?: string, model?: string): void {
    if (!this.enabled || this.saving) return;
    this.saving = true; this.error = '';
    this.subscriptions.add(this.http.post<WorkspaceChat>(`${this.url}/projects/${project.id}/chats`, {
      name: `Chat ${project.chats.length + 1}`, ...(framework ? { framework } : {}), ...(model ? { model } : {})
    }).subscribe({
      next: chat => { project.chats = [...project.chats, chat]; this.saving = false; this.newChatProject = ''; this.open(project, chat); },
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
    this.closeFolders();
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
  activity(id: string): ChatActivity { return this.panes?.find(p => p.chat.id === id)?.activity || IDLE_CHAT_ACTIVITY; }
  trackChat(_: number, item: OpenChat): string { return item.chat.id; }
  private saveOpen(): void { sessionStorage.setItem(this.openKey, JSON.stringify(this.opened.map(item => item.chat.id))); }
  private fail(err: any): void { this.error = err?.error?.message || err?.message || 'Workspace request failed'; }
}
