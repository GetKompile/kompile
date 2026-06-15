/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * TypeScript models for Event Attribution & Prediction framework.
 * Maps to the Java domain objects in kompile-event-attribution.
 */

export type CausalEdgeType =
  | 'CAUSES'
  | 'TRIGGERS'
  | 'ENABLES'
  | 'CONTRIBUTES_TO'
  | 'PREVENTS'
  | 'CORRELATES_WITH'
  | 'INFLUENCES'
  | 'DERIVED_FROM';

export type AttributionConfidence =
  | 'DEFINITIVE'
  | 'HIGH'
  | 'MODERATE'
  | 'LOW'
  | 'INSUFFICIENT';

export type EvidenceType =
  | 'DIRECT_EXTRACTION'
  | 'GRAPH_STRUCTURAL'
  | 'TEMPORAL_PROXIMITY'
  | 'EMBEDDING_SIMILARITY'
  | 'LLM_INFERENCE'
  | 'INFLUENCE_PROPAGATION'
  | 'PROVENANCE_CHAIN'
  | 'COMMUNITY_CO_MEMBERSHIP'
  | 'CENTRALITY_SIGNAL';

export interface AttributionEvidence {
  evidenceType: EvidenceType;
  strength: number;
  edgeId?: string;
  sourceNodeId?: string;
  summary?: string;
  sourceSnippet?: string;
  sourceReference?: string;
  collectedAt?: string;
  metadata?: Record<string, any>;
}

export interface CausalHop {
  causeNodeId: string;
  causeTitle: string;
  effectNodeId: string;
  effectTitle: string;
  causalType: CausalEdgeType;
  strength: number;
  causeTimestamp?: string;
  effectTimestamp?: string;
  evidence: AttributionEvidence[];
  llmExplanation?: string;
}

export interface AttributionChain {
  chainId: string;
  targetEventNodeId: string;
  targetEventTitle: string;
  rootCauseNodeId: string;
  rootCauseTitle: string;
  hops: CausalHop[];
  overallConfidence: number;
  confidenceBand: AttributionConfidence;
  narrative?: string;
  computedAt?: string;
  depth: number;
}

export interface CounterfactualResult {
  removedNodeId: string;
  removedNodeTitle: string;
  targetStillReachable: boolean;
  survivingChainCount: number;
  confidenceDelta: number;
  explanation?: string;
  necessaryCause: boolean;
}

export interface AttributionResult {
  targetNodeId: string;
  targetTitle: string;
  chains: AttributionChain[];
  synthesizedExplanation?: string;
  influenceScores: Record<string, number>;
  counterfactuals: CounterfactualResult[];
  deadEnds: string[];
  computedAt: string;
  computationTimeMs: number;
  nodesVisited: number;
  edgesExamined: number;
  llmUsed: boolean;
}

export interface PredictedEvent {
  nodeId: string;
  title: string;
  probability: number;
  hopsFromSource: number;
  pathFromSource: string[];
  pathEdgeTypes: CausalEdgeType[];
  explanation?: string;
  evidence: AttributionEvidence[];
}

export interface PredictionResult {
  sourceNodeId: string;
  sourceTitle: string;
  predictions: PredictedEvent[];
  synthesizedForecast?: string;
  computedAt: string;
  computationTimeMs: number;
  nodesVisited: number;
  llmUsed: boolean;
}

export interface AttributionQueryRequest {
  targetNodeId: string;
  question?: string;
  factSheetId?: number;
  maxDepth?: number;
  maxChains?: number;
  minConfidence?: number;
  useLlm?: boolean;
  includeCounterfactuals?: boolean;
  allowedCausalTypes?: CausalEdgeType[];
  requiredEvidenceTypes?: EvidenceType[];
}

export interface PredictionQueryRequest {
  sourceNodeId: string;
  context?: string;
  factSheetId?: number;
  maxDepth?: number;
  maxPredictions?: number;
  minProbability?: number;
  useLlm?: boolean;
}

// ═══════════════════════════════════════════════════════════════════════════
// BAYESIAN NETWORK MODELS
// ═══════════════════════════════════════════════════════════════════════════

export interface InferenceStep {
  eliminatedVariable: string;
  eliminatedTitle?: string;
  factorsInvolved: number;
  factorVariables: string[];
  operation: string;
  priorValue?: number;
  posteriorValue?: number;
  contributionWeight?: number;
}

export interface MebnVariableMeta {
  mfragName: string;
  nodeRole: 'RESIDENT' | 'INPUT' | 'CONTEXT';
  rvName: string;
  entityType?: string;
  entityId?: string;
}

export interface BayesianInferenceResult {
  posteriors: Record<string, number>;
  evidence: Record<string, number>;
  networkStats: Record<string, any>;
  variableToNodeId: Record<string, string>;
  variableToTitle: Record<string, string>;
  variableToMebnMeta: Record<string, MebnVariableMeta>;
  inferenceTrace: InferenceStep[];
  priors: Record<string, number>;
  computedAt: string;
  computationTimeMs: number;
}

export interface MpeResult {
  assignments: Record<string, number>;
  posteriors: Record<string, number>;
  priors: Record<string, number>;
  evidence: Record<string, number>;
  inferenceTrace: InferenceStep[];
  variableToNodeId: Record<string, string>;
  variableToTitle: Record<string, string>;
  variableToMebnMeta?: Record<string, MebnVariableMeta>;
  networkStats: Record<string, any>;
  computedAt: string;
  computationTimeMs: number;
}

export interface BayesianQueryRequest {
  seedNodeIds: string[];
  queryNodeId?: string;
  evidence?: Record<string, number>;
  maxDepth?: number;
  maxNodes?: number;
}

// ═══════════════════════════════════════════════════════════════════════════
// MEBN (MULTI-ENTITY BAYESIAN NETWORK) MODELS
// ═══════════════════════════════════════════════════════════════════════════

export interface MebnQueryRequest {
  seedNodeIds: string[];
  evidence?: Record<string, number>;
  maxDepth?: number;
  maxNodes?: number;
}

export interface MebnTypeQueryRequest {
  seedNodeIds: string[];
  entityType: string;
  evidence?: Record<string, number>;
  maxDepth?: number;
  maxNodes?: number;
}

export interface SensitivityRequest {
  seedNodeIds: string[];
  queryNodeId: string;
  evidence?: Record<string, number>;
  epsilon?: number;
  maxDepth?: number;
  maxNodes?: number;
}

export interface WhatIfRequest {
  seedNodeIds: string[];
  hypotheticalEvidence: Record<string, number>;
  maxDepth?: number;
  maxNodes?: number;
}

export interface SensitivityResult {
  sensitivities: Record<string, number>;
  priors: Record<string, number>;
  baselinePosterior: number;
  queryPrior: number;
  queryNodeId: string;
  computationTimeMs: number;
}

export interface StructuredEvidence {
  type: string;
  description: string;
  score?: number;
  supportingNodeIds: string[];
}

export interface ProcessSuggestionBayesian {
  bayesianPosteriors: Record<string, number>;
  bayesianPriors: Record<string, number>;
  mebnMeta?: Record<string, MebnVariableMeta>;
  structuredEvidence: StructuredEvidence[];
}
