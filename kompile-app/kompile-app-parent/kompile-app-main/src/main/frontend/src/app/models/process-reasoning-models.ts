export interface ProcessReasoningFields {
  reasoningRank?: number | null;
  reasoningProjection?: string | null;
  reasoningFamily?: string | null;
  hybridScore?: number | null;
  hybridReasoning?: ProcessHybridReasoning | null;
  entailmentScore?: number | null;
  processCaseCount?: number | null;
  processActivityCount?: number | null;
  directlyFollowsCount?: number | null;
  acceptedPrecedenceCount?: number | null;
  entailedOnlyPrecedenceCount?: number | null;
  sourceGraphRelationIds?: string[];
  reasoningTraceId?: string | null;
  reasoningTraceArtifactName?: string | null;
}

export interface ProcessHybridActivityReasoning {
  activity: string;
  score: number;
  pslScore: number;
  bayesianScore: number;
  pslStructuralScore: number;
  bayesianStructuralScore: number;
  semanticScore: number;
  embedded: boolean;
}

export interface ProcessHybridReasoning {
  interpretation: string;
  score: number;
  pslScore: number;
  bayesianScore: number;
  pslStructuralScore: number;
  bayesianStructuralScore: number;
  semanticScore: number;
  semanticMode: 'ACTIVITY_CENTROID' | 'STRUCTURAL_ONLY' | string;
  embeddingSource?: string | null;
  embeddingModel?: string | null;
  contextualizedActivityCount?: number;
  directlyEmbeddedActivityCount?: number;
  inferredEmbeddingActivityCount?: number;
  embeddedActivityCount: number;
  activityCount: number;
  structuralWeight: number;
  semanticWeight: number;
  pslAvailable: boolean;
  bayesianAvailable: boolean;
  activities: ProcessHybridActivityReasoning[];
  warnings: string[];
}

export interface ProcessSuggestionSummary extends ProcessReasoningFields {
  id: string;
  factSheetId?: number | null;
  name: string;
  description?: string;
  discoverySource?: string;
  confidence: number;
  learnedScore?: number | null;
  sourceGraphNodeIds?: string[];
  accepted?: boolean | null;
}

export interface ProcessSuggestionListResponse<T extends ProcessSuggestionSummary = ProcessSuggestionSummary> {
  count: number;
  suggestions: T[];
}

export interface ProcessReasoningOpinion {
  belief?: number;
  disbelief?: number;
  uncertainty?: number;
  baseRate?: number;
  expectation?: number;
}

export interface ProcessReasoningTraceStep {
  kind: string;
  conclusion: string;
  operation?: string;
  confidence: number;
  source?: string | null;
  premises?: ProcessReasoningTraceStep[];
  opinion?: ProcessReasoningOpinion | null;
  meta?: Record<string, string>;
}

export interface ProcessReasoningTrace {
  conclusion: ProcessReasoningTraceStep;
  size: number;
  depth: number;
}

export interface FlatProcessReasoningStep extends ProcessReasoningTraceStep {
  depthLevel: number;
}

export function flattenProcessReasoningTrace(
  trace: ProcessReasoningTrace | null | undefined,
  maxSteps = 200
): FlatProcessReasoningStep[] {
  if (!trace?.conclusion || maxSteps <= 0) {
    return [];
  }

  const flattened: FlatProcessReasoningStep[] = [];
  const visit = (step: ProcessReasoningTraceStep, depthLevel: number): void => {
    if (flattened.length >= maxSteps) {
      return;
    }
    flattened.push({ ...step, depthLevel });
    for (const premise of step.premises ?? []) {
      visit(premise, depthLevel + 1);
    }
  };
  visit(trace.conclusion, 0);
  return flattened;
}

export function rankedProcessHybridActivities(
  fields: ProcessReasoningFields | null | undefined
): ProcessHybridActivityReasoning[] {
  return [...(fields?.hybridReasoning?.activities ?? [])].sort((left, right) =>
    right.score - left.score || left.activity.localeCompare(right.activity));
}

export function processHybridModeLabel(
  reasoning: ProcessHybridReasoning | null | undefined
): string {
  return reasoning?.semanticMode === 'ACTIVITY_CENTROID'
    ? 'Semantic centroid'
    : 'Structural only';
}

export function sortProcessSuggestions<T extends ProcessSuggestionSummary>(items: T[]): T[] {
  return [...items].sort((left, right) => {
    const leftRank = left.reasoningRank;
    const rightRank = right.reasoningRank;
    if (leftRank != null || rightRank != null) {
      if (leftRank == null) return 1;
      if (rightRank == null) return -1;
      if (leftRank !== rightRank) return leftRank - rightRank;
    }

    const leftScore = left.learnedScore ?? left.confidence ?? 0;
    const rightScore = right.learnedScore ?? right.confidence ?? 0;
    if (leftScore !== rightScore) return rightScore - leftScore;
    return (left.name ?? '').localeCompare(right.name ?? '');
  });
}
