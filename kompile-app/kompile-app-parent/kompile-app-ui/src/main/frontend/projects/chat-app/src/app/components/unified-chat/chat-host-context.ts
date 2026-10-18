/** Host-owned identity for one mounted managed chat view. This is not an authorization policy. */
export interface ChatHostContext {
  readonly productId: string;
  readonly title: string;
  readonly workingDirectory: string;
  readonly chat: { readonly id: string; readonly name: string };
  readonly agentName: string;
}

interface ChatHostEventIdentity {
  readonly productId: string;
  readonly sessionId: string;
  readonly agentName: string;
}

/** Structured observations only; text, tool arguments and tool output never become host state. */
export type ChatHostEventDetail = (
  | { readonly type: 'session'; readonly status: 'ready' }
  // Server run discovery only: no arguments, results or model text cross this boundary.
  | { readonly type: 'readiness'; readonly status: 'busy' | 'idle' }
  // Attached describes the view, not a claim that a queued run has started executing.
  | { readonly type: 'run'; readonly runId: string; readonly status: 'attached' | 'completed' | 'cancelled' }
  // A transport failure is not proof that server work failed or stopped.
  | { readonly type: 'stream'; readonly runId: string; readonly status: 'error' }
  | { readonly type: 'tool'; readonly runId: string; readonly callId: string;
      readonly toolName: string; readonly status: 'completed'; readonly ok: boolean }
);

export type ChatHostEvent = ChatHostEventIdentity & ChatHostEventDetail;

/** Copy nested identity: later mutations of the caller's object cannot retarget a live view. */
export function snapshotChatHostContext(context: ChatHostContext): ChatHostContext {
  const values = [context?.productId, context?.title, context?.workingDirectory,
    context?.chat?.id, context?.chat?.name, context?.agentName];
  if (values.some(value => typeof value !== 'string' || !value.trim())) {
    throw new Error('ChatHostContext requires a product, title, directory, chat identity and agent');
  }
  return Object.freeze({ productId: context.productId, title: context.title,
    workingDirectory: context.workingDirectory, chat: Object.freeze({ id: context.chat.id, name: context.chat.name }),
    agentName: context.agentName });
}
