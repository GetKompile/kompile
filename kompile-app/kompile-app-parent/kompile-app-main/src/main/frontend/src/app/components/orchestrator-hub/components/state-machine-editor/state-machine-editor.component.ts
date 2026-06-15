/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit, OnDestroy, Input, ElementRef, ViewChild, AfterViewInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { OrchestratorService } from '../../../../services/orchestrator.service';
import {
  StateDefinition,
  StateTransition,
  StateMachineConfig,
  StateCategory,
  TransitionConditionType,
  StatePositionUpdate
} from '../../../../models/orchestrator-models';

interface VisualNode {
  state: StateDefinition;
  x: number;
  y: number;
  width: number;
  height: number;
  selected: boolean;
  dragging: boolean;
}

interface VisualEdge {
  transition: StateTransition;
  fromNode: VisualNode;
  toNode: VisualNode;
  selected: boolean;
}

@Component({
  standalone: false,
  selector: 'app-state-machine-editor',
  templateUrl: './state-machine-editor.component.html',
  styleUrls: ['./state-machine-editor.component.scss']
})
export class StateMachineEditorComponent implements OnInit, OnDestroy, AfterViewInit {
  @Input() instanceId: string = '';
  @ViewChild('svgCanvas') svgCanvas!: ElementRef<SVGElement>;

  // Data
  config: StateMachineConfig | null = null;
  nodes: VisualNode[] = [];
  edges: VisualEdge[] = [];

  // UI state
  loading = false;
  saving = false;
  error: string | null = null;

  // Selection
  selectedNode: VisualNode | null = null;
  selectedEdge: VisualEdge | null = null;

  // Editing
  editingState = false;
  editingTransition = false;

  // Drag state
  draggingNode: VisualNode | null = null;
  dragOffset = { x: 0, y: 0 };

  // Canvas
  canvasWidth = 1200;
  canvasHeight = 800;
  viewBox = { x: 0, y: 0, width: 1200, height: 800 };

  // Connection mode
  connectingFrom: VisualNode | null = null;
  tempConnectionEnd = { x: 0, y: 0 };

  // Forms
  stateForm!: FormGroup;
  transitionForm!: FormGroup;

  // Options
  stateCategories: StateCategory[] = ['INITIAL', 'PROCESSING', 'WAITING', 'TERMINAL', 'ERROR'];
  conditionTypes: TransitionConditionType[] = [
    'ALWAYS', 'ON_SUCCESS', 'ON_FAILURE', 'PATTERN_MATCH', 'CLASSIFICATION', 'EXPRESSION', 'MANUAL'
  ];

  // Node dimensions
  nodeWidth = 160;
  nodeHeight = 60;

  private destroy$ = new Subject<void>();

  constructor(
    private fb: FormBuilder,
    private orchestratorService: OrchestratorService
  ) {}

  ngOnInit(): void {
    this.initForms();
    this.loadStateMachine();
  }

  ngAfterViewInit(): void {
    // Set canvas size based on container
    this.updateCanvasSize();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private initForms(): void {
    this.stateForm = this.fb.group({
      stateId: ['', [Validators.required, Validators.pattern(/^[a-zA-Z0-9_-]+$/)]],
      name: ['', Validators.required],
      description: [''],
      category: ['PROCESSING', Validators.required],
      timeoutSeconds: [300],
      autoAdvance: [false],
      polling: [false],
      pollingIntervalMs: [5000],
      onEnterTaskId: [''],
      onExitTaskId: ['']
    });

    this.transitionForm = this.fb.group({
      name: [''],
      description: [''],
      conditionType: ['ALWAYS', Validators.required],
      conditionExpression: [''],
      autoTrigger: [true],
      priority: [0],
      onTransitionTaskId: [''],
      label: ['']
    });
  }

  private updateCanvasSize(): void {
    if (this.svgCanvas?.nativeElement) {
      const rect = this.svgCanvas.nativeElement.parentElement?.getBoundingClientRect();
      if (rect) {
        this.canvasWidth = rect.width;
        this.canvasHeight = rect.height;
        this.viewBox = { x: 0, y: 0, width: rect.width, height: rect.height };
      }
    }
  }

  loadStateMachine(): void {
    if (!this.instanceId) return;

    this.loading = true;
    this.orchestratorService.getStateMachineConfig(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          this.config = config;
          this.buildVisualGraph();
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load state machine: ' + (err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  private buildVisualGraph(): void {
    if (!this.config) return;

    // Build nodes with positions
    this.nodes = this.config.states.map((state, index) => ({
      state,
      x: state.positionX ?? (100 + (index % 4) * 200),
      y: state.positionY ?? (100 + Math.floor(index / 4) * 120),
      width: this.nodeWidth,
      height: this.nodeHeight,
      selected: false,
      dragging: false
    }));

    // Build edges
    this.edges = [];
    for (const transition of this.config.transitions) {
      const fromNode = this.nodes.find(n => n.state.stateId === transition.fromStateId);
      const toNode = this.nodes.find(n => n.state.stateId === transition.toStateId);
      if (fromNode && toNode) {
        this.edges.push({
          transition,
          fromNode,
          toNode,
          selected: false
        });
      }
    }
  }

  // Selection
  selectNode(node: VisualNode, event: MouseEvent): void {
    event.stopPropagation();
    this.deselectAll();
    node.selected = true;
    this.selectedNode = node;
    this.selectedEdge = null;

    // If in connection mode, create connection
    if (this.connectingFrom && this.connectingFrom !== node) {
      this.createTransition(this.connectingFrom.state.stateId, node.state.stateId);
      this.connectingFrom = null;
    }
  }

  selectEdge(edge: VisualEdge, event: MouseEvent): void {
    event.stopPropagation();
    this.deselectAll();
    edge.selected = true;
    this.selectedEdge = edge;
    this.selectedNode = null;
  }

  deselectAll(): void {
    this.nodes.forEach(n => n.selected = false);
    this.edges.forEach(e => e.selected = false);
    this.selectedNode = null;
    this.selectedEdge = null;
    this.connectingFrom = null;
  }

  // Drag operations
  startDrag(node: VisualNode, event: MouseEvent): void {
    event.stopPropagation();
    event.preventDefault();
    this.draggingNode = node;
    node.dragging = true;
    const svg = this.svgCanvas.nativeElement;
    const point = this.getSvgPoint(event, svg);
    this.dragOffset = { x: point.x - node.x, y: point.y - node.y };
  }

  onMouseMove(event: MouseEvent): void {
    if (this.draggingNode) {
      const svg = this.svgCanvas.nativeElement;
      const point = this.getSvgPoint(event, svg);
      this.draggingNode.x = point.x - this.dragOffset.x;
      this.draggingNode.y = point.y - this.dragOffset.y;
    }

    if (this.connectingFrom) {
      const svg = this.svgCanvas.nativeElement;
      const point = this.getSvgPoint(event, svg);
      this.tempConnectionEnd = { x: point.x, y: point.y };
    }
  }

  onMouseUp(event: MouseEvent): void {
    if (this.draggingNode) {
      this.draggingNode.dragging = false;
      this.saveNodePosition(this.draggingNode);
      this.draggingNode = null;
    }
  }

  private getSvgPoint(event: MouseEvent, svg: SVGElement): { x: number; y: number } {
    const svgGraphics = svg as SVGGraphicsElement;
    const pt = (svg as any).createSVGPoint();
    pt.x = event.clientX;
    pt.y = event.clientY;
    const ctm = svgGraphics.getScreenCTM();
    if (ctm) {
      const svgPt = pt.matrixTransform(ctm.inverse());
      return { x: svgPt.x, y: svgPt.y };
    }
    return { x: event.offsetX, y: event.offsetY };
  }

  private saveNodePosition(node: VisualNode): void {
    const updates: StatePositionUpdate[] = [{
      stateId: node.state.stateId,
      x: node.x,
      y: node.y
    }];

    this.orchestratorService.updateStatePositions(this.instanceId, updates)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        error: (err) => {
          this.error = 'Failed to save position: ' + (err.error?.message || err.message);
        }
      });
  }

  // State CRUD
  newState(): void {
    this.editingState = true;
    this.selectedNode = null;
    this.stateForm.reset({
      category: 'PROCESSING',
      timeoutSeconds: 300,
      autoAdvance: false,
      polling: false,
      pollingIntervalMs: 5000
    });
  }

  editState(): void {
    if (!this.selectedNode) return;
    this.editingState = true;
    this.stateForm.patchValue({
      stateId: this.selectedNode.state.stateId,
      name: this.selectedNode.state.name,
      description: this.selectedNode.state.description || '',
      category: this.selectedNode.state.category,
      timeoutSeconds: this.selectedNode.state.timeoutSeconds || 300,
      autoAdvance: this.selectedNode.state.autoAdvance || false,
      polling: this.selectedNode.state.polling || false,
      pollingIntervalMs: this.selectedNode.state.pollingIntervalMs || 5000,
      onEnterTaskId: this.selectedNode.state.onEnterTaskId || '',
      onExitTaskId: this.selectedNode.state.onExitTaskId || ''
    });
  }

  saveState(): void {
    if (this.stateForm.invalid) return;

    this.saving = true;
    const formValue = this.stateForm.value;
    const isNew = !this.selectedNode;

    const stateData: StateDefinition = {
      stateId: formValue.stateId,
      name: formValue.name,
      description: formValue.description || undefined,
      category: formValue.category,
      timeoutSeconds: formValue.timeoutSeconds,
      autoAdvance: formValue.autoAdvance,
      polling: formValue.polling,
      pollingIntervalMs: formValue.pollingIntervalMs,
      onEnterTaskId: formValue.onEnterTaskId || undefined,
      onExitTaskId: formValue.onExitTaskId || undefined,
      positionX: isNew ? 200 : this.selectedNode!.x,
      positionY: isNew ? 200 : this.selectedNode!.y
    };

    const request = isNew
      ? this.orchestratorService.createState(this.instanceId, stateData)
      : this.orchestratorService.updateState(this.instanceId, stateData.stateId, stateData);

    request.pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.saving = false;
          this.editingState = false;
          this.loadStateMachine();
        },
        error: (err) => {
          this.saving = false;
          this.error = 'Failed to save state: ' + (err.error?.message || err.message);
        }
      });
  }

  deleteState(): void {
    if (!this.selectedNode) return;

    if (!confirm(`Delete state "${this.selectedNode.state.name}"? This will also delete related transitions.`)) {
      return;
    }

    this.orchestratorService.deleteState(this.instanceId, this.selectedNode.state.stateId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.selectedNode = null;
          this.loadStateMachine();
        },
        error: (err) => {
          this.error = 'Failed to delete state: ' + (err.error?.message || err.message);
        }
      });
  }

  cancelEditState(): void {
    this.editingState = false;
  }

  // Transition CRUD
  startConnection(node: VisualNode, event: MouseEvent): void {
    event.stopPropagation();
    this.connectingFrom = node;
    const svg = this.svgCanvas.nativeElement;
    const point = this.getSvgPoint(event, svg);
    this.tempConnectionEnd = { x: point.x, y: point.y };
  }

  private createTransition(fromStateId: string, toStateId: string): void {
    const transition: StateTransition = {
      orchestratorInstanceId: this.instanceId,
      fromStateId,
      toStateId,
      conditionType: 'ALWAYS',
      autoTrigger: true,
      enabled: true
    };

    this.orchestratorService.createTransition(this.instanceId, transition)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.loadStateMachine();
        },
        error: (err) => {
          this.error = 'Failed to create transition: ' + (err.error?.message || err.message);
        }
      });
  }

  editTransition(): void {
    if (!this.selectedEdge) return;
    this.editingTransition = true;
    this.transitionForm.patchValue({
      name: this.selectedEdge.transition.name || '',
      description: this.selectedEdge.transition.description || '',
      conditionType: this.selectedEdge.transition.conditionType,
      conditionExpression: this.selectedEdge.transition.conditionExpression || '',
      autoTrigger: this.selectedEdge.transition.autoTrigger !== false,
      priority: this.selectedEdge.transition.priority || 0,
      onTransitionTaskId: this.selectedEdge.transition.onTransitionTaskId || '',
      label: this.selectedEdge.transition.label || ''
    });
  }

  saveTransition(): void {
    if (this.transitionForm.invalid || !this.selectedEdge?.transition.id) return;

    this.saving = true;
    const formValue = this.transitionForm.value;

    const transitionData: StateTransition = {
      ...this.selectedEdge.transition,
      name: formValue.name || undefined,
      description: formValue.description || undefined,
      conditionType: formValue.conditionType,
      conditionExpression: formValue.conditionExpression || undefined,
      autoTrigger: formValue.autoTrigger,
      priority: formValue.priority,
      onTransitionTaskId: formValue.onTransitionTaskId || undefined,
      label: formValue.label || undefined
    };

    this.orchestratorService.updateTransition(this.instanceId, this.selectedEdge.transition.id, transitionData)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.saving = false;
          this.editingTransition = false;
          this.loadStateMachine();
        },
        error: (err) => {
          this.saving = false;
          this.error = 'Failed to save transition: ' + (err.error?.message || err.message);
        }
      });
  }

  deleteTransition(): void {
    if (!this.selectedEdge?.transition.id) return;

    if (!confirm('Delete this transition?')) {
      return;
    }

    this.orchestratorService.deleteTransition(this.instanceId, this.selectedEdge.transition.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.selectedEdge = null;
          this.loadStateMachine();
        },
        error: (err) => {
          this.error = 'Failed to delete transition: ' + (err.error?.message || err.message);
        }
      });
  }

  cancelEditTransition(): void {
    this.editingTransition = false;
  }

  // Create default states
  createDefaultStates(): void {
    this.saving = true;
    this.orchestratorService.createDefaultStates(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.saving = false;
          this.loadStateMachine();
        },
        error: (err) => {
          this.saving = false;
          this.error = 'Failed to create default states: ' + (err.error?.message || err.message);
        }
      });
  }

  // Import/Export
  importConfig(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;
    const file = input.files[0];
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const config: StateMachineConfig = JSON.parse(reader.result as string);
        this.orchestratorService.importStateMachineConfig(this.instanceId, config)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: () => {
              this.loadStateMachine();
            },
            error: (err) => {
              this.error = 'Failed to import: ' + (err.error?.message || err.message);
            }
          });
      } catch (e) {
        this.error = 'Invalid JSON file';
      }
      // Reset input so the same file can be re-imported
      input.value = '';
    };
    reader.readAsText(file);
  }

  exportConfig(): void {
    this.orchestratorService.exportStateMachineConfig(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          const json = JSON.stringify(config, null, 2);
          const blob = new Blob([json], { type: 'application/json' });
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `state-machine-${this.instanceId}.json`;
          a.click();
          URL.revokeObjectURL(url);
        },
        error: (err) => {
          this.error = 'Failed to export: ' + (err.error?.message || err.message);
        }
      });
  }

  // Helpers for SVG
  getNodeCenter(node: VisualNode): { x: number; y: number } {
    return {
      x: node.x + node.width / 2,
      y: node.y + node.height / 2
    };
  }

  getEdgePath(edge: VisualEdge): string {
    const from = this.getNodeCenter(edge.fromNode);
    const to = this.getNodeCenter(edge.toNode);

    // Calculate edge intersection with node boundaries
    const fromEdge = this.getNodeEdgePoint(edge.fromNode, to);
    const toEdge = this.getNodeEdgePoint(edge.toNode, from);

    // Create curved path for better visibility
    const dx = toEdge.x - fromEdge.x;
    const dy = toEdge.y - fromEdge.y;
    const midX = fromEdge.x + dx / 2;
    const midY = fromEdge.y + dy / 2;

    // Add slight curve
    const curvature = 0.2;
    const cx = midX - dy * curvature;
    const cy = midY + dx * curvature;

    return `M ${fromEdge.x} ${fromEdge.y} Q ${cx} ${cy} ${toEdge.x} ${toEdge.y}`;
  }

  private getNodeEdgePoint(node: VisualNode, target: { x: number; y: number }): { x: number; y: number } {
    const center = this.getNodeCenter(node);
    const angle = Math.atan2(target.y - center.y, target.x - center.x);

    // Determine which edge the line exits from
    const hw = node.width / 2;
    const hh = node.height / 2;

    const cos = Math.cos(angle);
    const sin = Math.sin(angle);

    let x, y;
    if (Math.abs(cos) * hh > Math.abs(sin) * hw) {
      // Exits from left or right
      x = center.x + (cos > 0 ? hw : -hw);
      y = center.y + sin * hw / Math.abs(cos);
    } else {
      // Exits from top or bottom
      x = center.x + cos * hh / Math.abs(sin);
      y = center.y + (sin > 0 ? hh : -hh);
    }

    return { x, y };
  }

  getArrowPath(edge: VisualEdge): string {
    const from = this.getNodeCenter(edge.fromNode);
    const toEdge = this.getNodeEdgePoint(edge.toNode, from);

    // Calculate arrow direction
    const dx = from.x - toEdge.x;
    const dy = from.y - toEdge.y;
    const len = Math.sqrt(dx * dx + dy * dy);
    const ux = dx / len;
    const uy = dy / len;

    // Arrow size
    const size = 10;
    const angle = Math.PI / 6;

    const p1x = toEdge.x + size * (ux * Math.cos(angle) - uy * Math.sin(angle));
    const p1y = toEdge.y + size * (ux * Math.sin(angle) + uy * Math.cos(angle));
    const p2x = toEdge.x + size * (ux * Math.cos(-angle) - uy * Math.sin(-angle));
    const p2y = toEdge.y + size * (ux * Math.sin(-angle) + uy * Math.cos(-angle));

    return `M ${toEdge.x} ${toEdge.y} L ${p1x} ${p1y} L ${p2x} ${p2y} Z`;
  }

  getCategoryColor(category: StateCategory): string {
    switch (category) {
      case 'INITIAL': return '#4caf50';
      case 'PROCESSING': return '#2196f3';
      case 'WAITING': return '#ff9800';
      case 'TERMINAL': return '#9c27b0';
      case 'ERROR': return '#f44336';
      default: return '#757575';
    }
  }

  getConditionColor(type: TransitionConditionType): string {
    switch (type) {
      case 'ON_SUCCESS': return '#4caf50';
      case 'ON_FAILURE': return '#f44336';
      case 'ALWAYS': return '#2196f3';
      case 'MANUAL': return '#ff9800';
      default: return '#757575';
    }
  }

  clearError(): void {
    this.error = null;
  }
}
