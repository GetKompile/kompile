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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSliderModule } from '@angular/material/slider';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatTabsModule } from '@angular/material/tabs';
import { MatSelectModule } from '@angular/material/select';
import { Subject, takeUntil } from 'rxjs';

import { GraphEvalService } from '../../services/graph-eval.service';
import {
  GraphEvalStatus,
  GraphEvalResponse,
  GraphEntity,
  GraphRelationship,
  GraphEvalResult,
  EntityMatch,
  RelationshipMatch
} from '../../models/graph-eval.models';

@Component({
  selector: 'app-graph-eval-debugger',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    MatSliderModule,
    MatCardModule,
    MatTooltipModule,
    MatExpansionModule,
    MatDividerModule,
    MatChipsModule,
    MatTabsModule,
    MatSelectModule
  ],
  templateUrl: './graph-eval-debugger.component.html',
  styleUrls: ['./graph-eval-debugger.component.scss']
})
export class GraphEvalDebuggerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  status: GraphEvalStatus | null = null;
  sourceText = '';
  fuzzyMatch = false;
  similarityThreshold = 0.85;

  // Ground truth editor
  groundTruthEntities: GraphEntity[] = [];
  groundTruthRelationships: GraphRelationship[] = [];

  // New entity/relationship form
  newEntity: Partial<GraphEntity> = { type: 'PERSON' };
  newRelationship: Partial<GraphRelationship> = { type: 'WORKS_AT' };

  // Extracted results
  result: GraphEvalResponse | null = null;

  // State
  loading = false;
  running = false;
  error: string | null = null;

  entityTypes = ['PERSON', 'ORGANIZATION', 'LOCATION', 'DATE', 'PRODUCT', 'EVENT', 'CONCEPT', 'TABLE'];
  relationshipTypes = ['WORKS_AT', 'LOCATED_IN', 'FOUNDED_BY', 'PART_OF', 'RELATED_TO', 'CONTAINS', 'AUTHORED_BY', 'ADDRESSED_TO'];

  constructor(private graphEvalService: GraphEvalService) {}

  ngOnInit(): void {
    this.loadStatus();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadStatus(): void {
    this.loading = true;
    this.graphEvalService.getStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.status = status;
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load graph eval status: ' + (err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  addEntity(): void {
    if (!this.newEntity.title?.trim()) return;
    this.groundTruthEntities.push({
      id: 'gt-' + (this.groundTruthEntities.length + 1),
      title: this.newEntity.title!.trim(),
      type: this.newEntity.type || 'CONCEPT',
      description: this.newEntity.description
    });
    this.newEntity = { type: this.newEntity.type };
  }

  removeEntity(index: number): void {
    this.groundTruthEntities.splice(index, 1);
  }

  addRelationship(): void {
    if (!this.newRelationship.source?.trim() || !this.newRelationship.target?.trim()) return;
    this.groundTruthRelationships.push({
      source: this.newRelationship.source!.trim(),
      target: this.newRelationship.target!.trim(),
      type: this.newRelationship.type || 'RELATED_TO'
    });
    this.newRelationship = { type: this.newRelationship.type };
  }

  removeRelationship(index: number): void {
    this.groundTruthRelationships.splice(index, 1);
  }

  runEvaluation(): void {
    if (!this.sourceText.trim()) return;

    this.running = true;
    this.error = null;
    this.result = null;

    const hasGroundTruth = this.groundTruthEntities.length > 0 || this.groundTruthRelationships.length > 0;

    this.graphEvalService.runEvaluation({
      sourceText: this.sourceText,
      groundTruth: hasGroundTruth ? {
        entities: this.groundTruthEntities,
        relationships: this.groundTruthRelationships
      } : undefined,
      fuzzyMatch: this.fuzzyMatch,
      similarityThreshold: this.similarityThreshold
    }).pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.result = response;
          this.running = false;
        },
        error: (err) => {
          this.error = 'Evaluation failed: ' + (err.error?.message || err.message);
          this.running = false;
        }
      });
  }

  handleFileUpload(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length) return;
    const file = input.files[0];
    const reader = new FileReader();
    reader.onload = () => {
      this.sourceText = reader.result as string;
    };
    reader.readAsText(file);
    input.value = '';
  }

  handleGroundTruthUpload(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length) return;
    const file = input.files[0];
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const data = JSON.parse(reader.result as string);
        if (data.entities) this.groundTruthEntities = data.entities;
        if (data.relationships) this.groundTruthRelationships = data.relationships;
      } catch (e) {
        this.error = 'Invalid JSON file';
      }
    };
    reader.readAsText(file);
    input.value = '';
  }

  exportGroundTruth(): void {
    const data = JSON.stringify({
      entities: this.groundTruthEntities,
      relationships: this.groundTruthRelationships
    }, null, 2);
    const blob = new Blob([data], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'ground-truth.json';
    a.click();
    URL.revokeObjectURL(url);
  }

  getScoreColor(score: number): string {
    if (score >= 0.8) return '#4caf50';
    if (score >= 0.5) return '#ff9800';
    return '#f44336';
  }

  getMatchTypeColor(matchType: string): string {
    switch (matchType) {
      case 'TRUE_POSITIVE': return '#4caf50';
      case 'FALSE_POSITIVE': return '#f44336';
      case 'FALSE_NEGATIVE': return '#ff9800';
      case 'TYPE_MISMATCH': return '#9c27b0';
      default: return '#666';
    }
  }

  getMatchTypeLabel(matchType: string): string {
    switch (matchType) {
      case 'TRUE_POSITIVE': return 'TP';
      case 'FALSE_POSITIVE': return 'FP';
      case 'FALSE_NEGATIVE': return 'FN';
      case 'TYPE_MISMATCH': return 'Type Mismatch';
      default: return matchType;
    }
  }

  formatPercent(value: number): string {
    return (value * 100).toFixed(1) + '%';
  }
}
