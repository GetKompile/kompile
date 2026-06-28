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

import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';

interface FragLayoutNode {
  id: string;
  x: number;
  y: number;
  role: 'INPUT' | 'RESIDENT';
  label: string;
}

interface FragLayoutEdge {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  /** shortened for line body — stays clear of node shapes */
  lx2: number;
  ly2: number;
  /** arrowhead polygon points string */
  arrowPoints: string;
  strokeWidth: number;
  strengthPct: string;
  parentName: string;
  childName: string;
}

interface FragLayout {
  inputNodes: FragLayoutNode[];
  residentNodes: FragLayoutNode[];
  edges: FragLayoutEdge[];
  width: number;
  height: number;
  hasContext: boolean;
}

/** Axis-aligned pixel offset from a node center so lines start/end outside the shape. */
const NODE_RADIUS = 8;
const ARROW_SIZE = 7;

@Component({
  selector: 'app-mfrag-diagram',
  standalone: true,
  imports: [CommonModule],
  template: `
    <svg *ngIf="layout"
         [attr.width]="layout.width"
         [attr.height]="layout.height"
         class="mfrag-svg"
         xmlns="http://www.w3.org/2000/svg">

      <!-- Context guard: amber dashed border -->
      <rect *ngIf="layout.hasContext"
            x="2" y="2"
            [attr.width]="layout.width - 4"
            [attr.height]="layout.height - 4"
            fill="none"
            stroke="#f59e0b"
            stroke-dasharray="5,3"
            stroke-width="1.5"
            rx="4"/>

      <!-- Edges: line + inline arrowhead polygon -->
      <g *ngFor="let e of layout.edges" class="mfrag-edge">
        <line
          [attr.x1]="e.x1" [attr.y1]="e.y1"
          [attr.x2]="e.lx2" [attr.y2]="e.ly2"
          [attr.stroke-width]="e.strokeWidth"
          stroke="#667eea" opacity="0.55"/>
        <polygon [attr.points]="e.arrowPoints"
                 fill="#667eea" opacity="0.7"/>
      </g>

      <!-- Input nodes: purple diamonds (left column) -->
      <g *ngFor="let n of layout.inputNodes">
        <polygon [attr.points]="diamondPoints(n.x, n.y)"
                 fill="#8b5cf6" stroke="#7c3aed" stroke-width="1.5"/>
        <text [attr.x]="n.x - 13"
              [attr.y]="n.y + 4"
              font-size="9"
              fill="#8b5cf6"
              text-anchor="end"
              font-family="monospace">{{ truncate(n.label, 11) }}</text>
      </g>

      <!-- Resident nodes: green circles (right column) -->
      <g *ngFor="let n of layout.residentNodes">
        <circle [attr.cx]="n.x" [attr.cy]="n.y"
                r="7"
                fill="#22c55e" stroke="#16a34a" stroke-width="1.5"/>
        <text [attr.x]="n.x + 11"
              [attr.y]="n.y + 4"
              font-size="9"
              fill="#22c55e"
              font-family="monospace">{{ truncate(n.label, 11) }}</text>
      </g>
    </svg>
  `,
  styles: [`
    :host { display: block; }
    .mfrag-svg { overflow: visible; }
  `]
})
export class MfragDiagramComponent implements OnChanges {
  /** Full fragment object from MTheoryStructure.fragments[] */
  @Input() fragment: any;

  layout: FragLayout | null = null;

  ngOnChanges(_: SimpleChanges): void {
    this.layout = this.fragment ? this.computeLayout(this.fragment) : null;
  }

  private computeLayout(frag: any): FragLayout {
    const inputNodes: any[] = frag.inputNodes || [];
    const residentNodes: any[] = frag.residentNodes || [];
    const edges: any[] = frag.edges || [];
    const hasContext = (frag.contexts || []).length > 0;

    const SVG_W = 310;
    const LEFT_X = 50;
    const RIGHT_X = 250;
    const TOP_PAD = hasContext ? 18 : 10;
    const ROW_H = 30;
    const maxRows = Math.max(inputNodes.length, residentNodes.length, 1);
    const SVG_H = maxRows * ROW_H + TOP_PAD + 16;

    // Build position map for edge routing
    const posMap = new Map<string, { x: number; y: number }>();

    const inNodes: FragLayoutNode[] = inputNodes.map((rv: any, i: number) => {
      const y = TOP_PAD + i * ROW_H + ROW_H / 2;
      posMap.set(rv.name, { x: LEFT_X, y });
      return { id: rv.name, x: LEFT_X, y, role: 'INPUT', label: rv.name };
    });

    const resNodes: FragLayoutNode[] = residentNodes.map((rv: any, i: number) => {
      const y = TOP_PAD + i * ROW_H + ROW_H / 2;
      posMap.set(rv.name, { x: RIGHT_X, y });
      return { id: rv.name, x: RIGHT_X, y, role: 'RESIDENT', label: rv.name };
    });

    const fedges: FragLayoutEdge[] = edges.map((e: any) => {
      const src = posMap.get(e.parent) || { x: LEFT_X, y: TOP_PAD + ROW_H / 2 };
      const tgt = posMap.get(e.child) || { x: RIGHT_X, y: TOP_PAD + ROW_H / 2 };
      const strength = Math.max(0, Math.min(1, e.strength || 0));
      return {
        ...this.routeEdge(src.x, src.y, tgt.x, tgt.y),
        strokeWidth: Math.max(0.5, strength * 2.5),
        strengthPct: (strength * 100).toFixed(0) + '%',
        parentName: e.parent,
        childName: e.child,
      };
    });

    return { inputNodes: inNodes, residentNodes: resNodes, edges: fedges, width: SVG_W, height: SVG_H, hasContext };
  }

  /** Compute start/end coords (offset from node shapes) + inline arrowhead. */
  private routeEdge(sx: number, sy: number, tx: number, ty: number): Pick<FragLayoutEdge, 'x1'|'y1'|'x2'|'y2'|'lx2'|'ly2'|'arrowPoints'> {
    const dx = tx - sx;
    const dy = ty - sy;
    const len = Math.sqrt(dx * dx + dy * dy) || 1;
    const ux = dx / len;
    const uy = dy / len;

    // Offset from source (circle/diamond radius + margin)
    const x1 = sx + ux * (NODE_RADIUS + 2);
    const y1 = sy + uy * (NODE_RADIUS + 2);

    // End point at target
    const x2 = tx - ux * (NODE_RADIUS + 2);
    const y2 = ty - uy * (NODE_RADIUS + 2);

    // Line body ends just before arrowhead base
    const lx2 = x2 - ux * ARROW_SIZE;
    const ly2 = y2 - uy * ARROW_SIZE;

    // Perpendicular for arrowhead wing
    const px = -uy;
    const py = ux;
    const hw = ARROW_SIZE * 0.4;

    const arrowPoints = [
      `${x2.toFixed(1)},${y2.toFixed(1)}`,
      `${(lx2 + px * hw).toFixed(1)},${(ly2 + py * hw).toFixed(1)}`,
      `${(lx2 - px * hw).toFixed(1)},${(ly2 - py * hw).toFixed(1)}`,
    ].join(' ');

    return { x1, y1, x2, y2, lx2, ly2, arrowPoints };
  }

  /** Returns polygon points string for a diamond centred at (cx,cy). */
  diamondPoints(cx: number, cy: number): string {
    const r = NODE_RADIUS;
    return `${cx},${cy - r} ${cx + r},${cy} ${cx},${cy + r} ${cx - r},${cy}`;
  }

  truncate(s: string, max: number): string {
    return s && s.length > max ? s.substring(0, max - 1) + '…' : (s || '');
  }
}
