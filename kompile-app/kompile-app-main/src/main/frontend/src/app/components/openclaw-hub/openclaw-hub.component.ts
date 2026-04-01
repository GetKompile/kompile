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
import { OpenClawService } from '../../services/openclaw.service';
import { AgentDefinition, ChannelStatus, HeartbeatInfo, OpenClawConfig } from '../../models/openclaw-models';

@Component({
  selector: 'app-openclaw-hub',
  standalone: false,
  templateUrl: './openclaw-hub.component.html',
  styleUrls: ['./openclaw-hub.component.css']
})
export class OpenClawHubComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  config: OpenClawConfig | null = null;
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

  constructor(private openClawService: OpenClawService) {}

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
    
    this.openClawService.getConfig().subscribe();
    this.openClawService.getAgents().subscribe();
    this.openClawService.getChannels().subscribe();
    this.openClawService.getHeartbeats().subscribe();
  }

  private subscribeToState(): void {
    this.openClawService.config$
      .pipe(takeUntil(this.destroy$))
      .subscribe(config => this.config = config);

    this.openClawService.agents$
      .pipe(takeUntil(this.destroy$))
      .subscribe(agents => {
        this.agents = agents;
        this.stats.totalAgents = agents.length;
      });

    this.openClawService.channels$
      .pipe(takeUntil(this.destroy$))
      .subscribe(channels => {
        this.channels = channels;
        this.stats.activeChannels = channels.filter(c => c.running).length;
      });

    this.openClawService.heartbeats$
      .pipe(takeUntil(this.destroy$))
      .subscribe(heartbeats => {
        this.heartbeats = heartbeats;
        this.stats.scheduledHeartbeats = heartbeats.filter(h => h.status === 'SCHEDULED').length;
      });

    this.openClawService.loading$
      .pipe(takeUntil(this.destroy$))
      .subscribe(loading => this.loading = loading);

    this.openClawService.error$
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
