import { Component, Input, Output, EventEmitter, forwardRef } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterTestingModule } from '@angular/router/testing';
import { By } from '@angular/platform-browser';
import { responsiveLayout } from '../responsive-layout-test-helper';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { NewChatDialogComponent, NewChatDialogResult } from '../new-chat-dialog/new-chat-dialog.component';
import { ChatWorkspaceComponent, WorkspaceChatPaneComponent, WorkspaceProject } from './chat-workspace.component';
import { UnifiedChatComponent } from '../unified-chat/unified-chat.component';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { AgentService } from '@shared/services/agent.service';
import { ChatActivityIndicatorComponent, ChatActivity, IDLE_CHAT_ACTIVITY } from '../chat-activity-indicator/chat-activity-indicator.component';

@Component({ selector: 'app-unified-chat', standalone: false, template: '',
  // The pane finds its chat view by the real class.
  providers: [{ provide: UnifiedChatComponent, useExisting: forwardRef(() => StubChat) }] })
class StubChat {
  @Input() workingDirectory?: string;
  @Input() workspaceChat?: { id: string; name: string };
  @Input() viewActive = true;
  @Output() workspaceNewChat = new EventEmitter<void>();
  /** Like a new workspace chat, which starts loading its transcript while it is first checked. */
  static busyOnInit = false;
  lifecycleBusy = false;
  activityIndicator: ChatActivity = IDLE_CHAT_ACTIVITY;
  ngOnInit(): void {
    if (StubChat.busyOnInit) { this.lifecycleBusy = true; this.activityIndicator = { active: true, label: 'Loading', tone: 'work' }; }
  }
  refreshWorkspaceTranscript(): void { }
}

@Component({ selector: 'app-cli-terminal-console', standalone: false, template: '' })
class StubTerminal {
  @Input() workingDirectory = '';
  @Input() visible = true;
  @Output() hidden = new EventEmitter<void>();
}

describe('Chat workspace', () => {
  let fixture: ComponentFixture<ChatWorkspaceComponent>;
  let http: HttpTestingController;
  let projects: WorkspaceProject[];
  let closed: Subject<NewChatDialogResult | undefined>;
  let dialogs: jasmine.SpyObj<MatDialog>;
  beforeEach(async () => {
    sessionStorage.clear();
    closed = new Subject();
    dialogs = jasmine.createSpyObj('MatDialog', ['open']);
    dialogs.open.and.returnValue({afterClosed: () => closed.asObservable()} as any);
    await TestBed.configureTestingModule({
      imports: [CommonModule, FormsModule, RouterTestingModule, HttpClientTestingModule, MatButtonModule, MatIconModule, ChatActivityIndicatorComponent],
      declarations: [ChatWorkspaceComponent, WorkspaceChatPaneComponent, StubChat, StubTerminal],
      providers: [{provide: MatDialog, useValue: dialogs}]
    }).compileComponents();
    fixture = TestBed.createComponent(ChatWorkspaceComponent);
    http = TestBed.inject(HttpTestingController);
    projects = [
      { id: 'p1', name: 'One', workingDirectory: '/projects/one', chats: [{ id: 'c1', name: 'First' }] },
      { id: 'p2', name: 'Two', workingDirectory: '/projects/two', chats: [{ id: 'c2', name: 'Second' }] }
    ];
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('/agents/chat/workspace')).flush({ enabled: true, projects });
    http.expectOne(r => r.url.endsWith('/workspace/native-sources')).flush([
      { source: 'claude-code', name: 'Claude Code' }, { source: 'codex', name: 'Codex' }
    ]);
    for (const project of projects) flushVendorRequests(project.id);
    fixture.detectChanges();
  });
  function flushVendorRequests(projectId: string): void {
    for (const source of ['claude-code', 'codex']) {
      const chats = projectId === 'p1' && source === 'claude-code'
        ? [{ sessionId: 'original-claude', title: 'Original Claude', workingDirectory: '/projects/one' }]
        : projectId === 'p2' && source === 'codex'
          ? [{ sessionId: 'original-codex', title: 'Original Codex', workingDirectory: '/projects/two' }] : [];
      http.expectOne(r => r.url.endsWith(`/projects/${projectId}/native-folders/${source}`))
        .flush({ source, name: source, chats });
    }
  }
  afterEach(() => { fixture.destroy(); http.verify(); sessionStorage.clear(); });

  it('keeps the inline terminal mounted while hidden and follows the selected folder for new launches', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    const toggle: HTMLButtonElement = fixture.nativeElement.querySelector('.terminal-launcher button');
    toggle.click(); fixture.detectChanges();
    const terminal = fixture.debugElement.query(By.directive(StubTerminal)).componentInstance as StubTerminal;
    expect(terminal.visible).toBeTrue();
    expect(terminal.workingDirectory).toBe('/projects/one');
    workspace.open(projects[1], projects[1].chats[0]); fixture.detectChanges();
    expect(terminal.workingDirectory).toBe('/projects/two');
    terminal.hidden.emit(); fixture.detectChanges();
    expect(terminal.visible).toBeFalse();
    expect(fixture.debugElement.query(By.directive(StubTerminal)).componentInstance).toBe(terminal);
    toggle.click(); fixture.detectChanges();
    expect(terminal.visible).toBeTrue();
    expect(fixture.debugElement.query(By.directive(StubTerminal)).componentInstance).toBe(terminal);
    http.expectNone(r => r.method === 'POST');
  });

  it('refreshes each chat indicator in its sidebar and tab while another pane is selected', fakeAsync(() => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]);
    workspace.open(projects[1], projects[1].chats[0]);
    fixture.detectChanges();
    const views = fixture.debugElement.queryAll(By.directive(StubChat)).map(pane => pane.componentInstance as StubChat);
    views[0].activityIndicator = { active: true, label: 'Running 1 process', tone: 'work' };
    views[1].activityIndicator = { active: true, label: 'Thinking', tone: 'work' };
    workspace.panes.forEach((pane, index) => pane.view = views[index] as any);
    tick(1000);
    fixture.detectChanges();
    const sidebar = Array.from(fixture.nativeElement.querySelectorAll('aside .kompile-folder app-chat-activity-indicator')) as HTMLElement[];
    const tabs = Array.from(fixture.nativeElement.querySelectorAll('nav app-chat-activity-indicator')) as HTMLElement[];
    expect(sidebar.map(badge => badge.textContent!.trim())).toEqual(['Running 1 process', 'Thinking']);
    expect(tabs.map(badge => badge.textContent!.trim())).toEqual(['Running 1 process', 'Thinking']);
    expect(workspace.activeId).toBe('c2');
    expect(views[0].workspaceChat!.name).toBe('First');
    views[0].activityIndicator = IDLE_CHAT_ACTIVITY;
    tick(1000);
    fixture.detectChanges();
    expect(sidebar[0].textContent!.trim()).toBe('Idle');
    expect(sidebar[0].querySelector('.spinner')).toBeNull();
    expect(sidebar[1].querySelector('.spinner')).not.toBeNull();
    fixture.destroy();
  }));

  it('opens and dismisses mobile folders without destroying a busy chat', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]);
    fixture.detectChanges();
    const pane = fixture.debugElement.query(By.directive(StubChat)).componentInstance as StubChat;
    pane.lifecycleBusy = true;
    const toggle = fixture.nativeElement.querySelector('.mobile-folder-toolbar button') as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.querySelector('aside').classList.contains('mobile-open')).toBeTrue();
    fixture.nativeElement.querySelector('.workspace').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.folder-backdrop').click();
    fixture.detectChanges();
    expect(workspace.foldersOpen).toBeFalse();
    expect(fixture.debugElement.query(By.directive(StubChat)).componentInstance).toBe(pane);
    toggle.click();
    fixture.detectChanges();
    workspace.open(projects[1], projects[1].chats[0]);
    fixture.detectChanges();
    expect(workspace.foldersOpen).toBeFalse();
  });

  for (const width of [320, 375, 768, 1280]) {
    it(`fits folders and scrolling tabs at ${width}px without consuming the chat width`, () => {
      const workspace = fixture.componentInstance;
      workspace.open(projects[0], { id: 'long-chat', name: 'A very long chat title '.repeat(12) });
      workspace.open(projects[1], projects[1].chats[0]);
      fixture.detectChanges();
      const layout = responsiveLayout(fixture.nativeElement, width);
      try {
        const aside = layout.root.querySelector('aside') as HTMLElement;
        const toolbar = layout.root.querySelector('.mobile-folder-toolbar') as HTMLElement;
        const main = layout.root.querySelector('main') as HTMLElement;
        const nav = layout.root.querySelector('nav') as HTMLElement;
        expect(layout.view.getComputedStyle(aside).display).toBe(width <= 768 ? 'none' : 'block');
        expect(layout.view.getComputedStyle(toolbar).display).toBe(width <= 768 ? 'flex' : 'none');
        expect(main.getBoundingClientRect().width).toBeGreaterThan(width <= 768 ? width - 2 : width - 320);
        expect(layout.root.scrollWidth).toBeLessThanOrEqual(width);
        // One row of one-line tabs at every width: a long chat name is clipped, never wrapped into the chat's height.
        expect(nav.getBoundingClientRect().height).toBeLessThan(width <= 768 ? 80 : 60);
        const longName = nav.querySelector('.tab-name') as HTMLElement;
        expect(longName.getBoundingClientRect().width).toBeLessThanOrEqual(width <= 768 ? width * 0.6 + 1 : 360);
        expect(longName.scrollWidth).toBeGreaterThan(longName.clientWidth);
        if (width <= 768) {
          aside.classList.add('mobile-open');
          expect(aside.getBoundingClientRect().width).toBeLessThanOrEqual(width);
          expect(layout.view.getComputedStyle(aside).display).toBe('block');
          const close = aside.querySelector('.close-folders') as HTMLElement;
          expect(close.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
          expect((nav.querySelector('.close-chat') as HTMLElement).getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
        }
      } finally { layout.dispose(); }
    });
  }

  it('filters all project chat titles case-insensitively and clears without changing live panes', fakeAsync(() => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    const pane = workspace.panes.first;
    const input: HTMLInputElement = fixture.nativeElement.querySelector('#workspace-chat-search');
    const titles = () => Array.from(fixture.nativeElement.querySelectorAll('aside button.chat'))
      .map(button => ((button as HTMLElement).querySelector('.chat-title') || button as HTMLElement).textContent!.trim());
    input.value = '  oRiGiNaL  '; input.dispatchEvent(new Event('input')); tick(); fixture.detectChanges();
    expect(titles()).toEqual(['Original Claude', 'Original Codex']);
    expect(fixture.nativeElement.querySelector('.kompile-folder summary').textContent).toContain('0 / 1');
    expect(fixture.nativeElement.querySelector('.native-folder summary').textContent).toContain('1 / 1');
    input.value = ' FIRST '; input.dispatchEvent(new Event('input')); tick(); fixture.detectChanges();
    expect(titles()).toEqual(['First']);
    input.value = 'absent title'; input.dispatchEvent(new Event('input')); tick(); fixture.detectChanges();
    expect(titles()).toEqual([]);
    expect(fixture.nativeElement.querySelector('aside').textContent).toContain('No matching chat titles.');
    const clear = Array.from(fixture.nativeElement.querySelectorAll('.chat-search button'))[0] as HTMLButtonElement;
    clear.click(); fixture.detectChanges(); tick(); fixture.detectChanges();
    expect(input.value).toBe('');
    expect(titles()).toEqual(['First', 'Original Claude', 'Second', 'Original Codex']);
    expect(workspace.panes.first).toBe(pane);
    expect(workspace.activeId).toBe('c1');
    http.expectNone(r => r.method === 'POST');
  }));

  it('collapses vendors independently by project and preserves choices through refresh and filtering', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    const pane = workspace.panes.first;
    const sections = fixture.nativeElement.querySelectorAll('aside section');
    const claude: HTMLDetailsElement = sections[0].querySelector('.native-folder');
    const kompile: HTMLDetailsElement = sections[0].querySelector('.kompile-folder');
    const collapse = (details: HTMLDetailsElement) => {
      (details.querySelector('summary') as HTMLElement).click();
      details.dispatchEvent(new Event('toggle')); fixture.detectChanges();
      expect(details.open).toBeFalse();
    };
    collapse(claude); collapse(kompile);
    expect(sections[0].querySelectorAll('.native-folder')[1].open).toBeTrue();
    expect(sections[1].querySelector('.native-folder').open).toBeTrue();
    expect(sections[1].querySelector('.kompile-folder').open).toBeTrue();
    workspace.titleSearch = 'original';
    workspace.projects = workspace.projects.map(project => ({ ...project, chats: [...project.chats] }));
    workspace.nativeFolders['p1'] = workspace.nativeFolders['p1'].map(folder => ({ ...folder }));
    workspace.refreshNativeFolders(workspace.projects[0]); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('aside section .native-folder')).toBe(claude);
    expect(claude.open).toBeFalse();
    expect(claude.querySelector('summary')!.textContent).toContain('Loading chats');
    flushVendorRequests('p1'); fixture.detectChanges();
    workspace.titleSearch = ''; fixture.detectChanges();
    expect(claude.open).toBeFalse(); expect(kompile.open).toBeFalse();
    (claude.querySelector('summary') as HTMLElement).click();
    claude.dispatchEvent(new Event('toggle')); fixture.detectChanges();
    expect(claude.open).toBeTrue();
    expect(claude.textContent).toContain('Original Claude');
    expect(workspace.panes.first).toBe(pane);
  });

  it('filters arriving vendor titles without hiding loading and error states', () => {
    const workspace = fixture.componentInstance;
    workspace.titleSearch = 'parser';
    workspace.refreshNativeFolders(projects[0]); fixture.detectChanges();
    const section = fixture.nativeElement.querySelector('aside section');
    expect(section.textContent).toContain('Loading Claude Code chats');
    http.expectOne(r => r.url.endsWith('/projects/p1/native-folders/claude-code')).flush({ source: 'claude-code', chats: [
      { sessionId: 'match', title: 'Fix Parser' }, { sessionId: 'not-a-match', title: 'Other chat' }
    ] });
    http.expectOne(r => r.url.endsWith('/projects/p1/native-folders/codex')).flush({ source: 'codex', chats: [], error: 'Vendor unavailable' });
    fixture.detectChanges();
    expect(section.querySelector('.native-folder summary').textContent).toContain('1 / 2');
    const titles = Array.from(section.querySelectorAll('button.chat')).map(button => ((button as HTMLElement).querySelector('.chat-title') || button as HTMLElement).textContent!.trim());
    expect(titles).toEqual(['Fix Parser']);
    expect(section.querySelector('[role="alert"]').textContent).toContain('Vendor unavailable');
    expect(workspace.nativeFolders['p1'][0].chats.length).toBe(2);
    http.expectNone(r => r.method === 'POST');
  });

  it('lists original vendor chats and opens reference-only panes with independent transports', () => {
    const workspace = fixture.componentInstance;
    expect(fixture.nativeElement.querySelector('aside').textContent).toContain('Claude Code');
    expect(fixture.nativeElement.querySelector('aside').textContent).toContain('Original Codex');
    http.expectNone(r => r.method === 'POST');
    const firstFolder = workspace.nativeFolders['p1'][0];
    workspace.openNative(firstFolder, firstFolder.chats[0]);
    const request = http.expectOne(r => r.url.endsWith('/workspace/native/open'));
    expect(request.request.body).toEqual({ source: 'claude-code', sessionId: 'original-claude' });
    const chat = { id: 'claude-reference', name: 'Original Claude', framework: 'claude', nativeSource: 'claude-code' };
    const project = { ...projects[0], chats: [...projects[0].chats, chat] };
    request.flush({ project, chat }); fixture.detectChanges();
    const pane = workspace.panes.first;
    expect(pane.chat.id).toBe('claude-reference');
    expect(pane.project.workingDirectory).toBe('/projects/one');
    expect(fixture.nativeElement.querySelectorAll('.rename-chat').length).toBe(0);
    const transport = fixture.debugElement.query(By.directive(WorkspaceChatPaneComponent)).injector.get(LocalAgentChatService);
    const secondFolder = workspace.nativeFolders['p2'][1];
    workspace.openNative(secondFolder, secondFolder.chats[0]);
    const codex = { id: 'codex-reference', name: 'Original Codex', framework: 'codex', nativeSource: 'codex' };
    http.expectOne(r => r.url.endsWith('/workspace/native/open')).flush({ project: { ...projects[1], chats: [codex] }, chat: codex });
    fixture.detectChanges();
    expect(workspace.panes.first).toBe(pane);
    const panes = fixture.debugElement.queryAll(By.directive(WorkspaceChatPaneComponent));
    expect(panes[1].injector.get(LocalAgentChatService)).not.toBe(transport);
    workspace.openNative(firstFolder, firstFolder.chats[0]);
    http.expectOne(r => r.url.endsWith('/workspace/native/open')).flush({ project, chat });
    expect(workspace.opened.length).toBe(2);
    expect(workspace.activeId).toBe('claude-reference');
  });

  it('refreshes native listings without remounting a live pane and retains them on failure', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    const pane = workspace.panes.first;
    workspace.refreshNativeFolders(projects[0]); workspace.refreshNativeFolders(projects[0]);
    http.expectOne(r => r.url.endsWith('/projects/p1/native-folders/codex')).flush(
      { source: 'codex', name: 'Codex', chats: [], error: 'Vendor store unavailable' });
    http.expectOne(r => r.url.endsWith('/projects/p1/native-folders/claude-code')).flush(
      { message: 'Read failed' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(workspace.panes.first).toBe(pane);
    expect(fixture.nativeElement.querySelector('aside').textContent).toContain('Vendor store unavailable');
    expect(workspace.nativeFolders['p1'][0].chats[0].title).toBe('Original Claude');
    expect(workspace.nativeFolders['p1'][0].error).toBe('Read failed');
    expect(workspace.nativeFolders['p2'][1].error).toBeFalsy();
    http.expectNone(r => r.url.includes('/projects/p2/native-folders/')); 
    expect(workspace.panes.first).toBe(pane);
  });

  it('shows expanded project folders immediately and renders each vendor independently', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    const pane = workspace.panes.first;
    const sections = fixture.nativeElement.querySelectorAll('aside section');
    expect(sections[0].textContent).toContain('Original Claude');
    expect(sections[0].textContent).not.toContain('Original Codex');
    expect(sections[1].textContent).toContain('Original Codex');
    expect(sections[1].textContent).not.toContain('Original Claude');
    for (const details of fixture.nativeElement.querySelectorAll('details.native-folder')) expect(details.open).toBeTrue();
    workspace.refreshNativeFolders(projects[0]); fixture.detectChanges();
    expect(sections[0].textContent).toContain('Loading Claude Code chats');
    expect(sections[0].textContent).toContain('Loading Codex chats');
    expect(sections[1].textContent).not.toContain('Loading');
    expect(sections[0].querySelector('[role="status"]')).not.toBeNull();
    http.expectOne(r => r.url.endsWith('/projects/p1/native-folders/codex')).flush({
      source: 'codex', name: 'Codex', chats: [{ sessionId: 'fast', title: 'Fast Codex result' }]
    }); fixture.detectChanges();
    expect(sections[0].textContent).toContain('Fast Codex result');
    expect(sections[0].textContent).toContain('Loading Claude Code chats');
    expect(workspace.panes.first).toBe(pane);
    http.expectOne(r => r.url.endsWith('/projects/p1/native-folders/claude-code')).flush({ source: 'claude-code', name: 'Claude Code', chats: [] });
    fixture.detectChanges();
    expect(sections[0].textContent).not.toContain('Loading');
    expect(sections[0].textContent).toContain('No chats for this project.');
  });

  it('loads project vendor folders automatically on initial workspace load', () => {
    const extra = TestBed.createComponent(ChatWorkspaceComponent);
    extra.detectChanges();
    http.expectOne(r => r.url.endsWith('/agents/chat/workspace')).flush({ enabled: true, projects: [projects[0]] });
    extra.detectChanges();
    expect(extra.nativeElement.textContent).toContain('Loading vendor folders');
    http.expectOne(r => r.url.endsWith('/workspace/native-sources')).flush([
      { source: 'claude-code', name: 'Claude Code' }, { source: 'codex', name: 'Codex' }
    ]); extra.detectChanges();
    expect(extra.nativeElement.textContent).toContain('Loading Claude Code chats');
    expect(extra.nativeElement.textContent).toContain('Loading Codex chats');
    expect(extra.nativeElement.textContent).toContain('First');
    flushVendorRequests('p1'); extra.detectChanges();
    expect(extra.nativeElement.textContent).toContain('Original Claude');
    extra.destroy();
  });

  it('keeps existing panes when opening a missing native session fails', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    workspace.openNative(workspace.nativeFolders['p1'][0], workspace.nativeFolders['p1'][0].chats[0]);
    http.expectOne(r => r.url.endsWith('/workspace/native/open')).flush({ message: 'Unknown native chat session' }, { status: 400, statusText: 'Bad Request' });
    expect(workspace.saving).toBeFalse();
    expect(workspace.activeId).toBe('c1');
    expect(workspace.opened.length).toBe(1);
    expect(workspace.error).toBe('Unknown native chat session');
  });

  it('persists full renamed titles and updates the same mounted chat', () => {
    const workspace = fixture.componentInstance;
    const project = workspace.projects[0];
    const chat = project.chats[0];
    workspace.open(project, chat); fixture.detectChanges();
    const pane = workspace.panes.first;
    const title = 'A long chat title '.repeat(30) + 'important ending';
    workspace.startRename(chat);
    workspace.editingTitle = title;
    workspace.rename(project, chat);
    const request = http.expectOne(r => r.url.endsWith('/projects/p1/chats/c1/title'));
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ name: title });
    request.flush({ id: chat.id, name: title });
    fixture.detectChanges();
    expect(workspace.panes.first).toBe(pane);
    expect(workspace.opened[0].chat.name).toBe(title);
    expect(fixture.nativeElement.querySelector('aside').textContent).toContain(title);
    expect((fixture.debugElement.query(By.directive(StubChat)).componentInstance as StubChat).workspaceChat!.name).toBe(title);
    workspace.startRename(chat); workspace.editingTitle = '  ';
    workspace.rename(project, chat);
    http.expectNone(r => r.method === 'PUT');
  });

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

  it('refreshes sidebar titles without remounting live panes or overlapping reads', fakeAsync(() => {
    const extra = TestBed.createComponent(ChatWorkspaceComponent);
    extra.detectChanges();
    http.expectOne(r => r.url.endsWith('/agents/chat/workspace')).flush({ enabled: true, projects });
    http.expectOne(r => r.url.endsWith('/workspace/native-sources')).flush([]);
    extra.componentInstance.open(projects[0], projects[0].chats[0]); extra.detectChanges();
    const pane = extra.componentInstance.panes.first;
    pane.view = { lifecycleBusy: true } as any;
    tick(10_000);
    const pending = http.expectOne(r => r.url.endsWith('/agents/chat/workspace'));
    tick(10_000);
    http.expectNone(r => r.url.endsWith('/agents/chat/workspace'));
    pending.flush({ enabled: true, projects: [{ ...projects[0], chats: [{ id: 'c1', name: 'Real resume title',
      framework: 'opencode', model: 'zai/glm-5', route: 'anthropic / claude-opus-5-5' }] }] });
    extra.detectChanges();
    expect(extra.componentInstance.panes.first).toBe(pane);
    expect(extra.componentInstance.opened[0].chat.name).toBe('Real resume title');
    expect(extra.nativeElement.querySelector('aside').textContent).toContain('Real resume title');
    // The list shows where the chat runs now, after a vendor switch, not its launch selection.
    const route = extra.nativeElement.querySelector('aside [data-testid="chat-route"]');
    expect(route.textContent).toBe('anthropic / claude-opus-5-5');
    extra.destroy(); tick(20_000);
    http.expectNone(r => r.url.endsWith('/agents/chat/workspace'));
  }));

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

  it('chooses a framework and vendor model without replacing an existing pane', () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    const oldPane = workspace.panes.first;
    workspace.beginChat(projects[0]);
    expect(dialogs.open).toHaveBeenCalledWith(NewChatDialogComponent, jasmine.objectContaining({data: jasmine.objectContaining({workingDirectory: '/projects/one'})}));
    closed.next({chat: { id: 'zai', name: 'GLM', framework: 'opencode', model: 'zai/glm-5' }}); fixture.detectChanges();
    expect(workspace.panes.first).toBe(oldPane);
    expect(workspace.opened.map(item => item.chat.id)).toEqual(['c1', 'zai']);
    expect(fixture.nativeElement.querySelector('aside').textContent).toContain('opencode / zai/glm-5');
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
    expect(workspace.newChatProject).toBe('p2');
    expect((dialogs.open.calls.mostRecent().args[1]?.data as {workingDirectory: string}).workingDirectory).toBe('/projects/two');
    closed.next({chat: { id: 'c3', name: 'Chat 2' }});
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
    flushVendorRequests('p3');
    expect(dialogs.open.calls.mostRecent().args[1]?.data).toEqual(jasmine.objectContaining({name: 'Chat 1', workingDirectory: '/projects/Three'}));
    closed.next({chat: { id: 'c3', name: 'Chat 1' }});
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
    flushVendorRequests('p3');
    expect(workspace.activeId).toBe('native-session');
    expect(workspace.opened[0].project.id).toBe('p3');
  });

  it('keeps re-added aliases on the same folder object when creating subsequent chats', () => {
    const workspace = fixture.componentInstance;
    workspace.directory = '/alias/one';
    workspace.addProject();
    http.expectOne(r => r.url.endsWith('/workspace/projects')).flush({ ...projects[0], chats: [] });
    closed.next({chat: { id: 'fresh', name: 'Chat 1' }});
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
    http.expectOne(r => r.url.endsWith('/workspace/native-sources')).flush([]);
    expect((dialogs.open.calls.mostRecent().args[1]?.data as {workingDirectory: string}).workingDirectory).toBe('/projects/one');
    closed.next({chat: { id: 'first', name: 'Chat 1' }});
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

  it('shows a chat that starts busy as busy from the next check, not within the check that mounted it', async () => {
    StubChat.busyOnInit = true;
    try {
      const workspace = fixture.componentInstance;
      workspace.open(projects[0], projects[0].chats[0]);
      // TestBed re-checks every binding after the pass (NG0100) — this used to throw for the tab's close button.
      expect(() => fixture.detectChanges()).not.toThrow();
      await Promise.resolve(); // the pane publishes its state on the next microtask
      fixture.detectChanges();
      const close = fixture.nativeElement.querySelector('nav .close-chat') as HTMLButtonElement;
      expect(close.title).toBe('Stop the running chat before closing it');
      expect(workspace.busy('c1')).toBeTrue();
      expect((fixture.nativeElement.querySelector('nav app-chat-activity-indicator') as HTMLElement).textContent!.trim()).toBe('Loading');
      workspace.close('c1');
      expect(workspace.opened.length).toBe(1);
    } finally {
      StubChat.busyOnInit = false;
    }
  });

  it('does not close a busy pane and closing an idle pane keeps its registry chat', async () => {
    const workspace = fixture.componentInstance;
    workspace.open(projects[0], projects[0].chats[0]); fixture.detectChanges();
    await Promise.resolve();
    workspace.panes.first.view = { lifecycleBusy: true } as any;
    workspace.close('c1'); expect(workspace.opened.length).toBe(1);
    workspace.panes.first.view = { lifecycleBusy: false } as any;
    workspace.close('c1'); expect(workspace.opened.length).toBe(0);
    expect(workspace.projects[0].chats.length).toBe(1);
  });
});
