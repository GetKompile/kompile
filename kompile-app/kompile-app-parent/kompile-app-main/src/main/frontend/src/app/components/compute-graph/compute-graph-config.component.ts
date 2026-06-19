import { Component, OnInit, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatStepperModule } from '@angular/material/stepper';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ComputeGraphService } from '../../services/compute-graph.service';
import { ComputeGraphConfig } from '../../models/compute-graph-models';

@Component({
  standalone: true,
  selector: 'app-compute-graph-config',
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule,
    MatSlideToggleModule, MatButtonModule, MatIconModule, MatDividerModule,
    MatStepperModule, MatSnackBarModule
  ],
  template: `
    <div class="config-container" *ngIf="config">
      <div class="wizard-header">
        <div>
          <h3>Engine Setup</h3>
          <p class="wizard-sub">
            Configure which execution backends are available and the sandbox limits applied to every node.
            Step through each section &mdash; your changes aren't applied until you Save.
          </p>
        </div>
      </div>

      <mat-stepper #stepper class="config-wizard" [animationDuration]="'200ms'">

        <!-- Step 1: Backends -->
        <mat-step [editable]="true">
          <ng-template matStepLabel>Backends</ng-template>
          <div class="step-body">
            <div class="wizard-intro">
              <mat-icon>power</mat-icon>
              <div>
                <strong>Which engines can nodes use?</strong>
                <p>
                  Each backend unlocks a family of node types. Turn off the ones you don't need to shrink the
                  attack surface and startup cost. The master switch disables the whole engine at once.
                </p>
              </div>
            </div>

            <div class="setting">
              <mat-slide-toggle [(ngModel)]="config.enabled" color="primary">
                Enable Compute Graph Engine
              </mat-slide-toggle>
              <p class="setting-hint">Master switch. When off, no graphs can be validated or executed.</p>
            </div>
            <mat-divider></mat-divider>
            <div class="setting">
              <mat-slide-toggle [(ngModel)]="config.scriptingEnabled" color="primary" [disabled]="!config.enabled">
                Scripting backend (JavaScript / Python)
              </mat-slide-toggle>
              <p class="setting-hint">Enables <code>JAVASCRIPT</code> and <code>PYTHON</code> nodes via the embedded engines.</p>
            </div>
            <div class="setting">
              <mat-slide-toggle [(ngModel)]="config.droolsEnabled" color="primary" [disabled]="!config.enabled">
                Drools rules backend
              </mat-slide-toggle>
              <p class="setting-hint">Enables <code>DROOLS_RULE</code> nodes &mdash; business rules written in DRL.</p>
            </div>
            <div class="setting">
              <mat-slide-toggle [(ngModel)]="config.droolsInferenceEnabled" color="primary"
                                [disabled]="!config.enabled || !config.droolsEnabled">
                Drools inference engine
              </mat-slide-toggle>
              <p class="setting-hint">
                Enables <code>DROOLS_INFERENCE</code> nodes where rules chain &mdash; facts asserted by one rule
                trigger others. Requires the Drools backend above.
              </p>
            </div>

            <div class="step-actions">
              <span class="spacer"></span>
              <button mat-raised-button color="primary" matStepperNext>Next <mat-icon>arrow_forward</mat-icon></button>
            </div>
          </div>
        </mat-step>

        <!-- Step 2: Resource Limits -->
        <mat-step [editable]="true">
          <ng-template matStepLabel>Resource limits</ng-template>
          <div class="step-body">
            <div class="wizard-intro">
              <mat-icon>speed</mat-icon>
              <div>
                <strong>Default sandbox budget per node</strong>
                <p>
                  Every node runs inside these limits unless it overrides them. They protect the host from
                  runaway scripts &mdash; a node that exceeds a limit is aborted and reported as failed.
                </p>
              </div>
            </div>

            <div class="form-grid">
              <mat-form-field appearance="outline">
                <mat-label>Max CPU time (ms)</mat-label>
                <input matInput type="number" [(ngModel)]="config.defaultMaxCpuTimeMs" min="0">
                <mat-hint>Wall-clock budget for one node. 0 disables the limit (not recommended).</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Max heap memory (bytes)</mat-label>
                <input matInput type="number" [(ngModel)]="config.defaultMaxHeapMemoryBytes" min="0">
                <mat-hint>{{ formatBytes(config.defaultMaxHeapMemoryBytes) }} &mdash; memory a node may allocate before it is killed.</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Max stack frames</mat-label>
                <input matInput type="number" [(ngModel)]="config.defaultMaxStackFrames" min="0">
                <mat-hint>Guards against infinite recursion / stack overflows.</mat-hint>
              </mat-form-field>
            </div>

            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
              <span class="spacer"></span>
              <button mat-raised-button color="primary" matStepperNext>Next <mat-icon>arrow_forward</mat-icon></button>
            </div>
          </div>
        </mat-step>

        <!-- Step 3: Security -->
        <mat-step [editable]="true">
          <ng-template matStepLabel>Security</ng-template>
          <div class="step-body">
            <div class="wizard-intro warn-intro">
              <mat-icon>shield</mat-icon>
              <div>
                <strong>Capabilities granted to node code</strong>
                <p>
                  These are <em>off by default</em> for good reason. Each one widens what an untrusted graph can
                  do on the host. Only enable a capability if every graph you run is fully trusted.
                </p>
              </div>
            </div>

            <div class="setting">
              <mat-slide-toggle [(ngModel)]="config.defaultAllowIO" color="warn">Allow file I/O</mat-slide-toggle>
              <p class="setting-hint">Lets node code read and write the server filesystem.</p>
            </div>
            <div class="setting">
              <mat-slide-toggle [(ngModel)]="config.defaultAllowNetwork" color="warn">Allow network access</mat-slide-toggle>
              <p class="setting-hint">Lets node code open outbound network connections.</p>
            </div>
            <div class="setting">
              <mat-slide-toggle [(ngModel)]="config.defaultAllowHostAccess" color="warn">Allow host class access</mat-slide-toggle>
              <p class="setting-hint danger">
                Lets scripts reach arbitrary host (Java) classes &mdash; effectively full JVM access. Highest risk.
              </p>
            </div>

            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
              <span class="spacer"></span>
              <button mat-raised-button color="primary" matStepperNext>Next <mat-icon>arrow_forward</mat-icon></button>
            </div>
          </div>
        </mat-step>

        <!-- Step 4: Rule Limits -->
        <mat-step [editable]="true">
          <ng-template matStepLabel>Rule limits</ng-template>
          <div class="step-body">
            <div class="wizard-intro">
              <mat-icon>gavel</mat-icon>
              <div>
                <strong>Drools firing caps</strong>
                <p>
                  Rule engines can loop forever if a rule keeps re-activating. These caps stop a single node &mdash;
                  or a whole graph &mdash; after too many firings. They only apply when the Drools backend is enabled.
                </p>
              </div>
            </div>

            <div *ngIf="!config.droolsEnabled" class="inactive-note">
              <mat-icon>info</mat-icon>
              The Drools backend is currently disabled, so these limits have no effect. Enable it on the Backends step to use rule nodes.
            </div>

            <div class="form-grid">
              <mat-form-field appearance="outline">
                <mat-label>Max rule firings per node</mat-label>
                <input matInput type="number" [(ngModel)]="config.maxRuleFiringsPerNode" min="1">
                <mat-hint>Aborts a node after this many rule activations.</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Max rule firings total</mat-label>
                <input matInput type="number" [(ngModel)]="config.maxRuleFiringsTotal" min="1">
                <mat-hint>Aborts the whole graph after this many activations across all nodes.</mat-hint>
              </mat-form-field>
            </div>

            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
              <span class="spacer"></span>
              <button mat-raised-button color="primary" matStepperNext>Review <mat-icon>arrow_forward</mat-icon></button>
            </div>
          </div>
        </mat-step>

        <!-- Step 5: Review -->
        <mat-step [editable]="true">
          <ng-template matStepLabel>Review</ng-template>
          <div class="step-body">
            <div class="wizard-intro">
              <mat-icon>fact_check</mat-icon>
              <div>
                <strong>Review and save</strong>
                <p>Confirm the configuration below, then save to apply it to the engine.</p>
              </div>
            </div>

            <div class="review-grid">
              <div class="review-row"><span>Engine</span><strong [class.on]="config.enabled" [class.off]="!config.enabled">{{config.enabled ? 'Enabled' : 'Disabled'}}</strong></div>
              <div class="review-row"><span>Scripting (JS / Python)</span><strong>{{config.scriptingEnabled ? 'On' : 'Off'}}</strong></div>
              <div class="review-row"><span>Drools rules</span><strong>{{config.droolsEnabled ? 'On' : 'Off'}}</strong></div>
              <div class="review-row"><span>Drools inference</span><strong>{{config.droolsInferenceEnabled ? 'On' : 'Off'}}</strong></div>
              <div class="review-row"><span>Max CPU time</span><strong>{{config.defaultMaxCpuTimeMs}} ms</strong></div>
              <div class="review-row"><span>Max heap</span><strong>{{formatBytes(config.defaultMaxHeapMemoryBytes)}}</strong></div>
              <div class="review-row"><span>Max stack frames</span><strong>{{config.defaultMaxStackFrames}}</strong></div>
              <div class="review-row"><span>File I/O</span><strong [class.danger-text]="config.defaultAllowIO">{{config.defaultAllowIO ? 'Allowed' : 'Blocked'}}</strong></div>
              <div class="review-row"><span>Network</span><strong [class.danger-text]="config.defaultAllowNetwork">{{config.defaultAllowNetwork ? 'Allowed' : 'Blocked'}}</strong></div>
              <div class="review-row"><span>Host class access</span><strong [class.danger-text]="config.defaultAllowHostAccess">{{config.defaultAllowHostAccess ? 'Allowed' : 'Blocked'}}</strong></div>
            </div>

            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
            </div>
          </div>
        </mat-step>
      </mat-stepper>

      <!-- Persistent action bar (save from anywhere) -->
      <div class="action-bar">
        <button mat-raised-button color="primary" (click)="saveConfig()">
          <mat-icon>save</mat-icon> Save configuration
        </button>
        <button mat-stroked-button (click)="loadConfig()">
          <mat-icon>refresh</mat-icon> Reload
        </button>
      </div>
    </div>
  `,
  styles: [`
    .config-container { padding: 16px; max-width: 820px; }
    .wizard-header h3 { margin: 0; font-size: 18px; }
    .wizard-sub { margin: 4px 0 12px; font-size: 12px; color: #999; max-width: 640px; }
    .config-wizard { background: transparent; }
    .step-body { padding: 8px 4px 4px; }

    .wizard-intro {
      display: flex; gap: 12px; align-items: flex-start;
      background: rgba(144,202,249,0.08); border: 1px solid rgba(144,202,249,0.25);
      border-radius: 8px; padding: 12px 14px; margin-bottom: 16px;
    }
    .wizard-intro mat-icon { color: #90caf9; flex-shrink: 0; }
    .wizard-intro strong { display: block; font-size: 13px; margin-bottom: 2px; }
    .wizard-intro p { margin: 0; font-size: 12.5px; color: #bbb; line-height: 1.5; }
    .warn-intro { background: rgba(255,183,77,0.08); border-color: rgba(255,183,77,0.3); }
    .warn-intro mat-icon { color: #ffb74d; }

    .setting { padding: 10px 0; }
    .setting-hint { margin: 4px 0 0 0; font-size: 12px; color: #999; line-height: 1.4; }
    .setting-hint code { background: rgba(255,255,255,0.08); padding: 1px 5px; border-radius: 3px; font-size: 11px; }
    .setting-hint.danger { color: #ef9a9a; }
    mat-divider { margin: 8px 0; }

    .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; padding: 8px 0; }

    .inactive-note {
      display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: #ffb74d;
      background: rgba(255,183,77,0.08); border-radius: 6px; padding: 10px 12px; margin-bottom: 12px;
    }
    .inactive-note mat-icon { font-size: 18px; width: 18px; height: 18px; }

    .review-grid {
      display: grid; grid-template-columns: 1fr 1fr; gap: 1px;
      background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.06); border-radius: 8px;
      overflow: hidden; margin-bottom: 8px;
    }
    .review-row {
      display: flex; justify-content: space-between; gap: 12px; padding: 10px 14px;
      background: #1e1e2e; font-size: 13px;
    }
    .review-row span { color: #aaa; }
    .review-row strong.on, .review-row strong.off { font-weight: 500; }
    .review-row strong.on { color: #81c784; }
    .review-row strong.off { color: #999; }
    .danger-text { color: #ef5350; }

    .step-actions { display: flex; align-items: center; gap: 8px; padding-top: 12px; }
    .spacer { flex: 1; }
    .action-bar {
      display: flex; gap: 12px; padding: 16px 0 0; margin-top: 8px;
      border-top: 1px solid rgba(255,255,255,0.08);
    }
  `]
})
export class ComputeGraphConfigComponent implements OnInit {
  config: ComputeGraphConfig | null = null;
  @Output() configChanged = new EventEmitter<void>();

  constructor(
    private computeGraphService: ComputeGraphService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadConfig();
  }

  loadConfig(): void {
    this.computeGraphService.getConfig().subscribe({
      next: (config) => this.config = config,
      error: (err) => this.snackBar.open('Failed to load config: ' + err.message, 'Dismiss', { duration: 3000 })
    });
  }

  saveConfig(): void {
    if (!this.config) return;
    this.computeGraphService.updateConfig(this.config).subscribe({
      next: (updated) => {
        this.config = updated;
        this.configChanged.emit();
        this.snackBar.open('Configuration saved', 'OK', { duration: 2000 });
      },
      error: (err) => this.snackBar.open('Failed to save config: ' + err.message, 'Dismiss', { duration: 3000 })
    });
  }

  formatBytes(bytes: number | undefined | null): string {
    if (bytes == null || bytes <= 0) return 'no limit';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }
}
