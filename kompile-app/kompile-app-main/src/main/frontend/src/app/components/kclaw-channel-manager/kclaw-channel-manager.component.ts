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
import { FormBuilder, FormGroup } from '@angular/forms';
import { Subject, takeUntil } from 'rxjs';
import { KClawService } from '../../services/kclaw.service';
import { ChannelStatus, ChannelConfig, AgentDefinition } from '../../models/kclaw-models';
import { MatSnackBar } from '@angular/material/snack-bar';

@Component({
  selector: 'app-kclaw-channel-manager',
  standalone: false,
  templateUrl: './kclaw-channel-manager.component.html',
  styleUrls: ['./kclaw-channel-manager.component.css']
})
export class KClawChannelManagerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  channels: ChannelStatus[] = [];
  agents: AgentDefinition[] = [];
  selectedChannel: ChannelStatus | null = null;
  showConfig = false;
  
  channelTypes = ['telegram', 'discord', 'slack', 'whatsapp', 'email'];

  oauthStatus: Record<string, {connected: boolean, teamName?: string}> = {};

  telegramForm: FormGroup;
  discordForm: FormGroup;
  slackForm: FormGroup;
  whatsappForm: FormGroup;
  emailForm: FormGroup;

  constructor(
    private fb: FormBuilder,
    private kClawService: KClawService,
    private snackBar: MatSnackBar
  ) {
    this.telegramForm = this.fb.group({
      botToken: [''],
      allowedChatIds: [[]]
    });
    
    this.discordForm = this.fb.group({
      botToken: [''],
      allowedChannelIds: [[]],
      allowedGuildIds: [[]]
    });
    
    this.slackForm = this.fb.group({
      botToken: [''],
      appToken: [''],
      allowedChannelIds: [[]],
      respondToAllMessages: [false]
    });
    
    this.whatsappForm = this.fb.group({
      accessToken: [''],
      phoneNumberId: [''],
      verifyToken: [''],
      allowedPhoneNumbers: [[]]
    });
    
    this.emailForm = this.fb.group({
      imapHost: ['imap.gmail.com'],
      imapPort: [993],
      username: [''],
      password: [''],
      smtpHost: ['smtp.gmail.com'],
      smtpPort: [587],
      fromAddress: [''],
      fromName: ['KClaw Assistant'],
      allowedSenders: [[]]
    });
  }

  ngOnInit(): void {
    this.kClawService.channels$
      .pipe(takeUntil(this.destroy$))
      .subscribe(channels => this.channels = channels);

    this.kClawService.agents$
      .pipe(takeUntil(this.destroy$))
      .subscribe(agents => this.agents = agents);

    this.kClawService.getChannels().subscribe();
    this.kClawService.getAgents().subscribe();

    // Check OAuth status for Slack and Discord
    for (const provider of ['slack', 'discord']) {
      this.kClawService.getOAuthStatus(provider)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: status => this.oauthStatus[provider] = status,
          error: () => this.oauthStatus[provider] = { connected: false }
        });
    }

    // Listen for OAuth completion via postMessage from popup
    if (typeof window !== 'undefined') {
      window.addEventListener('message', (event) => {
        if (event.data?.type === 'oauth-complete') {
          const provider = event.data.provider;
          this.kClawService.getOAuthStatus(provider).subscribe(status => {
            this.oauthStatus[provider] = status;
            this.snackBar.open(`Connected to ${provider}`, 'Close', { duration: 3000 });
          });
        }
      });
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  startChannel(channelName: string): void {
    this.kClawService.startChannel(channelName).subscribe({
      next: () => this.snackBar.open(`${channelName} started`, 'Close', { duration: 3000 }),
      error: (err) => this.snackBar.open('Error: ' + err.message, 'Close', { duration: 5000 })
    });
  }

  stopChannel(channelName: string): void {
    this.kClawService.stopChannel(channelName).subscribe({
      next: () => this.snackBar.open(`${channelName} stopped`, 'Close', { duration: 3000 }),
      error: (err) => this.snackBar.open('Error: ' + err.message, 'Close', { duration: 5000 })
    });
  }

  configureChannel(channel: ChannelStatus): void {
    this.selectedChannel = channel;
    this.showConfig = true;
  }

  saveChannelConfig(): void {
    if (!this.selectedChannel) return;
    
    const channelName = this.selectedChannel.channelName;
    const config: ChannelConfig = {
      channelId: channelName,
      channelType: channelName,
      agentId: this.agents.find(a => a.isDefault)?.name || 'jarvis',
      enabled: true
    };

    switch (channelName) {
      case 'telegram':
        config.telegram = this.telegramForm.value;
        break;
      case 'discord':
        config.discord = this.discordForm.value;
        break;
      case 'slack':
        config.slack = this.slackForm.value;
        break;
      case 'whatsapp':
        config.whatsapp = this.whatsappForm.value;
        break;
      case 'email':
        config.email = this.emailForm.value;
        break;
    }

    this.kClawService.updateChannelConfig(channelName, config).subscribe({
      next: () => {
        this.snackBar.open('Configuration saved', 'Close', { duration: 3000 });
        this.showConfig = false;
      },
      error: (err) => this.snackBar.open('Error: ' + err.message, 'Close', { duration: 5000 })
    });
  }

  closeConfig(): void {
    this.showConfig = false;
    this.selectedChannel = null;
  }

  getChannelIcon(channelName: string): string {
    const icons: Record<string, string> = {
      'telegram': 'send',
      'discord': 'videogame_asset',
      'slack': 'chat',
      'whatsapp': 'message',
      'email': 'email'
    };
    return icons[channelName] || 'device_hub';
  }

  supportsOAuth(channelName: string): boolean {
    return channelName === 'slack' || channelName === 'discord';
  }

  connectOAuth(provider: string): void {
    this.kClawService.startOAuth(provider).subscribe({
      next: (result) => {
        // Open the authorization URL in a popup
        window.open(result.authorizationUrl, `oauth-${provider}`, 'width=600,height=700');
      },
      error: (err) => {
        if (err.status === 400) {
          this.snackBar.open('Configure OAuth client ID and secret first', 'Close', { duration: 5000 });
        } else {
          this.snackBar.open('Error: ' + err.message, 'Close', { duration: 5000 });
        }
      }
    });
  }

  disconnectOAuth(provider: string): void {
    this.kClawService.disconnectOAuth(provider).subscribe({
      next: () => {
        this.oauthStatus[provider] = { connected: false };
        this.snackBar.open(`Disconnected from ${provider}`, 'Close', { duration: 3000 });
      }
    });
  }
}
