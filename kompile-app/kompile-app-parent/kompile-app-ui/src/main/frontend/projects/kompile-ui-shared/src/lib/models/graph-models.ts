/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * TypeScript models for Knowledge Graph visualization
 */

// Node hierarchy levels
export type NodeLevel = 'SOURCE' | 'DOCUMENT' | 'SNIPPET' | 'ENTITY' | 'CUSTOM' | 'ATTACHMENT' | 'TABLE' | 'IDENTIFIER' | 'ALIAS';

// Edge relationship types. The backend can emit schema/domain-specific keys
// (for example CONTAINS, HEADER_OF, DEPENDS_ON), so keep this open-ended.
export type EdgeType = string;

/**
 * Graph node representing a source, document, snippet, or entity
 */
export interface GraphNode {
  id: number;
  nodeId: string;
  nodeType: NodeLevel;
  externalId?: string;
  sourceType?: string;
  title: string;
  description?: string;
  metadata?: Record<string, any>;
  parentId?: string;
  sourceId?: string;
  childCount: number;
  edgeCount: number;
  createdAt?: string;
  updatedAt?: string;

  // D3 simulation properties (added at runtime)
  x?: number;
  y?: number;
  vx?: number;
  vy?: number;
  fx?: number | null;  // Fixed position
  fy?: number | null;

  // UI state
  selected?: boolean;
  highlighted?: boolean;
  expanded?: boolean;

  // Composite entity / hierarchy fields
  confidence?: number;
  contentPreview?: string;
  pathOrUrl?: string;
  isComposite?: boolean;
  subGraphId?: string;
}

/**
 * Graph edge connecting two nodes
 */
export interface GraphEdge {
  id: number;
  edgeId: string;
  sourceNodeId: string;
  targetNodeId: string;
  edgeType: EdgeType;
  weight: number;
  description?: string;
  bidirectional: boolean;
  computedAt?: string;
  createdAt?: string;
  occurredAt?: string;

  // D3 link properties (added at runtime)
  source: string | GraphNode;
  target: string | GraphNode;

  // UI state
  selected?: boolean;
  highlighted?: boolean;
}

/**
 * Source weight configuration
 */
export interface SourceWeight {
  id: number;
  sourceNodeId: string;
  sourceName?: string;
  sourceType?: string;
  topic?: string;
  userId?: string;
  baseWeight: number;
  topicRelevanceScore: number;
  qualityScore: number;
  recencyFactor: number;
  effectiveWeight: number;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Entity mention for shared-entity edge computation
 */
export interface EntityMention {
  id: number;
  nodeId: string;
  entityName: string;
  entityType: string;
  mentionCount: number;
  confidence: number;
}

/**
 * Complete knowledge graph for visualization
 */
export interface KnowledgeGraph {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

/**
 * Graph statistics
 */
export interface GraphStatistics {
  totalNodes?: number;
  totalEdges?: number;
  nodeCount?: number;
  edgeCount?: number;
  nodesByType?: Record<string, number>;
  edgesByType?: Record<string, number>;
  nodeTypes?: Record<string, number>;
  edgeTypes?: Record<string, number>;
  averageEdgesPerNode?: number;
  maxDepth?: number;
  factSheetId?: number;
}

/**
 * D3 visualization data format (from backend)
 */
export interface D3VisualizationData {
  nodes: D3Node[];
  links: D3Link[];
  statistics?: GraphStatistics;
}

export interface D3Node {
  id: string;
  type: NodeLevel;
  label: string;
  title?: string;
  description?: string;
  metadata?: Record<string, any>;
  childCount?: number;
  edgeCount?: number;
  symbolType?: string;
  language?: string;
  fqn?: string;
  filePath?: string;
  file?: string;
  startLine?: number;
  endLine?: number;
  line?: number;
  signature?: string;
  docComment?: string;
  fullyQualifiedName?: string;
  name?: string;
  occurredAt?: string;
}

export interface CreateCompositeEntityRequest {
  title: string;
  externalId?: string;
  description?: string;
  parentNodeId?: string;
  confidence?: number;
  metadata?: Record<string, string>;
}

export interface D3Link {
  id: string;
  source: string | D3Node | GraphNode;
  target: string | D3Node | GraphNode;
  type: EdgeType;
  weight: number;
  label?: string;
  description?: string;
  bidirectional?: boolean;
  confidence?: number;
  provenance?: string;
  occurredAt?: string;
  metadata?: Record<string, any>;
  metadataJson?: string;
  /** Semantic relationship type (e.g. "CONTAINS"), distinct from the structural EdgeType. */
  relationType?: string;
  /** Epistemic provenance: EXTRACTED | INFERRED | AMBIGUOUS. */
  provenanceType?: string;
  /** True when this edge was inferred by OWL-RL (has-a transitive closure). */
  inferred?: boolean;
}

/**
 * Typed reasoning overlays for graph visualization and MCP/tooling.
 * Mirrors GraphFocalViewController.ReasoningLayersDto.
 */
export interface ReasoningLayers {
  factSheetId: number;
  nodes: NodeReasoningOverlay[];
  edges: EdgeReasoningOverlay[];
  statistics?: ReasoningLayerStatistics;
}

export interface ReasoningLayerStatistics {
  nodeCount?: number;
  edgeCount?: number;
  ontologyCount?: number;
  pslCount?: number;
  mebnCount?: number;
  provenanceCount?: number;
  opinionCount?: number;
  neuralScoreCount?: number;
  typeCandidateCount?: number;
  typeHierarchyCount?: number;
  inferredRelationCount?: number;
}

export interface NodeReasoningOverlay {
  nodeId: string;
  nodeType?: NodeLevel;
  label?: string;
  ontology?: OntologyOverlay | null;
  psl?: PslOverlay | null;
  mebn?: MebnOverlay | null;
  provenance?: ProvenanceOverlay | null;
  opinion?: OpinionOverlay | null;
  neuralScores?: NeuralScoreOverlay | null;
}

export interface EdgeReasoningOverlay {
  edgeId: string;
  sourceNodeId?: string;
  targetNodeId?: string;
  edgeType?: EdgeType;
  relationType?: string;
  weight?: number;
  ontology?: OntologyOverlay | null;
  psl?: PslOverlay | null;
  mebn?: MebnOverlay | null;
  provenance?: ProvenanceOverlay | null;
  opinion?: OpinionOverlay | null;
  neuralScores?: NeuralScoreOverlay | null;
}

export interface OntologyOverlay {
  declaredType?: string;
  inferredTypes?: string[];
  typeCandidates?: TypeCandidateOverlay[];
  typeHierarchy?: TypeHierarchyOverlay[];
  inferredRelations?: InferredRelationOverlay[];
  conformant?: boolean | null;
  violations?: string[];
}

export interface TypeCandidateOverlay {
  type: string;
  confidence?: number;
  source?: string;
  basis?: string;
  evidence?: string[];
}

export interface TypeHierarchyOverlay {
  type: string;
  parentType?: string;
  depth?: number;
  confidence?: number;
  source?: string;
  basis?: string;
  evidence?: string[];
}

export interface InferredRelationOverlay {
  relationType?: string;
  sourceNodeId?: string;
  targetNodeId?: string;
  sourceType?: string;
  targetType?: string;
  confidence?: number;
  inferenceSource?: string;
  basis?: string;
  evidence?: string[];
}

export interface PslOverlay {
  groundingId?: string;
  ruleId?: string;
  ruleText?: string;
  truthValue?: number;
  incompatibility?: number;
  bindings?: string[];
}

export interface MebnOverlay {
  mfrag?: string;
  residentVariable?: string;
  state?: string;
  prior?: number;
  posterior?: number;
  findings?: string[];
}

export interface ProvenanceOverlay {
  details?: Record<string, any>;
}

export interface OpinionOverlay {
  belief?: number;
  disbelief?: number;
  uncertainty?: number;
  confidence?: number;
  basis?: string;
}

export interface NeuralScoreOverlay {
  scores?: Record<string, number>;
  embeddingAlgorithm?: string;
  embeddingVersion?: number;
}

export type ReasoningLayerKind = 'ontology' | 'psl' | 'mebn' | 'provenance' | 'opinion' | 'neural';

export interface ReasoningLayerVisualOverlay {
  activeLayers: ReasoningLayerKind[];
  ontologyConformant?: boolean | null;
  ontologyViolation?: boolean;
  inferredRelationship?: boolean;
  inferredRelationshipScore?: number;
  hierarchyDepth?: number;
  pslTruthValue?: number;
  pslIncompatibility?: number;
  mebnPosterior?: number;
  mebnPrior?: number;
  mebnFinding?: boolean;
  provenance?: boolean;
  opinionConfidence?: number;
  neuralScore?: number;
}

/**
 * Graph filter configuration
 */
export interface GraphFilter {
  nodeTypes: NodeLevel[];
  searchQuery?: string;
  minWeight?: number;
  maxDepth?: number;
  showOrphans?: boolean;
  timeFrom?: string;
  timeTo?: string;
}

/**
 * Temporal bounds for the graph (earliest/latest occurredAt timestamps)
 */
export interface TemporalBounds {
  earliest?: string;
  latest?: string;
  temporalEdgeCount?: number;
}

/**
 * Weight update request
 */
export interface SetWeightRequest {
  sourceNodeId: string;
  baseWeight: number;
  topic?: string;
  userId?: string;
}

/**
 * Weighted search preview result
 */
export interface WeightedSearchPreview {
  query: string;
  maxResults: number;
  sourceWeights: SourceWeightPreview[];
  note?: string;
}

export interface SourceWeightPreview {
  sourceId: string;
  sourceName: string;
  sourceType?: string;
  weight: number;
  /** 0..1 semantic similarity of the query to this source; null when embeddings are unavailable. */
  relevance?: number | null;
  /** Final ranking score = weight × relevance (or weight alone when relevance is unavailable). */
  score?: number;
}

/**
 * Create node request
 */
export interface CreateNodeRequest {
  type: NodeLevel;
  externalId?: string;
  title: string;
  description?: string;
  metadata?: Record<string, any>;
}

/**
 * Update node request
 */
export interface UpdateNodeRequest {
  title?: string;
  description?: string;
  metadata?: Record<string, any>;
}

/**
 * Create edge request
 */
export interface CreateEdgeRequest {
  sourceNodeId: string;
  targetNodeId: string;
  edgeType: EdgeType;
  weight?: number;
  description?: string;
}

/**
 * Update edge request
 */
export interface UpdateEdgeRequest {
  weight?: number;
  description?: string;
}

/**
 * Feedback request for quality scoring
 */
export interface FeedbackRequest {
  sourceNodeId: string;
  wasHelpful: boolean;
}

/**
 * D3 force simulation configuration
 */
export interface ForceConfig {
  linkDistance: number;
  linkStrength: number;
  chargeStrength: number;
  collisionRadius: number;
  centerStrength: number;
  alphaDecay: number;
  velocityDecay: number;
}

/**
 * Default force configuration
 */
export const DEFAULT_FORCE_CONFIG: ForceConfig = {
  linkDistance: 100,
  linkStrength: 0.5,
  chargeStrength: -300,
  collisionRadius: 30,
  centerStrength: 0.1,
  alphaDecay: 0.0228,
  velocityDecay: 0.4
};

/**
 * Node color mapping by type
 */
export const NODE_COLORS: Record<NodeLevel, string> = {
  SOURCE: '#4CAF50',      // Green
  DOCUMENT: '#2196F3',    // Blue
  SNIPPET: '#FF9800',     // Orange
  ENTITY: '#9C27B0',      // Purple
  CUSTOM: '#607D8B',      // Grey
  TABLE: '#795548',       // Brown
  ATTACHMENT: '#FF5722',  // Deep Orange
  IDENTIFIER: '#3F51B5',  // Indigo
  ALIAS: '#009688'        // Teal — synthetic cross-doc alias hub
};

/**
 * Node size mapping by type
 */
export const NODE_SIZES: Record<NodeLevel, number> = {
  SOURCE: 20,
  DOCUMENT: 15,
  SNIPPET: 10,
  ENTITY: 12,
  CUSTOM: 12,
  ATTACHMENT: 10,
  TABLE: 10,
  IDENTIFIER: 10,
  ALIAS: 14
};

/**
 * Edge color mapping by type
 */
export const EDGE_COLORS: Record<EdgeType, string> = {
  HIERARCHICAL: '#9E9E9E',         // Grey
  EMBEDDING_SIMILARITY: '#4CAF50', // Green
  SHARED_ENTITY: '#9C27B0',        // Purple
  USER_DEFINED: '#2196F3',         // Blue
  CITATION: '#FF5722',             // Deep Orange
  TEMPORAL: '#795548',             // Brown
  CROSS_SOURCE: '#00BCD4',         // Cyan
  RESOLVES_TO: '#E91E63',          // Pink
  ALIAS_OF: '#009688',             // Teal — member → alias hub
  HAS_OWNER: '#FF9800',            // Orange — entity → owner (forward)
  OWNED_BY: '#FF6F00'              // Amber-dark — entity is owned by (inverse)
};

/**
 * Edge dash patterns by type
 */
export const EDGE_DASH_PATTERNS: Record<EdgeType, string> = {
  HIERARCHICAL: 'none',
  EMBEDDING_SIMILARITY: '5,5',
  SHARED_ENTITY: '2,2',
  USER_DEFINED: 'none',
  CITATION: '10,5',
  TEMPORAL: '5,2,2,2',
  CROSS_SOURCE: '8,4',
  RESOLVES_TO: '6,3',
  ALIAS_OF: '4,3',
  HAS_OWNER: 'none',
  OWNED_BY: 'none'
};

export interface ProvenanceCitation {
  sourceId: string;
  sourceName: string;
  excerpt: string;
  relevance: number;
  page?: number;
  section?: string;
  confidence?: number;
  discoverySource?: string;
  documentTitle?: string;
  entityType?: string;
  extractedText?: string;
  location?: string;
  nodeId?: string;
  title?: string;
}

/**
 * Tree node for hierarchical graph display
 */
export interface HierarchyTreeNode {
  nodeId: string;
  nodeType: NodeLevel;
  id: string;
  type: NodeLevel;
  label: string;
  title?: string;
  description?: string;
  childCount?: number;
  edgeCount?: number;
  confidence?: number;
  isComposite?: boolean;
  subGraphId?: string;
  depth?: number;
  children?: HierarchyTreeNode[];
  hasMore?: boolean;
}

/**
 * A named graph (sub-graph or named grouping within the knowledge graph)
 */
export interface NamedGraph {
  graphId: string;
  name: string;
  description?: string;
  parentGraphId?: string;
  nodeCount?: number;
  edgeCount?: number;
  childGraphCount?: number;
  childGraphs?: NamedGraph[];
  createdAt?: string;
  updatedAt?: string;
  metadata?: Record<string, any>;
  ontologyType?: string;
  factSheetId?: number;
}

/**
 * Request to create a named graph
 */
export interface CreateNamedGraphRequest {
  name: string;
  description?: string;
  ontologyType?: string;
  parentGraphId?: string;
  metadata?: Record<string, any>;
}

/**
 * A single metadata patch rule
 */
export interface GraphMetadataPatchRule {
  nodeType?: NodeLevel | string;
  name?: string;
  entityType?: string;
  titleEquals?: string[];
  titleRegex?: string;
  metadataEquals?: Record<string, any>;
  metadataExists?: string[];
  setMetadata: Record<string, any>;
  removeMetadataKeys?: string[];
}

/**
 * Request to patch node metadata in bulk
 */
export interface GraphMetadataPatchRequest {
  factSheetId?: number | null;
  allowGlobal?: boolean;
  dryRun?: boolean;
  limit?: number | null;
  rules: GraphMetadataPatchRule[];
}

/**
 * Sample showing before/after metadata change from a patch operation
 */
export interface GraphMetadataPatchSample {
  nodeId: string;
  title?: string;
  matchedRules: string[];
  beforeMetadata: Record<string, any>;
  afterMetadata: Record<string, any>;
}

/**
 * Result of a metadata patch operation
 */
export interface GraphMetadataPatchResult {
  dryRun?: boolean;
  allowGlobal?: boolean;
  scannedCount?: number;
  matchedCount?: number;
  changedCount?: number;
  updatedCount?: number;
  unchangedCount?: number;
  skippedByLimitCount?: number;
  samples?: GraphMetadataPatchSample[];
}

/**
 * Move graph result
 */
export interface MoveGraphResult {
  graphId: string;
  newParentGraphId?: string;
  success?: boolean;
}
