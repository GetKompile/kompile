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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

export type ToolsSubTab =
  | 'indexBrowser'
  | 'mcp'
  | 'orchestrator'
  | 'chunkManager'
  | 'knowledgeGraph'
  | 'backup'
  | 'prompts'
  | 'pipelines'
  | 'processEngine'
  | 'eventObservation'
  | 'causalAttribution'
  | 'workflows'
  | 'computeGraph'
  | 'crawlers'
  | 'codeProjects';

const KNOWN_TABS: ToolsSubTab[] = [
  'indexBrowser', 'mcp', 'orchestrator', 'chunkManager', 'knowledgeGraph',
  'backup', 'prompts', 'pipelines', 'processEngine', 'eventObservation', 'causalAttribution',
  'workflows', 'computeGraph', 'crawlers', 'codeProjects'
];

@Component({
  standalone: false,
  selector: 'app-tools-hub',
  templateUrl: './tools-hub.component.html',
  styleUrls: ['./tools-hub.component.css']
})
export class ToolsHubComponent implements OnInit, OnDestroy {
  activeSubTab: ToolsSubTab = 'indexBrowser';

  private destroy$ = new Subject<void>();
  private readonly hashChangeListener = () => this.applySubTabFromLocation();

  constructor(private route: ActivatedRoute) {}

  ngOnInit(): void {
    this.applySubTabFromLocation();

    if (typeof window !== 'undefined') {
      window.addEventListener('hashchange', this.hashChangeListener);
    }

    this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe(params => {
      const tab = params.get('tab') as ToolsSubTab | null;
      if (tab && this.isKnownTab(tab)) {
        this.activeSubTab = tab;
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    if (typeof window !== 'undefined') {
      window.removeEventListener('hashchange', this.hashChangeListener);
    }
  }

  selectSubTab(tab: ToolsSubTab): void {
    this.activeSubTab = tab;
    if (typeof window !== 'undefined') {
      window.location.hash = `/tools?tab=${encodeURIComponent(tab)}`;
    }
  }

  private isKnownTab(tab: string): tab is ToolsSubTab {
    return (KNOWN_TABS as string[]).includes(tab);
  }

  private applySubTabFromLocation(): void {
    if (typeof window === 'undefined') {
      return;
    }
    const query = this.currentHashQuery();
    if (!query) {
      return;
    }
    const params = new URLSearchParams(query);
    const tab = params.get('tab');
    if (tab && this.isKnownTab(tab)) {
      this.activeSubTab = tab;
    }
  }

  private currentHashQuery(): string {
    if (typeof window === 'undefined') {
      return '';
    }
    const hash = window.location.hash; // e.g. "#/tools?tab=mcp"
    const qIndex = hash.indexOf('?');
    return qIndex >= 0 ? hash.slice(qIndex + 1) : '';
  }
}
