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
import { BaseService } from '../../services/base.service';

export interface IndexStatus {
  vectorStorePath: string;
  vectorStoreAvailable: boolean;
  vectorStoreNoOp: boolean;
  vectorDocumentCount: number;
  vectorIndexLoaded: boolean;
  vectorIndexEmpty: boolean;

  keywordIndexPath: string;
  keywordIndexAvailable: boolean;
  indexerServiceNoOp: boolean;
  keywordDocumentCount: number;
  keywordIndexLoaded: boolean;
  keywordIndexEmpty: boolean;

  availableVectorIndices: string[];
  availableKarchFiles: string[];

  anyIndexLoaded: boolean;
  warningMessage: string | null;
}

@Component({
  selector: 'app-index-status-banner',
  standalone: true,
  imports: [CommonModule, HttpClientModule],
  template: `
    <!-- Prominent warning banner when no index is loaded -->
    <div *ngIf="showBanner && status" class="index-status-banner" [class.warning]="!status.anyIndexLoaded" [class.info]="status.anyIndexLoaded && hasWarning">
      <div class="banner-content">
        <div class="banner-icon">
          <svg *ngIf="!status.anyIndexLoaded" xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
            <line x1="12" y1="9" x2="12" y2="13"></line>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
          <svg *ngIf="status.anyIndexLoaded && hasWarning" xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="8" x2="12" y2="12"></line>
            <line x1="12" y1="16" x2="12.01" y2="16"></line>
          </svg>
        </div>

        <div class="banner-text">
          <div class="banner-title" *ngIf="!status.anyIndexLoaded">
            No Index Loaded - Search Functionality Unavailable
          </div>
          <div class="banner-title" *ngIf="status.anyIndexLoaded && hasWarning">
            Index Status Warning
          </div>

          <div class="banner-message">
            {{ status.warningMessage || 'No indices are currently loaded. Upload documents or import a .karch archive to enable search.' }}
          </div>

          <div class="banner-details" *ngIf="!status.anyIndexLoaded">
            <span *ngIf="getAvailableVectorIndicesCount() > 0" class="detail-item">
              {{ getAvailableVectorIndicesCount() }} available index(es) found
            </span>
            <span *ngIf="getAvailableKarchFilesCount() > 0" class="detail-item">
              {{ getAvailableKarchFilesCount() }} .karch file(s) available
            </span>
          </div>
        </div>

        <div class="banner-actions">
          <button class="action-btn primary" (click)="goToDocumentManager()">
            Upload Documents
          </button>
          <button class="action-btn secondary" (click)="goToArchiveManager()">
            Import Archive
          </button>
          <button class="action-btn tertiary" (click)="dismissBanner()">
            Dismiss
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .index-status-banner {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      z-index: 10000;
      padding: 12px 20px;
      animation: slideDown 0.3s ease-out;
    }

    .index-status-banner.warning {
      background: linear-gradient(135deg, #d32f2f 0%, #c62828 100%);
      color: white;
      box-shadow: 0 4px 20px rgba(211, 47, 47, 0.4);
    }

    .index-status-banner.info {
      background: linear-gradient(135deg, #f57c00 0%, #ef6c00 100%);
      color: white;
      box-shadow: 0 4px 20px rgba(245, 124, 0, 0.4);
    }

    @keyframes slideDown {
      from {
        transform: translateY(-100%);
        opacity: 0;
      }
      to {
        transform: translateY(0);
        opacity: 1;
      }
    }

    .banner-content {
      max-width: 1400px;
      margin: 0 auto;
      display: flex;
      align-items: center;
      gap: 16px;
    }

    .banner-icon {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 40px;
      height: 40px;
      background: rgba(255, 255, 255, 0.2);
      border-radius: 50%;
    }

    .banner-text {
      flex: 1;
      min-width: 0;
    }

    .banner-title {
      font-weight: 600;
      font-size: 16px;
      margin-bottom: 4px;
    }

    .banner-message {
      font-size: 14px;
      opacity: 0.9;
    }

    .banner-details {
      display: flex;
      gap: 16px;
      margin-top: 6px;
      font-size: 12px;
      opacity: 0.8;
    }

    .detail-item {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .detail-item::before {
      content: '';
      width: 6px;
      height: 6px;
      background: rgba(255, 255, 255, 0.6);
      border-radius: 50%;
    }

    .banner-actions {
      display: flex;
      gap: 8px;
      flex-shrink: 0;
    }

    .action-btn {
      padding: 8px 16px;
      border: none;
      border-radius: 6px;
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.2s;
      white-space: nowrap;
    }

    .action-btn.primary {
      background: white;
      color: #d32f2f;
    }

    .action-btn.primary:hover {
      background: #f5f5f5;
      transform: translateY(-1px);
    }

    .action-btn.secondary {
      background: rgba(255, 255, 255, 0.2);
      color: white;
      border: 1px solid rgba(255, 255, 255, 0.4);
    }

    .action-btn.secondary:hover {
      background: rgba(255, 255, 255, 0.3);
    }

    .action-btn.tertiary {
      background: transparent;
      color: rgba(255, 255, 255, 0.8);
      padding: 8px 12px;
    }

    .action-btn.tertiary:hover {
      color: white;
      background: rgba(255, 255, 255, 0.1);
    }

    @media (max-width: 768px) {
      .banner-content {
        flex-wrap: wrap;
      }

      .banner-actions {
        width: 100%;
        justify-content: flex-end;
        margin-top: 8px;
      }

      .banner-details {
        flex-direction: column;
        gap: 4px;
      }
    }
  `]
})
export class IndexStatusBannerComponent implements OnInit, OnDestroy {
  status: IndexStatus | null = null;
  showBanner = false; // Start hidden, show only if there's an issue
  private refreshSubscription: Subscription | null = null;
  private baseService = new BaseService();

  @Output() navigateToTab = new EventEmitter<string>();

  constructor(
    private http: HttpClient
  ) {}

  ngOnInit(): void {
    this.checkIndexStatus();

    // Refresh status every 30 seconds
    this.refreshSubscription = interval(30000).subscribe(() => {
      if (this.showBanner) {
        this.checkIndexStatus();
      }
    });
  }

  ngOnDestroy(): void {
    if (this.refreshSubscription) {
      this.refreshSubscription.unsubscribe();
    }
  }

  get hasWarning(): boolean {
    return !!this.status?.warningMessage;
  }

  getAvailableVectorIndicesCount(): number {
    return this.status?.availableVectorIndices?.length ?? 0;
  }

  getAvailableKarchFilesCount(): number {
    return this.status?.availableKarchFiles?.length ?? 0;
  }

  checkIndexStatus(): void {
    this.http.get<IndexStatus>(`${this.baseService.backendUrl}/services/index-status`)
      .subscribe({
        next: (status) => {
          this.status = status;
          // Only show banner if there's an issue
          this.showBanner = !status.anyIndexLoaded || !!status.warningMessage;
        },
        error: (err) => {
          console.error('Failed to check index status:', err);
          // Show banner on error to indicate potential issues
          this.status = {
            vectorIndexLoaded: false,
            keywordIndexLoaded: false,
            anyIndexLoaded: false,
            warningMessage: 'Unable to check index status. The server may be starting up.',
            vectorStorePath: '',
            vectorStoreAvailable: false,
            vectorStoreNoOp: true,
            vectorDocumentCount: 0,
            vectorIndexEmpty: true,
            keywordIndexPath: '',
            keywordIndexAvailable: false,
            indexerServiceNoOp: true,
            keywordDocumentCount: 0,
            keywordIndexEmpty: true,
            availableVectorIndices: [],
            availableKarchFiles: []
          };
          this.showBanner = true;
        }
      });
  }

  goToDocumentManager(): void {
    this.navigateToTab.emit('sources');
    this.dismissBanner();
  }

  goToArchiveManager(): void {
    this.navigateToTab.emit('archiveAssembly');
    this.dismissBanner();
  }

  dismissBanner(): void {
    this.showBanner = false;
  }
}
