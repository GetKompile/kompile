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

import { Component, Inject, OnInit } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { OPTIMIZATION_PASS_GROUPS, OptimizationPassDetail, getCategoryColor } from '../../models/api-models';

export interface OptimizeDialogData {
  modelId: string;
  isReoptimize: boolean;
}

export interface OptimizeDialogResult {
  selectedPasses: string[];
  maxIterations: number;
  preset: string;
}

interface GroupState {
  group: OptimizationPassDetail;
  expanded: boolean;
  selectedSubPasses: Set<string>;
}

const PRESETS: { [key: string]: { name: string; description: string; groups: string[] } } = {
  'NONE': { name: 'None', description: 'No passes selected', groups: [] },
  'BASIC': {
    name: 'Basic',
    description: 'Cleanup optimizations only',
    groups: ['dead_code_elimination', 'constant_folding', 'algebraic_simplification', 'identity_removal']
  },
  'TRANSFORMER': {
    name: 'Transformer',
    description: 'Basic + attention, normalization, and linear fusion',
    groups: ['dead_code_elimination', 'constant_folding', 'algebraic_simplification', 'identity_removal',
             'attention_fusion', 'normalization_fusion', 'linear_fusion']
  },
  'GPU': {
    name: 'GPU',
    description: 'Transformer + cuDNN replacement',
    groups: ['dead_code_elimination', 'constant_folding', 'algebraic_simplification', 'identity_removal',
             'attention_fusion', 'normalization_fusion', 'linear_fusion', 'cudnn_replacement']
  },
  'FULL': {
    name: 'Full',
    description: 'All available optimization passes',
    groups: OPTIMIZATION_PASS_GROUPS.map(g => g.id)
  }
};

@Component({
  selector: 'app-optimize-dialog',
  standalone: false,
  templateUrl: './optimize-dialog.component.html',
  styleUrls: ['./optimize-dialog.component.css']
})
export class OptimizeDialogComponent implements OnInit {

  groupStates: GroupState[] = [];
  maxIterations = 3;
  selectedPreset = 'BASIC';
  presetKeys = Object.keys(PRESETS);
  presets = PRESETS;
  getCategoryColor = getCategoryColor;

  constructor(
    public dialogRef: MatDialogRef<OptimizeDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: OptimizeDialogData
  ) {}

  ngOnInit(): void {
    this.groupStates = OPTIMIZATION_PASS_GROUPS.map(group => ({
      group,
      expanded: false,
      selectedSubPasses: new Set<string>()
    }));
    this.applyPreset('BASIC');
  }

  applyPreset(presetId: string): void {
    this.selectedPreset = presetId;
    const preset = PRESETS[presetId];
    if (!preset) return;

    const enabledGroupIds = new Set(preset.groups);
    for (const gs of this.groupStates) {
      if (enabledGroupIds.has(gs.group.id)) {
        this.selectAllSubPasses(gs);
      } else {
        gs.selectedSubPasses.clear();
      }
    }
  }

  isGroupFullySelected(gs: GroupState): boolean {
    const subs = gs.group.subPasses || [];
    return subs.length > 0 && gs.selectedSubPasses.size === subs.length;
  }

  isGroupPartiallySelected(gs: GroupState): boolean {
    const subs = gs.group.subPasses || [];
    return gs.selectedSubPasses.size > 0 && gs.selectedSubPasses.size < subs.length;
  }

  isGroupEmpty(gs: GroupState): boolean {
    return gs.selectedSubPasses.size === 0;
  }

  toggleGroup(gs: GroupState): void {
    if (this.isGroupFullySelected(gs)) {
      gs.selectedSubPasses.clear();
    } else {
      this.selectAllSubPasses(gs);
    }
    this.selectedPreset = 'CUSTOM';
  }

  toggleSubPass(gs: GroupState, subPassId: string): void {
    if (gs.selectedSubPasses.has(subPassId)) {
      gs.selectedSubPasses.delete(subPassId);
    } else {
      gs.selectedSubPasses.add(subPassId);
    }
    this.selectedPreset = 'CUSTOM';
  }

  isSubPassSelected(gs: GroupState, subPassId: string): boolean {
    return gs.selectedSubPasses.has(subPassId);
  }

  private selectAllSubPasses(gs: GroupState): void {
    gs.selectedSubPasses.clear();
    for (const sub of gs.group.subPasses || []) {
      gs.selectedSubPasses.add(sub.id);
    }
  }

  getSelectedPassIds(): string[] {
    const passes: string[] = [];
    for (const gs of this.groupStates) {
      if (this.isGroupFullySelected(gs)) {
        // Send the group ID when all sub-passes are selected
        passes.push(gs.group.id);
      } else {
        // Send individual sub-pass IDs
        for (const subId of gs.selectedSubPasses) {
          passes.push(subId);
        }
      }
    }
    return passes;
  }

  getTotalSelectedCount(): number {
    let count = 0;
    for (const gs of this.groupStates) {
      count += gs.selectedSubPasses.size;
    }
    return count;
  }

  onCancel(): void {
    this.dialogRef.close(null);
  }

  onOptimize(): void {
    const result: OptimizeDialogResult = {
      selectedPasses: this.getSelectedPassIds(),
      maxIterations: this.maxIterations,
      preset: this.selectedPreset
    };
    this.dialogRef.close(result);
  }
}
