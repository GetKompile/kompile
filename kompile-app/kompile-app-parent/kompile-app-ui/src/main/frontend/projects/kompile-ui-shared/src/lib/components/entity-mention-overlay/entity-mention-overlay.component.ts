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

import {
  Component, Input, Output, EventEmitter,
  ChangeDetectionStrategy
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { EntityMention } from '../../services/entity-mention.service';

@Component({
  selector: 'app-entity-mention-overlay',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="mention-overlay" *ngIf="visible && results.length > 0"
         [style.bottom.px]="bottom" [style.left.px]="left">
      <div class="mention-header">Entities</div>
      <div class="mention-list">
        <div *ngFor="let entity of results; let i = index"
             class="mention-item"
             [class.active]="i === activeIndex"
             (click)="select(entity)"
             (mouseenter)="activeIndex = i">
          <span class="mention-type-dot" [style.background]="entity.color"></span>
          <div class="mention-info">
            <span class="mention-title">{{ entity.title }}</span>
            <span class="mention-type">{{ entity.nodeType }}</span>
          </div>
          <span class="mention-desc" *ngIf="entity.description">{{ entity.description | slice:0:60 }}</span>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .mention-overlay {
      position: absolute;
      z-index: 1000;
      min-width: 280px;
      max-width: 420px;
      max-height: 240px;
      overflow-y: auto;
      background: var(--bg-surface, #fff);
      border: 1px solid var(--border-color, #ddd);
      border-radius: 8px;
      box-shadow: 0 4px 16px rgba(0,0,0,0.15);
      font-size: 0.88rem;
    }
    .mention-header {
      padding: 6px 12px;
      font-size: 0.72em;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-secondary, #888);
      border-bottom: 1px solid var(--border-subtle, #eee);
    }
    .mention-list {
      padding: 4px 0;
    }
    .mention-item {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 7px 12px;
      cursor: pointer;
      transition: background 0.1s;
    }
    .mention-item:hover, .mention-item.active {
      background: var(--bg-hover, #f0f0f5);
    }
    .mention-type-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .mention-info {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
    .mention-title {
      font-weight: 500;
      color: var(--text-primary, #222);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .mention-type {
      font-size: 0.75em;
      color: var(--text-secondary, #888);
    }
    .mention-desc {
      flex-shrink: 1;
      font-size: 0.8em;
      color: var(--text-tertiary, #aaa);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      margin-left: auto;
    }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EntityMentionOverlayComponent {
  @Input() visible: boolean = false;
  @Input() results: EntityMention[] = [];
  @Input() activeIndex: number = 0;
  @Input() bottom: number = 60;
  @Input() left: number = 16;

  @Output() entitySelected = new EventEmitter<EntityMention>();
  @Output() dismissed = new EventEmitter<void>();

  select(entity: EntityMention): void {
    this.entitySelected.emit(entity);
  }
}
