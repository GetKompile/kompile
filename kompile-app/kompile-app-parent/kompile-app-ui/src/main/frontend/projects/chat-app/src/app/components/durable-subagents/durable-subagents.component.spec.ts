import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DurableSubagentsComponent, durableSubagentsMenu, DurableSubagentsMenu } from './durable-subagents.component';

describe('DurableSubagentsComponent', () => {
  let fixture: ComponentFixture<DurableSubagentsComponent>;
  let component: DurableSubagentsComponent;
  const menu = (): DurableSubagentsMenu => ({ menu: 'durable-subagents', action: 'list', children: [
    { sessionId: 'subagent-saved', name: 'Saved child', agentType: 'general', model: 'model', locked: false },
    { sessionId: 'subagent-busy', name: 'Busy child', locked: true }
  ] });
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DurableSubagentsComponent] }).compileComponents();
    fixture = TestBed.createComponent(DurableSubagentsComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('menu', menu());
    fixture.detectChanges();
  });
  function finish(action: 'list' | 'show' | 'resume', ok = true, text = '', history?: { role: string; content: string }[]): void {
    fixture.componentRef.setInput('outcome', { ok, text, data: { menu: 'durable-subagents', action,
      sessionId: action === 'list' ? undefined : component.selectedId, history } });
    fixture.detectChanges();
  }
  it('refreshes and opens exact durable child ids without sending a model prompt', () => {
    const dispatch = spyOn(component.dispatch, 'emit');
    component.refresh();
    expect(dispatch).toHaveBeenCalledOnceWith('/subagents saved');
    finish('list');
    component.open(component.children[0]);
    expect(dispatch).toHaveBeenCalledWith('/subagents show subagent-saved');
    expect(component.pending).toBe('show');
  });
  it('resumes the selected child once with an allowlisted structured follow-up', () => {
    component.open(component.children[0]); finish('show');
    component.prompt = '  Continue this child only "quoted"\nnext  ';
    const dispatch = spyOn(component.dispatch, 'emit');
    component.resume(); component.resume();
    expect(dispatch).toHaveBeenCalledTimes(1);
    const command = dispatch.calls.mostRecent().args[0];
    if (command === undefined) throw new Error('Expected a durable resume command');
    expect(JSON.parse(command.slice('/subagents resume '.length))).toEqual({ session_id: 'subagent-saved', prompt: 'Continue this child only "quoted"\nnext' });
    finish('resume', true, 'child answer');
    expect(component.prompt).toBe('');
    expect(component.status).toBe('child answer');
  });
  it('refuses busy, locked, empty and oversized continuations', () => {
    component.open(component.children[1]); finish('show');
    component.prompt = 'continue'; expect(component.canResume()).toBeFalse();
    component.open(component.children[0]); finish('show');
    component.prompt = ''; expect(component.canResume()).toBeFalse();
    component.prompt = 'x'.repeat(8001); expect(component.canResume()).toBeFalse();
    component.prompt = 'continue'; component.disabled = true;
    const dispatch = spyOn(component.dispatch, 'emit');
    component.resume(); component.refresh(); expect(dispatch).not.toHaveBeenCalled();
  });
  it('keeps failed drafts and never retries or fabricates successful continuation', () => {
    component.open(component.children[0]); finish('show'); component.prompt = 'continue';
    const dispatch = spyOn(component.dispatch, 'emit'); component.resume();
    finish('resume', false, 'Resume permission denied');
    expect(component.prompt).toBe('continue'); expect(component.error).toBe('Resume permission denied');
    expect(component.status).toBe(''); expect(dispatch).toHaveBeenCalledTimes(1);
  });
  it('renders saved history as bounded plain text, never executable markup', () => {
    component.open(component.children[0]);
    const parsed = durableSubagentsMenu({ menu: 'durable-subagents', action: 'show', sessionId: 'subagent-saved',
      history: [{ role: 'assistant', content: '<img src=x onerror=alert(1)> & child text' }] });
    fixture.componentRef.setInput('outcome', { ok: true, text: '', data: parsed }); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.child-turn pre').textContent).toContain('<img src=x onerror=alert(1)>');
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    const bounded = durableSubagentsMenu({ menu: 'durable-subagents', action: 'show',
      history: Array.from({ length: 50 }, () => ({ role: 'assistant', content: 'x'.repeat(10000) })) });
    expect(bounded!.history!.length).toBe(30);
    expect(bounded!.history!.reduce((sum, row) => sum + row.content.length, 0)).toBe(65536);
  });
  it('rejects main-chat and traversal ids in snapshots', () => {
    const parsed = durableSubagentsMenu({ menu: 'durable-subagents', action: 'list', children: [
      { sessionId: 'main-chat', locked: false }, { sessionId: 'subagent-../../outside', locked: false },
      { sessionId: 'subagent-safe', locked: false, apiKey: 'never-preserve' }
    ] });
    expect(parsed!.children!.map(row => row.sessionId)).toEqual(['subagent-safe']);
    expect(JSON.stringify(parsed)).not.toContain('never-preserve');
    expect(durableSubagentsMenu({ menu: 'other' })).toBeNull();
  });
});
