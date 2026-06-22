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

export interface KbConfig {
  // PSL learner
  kbPslLearningRate: number;
  kbPslTolerance: number;
  kbPslBatchSize: number;
  kbPslSeed: number;
  kbPslMaxEpochs: number;
  kbPslWeightPriorStrength: number;
  kbPslWeightPriorMean: number;
  kbPslDefaultRuleWeight: number;
  kbLearningEnabled: boolean;

  // Evidence priors
  kbEvidencePriorStrength: number;
  kbStructuralPriorStrength: number;
  kbAssertedPriorStrength: number;

  // Source trust (0..1)
  kbTrustEmailFrom: number;
  kbTrustEmailToCc: number;
  kbTrustStructuredUpload: number;
  kbTrustPdfOffice: number;
  kbTrustEmailBody: number;
  kbTrustLlmExtraction: number;
  kbTrustWebScrape: number;
  kbTrustDefault: number;

  // Structural
  kbBelongsToOrgStrength: number;

  // MEBN
  kbMebnLearningInterval: number;

  // Opinion prune policy (P6 in PruneCompactOrchestrator)
  kbPrunePolicyMinBelief: number;
  kbPrunePolicyMaxUncertainty: number;
  kbPrunePolicyMinExpectation: number;
  kbPrunePolicyPruneSuppressedBand: boolean;

  // Personal email domains
  kbPersonalEmailDomains: string[];
}

@Injectable({
  providedIn: 'root'
})
export class KbConfigService {
  private readonly baseUrl = '/api/kb-config';

  constructor(private http: HttpClient) {}

  getConfig(): Observable<KbConfig> {
    return this.http.get<KbConfig>(this.baseUrl);
  }

  getDefaults(): Observable<KbConfig> {
    return this.http.get<KbConfig>(`${this.baseUrl}/defaults`);
  }

  saveConfig(config: Partial<KbConfig>): Observable<KbConfig> {
    return this.http.post<KbConfig>(this.baseUrl, config);
  }

  createDefaultConfig(): KbConfig {
    return {
      kbPslLearningRate: 0.01,
      kbPslTolerance: 1e-4,
      kbPslBatchSize: 32,
      kbPslSeed: 42,
      kbPslMaxEpochs: 100,
      kbPslWeightPriorStrength: 1.0,
      kbPslWeightPriorMean: 0.5,
      kbPslDefaultRuleWeight: 0.7,
      kbLearningEnabled: true,
      kbEvidencePriorStrength: 0.8,
      kbStructuralPriorStrength: 0.6,
      kbAssertedPriorStrength: 0.9,
      kbTrustEmailFrom: 0.7,
      kbTrustEmailToCc: 0.5,
      kbTrustStructuredUpload: 0.9,
      kbTrustPdfOffice: 0.8,
      kbTrustEmailBody: 0.4,
      kbTrustLlmExtraction: 0.6,
      kbTrustWebScrape: 0.3,
      kbTrustDefault: 0.5,
      kbBelongsToOrgStrength: 0.8,
      kbMebnLearningInterval: 100,
      kbPrunePolicyMinBelief: 0.10,
      kbPrunePolicyMaxUncertainty: 0.80,
      kbPrunePolicyMinExpectation: 0.15,
      kbPrunePolicyPruneSuppressedBand: true,
      kbPersonalEmailDomains: ['gmail.com', 'yahoo.com', 'hotmail.com', 'outlook.com']
    };
  }
}
