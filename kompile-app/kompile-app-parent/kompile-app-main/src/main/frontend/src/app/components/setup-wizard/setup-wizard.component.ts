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

import { Component, OnInit, OnDestroy, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpClientModule } from '@angular/common/http';
import { interval, Subscription } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { of } from 'rxjs';
import { BaseService } from '../../services/base.service';

// ==================== Interfaces ====================

export type StepState = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETE' | 'WARNING';

export interface StepStatus {
  stepNumber: number;
  name: string;
  description: string;
  status: StepState;
  complete: boolean;
  message: string | null;
  detail: string | null;
  action: string | null;
}

export interface SetupStatus {
  stagingServer: StepStatus;
  modelSource: StepStatus;
  embeddingModel: StepStatus;
  indexing: StepStatus;
  searchReady: StepStatus;
  setupComplete: boolean;
  wizardDismissed: boolean;
  currentStep: number;
  totalSteps: number;
}

@Component({
  selector: 'app-setup-wizard',
  standalone: true,
  imports: [CommonModule, HttpClientModule],
  template: `
    <div class="wizard-overlay" *ngIf="visible && status && !status.setupComplete">
      <div class="wizard-container">

        <!-- Header -->
        <div class="wizard-header">
          <div class="header-icon">
            <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 2L2 7l10 5 10-5-10-5z"></path>
              <path d="M2 17l10 5 10-5"></path>
              <path d="M2 12l10 5 10-5"></path>
            </svg>
          </div>
          <div class="header-text">
            <h2>Welcome to Kompile</h2>
            <p>Let's get your RAG application ready. Complete these steps to enable document search.</p>
          </div>
          <button class="dismiss-btn" (click)="dismiss()" title="Dismiss and continue to app">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>

        <!-- Progress Bar -->
        <div class="progress-section">
          <div class="progress-bar">
            <div class="progress-fill" [style.width.%]="getProgressPercent()"></div>
          </div>
          <span class="progress-text">{{ getCompletedCount() }} of {{ status.totalSteps }} steps complete</span>
        </div>

        <!-- Steps -->
        <div class="steps-container">

          <!-- Step 1: Staging Server -->
          <div class="step" [class.complete]="status.stagingServer.complete"
               [class.active]="status.currentStep === 1"
               [class.in-progress]="status.stagingServer.status === 'IN_PROGRESS'"
               [class.warning]="status.stagingServer.status === 'WARNING'">
            <div class="step-indicator">
              <div class="step-number" *ngIf="!status.stagingServer.complete && status.stagingServer.status !== 'IN_PROGRESS'">1</div>
              <svg *ngIf="status.stagingServer.complete" class="check-icon" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <polyline points="20 6 9 17 4 12"></polyline>
              </svg>
              <svg *ngIf="status.stagingServer.status === 'IN_PROGRESS'" class="spin-icon" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
              </svg>
            </div>
            <div class="step-content">
              <div class="step-header">
                <h3>{{ status.stagingServer.name }}</h3>
                <span class="step-badge" [class]="'badge-' + status.stagingServer.status.toLowerCase()">
                  {{ formatStatus(status.stagingServer.status) }}
                </span>
              </div>
              <p class="step-description">{{ status.stagingServer.description }}</p>
              <p class="step-message">{{ status.stagingServer.message }}</p>
              <p class="step-detail" *ngIf="status.stagingServer.detail">{{ status.stagingServer.detail }}</p>
              <div class="step-actions" *ngIf="!status.stagingServer.complete && status.stagingServer.status !== 'IN_PROGRESS'">
                <button class="action-btn primary" (click)="startStagingServer()" [disabled]="startingStagingServer">
                  {{ startingStagingServer ? 'Starting...' : 'Start Staging Server' }}
                </button>
              </div>
              <div class="step-actions" *ngIf="status.stagingServer.status === 'IN_PROGRESS'">
                <div class="loading-indicator">
                  <div class="loading-bar"><div class="loading-bar-progress"></div></div>
                  <span class="loading-text">Staging server is starting...</span>
                </div>
              </div>
            </div>
          </div>

          <!-- Connector -->
          <div class="step-connector" [class.complete]="status.stagingServer.complete"></div>

          <!-- Step 2: Model Source -->
          <div class="step" [class.complete]="status.modelSource.complete"
               [class.active]="status.currentStep === 2"
               [class.in-progress]="status.modelSource.status === 'IN_PROGRESS'"
               [class.warning]="status.modelSource.status === 'WARNING'">
            <div class="step-indicator">
              <div class="step-number" *ngIf="!status.modelSource.complete && status.modelSource.status !== 'IN_PROGRESS'">2</div>
              <svg *ngIf="status.modelSource.complete" class="check-icon" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <polyline points="20 6 9 17 4 12"></polyline>
              </svg>
              <svg *ngIf="status.modelSource.status === 'IN_PROGRESS'" class="spin-icon" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
              </svg>
            </div>
            <div class="step-content">
              <div class="step-header">
                <h3>{{ status.modelSource.name }}</h3>
                <span class="step-badge" [class]="'badge-' + status.modelSource.status.toLowerCase()">
                  {{ formatStatus(status.modelSource.status) }}
                </span>
              </div>
              <p class="step-description">{{ status.modelSource.description }}</p>
              <p class="step-message">{{ status.modelSource.message }}</p>
              <p class="step-detail" *ngIf="status.modelSource.detail">{{ status.modelSource.detail }}</p>
              <div class="step-actions" *ngIf="!status.modelSource.complete">
                <button class="action-btn primary" (click)="navigateTo('developer')">
                  Open Model Staging
                </button>
              </div>
            </div>
          </div>

          <!-- Connector -->
          <div class="step-connector" [class.complete]="status.modelSource.complete"></div>

          <!-- Step 3: Embedding Model -->
          <div class="step" [class.complete]="status.embeddingModel.complete"
               [class.active]="status.currentStep === 3"
               [class.in-progress]="status.embeddingModel.status === 'IN_PROGRESS'">
            <div class="step-indicator">
              <div class="step-number" *ngIf="!status.embeddingModel.complete && status.embeddingModel.status !== 'IN_PROGRESS'">3</div>
              <svg *ngIf="status.embeddingModel.complete" class="check-icon" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <polyline points="20 6 9 17 4 12"></polyline>
              </svg>
              <svg *ngIf="status.embeddingModel.status === 'IN_PROGRESS'" class="spin-icon" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
              </svg>
            </div>
            <div class="step-content">
              <div class="step-header">
                <h3>{{ status.embeddingModel.name }}</h3>
                <span class="step-badge" [class]="'badge-' + status.embeddingModel.status.toLowerCase()">
                  {{ formatStatus(status.embeddingModel.status) }}
                </span>
              </div>
              <p class="step-description">{{ status.embeddingModel.description }}</p>
              <p class="step-message">{{ status.embeddingModel.message }}</p>
              <p class="step-detail" *ngIf="status.embeddingModel.detail">{{ status.embeddingModel.detail }}</p>
              <div class="step-actions" *ngIf="status.embeddingModel.status === 'IN_PROGRESS'">
                <div class="loading-indicator">
                  <div class="loading-bar"><div class="loading-bar-progress"></div></div>
                  <span class="loading-text">Model is loading automatically...</span>
                </div>
              </div>
              <div class="step-actions" *ngIf="!status.embeddingModel.complete && status.embeddingModel.status !== 'IN_PROGRESS'">
                <button class="action-btn secondary" (click)="forceReloadModels()">
                  Force Reload
                </button>
              </div>
            </div>
          </div>

          <!-- Connector -->
          <div class="step-connector" [class.complete]="status.embeddingModel.complete"></div>

          <!-- Step 4: Document Index -->
          <div class="step" [class.complete]="status.indexing.complete"
               [class.active]="status.currentStep === 4">
            <div class="step-indicator">
              <div class="step-number" *ngIf="!status.indexing.complete && status.indexing.status !== 'IN_PROGRESS'">4</div>
              <svg *ngIf="status.indexing.complete" class="check-icon" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <polyline points="20 6 9 17 4 12"></polyline>
              </svg>
              <svg *ngIf="status.indexing.status === 'IN_PROGRESS'" class="spin-icon" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
              </svg>
            </div>
            <div class="step-content">
              <div class="step-header">
                <h3>{{ status.indexing.name }}</h3>
                <span class="step-badge" [class]="'badge-' + status.indexing.status.toLowerCase()">
                  {{ formatStatus(status.indexing.status) }}
                </span>
              </div>
              <p class="step-description">{{ status.indexing.description }}</p>
              <p class="step-message">{{ status.indexing.message }}</p>
              <p class="step-detail" *ngIf="status.indexing.detail">{{ status.indexing.detail }}</p>
              <div class="step-actions" *ngIf="!status.indexing.complete">
                <button class="action-btn primary" (click)="navigateTo('sources')">
                  Upload Documents
                </button>
              </div>
            </div>
          </div>

          <!-- Connector -->
          <div class="step-connector" [class.complete]="status.indexing.complete"></div>

          <!-- Step 5: Search Ready -->
          <div class="step" [class.complete]="status.searchReady.complete"
               [class.active]="status.currentStep === 5">
            <div class="step-indicator">
              <div class="step-number" *ngIf="!status.searchReady.complete && status.searchReady.status !== 'IN_PROGRESS'">5</div>
              <svg *ngIf="status.searchReady.complete" class="check-icon" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <polyline points="20 6 9 17 4 12"></polyline>
              </svg>
              <svg *ngIf="status.searchReady.status === 'IN_PROGRESS'" class="spin-icon" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
              </svg>
            </div>
            <div class="step-content">
              <div class="step-header">
                <h3>{{ status.searchReady.name }}</h3>
                <span class="step-badge" [class]="'badge-' + status.searchReady.status.toLowerCase()">
                  {{ formatStatus(status.searchReady.status) }}
                </span>
              </div>
              <p class="step-description">{{ status.searchReady.description }}</p>
              <p class="step-message">{{ status.searchReady.message }}</p>
              <div class="step-actions" *ngIf="status.searchReady.complete">
                <button class="action-btn primary" (click)="navigateTo('unifiedChat')">
                  Start Chatting
                </button>
              </div>
            </div>
          </div>

        </div>

        <!-- Footer -->
        <div class="wizard-footer">
          <button class="footer-btn" (click)="dismiss()">
            Skip Setup — Continue to App
          </button>
          <span class="footer-note">You can return to this wizard from Developer > System Diagnostics</span>
        </div>

      </div>
    </div>
  `,
  styles: [`
    .wizard-overlay {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background: rgba(0, 0, 0, 0.85);
      z-index: 10001;
      display: flex;
      align-items: center;
      justify-content: center;
      animation: fadeIn 0.3s ease-out;
    }

    @keyframes fadeIn {
      from { opacity: 0; }
      to { opacity: 1; }
    }

    .wizard-container {
      background: #1a1a1a;
      border-radius: 16px;
      border: 1px solid #333;
      width: 680px;
      max-width: 90vw;
      max-height: 90vh;
      overflow-y: auto;
      box-shadow: 0 24px 80px rgba(0, 0, 0, 0.6);
      animation: slideUp 0.3s ease-out;
    }

    @keyframes slideUp {
      from { transform: translateY(20px); opacity: 0; }
      to { transform: translateY(0); opacity: 1; }
    }

    /* Header */
    .wizard-header {
      display: flex;
      align-items: flex-start;
      gap: 16px;
      padding: 28px 28px 0;
    }

    .header-icon {
      width: 48px;
      height: 48px;
      border-radius: 12px;
      background: linear-gradient(135deg, #2196f3 0%, #1565c0 100%);
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      color: white;
    }

    .header-text {
      flex: 1;
    }

    .header-text h2 {
      margin: 0;
      font-size: 22px;
      font-weight: 600;
      color: #fff;
    }

    .header-text p {
      margin: 6px 0 0;
      font-size: 14px;
      color: #999;
      line-height: 1.5;
    }

    .dismiss-btn {
      background: none;
      border: none;
      color: #666;
      cursor: pointer;
      padding: 4px;
      border-radius: 6px;
      transition: all 0.2s;
    }

    .dismiss-btn:hover {
      color: #fff;
      background: #333;
    }

    /* Progress */
    .progress-section {
      padding: 20px 28px;
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .progress-bar {
      flex: 1;
      height: 6px;
      background: #333;
      border-radius: 3px;
      overflow: hidden;
    }

    .progress-fill {
      height: 100%;
      background: linear-gradient(90deg, #2196f3, #4caf50);
      border-radius: 3px;
      transition: width 0.5s ease;
    }

    .progress-text {
      font-size: 12px;
      color: #888;
      white-space: nowrap;
    }

    /* Steps */
    .steps-container {
      padding: 0 28px 20px;
    }

    .step {
      display: flex;
      gap: 16px;
      padding: 16px;
      border-radius: 12px;
      border: 1px solid #2a2a2a;
      background: #111;
      transition: all 0.3s;
    }

    .step.active {
      border-color: #2196f3;
      background: rgba(33, 150, 243, 0.05);
    }

    .step.complete {
      border-color: #2e7d32;
      background: rgba(76, 175, 80, 0.05);
    }

    .step.in-progress {
      border-color: #f57c00;
      background: rgba(255, 152, 0, 0.05);
    }

    .step.warning {
      border-color: #f9a825;
      background: rgba(249, 168, 37, 0.05);
    }

    .step-indicator {
      width: 36px;
      height: 36px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      background: #2a2a2a;
      border: 2px solid #444;
      transition: all 0.3s;
    }

    .step.active .step-indicator {
      background: #1565c0;
      border-color: #2196f3;
    }

    .step.complete .step-indicator {
      background: #2e7d32;
      border-color: #4caf50;
    }

    .step.in-progress .step-indicator {
      background: #e65100;
      border-color: #ff9800;
    }

    .step-number {
      font-size: 14px;
      font-weight: 600;
      color: #888;
    }

    .step.active .step-number {
      color: #fff;
    }

    .check-icon {
      color: #fff;
    }

    .spin-icon {
      color: #fff;
      animation: spin 1s linear infinite;
    }

    @keyframes spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }

    .step-content {
      flex: 1;
      min-width: 0;
    }

    .step-header {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 4px;
    }

    .step-header h3 {
      margin: 0;
      font-size: 15px;
      font-weight: 600;
      color: #fff;
    }

    .step-badge {
      font-size: 10px;
      padding: 2px 8px;
      border-radius: 10px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .badge-not_started {
      background: rgba(102, 102, 102, 0.2);
      color: #888;
    }

    .badge-in_progress {
      background: rgba(255, 152, 0, 0.2);
      color: #ff9800;
    }

    .badge-complete {
      background: rgba(76, 175, 80, 0.2);
      color: #4caf50;
    }

    .badge-warning {
      background: rgba(249, 168, 37, 0.2);
      color: #f9a825;
    }

    .step-description {
      margin: 0;
      font-size: 12px;
      color: #777;
    }

    .step-message {
      margin: 8px 0 0;
      font-size: 13px;
      color: #ccc;
    }

    .step-detail {
      margin: 4px 0 0;
      font-size: 12px;
      color: #888;
    }

    .step-actions {
      margin-top: 12px;
    }

    .action-btn {
      padding: 8px 18px;
      border-radius: 6px;
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      border: none;
      transition: all 0.2s;
    }

    .action-btn.primary {
      background: #2196f3;
      color: #fff;
    }

    .action-btn.primary:hover {
      background: #1976d2;
      transform: translateY(-1px);
    }

    .action-btn.secondary {
      background: #333;
      color: #ddd;
      border: 1px solid #444;
    }

    .action-btn.secondary:hover {
      background: #444;
    }

    /* Loading indicator */
    .loading-indicator {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .loading-bar {
      height: 4px;
      background: rgba(255, 152, 0, 0.2);
      border-radius: 2px;
      overflow: hidden;
    }

    .loading-bar-progress {
      height: 100%;
      background: #ff9800;
      border-radius: 2px;
      animation: loadingPulse 1.5s ease-in-out infinite;
      width: 30%;
    }

    @keyframes loadingPulse {
      0% { transform: translateX(-100%); }
      100% { transform: translateX(400%); }
    }

    .loading-text {
      font-size: 12px;
      color: #ff9800;
    }

    /* Step connector */
    .step-connector {
      width: 2px;
      height: 20px;
      background: #333;
      margin: 0 0 0 45px;
      transition: background 0.3s;
    }

    .step-connector.complete {
      background: #4caf50;
    }

    /* Footer */
    .wizard-footer {
      padding: 20px 28px;
      border-top: 1px solid #2a2a2a;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .footer-btn {
      background: none;
      border: 1px solid #444;
      color: #999;
      padding: 8px 18px;
      border-radius: 6px;
      font-size: 13px;
      cursor: pointer;
      transition: all 0.2s;
    }

    .footer-btn:hover {
      color: #fff;
      border-color: #666;
      background: #222;
    }

    .footer-note {
      font-size: 11px;
      color: #555;
    }

    /* Scrollbar */
    .wizard-container::-webkit-scrollbar {
      width: 6px;
    }

    .wizard-container::-webkit-scrollbar-track {
      background: transparent;
    }

    .wizard-container::-webkit-scrollbar-thumb {
      background: #444;
      border-radius: 3px;
    }
  `]
})
export class SetupWizardComponent implements OnInit, OnDestroy {

  @Output() navigateToTab = new EventEmitter<string>();
  @Output() dismissed = new EventEmitter<void>();

  status: SetupStatus | null = null;
  visible = true;
  startingStagingServer = false;
  private pollSubscription: Subscription | null = null;

  constructor(
    private http: HttpClient,
    private baseService: BaseService
  ) {}

  ngOnInit(): void {
    this.fetchStatus();
    // Poll every 5 seconds to pick up changes in real time
    this.pollSubscription = interval(5000).subscribe(() => {
      this.fetchStatus();
    });
  }

  ngOnDestroy(): void {
    this.pollSubscription?.unsubscribe();
  }

  fetchStatus(): void {
    this.http.get<SetupStatus>(`${this.baseService.backendUrl}/setup/status`).pipe(
      catchError(err => {
        console.error('Failed to fetch setup status:', err);
        return of(null);
      })
    ).subscribe(status => {
      if (status) {
        const wasIncomplete = this.status && !this.status.setupComplete;
        this.status = status;

        // If setup just completed, auto-dismiss after a brief celebration
        if (wasIncomplete && status.setupComplete) {
          setTimeout(() => this.dismiss(), 2000);
        }

        // Respect server-side dismissal
        if (status.wizardDismissed) {
          this.visible = false;
        }
      }
    });
  }

  getProgressPercent(): number {
    if (!this.status) return 0;
    return (this.getCompletedCount() / this.status.totalSteps) * 100;
  }

  getCompletedCount(): number {
    if (!this.status) return 0;
    let count = 0;
    if (this.status.stagingServer.complete) count++;
    if (this.status.modelSource.complete) count++;
    if (this.status.embeddingModel.complete) count++;
    if (this.status.indexing.complete) count++;
    if (this.status.searchReady.complete) count++;
    return count;
  }

  formatStatus(status: StepState): string {
    switch (status) {
      case 'NOT_STARTED': return 'Pending';
      case 'IN_PROGRESS': return 'Loading';
      case 'COMPLETE': return 'Complete';
      case 'WARNING': return 'Warning';
      default: return status;
    }
  }

  navigateTo(tab: string): void {
    this.dismiss();
    this.navigateToTab.emit(tab);
  }

  forceReloadModels(): void {
    this.http.post<any>(`${this.baseService.backendUrl}/models/registry/refresh-and-reload`, {}).pipe(
      catchError(err => {
        console.error('Force reload failed:', err);
        return of(null);
      })
    ).subscribe(() => {
      // Refresh status immediately
      this.fetchStatus();
    });
  }

  startStagingServer(): void {
    this.startingStagingServer = true;
    this.http.post<any>(`${this.baseService.backendUrl}/setup/staging-server/start`, {}).pipe(
      catchError(err => {
        console.error('Failed to start staging server:', err);
        this.startingStagingServer = false;
        return of(null);
      })
    ).subscribe(result => {
      this.startingStagingServer = false;
      // Refresh status to pick up the change
      this.fetchStatus();
    });
  }

  stopStagingServer(): void {
    this.http.post<any>(`${this.baseService.backendUrl}/setup/staging-server/stop`, {}).pipe(
      catchError(err => {
        console.error('Failed to stop staging server:', err);
        return of(null);
      })
    ).subscribe(() => {
      this.fetchStatus();
    });
  }

  dismiss(): void {
    this.visible = false;
    // Tell the server we dismissed
    this.http.post(`${this.baseService.backendUrl}/setup/dismiss`, {}).pipe(
      catchError(() => of(null))
    ).subscribe();
    this.dismissed.emit();
  }
}
