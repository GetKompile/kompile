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

import { Component, Input, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatStepperModule, MatStepper } from '@angular/material/stepper';
import { MatRadioModule } from '@angular/material/radio';
import { MatDividerModule } from '@angular/material/divider';
import { GraphRulesService, GraphRuleConfig } from '@shared/services/graph-rules.service';

/**
 * CRUD UI for the reactive graph-rules engine (graph-as-asset Phase 4/8): list rules, create
 * CHANGESET-threshold or per-MUTATION rules via a guided 3-step wizard, toggle enabled, delete.
 * The action (LOG / WEBHOOK) fires when the rule matches a graph change event.
 */
@Component({
  selector: 'app-graph-rules-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatStepperModule,
    MatRadioModule,
    MatDividerModule
  ],
  templateUrl: './graph-rules-panel.component.html',
  styleUrls: ['./graph-rules-panel.component.css']
})
export class GraphRulesPanelComponent implements OnInit {
  /** When set, new rules default to this fact-sheet scope. */
  @Input() factSheetId: number | null = null;

  @ViewChild('wizardStepper') wizardStepper!: MatStepper;

  rules: GraphRuleConfig[] = [];
  loading = false;
  showWizard = false;
  creating = false;

  // Step 1 — trigger type
  step1Form!: FormGroup;
  // Step 2 — condition (fields shown conditionally)
  step2Form!: FormGroup;
  // Step 3 — action + rule name
  step3Form!: FormGroup;

  readonly mutationTypeOptions = [
    { value: 'NODE_CREATED',  label: 'Node created' },
    { value: 'NODE_UPDATED',  label: 'Node updated' },
    { value: 'NODE_DELETED',  label: 'Node deleted' },
    { value: 'EDGE_CREATED',  label: 'Edge created' },
    { value: 'EDGE_UPDATED',  label: 'Edge updated' },
    { value: 'EDGE_DELETED',  label: 'Edge deleted' },
  ];

  readonly entityKindOptions = [
    { value: null,   label: 'Any' },
    { value: 'NODE', label: 'Node' },
    { value: 'EDGE', label: 'Edge' },
  ];

  constructor(
    private svc: GraphRulesService,
    private snackBar: MatSnackBar,
    private fb: FormBuilder
  ) {}

  ngOnInit(): void {
    this.refresh();
    this.buildForms();
  }

  private buildForms(): void {
    this.step1Form = this.fb.group({
      triggerType: ['CHANGESET', Validators.required]
    });
    this.step2Form = this.fb.group({
      // CHANGESET fields
      minNodesCreated: [null],
      minEdgesCreated: [null],
      // MUTATION fields
      onMutationType: [null],
      onEntityKind:   [null],
      onEntityType:   [null]
    });
    this.step3Form = this.fb.group({
      actionType:   ['LOG', Validators.required],
      actionTarget: [null],
      name:         ['', [Validators.required, Validators.minLength(1)]]
    });
  }

  get triggerType(): string {
    return this.step1Form.get('triggerType')?.value ?? 'CHANGESET';
  }

  get actionType(): string {
    return this.step3Form.get('actionType')?.value ?? 'LOG';
  }

  refresh(): void {
    this.loading = true;
    this.svc.list().subscribe({
      next: (r) => { this.rules = r; this.loading = false; },
      error: (e) => { this.loading = false; this.error('Failed to load rules', e); }
    });
  }

  openWizard(): void {
    this.showWizard = true;
    this.buildForms();
    // Reset stepper after it renders
    setTimeout(() => { if (this.wizardStepper) this.wizardStepper.reset(); }, 0);
  }

  cancelWizard(): void {
    this.showWizard = false;
  }

  create(): void {
    if (this.step3Form.invalid) { return; }
    const s1 = this.step1Form.value;
    const s2 = this.step2Form.value;
    const s3 = this.step3Form.value;

    const payload: GraphRuleConfig = {
      name:        s3.name.trim(),
      enabled:     true,
      triggerType: s1.triggerType,
      actionType:  s3.actionType,
      actionTarget: s3.actionType === 'WEBHOOK' ? (s3.actionTarget?.trim() || null) : null,
      factSheetId: this.factSheetId ?? null,
    };

    if (s1.triggerType === 'CHANGESET') {
      payload.minNodesCreated = s2.minNodesCreated != null && s2.minNodesCreated !== '' ? +s2.minNodesCreated : null;
      payload.minEdgesCreated = s2.minEdgesCreated != null && s2.minEdgesCreated !== '' ? +s2.minEdgesCreated : null;
    } else {
      payload.onMutationType = s2.onMutationType || null;
      payload.onEntityKind   = s2.onEntityKind   || null;
      payload.onEntityType   = s2.onEntityType?.trim() || null;
    }

    this.creating = true;
    this.svc.create(payload).subscribe({
      next: () => {
        this.creating = false;
        this.showWizard = false;
        this.refresh();
        this.ok('Rule created');
      },
      error: (e) => { this.creating = false; this.error('Create failed', e); }
    });
  }

  toggle(rule: GraphRuleConfig): void {
    if (!rule.ruleId) { return; }
    this.svc.setEnabled(rule.ruleId, !rule.enabled).subscribe({
      next: () => this.refresh(),
      error: (e) => this.error('Toggle failed', e)
    });
  }

  remove(rule: GraphRuleConfig): void {
    if (!rule.ruleId) { return; }
    this.svc.delete(rule.ruleId).subscribe({
      next: () => this.refresh(),
      error: (e) => this.error('Delete failed', e)
    });
  }

  /** Human-readable summary of a rule's match condition. */
  condition(r: GraphRuleConfig): string {
    if (r.triggerType === 'MUTATION') {
      const parts = [r.onMutationType, r.onEntityKind, r.onEntityType].filter((x) => !!x);
      return parts.length ? parts.join(' / ') : 'any mutation';
    }
    const parts: string[] = [];
    if (r.minNodesCreated != null) parts.push('≥' + r.minNodesCreated + ' nodes');
    if (r.minEdgesCreated != null) parts.push('≥' + r.minEdgesCreated + ' edges');
    return parts.length ? parts.join(', ') : 'any changeset';
  }

  private ok(msg: string): void {
    this.snackBar.open(msg, 'OK', { duration: 2500 });
  }

  private error(prefix: string, e: any): void {
    this.snackBar.open(`${prefix}: ${e?.error?.message || e?.message || 'error'}`, 'Dismiss', { duration: 5000 });
  }
}
