import { of } from 'rxjs';

import { ProcessDiscoverySuggestionsComponent } from './process-discovery-suggestions.component';
import { ProcessEngineService } from '@shared/services/process-engine.service';
import { GraphService } from '@shared/services/graph.service';

describe('ProcessDiscoverySuggestionsComponent reasoning candidates', () => {
  let component: ProcessDiscoverySuggestionsComponent;
  let processEngine: jasmine.SpyObj<ProcessEngineService>;

  beforeEach(() => {
    processEngine = jasmine.createSpyObj<ProcessEngineService>('ProcessEngineService', [
      'listStoredSuggestions', 'getStoredSuggestionTrace'
    ]);
    processEngine.listStoredSuggestions.and.returnValue(of({
      count: 2,
      suggestions: [
        { id: 'second', name: 'Second', confidence: 0.95, reasoningRank: 2 },
        {
          id: 'first', name: 'First', confidence: 0.75, reasoningRank: 1,
          reasoningTraceId: 'trace-first', reasoningProjection: 'ACTIVITY_FLOW',
          hybridReasoning: {
            interpretation: 'ACTIVITY_ACTIVATION_CONSENSUS', score: 0.71,
            pslScore: 0.69, bayesianScore: 0.73,
            pslStructuralScore: 0.64, bayesianStructuralScore: 0.68, semanticScore: 0.8,
            semanticMode: 'ACTIVITY_CENTROID', embeddedActivityCount: 2, activityCount: 2,
            structuralWeight: 0.6, semanticWeight: 0.4,
            pslAvailable: true, bayesianAvailable: true, warnings: [],
            activities: [
              { activity: 'Close', score: 0.62, pslScore: 0.6, bayesianScore: 0.64,
                pslStructuralScore: 0.55, bayesianStructuralScore: 0.58,
                semanticScore: 0.75, embedded: true },
              { activity: 'Approve', score: 0.8, pslScore: 0.78, bayesianScore: 0.82,
                pslStructuralScore: 0.74, bayesianStructuralScore: 0.77,
                semanticScore: 0.88, embedded: true }
            ]
          }
        }
      ]
    }));
    processEngine.getStoredSuggestionTrace.and.returnValue(of({
      size: 2,
      depth: 2,
      conclusion: {
        kind: 'CONCLUSION', conclusion: 'First', confidence: 0.9,
        premises: [{ kind: 'EVIDENCE', conclusion: 'relation observed', confidence: 0.8 }]
      }
    }));

    component = new ProcessDiscoverySuggestionsComponent(
      processEngine,
      jasmine.createSpyObj<GraphService>('GraphService', ['getNode']),
      jasmine.createSpyObj('HttpClient', ['get', 'post']),
      jasmine.createSpyObj('MatSnackBar', ['open'])
    );
  });

  it('shows persisted reasoning rank before a higher raw score', () => {
    component.loadSuggestions();

    expect(component.suggestions.map(suggestion => suggestion.id)).toEqual(['first', 'second']);
    expect(component.hasReasoningSummary(component.suggestions[0] as any)).toBeTrue();
  });

  it('loads a walkable reasoning trace for a suggestion', () => {
    component.loadSuggestions();
    component.toggleReasoningTrace(component.suggestions[0] as any);

    expect(processEngine.getStoredSuggestionTrace).toHaveBeenCalledWith('first');
    expect(component.reasoningSteps(component.suggestions[0] as any).map(step => step.depthLevel))
      .toEqual([0, 1]);
  });

  it('exposes ranked native HybridReasoner activity components', () => {
    component.loadSuggestions();
    const suggestion = component.suggestions[0] as any;

    expect(component.hybridModeLabel(suggestion.hybridReasoning)).toBe('Semantic centroid');
    expect(component.hybridActivities(suggestion).map(activity => activity.activity))
      .toEqual(['Approve', 'Close']);
  });
});
