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

export interface GraphEvalStatus {
  extractionAvailable: boolean;
  evaluatorCount: number;
  evaluators: GraphEvaluatorInfo[];
}

export interface GraphEvaluatorInfo {
  name: string;
  type: string;
  requiresLlm: boolean;
  requiresGroundTruth: boolean;
}

export interface GraphEvalRequest {
  sourceText: string;
  groundTruth?: GraphData;
  fuzzyMatch?: boolean;
  similarityThreshold?: number;
}

export interface GraphEvalResponse {
  success: boolean;
  message?: string;
  extractedGraph?: GraphData;
  evaluationResults?: GraphEvalResult[];
  evaluationTimeMs?: number;
}

export interface GraphData {
  entities: GraphEntity[];
  relationships: GraphRelationship[];
}

export interface GraphEntity {
  id: string;
  title: string;
  type: string;
  description?: string;
  confidence?: number;
}

export interface GraphRelationship {
  source: string;
  target: string;
  type: string;
  description?: string;
  confidence?: number;
}

export interface GraphEvalResult {
  evaluatorName: string;
  evaluationType: string;
  passed: boolean;
  score: number;
  precision: number;
  recall: number;
  f1: number;
  truePositives: number;
  falsePositives: number;
  falseNegatives: number;
  threshold: number;
  explanation: string;
  evaluationTimeMs: number;
  metrics: Record<string, number>;
  entityMatches?: EntityMatch[];
  relationshipMatches?: RelationshipMatch[];
  error?: string;
}

export interface EntityMatch {
  extractedTitle?: string;
  expectedTitle?: string;
  extractedType?: string;
  expectedType?: string;
  matchType: 'TRUE_POSITIVE' | 'FALSE_POSITIVE' | 'FALSE_NEGATIVE' | 'TYPE_MISMATCH';
  similarity: number;
}

export interface RelationshipMatch {
  extractedSource?: string;
  extractedTarget?: string;
  extractedType?: string;
  expectedSource?: string;
  expectedTarget?: string;
  expectedType?: string;
  matchType: 'TRUE_POSITIVE' | 'FALSE_POSITIVE' | 'FALSE_NEGATIVE' | 'TYPE_MISMATCH';
}
