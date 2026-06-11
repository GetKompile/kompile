/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Component, OnInit, OnDestroy } from '@angular/core';
import { NestedTreeControl } from '@angular/cdk/tree';
import { MatTreeNestedDataSource } from '@angular/material/tree';
import { Subscription } from 'rxjs';
import {
  ChatSession,
  CodingProject,
  CrawlProfile,
  CrawlResult,
  FactSheet as ProjectFactSheet,
  GitResult,
  IndexedDocument,
  MarkdownEntry,
  NoteSyncConnection,
  ProjectComponent,
  ProjectComponentType,
  ProjectLifecycle,
  ProjectManifest,
  ProjectModel,
  ProjectPipeline,
  ProjectResponse,
  ProjectScript,
  ProjectService,
  ProjectStorageBackend,
  ProjectWorkflow,
  PromptTemplate,
  SourceDocument,
  WorkflowStep
} from '../../services/project.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet, Fact } from '../../models/api-models';

// ── Tree node types ──────────────────────────────────────────────────────────

export type TreeNodeType =
  'root' | 'category' | 'component' | 'markdown' | 'model' | 'pipeline' |
  'crawl' | 'workflow' | 'script' | 'code-project' | 'fact-sheet' | 'fact' |
  'crawl-result' | 'source-document' | 'prompt-template' | 'chat-session' | 'note-sync' | 'indexed-document';

export interface ProjectTreeNode {
  name: string;
  type: TreeNodeType;
  icon: string;
  children: ProjectTreeNode[];
  data?: any;
  badge?: number;
}

@Component({
  standalone: false,
  selector: 'app-project-manager',
  templateUrl: './project-manager.component.html',
  styleUrls: ['./project-manager.component.css']
})
export class ProjectManagerComponent implements OnInit, OnDestroy {
  // ── State ────────────────────────────────────────────────────────────────
  response: ProjectResponse | null = null;
  loading = false;
  saving = false;
  error: string | null = null;
  gitOutput: string | null = null;

  // ── Tree ─────────────────────────────────────────────────────────────────
  treeControl = new NestedTreeControl<ProjectTreeNode>(node => node.children);
  dataSource = new MatTreeNestedDataSource<ProjectTreeNode>();
  selectedNode: ProjectTreeNode | null = null;

  // ── Content viewer ───────────────────────────────────────────────────────
  viewMode: 'welcome' | 'details' | 'markdown' | 'table' | 'fact-sheet' | 'fact' | 'init' = 'welcome';
  selectedMarkdown: MarkdownEntry | null = null;
  markdownLoading = false;
  markdownEntries: MarkdownEntry[] = [];
  markdownSearch = '';

  // ── Catalog data ─────────────────────────────────────────────────────────
  crawlResults: CrawlResult[] = [];
  sourceDocuments: SourceDocument[] = [];
  promptTemplates: PromptTemplate[] = [];
  chatSessions: ChatSession[] = [];
  noteSyncConnections: NoteSyncConnection[] = [];
  indexedDocuments: IndexedDocument[] = [];

  // ── Fact sheet bridge ────────────────────────────────────────────────────
  factSheets: FactSheet[] = [];
  activeFacts: Fact[] = [];
  selectedFact: Fact | null = null;
  selectedFactSheet: FactSheet | null = null;
  private subs: Subscription[] = [];

  // ── Scope selector ───────────────────────────────────────────────────────
  scopeMode: 'project' | 'fact-sheets' = 'project';

  // ── Init form ────────────────────────────────────────────────────────────
  lifecycleOptions: ProjectLifecycle[] = ['DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED', 'DEPRECATED'];
  backendOptions: ProjectStorageBackend[] = ['LOCAL', 'GIT', 'GIT_XET', 'EXTERNAL'];
  componentTypes: ProjectComponentType[] = [
    'MARKDOWN', 'MODEL', 'SOURCE', 'CHAT', 'PROMPT', 'PIPELINE',
    'GRAPH', 'CONFIG', 'DATASET', 'ARTIFACT', 'MODULE', 'CODE_PROJECT', 'SCRIPT', 'CRAWL', 'OTHER'
  ];
  initName = '';
  initBackend: ProjectStorageBackend = 'LOCAL';
  initRemote = '';
  initBranch = 'main';
  initTags = 'kompile,rag';
  initGit = false;
  initGitXetInstall = false;

  // ── Add forms ────────────────────────────────────────────────────────────
  newComponent: ProjectComponent = this.emptyComponent();
  newComponentTags = '';
  newCodingProject: CodingProject = this.emptyCodingProject();
  newCodingProjectTags = '';
  newScript: ProjectScript = this.emptyScript();
  newScriptTags = '';
  newCrawlProfile: CrawlProfile = this.emptyCrawlProfile();
  newCrawlSources = '';
  newCrawlIncludes = '';
  newCrawlExcludes = '';
  newCrawlTags = '';
  newWorkflow: ProjectWorkflow = this.emptyWorkflow();
  newWorkflowStep: WorkflowStep = this.emptyWorkflowStep();
  newWorkflowTags = '';

  constructor(
    private projectService: ProjectService,
    private factSheetService: FactSheetService
  ) {}

  ngOnInit(): void {
    this.load();
    this.loadFactSheets();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  // ── Tree helpers ─────────────────────────────────────────────────────────

  hasChildren = (_: number, node: ProjectTreeNode) => node.children && node.children.length > 0;

  selectNode(node: ProjectTreeNode): void {
    this.selectedNode = node;
    this.error = null;

    switch (node.type) {
      case 'markdown':
        this.loadMarkdownContent(node.data as string);
        break;
      case 'fact':
        this.selectedFact = node.data as Fact;
        this.viewMode = 'fact';
        break;
      case 'fact-sheet':
        this.selectedFactSheet = node.data as FactSheet;
        this.loadFactsForSheet(this.selectedFactSheet.id);
        this.viewMode = 'fact-sheet';
        break;
      case 'category':
        this.viewMode = 'table';
        break;
      case 'component':
      case 'model':
      case 'pipeline':
      case 'crawl':
      case 'workflow':
      case 'script':
      case 'code-project':
      case 'crawl-result':
      case 'source-document':
      case 'prompt-template':
      case 'chat-session':
      case 'note-sync':
      case 'indexed-document':
        this.viewMode = 'details';
        break;
      default:
        this.viewMode = 'welcome';
    }
  }

  isSelected(node: ProjectTreeNode): boolean {
    return this.selectedNode === node;
  }

  // ── Data loading ─────────────────────────────────────────────────────────

  load(): void {
    this.loading = true;
    this.error = null;
    this.projectService.current().subscribe({
      next: response => {
        this.response = response;
        this.loading = false;
        if (response.manifest && !this.initName) {
          this.initName = response.manifest.name;
        }
        if (!response.manifest) {
          this.viewMode = 'init';
        }
        this.loadMarkdownList();
        this.loadCatalogs();
        this.rebuildTree();
      },
      error: err => {
        this.error = this.errorMessage(err);
        this.loading = false;
      }
    });
  }

  private loadCatalogs(): void {
    if (!this.response?.manifest) return;
    this.projectService.listCrawlResults().subscribe({
      next: data => { this.crawlResults = data; this.rebuildTree(); },
      error: () => {}
    });
    this.projectService.listSourceDocuments().subscribe({
      next: data => { this.sourceDocuments = data; this.rebuildTree(); },
      error: () => {}
    });
    this.projectService.listPromptTemplates().subscribe({
      next: data => { this.promptTemplates = data; this.rebuildTree(); },
      error: () => {}
    });
    this.projectService.listChatSessions().subscribe({
      next: data => { this.chatSessions = data; this.rebuildTree(); },
      error: () => {}
    });
    this.projectService.listNoteSyncConnections().subscribe({
      next: data => { this.noteSyncConnections = data; this.rebuildTree(); },
      error: () => {}
    });
    this.projectService.listIndexedDocuments().subscribe({
      next: data => { this.indexedDocuments = data; this.rebuildTree(); },
      error: () => {}
    });
  }

  loadMarkdownList(): void {
    if (!this.response?.manifest) return;
    this.projectService.listMarkdown().subscribe({
      next: entries => {
        this.markdownEntries = entries;
        this.rebuildTree();
      },
      error: () => { /* non-critical */ }
    });
  }

  loadMarkdownContent(path: string): void {
    this.markdownLoading = true;
    this.viewMode = 'markdown';
    this.projectService.readMarkdown(path).subscribe({
      next: entry => {
        this.selectedMarkdown = entry;
        this.markdownLoading = false;
      },
      error: err => {
        this.error = 'Failed to load markdown: ' + path;
        this.markdownLoading = false;
      }
    });
  }

  searchMarkdown(): void {
    if (!this.markdownSearch.trim()) {
      this.loadMarkdownList();
      return;
    }
    this.projectService.searchMarkdown(this.markdownSearch).subscribe({
      next: entries => {
        this.markdownEntries = entries;
        this.rebuildTree();
      },
      error: () => { }
    });
  }

  loadFactSheets(): void {
    this.subs.push(
      this.factSheetService.loadSheets().subscribe({
        next: sheets => {
          this.factSheets = sheets;
          this.rebuildTree();
        },
        error: () => { }
      })
    );
  }

  loadFactsForSheet(sheetId: number): void {
    this.subs.push(
      this.factSheetService.loadSheetFacts(sheetId).subscribe({
        next: facts => {
          this.activeFacts = facts;
          this.rebuildTree();
        },
        error: () => { }
      })
    );
  }

  // ── Tree building ────────────────────────────────────────────────────────

  rebuildTree(): void {
    if (this.scopeMode === 'project') {
      this.dataSource.data = this.buildProjectTree();
    } else {
      this.dataSource.data = this.buildFactSheetTree();
    }
  }

  switchScope(mode: 'project' | 'fact-sheets'): void {
    this.scopeMode = mode;
    this.selectedNode = null;
    this.viewMode = 'welcome';
    this.rebuildTree();
  }

  private buildProjectTree(): ProjectTreeNode[] {
    const manifest = this.response?.manifest;
    if (!manifest) return [];

    const nodes: ProjectTreeNode[] = [];

    // Components
    if (manifest.components.length > 0) {
      nodes.push({
        name: 'Components',
        type: 'category',
        icon: 'widgets',
        badge: manifest.components.length,
        children: manifest.components.map(c => ({
          name: c.name || c.id || c.path,
          type: 'component' as TreeNodeType,
          icon: this.componentIcon(c.type),
          children: [],
          data: c
        }))
      });
    }

    // Markdown files
    if (this.markdownEntries.length > 0) {
      const mdTree = this.buildMarkdownSubtree(this.markdownEntries);
      nodes.push({
        name: 'Markdown',
        type: 'category',
        icon: 'description',
        badge: this.markdownEntries.length,
        children: mdTree
      });
    }

    // Scripts
    if ((manifest.scripts || []).length > 0) {
      nodes.push({
        name: 'Scripts',
        type: 'category',
        icon: 'terminal',
        badge: manifest.scripts.length,
        children: manifest.scripts.map(s => ({
          name: s.name || s.id || 'script',
          type: 'script' as TreeNodeType,
          icon: 'code',
          children: [],
          data: s
        }))
      });
    }

    // Crawl Profiles
    if ((manifest.crawlProfiles || []).length > 0) {
      nodes.push({
        name: 'Crawl Profiles',
        type: 'category',
        icon: 'travel_explore',
        badge: manifest.crawlProfiles.length,
        children: manifest.crawlProfiles.map(p => ({
          name: p.name || p.id || 'crawl',
          type: 'crawl' as TreeNodeType,
          icon: 'language',
          children: [],
          data: p
        }))
      });
    }

    // Workflows
    if ((manifest.workflows || []).length > 0) {
      nodes.push({
        name: 'Workflows',
        type: 'category',
        icon: 'account_tree',
        badge: manifest.workflows.length,
        children: manifest.workflows.map(w => ({
          name: w.name || w.id || 'workflow',
          type: 'workflow' as TreeNodeType,
          icon: 'alt_route',
          children: [],
          data: w
        }))
      });
    }

    // Models
    if ((manifest.models || []).length > 0) {
      nodes.push({
        name: 'Models',
        type: 'category',
        icon: 'psychology',
        badge: manifest.models.length,
        children: manifest.models.map(m => ({
          name: m.modelId || m.id || 'model',
          type: 'model' as TreeNodeType,
          icon: 'smart_toy',
          children: [],
          data: m
        }))
      });
    }

    // Pipelines
    if ((manifest.pipelines || []).length > 0) {
      nodes.push({
        name: 'Pipelines',
        type: 'category',
        icon: 'linear_scale',
        badge: manifest.pipelines.length,
        children: manifest.pipelines.map(p => ({
          name: p.name || p.id || 'pipeline',
          type: 'pipeline' as TreeNodeType,
          icon: 'timeline',
          children: [],
          data: p
        }))
      });
    }

    // Coding Projects
    if ((manifest.codingProjects || []).length > 0) {
      nodes.push({
        name: 'Coding Projects',
        type: 'category',
        icon: 'integration_instructions',
        badge: manifest.codingProjects.length,
        children: manifest.codingProjects.map(cp => ({
          name: cp.name || cp.id || 'code-project',
          type: 'code-project' as TreeNodeType,
          icon: 'folder_special',
          children: [],
          data: cp
        }))
      });
    }

    // Crawl Results
    if (this.crawlResults.length > 0) {
      nodes.push({
        name: 'Crawl Results',
        type: 'category',
        icon: 'summarize',
        badge: this.crawlResults.length,
        children: this.crawlResults.map(r => ({
          name: r.name || r.profileId || 'crawl-result',
          type: 'crawl-result' as TreeNodeType,
          icon: 'receipt_long',
          children: [],
          data: r
        }))
      });
    }

    // Source Documents
    if (this.sourceDocuments.length > 0) {
      nodes.push({
        name: 'Source Documents',
        type: 'category',
        icon: 'upload_file',
        badge: this.sourceDocuments.length,
        children: this.sourceDocuments.map(d => ({
          name: d.fileName || d.path,
          type: 'source-document' as TreeNodeType,
          icon: 'insert_drive_file',
          children: [],
          data: d
        }))
      });
    }

    // Prompt Templates
    if (this.promptTemplates.length > 0) {
      nodes.push({
        name: 'Prompt Templates',
        type: 'category',
        icon: 'chat_bubble_outline',
        badge: this.promptTemplates.length,
        children: this.promptTemplates.map(t => ({
          name: t.displayName || t.name || t.id || 'prompt',
          type: 'prompt-template' as TreeNodeType,
          icon: t.builtIn ? 'verified' : 'edit_note',
          children: [],
          data: t
        }))
      });
    }

    // Chat Sessions
    if (this.chatSessions.length > 0) {
      nodes.push({
        name: 'Chat Sessions',
        type: 'category',
        icon: 'forum',
        badge: this.chatSessions.length,
        children: this.chatSessions.map(cs => ({
          name: cs.title || cs.sessionId || 'chat',
          type: 'chat-session' as TreeNodeType,
          icon: 'chat',
          children: [],
          data: cs
        }))
      });
    }

    // Indexed Documents (Managed Sources)
    if (this.indexedDocuments.length > 0) {
      nodes.push({
        name: 'Managed Sources',
        type: 'category',
        icon: 'source',
        badge: this.indexedDocuments.length,
        children: this.indexedDocuments.map(d => ({
          name: d.fileName || d.sourceId || 'document',
          type: 'indexed-document' as TreeNodeType,
          icon: this.indexedDocIcon(d),
          children: [],
          data: d
        }))
      });
    }

    // Note Sync Connections
    if (this.noteSyncConnections.length > 0) {
      nodes.push({
        name: 'Note Sync',
        type: 'category',
        icon: 'sync',
        badge: this.noteSyncConnections.length,
        children: this.noteSyncConnections.map(nc => ({
          name: (nc.provider || 'sync') + (nc.externalScope ? ' — ' + nc.externalScope : ''),
          type: 'note-sync' as TreeNodeType,
          icon: nc.enabled ? 'sync' : 'sync_disabled',
          children: [],
          data: nc
        }))
      });
    }

    // Fact Sheets (bridge)
    if (this.factSheets.length > 0) {
      nodes.push({
        name: 'Fact Sheets',
        type: 'category',
        icon: 'library_books',
        badge: this.factSheets.length,
        children: this.factSheets.map(fs => ({
          name: fs.name,
          type: 'fact-sheet' as TreeNodeType,
          icon: fs.isActive ? 'auto_stories' : 'menu_book',
          children: [],
          data: fs
        }))
      });
    }

    return nodes;
  }

  private buildFactSheetTree(): ProjectTreeNode[] {
    return this.factSheets.map(fs => {
      const children: ProjectTreeNode[] = [];
      if (fs.isActive && this.activeFacts.length > 0) {
        for (const fact of this.activeFacts) {
          children.push({
            name: fact.title || fact.fileName,
            type: 'fact',
            icon: this.factIcon(fact),
            children: [],
            data: fact
          });
        }
      }
      return {
        name: fs.name + (fs.isActive ? ' (active)' : ''),
        type: 'fact-sheet' as TreeNodeType,
        icon: fs.isActive ? 'auto_stories' : 'menu_book',
        children,
        data: fs
      };
    });
  }

  private buildMarkdownSubtree(entries: MarkdownEntry[]): ProjectTreeNode[] {
    // Group by directory prefix
    const dirMap = new Map<string, MarkdownEntry[]>();
    const rootFiles: MarkdownEntry[] = [];

    for (const entry of entries) {
      const slashIdx = entry.path.indexOf('/');
      if (slashIdx > 0) {
        const dir = entry.path.substring(0, slashIdx);
        if (!dirMap.has(dir)) dirMap.set(dir, []);
        dirMap.get(dir)!.push(entry);
      } else {
        rootFiles.push(entry);
      }
    }

    const nodes: ProjectTreeNode[] = [];

    // Directory folders
    for (const [dir, files] of dirMap) {
      nodes.push({
        name: dir,
        type: 'category',
        icon: 'folder',
        badge: files.length,
        children: files.map(f => ({
          name: f.title || f.path.split('/').pop() || f.path,
          type: 'markdown' as TreeNodeType,
          icon: 'article',
          children: [],
          data: f.path
        }))
      });
    }

    // Root-level files
    for (const f of rootFiles) {
      nodes.push({
        name: f.title || f.path,
        type: 'markdown',
        icon: 'article',
        children: [],
        data: f.path
      });
    }

    return nodes;
  }

  // ── Icons ────────────────────────────────────────────────────────────────

  componentIcon(type: string): string {
    const map: Record<string, string> = {
      MARKDOWN: 'description', MODEL: 'model_training', SOURCE: 'source',
      CHAT: 'chat', PROMPT: 'edit_note', PIPELINE: 'linear_scale',
      GRAPH: 'hub', CONFIG: 'settings', DATASET: 'dataset',
      ARTIFACT: 'inventory_2', MODULE: 'extension', CODE_PROJECT: 'code',
      SCRIPT: 'terminal', CRAWL: 'travel_explore', OTHER: 'category'
    };
    return map[type] || 'category';
  }

  factIcon(fact: Fact): string {
    const ext = fact.extension?.toLowerCase();
    if (ext === 'pdf') return 'picture_as_pdf';
    if (ext === 'md' || ext === 'markdown') return 'article';
    if (['doc', 'docx'].includes(ext || '')) return 'description';
    if (['xls', 'xlsx', 'csv'].includes(ext || '')) return 'table_chart';
    if (['png', 'jpg', 'jpeg', 'gif', 'svg'].includes(ext || '')) return 'image';
    return 'insert_drive_file';
  }

  indexedDocIcon(doc: IndexedDocument): string {
    if (doc.overallStatus === 'FULLY_INDEXED') return 'check_circle';
    if (doc.overallStatus === 'PARTIAL') return 'pending';
    if (doc.overallStatus === 'FAILED') return 'error';
    if (doc.overallStatus === 'OUT_OF_SYNC') return 'sync_problem';
    return 'hourglass_empty';
  }

  // ── Project operations (kept from original) ─────────────────────────────

  initializeProject(): void {
    this.saving = true;
    this.error = null;
    this.projectService.init({
      name: this.initName || null,
      description: null,
      backend: this.initBackend,
      remoteUrl: this.initRemote || null,
      branch: this.initBranch || 'main',
      installGitXet: this.initGitXetInstall,
      initializeGit: this.initGit,
      includeStandardComponents: true,
      tags: this.parseTags(this.initTags),
      modules: [],
      components: []
    }).subscribe({
      next: response => {
        this.response = response;
        this.saving = false;
        this.viewMode = 'welcome';
        this.rebuildTree();
      },
      error: err => {
        this.error = this.errorMessage(err);
        this.saving = false;
      }
    });
  }

  addComponent(): void {
    if (!this.newComponent.name.trim() || !this.newComponent.path.trim()) {
      this.error = 'Component name and path are required.';
      return;
    }
    this.saving = true;
    this.error = null;
    const component: ProjectComponent = { ...this.newComponent, tags: this.parseTags(this.newComponentTags) };
    this.projectService.addComponent(component).subscribe({
      next: response => {
        this.response = response;
        this.saving = false;
        this.newComponent = this.emptyComponent();
        this.newComponentTags = '';
        this.rebuildTree();
      },
      error: err => { this.error = this.errorMessage(err); this.saving = false; }
    });
  }

  addCodingProject(): void {
    if (!this.newCodingProject.name.trim() || !this.newCodingProject.rootPath.trim()) {
      this.error = 'Coding project name and external root path are required.';
      return;
    }
    this.saving = true;
    this.error = null;
    const codingProject: CodingProject = {
      ...this.newCodingProject,
      name: this.newCodingProject.name.trim(),
      rootPath: this.newCodingProject.rootPath.trim(),
      codeProjectId: this.newCodingProject.codeProjectId?.trim() || null,
      description: this.newCodingProject.description?.trim() || null,
      includePatterns: this.newCodingProject.includePatterns?.trim() || null,
      excludePatterns: this.newCodingProject.excludePatterns?.trim() || null,
      tags: this.parseTags(this.newCodingProjectTags)
    };
    this.projectService.registerCodingProject(codingProject).subscribe({
      next: response => {
        this.response = response;
        this.saving = false;
        this.newCodingProject = this.emptyCodingProject();
        this.newCodingProjectTags = '';
        this.rebuildTree();
      },
      error: err => { this.error = this.errorMessage(err); this.saving = false; }
    });
  }

  indexCodingProject(codingProject: CodingProject, forceReindex = false): void {
    if (!codingProject.id) { this.error = 'Coding project ID is required before indexing.'; return; }
    this.saving = true;
    this.error = null;
    this.gitOutput = null;
    this.projectService.indexCodingProject(codingProject.id, forceReindex).subscribe({
      next: result => {
        this.gitOutput = `${result.forceReindex ? 'Reindex' : 'Index'} started for ${result.projectId}.`;
        this.saving = false;
        this.load();
      },
      error: err => { this.error = this.errorMessage(err); this.saving = false; }
    });
  }

  addScript(): void {
    if (!this.newScript.name.trim() || !(this.newScript.path?.trim() || this.newScript.command?.trim())) {
      this.error = 'Script name and path or command are required.';
      return;
    }
    this.saving = true;
    this.error = null;
    const script: ProjectScript = {
      ...this.newScript,
      name: this.newScript.name.trim(),
      path: this.newScript.path?.trim() || null,
      command: this.newScript.command?.trim() || null,
      workingDirectory: this.newScript.workingDirectory?.trim() || '.',
      phase: this.newScript.phase?.trim() || 'run',
      platform: this.newScript.platform?.trim() || 'any',
      tags: this.parseTags(this.newScriptTags)
    };
    this.projectService.registerScript(script).subscribe({
      next: response => {
        this.response = response;
        this.saving = false;
        this.newScript = this.emptyScript();
        this.newScriptTags = '';
        this.rebuildTree();
      },
      error: err => { this.error = this.errorMessage(err); this.saving = false; }
    });
  }

  addCrawlProfile(): void {
    const sources = this.parseTags(this.newCrawlSources);
    if (sources.length === 0) { this.error = 'At least one crawl source is required.'; return; }
    this.saving = true;
    this.error = null;
    const profile: CrawlProfile = {
      ...this.newCrawlProfile,
      sources,
      includePatterns: this.parseTags(this.newCrawlIncludes),
      excludePatterns: this.parseTags(this.newCrawlExcludes),
      tags: this.parseTags(this.newCrawlTags),
      schemaPresetId: this.newCrawlProfile.schemaPresetId?.trim() || null,
      graphSchemaMode: this.newCrawlProfile.graphSchemaMode?.trim() || null,
      graphExtraction: this.newCrawlProfile.graphExtraction || !!this.newCrawlProfile.schemaPresetId?.trim()
    };
    this.projectService.registerCrawlProfile(profile).subscribe({
      next: response => {
        this.response = response;
        this.saving = false;
        this.newCrawlProfile = this.emptyCrawlProfile();
        this.newCrawlSources = '';
        this.newCrawlIncludes = '';
        this.newCrawlExcludes = '';
        this.newCrawlTags = '';
        this.rebuildTree();
      },
      error: err => { this.error = this.errorMessage(err); this.saving = false; }
    });
  }

  addWorkflow(): void {
    if (!this.newWorkflow.name.trim()) { this.error = 'Workflow name is required.'; return; }
    if (!this.newWorkflowStep.ref?.trim() && !this.newWorkflowStep.command?.trim() && !this.newWorkflowStep.url?.trim()) {
      this.error = 'Workflow step needs a ref, command, or URL.';
      return;
    }
    this.saving = true;
    this.error = null;
    const step: WorkflowStep = {
      ...this.newWorkflowStep,
      type: this.newWorkflowStep.type.trim() || 'COMMAND',
      ref: this.newWorkflowStep.ref?.trim() || null,
      command: this.newWorkflowStep.command?.trim() || null,
      url: this.newWorkflowStep.url?.trim() || null,
      method: this.newWorkflowStep.method?.trim() || 'GET',
      body: this.newWorkflowStep.body?.trim() || null,
      continueOnFailure: this.newWorkflowStep.continueOnFailure
    };
    const workflow: ProjectWorkflow = {
      ...this.newWorkflow,
      name: this.newWorkflow.name.trim(),
      phase: this.newWorkflow.phase?.trim() || 'run',
      tags: this.parseTags(this.newWorkflowTags),
      steps: [step]
    };
    this.projectService.registerWorkflow(workflow).subscribe({
      next: response => {
        this.response = response;
        this.saving = false;
        this.newWorkflow = this.emptyWorkflow();
        this.newWorkflowStep = this.emptyWorkflowStep();
        this.newWorkflowTags = '';
        this.rebuildTree();
      },
      error: err => { this.error = this.errorMessage(err); this.saving = false; }
    });
  }

  setLifecycle(lifecycle: ProjectLifecycle): void {
    this.saving = true;
    this.projectService.setLifecycle(lifecycle).subscribe({
      next: response => { this.response = response; this.saving = false; },
      error: err => { this.error = this.errorMessage(err); this.saving = false; }
    });
  }

  registerMarkdownAsFacts(): void {
    this.saving = true;
    this.error = null;
    this.projectService.registerCrawlMarkdownAsFacts().subscribe({
      next: result => {
        this.gitOutput = `Registered ${result.registered} markdown file(s) as facts.`;
        this.saving = false;
        this.loadFactSheets();
      },
      error: err => { this.error = this.errorMessage(err); this.saving = false; }
    });
  }

  commit(): void { this.runGitAction(this.projectService.commit('Update Kompile project')); }
  pull(): void { this.runGitAction(this.projectService.pull()); }
  push(): void { this.runGitAction(this.projectService.push()); }

  statusLabel(value: boolean): string { return value ? 'Yes' : 'No'; }
  hasTags(tags: string[] | null | undefined): boolean { return !!tags && tags.length > 0; }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private runGitAction(request: ReturnType<ProjectService['pull']>): void {
    this.saving = true;
    this.error = null;
    this.gitOutput = null;
    request.subscribe({
      next: (result: GitResult) => {
        this.gitOutput = result.output?.trim() || `Git command exited with ${result.exitCode}.`;
        this.saving = false;
        this.load();
      },
      error: err => { this.error = this.errorMessage(err); this.saving = false; }
    });
  }

  private parseTags(value: string): string[] {
    return value.split(',').map(tag => tag.trim()).filter(tag => tag.length > 0);
  }

  private emptyComponent(): ProjectComponent {
    return { type: 'SOURCE', name: '', path: 'data/sources/', description: '', storageBackend: 'GIT', tags: [] };
  }

  private emptyCodingProject(): CodingProject {
    return { name: '', rootPath: '', codeProjectId: '', description: '', includePatterns: '', excludePatterns: '', autoIndex: false, lifecycle: 'ACTIVE', tags: [] };
  }

  private emptyScript(): ProjectScript {
    return { name: '', path: '', command: '', workingDirectory: '.', phase: 'run', platform: 'any', generated: false, lifecycle: 'ACTIVE', tags: [] };
  }

  private emptyCrawlProfile(): CrawlProfile {
    return {
      name: '', sources: [], maxDepth: 3, maxDocuments: 0, sameDomain: true, robots: true,
      delayMs: 500, timeoutMin: 60, includePatterns: [], excludePatterns: [], contentTypes: [],
      multimodal: false, graphExtraction: false, graphEntityTypes: [], graphRelationTypes: [],
      graphLocal: false, graphAutoStart: false, followLinks: false, includeHidden: false,
      watch: false, lifecycle: 'ACTIVE', tags: []
    };
  }

  private emptyWorkflow(): ProjectWorkflow {
    return { name: '', phase: 'run', generated: false, lifecycle: 'ACTIVE', steps: [], tags: [] };
  }

  private emptyWorkflowStep(): WorkflowStep {
    return { name: '', type: 'CRAWL', ref: '', command: '', url: '', method: 'GET', body: '', continueOnFailure: false };
  }

  private errorMessage(err: unknown): string {
    if (typeof err === 'object' && err !== null && 'error' in err) {
      const body = (err as { error?: { error?: string } | string }).error;
      if (typeof body === 'string') return body;
      if (body?.error) return body.error;
    }
    return 'Project operation failed.';
  }
}
