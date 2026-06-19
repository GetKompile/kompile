import {
  Component, Inject, OnInit, ChangeDetectionStrategy, ChangeDetectorRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTabsModule } from '@angular/material/tabs';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import {
  UnifiedCrawlService,
  UnifiedCrawlSource,
  GraphExtractionConfig,
  VectorIndexConfig,
  PreprocessingConfig,
  TranslationConfig,
  LanguageDetectionConfig,
  UnicodeNormalizationConfig,
  PiiRedactionConfig,
  BoilerplateRemovalConfig,
  DeduplicationConfig,
  ProcessingRouteConfig,
  ProcessingBackend,
  UnifiedCrawlRequest,
  AvailableSourceType
} from '../../../services/unified-crawl.service';
import { ProjectService, FactSheet } from '../../../services/project.service';

export interface CrawlConfigDialogData {
  factSheetId?: number | null;
}

export interface CrawlConfigDialogResult {
  jobId: string;
}

/* ── Local form models (mutable, with defaults) ── */

interface SourceForm {
  label: string;
  sourceType: string;
  pathOrUrl: string;
  maxDepth: number;
  maxDocuments: number;
  includePatterns: string;
  excludePatterns: string;
  allowedContentTypes: string;
  loaderName: string;
  chunkerName: string;
  expanded: boolean;
}

interface PipelineForm {
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
  collectionName: string;
  extractionLlmProvider: string;
  extractionModelName: string;
  extractionTemperature: number | null;
  extractionMaxTokens: number | null;
  maxChunkChars: number | null;
  expanded: boolean;
}

interface RouteRuleForm {
  pipelineId: string;
  priority: number;
  contentTypes: string;
  fileExtensions: string;
  urlPatterns: string;
  minSizeBytes: number | null;
  maxSizeBytes: number | null;
  languages: string;
  expanded: boolean;
}

interface BackendForm {
  id: string;
  displayName: string;
  type: string;
  priority: number;
  maxConcurrent: number;
  requestsPerMinute: number;
  maxMemoryBytes: number;
  agentName: string;
  endpointUrl: string;
  apiKey: string;
  modelName: string;
  enabled: boolean;
  expanded: boolean;
}

@Component({
  selector: 'app-crawl-config-dialog',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSlideToggleModule,
    MatTabsModule, MatExpansionModule, MatChipsModule, MatTooltipModule,
    MatDividerModule, MatSnackBarModule
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './crawl-config-dialog.component.html',
  styleUrls: ['./crawl-config-dialog.component.css']
})
export class CrawlConfigDialogComponent implements OnInit {

  // ── General ──
  jobName = '';
  factSheetId: number | null = null;
  factSheets: FactSheet[] = [];
  availableSourceTypes: AvailableSourceType[] = [];

  // ── Sources ──
  sources: SourceForm[] = [];

  // ── Vector Index ──
  vectorEnabled = true;
  vectorCollectionName = '';
  vectorChunkerName = '';
  vectorChunkSize: number | null = null;
  vectorChunkOverlap: number | null = null;
  vectorEmbeddingBatchSize: number | null = null;
  vectorMaxEmbeddingBatchSize: number | null = null;
  vectorAdaptiveBatching = true;

  // ── Graph Extraction ──
  graphEnabled = true;
  graphSchemaPresetId = '';
  graphEntityTypes = '';
  graphRelationshipTypes = '';
  graphLlmProvider = 'default';
  graphModelName = '';
  graphTemperature = 0.0;
  graphMaxTokens = 4096;
  graphCustomPrompt = '';
  graphSchemaMode = 'LENIENT';
  graphEntityResolution = true;
  graphEntityResolutionSimilarityThreshold = 0.85;
  graphEntityResolutionUseEmbeddings = true;
  graphEntityResolutionEmbeddingThreshold = 0.88;
  graphMinConfidence = 0.5;

  // ── Preprocessing ──
  prepEnabled = false;
  prepLlmProvider = 'default';
  prepLlmModelName = '';
  prepParallelism: number | null = null;
  // Translation
  transEnabled = false;
  transTargetLanguage = 'en';
  transSourceLanguage = '';
  transPreserveOriginal = true;
  transDualIndex = false;
  transDetectionConfidenceThreshold = 0.7;
  transMaxCharsPerRequest = 8000;
  transDomainHint = '';
  transPreserveTerms = '';
  transCustomInstructions = '';
  // Language Detection
  langDetEnabled = false;
  langDetMinTextLength = 50;
  langDetLlmFallback = false;
  langDetForceLanguage = '';
  // Unicode
  unicodeEnabled = false;
  unicodeForm = 'NFC';
  unicodeFixMojibake = true;
  unicodeStandardizeTypography = true;
  // PII
  piiEnabled = false;
  piiEntityTypes = '';
  piiReplacementStrategy = 'TYPE_TAG';
  piiUseLlm = true;
  piiLogCounts = true;
  // Boilerplate
  boilerplateEnabled = false;
  boilerplateRemoveWeb = true;
  boilerplateRemoveEmail = true;
  boilerplateRemoveLegal = true;
  boilerplateCustomPatterns = '';
  boilerplateMinRemainingChars = 50;
  // Deduplication
  dedupEnabled = false;
  dedupSimilarityThreshold = 0.95;
  dedupStrategy = 'KEEP_FIRST';
  dedupAlgorithm = 'SIMHASH';
  dedupTrackDuplicateRelations = true;

  // ── Processing Route ──
  routePdfRoutingMode = 'AUTO';
  routeFallbackEnabled = false;
  routeVlmModelId = '';
  routeExtractTablesFromTextPdfs = true;
  routeTextThresholdCharsPerPage = 50;
  routeBackends: BackendForm[] = [];

  // ── Pipelines ──
  pipelines: PipelineForm[] = [];
  routeRules: RouteRuleForm[] = [];
  defaultPipelineId = '';

  // ── Runtime ──
  rtGraphExtractionParallelism: number | null = null;
  rtGraphExtractionBatchSize: number | null = null;
  rtGraphExtractionTargetCharsPerBatch: number | null = null;
  rtSourceLoadParallelism: number | null = null;
  rtChunkingParallelism: number | null = null;
  rtVectorBatchSize: number | null = null;
  rtCostSortChunks: boolean | null = null;
  rtEntityResolutionBatchSize: number | null = null;
  rtEdgeComputationParallelism: number | null = null;
  rtVectorIndexingParallelism: number | null = null;
  rtParallelVectorAndGraph: boolean | null = null;
  rtLlmCallTimeoutSeconds: number | null = null;
  rtGraphExtractionBatchTimeoutSeconds: number | null = null;

  isSubmitting = false;
  activeTabIndex = 0;

  pipelineTypes = [
    'STANDARD_TEXT', 'VLM', 'OCR', 'CODE', 'TABLE_AWARE', 'KEYWORD_ONLY', 'CUSTOM'
  ];
  schemaModes = ['LENIENT', 'STRICT', 'IGNORE'];
  pdfRoutingModes = ['AUTO', 'FORCE_VLM', 'FORCE_TEXT', 'DISABLED'];
  backendTypes = ['LOCAL_MODEL', 'CLI_AGENT', 'API_AGENT'];
  piiStrategies = ['TYPE_TAG', 'MASK', 'HASH', 'REMOVE'];
  dedupStrategies = ['KEEP_FIRST', 'KEEP_LONGEST', 'KEEP_NEWEST', 'MERGE_METADATA'];
  dedupAlgorithms = ['SIMHASH', 'MINHASH', 'EXACT_HASH'];
  unicodeForms = ['NFC', 'NFD', 'NFKC', 'NFKD'];

  constructor(
    public dialogRef: MatDialogRef<CrawlConfigDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: CrawlConfigDialogData,
    private crawlService: UnifiedCrawlService,
    private projectService: ProjectService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    if (this.data?.factSheetId) {
      this.factSheetId = this.data.factSheetId;
    }
    this.addSource();
    this.loadFactSheets();
    this.loadSourceTypes();
  }

  private loadFactSheets(): void {
    this.projectService.listFactSheets().subscribe({
      next: (sheets) => { this.factSheets = sheets; this.cdr.markForCheck(); },
      error: () => {}
    });
  }

  private loadSourceTypes(): void {
    this.crawlService.getSourceTypes().subscribe({
      next: (types) => { this.availableSourceTypes = types; this.cdr.markForCheck(); },
      error: () => {}
    });
  }

  // ── Source management ──

  addSource(): void {
    this.sources.push({
      label: '', sourceType: 'WEB', pathOrUrl: '', maxDepth: 3, maxDocuments: 0,
      includePatterns: '', excludePatterns: '', allowedContentTypes: '',
      loaderName: '', chunkerName: '', expanded: true
    });
  }

  removeSource(i: number): void {
    this.sources.splice(i, 1);
  }

  // ── Pipeline management ──

  addPipeline(): void {
    this.pipelines.push({
      pipelineId: '', displayName: '', pipelineType: 'STANDARD_TEXT',
      loaderName: '', chunkerName: '', embeddingModelName: '', language: '',
      chunkSize: null, chunkOverlap: null, keywordOnly: false, enableVlm: false,
      enableGraphExtraction: false, collectionName: '',
      extractionLlmProvider: '', extractionModelName: '',
      extractionTemperature: null, extractionMaxTokens: null,
      maxChunkChars: null, expanded: true
    });
  }

  removePipeline(i: number): void {
    this.pipelines.splice(i, 1);
  }

  // ── Route Rule management ──

  addRouteRule(): void {
    this.routeRules.push({
      pipelineId: '', priority: 100, contentTypes: '', fileExtensions: '',
      urlPatterns: '', minSizeBytes: null, maxSizeBytes: null, languages: '',
      expanded: true
    });
  }

  removeRouteRule(i: number): void {
    this.routeRules.splice(i, 1);
  }

  // ── Backend management ──

  addBackend(): void {
    this.routeBackends.push({
      id: '', displayName: '', type: 'LOCAL_MODEL', priority: 100,
      maxConcurrent: 0, requestsPerMinute: 0, maxMemoryBytes: 0,
      agentName: '', endpointUrl: '', apiKey: '', modelName: '',
      enabled: true, expanded: true
    });
  }

  removeBackend(i: number): void {
    this.routeBackends.splice(i, 1);
  }

  // ── Submission ──

  get canSubmit(): boolean {
    return this.sources.some(s => s.pathOrUrl.trim().length > 0) && !this.isSubmitting;
  }

  startCrawl(): void {
    if (!this.canSubmit) return;
    this.isSubmitting = true;
    this.cdr.markForCheck();

    const request = this.buildRequest();
    this.crawlService.startJob(request).subscribe({
      next: (resp) => {
        this.isSubmitting = false;
        this.snackBar.open('Crawl job started: ' + resp.jobId.substring(0, 8), 'OK', { duration: 4000 });
        this.dialogRef.close({ jobId: resp.jobId } as CrawlConfigDialogResult);
      },
      error: (err) => {
        this.isSubmitting = false;
        this.snackBar.open(
          'Failed to start crawl: ' + (err.error?.error || err.error?.message || err.message || 'Unknown error'),
          'Dismiss', { duration: 6000 }
        );
        this.cdr.markForCheck();
      }
    });
  }

  onCancel(): void {
    this.dialogRef.close(null);
  }

  // ── Build UnifiedCrawlRequest ──

  private buildRequest(): UnifiedCrawlRequest {
    const req: UnifiedCrawlRequest = {
      name: this.jobName || ('Crawl ' + new Date().toLocaleString()),
      sources: this.buildSources()
    };

    if (this.factSheetId) {
      req.factSheetId = this.factSheetId;
    }

    req.vectorIndex = this.buildVectorIndex();
    req.graphExtraction = this.buildGraphExtraction();
    req.preprocessing = this.buildPreprocessing();
    req.processingRoute = this.buildProcessingRoute();

    const pipelines = this.buildPipelines();
    if (pipelines.length > 0) {
      (req as any).pipelines = pipelines;
    }
    const rules = this.buildRouteRules();
    if (rules.length > 0) {
      (req as any).routeRules = rules;
    }
    if (this.defaultPipelineId) {
      (req as any).defaultPipelineId = this.defaultPipelineId;
    }

    const runtime = this.buildRuntimeConfig();
    if (runtime) {
      (req as any).runtimeConfig = runtime;
    }

    return req;
  }

  private buildSources(): UnifiedCrawlSource[] {
    return this.sources
      .filter(s => s.pathOrUrl.trim())
      .map(s => {
        const src: UnifiedCrawlSource = {
          label: s.label || s.pathOrUrl,
          sourceType: s.sourceType,
          pathOrUrl: s.pathOrUrl.trim(),
          maxDepth: s.maxDepth,
          maxDocuments: s.maxDocuments
        };
        if (s.includePatterns.trim()) src.includePatterns = this.splitCsv(s.includePatterns);
        if (s.excludePatterns.trim()) src.excludePatterns = this.splitCsv(s.excludePatterns);
        if (s.allowedContentTypes.trim()) src.allowedContentTypes = this.splitCsv(s.allowedContentTypes);
        if (s.loaderName) (src as any).loaderName = s.loaderName;
        if (s.chunkerName) (src as any).chunkerName = s.chunkerName;
        return src;
      });
  }

  private buildVectorIndex(): VectorIndexConfig {
    const vi: VectorIndexConfig = { enabled: this.vectorEnabled };
    if (this.vectorCollectionName) vi.collectionName = this.vectorCollectionName;
    if (this.vectorChunkerName) vi.chunkerName = this.vectorChunkerName;
    if (this.vectorChunkSize != null) vi.chunkSize = this.vectorChunkSize;
    if (this.vectorChunkOverlap != null) vi.chunkOverlap = this.vectorChunkOverlap;
    if (this.vectorEmbeddingBatchSize != null) vi.embeddingBatchSize = this.vectorEmbeddingBatchSize;
    if (this.vectorMaxEmbeddingBatchSize != null) vi.maxEmbeddingBatchSize = this.vectorMaxEmbeddingBatchSize;
    vi.adaptiveBatching = this.vectorAdaptiveBatching;
    return vi;
  }

  private buildGraphExtraction(): GraphExtractionConfig {
    const ge: GraphExtractionConfig = { enabled: this.graphEnabled };
    if (this.graphSchemaPresetId) ge.schemaPresetId = this.graphSchemaPresetId;
    if (this.graphEntityTypes.trim()) ge.entityTypes = this.splitCsv(this.graphEntityTypes);
    if (this.graphRelationshipTypes.trim()) ge.relationshipTypes = this.splitCsv(this.graphRelationshipTypes);
    ge.llmProvider = this.graphLlmProvider || 'default';
    if (this.graphModelName) ge.modelName = this.graphModelName;
    ge.temperature = this.graphTemperature;
    ge.maxTokens = this.graphMaxTokens;
    ge.schemaMode = this.graphSchemaMode;
    ge.entityResolution = this.graphEntityResolution;
    ge.entityResolutionSimilarityThreshold = this.graphEntityResolutionSimilarityThreshold;
    ge.entityResolutionUseEmbeddings = this.graphEntityResolutionUseEmbeddings;
    ge.entityResolutionEmbeddingThreshold = this.graphEntityResolutionEmbeddingThreshold;
    ge.minConfidence = this.graphMinConfidence;
    if (this.graphCustomPrompt.trim()) ge.customPrompt = this.graphCustomPrompt;
    return ge;
  }

  private buildPreprocessing(): PreprocessingConfig {
    const pp: PreprocessingConfig = { enabled: this.prepEnabled };
    if (!this.prepEnabled) return pp;

    pp.llmProvider = this.prepLlmProvider || 'default';
    if (this.prepLlmModelName) pp.llmModelName = this.prepLlmModelName;
    if (this.prepParallelism != null) pp.parallelism = this.prepParallelism;

    if (this.transEnabled) {
      const t: TranslationConfig = { enabled: true, targetLanguage: this.transTargetLanguage };
      if (this.transSourceLanguage) t.sourceLanguage = this.transSourceLanguage;
      t.preserveOriginal = this.transPreserveOriginal;
      t.dualIndex = this.transDualIndex;
      t.detectionConfidenceThreshold = this.transDetectionConfidenceThreshold;
      t.maxCharsPerRequest = this.transMaxCharsPerRequest;
      if (this.transDomainHint) t.domainHint = this.transDomainHint;
      if (this.transPreserveTerms.trim()) t.preserveTerms = this.splitCsv(this.transPreserveTerms);
      if (this.transCustomInstructions) t.customInstructions = this.transCustomInstructions;
      pp.translation = t;
    }
    if (this.langDetEnabled) {
      const ld: LanguageDetectionConfig = { enabled: true };
      ld.minTextLength = this.langDetMinTextLength;
      ld.llmFallback = this.langDetLlmFallback;
      if (this.langDetForceLanguage) ld.forceLanguage = this.langDetForceLanguage;
      pp.languageDetection = ld;
    }
    if (this.unicodeEnabled) {
      pp.unicodeNormalization = {
        enabled: true, form: this.unicodeForm,
        fixMojibake: this.unicodeFixMojibake, standardizeTypography: this.unicodeStandardizeTypography
      };
    }
    if (this.piiEnabled) {
      const pii: PiiRedactionConfig = {
        enabled: true, replacementStrategy: this.piiReplacementStrategy,
        useLlm: this.piiUseLlm, logCounts: this.piiLogCounts
      };
      if (this.piiEntityTypes.trim()) pii.entityTypes = this.splitCsv(this.piiEntityTypes);
      pp.piiRedaction = pii;
    }
    if (this.boilerplateEnabled) {
      const bp: BoilerplateRemovalConfig = {
        enabled: true, removeWebBoilerplate: this.boilerplateRemoveWeb,
        removeEmailSignatures: this.boilerplateRemoveEmail,
        removeLegalDisclaimers: this.boilerplateRemoveLegal,
        minRemainingChars: this.boilerplateMinRemainingChars
      };
      if (this.boilerplateCustomPatterns.trim()) bp.customPatterns = this.splitCsv(this.boilerplateCustomPatterns);
      pp.boilerplateRemoval = bp;
    }
    if (this.dedupEnabled) {
      pp.deduplication = {
        enabled: true, similarityThreshold: this.dedupSimilarityThreshold,
        strategy: this.dedupStrategy, algorithm: this.dedupAlgorithm,
        trackDuplicateRelations: this.dedupTrackDuplicateRelations
      };
    }
    return pp;
  }

  private buildProcessingRoute(): ProcessingRouteConfig {
    const pr: ProcessingRouteConfig = {
      pdfRoutingMode: this.routePdfRoutingMode as any,
      fallbackEnabled: this.routeFallbackEnabled,
      extractTablesFromTextPdfs: this.routeExtractTablesFromTextPdfs,
      textThresholdCharsPerPage: this.routeTextThresholdCharsPerPage
    };
    if (this.routeVlmModelId) pr.vlmModelId = this.routeVlmModelId;
    if (this.routeBackends.length > 0) {
      pr.backends = this.routeBackends.map(b => ({
        id: b.id, displayName: b.displayName || undefined, type: b.type as any,
        priority: b.priority, maxConcurrent: b.maxConcurrent,
        requestsPerMinute: b.requestsPerMinute, maxMemoryBytes: b.maxMemoryBytes,
        agentName: b.agentName || undefined, endpointUrl: b.endpointUrl || undefined,
        apiKey: b.apiKey || undefined, modelName: b.modelName || undefined,
        enabled: b.enabled
      } as ProcessingBackend));
    }
    return pr;
  }

  private buildPipelines(): any[] {
    return this.pipelines
      .filter(p => p.pipelineId.trim())
      .map(p => {
        const def: any = {
          pipelineId: p.pipelineId, pipelineType: p.pipelineType,
          keywordOnly: p.keywordOnly, enableVlm: p.enableVlm,
          enableGraphExtraction: p.enableGraphExtraction
        };
        if (p.displayName) def.displayName = p.displayName;
        if (p.loaderName) def.loaderName = p.loaderName;
        if (p.chunkerName) def.chunkerName = p.chunkerName;
        if (p.embeddingModelName) def.embeddingModelName = p.embeddingModelName;
        if (p.language) def.language = p.language;
        if (p.chunkSize != null) def.chunkSize = p.chunkSize;
        if (p.chunkOverlap != null) def.chunkOverlap = p.chunkOverlap;
        if (p.collectionName) def.collectionName = p.collectionName;
        if (p.extractionLlmProvider) def.extractionLlmProvider = p.extractionLlmProvider;
        if (p.extractionModelName) def.extractionModelName = p.extractionModelName;
        if (p.extractionTemperature != null) def.extractionTemperature = p.extractionTemperature;
        if (p.extractionMaxTokens != null) def.extractionMaxTokens = p.extractionMaxTokens;
        if (p.maxChunkChars != null) def.maxChunkChars = p.maxChunkChars;
        return def;
      });
  }

  private buildRouteRules(): any[] {
    return this.routeRules
      .filter(r => r.pipelineId.trim())
      .map(r => {
        const rule: any = { pipelineId: r.pipelineId, priority: r.priority };
        if (r.contentTypes.trim()) rule.contentTypes = this.splitCsv(r.contentTypes);
        if (r.fileExtensions.trim()) rule.fileExtensions = this.splitCsv(r.fileExtensions);
        if (r.urlPatterns.trim()) rule.urlPatterns = this.splitCsv(r.urlPatterns);
        if (r.languages.trim()) rule.languages = this.splitCsv(r.languages);
        if (r.minSizeBytes != null) rule.minSizeBytes = r.minSizeBytes;
        if (r.maxSizeBytes != null) rule.maxSizeBytes = r.maxSizeBytes;
        return rule;
      });
  }

  private buildRuntimeConfig(): any | null {
    const rt: any = {};
    let hasValue = false;
    const set = (key: string, val: any) => {
      if (val != null) { rt[key] = val; hasValue = true; }
    };
    set('graphExtractionParallelism', this.rtGraphExtractionParallelism);
    set('graphExtractionBatchSize', this.rtGraphExtractionBatchSize);
    set('graphExtractionTargetCharsPerBatch', this.rtGraphExtractionTargetCharsPerBatch);
    set('sourceLoadParallelism', this.rtSourceLoadParallelism);
    set('chunkingParallelism', this.rtChunkingParallelism);
    set('vectorBatchSize', this.rtVectorBatchSize);
    set('costSortChunks', this.rtCostSortChunks);
    set('entityResolutionBatchSize', this.rtEntityResolutionBatchSize);
    set('edgeComputationParallelism', this.rtEdgeComputationParallelism);
    set('vectorIndexingParallelism', this.rtVectorIndexingParallelism);
    set('parallelVectorAndGraph', this.rtParallelVectorAndGraph);
    set('llmCallTimeoutSeconds', this.rtLlmCallTimeoutSeconds);
    set('graphExtractionBatchTimeoutSeconds', this.rtGraphExtractionBatchTimeoutSeconds);
    return hasValue ? rt : null;
  }

  private splitCsv(val: string): string[] {
    return val.split(',').map(s => s.trim()).filter(s => s.length > 0);
  }

  getSourceTypeLabel(type: string): string {
    const found = this.availableSourceTypes.find(t => t.type === type);
    return found ? found.displayName : type;
  }
}
