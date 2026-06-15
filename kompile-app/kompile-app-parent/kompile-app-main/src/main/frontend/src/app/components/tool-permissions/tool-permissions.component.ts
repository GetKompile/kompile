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

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ToolPermissionService } from '../../services/tool-permission.service';
import {
  PermissionLevel,
  ToolPermissionStatus,
  CategoryPermissionInfo,
  ToolPermissionInfo
} from '../../models/api-models';

interface CategoryView {
  key: string;
  displayName: string;
  permission: PermissionLevel | null;
  toolCount: number;
  tools: ToolPermissionInfo[];
  expanded: boolean;
}

@Component({
  selector: 'app-tool-permissions',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatExpansionModule,
    MatSlideToggleModule,
    MatIconModule,
    MatButtonModule,
    MatChipsModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatSnackBarModule
  ],
  templateUrl: './tool-permissions.component.html',
  styleUrls: ['./tool-permissions.component.css']
})
export class ToolPermissionsComponent implements OnInit {

  defaultPermission: PermissionLevel = 'ALLOW';
  categories: CategoryView[] = [];
  loading = false;
  error: string | null = null;

  constructor(
    private permissionService: ToolPermissionService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadPermissions();
  }

  loadPermissions(): void {
    this.loading = true;
    this.error = null;
    this.permissionService.getToolsWithStatus().subscribe({
      next: (status: ToolPermissionStatus) => {
        this.defaultPermission = status.defaultPermission;
        this.categories = [];

        for (const [key, catInfo] of Object.entries(status.categories)) {
          const tools = (status.tools || []).filter(t => t.category === key);
          this.categories.push({
            key,
            displayName: catInfo.displayName,
            permission: catInfo.permission,
            toolCount: catInfo.toolCount,
            tools,
            expanded: false
          });
        }

        // Sort categories alphabetically by display name
        this.categories.sort((a, b) => a.displayName.localeCompare(b.displayName));
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to load tool permissions';
        this.loading = false;
        console.error('Failed to load permissions:', err);
      }
    });
  }

  onDefaultPermissionToggle(): void {
    const newLevel: PermissionLevel = this.defaultPermission === 'ALLOW' ? 'DENY' : 'ALLOW';
    this.permissionService.setDefaultPermission(newLevel).subscribe({
      next: () => {
        this.defaultPermission = newLevel;
        this.showMessage(`Default permission set to ${newLevel}`);
        this.loadPermissions();
      },
      error: () => this.showMessage('Failed to update default permission', true)
    });
  }

  isCategoryAllowed(cat: CategoryView): boolean {
    if (cat.permission != null) {
      return cat.permission === 'ALLOW';
    }
    return this.defaultPermission === 'ALLOW';
  }

  onCategoryToggle(cat: CategoryView): void {
    const currentlyAllowed = this.isCategoryAllowed(cat);
    const newLevel: PermissionLevel = currentlyAllowed ? 'DENY' : 'ALLOW';

    this.permissionService.setCategoryRule(cat.key, newLevel).subscribe({
      next: () => {
        cat.permission = newLevel;
        this.showMessage(`${cat.displayName} set to ${newLevel}`);
        this.loadPermissions();
      },
      error: () => this.showMessage('Failed to update category permission', true)
    });
  }

  resetCategoryRule(cat: CategoryView): void {
    this.permissionService.removeCategoryRule(cat.key).subscribe({
      next: () => {
        cat.permission = null;
        this.showMessage(`${cat.displayName} reset to default`);
        this.loadPermissions();
      },
      error: () => this.showMessage('Failed to reset category', true)
    });
  }

  isToolAllowed(tool: ToolPermissionInfo): boolean {
    return tool.resolvedPermission === 'ALLOW';
  }

  onToolToggle(tool: ToolPermissionInfo): void {
    const newLevel: PermissionLevel = tool.resolvedPermission === 'ALLOW' ? 'DENY' : 'ALLOW';
    this.permissionService.setToolRule(tool.name, newLevel).subscribe({
      next: () => {
        tool.resolvedPermission = newLevel;
        tool.hasOverride = true;
        this.showMessage(`${tool.name} set to ${newLevel}`);
      },
      error: () => this.showMessage('Failed to update tool permission', true)
    });
  }

  removeToolOverride(tool: ToolPermissionInfo): void {
    this.permissionService.removeToolRule(tool.name).subscribe({
      next: () => {
        tool.hasOverride = false;
        this.showMessage(`${tool.name} override removed`);
        this.loadPermissions();
      },
      error: () => this.showMessage('Failed to remove tool override', true)
    });
  }

  allowAllCategories(): void {
    const rules: { [key: string]: PermissionLevel } = {};
    for (const cat of this.categories) {
      rules[cat.key] = 'ALLOW';
    }
    this.permissionService.bulkUpdate({ categoryRules: rules }).subscribe({
      next: () => {
        this.showMessage('All categories set to ALLOW');
        this.loadPermissions();
      },
      error: () => this.showMessage('Failed to update categories', true)
    });
  }

  denyWriteCategories(): void {
    const writeCategories = ['filesystem', 'indexing', 'config', 'backup', 'ingestion'];
    const rules: { [key: string]: PermissionLevel } = {};
    for (const cat of this.categories) {
      if (writeCategories.includes(cat.key)) {
        rules[cat.key] = 'DENY';
      }
    }
    this.permissionService.bulkUpdate({ categoryRules: rules }).subscribe({
      next: () => {
        this.showMessage('Write categories set to DENY');
        this.loadPermissions();
      },
      error: () => this.showMessage('Failed to update categories', true)
    });
  }

  getCategoryIcon(key: string): string {
    const icons: { [k: string]: string } = {
      rag: 'search',
      filesystem: 'folder',
      indexing: 'storage',
      model: 'psychology',
      system: 'memory',
      config: 'settings',
      action_log: 'history',
      chat: 'chat',
      factsheet: 'description',
      evaluation: 'assessment',
      ingestion: 'upload',
      pipeline: 'route',
      settings: 'tune',
      chunk: 'view_module',
      prompt: 'edit_note',
      timing: 'timer',
      benchmark: 'speed',
      backup: 'backup',
      orchestrator: 'account_tree',
      experiment: 'science',
      crossindex: 'sync',
      archive: 'archive',
      vlm: 'visibility',
      kvcache: 'cached',
      device: 'devices',
      subprocess: 'terminal',
      delegation: 'group_work'
    };
    return icons[key] || 'extension';
  }

  private showMessage(msg: string, isError = false): void {
    this.snackBar.open(msg, 'Close', {
      duration: 3000,
      panelClass: isError ? ['snackbar-error'] : []
    });
  }
}
