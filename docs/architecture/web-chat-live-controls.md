# Web chat live harness controls

The chat web adapter launches `kompile chat --input-format web-json --output-format stream-json --web-controls -`.
Unlike plain exec, this opt-in mode consumes one initial web-json line and leaves stdin open for control JSONL.
Both the web application and CLI must be rebuilt together. Older CLIs reject the new option explicitly.

## Single chat and multi-project workspace

- `kompile chat --web` preserves the single-project chat launch and directory boundary.
- `kompile chat --web --workspace` launches the same CHAT JAR persona at `/#/workspace`. Add `--open-browser` to open it automatically. Rebuild the CLI, common library, CHAT backend and chat frontend together; the installed binaries are not updated by a source edit.
- The launch directory is registered automatically. Add other **absolute directories on the server host** using the project sidebar, create chats within each project, and select chat tabs. The non-secret project/chat index persists at `~/.kompile/chat-workspace.json` using cross-process locking and atomic replacement. Canonical roots must be registered explicitly; registering a parent does not authorize every subdirectory.
- Each open pane has its own CLI transport, provider/persona state, scoped browser history and live controls. Switching tabs hides rather than destroys panes, so multiple projects (or multiple chats in one project) can run together. Keyboard shortcuts and the header settings gear target only the selected pane. Close is disabled while that pane is busy; use its Stop control first. Closing a pane keeps its registered chat.
- Workspace mode defaults to four concurrent CLI workers; single-chat mode retains one. `kompile.web.chat.harness.maxConcurrent` overrides the worker count, capped at eight, with two queued turns. The UI allows eight open panes. Resource/permission checks inside each CLI remain in force; parallel provider chats do not imply simultaneous local GPU capacity.
- Project selection changes the CLI child's working directory, not the Spring server's shared folder/model/RAG services. Provider configuration follows the existing CLI project/global-config launch policy. CLI session identity includes the canonical project root and browser chat id, so one project cannot resume another project's chat accidentally.
- Open panes/reconnect checkpoints are browser-tab scoped; rendered history is browser-origin scoped. The project/chat index and CLI transcripts survive a new launch, but a new server port does not copy another origin's rendered browser history. Reload exposes the existing reconnect/stop controls; it does not automatically resubmit input or resurrect work after server shutdown. Both `/chat` and `/workspace` routes remain available in a workspace launch; ordinary single-mode launches cannot add projects through the workspace API.

## Browser controls

- **Ctrl+B / Background button** transfers an eligible blocking subagent invocation to the CLI's retained background-task lifecycle. Model thinking is not backgroundable.
- **Child conversations** expose retained output and per-child follow-up/stop buttons during a live run. Enter in the child composer sends only to that child; Delete on its focused summary requests cancellation of only that child. Capability flags disable unsupported actions, and input focus survives snapshots.
- **Activity** shows tasks, command processes, states and bounded output. Process output/stop requests target only a process owned by the active run, through the CLI permission boundary.
- **Enter while running** queues plain-text follow-up input for the next turn boundary. Accepted follow-ups remain visible in the activity panel. Every dispatched input and assistant response renders as its own browser message. Completion-triggered inputs are system notices, not user text. The final run event does not duplicate the last answer.
- **Escape / Stop** ends the entire live run and its owned work. Ctrl+C remains browser copy, and composition/repeated keys/dialog shortcuts are not intercepted.
- Slash commands must be sent after the live run finishes, through the existing command resolver; they are never passed to the model via the input control.

## Protocol

`POST /api/agents/chat/control/{runId}` accepts:

```json
{"version":1,"requestId":"web-1","action":"background"}
```

Actions: `background`, `process_list`, `process_output`, `process_kill`, `input`, `subagent_input`, `subagent_cancel`.
`process_output`/`process_kill`/`subagent_cancel` require `targetId`; `input` requires `text`; `subagent_input` requires both. Child controls accept only ids observed from the owning run's subagent runner.
Unknown fields/actions and oversized frames are rejected. The CLI limits frames to 64 KiB, queued inputs to 64 and request ids to 4096 per run.

HTTP 202 `{accepted:true}` means **written**, not **executed**. Only the correlated `control` SSE event acknowledges the operation's outcome. A lost acknowledgement is an unknown outcome, not grounds for automatic retry.

CLI nonterminal events carry a `data` object, unwrapped by the web adapter:

- `control`: requestId, action, ok, message, optional targetId/output.
- `activity`: backgroundable, turnActive, processes, tasks, subagents (id, type, description, state, output, running, canSend, canCancel).
- `turn_started`: turnId, source (`initial`, `user`, `system`), text. The initial decorated harness prompt is deliberately not exposed.
- `turn_complete`: turnId, text; does not close SSE or release background resources.

The dispatch owner serializes model turns. Background task/process completions trigger follow-up turns before final `result`. Process output snapshots are throttled. The run closes when inputs and background work are drained, or on cancellation or timeout. Socket disconnection only detaches the subscriber. Session switching is blocked during a live run to prevent misdirected input or controls.

## Bounded reconnect

The web adapter emits monotonic SSE `id` values, including an initial `queued` event that identifies the run. `GET /api/agents/chat/events/{runId}?after=N` replays events after the cursor and then attaches live, atomically with publication. A replacement subscriber cannot be detached by an old socket callback; the old view receives `superseded` and stops automatic reconnection rather than fighting for ownership. This endpoint never launches a process or submits a prompt.

The browser deduplicates event ids and retries a broken stream up to three times. A same-tab `sessionStorage` checkpoint stores rendered message state, turn identity, cursor and run id (not agent credentials); reloading exposes **Reconnect run** and **Stop saved run**. Reload replay starts at that checkpoint's cursor and replaces only that run's message span. Checkpoints are updated at turn boundaries and at most every 250 ms during streaming. Leaving the view disconnects, while Escape/Stop explicitly cancels. Input is not automatically resent.

Retention is limited to 1 MiB / 2000 events per run, 16 completed runs, and two minutes after completion. Active work remains subject to its original timeout (maximum 30 minutes); reconnect does not extend it. A cursor outside the retained window receives an explicit error, never a partial replay presented as complete. Browser checkpoints over 1 MiB or disabled browser storage cannot advance reload bookmarks; in-page reconnect remains available. If a saved checkpoint is older than the server's retained window, replay fails explicitly. Server restart cannot resume work.

## Qualification and remaining boundaries

Deterministic tests cover real worker transfer, eligibility races, process ownership/fast exit/kill, protocol framing, browser key binding, SSE acknowledgement and command regressions. Provider-backed end-to-end qualification still needs a rebuilt CLI + CHAT application.

This is not durable daemon recovery: work does not survive timeout or application shutdown. Child controls apply only while the owning live run exists; replay cannot reopen a completed run. Other CLI terminal key bindings and fully interactive live slash-command/permission flows remain parity work. Async child follow-up completion is published before releasing child ownership, then wakes the parent without duplicating synchronous TaskTool results. Plain exec keeps its original one-shot EOF behavior.
