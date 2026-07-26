/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { of } from 'rxjs';

import { DiffPolicyPanelComponent } from './diff-policy-panel.component';
import { DiffPolicyService, DiffPolicyViolation } from '@shared/services/diff-policy.service';
import { DiffIndexService, DiffIndexEntry } from '@shared/services/diff-index.service';

describe('DiffPolicyPanelComponent', () => {
  let component: DiffPolicyPanelComponent;
  let policy: jasmine.SpyObj<DiffPolicyService>;
  let diff: jasmine.SpyObj<DiffIndexService>;

  const viol: DiffPolicyViolation = {
    id: 'v1', diffEntryId: 'd1', detector: 'path', ruleId: '**/*.env', severity: 'critical',
    riskScore: 1, agent: 'claude-code', source: 'transcript', sessionId: 's1', filePath: '/proj/.env',
    lineNumber: 0, matchedLine: null, message: 'Edit to a secret file', toolName: 'Edit',
    timestamp: '2026-01-01T00:00:00Z', detectedAt: '2026-01-02T00:00:00Z'
  };
  const entry: DiffIndexEntry = {
    id: 'd1', agent: 'claude-code', source: 'transcript', sessionId: 's1', sessionFingerprint: 'fp',
    projectDirectory: '/proj', filePath: '/proj/.env', toolName: 'Edit', diffType: 'edit',
    oldString: 'a', newString: 'b', unifiedDiff: null, timestamp: '2026-01-01T00:00:00Z',
    linesAdded: 1, linesRemoved: 0
  };

  beforeEach(() => {
    policy = jasmine.createSpyObj<DiffPolicyService>('DiffPolicyService',
      ['getRules', 'saveRules', 'scan', 'listViolations', 'stats', 'clearViolations']);
    diff = jasmine.createSpyObj<DiffIndexService>('DiffIndexService', ['getEntry']);

    policy.getRules.and.returnValue(of({ pathRules: [{ glob: '**/*.env', severity: 'critical', description: 'x' }], contentRulesText: 'BAN_DIFF: System.exit(', llmAvailable: true }));
    policy.stats.and.returnValue(of({ total: 1, bySeverity: { critical: 1 } }));
    policy.listViolations.and.returnValue(of([viol]));
    policy.scan.and.returnValue(of({ scannedDiffs: 3, violations: 1 }));
    policy.saveRules.and.returnValue(of({}));
    policy.clearViolations.and.returnValue(of({}));
    diff.getEntry.and.returnValue(of(entry));

    component = new DiffPolicyPanelComponent(policy, diff);
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('loads rules, stats and violations on init', () => {
    component.ngOnInit();
    expect(policy.getRules).toHaveBeenCalled();
    expect(policy.stats).toHaveBeenCalled();
    expect(policy.listViolations).toHaveBeenCalled();
    expect(component.violations.length).toBe(1);
    expect(component.severityCount('critical')).toBe(1);
  });

  it('reflects LLM availability from the rules response', () => {
    component.ngOnInit();
    expect(component.llmAvailable).toBeTrue();
  });

  it('runScan scans (with the LLM flag) then reloads stats + violations', () => {
    component.useLlm = true;
    component.runScan();
    expect(policy.scan).toHaveBeenCalledWith(jasmine.objectContaining({ useLlm: true }));
    expect(component.lastScan?.['violations']).toBe(1);
    expect(policy.listViolations).toHaveBeenCalled();
    expect(component.scanning).toBeFalse();
  });

  it('loadViolations forwards the file/severity/detector filters', () => {
    component.fileFilter = '**/*.env';
    component.severityFilter = 'critical';
    component.detectorFilter = 'path';
    component.loadViolations();
    expect(policy.listViolations).toHaveBeenCalledWith(
      jasmine.objectContaining({ filePath: '**/*.env', severity: 'critical', detector: 'path' }));
  });

  it('selecting a violation loads the underlying diff entry', () => {
    component.selectViolation(viol);
    expect(diff.getEntry).toHaveBeenCalledWith('d1');
    expect(component.selectedEntry?.id).toBe('d1');
  });

  it('adds and removes path rules', () => {
    component.rules = { pathRules: [], contentRulesText: '' };
    component.addPathRule();
    expect(component.rules.pathRules.length).toBe(1);
    component.removePathRule(0);
    expect(component.rules.pathRules.length).toBe(0);
  });

  it('saveRules drops blank globs', () => {
    component.rules = {
      pathRules: [{ glob: '', severity: 'info', description: '' }, { glob: '**/*.pem', severity: 'critical', description: '' }],
      contentRulesText: 'r'
    };
    component.saveRules();
    const arg = policy.saveRules.calls.mostRecent().args[0];
    expect(arg.pathRules.length).toBe(1);
    expect(arg.pathRules[0].glob).toBe('**/*.pem');
    expect(arg.contentRulesText).toBe('r');
  });

  it('exposes severity/detector styling + agent options', () => {
    expect(component.severityIcon('critical')).toBe('gpp_bad');
    expect(component.detectorIcon('path')).toBe('folder_off');
    expect(component.detectorIcon('llm')).toBe('smart_toy');
    expect(component.getFileName('/a/b.env')).toBe('b.env');
    expect(component.severityColor('error')).toBe('#cb2431');

    component.violations = [viol];
    expect(component.agentOptions).toEqual(['claude-code']);
  });
});
