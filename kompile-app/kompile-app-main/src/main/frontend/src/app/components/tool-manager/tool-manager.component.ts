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
import { ToolDefinitionService } from '../../services/tool-definition.service';
import {
  EnhancedToolDefinition,
  ToolCategoryInfo,
  ToolsSummary,
  TOOL_CATEGORIES,
  ToolUsageExample
} from '../../models/api-models';

@Component({
  standalone: false,
  selector: 'app-tool-manager',
  templateUrl: './tool-manager.component.html',
  styleUrls: ['./tool-manager.component.css']
})
export class ToolManagerComponent implements OnInit {
  // Data
  tools: EnhancedToolDefinition[] = [];
  toolsByCategory: { [key: string]: EnhancedToolDefinition[] } = {};
  categories: { [key: string]: ToolCategoryInfo } = TOOL_CATEGORIES;
  summary: ToolsSummary | null = null;

  // UI State
  isLoading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  searchQuery = '';
  selectedCategory: string | null = null;
  viewMode: 'grid' | 'list' | 'categories' = 'categories';

  // Tool Detail/Edit
  selectedTool: EnhancedToolDefinition | null = null;
  isEditMode = false;
  isCreating = false;
  editForm: Partial<EnhancedToolDefinition> = {};

  // Agent Prompt
  agentPrompt: string | null = null;
  showAgentPrompt = false;

  constructor(private toolService: ToolDefinitionService) {}

  ngOnInit(): void {
    this.loadTools();
  }

  loadTools(): void {
    this.isLoading = true;
    this.errorMessage = null;

    // Load summary
    this.toolService.getToolsSummary().subscribe({
      next: (summary) => {
        this.summary = summary;
      },
      error: (err) => {
        console.error('Failed to load tools summary:', err);
      }
    });

    // Load tools grouped by category
    this.toolService.getToolsGroupedByCategory().subscribe({
      next: (grouped) => {
        this.toolsByCategory = grouped;
        this.tools = Object.values(grouped).flat();
        this.isLoading = false;
      },
      error: (err) => {
        this.errorMessage = `Failed to load tools: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  refreshTools(): void {
    this.isLoading = true;
    this.toolService.refreshTools().subscribe({
      next: (result) => {
        this.showSuccess(result.message);
        this.loadTools();
      },
      error: (err) => {
        this.errorMessage = `Failed to refresh tools: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  searchTools(): void {
    if (!this.searchQuery.trim()) {
      this.loadTools();
      return;
    }

    this.isLoading = true;
    this.toolService.searchTools(this.searchQuery).subscribe({
      next: (tools) => {
        this.tools = tools;
        this.toolsByCategory = this.groupByCategory(tools);
        this.isLoading = false;
      },
      error: (err) => {
        this.errorMessage = `Search failed: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  filterByCategory(category: string | null): void {
    this.selectedCategory = category;

    if (!category) {
      this.loadTools();
      return;
    }

    this.isLoading = true;
    this.toolService.getToolsByCategory(category).subscribe({
      next: (tools) => {
        this.tools = tools;
        this.toolsByCategory = { [category]: tools };
        this.isLoading = false;
      },
      error: (err) => {
        this.errorMessage = `Failed to filter tools: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  selectTool(tool: EnhancedToolDefinition): void {
    this.selectedTool = tool;
    this.isEditMode = false;
    this.editForm = {};
  }

  closeTool(): void {
    this.selectedTool = null;
    this.isEditMode = false;
    this.isCreating = false;
    this.editForm = {};
  }

  startEdit(): void {
    if (this.selectedTool) {
      this.isEditMode = true;
      this.editForm = { ...this.selectedTool };
    }
  }

  cancelEdit(): void {
    this.isEditMode = false;
    this.isCreating = false;
    this.editForm = {};
  }

  saveEdit(): void {
    if (!this.selectedTool || !this.editForm.name) return;

    this.isLoading = true;
    this.toolService.updateTool(this.selectedTool.name, this.editForm).subscribe({
      next: (updated) => {
        this.selectedTool = updated;
        this.isEditMode = false;
        this.showSuccess('Tool updated successfully');
        this.loadTools();
      },
      error: (err) => {
        this.errorMessage = `Failed to update tool: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  startCreate(): void {
    this.isCreating = true;
    this.selectedTool = null;
    this.editForm = {
      name: '',
      description: '',
      category: 'custom',
      enabled: true,
      tags: [],
      usageHints: [],
      relatedTools: [],
      examples: []
    };
  }

  createTool(): void {
    if (!this.editForm.name || !this.editForm.description) {
      this.errorMessage = 'Name and description are required';
      return;
    }

    this.isLoading = true;
    this.toolService.createTool(this.editForm as EnhancedToolDefinition).subscribe({
      next: (created) => {
        this.selectedTool = created;
        this.isCreating = false;
        this.showSuccess('Tool created successfully');
        this.loadTools();
      },
      error: (err) => {
        this.errorMessage = `Failed to create tool: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  deleteTool(tool: EnhancedToolDefinition): void {
    if (!confirm(`Are you sure you want to delete "${tool.name}"?`)) return;

    this.isLoading = true;
    this.toolService.deleteTool(tool.name).subscribe({
      next: () => {
        this.closeTool();
        this.showSuccess('Tool deleted successfully');
        this.loadTools();
      },
      error: (err) => {
        this.errorMessage = `Failed to delete tool: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  toggleToolEnabled(tool: EnhancedToolDefinition): void {
    const newState = !tool.enabled;
    this.toolService.setToolEnabled(tool.name, newState).subscribe({
      next: (updated) => {
        tool.enabled = updated.enabled;
        this.showSuccess(`Tool ${newState ? 'enabled' : 'disabled'}`);
      },
      error: (err) => {
        this.errorMessage = `Failed to toggle tool: ${err.message}`;
      }
    });
  }

  loadAgentPrompt(): void {
    this.isLoading = true;
    this.toolService.getAgentToolsPrompt().subscribe({
      next: (prompt) => {
        this.agentPrompt = prompt;
        this.showAgentPrompt = true;
        this.isLoading = false;
      },
      error: (err) => {
        this.errorMessage = `Failed to load agent prompt: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  closeAgentPrompt(): void {
    this.showAgentPrompt = false;
  }

  copyAgentPrompt(): void {
    if (this.agentPrompt) {
      navigator.clipboard.writeText(this.agentPrompt).then(() => {
        this.showSuccess('Prompt copied to clipboard');
      });
    }
  }

  addExample(): void {
    if (!this.editForm.examples) {
      this.editForm.examples = [];
    }
    this.editForm.examples.push({
      title: '',
      description: '',
      input: {},
      scenario: ''
    });
  }

  removeExample(index: number): void {
    if (this.editForm.examples) {
      this.editForm.examples.splice(index, 1);
    }
  }

  addUsageHint(): void {
    if (!this.editForm.usageHints) {
      this.editForm.usageHints = [];
    }
    this.editForm.usageHints.push('');
  }

  removeUsageHint(index: number): void {
    if (this.editForm.usageHints) {
      this.editForm.usageHints.splice(index, 1);
    }
  }

  addTag(): void {
    if (!this.editForm.tags) {
      this.editForm.tags = [];
    }
    this.editForm.tags.push('');
  }

  removeTag(index: number): void {
    if (this.editForm.tags) {
      this.editForm.tags.splice(index, 1);
    }
  }

  getCategoryDisplayName(categoryKey: string): string {
    return this.categories[categoryKey]?.displayName || categoryKey;
  }

  getCategoryDescription(categoryKey: string): string {
    return this.categories[categoryKey]?.description || '';
  }

  getSourceBadgeClass(source: string | undefined): string {
    switch (source) {
      case 'BUILT_IN': return 'badge-builtin';
      case 'CUSTOM': return 'badge-custom';
      case 'MCP_SERVER': return 'badge-mcp';
      case 'REST_BRIDGE': return 'badge-bridge';
      default: return 'badge-default';
    }
  }

  trackByToolName(index: number, tool: EnhancedToolDefinition): string {
    return tool.name;
  }

  trackByCategory(index: number, category: string): string {
    return category;
  }

  private groupByCategory(tools: EnhancedToolDefinition[]): { [key: string]: EnhancedToolDefinition[] } {
    const grouped: { [key: string]: EnhancedToolDefinition[] } = {};
    for (const tool of tools) {
      const category = tool.category || 'other';
      if (!grouped[category]) {
        grouped[category] = [];
      }
      grouped[category].push(tool);
    }
    return grouped;
  }

  private showSuccess(message: string): void {
    this.successMessage = message;
    setTimeout(() => {
      this.successMessage = null;
    }, 3000);
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.selectedCategory = null;
    this.loadTools();
  }

  get categoryKeys(): string[] {
    return Object.keys(this.toolsByCategory);
  }
}
