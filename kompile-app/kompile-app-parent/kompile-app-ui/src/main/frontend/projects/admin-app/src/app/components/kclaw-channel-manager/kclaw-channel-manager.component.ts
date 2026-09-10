/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */

import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, FormControl, FormGroup, Validators } from '@angular/forms';
import { Subject, switchMap, take, takeUntil, takeWhile, timer } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { KClawService } from '@shared/services/kclaw.service';
import {
  ChannelConnectionView,
  ChannelConnectionWrite,
  ChannelEngineDescriptor,
  ChannelFieldDescriptor,
  ChannelProviderDescriptor,
  TelegramDiagnostics,
  TelegramPairing,
  TelegramPairingStart,
  TelegramWebhookInfo
} from '@shared/models/kclaw-models';

@Component({
  selector: 'app-kclaw-channel-manager',
  standalone: false,
  templateUrl: './kclaw-channel-manager.component.html',
  styleUrls: ['./kclaw-channel-manager.component.css']
})
export class KClawChannelManagerComponent implements OnInit, OnDestroy {
  private readonly destroy$ = new Subject<void>();

  connections: ChannelConnectionView[] = [];
  providers: ChannelProviderDescriptor[] = [];
  engines: ChannelEngineDescriptor[] = [];
  form: FormGroup | null = null;
  editing: ChannelConnectionView | null = null;
  selectedProvider: ChannelProviderDescriptor | null = null;
  showConfig = false;
  saving = false;
  newProviderId = '';
  testTarget = '';
  testMessage = '';
  pairingStart: TelegramPairingStart | null = null;
  pairing: TelegramPairing | null = null;
  telegramDiagnostics: TelegramDiagnostics | null = null;
  telegramWebhook: TelegramWebhookInfo | null = null;
  dropPendingUpdates = false;
  authenticationRequired: boolean;
  loginCode = '';

  constructor(
    private readonly fb: FormBuilder,
    private readonly kClawService: KClawService,
    private readonly snackBar: MatSnackBar
  ) {
    this.authenticationRequired = !this.kClawService.hasChannelBrowserSession();
  }

  ngOnInit(): void {
    this.kClawService.channels$
      .pipe(takeUntil(this.destroy$))
      .subscribe(connections => this.connections = connections);
    this.loadControlPlane();
  }

  authenticateBrowser(): void {
    if (!this.loginCode.trim()) return;
    this.kClawService.exchangeChannelBrowserSession(this.loginCode.trim()).subscribe({
      next: () => {
        this.loginCode = '';
        this.authenticationRequired = false;
        window.location.reload();
      },
      error: error => this.notifyError(error)
    });
  }

  private loadControlPlane(): void {
    this.kClawService.getChannelProviders().subscribe({
      next: providers => {
        this.providers = providers;
        this.newProviderId ||= providers[0]?.id || '';
      },
      error: error => this.handleLoadError(error)
    });
    this.kClawService.getChannelEngines().subscribe({
      next: engines => this.engines = engines,
      error: error => this.handleLoadError(error)
    });
    this.kClawService.getChannels().subscribe({ error: error => this.handleLoadError(error) });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  openCreate(providerId = this.newProviderId): void {
    const provider = this.providers.find(candidate => candidate.id === providerId);
    if (!provider) {
      this.snackBar.open('Choose an installed channel provider.', 'Close', { duration: 4000 });
      return;
    }
    this.editing = null;
    this.selectedProvider = provider;
    this.buildForm(provider, null);
    this.resetOperationalState();
    this.showConfig = true;
  }

  editConnection(connection: ChannelConnectionView): void {
    const provider = this.providers.find(candidate => candidate.id === connection.providerId);
    if (!provider) {
      this.snackBar.open(`Provider ${connection.providerId} is not installed.`, 'Close', { duration: 5000 });
      return;
    }
    this.editing = connection;
    this.selectedProvider = provider;
    this.buildForm(provider, connection);
    this.resetOperationalState();
    this.showConfig = true;
    if (connection.providerId === 'telegram') {
      this.refreshTelegramHealth();
    }
  }

  closeConfig(): void {
    this.showConfig = false;
    this.form = null;
    this.editing = null;
    this.selectedProvider = null;
    this.resetOperationalState();
  }

  save(): void {
    if (!this.form || !this.selectedProvider) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.snackBar.open('Complete the required fields.', 'Close', { duration: 4000 });
      return;
    }
    const raw = this.form.getRawValue();
    const settings = this.normalizedSettings(this.selectedProvider, raw.settings || {});
    const secrets = Object.fromEntries(
      Object.entries(raw.secrets || {})
        .map(([name, value]) => [name, String(value || '').trim()])
        .filter(([, value]) => value.length > 0));
    const request: ChannelConnectionWrite = {
      engine: raw.engine,
      agentId: String(raw.agentId || 'jarvis').trim(),
      model: String(raw.model || '').trim(),
      settings,
      secrets,
      enabled: Boolean(raw.enabled)
    };
    this.saving = true;
    const operation = this.editing
      ? this.kClawService.updateChannel(this.editing.name, request)
      : this.kClawService.createChannel({
          ...request,
          name: String(raw.name).trim(),
          providerId: this.selectedProvider.id
        });
    operation.subscribe({
      next: connection => {
        this.saving = false;
        this.snackBar.open(
          `${connection.name} ${this.editing ? 'updated' : 'connected'}.`,
          'Close', { duration: 3000 });
        this.closeConfig();
      },
      error: error => {
        this.saving = false;
        this.notifyError(error);
      }
    });
  }

  toggle(connection: ChannelConnectionView): void {
    const operation = connection.enabled
      ? this.kClawService.disableChannel(connection.name)
      : this.kClawService.enableChannel(connection.name);
    operation.subscribe({
      next: updated => {
        if (updated.enabled && updated.runtimeState === 'STARTING') {
          this.waitForRuntime(updated.name);
        } else {
          this.snackBar.open(
            `${updated.name} is ${updated.enabled ? 'running' : 'disabled'}.`,
            'Close', { duration: 3000 });
        }
      },
      error: error => this.notifyError(error)
    });
  }

  disconnect(connection: ChannelConnectionView): void {
    if (!window.confirm(`Disconnect ${connection.name} and delete its stored credentials?`)) return;
    this.kClawService.disconnectChannel(connection.name).subscribe({
      next: () => this.snackBar.open(`${connection.name} disconnected.`, 'Close', { duration: 3000 }),
      error: error => this.notifyError(error)
    });
  }

  sendTest(): void {
    if (!this.editing || !this.testTarget.trim()) {
      this.snackBar.open('Enter a provider delivery target.', 'Close', { duration: 3000 });
      return;
    }
    this.kClawService.testChannel(this.editing.name, this.testTarget.trim(), this.testMessage.trim()).subscribe({
      next: result => this.snackBar.open(result.message, 'Close', { duration: 4000 }),
      error: error => this.notifyError(error)
    });
  }

  startTelegramPairing(): void {
    if (!this.editing) return;
    this.kClawService.startTelegramPairing(this.editing.name).subscribe({
      next: pairing => {
        this.pairingStart = pairing;
        this.pairing = null;
      },
      error: error => this.notifyError(error)
    });
  }

  refreshPairing(): void {
    if (!this.editing || !this.pairingStart) return;
    this.kClawService.getTelegramPairing(this.editing.name, this.pairingStart.pairingId).subscribe({
      next: pairing => this.pairing = pairing,
      error: error => this.notifyError(error)
    });
  }

  approvePairing(): void {
    if (!this.editing || !this.pairingStart || !this.pairing?.candidate) return;
    const candidate = this.pairing.candidate;
    if (candidate.authorizesEntireChat
        && !window.confirm(`Approve every participant in Telegram ${candidate.chatType} ${candidate.chatId}?`)) {
      return;
    }
    this.kClawService.approveTelegramPairing(
      this.editing.name, this.pairingStart.pairingId, candidate.chatId).subscribe({
      next: connection => {
        this.editing = connection;
        this.pairing = { ...this.pairing!, status: 'APPROVED' };
        this.snackBar.open(`Telegram chat ${candidate.chatId} approved.`, 'Close', { duration: 4000 });
      },
      error: error => this.notifyError(error)
    });
  }

  cancelPairing(): void {
    if (!this.editing || !this.pairingStart) return;
    this.kClawService.cancelTelegramPairing(this.editing.name, this.pairingStart.pairingId).subscribe({
      next: () => {
        this.pairing = this.pairing
          ? { ...this.pairing, status: 'CANCELLED' }
          : null;
        this.pairingStart = null;
      },
      error: error => this.notifyError(error)
    });
  }

  refreshTelegramHealth(): void {
    if (!this.editing || this.editing.providerId !== 'telegram') return;
    this.kClawService.getTelegramDiagnostics(this.editing.name).subscribe({
      next: diagnostics => this.telegramDiagnostics = diagnostics,
      error: () => this.telegramDiagnostics = null
    });
    this.kClawService.getTelegramWebhook(this.editing.name).subscribe({
      next: webhook => this.telegramWebhook = webhook,
      error: error => this.notifyError(error)
    });
  }

  deleteTelegramWebhook(): void {
    if (!this.editing) return;
    this.kClawService.deleteTelegramWebhook(this.editing.name, this.dropPendingUpdates).subscribe({
      next: webhook => {
        this.telegramWebhook = webhook;
        this.snackBar.open('Telegram webhook deleted. Enable the connection to begin polling.',
          'Close', { duration: 5000 });
      },
      error: error => this.notifyError(error)
    });
  }

  settingsForm(): FormGroup {
    return this.form?.get('settings') as FormGroup;
  }

  secretsForm(): FormGroup {
    return this.form?.get('secrets') as FormGroup;
  }

  engineStatus(engineName: string): ChannelEngineDescriptor | undefined {
    return this.engines.find(engine => engine.engine === engineName);
  }

  selectedEngine(): ChannelEngineDescriptor | undefined {
    return this.engineStatus(this.form?.get('engine')?.value);
  }

  getChannelIcon(providerId: string): string {
    return ({ telegram: 'send', discord: 'videogame_asset', slack: 'chat',
      whatsapp: 'message', email: 'email' } as Record<string, string>)[providerId] || 'device_hub';
  }

  whatsAppWebhookUrl(): string {
    return `${window.location.origin}/api/kclaw/channels/webhook/whatsapp`;
  }

  private buildForm(provider: ChannelProviderDescriptor, connection: ChannelConnectionView | null): void {
    const settingControls: Record<string, FormControl> = {};
    for (const field of provider.settings) {
      const value = connection?.settings[field.name] ?? field.defaultValue ?? this.emptyValue(field);
      settingControls[field.name] = new FormControl(this.displayValue(field, value),
        field.required ? Validators.required : []);
    }
    const secretControls: Record<string, FormControl> = {};
    for (const field of provider.secrets) {
      const required = field.required && !connection?.configuredSecrets.includes(field.name);
      secretControls[field.name] = new FormControl('', required ? Validators.required : []);
    }
    const defaultEngine = this.engines.find(engine => engine.available)?.engine || 'REACT';
    this.form = this.fb.group({
      name: new FormControl(connection?.name || provider.id, [Validators.required,
        Validators.pattern('[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}')]),
      providerId: new FormControl(provider.id),
      engine: new FormControl(connection?.engine || defaultEngine, Validators.required),
      agentId: new FormControl(connection?.agentId || 'jarvis', Validators.required),
      model: new FormControl(connection?.model || ''),
      enabled: new FormControl(connection?.enabled || false),
      settings: this.fb.group(settingControls),
      secrets: this.fb.group(secretControls)
    });
    this.form.get('engine')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(engine => {
        if (engine !== 'KOMPILE_CLI') {
          this.form?.get('model')?.setValue('', { emitEvent: false });
        }
      });
  }

  private waitForRuntime(name: string): void {
    timer(500, 1000).pipe(
      take(30),
      switchMap(() => this.kClawService.getChannel(name)),
      takeWhile(connection => connection.runtimeState === 'STARTING', true),
      takeUntil(this.destroy$)
    ).subscribe({
      next: connection => {
        if (connection.runtimeState !== 'STARTING') {
          this.kClawService.getChannels().subscribe();
          this.snackBar.open(
            connection.runtimeState === 'RUNNING'
              ? `${name} is ready.`
              : connection.lastError || `${name} did not become ready.`,
            'Close', { duration: 5000 });
        }
      },
      error: error => this.notifyError(error)
    });
  }

  private normalizedSettings(
    provider: ChannelProviderDescriptor, values: Record<string, unknown>
  ): Record<string, unknown> {
    const normalized: Record<string, unknown> = {};
    for (const field of provider.settings) {
      const raw = values[field.name];
      if (field.type === 'BOOLEAN') normalized[field.name] = Boolean(raw);
      else if (field.type === 'INTEGER') normalized[field.name] = Number(raw);
      else if (field.type === 'STRING_LIST') normalized[field.name] = this.list(raw).map(String);
      else if (field.type === 'LONG_LIST') normalized[field.name] = this.list(raw).map(Number);
      else normalized[field.name] = String(raw ?? '').trim();
    }
    return normalized;
  }

  private list(value: unknown): string[] {
    if (Array.isArray(value)) return value.map(String).filter(item => item.length > 0);
    return String(value ?? '').split(',').map(item => item.trim()).filter(item => item.length > 0);
  }

  private displayValue(field: ChannelFieldDescriptor, value: unknown): unknown {
    return field.type === 'STRING_LIST' || field.type === 'LONG_LIST'
      ? (Array.isArray(value) ? value.join(', ') : value)
      : value;
  }

  private emptyValue(field: ChannelFieldDescriptor): unknown {
    if (field.type === 'BOOLEAN') return false;
    if (field.type === 'INTEGER') return 0;
    return '';
  }

  private resetOperationalState(): void {
    this.testTarget = '';
    this.testMessage = '';
    this.pairingStart = null;
    this.pairing = null;
    this.telegramDiagnostics = null;
    this.telegramWebhook = null;
    this.dropPendingUpdates = false;
  }

  private notifyError(error: any): void {
    const message = error?.error?.message || error?.error?.error || error?.message || 'Channel operation failed.';
    this.snackBar.open(message, 'Close', { duration: 6000 });
  }

  private handleLoadError(error: any): void {
    if (error?.status === 401) {
      this.authenticationRequired = true;
      return;
    }
    this.notifyError(error);
  }
}
