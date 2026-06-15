/*
 *   Copyright 2025 Kompile Inc.
 *  Licensed under the Apache License, Version 2.0
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
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatSelectModule } from '@angular/material/select';
import { Subject, takeUntil } from 'rxjs';

import { ToolGatewayService } from '../../../services/tool-gateway.service';
import { ToolGatewayConfig, ToolGatewayRule, GatewayJudgeScore } from '../../../models/tool-gateway.models';

@Component({
  selector: 'app-tool-gateway-settings',
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
    MatCardModule,
    MatTooltipModule,
    MatExpansionModule,
    MatChipsModule,
    MatDividerModule,
    MatSelectModule
  ],
  templateUrl: './tool-gateway-settings.component.html',
  styleUrls: ['./tool-gateway-settings.component.scss']
})
export class ToolGatewaySettingsComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  config: ToolGatewayConfig;
  rules: ToolGatewayRule[] = [];

  loading = false;
  saving = false;
  error: string | null = null;
  successMessage: string | null = null;

  // Model configuration (separate fields so we don't accidentally send stale values)
  modelBaseUrl = '';
  modelApiKey = '';
  modelName = '';
  modelTemperature = 0.0;

  // Judge scores
  judgeScores: GatewayJudgeScore[] = [];
  showScores = false;
  scoresLoading = false;

  // New rule form
  showNewRuleForm = false;
  newRule: ToolGatewayRule = this.createEmptyRule();
  newToolPattern = '';

  constructor(private gatewayService: ToolGatewayService) {
    this.config = gatewayService.createDefaultConfig();
  }

  ngOnInit(): void {
    this.loadConfiguration();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadConfiguration(): void {
    this.loading = true;
    this.error = null;

    this.gatewayService.getConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.available) {
            this.config = response;
          } else {
            this.config = this.gatewayService.createDefaultConfig();
            this.config.available = false;
          }
          // Populate model fields from config
          if (this.config.model) {
            this.modelBaseUrl = this.config.model.baseUrl || '';
            this.modelName = this.config.model.modelName || '';
            this.modelTemperature = this.config.model.temperature ?? 0.0;
            // Never populate apiKey from response (server only sends apiKeySet boolean)
          }
          this.loading = false;
          if (this.config.available) {
            this.loadRules();
          }
        },
        error: (err) => {
          this.error = 'Failed to load tool gateway configuration: ' + (err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  loadRules(): void {
    this.gatewayService.getRules()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (rules) => {
          this.rules = rules;
        },
        error: (err) => {
          console.error('Failed to load rules:', err);
        }
      });
  }

  saveConfiguration(): void {
    this.saving = true;
    this.error = null;
    this.successMessage = null;

    // Build update payload including model config
    const payload: any = { ...this.config };
    const modelUpdate: any = {};
    if (this.modelBaseUrl) modelUpdate.baseUrl = this.modelBaseUrl;
    if (this.modelApiKey) modelUpdate.apiKey = this.modelApiKey;
    if (this.modelName) modelUpdate.modelName = this.modelName;
    modelUpdate.temperature = this.modelTemperature;
    payload.model = modelUpdate;

    this.gatewayService.updateConfig(payload)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.config = response;
          // Re-populate model fields from saved response
          if (this.config.model) {
            this.modelBaseUrl = this.config.model.baseUrl || '';
            this.modelName = this.config.model.modelName || '';
            this.modelTemperature = this.config.model.temperature ?? 0.0;
          }
          // Clear the API key field after save (server never echoes it back)
          this.modelApiKey = '';
          this.successMessage = 'Configuration saved successfully';
          this.saving = false;
          setTimeout(() => this.successMessage = null, 3000);
        },
        error: (err) => {
          this.error = 'Failed to save configuration: ' + (err.error?.message || err.message);
          this.saving = false;
        }
      });
  }

  toggleEnabled(): void {
    this.gatewayService.toggle(this.config.enabled)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.config.enabled = response.enabled;
          this.successMessage = `Tool gateway ${response.enabled ? 'enabled' : 'disabled'}`;
          setTimeout(() => this.successMessage = null, 3000);
        },
        error: (err) => {
          this.config.enabled = !this.config.enabled;
          this.error = 'Failed to toggle tool gateway: ' + (err.error?.message || err.message);
        }
      });
  }

  // ── Rule management ─────────────────────────────��────────────

  addToolPattern(): void {
    const pattern = this.newToolPattern.trim();
    if (pattern && !this.newRule.toolPatterns.includes(pattern)) {
      this.newRule.toolPatterns.push(pattern);
      this.newToolPattern = '';
    }
  }

  removeToolPattern(pattern: string): void {
    const idx = this.newRule.toolPatterns.indexOf(pattern);
    if (idx >= 0) {
      this.newRule.toolPatterns.splice(idx, 1);
    }
  }

  submitNewRule(): void {
    if (!this.newRule.id || !this.newRule.condition) return;

    this.gatewayService.addRule(this.newRule)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.successMessage = `Rule "${this.newRule.id}" added`;
          setTimeout(() => this.successMessage = null, 3000);
          this.showNewRuleForm = false;
          this.newRule = this.createEmptyRule();
          this.loadRules();
        },
        error: (err) => {
          this.error = 'Failed to add rule: ' + (err.error?.message || err.message);
        }
      });
  }

  deleteRule(ruleId: string): void {
    this.gatewayService.deleteRule(ruleId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.successMessage = `Rule "${ruleId}" deleted`;
          setTimeout(() => this.successMessage = null, 3000);
          this.loadRules();
        },
        error: (err) => {
          this.error = 'Failed to delete rule: ' + (err.error?.message || err.message);
        }
      });
  }

  reloadRules(): void {
    this.gatewayService.reloadRules()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.successMessage = 'Rules reloaded from disk';
          setTimeout(() => this.successMessage = null, 3000);
          this.loadRules();
          this.loadConfiguration();
        },
        error: (err) => {
          this.error = 'Failed to reload rules: ' + (err.error?.message || err.message);
        }
      });
  }

  loadScores(): void {
    this.scoresLoading = true;
    this.showScores = true;
    this.gatewayService.getScores()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (scores) => {
          this.judgeScores = scores;
          this.scoresLoading = false;
        },
        error: (err) => {
          console.error('Failed to load scores:', err);
          this.scoresLoading = false;
        }
      });
  }

  getAvgScore(field: 'correctness' | 'completeness'): string {
    if (this.judgeScores.length === 0) return '-';
    const sum = this.judgeScores.reduce((acc, s) => acc + s[field], 0);
    return (sum / this.judgeScores.length).toFixed(1);
  }

  private createEmptyRule(): ToolGatewayRule {
    return {
      id: '',
      description: '',
      toolPatterns: [],
      condition: '',
      action: 'BLOCK',
      blockMessage: null,
      rewriteInstructions: null,
      priority: 0,
      enabled: true
    };
  }
}
