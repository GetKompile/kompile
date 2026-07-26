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

import { Component, Inject, OnInit, OnDestroy, Optional } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';

// Angular Material
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTooltipModule } from '@angular/material/tooltip';

import { FactSheet } from '../../../models/api-models';
import { GraphExtractionService, GraphExtractionConfig, ModelProvider } from '../../../services/graph-extraction.service';
import {
  UnifiedCrawlService,
  UnifiedCrawlRequest,
  UnifiedCrawlSource,
  AvailableSourceType,
  PipelineStepCatalogEntry,
  PreprocessingConfig,
  RuntimeConfig
} from '../../../services/unified-crawl.service';
import { FactSheetService } from '../../../services/fact-sheet.service';
import { DistributedCrawlService } from '../../../services/distributed-crawl.service';
import { ModelFallbackPanelComponent } from '../model-fallback-panel/model-fallback-panel.component';
import { ChipListInputComponent } from './chip-list-input.component';
import { KeyValueEditorComponent, KvRow } from './key-value-editor.component';

/**
 * Optional context from the host. Every field is optional — when omitted the dialog loads the
 * catalog itself, so any component can open it with no data (or just a {@link seedPath} prefill).
 */
export interface CrawlLauncherDialogData {
  sourceTypes?: AvailableSourceType[];
  graphModelProviders?: ModelProvider[];
  stepCatalog?: PipelineStepCatalogEntry[];
  clusterWorkerCount?: number;
  activeFactSheet?: FactSheet | null;
  extractionAgentLabel?: string | null;
  /** Optional seed URL/path to prefill the first source (e.g. from the Crawlers quick form). */
  seedPath?: string;
}

/** Result returned to the host on "Start Crawl". */
export interface CrawlLauncherResult {
  request: UnifiedCrawlRequest;
  distribute: boolean;
}

type TriState = 'default' | 'on' | 'off';

interface EditableUnifiedCrawlSource {
  label: string;
  sourceType: string;
  pathOrUrl: string;
  maxDepth: number;
  maxDocuments: number;
  includePatterns: string[];
  excludePatterns: string[];
  allowedContentTypes: string[];
  propertyRows: KvRow[];
}

/** Editable form model mirroring the backend {@code IngestPipelineDefinition}. */
interface EditablePipeline {
  pipelineId: string;
  displayName: string;
  pipelineType: string;
  loaderName: string;
  chunkerName: string;
  embeddingModelName: string;
  language: string;
  chunkSize: number | null;
  chunkOverlap: number | null;
  keywordOnly: boolean;
  enableVlm: boolean;
  enableGraphExtraction: boolean;
  processingMode: string;
  collectionName: string;
  extractionEntityTypes: string[];
  extractionRelationshipTypes: string[];
  extractionPromptTemplate: string;
  extractionLlmProvider: string;
  extractionModelName: string;
  extractionTemperature: number | null;
  extractionMaxTokens: number | null;
  maxChunkChars: number | null;
  options: KvRow[];
}

/** Editable form model mirroring the backend {@code ContentRouteRule}. */
interface EditableRouteRule {
  pipelineId: string;
  priority: number;
  contentTypes: string[];
  fileExtensions: string[];
  urlPatterns: string[];
  sourceTypes: string[];
  minSizeBytes: number | null;
  maxSizeBytes: number | null;
  languages: string[];
  minLanguageConfidence: number | null;
  tags: string[];
  contentPatterns: string[];
  metadataMatchers: KvRow[];
  minPages: number | null;
  maxPages: number | null;
}

/**
 * Full-surface crawl launcher modal. Every field on {@link UnifiedCrawlRequest} that the
 * unified-crawl pipeline consumes is exposed here with a real, structured control — sources,
 * graph extraction, vector indexing, processing routes, document preprocessing, graph enrichment
 * (hydration), runtime performance tuning, per-step run/archive/skip selection, and custom ingest
 * pipelines / content routing rules. The dialog assembles the request and hands it back to the host.
 */
@Component({
  selector: 'app-crawl-launcher-dialog',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatDialogModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatSlideToggleModule, MatExpansionModule, MatTooltipModule,
    ModelFallbackPanelComponent, ChipListInputComponent, KeyValueEditorComponent
  ],
  templateUrl: './crawl-launcher-dialog.component.html',
  styleUrls: ['./crawl-launcher-dialog.component.css']
})
export class CrawlLauncherDialogComponent implements OnInit, OnDestroy {

  private readonly subs = new Subscription();

  // ── Static option lists ──────────────────────────────────────────────────
  readonly pipelineTypes = ['STANDARD_TEXT', 'VLM', 'OCR', 'CODE', 'TABLE_AWARE', 'KEYWORD_ONLY', 'CUSTOM'];

  // ── Catalogs (from host) ─────────────────────────────────────────────────
  availableSourceTypes: AvailableSourceType[] = [];
  graphModelProviders: ModelProvider[] = [];
  stepCatalog: PipelineStepCatalogEntry[] = [];
  clusterWorkerCount = 0;

  /** Validation error surfaced inline above the action bar. */
  error: string | null = null;

  // ── General ──────────────────────────────────────────────────────────────
  jobName = '';
  maxValidationRetries = 2;

  // ── Sources ──────────────────────────────────────────────────────────────
  sources: EditableUnifiedCrawlSource[] = [];

  // ── Graph extraction ─────────────────────────────────────────────────────
  graphEnabled = true;
  graphLlmProvider = 'default';
  graphModelName = '';
  graphAvailableModels: { id: string; name: string }[] = [];
  graphSchemaPresetId = 'fpna-cpg-channel-v1';
  graphEntityTypes: string[] = ['PERSON', 'ORGANIZATION', 'CONCEPT', 'TECHNOLOGY'];
  graphRelTypes: string[] = [];
  graphSchemaMode = 'LENIENT';
  graphMinConfidence = 0.5;
  graphTemperature = 0.0;
  graphMaxTokens = 4096;
  graphCustomPrompt = '';
  graphEntityResolution = true;
  graphEntityResolutionSimilarityThreshold = 0.85;
  graphEntityResolutionUseEmbeddings = true;
  graphEntityResolutionEmbeddingThreshold = 0.88;
  graphModelProviderAllow: string[] = [];
  graphModelExcludeMarkers: string[] = [];

  // ── Vector indexing ──────────────────────────────────────────────────────
  indexEnabled = true;
  indexCollectionName = '';
  chunkerName = '';
  chunkSize: number | null = null;
  chunkOverlap: number | null = null;
  embeddingBatchSize: number | null = null;
  maxEmbeddingBatchSize: number | null = null;
  adaptiveBatching = true;

  // ── Processing routes ────────────────────────────────────────────────────
  processingRouteEnabled = false;
  pdfRoutingMode: 'AUTO' | 'FORCE_VLM' | 'FORCE_TEXT' | 'DISABLED' = 'AUTO';
  extractTablesFromTextPdfs = true;
  vlmModelId = '';
  textThresholdCharsPerPage: number | null = null;
  fallbackEnabled = false;
  backends: any[] = [];

  // ── Preprocessing ────────────────────────────────────────────────────────
  preprocessingEnabled = false;
  ppLlmProvider = 'default';
  ppLlmModelName = '';
  ppParallelism: number | null = null;
  // Translation
  tEnabled = false;
  tTargetLanguage = 'en';
  tSourceLanguage = '';
  tPreserveOriginal = true;
  tDualIndex = false;
  tDetectionConfidenceThreshold = 0.7;
  tMaxCharsPerRequest = 8000;
  tDomainHint = '';
  tPreserveTerms: string[] = [];
  tCustomInstructions = '';
  // Language detection
  ldEnabled = false;
  ldMinTextLength = 50;
  ldLlmFallback = false;
  ldForceLanguage = '';
  // Unicode normalization
  unEnabled = false;
  unForm = 'NFC';
  unFixMojibake = true;
  unStandardizeTypography = true;
  // Script transliteration
  stEnabled = false;
  stTargetScript = 'Latin';
  stSourceScript = '';
  stPreserveOriginal = true;
  // PII redaction
  piiEnabled = false;
  piiEntityTypes: string[] = ['PERSON', 'EMAIL', 'PHONE', 'SSN'];
  piiReplacementStrategy = 'TYPE_TAG';
  piiUseLlm = true;
  piiLogCounts = true;
  // Boilerplate removal
  brEnabled = false;
  brRemoveWeb = true;
  brRemoveEmailSig = true;
  brRemoveLegal = true;
  brCustomPatterns: string[] = [];
  brMinRemainingChars = 50;
  // Deduplication
  dedupEnabled = false;
  dedupSimilarityThreshold = 0.95;
  dedupStrategy = 'KEEP_FIRST';
  dedupAlgorithm = 'SIMHASH';
  dedupTrackRelations = true;
  // Date/number normalization
  dnEnabled = false;
  dnDateFormat = 'ISO8601';
  dnNumberLocale = 'en-US';
  dnNormalizeCurrency = true;
  dnNormalizeUnits = false;
  // Terminology standardization
  tsEnabled = false;
  tsGlossary: KvRow[] = [];
  tsGlossaryFile = '';
  tsCaseSensitive = false;
  tsExpandAbbreviations = true;
  tsContextAware = false;

  // ── Graph enrichment (hydration) ─────────────────────────────────────────
  hydrationCustomize = false;
  hydrationDerivation = true;
  hydrationPruneCompact = true;
  hydrationHealth = true;
  hydrationConfidencePruneThreshold = 0.4;
  hydrationDryRun = false;

  // ── Runtime / performance tuning ─────────────────────────────────────────
  runtimeTuningEnabled = false;
  rtGraphExtractionParallelism: number | null = null;
  rtGraphExtractionRemoteParallelism: number | null = null;
  rtGraphExtractionBatchSize: number | null = null;
  rtGraphExtractionTargetCharsPerBatch: number | null = null;
  rtGraphExtractionMaxItemsPerBatch: number | null = null;
  rtGraphExtractionBatchTimeoutSeconds: number | null = null;
  rtLlmCallTimeoutSeconds: number | null = null;
  rtSourceLoadParallelism: number | null = null;
  rtChunkingParallelism: number | null = null;
  rtEntityResolutionBatchSize: number | null = null;
  rtEdgeComputationParallelism: number | null = null;
  rtVectorIndexingParallelism: number | null = null;
  rtVectorBatchSize: number | null = null;
  rtCostSortChunks: TriState = 'default';
  rtParallelVectorAndGraph: TriState = 'default';
  rtIncrementalByContentHash: TriState = 'default';
  rtForceFullRecrawl: TriState = 'default';
  rtClearGraphBeforeRun: TriState = 'default';

  // ── Pipeline steps ───────────────────────────────────────────────────────
  stepSelections: { [stepId: string]: 'run' | 'archive' | 'skip' } = {};

  // ── Advanced: custom ingest pipelines + routing ──────────────────────────
  defaultPipelineId = '';
  pipelines: EditablePipeline[] = [];
  routeRules: EditableRouteRule[] = [];

  // ── Distribution ─────────────────────────────────────────────────────────
  distributeAcrossWorkers = false;

  // ── Display-only context ─────────────────────────────────────────────────
  activeFactSheet: FactSheet | null = null;
  extractionAgentLabel: string | null = null;

  constructor(
    public dialogRef: MatDialogRef<CrawlLauncherDialogComponent, CrawlLauncherResult>,
    private crawlService: UnifiedCrawlService,
    private graphExtractionService: GraphExtractionService,
    private factSheetService: FactSheetService,
    private distributedCrawlService: DistributedCrawlService,
    @Optional() @Inject(MAT_DIALOG_DATA) public data: CrawlLauncherDialogData | null
  ) {
    const d = this.data || {};
    this.availableSourceTypes = d.sourceTypes || [];
    this.graphModelProviders = d.graphModelProviders || [];
    this.stepCatalog = d.stepCatalog || [];
    this.clusterWorkerCount = d.clusterWorkerCount || 0;
    this.activeFactSheet = d.activeFactSheet ?? null;
    this.extractionAgentLabel = d.extractionAgentLabel ?? null;
    for (const s of this.stepCatalog) {
      this.stepSelections[s.id] = 'run';
    }
    this.onGraphLlmProviderChange();
    if (this.sources.length === 0) {
      this.addSource();
    }
    if (d.seedPath && d.seedPath.trim()) {
      this.prefillSeed(d.seedPath.trim());
    }
  }

  /**
   * Self-load any catalog the host did not already provide via {@link data}, so the dialog works
   * standalone from any component (e.g. the Crawlers tab) without that host duplicating the loads.
   */
  ngOnInit(): void {
    const provided = this.data || {};

    if (this.availableSourceTypes.length === 0) {
      this.subs.add(this.crawlService.getSourceTypes().subscribe({
        next: (types) => { this.availableSourceTypes = (types && types.length) ? types : this.defaultSourceTypes(); },
        error: () => { this.availableSourceTypes = this.defaultSourceTypes(); }
      }));
    }
    if (this.graphModelProviders.length === 0) {
      this.subs.add(this.graphExtractionService.getModelProviders().subscribe({
        next: (providers) => { this.graphModelProviders = providers || []; this.onGraphLlmProviderChange(); },
        error: () => { /* non-fatal: provider list stays empty, model is a free-text input */ }
      }));
    }
    if (this.stepCatalog.length === 0) {
      this.subs.add(this.crawlService.getStepCatalog().subscribe({
        next: (catalog) => {
          this.stepCatalog = catalog || [];
          for (const s of this.stepCatalog) {
            if (!(s.id in this.stepSelections)) this.stepSelections[s.id] = 'run';
          }
        },
        error: () => { /* non-fatal: the Pipeline Steps panel simply does not render */ }
      }));
    }
    if (provided.clusterWorkerCount === undefined) {
      this.subs.add(this.distributedCrawlService.liveWorkers().subscribe({
        next: (workers) => { this.clusterWorkerCount = Array.isArray(workers) ? workers.length : 0; },
        error: () => { this.clusterWorkerCount = 0; }
      }));
    }
    if (!this.activeFactSheet) {
      this.subs.add(this.factSheetService.activeSheet$.subscribe(sheet => { this.activeFactSheet = sheet; }));
      this.subs.add(this.factSheetService.loadActiveSheet().subscribe({ error: () => { /* non-fatal */ } }));
    }
    if (!this.extractionAgentLabel) {
      this.subs.add(this.graphExtractionService.getConfig().subscribe({
        next: (cfg: GraphExtractionConfig) => {
          const provider = (cfg?.extractionModelProvider || '').trim() || 'default';
          const model = (cfg?.extractionModelName || '').trim() || 'default';
          this.extractionAgentLabel = `${provider} / ${model}`;
        },
        error: () => { /* non-fatal: leave the label hidden */ }
      }));
    }
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
  }

  /** Prefill the first source from a seed URL/path (carried in from the Crawlers quick form). */
  private prefillSeed(seed: string): void {
    const first = this.sources[0];
    if (!first) return;
    first.pathOrUrl = seed;
    const looksLikeUrl = /^https?:\/\//i.test(seed);
    first.sourceType = looksLikeUrl ? 'WEB_CRAWL' : 'DIRECTORY';
    if (!first.label) first.label = looksLikeUrl ? 'Web crawl' : 'Local source';
  }

  /** Minimal fallback source-type list when the catalog endpoint is unavailable. */
  private defaultSourceTypes(): AvailableSourceType[] {
    const t = (type: string, displayName: string): AvailableSourceType =>
      ({ type, displayName, description: '', available: true, requiredProperties: [], optionalProperties: [] });
    return [
      t('DIRECTORY', 'Local Directory'),
      t('FILE', 'Single File'),
      t('WEB_CRAWL', 'Web Crawl'),
      t('URL', 'Web URL')
    ];
  }

  // ── Sources ──────────────────────────────────────────────────────────────

  addSource(): void {
    this.sources.push({
      label: '',
      sourceType: 'DIRECTORY',
      pathOrUrl: '',
      maxDepth: 3,
      maxDocuments: 0,
      includePatterns: [],
      excludePatterns: [],
      allowedContentTypes: [],
      propertyRows: []
    });
  }

  removeSource(index: number): void {
    this.sources.splice(index, 1);
  }

  // ── Processing-route backends ────────────────────────────────────────────

  addBackend(): void {
    this.backends.push({
      id: '',
      type: 'LOCAL_MODEL',
      priority: (this.backends.length + 1) * 10,
      maxConcurrent: 1,
      requestsPerMinute: 0,
      enabled: true
    });
  }

  removeBackend(index: number): void {
    this.backends.splice(index, 1);
  }

  // ── Custom ingest pipelines ──────────────────────────────────────────────

  addPipeline(): void {
    this.pipelines.push({
      pipelineId: '',
      displayName: '',
      pipelineType: 'STANDARD_TEXT',
      loaderName: '',
      chunkerName: '',
      embeddingModelName: '',
      language: '',
      chunkSize: null,
      chunkOverlap: null,
      keywordOnly: false,
      enableVlm: false,
      enableGraphExtraction: false,
      processingMode: '',
      collectionName: '',
      extractionEntityTypes: [],
      extractionRelationshipTypes: [],
      extractionPromptTemplate: '',
      extractionLlmProvider: '',
      extractionModelName: '',
      extractionTemperature: null,
      extractionMaxTokens: null,
      maxChunkChars: null,
      options: []
    });
  }

  removePipeline(index: number): void {
    this.pipelines.splice(index, 1);
  }

  // ── Content routing rules ────────────────────────────────────────────────

  addRouteRule(): void {
    this.routeRules.push({
      pipelineId: '',
      priority: 100,
      contentTypes: [],
      fileExtensions: [],
      urlPatterns: [],
      sourceTypes: [],
      minSizeBytes: null,
      maxSizeBytes: null,
      languages: [],
      minLanguageConfidence: null,
      tags: [],
      contentPatterns: [],
      metadataMatchers: [],
      minPages: null,
      maxPages: null
    });
  }

  removeRouteRule(index: number): void {
    this.routeRules.splice(index, 1);
  }

  // ── Graph model provider → model list ────────────────────────────────────

  onGraphLlmProviderChange(): void {
    this.graphAvailableModels = [];
    if (this.graphLlmProvider && this.graphLlmProvider !== 'default') {
      const provider = this.graphModelProviders.find(p => p.id === this.graphLlmProvider);
      if (provider && provider.models && provider.models.length > 0) {
        this.graphAvailableModels = provider.models;
      }
    }
  }

  // ── Pipeline-step selection ──────────────────────────────────────────────

  setStepSelection(stepId: string, value: 'run' | 'archive' | 'skip'): void {
    this.stepSelections[stepId] = value;
    // Dependency cascade: if this step is no longer 'run', force dependents that list it to skip.
    for (const step of this.stepCatalog) {
      if (step.dependsOn.includes(stepId) && value !== 'run') {
        if (this.stepSelections[step.id] === 'run') {
          this.stepSelections[step.id] = 'skip';
        }
      }
    }
  }

  isStepDependencyBlocked(step: PipelineStepCatalogEntry): boolean {
    return step.dependsOn.some(depId => {
      const sel = this.stepSelections[depId];
      return sel === 'skip' || sel === 'archive';
    });
  }

  // ── Icons ────────────────────────────────────────────────────────────────

  getSourceIcon(type: string): string {
    const icons: { [key: string]: string } = {
      'DIRECTORY': 'folder', 'FILE': 'insert_drive_file', 'URL': 'link', 'WEB_CRAWL': 'language',
      'EMAIL': 'email', 'IMAP': 'email', 'POP3': 'mark_email_unread', 'SLACK': 'chat',
      'SLACK_HISTORY': 'forum', 'GDRIVE': 'cloud', 'GDOCS': 'article', 'GMAIL': 'alternate_email',
      'CONFLUENCE': 'article', 'DISCORD': 'forum', 'DISCORD_HISTORY': 'history',
      'GOOGLE_WORKSPACE': 'work', 'MBOX': 'inbox', 'MAILDIR': 'move_to_inbox', 'EMLX_DIR': 'mail',
      'PST': 'inbox', 'ONEDRIVE': 'cloud_queue', 'NOTION': 'note',
    };
    return icons[type] || 'source';
  }

  getStepTypeIcon(stepType: string | undefined): string {
    const normalized = (stepType || '').toUpperCase();
    if (normalized.includes('IO')) return 'folder_open';
    if (normalized.includes('CPU')) return 'settings_suggest';
    if (normalized.includes('LLM')) return 'psychology';
    if (normalized.includes('GRAPH_CONSTRUCTOR')) return 'account_tree';
    if (normalized.includes('GRAPH')) return 'hub';
    if (normalized.includes('EMBEDDING')) return 'memory';
    if (normalized.includes('PIPELINE')) return 'schema';
    return 'schema';
  }

  getStepTypeClass(stepType: string | undefined): string {
    return 'type-' + (stepType || 'pipeline').toLowerCase().replace(/_/g, '-');
  }

  // ── Actions ──────────────────────────────────────────────────────────────

  cancel(): void {
    this.dialogRef.close();
  }

  start(): void {
    this.error = null;
    if (this.sources.length === 0) {
      this.error = 'Add at least one source before starting a crawl.';
      return;
    }
    const request = this.buildRequest();
    this.dialogRef.close({
      request,
      distribute: this.distributeAcrossWorkers && this.clusterWorkerCount > 0
    });
  }

  // ── Request assembly ─────────────────────────────────────────────────────

  private buildRequest(): UnifiedCrawlRequest {
    const requestSources = this.sources.map(s => this.toRequestSource(s));

    const enabledSteps: string[] = [];
    const archivedSteps: string[] = [];
    for (const step of this.stepCatalog) {
      if (step.foundational) continue;
      const sel = this.stepSelections[step.id] || 'run';
      if (sel === 'run') enabledSteps.push(step.id);
      else if (sel === 'archive') archivedSteps.push(step.id);
      // 'skip' → omitted from both arrays
    }

    const request: UnifiedCrawlRequest = {
      name: this.jobName || 'Unified crawl',
      factSheetId: this.activeFactSheet?.id ?? null,
      sources: requestSources,
      enabledSteps: enabledSteps.length > 0 ? enabledSteps : undefined,
      archivedSteps: archivedSteps.length > 0 ? archivedSteps : undefined,
      maxValidationRetries: this.maxValidationRetries,
      graphExtraction: {
        enabled: this.graphEnabled,
        schemaPresetId: this.graphSchemaPresetId || undefined,
        entityTypes: [...this.graphEntityTypes],
        relationshipTypes: [...this.graphRelTypes],
        llmProvider: this.graphLlmProvider,
        modelName: this.graphModelName || undefined,
        schemaMode: this.graphSchemaMode,
        minConfidence: this.graphMinConfidence,
        temperature: this.graphTemperature,
        maxTokens: this.graphMaxTokens > 0 ? this.graphMaxTokens : undefined,
        customPrompt: this.graphCustomPrompt.trim() || undefined,
        entityResolution: this.graphEntityResolution,
        entityResolutionSimilarityThreshold: this.graphEntityResolutionSimilarityThreshold,
        entityResolutionUseEmbeddings: this.graphEntityResolutionUseEmbeddings,
        entityResolutionEmbeddingThreshold: this.graphEntityResolutionEmbeddingThreshold,
        extractionModelProviderAllow: this.optArr(this.graphModelProviderAllow),
        extractionModelExcludeMarkers: this.optArr(this.graphModelExcludeMarkers)
      },
      vectorIndex: {
        enabled: this.indexEnabled,
        collectionName: this.indexCollectionName || undefined,
        chunkerName: this.chunkerName || undefined,
        chunkSize: this.posOrUndef(this.chunkSize),
        chunkOverlap: this.posOrUndef(this.chunkOverlap),
        embeddingBatchSize: this.posOrUndef(this.embeddingBatchSize),
        maxEmbeddingBatchSize: this.posOrUndef(this.maxEmbeddingBatchSize),
        adaptiveBatching: this.adaptiveBatching
      }
    };

    if (this.processingRouteEnabled) {
      request.processingRoute = {
        pdfRoutingMode: this.pdfRoutingMode,
        fallbackEnabled: this.fallbackEnabled,
        extractTablesFromTextPdfs: this.extractTablesFromTextPdfs,
        vlmModelId: this.vlmModelId || undefined,
        textThresholdCharsPerPage: this.posOrUndef(this.textThresholdCharsPerPage),
        backends: this.fallbackEnabled ? this.backends : undefined
      };
    }

    if (this.preprocessingEnabled) {
      request.preprocessing = this.buildPreprocessing();
    }

    if (this.hydrationCustomize) {
      const stages: string[] = [];
      if (this.hydrationDerivation) stages.push('DERIVATION');
      if (this.hydrationPruneCompact) stages.push('PRUNE_COMPACT');
      if (this.hydrationHealth) stages.push('HEALTH');
      request.hydration = {
        enabledStageIds: stages,
        confidencePruneThreshold: this.hydrationConfidencePruneThreshold,
        dryRun: this.hydrationDryRun
      };
    }

    if (this.runtimeTuningEnabled) {
      const rt = this.buildRuntimeConfig();
      if (Object.keys(rt).length > 0) request.runtimeConfig = rt;
    }

    // Advanced — custom ingest pipelines / routing.
    if (this.defaultPipelineId.trim()) request.defaultPipelineId = this.defaultPipelineId.trim();
    const pipelines = this.buildPipelines();
    if (pipelines) request.pipelines = pipelines;
    const routeRules = this.buildRouteRules();
    if (routeRules) request.routeRules = routeRules;

    return request;
  }

  private buildPreprocessing(): PreprocessingConfig {
    const pp: PreprocessingConfig = {
      enabled: true,
      llmProvider: this.ppLlmProvider || undefined,
      llmModelName: this.ppLlmModelName || undefined,
      parallelism: this.posOrUndef(this.ppParallelism)
    };
    if (this.tEnabled) {
      pp.translation = {
        enabled: true,
        targetLanguage: this.tTargetLanguage || 'en',
        sourceLanguage: this.tSourceLanguage || undefined,
        preserveOriginal: this.tPreserveOriginal,
        dualIndex: this.tDualIndex,
        detectionConfidenceThreshold: this.tDetectionConfidenceThreshold,
        maxCharsPerRequest: this.tMaxCharsPerRequest,
        domainHint: this.tDomainHint || undefined,
        preserveTerms: this.optArr(this.tPreserveTerms),
        customInstructions: this.tCustomInstructions || undefined
      };
    }
    if (this.ldEnabled) {
      pp.languageDetection = {
        enabled: true,
        minTextLength: this.ldMinTextLength,
        llmFallback: this.ldLlmFallback,
        forceLanguage: this.ldForceLanguage || undefined
      };
    }
    if (this.unEnabled) {
      pp.unicodeNormalization = {
        enabled: true,
        form: this.unForm,
        fixMojibake: this.unFixMojibake,
        standardizeTypography: this.unStandardizeTypography
      };
    }
    if (this.stEnabled) {
      pp.scriptTransliteration = {
        enabled: true,
        targetScript: this.stTargetScript,
        sourceScript: this.stSourceScript || undefined,
        preserveOriginal: this.stPreserveOriginal
      };
    }
    if (this.piiEnabled) {
      pp.piiRedaction = {
        enabled: true,
        entityTypes: this.optArr(this.piiEntityTypes),
        replacementStrategy: this.piiReplacementStrategy,
        useLlm: this.piiUseLlm,
        logCounts: this.piiLogCounts
      };
    }
    if (this.brEnabled) {
      pp.boilerplateRemoval = {
        enabled: true,
        removeWebBoilerplate: this.brRemoveWeb,
        removeEmailSignatures: this.brRemoveEmailSig,
        removeLegalDisclaimers: this.brRemoveLegal,
        customPatterns: this.optArr(this.brCustomPatterns),
        minRemainingChars: this.brMinRemainingChars
      };
    }
    if (this.dedupEnabled) {
      pp.deduplication = {
        enabled: true,
        similarityThreshold: this.dedupSimilarityThreshold,
        strategy: this.dedupStrategy,
        algorithm: this.dedupAlgorithm,
        trackDuplicateRelations: this.dedupTrackRelations
      };
    }
    if (this.dnEnabled) {
      pp.dateNumberNormalization = {
        enabled: true,
        dateFormat: this.dnDateFormat,
        numberLocale: this.dnNumberLocale,
        normalizeCurrency: this.dnNormalizeCurrency,
        normalizeUnits: this.dnNormalizeUnits
      };
    }
    if (this.tsEnabled) {
      pp.terminologyStandardization = {
        enabled: true,
        glossary: this.kvToObject(this.tsGlossary),
        glossaryFile: this.tsGlossaryFile || undefined,
        caseSensitive: this.tsCaseSensitive,
        expandAbbreviations: this.tsExpandAbbreviations,
        contextAwareDisambiguation: this.tsContextAware
      };
    }
    return pp;
  }

  private buildRuntimeConfig(): RuntimeConfig {
    const rt: RuntimeConfig = {};
    const num = (v: number | null, key: keyof RuntimeConfig) => {
      const n = this.posOrUndef(v);
      if (n !== undefined) (rt as any)[key] = n;
    };
    num(this.rtGraphExtractionParallelism, 'graphExtractionParallelism');
    num(this.rtGraphExtractionRemoteParallelism, 'graphExtractionRemoteParallelism');
    num(this.rtGraphExtractionBatchSize, 'graphExtractionBatchSize');
    num(this.rtGraphExtractionTargetCharsPerBatch, 'graphExtractionTargetCharsPerBatch');
    num(this.rtGraphExtractionMaxItemsPerBatch, 'graphExtractionMaxItemsPerBatch');
    num(this.rtGraphExtractionBatchTimeoutSeconds, 'graphExtractionBatchTimeoutSeconds');
    num(this.rtLlmCallTimeoutSeconds, 'llmCallTimeoutSeconds');
    num(this.rtSourceLoadParallelism, 'sourceLoadParallelism');
    num(this.rtChunkingParallelism, 'chunkingParallelism');
    num(this.rtEntityResolutionBatchSize, 'entityResolutionBatchSize');
    num(this.rtEdgeComputationParallelism, 'edgeComputationParallelism');
    num(this.rtVectorIndexingParallelism, 'vectorIndexingParallelism');
    num(this.rtVectorBatchSize, 'vectorBatchSize');
    const tri = (v: TriState, key: keyof RuntimeConfig) => {
      if (v === 'on') (rt as any)[key] = true;
      else if (v === 'off') (rt as any)[key] = false;
    };
    tri(this.rtCostSortChunks, 'costSortChunks');
    tri(this.rtParallelVectorAndGraph, 'parallelVectorAndGraph');
    tri(this.rtIncrementalByContentHash, 'incrementalByContentHash');
    tri(this.rtForceFullRecrawl, 'forceFullRecrawl');
    tri(this.rtClearGraphBeforeRun, 'clearGraphBeforeRun');
    return rt;
  }

  /** Map editable pipeline cards → IngestPipelineDefinition[]; only those with a pipelineId are sent. */
  private buildPipelines(): any[] | undefined {
    const out = this.pipelines
      .filter(p => p.pipelineId.trim())
      .map(p => {
        const def: any = {
          pipelineId: p.pipelineId.trim(),
          pipelineType: p.pipelineType,
          keywordOnly: p.keywordOnly,
          enableVlm: p.enableVlm,
          enableGraphExtraction: p.enableGraphExtraction
        };
        if (p.displayName.trim()) def.displayName = p.displayName.trim();
        if (p.loaderName.trim()) def.loaderName = p.loaderName.trim();
        if (p.chunkerName.trim()) def.chunkerName = p.chunkerName.trim();
        if (p.embeddingModelName.trim()) def.embeddingModelName = p.embeddingModelName.trim();
        if (p.language.trim()) def.language = p.language.trim();
        if (p.processingMode) def.processingMode = p.processingMode;
        if (p.collectionName.trim()) def.collectionName = p.collectionName.trim();
        if (p.extractionPromptTemplate.trim()) def.extractionPromptTemplate = p.extractionPromptTemplate.trim();
        if (p.extractionLlmProvider.trim()) def.extractionLlmProvider = p.extractionLlmProvider.trim();
        if (p.extractionModelName.trim()) def.extractionModelName = p.extractionModelName.trim();
        const chunkSize = this.posOrUndef(p.chunkSize);
        if (chunkSize !== undefined) def.chunkSize = chunkSize;
        const chunkOverlap = this.posOrUndef(p.chunkOverlap);
        if (chunkOverlap !== undefined) def.chunkOverlap = chunkOverlap;
        if (p.extractionTemperature !== null) def.extractionTemperature = p.extractionTemperature;
        const maxTokens = this.posOrUndef(p.extractionMaxTokens);
        if (maxTokens !== undefined) def.extractionMaxTokens = maxTokens;
        const maxChunkChars = this.posOrUndef(p.maxChunkChars);
        if (maxChunkChars !== undefined) def.maxChunkChars = maxChunkChars;
        const entityTypes = this.optArr(p.extractionEntityTypes);
        if (entityTypes) def.extractionEntityTypes = entityTypes;
        const relTypes = this.optArr(p.extractionRelationshipTypes);
        if (relTypes) def.extractionRelationshipTypes = relTypes;
        const options = this.kvToObject(p.options);
        if (options) def.options = options;
        return def;
      });
    return out.length > 0 ? out : undefined;
  }

  /** Map editable route-rule cards → ContentRouteRule[]; only those with a pipelineId are sent. */
  private buildRouteRules(): any[] | undefined {
    const out = this.routeRules
      .filter(r => r.pipelineId.trim())
      .map(r => {
        const rule: any = {
          pipelineId: r.pipelineId.trim(),
          priority: r.priority
        };
        const contentTypes = this.optArr(r.contentTypes);
        if (contentTypes) rule.contentTypes = contentTypes;
        const fileExtensions = this.optArr(r.fileExtensions);
        if (fileExtensions) rule.fileExtensions = fileExtensions;
        const urlPatterns = this.optArr(r.urlPatterns);
        if (urlPatterns) rule.urlPatterns = urlPatterns;
        const sourceTypes = this.optArr(r.sourceTypes);
        if (sourceTypes) rule.sourceTypes = sourceTypes;
        const languages = this.optArr(r.languages);
        if (languages) rule.languages = languages;
        const tags = this.optArr(r.tags);
        if (tags) rule.tags = tags;
        const contentPatterns = this.optArr(r.contentPatterns);
        if (contentPatterns) rule.contentPatterns = contentPatterns;
        const metadataMatchers = this.kvToObject(r.metadataMatchers);
        if (metadataMatchers) rule.metadataMatchers = metadataMatchers;
        if (r.minSizeBytes !== null) rule.minSizeBytes = r.minSizeBytes;
        if (r.maxSizeBytes !== null) rule.maxSizeBytes = r.maxSizeBytes;
        if (r.minLanguageConfidence !== null) rule.minLanguageConfidence = r.minLanguageConfidence;
        const minPages = this.posOrUndef(r.minPages);
        if (minPages !== undefined) rule.minPages = minPages;
        const maxPages = this.posOrUndef(r.maxPages);
        if (maxPages !== undefined) rule.maxPages = maxPages;
        return rule;
      });
    return out.length > 0 ? out : undefined;
  }

  private toRequestSource(source: EditableUnifiedCrawlSource): UnifiedCrawlSource {
    return {
      label: source.label,
      sourceType: source.sourceType,
      pathOrUrl: source.pathOrUrl,
      maxDepth: source.maxDepth,
      maxDocuments: source.maxDocuments,
      includePatterns: this.optArr(source.includePatterns),
      excludePatterns: this.optArr(source.excludePatterns),
      allowedContentTypes: this.optArr(source.allowedContentTypes),
      properties: this.kvToObject(source.propertyRows)
    };
  }

  /** Non-empty rows → object, or undefined when there are none (so it does not override a default). */
  private kvToObject(rows: KvRow[]): { [key: string]: string } | undefined {
    const obj: { [key: string]: string } = {};
    for (const row of rows) {
      const key = (row.key || '').trim();
      if (key) obj[key] = row.value;
    }
    return Object.keys(obj).length > 0 ? obj : undefined;
  }

  /** A copy of the list, or undefined when empty (so it does not override a backend default). */
  private optArr(arr: string[]): string[] | undefined {
    return arr.length > 0 ? [...arr] : undefined;
  }

  /** A positive number, or undefined for null / non-positive (meaning "use the default"). */
  private posOrUndef(v: number | null): number | undefined {
    return v !== null && v !== undefined && v > 0 ? v : undefined;
  }
}
