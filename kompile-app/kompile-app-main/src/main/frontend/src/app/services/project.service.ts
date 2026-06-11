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
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService, backendUrl } from './base.service';

export type ProjectLifecycle = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ARCHIVED' | 'DEPRECATED';
export type ProjectStorageBackend = 'LOCAL' | 'GIT' | 'GIT_XET' | 'EXTERNAL';
export type ProjectComponentType =
  'MARKDOWN' | 'MODEL' | 'SOURCE' | 'CHAT' | 'PROMPT' | 'PIPELINE' |
  'GRAPH' | 'CONFIG' | 'DATASET' | 'ARTIFACT' | 'MODULE' |
  'CODE_PROJECT' | 'SCRIPT' | 'CRAWL' | 'OTHER';

export interface ProjectRepository {
  backend: ProjectStorageBackend;
  remoteUrl?: string | null;
  branch: string;
  autoCommit: boolean;
  remoteSyncEnabled: boolean;
  gitXetEnabled: boolean;
  metadata: Record<string, string>;
}

export interface ProjectComponent {
  id?: string | null;
  type: ProjectComponentType;
  name: string;
  path: string;
  description?: string | null;
  storageBackend: ProjectStorageBackend;
  lifecycle?: ProjectLifecycle;
  tags: string[];
  metadata?: Record<string, string>;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface CodingProject {
  id?: string | null;
  codeProjectId?: string | null;
  name: string;
  rootPath: string;
  contextPath?: string | null;
  agentsMdPath?: string | null;
  chatsPath?: string | null;
  description?: string | null;
  includePatterns?: string | null;
  excludePatterns?: string | null;
  autoIndex: boolean;
  lifecycle?: ProjectLifecycle;
  tags: string[];
  metadata?: Record<string, string>;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface ProjectScript {
  id?: string | null;
  name: string;
  path?: string | null;
  command?: string | null;
  workingDirectory?: string | null;
  description?: string | null;
  phase?: string | null;
  platform?: string | null;
  generated: boolean;
  lifecycle?: ProjectLifecycle;
  tags: string[];
  metadata?: Record<string, string>;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface CrawlProfile {
  id?: string | null;
  name?: string | null;
  description?: string | null;
  sources: string[];
  maxDepth: number;
  maxDocuments: number;
  sameDomain: boolean;
  robots: boolean;
  delayMs: number;
  timeoutMin: number;
  includePatterns: string[];
  excludePatterns: string[];
  contentTypes: string[];
  chunker?: string | null;
  loader?: string | null;
  collection?: string | null;
  multimodal: boolean;
  vlmModel?: string | null;
  graphExtraction: boolean;
  graphEntityTypes: string[];
  graphRelationTypes: string[];
  graphModelProvider?: string | null;
  graphModelName?: string | null;
  graphTemperature?: number | null;
  graphMinConfidence?: number | null;
  graphAutoAccept?: boolean | null;
  graphAutoAcceptThreshold?: number | null;
  graphSchemaMode?: string | null;
  schemaPresetId?: string | null;
  graphCustomPrompt?: string | null;
  graphLocal: boolean;
  graphAutoStart: boolean;
  followLinks: boolean;
  includeHidden: boolean;
  sourceType?: string | null;
  watch: boolean;
  factSheetName?: string | null;
  lifecycle?: ProjectLifecycle;
  tags: string[];
  metadata?: Record<string, string>;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface WorkflowStep {
  id?: string | null;
  name?: string | null;
  type: string;
  ref?: string | null;
  command?: string | null;
  workingDirectory?: string | null;
  url?: string | null;
  method?: string | null;
  body?: string | null;
  expectedStatus?: number | null;
  timeoutSeconds?: number | null;
  waitSeconds?: number | null;
  continueOnFailure: boolean;
  environment?: Record<string, string>;
  metadata?: Record<string, string>;
}

export interface ProjectWorkflow {
  id?: string | null;
  name: string;
  description?: string | null;
  phase?: string | null;
  generated: boolean;
  lifecycle?: ProjectLifecycle;
  steps: WorkflowStep[];
  tags: string[];
  metadata?: Record<string, string>;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface ProjectManifest {
  schemaVersion: number;
  projectId: string;
  name: string;
  description?: string | null;
  lifecycle: ProjectLifecycle;
  tags: string[];
  repository: ProjectRepository;
  modules: string[];
  components: ProjectComponent[];
  codingProjects: CodingProject[];
  models: ProjectModel[];
  pipelines: ProjectPipeline[];
  scripts: ProjectScript[];
  crawlProfiles: CrawlProfile[];
  workflows: ProjectWorkflow[];
  metadata: Record<string, string>;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface ProjectStatus {
  root: string;
  manifestPath: string;
  manifestPresent: boolean;
  gitRepository: boolean;
  gitDirty: boolean;
  branch?: string | null;
  remoteUrl?: string | null;
  gitXetAvailable: boolean;
  gitXetEnabled: boolean;
  componentCount: number;
  codingProjectCount: number;
  scriptCount: number;
  crawlProfileCount: number;
  workflowCount: number;
  markdownCount: number;
  crawlResultCount: number;
  sourceDocumentCount: number;
  promptTemplateCount: number;
  factSheetCount: number;
  chatSessionCount: number;
  noteSyncConnectionCount: number;
  indexedDocumentCount: number;
}

export interface ProjectResponse {
  manifest: ProjectManifest | null;
  status: ProjectStatus;
}

export interface ProjectInitRequest {
  name?: string | null;
  description?: string | null;
  backend: ProjectStorageBackend;
  remoteUrl?: string | null;
  branch: string;
  installGitXet: boolean;
  initializeGit: boolean;
  includeStandardComponents: boolean;
  tags: string[];
  modules: string[];
  components: ProjectComponent[];
}

export interface GitResult {
  exitCode: number;
  output: string;
}

export interface MarkdownEntry {
  path: string;
  title: string | null;
  tags: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  body: string | null;
  source: string | null;
  sourcePath: string | null;
  contentType: string | null;
  converter: string | null;
  crawlProfile: string | null;
  factSheet: string | null;
  collection: string | null;
  project: string | null;
}

export interface CrawlResult {
  profileId: string;
  name: string | null;
  status: string;
  finishedAt: string | null;
  loader: string | null;
  chunker: string | null;
  collection: string | null;
  factSheetName: string | null;
  markdownPath: string | null;
  documentCount: number;
  markdownCount: number;
  chunkCount: number;
}

export interface SourceDocument {
  path: string;
  fileName: string;
  sizeBytes: number;
  contentType: string | null;
  lastModified: string | null;
}

export interface PromptTemplate {
  id: string | null;
  name: string;
  displayName: string | null;
  description: string | null;
  category: string | null;
  enabled: boolean;
  builtIn: boolean;
  tags: string[];
  createdAt: string | null;
  updatedAt: string | null;
}

export interface FactSheet {
  id: string | null;
  name: string;
  description: string | null;
  active: boolean;
  color: string | null;
  icon: string | null;
  vectorStorePath: string | null;
  keywordIndexPath: string | null;
  embeddingModel: string | null;
  embeddingModelSource: string | null;
  rerankingEnabled: boolean;
  rerankerType: string | null;
  enableGraphBuilding: boolean;
  graphBuilderType: string | null;
  graphStorageType: string | null;
  factCount: number;
  indexedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ChatSession {
  sessionId: string;
  title: string | null;
  source: string | null;
  factSheetName: string | null;
  codeProjectId: string | null;
  messageCount: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface NoteSyncConnection {
  id: string | null;
  provider: string | null;
  factSheetName: string | null;
  externalScope: string | null;
  direction: string | null;
  enabled: boolean;
  repositoryUrl: string | null;
  gitBranch: string | null;
  lastSyncAt: string | null;
  lastSyncStatus: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface IndexedDocument {
  id: number | null;
  sourceId: string;
  fileName: string | null;
  checksum: string | null;
  factSheetName: string | null;
  keywordIndexStatus: string | null;
  keywordPassageCount: number;
  vectorStoreStatus: string | null;
  vectorPassageCount: number;
  graphStatus: string | null;
  graphNodeCount: number;
  overallStatus: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ProjectModel {
  id: string | null;
  role: string;
  modelId: string;
  version: string | null;
  source: string | null;
  sourceRepository: string | null;
  sourceRevision: string | null;
  path: string | null;
  registryModelId: string | null;
  required: boolean;
  lifecycle: ProjectLifecycle | null;
  tags: string[];
  metadata: Record<string, string>;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ProjectPipeline {
  id: string | null;
  pipelineId: string | null;
  name: string;
  role: string | null;
  version: string | null;
  definitionPath: string | null;
  registryPath: string | null;
  active: boolean;
  required: boolean;
  modelRefs: string[];
  lifecycle: ProjectLifecycle | null;
  tags: string[];
  metadata: Record<string, string>;
  createdAt: string | null;
  updatedAt: string | null;
}

@Injectable({ providedIn: 'root' })
export class ProjectService extends BaseService {
  constructor(private http: HttpClient) {
    super();
  }

  current(): Observable<ProjectResponse> {
    return this.http.get<ProjectResponse>(`${backendUrl}/projects/current`);
  }

  init(request: ProjectInitRequest): Observable<ProjectResponse> {
    return this.http.post<ProjectResponse>(`${backendUrl}/projects/current/init`, request);
  }

  addComponent(component: ProjectComponent): Observable<ProjectResponse> {
    return this.http.post<ProjectResponse>(`${backendUrl}/projects/current/components`, component);
  }

  registerCodingProject(project: CodingProject): Observable<ProjectResponse> {
    return this.http.post<ProjectResponse>(`${backendUrl}/projects/current/code-projects`, project);
  }

  registerScript(script: ProjectScript): Observable<ProjectResponse> {
    return this.http.post<ProjectResponse>(`${backendUrl}/projects/current/scripts`, script);
  }

  registerCrawlProfile(profile: CrawlProfile): Observable<ProjectResponse> {
    return this.http.post<ProjectResponse>(`${backendUrl}/projects/current/crawl-profiles`, profile);
  }

  registerWorkflow(workflow: ProjectWorkflow): Observable<ProjectResponse> {
    return this.http.post<ProjectResponse>(`${backendUrl}/projects/current/workflows`, workflow);
  }

  indexCodingProject(codingProjectId: string, forceReindex = false): Observable<{ projectId: string; indexing: boolean; forceReindex: boolean }> {
    return this.http.post<{ projectId: string; indexing: boolean; forceReindex: boolean }>(
      `${backendUrl}/projects/current/code-projects/${codingProjectId}/index`,
      { forceReindex }
    );
  }

  setProjectTags(tags: string[]): Observable<ProjectResponse> {
    return this.http.put<ProjectResponse>(`${backendUrl}/projects/current/tags`, { tags });
  }

  setComponentTags(componentId: string, tags: string[]): Observable<ProjectResponse> {
    return this.http.put<ProjectResponse>(`${backendUrl}/projects/current/components/${componentId}/tags`, { tags });
  }

  setLifecycle(lifecycle: ProjectLifecycle): Observable<ProjectResponse> {
    return this.http.put<ProjectResponse>(`${backendUrl}/projects/current/lifecycle`, { lifecycle });
  }

  commit(message: string): Observable<GitResult> {
    return this.http.post<GitResult>(`${backendUrl}/projects/current/git/commit`, { message });
  }

  pull(): Observable<GitResult> {
    return this.http.post<GitResult>(`${backendUrl}/projects/current/git/pull`, {});
  }

  push(): Observable<GitResult> {
    return this.http.post<GitResult>(`${backendUrl}/projects/current/git/push`, {});
  }

  listMarkdown(): Observable<MarkdownEntry[]> {
    return this.http.get<MarkdownEntry[]>(`${backendUrl}/projects/current/markdown`);
  }

  readMarkdown(path: string): Observable<MarkdownEntry> {
    return this.http.get<MarkdownEntry>(`${backendUrl}/projects/current/markdown/read`, {
      params: { path }
    });
  }

  searchMarkdown(query: string): Observable<MarkdownEntry[]> {
    return this.http.get<MarkdownEntry[]>(`${backendUrl}/projects/current/markdown/search`, {
      params: { q: query }
    });
  }

  registerCrawlMarkdownAsFacts(factSheetName?: string | null): Observable<{ registered: number; files: string[] }> {
    return this.http.post<{ registered: number; files: string[] }>(
      `${backendUrl}/projects/current/markdown/register-facts`,
      { factSheetName: factSheetName || null }
    );
  }

  listCrawlResults(): Observable<CrawlResult[]> {
    return this.http.get<CrawlResult[]>(`${backendUrl}/projects/current/crawl-results`);
  }

  listSourceDocuments(): Observable<SourceDocument[]> {
    return this.http.get<SourceDocument[]>(`${backendUrl}/projects/current/sources`);
  }

  listPromptTemplates(): Observable<PromptTemplate[]> {
    return this.http.get<PromptTemplate[]>(`${backendUrl}/projects/current/prompt-templates`);
  }

  listFactSheets(): Observable<FactSheet[]> {
    return this.http.get<FactSheet[]>(`${backendUrl}/projects/current/fact-sheets`);
  }

  listChatSessions(): Observable<ChatSession[]> {
    return this.http.get<ChatSession[]>(`${backendUrl}/projects/current/chat-sessions`);
  }

  listNoteSyncConnections(): Observable<NoteSyncConnection[]> {
    return this.http.get<NoteSyncConnection[]>(`${backendUrl}/projects/current/note-sync`);
  }

  listIndexedDocuments(): Observable<IndexedDocument[]> {
    return this.http.get<IndexedDocument[]>(`${backendUrl}/projects/current/indexed-documents`);
  }

  listScripts(): Observable<ProjectScript[]> {
    return this.http.get<ProjectScript[]>(`${backendUrl}/projects/current/scripts`);
  }

  listWorkflows(): Observable<ProjectWorkflow[]> {
    return this.http.get<ProjectWorkflow[]>(`${backendUrl}/projects/current/workflows`);
  }

  listModels(): Observable<ProjectModel[]> {
    return this.http.get<ProjectModel[]>(`${backendUrl}/projects/current/models`);
  }

  listPipelines(): Observable<ProjectPipeline[]> {
    return this.http.get<ProjectPipeline[]>(`${backendUrl}/projects/current/pipelines`);
  }

  listCodingProjects(): Observable<CodingProject[]> {
    return this.http.get<CodingProject[]>(`${backendUrl}/projects/current/code-projects`);
  }

  listCrawlProfiles(): Observable<CrawlProfile[]> {
    return this.http.get<CrawlProfile[]>(`${backendUrl}/projects/current/crawl-profiles`);
  }
}
