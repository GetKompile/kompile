import {
  ProcessReasoningTrace,
  ProcessSuggestionSummary,
  flattenProcessReasoningTrace,
  processHybridModeLabel,
  rankedProcessHybridActivities,
  sortProcessSuggestions
} from './process-reasoning-models';

describe('process reasoning models', () => {
  it('sorts persisted ranks before score-only candidates', () => {
    const candidates: ProcessSuggestionSummary[] = [
      { id: 'score-only', name: 'Score only', confidence: 0.99 },
      { id: 'rank-two', name: 'Second', confidence: 0.95, reasoningRank: 2 },
      { id: 'rank-one', name: 'First', confidence: 0.60, reasoningRank: 1 }
    ];

    expect(sortProcessSuggestions(candidates).map(item => item.id)).toEqual([
      'rank-one', 'rank-two', 'score-only'
    ]);
  });

  it('uses learned score and then confidence when no rank is persisted', () => {
    const candidates: ProcessSuggestionSummary[] = [
      { id: 'confidence', name: 'Confidence', confidence: 0.8 },
      { id: 'learned', name: 'Learned', confidence: 0.4, learnedScore: 0.9 }
    ];

    expect(sortProcessSuggestions(candidates).map(item => item.id)).toEqual([
      'learned', 'confidence'
    ]);
  });

  it('flattens nested reasoning premises in walk order with depth', () => {
    const trace: ProcessReasoningTrace = {
      size: 3,
      depth: 3,
      conclusion: {
        kind: 'CONCLUSION',
        conclusion: 'process candidate',
        confidence: 0.9,
        premises: [{
          kind: 'INFERENCE',
          conclusion: 'precedence accepted',
          confidence: 0.8,
          premises: [{
            kind: 'EVIDENCE',
            conclusion: 'relation observed',
            confidence: 1.0
          }]
        }]
      }
    };

    const steps = flattenProcessReasoningTrace(trace);

    expect(steps.map(step => step.conclusion)).toEqual([
      'process candidate', 'precedence accepted', 'relation observed'
    ]);
    expect(steps.map(step => step.depthLevel)).toEqual([0, 1, 2]);
    expect(flattenProcessReasoningTrace(trace, 2).length).toBe(2);
  });

  it('ranks and labels detailed hybrid activity interpretations', () => {
    const candidate: ProcessSuggestionSummary = {
      id: 'hybrid', name: 'Hybrid', confidence: 0.8,
      hybridReasoning: {
        interpretation: 'ACTIVITY_ACTIVATION_CONSENSUS',
        score: 0.72, pslScore: 0.70, bayesianScore: 0.74,
        pslStructuralScore: 0.65, bayesianStructuralScore: 0.69, semanticScore: 0.8,
        semanticMode: 'ACTIVITY_CENTROID', embeddedActivityCount: 2, activityCount: 2,
        structuralWeight: 0.6, semanticWeight: 0.4,
        pslAvailable: true, bayesianAvailable: true, warnings: [],
        activities: [
          { activity: 'Close', score: 0.6, pslScore: 0.58, bayesianScore: 0.62,
            pslStructuralScore: 0.5, bayesianStructuralScore: 0.55, semanticScore: 0.75, embedded: true },
          { activity: 'Approve', score: 0.84, pslScore: 0.82, bayesianScore: 0.86,
            pslStructuralScore: 0.8, bayesianStructuralScore: 0.83, semanticScore: 0.9, embedded: true }
        ]
      }
    };

    expect(rankedProcessHybridActivities(candidate).map(activity => activity.activity))
      .toEqual(['Approve', 'Close']);
    expect(processHybridModeLabel(candidate.hybridReasoning)).toBe('Semantic centroid');
    expect(processHybridModeLabel({ ...candidate.hybridReasoning!, semanticMode: 'STRUCTURAL_ONLY' }))
      .toBe('Structural only');
  });
});
