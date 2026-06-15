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
import { Subject, takeUntil } from 'rxjs';
import { KClawService } from '../../services/kclaw.service';
import { AgentDefinition, ChannelStatus, HeartbeatInfo, KClawConfig } from '../../models/kclaw-models';

@Component({
  selector: 'app-kclaw-hub',
  standalone: false,
  templateUrl: './kclaw-hub.component.html',
  styleUrls: ['./kclaw-hub.component.css']
})
export class KClawHubComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  config: KClawConfig | null = null;
  agents: AgentDefinition[] = [];
  channels: ChannelStatus[] = [];
  heartbeats: HeartbeatInfo[] = [];
  
  loading = false;
  error: string | null = null;

  activeTab: 'agents' | 'channels' | 'chat' | 'sessions' | 'heartbeats' | 'permissions' = 'agents';
  activeTabIndex: number = 0;

  stats = {
    totalAgents: 0,
    activeChannels: 0,
    scheduledHeartbeats: 0,
    totalSessions: 0
  };

  constructor(private kClawService: KClawService) {}

  ngOnInit(): void {
    this.loadData();
    this.subscribeToState();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private loadData(): void {
    this.loading = true;
    
    this.kClawService.getConfig().subscribe();
    this.kClawService.getAgents().subscribe();
    this.kClawService.getChannels().subscribe();
    this.kClawService.getHeartbeats().subscribe();
  }

  private subscribeToState(): void {
    this.kClawService.config$
      .pipe(takeUntil(this.destroy$))
      .subscribe(config => this.config = config);

    this.kClawService.agents$
      .pipe(takeUntil(this.destroy$))
      .subscribe(agents => {
        this.agents = agents;
        this.stats.totalAgents = agents.length;
      });

    this.kClawService.channels$
      .pipe(takeUntil(this.destroy$))
      .subscribe(channels => {
        this.channels = channels;
        this.stats.activeChannels = channels.filter(c => c.running).length;
      });

    this.kClawService.heartbeats$
      .pipe(takeUntil(this.destroy$))
      .subscribe(heartbeats => {
        this.heartbeats = heartbeats;
        this.stats.scheduledHeartbeats = heartbeats.filter(h => h.status === 'SCHEDULED').length;
      });

    this.kClawService.loading$
      .pipe(takeUntil(this.destroy$))
      .subscribe(loading => this.loading = loading);

    this.kClawService.error$
      .pipe(takeUntil(this.destroy$))
      .subscribe(error => this.error = error);
  }

  setTab(tab: typeof this.activeTab): void {
    this.activeTab = tab;
  }

  refresh(): void {
    this.loadData();
  }
}
