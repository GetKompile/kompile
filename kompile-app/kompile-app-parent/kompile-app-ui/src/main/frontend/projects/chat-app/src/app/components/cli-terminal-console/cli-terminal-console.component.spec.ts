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
