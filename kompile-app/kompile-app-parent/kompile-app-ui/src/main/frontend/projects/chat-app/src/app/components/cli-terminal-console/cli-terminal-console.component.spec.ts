import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CliTerminalConsoleComponent, CliTerminalView } from './cli-terminal-console.component';

class MockSocket {
  readyState = 0;
  bufferedAmount = 0;
  onopen?: () => void;
  onclose?: () => void;
  onerror?: () => void;
  onmessage?: (event: {data: string}) => void;
  send = jasmine.createSpy('send');
  close = jasmine.createSpy('close');
}

describe('Inline CLI terminal', () => {
  let fixture: ComponentFixture<CliTerminalConsoleComponent>;
  let http: HttpTestingController;
  let sockets: MockSocket[];
  const session: CliTerminalView = { id: 'terminal-id', sessionId: 'normal-cli-id', workingDirectory: '/projects/one',
    pid: 123, state: 'RUNNING', exitCode: null, cols: 100, rows: 24 };
  beforeEach(async () => {
    sockets = [];
    const spy = spyOn(window, 'WebSocket').and.callFake(function() {
      const socket = new MockSocket(); sockets.push(socket); return socket as any;
    } as any);
    (spy as any).OPEN = 1;
    await TestBed.configureTestingModule({ imports: [HttpClientTestingModule, CliTerminalConsoleComponent] }).compileComponents();
    fixture = TestBed.createComponent(CliTerminalConsoleComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.componentInstance.workingDirectory = '/projects/one';
    fixture.detectChanges();
  });
  afterEach(() => { fixture.destroy(); http.verify(); });
  function available(): void {
    http.expectOne(r => r.url.endsWith('/agents/chat/terminal')).flush({ available: true });
    http.expectOne(r => r.url.endsWith('/terminal/sessions')).flush([]);
    fixture.detectChanges();
  }
  function launch(): MockSocket {
    available(); fixture.componentInstance.launch();
    const request = http.expectOne(r => r.method === 'POST' && r.url.endsWith('/sessions'));
    expect(request.request.body).toEqual({ workingDirectory: '/projects/one', cols: 100, rows: 24 });
    expect(request.request.headers.get('X-Kompile-Terminal')).toBe('1');
    request.flush(session);
    const socket = sockets[0]; socket.readyState = 1; socket.onopen!();
    return socket;
  }
  it('does not start a child just by opening the drawer', () => {
    available(); expect(sockets.length).toBe(0);
    http.expectNone(r => r.method === 'POST');
    expect(fixture.componentInstance.available).toBeTrue();
  });
  it('explains why hosted/remote terminal access is unavailable', () => {
    http.expectOne(r => r.url.endsWith('/agents/chat/terminal')).flush({ available: false, reason: 'Loopback only' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Loopback only');
    fixture.componentInstance.launch(); http.expectNone(r => r.method === 'POST');
  });
  it('sends raw interactive input and Ctrl-C without terminating the process', fakeAsync(() => {
    const socket = launch();
    fixture.componentInstance.input('hello\r'); fixture.componentInstance.interrupt();
    const messages = socket.send.calls.allArgs().map(args => JSON.parse(args[0]));
    expect(messages).toContain({ type: 'input', data: 'hello\r' });
    expect(messages).toContain({ type: 'input', data: '\x03' });
    http.expectNone(r => r.url.endsWith('/stop'));
    fixture.destroy(); tick(200);
  }));
  it('preserves Unicode when splitting large pasted input into frames', fakeAsync(() => {
    const socket = launch();
    const data = 'x'.repeat(8191) + '😀' + 'tail';
    fixture.componentInstance.input(data);
    const chunks = socket.send.calls.allArgs().map(args => JSON.parse(args[0]).data);
    expect(chunks.length).toBe(2);
    expect(chunks.join('')).toBe(data);
    expect(chunks[0].length).toBe(8191);
    expect(chunks[1]).toBe('😀tail');
    fixture.destroy(); tick(200);
  }));
  it('reconnects to an existing child, tracks exit status and detaches on component destruction', fakeAsync(() => {
    const socket = launch();
    socket.onmessage!({ data: JSON.stringify({ type: 'status', terminal: { ...session, state: 'EXITED', exitCode: 7 } }) });
    expect(fixture.componentInstance.current?.exitCode).toBe(7);
    fixture.componentInstance.connect(session.id);
    expect(socket.close).toHaveBeenCalled();
    expect(sockets.length).toBe(2);
    http.expectNone(r => r.method === 'POST');
    fixture.destroy(); tick(200);
    expect(sockets[1].close).toHaveBeenCalled();
    http.expectNone(r => r.method === 'DELETE' || r.url.endsWith('/stop'));
  }));
  it('forwards CLI mouse-wheel reports and transcript paging keys instead of consuming them as scrollback', async () => {
    const socket = launch();
    const terminal = (fixture.componentInstance as any).terminal;
    await new Promise<void>(resolve => terminal.write('\x1b[?1002h\x1b[?1006h', resolve));
    const screen: HTMLElement = fixture.nativeElement.querySelector('.xterm-screen');
    const rect = screen.getBoundingClientRect();
    screen.dispatchEvent(new WheelEvent('wheel', { deltaY: -120, clientX: rect.left + 20,
      clientY: rect.top + 20, bubbles: true, cancelable: true }));
    const textarea: HTMLElement = fixture.nativeElement.querySelector('.xterm-helper-textarea');
    textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageUp', code: 'PageUp', keyCode: 33,
      shiftKey: true, bubbles: true, cancelable: true }));
    textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', code: 'End', keyCode: 35,
      ctrlKey: true, bubbles: true, cancelable: true }));
    const messages = socket.send.calls.allArgs().map(args => JSON.parse(args[0]));
    expect(messages.some(m => m.type === 'input' && /^\x1b\[<64;[0-9]+;[0-9]+M$/.test(m.data))).toBeTrue();
    expect(messages).toContain({ type: 'input', data: '\x1b[5~' });
    expect(messages).toContain({ type: 'input', data: '\x1b[1;5F' });
    // Older terminal applications use legacy binary reports, not UTF-8 strings.
    await new Promise<void>(resolve => terminal.write('\x1b[?1006l', resolve));
    screen.dispatchEvent(new WheelEvent('wheel', { deltaY: 120, clientX: rect.left + 20,
      clientY: rect.top + 20, bubbles: true, cancelable: true }));
    const binary = socket.send.calls.allArgs().map(args => JSON.parse(args[0])).find(m => m.type === 'binary-input');
    expect(binary).toBeDefined();
    expect(atob(binary.data).startsWith('\x1b[M')).toBeTrue();
  });
  it('keeps native terminal scrollback when an application is not capturing the mouse', async () => {
    launch();
    const terminal = (fixture.componentInstance as any).terminal;
    await new Promise<void>(resolve => terminal.write(Array.from({length: 100}, (_, i) => `line ${i}\r\n`).join(''), resolve));
    // The write callback signals parsing, not rendering. xterm synchronizes its
    // DOM scroll area on animation frames; wait for that before sending a wheel.
    await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
    terminal.scrollToTop();
    await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
    expect(terminal.buffer.active.viewportY).toBe(0);
    const screen: HTMLElement = fixture.nativeElement.querySelector('.xterm-screen');
    const rect = screen.getBoundingClientRect();
    screen.dispatchEvent(new WheelEvent('wheel', {deltaY: 120, clientX: rect.left + 20,
      clientY: rect.top + 20, bubbles: true, cancelable: true}));
    await new Promise(resolve => setTimeout(resolve, 100));
    expect(terminal.buffer.active.viewportY).toBeGreaterThan(0);
  });
  it('lets readers scroll a saved transcript without reconnecting or snapping to incoming terminal output', fakeAsync(() => {
    const socket = launch();
    const component = fixture.componentInstance;
    const turns = [{role: 'user', content: '<script>not HTML</script>'},
      {role: 'assistant', content: 'old chat line\n'.repeat(200)}];
    component.toggleTranscript();
    http.expectOne(r => r.url.endsWith('/sessions/terminal-id/transcript')).flush({sessionId: session.sessionId, turns});
    fixture.detectChanges();
    const region: HTMLElement = fixture.nativeElement.querySelector('.cli-console-transcript');
    region.style.height = '100px'; region.style.flex = 'none';
    expect(region.scrollHeight).toBeGreaterThan(region.clientHeight);
    region.scrollTop = 120;
    const position = region.scrollTop;
    expect(position).toBeGreaterThan(0);
    expect(region.querySelector('script')).toBeNull();
    expect(region.textContent).toContain('<script>not HTML</script>');
    socket.onmessage!({data: JSON.stringify({type: 'output', data: 'new live output\r\n'})});
    component.loadTranscript();
    http.expectOne(r => r.url.endsWith('/sessions/terminal-id/transcript')).flush({sessionId: session.sessionId,
      turns: [...turns, {role: 'assistant', content: 'new completed turn'}]});
    fixture.detectChanges();
    expect(region.scrollTop).toBe(position);
    component.toggleTranscript(); fixture.detectChanges();
    component.toggleTranscript();
    http.expectOne(r => r.url.endsWith('/sessions/terminal-id/transcript')).flush({sessionId: session.sessionId, turns});
    fixture.detectChanges();
    expect(region.scrollTop).toBe(position);
    expect(sockets.length).toBe(1); expect(socket.close).not.toHaveBeenCalled();
    http.expectNone(r => r.method !== 'GET');
    fixture.destroy(); tick(200);
  }));
  it('ignores a stale transcript response after switching CLI sessions', fakeAsync(() => {
    launch();
    const component = fixture.componentInstance;
    component.toggleTranscript();
    const old = http.expectOne(r => r.url.endsWith('/sessions/terminal-id/transcript'));
    component.sessions.push({...session, id: 'other-terminal'});
    component.connect('other-terminal');
    old.flush({sessionId: session.sessionId, turns: [{role: 'user', content: 'wrong session'}]});
    expect(component.turns).toEqual([]); expect(component.showTranscript).toBeFalse();
    fixture.destroy(); tick(200);
  }));
  it('hides without disconnecting and uses explicit stop with CSRF header', fakeAsync(() => {
    const socket = launch();
    fixture.componentInstance.visible = false; fixture.detectChanges();
    expect(socket.close).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.cli-console').classList.contains('cli-console-hidden')).toBeTrue();
    fixture.componentInstance.stop();
    const stop = http.expectOne(r => r.url.endsWith('/sessions/terminal-id/stop'));
    expect(stop.request.headers.get('X-Kompile-Terminal')).toBe('1');
    stop.flush(null);
    http.expectOne(r => r.url.endsWith('/terminal/sessions')).flush([{ ...session, state: 'STOPPED' }]);
    expect(fixture.componentInstance.current?.state).toBe('STOPPED');
    fixture.destroy(); tick(200);
  }));
});
