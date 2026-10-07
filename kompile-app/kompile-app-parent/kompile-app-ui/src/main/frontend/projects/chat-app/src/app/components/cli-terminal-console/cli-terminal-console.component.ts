import { AfterViewInit, Component, ElementRef, EventEmitter, Input, NgZone, OnChanges, OnDestroy, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { BaseService } from '@shared/services/base.service';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';

export interface CliTerminalView {
  id: string; sessionId: string; workingDirectory: string; pid: number;
  state: string; exitCode: number | null; cols: number; rows: number;
}

/** A browser attachment, not the owner of the child process. Hiding/unmounting never kills it. */
@Component({
  selector: 'app-cli-terminal-console', standalone: true, imports: [CommonModule, FormsModule],
  encapsulation: ViewEncapsulation.None,
  styleUrls: ['./cli-terminal-console.component.css'],
  template: `
    <section class="cli-console" [class.cli-console-hidden]="!visible" aria-label="Kompile CLI terminal">
      <header>
        <strong>CLI terminal</strong>
        <select aria-label="Managed terminal sessions" [(ngModel)]="selectedId" (ngModelChange)="connect($event)" [disabled]="busy">
          <option value="">Select terminal</option>
          <option *ngFor="let session of sessions" [value]="session.id">PID {{session.pid}} · {{session.state}} · {{session.workingDirectory}}</option>
        </select>
        <button type="button" (click)="launch()" [disabled]="!available || busy">Launch kompile chat</button>
        <button type="button" (click)="refresh()" [disabled]="!available || busy">Refresh</button>
        <button type="button" (click)="connect(selectedId)" [disabled]="!selectedId || busy">Reconnect</button>
        <button type="button" (click)="interrupt()" [disabled]="!connected || current?.state !== 'RUNNING'">Ctrl-C</button>
        <button type="button" (click)="stop()" [disabled]="!current || current.state !== 'RUNNING' || busy">Stop process</button>
        <button type="button" (click)="remove()" [disabled]="!current || current.state === 'RUNNING' || busy">Remove</button>
        <button type="button" (click)="hidden.emit()" aria-label="Hide CLI terminal">Hide</button>
      </header>
      <small>New chat in {{workingDirectory || 'the launch folder'}}. Separate CLI session; hiding does not stop it. Detached sessions expire after 30 minutes.</small>
      <small *ngIf="current">Session {{current.sessionId}} · PID {{current.pid}} · {{current.state}}<span *ngIf="current.exitCode !== null"> (exit {{current.exitCode}})</span> · {{connected ? 'Connected' : 'Disconnected'}}</small>
      <p *ngIf="error" role="alert">{{error}}</p>
      <p *ngIf="notice" role="status">{{notice}}</p>
      <div #screen class="cli-console-screen" aria-label="Interactive CLI input and output"></div>
    </section>`
})
export class CliTerminalConsoleComponent extends BaseService implements AfterViewInit, OnChanges, OnDestroy {
  @Input() workingDirectory = '';
  @Input() visible = true;
  @Output() hidden = new EventEmitter<void>();
  @ViewChild('screen', { static: true }) screen!: ElementRef<HTMLElement>;
  available = false;
  busy = false;
  connected = false;
  sessions: CliTerminalView[] = [];
  selectedId = '';
  error = '';
  notice = '';
  private terminal?: Terminal;
  private fitAddon?: FitAddon;
  private socket?: WebSocket;
  private observer?: ResizeObserver;
  private resizeTimer?: ReturnType<typeof setTimeout>;
  private destroyed = false;
  private readonly subscriptions = new Subscription();
  private readonly url = `${this.backendUrl}/agents/chat/terminal`;
  private readonly options = { headers: { 'X-Kompile-Terminal': '1' } };
  constructor(private http: HttpClient, private zone: NgZone) { super(); }
  get current(): CliTerminalView | undefined { return this.sessions.find(s => s.id === this.selectedId); }
  ngAfterViewInit(): void {
    this.subscriptions.add(this.http.get<{available: boolean; reason?: string}>(this.url).subscribe({
      next: capabilities => {
        this.available = capabilities.available;
        if (this.available) this.refresh(); else this.error = capabilities.reason || 'Local CLI terminal is unavailable';
      }, error: err => this.fail(err)
    }));
    this.zone.runOutsideAngular(() => {
      this.observer = new ResizeObserver(() => this.scheduleFit());
      this.observer.observe(this.screen.nativeElement);
    });
  }
  ngOnChanges(): void { this.scheduleFit(); }
  refresh(): void {
    this.subscriptions.add(this.http.get<CliTerminalView[]>(`${this.url}/sessions`).subscribe({
      next: sessions => {
        this.sessions = sessions;
        if (this.selectedId && !this.current) { this.disconnect(); this.selectedId = ''; }
        if (!this.selectedId && sessions.length) { this.selectedId = sessions[0].id; this.connect(this.selectedId); }
      }, error: err => this.fail(err)
    }));
  }
  launch(): void {
    if (this.busy || !this.available) return;
    this.busy = true; this.error = '';
    this.subscriptions.add(this.http.post<CliTerminalView>(`${this.url}/sessions`, {
      workingDirectory: this.workingDirectory || null, cols: this.terminal?.cols || 100, rows: this.terminal?.rows || 24
    }, this.options).subscribe({
      next: session => {
        this.busy = false; this.sessions.push(session); this.selectedId = session.id; this.connect(session.id);
      }, error: err => { this.busy = false; this.fail(err); }
    }));
  }
  connect(id: string): void {
    this.disconnect();
    if (!id || this.destroyed) return;
    this.selectedId = id; this.error = ''; this.notice = '';
    this.terminal?.dispose();
    this.screen.nativeElement.replaceChildren();
    this.terminal = new Terminal({ cursorBlink: true, scrollback: 5000, fontSize: 13, theme: { background: '#111827' } });
    this.fitAddon = new FitAddon();
    this.terminal.loadAddon(this.fitAddon);
    this.terminal.open(this.screen.nativeElement);
    this.terminal.onData(data => this.input(data));
    const url = new URL(`${this.url}/socket/${encodeURIComponent(id)}`, window.location.href);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(url.toString());
    this.socket = socket;
    socket.onopen = () => this.zone.run(() => {
      if (this.socket !== socket) return;
      this.connected = true; this.scheduleFit(); this.terminal?.focus();
    });
    socket.onmessage = event => {
      if (this.socket !== socket) return;
      const message = JSON.parse(event.data);
      if (message.type === 'output') this.terminal?.write(message.data);
      else if (message.type === 'reset') {
        this.terminal?.reset();
        if (message.truncated) this.zone.run(() => this.notice = 'Older terminal output was trimmed; showing the bounded replay.');
      } else if (message.type === 'status') this.zone.run(() => {
        this.sessions = this.sessions.map(session => session.id === id ? message.terminal : session);
      });
      else if (message.type === 'error') this.zone.run(() => this.error = message.message);
    };
    socket.onclose = () => this.zone.run(() => {
      if (this.socket !== socket) return;
      this.connected = false;
      this.notice = 'Terminal detached. Reconnect to the same process, or refresh its status.';
    });
    socket.onerror = () => this.zone.run(() => {
      if (this.socket === socket) this.error = 'Cannot connect to the terminal. Use Refresh or Reconnect.';
    });
  }
  interrupt(): void { this.input('\x03'); }
  input(data: string): void {
    if (this.socket?.readyState !== WebSocket.OPEN) return;
    if (this.socket.bufferedAmount > 128 * 1024 || data.length > 64 * 1024) {
      this.zone.run(() => this.error = 'Terminal input is too large or connection is backed up; paste smaller chunks.');
      return;
    }
    for (let offset = 0; offset < data.length;) {
      let end = Math.min(offset + 8192, data.length);
      // Do not split a UTF-16 surrogate pair across separately encoded input frames.
      if (end < data.length && data.charCodeAt(end - 1) >= 0xD800 && data.charCodeAt(end - 1) <= 0xDBFF) end--;
      this.socket.send(JSON.stringify({ type: 'input', data: data.slice(offset, end) }));
      offset = end;
    }
  }
  private scheduleFit(): void {
    clearTimeout(this.resizeTimer);
    if (this.destroyed) return;
    this.resizeTimer = setTimeout(() => {
      if (!this.visible || !this.terminal || !this.screen.nativeElement.clientWidth || !this.screen.nativeElement.clientHeight) return;
      this.fitAddon?.fit();
      if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify({ type: 'resize',
        cols: Math.max(10, Math.min(500, this.terminal.cols)), rows: Math.max(2, Math.min(200, this.terminal.rows)) }));
    }, 100);
  }
  stop(): void {
    if (!this.current || this.busy) return;
    this.busy = true;
    this.subscriptions.add(this.http.post(`${this.url}/sessions/${this.selectedId}/stop`, {}, this.options).subscribe({
      next: () => { this.busy = false; this.refresh(); }, error: err => { this.busy = false; this.fail(err); }
    }));
  }
  remove(): void {
    if (!this.current || this.current.state === 'RUNNING' || this.busy) return;
    this.busy = true;
    this.subscriptions.add(this.http.delete(`${this.url}/sessions/${this.selectedId}`, this.options).subscribe({
      next: () => { this.busy = false; this.disconnect(); this.selectedId = ''; this.refresh(); },
      error: err => { this.busy = false; this.fail(err); }
    }));
  }
  private fail(err: any): void { this.error = err?.error?.message || err?.error?.detail || err?.message || 'Terminal operation failed'; }
  private disconnect(): void {
    const socket = this.socket; this.socket = undefined; this.connected = false;
    if (socket) { socket.onclose = null; socket.onerror = null; socket.close(); }
  }
  ngOnDestroy(): void {
    this.destroyed = true; this.subscriptions.unsubscribe(); this.disconnect();
    clearTimeout(this.resizeTimer); this.observer?.disconnect(); this.terminal?.dispose();
  }
}
