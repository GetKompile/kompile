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

import { Injectable } from '@angular/core';
import { Observable, of, Subject } from 'rxjs';
import { debounceTime, switchMap, catchError, map } from 'rxjs/operators';
import { GraphService } from './graph.service';
import { GraphNode, NODE_COLORS, NodeLevel } from '../models/graph-models';

export interface EntityMention {
  nodeId: string;
  title: string;
  nodeType: NodeLevel;
  description?: string;
  confidence?: number;
  color: string;
}

@Injectable({ providedIn: 'root' })
export class EntityMentionService {

  private searchQuery$ = new Subject<string>();

  constructor(private graphService: GraphService) {}

  searchEntities(query: string, limit: number = 10): Observable<EntityMention[]> {
    if (!query || query.length < 1) {
      return of([]);
    }
    return this.graphService.getNodes(undefined, query, limit).pipe(
      map(nodes => nodes.map(n => this.toMention(n))),
      catchError(() => of([]))
    );
  }

  getEntity(nodeId: string): Observable<EntityMention | null> {
    return this.graphService.getNode(nodeId).pipe(
      map(n => this.toMention(n)),
      catchError(() => of(null))
    );
  }

  getEntityColor(nodeType: NodeLevel): string {
    return NODE_COLORS[nodeType] || NODE_COLORS.ENTITY;
  }

  private toMention(node: GraphNode): EntityMention {
    return {
      nodeId: node.nodeId,
      title: node.title,
      nodeType: node.nodeType,
      description: node.description,
      confidence: node.confidence,
      color: this.getEntityColor(node.nodeType)
    };
  }

  /**
   * Extract @mentions from message text.
   * Format: @[Entity Title](nodeId)
   */
  extractMentions(text: string): EntityMention[] {
    const mentions: EntityMention[] = [];
    const pattern = /@\[([^\]]+)\]\(([^)]+)\)/g;
    let match: RegExpExecArray | null;
    while ((match = pattern.exec(text)) !== null) {
      mentions.push({
        nodeId: match[2],
        title: match[1],
        nodeType: 'ENTITY',
        color: NODE_COLORS.ENTITY
      });
    }
    return mentions;
  }

  /**
   * Convert @mentions to plain text for sending to the backend.
   * @[Entity Title](nodeId) -> "Entity Title"
   */
  mentionsToPlainText(text: string): string {
    return text.replace(/@\[([^\]]+)\]\([^)]+\)/g, '"$1"');
  }

  /**
   * Build context string from mentioned entities for LLM context injection.
   */
  buildEntityContext(mentions: EntityMention[]): string {
    if (mentions.length === 0) return '';
    const entries = mentions.map(m => {
      let entry = `- ${m.title} (${m.nodeType})`;
      if (m.description) entry += `: ${m.description}`;
      return entry;
    });
    return `\n\n[Referenced entities:\n${entries.join('\n')}\n]`;
  }
}
