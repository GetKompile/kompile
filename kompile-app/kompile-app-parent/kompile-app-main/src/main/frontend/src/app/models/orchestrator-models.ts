/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// ==================== State Machine Models ====================

export interface StateDefinition {
  stateId: string;
  orchestratorInstanceId?: string;
  name: string;
  description?: string;
  category: StateCategory;
  allowedNextStates?: string[];
  handlerClassName?: string;
  timeoutSeconds?: number;
  autoAdvance?: boolean;
  polling?: boolean;
  pollingIntervalMs?: number;
  llmTriggerConfig?: LlmTriggerConfig;
  builtin?: boolean;
  displayOrder?: number;
  positionX?: number;
  positionY?: number;
  onEnterTaskId?: string;
  onExitTaskId?: string;
  position?: { x: number; y: number };
}

export type StateCategory = 'INITIAL' | 'PROCESSING' | 'WAITING' | 'TERMINAL' | 'ERROR';

export interface StateTransition {
  id?: number;
  orchestratorInstanceId: string;
  fromStateId: string;
  toStateId: string;
  name?: string;
  description?: string;
  conditionType: TransitionConditionType;
  conditionExpression?: string;
  autoTrigger?: boolean;
  priority?: number;
  onTransitionTaskId?: string;
  enabled?: boolean;
  label?: string;
}

export type TransitionConditionType =
  | 'ALWAYS'
  | 'ON_SUCCESS'
  | 'ON_FAILURE'
  | 'PATTERN_MATCH'
  | 'CLASSIFICATION'
  | 'EXPRESSION'
  | 'MANUAL';

export interface StateMachineConfig {
  instanceId: string;
  states: StateDefinition[];
  transitions: StateTransition[];
  stateCount: number;
  transitionCount: number;
}

export interface StatePositionUpdate {
  stateId: string;
  x: number;
  y: number;
}

// ==================== Output Classification Models ====================

export interface OutputClassifier {
  id?: number;
  orchestratorInstanceId: string;
  name: string;
  description?: string;
  enabled?: boolean;
  applyAllMatches?: boolean;
  defaultAction?: ClassificationAction;
  tags?: string;
  rules?: ClassificationRule[];
}

export interface ClassificationRule {
  id?: number;
  classifierId?: number;
  name: string;
  description?: string;
  pattern: string;
  caseSensitive?: boolean;
  multiline?: boolean;
  classificationType: ClassificationType;
  severity: ClassificationSeverity;
  action: ClassificationAction;
  actionConfig?: string;
  targetStateId?: string;
  handlerTaskId?: string;
  llmPromptTemplate?: string;
  maxRetries?: number;
  retryDelaySeconds?: number;
  ruleOrder?: number;
  enabled?: boolean;
  stopOnMatch?: boolean;
  tags?: string;
}

export type ClassificationType =
  | 'COMPILATION_ERROR'
  | 'LINKER_ERROR'
  | 'RUNTIME_ERROR'
  | 'MEMORY_ERROR'
  | 'SEGFAULT'
  | 'TIMEOUT'
  | 'BUILD_SUCCESS'
  | 'TEST_SUCCESS'
  | 'TEST_FAILURE'
  | 'PERMISSION_ERROR'
  | 'NETWORK_ERROR'
  | 'DEPENDENCY_ERROR'
  | 'CONFIGURATION_ERROR'
  | 'RESOURCE_ERROR'
  | 'WARNING'
  | 'INFO'
  | 'DEBUG'
  | 'CUSTOM';

export type ClassificationSeverity = 'CRITICAL' | 'ERROR' | 'WARNING' | 'INFO';

export type ClassificationAction =
  | 'RETRY'
  | 'RETRY_WITH_BACKOFF'
  | 'INVOKE_LLM'
  | 'INVOKE_LLM_FOR_FIX'
  | 'TRANSITION_STATE'
  | 'EXECUTE_TASK'
  | 'NOTIFY'
  | 'LOG'
  | 'SKIP'
  | 'ABORT'
  | 'AWAIT_APPROVAL'
  | 'ESCALATE'
  | 'CUSTOM'
  | 'CONTINUE';

export interface ClassificationResult {
  output: string;
  matches: RuleMatch[];
  mostSevere?: RuleMatch;
  hasErrors?: boolean;
  hasWarnings?: boolean;
  summary?: string;
}

export interface RuleMatch {
  ruleId: number;
  ruleName: string;
  classificationType: ClassificationType;
  severity: ClassificationSeverity;
  action: ClassificationAction;
  matchedText: string;
  matchStart: number;
  matchEnd: number;
  capturedGroups: string[];
}

export interface PatternTestResult {
  pattern: string;
  input: string;
  valid: boolean;
  error?: string;
  errorIndex?: number;
  matchCount: number;
  matches: PatternMatch[];
}

export interface PatternMatch {
  text: string;
  start: number;
  end: number;
  groups: string[];
}

// ==================== Audit Log Models ====================

export interface AuditLogEntry {
  id: number;
  orchestratorInstanceId: string;
  eventType: AuditEventType;
  entityType?: AuditEntityType;
  entityId?: string;
  action: string;
  message?: string;
  detailsJson?: string;
  previousValue?: string;
  newValue?: string;
  source?: string;
  actorId?: string;
  actorType?: string;
  triggerId?: string;
  hookId?: string;
  error?: boolean;
  errorMessage?: string;
  durationMs?: number;
  timestamp: string;
}

export type AuditEventType =
  | 'STATE_CHANGE'
  | 'TASK_LIFECYCLE'
  | 'LLM_INTERACTION'
  | 'WORKFLOW_LIFECYCLE'
  | 'TRIGGER_FIRED'
  | 'HOOK_EXECUTED'
  | 'ERROR'
  | 'RECOVERY'
  | 'CONFIGURATION_CHANGE'
  | 'ORCHESTRATOR_LIFECYCLE';

export type AuditEntityType =
  | 'ORCHESTRATOR'
  | 'STATE'
  | 'TASK'
  | 'WORKFLOW'
  | 'WORKFLOW_STEP'
  | 'LLM_SESSION'
  | 'TRIGGER'
  | 'HOOK'
  | 'CONFIGURATION';

export interface AuditSearchCriteria {
  eventType?: AuditEventType;
  entityType?: AuditEntityType;
  fromTime?: string;
  toTime?: string;
  search?: string;
  actorId?: string;
  errorsOnly?: boolean;
}

export interface AuditStats {
  totalEvents: number;
  errorCount: number;
  avgDurationMs: number;
  eventsByType: Record<string, number>;
  eventsByEntityType: Record<string, number>;
  eventsByHour: HourlyCount[];
}

export interface HourlyCount {
  hour: number;
  count: number;
}

export interface PagedResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

// ==================== Enhanced Task Definition Models ====================

export interface EnhancedTaskDefinition {
  taskId: string;
  name: string;
  description?: string;
  taskType: TaskType;
  command?: string;
  workingDirectory?: string;
  timeoutSeconds?: number;
  promptTemplate?: string;
  autoInvokeLlmOnError?: boolean;
  llmErrorPromptTemplate?: string;

  // Agent/Chat Integration
  agentName?: string;
  enableRag?: boolean;
  ragFolderId?: string;
  ragMaxResults?: number;
  ragSimilarityThreshold?: number;
  ragIncludeKeywordSearch?: boolean;
  ragIncludeSemanticSearch?: boolean;
  enableTools?: boolean;
  allowedTools?: string;
  skipPermissions?: boolean;
  systemPrompt?: string;
  outputClassifierId?: number;

  // Variables
  defaultVariables?: Record<string, string>;
  requiredVariables?: string[];

  // Patterns - Pass/Fail Triggers
  successPattern?: string;
  failurePattern?: string;

  // Actions on Success/Failure (Pass/Fail Triggers)
  onSuccessAction?: TaskAction;
  onSuccessStateId?: string;
  onSuccessTaskId?: string;
  onSuccessLlmPrompt?: string;

  onFailureAction?: TaskAction;
  onFailureStateId?: string;
  onFailureTaskId?: string;
  onFailureLlmPrompt?: string;

  // HTTP Task
  httpUrl?: string;
  httpMethod?: string;
  httpHeadersJson?: string;
  httpBodyTemplate?: string;

  // Custom Executor
  executorClassName?: string;

  // Retry
  retryCount?: number;
  retryDelaySeconds?: number;

  // Metadata
  metadataJson?: string;
  metadata?: Record<string, any>;
}

// Task Action Types - What to do on pass/fail
export type TaskAction =
  | 'CONTINUE'           // Continue to next step
  | 'RETRY'              // Retry the task
  | 'RETRY_WITH_BACKOFF' // Retry with exponential backoff
  | 'INVOKE_LLM'         // Send output to LLM for analysis
  | 'INVOKE_LLM_FOR_FIX' // Send to LLM to generate fix
  | 'TRANSITION_STATE'   // Transition to specific state
  | 'EXECUTE_TASK'       // Execute another task
  | 'NOTIFY'             // Send notification
  | 'LOG'                // Log and continue
  | 'SKIP'               // Skip to next step
  | 'ABORT'              // Abort workflow
  | 'AWAIT_APPROVAL'     // Wait for manual approval
  | 'ESCALATE'           // Escalate to human
  | 'PARSE_LOG'          // Parse log output using classifier
  | 'WAIT_FOR_PROCESS'   // Wait for process completion
  | 'SEND_TO_AGENT'      // Send to agent for processing
  | 'CUSTOM';            // Custom action handler

export type TaskType = 'SHELL' | 'HTTP' | 'CODE' | 'LLM_QUERY' | 'CUSTOM';

// ==================== LLM Trigger Models ====================

export interface LlmTriggerConfig {
  triggerId?: string;
  name: string;
  description?: string;
  triggerType: LlmTriggerType;
  enabled?: boolean;
  stateConditions?: string[];
  promptTemplate?: string;
  systemPrompt?: string;
  llmProviderId?: string;
  autoExecute?: boolean;
  cooldownSeconds?: number;
  maxRetries?: number;
  triggerOnEnter?: boolean;
  triggerOnExit?: boolean;
  triggerOnError?: boolean;
}

export type LlmTriggerType =
  | 'ON_STATE_ENTER'
  | 'ON_STATE_EXIT'
  | 'ON_TASK_COMPLETE'
  | 'ON_TASK_FAILURE'
  | 'ON_ERROR'
  | 'PERIODIC'
  | 'MANUAL';

// ==================== Agent Models ====================

export interface AgentInfo {
  name: string;
  displayName: string;
  description?: string;
  available: boolean;
  supportsRag?: boolean;
  supportsTools?: boolean;
  supportsMcp?: boolean;
}

// ==================== Visual Editor Models ====================

export interface NodePosition {
  x: number;
  y: number;
}

export interface EdgeConnection {
  source: string;
  target: string;
  label?: string;
}

export interface StateMachineLayout {
  nodes: Array<{
    id: string;
    position: NodePosition;
    data: StateDefinition;
  }>;
  edges: Array<{
    id: string;
    source: string;
    target: string;
    data: StateTransition;
  }>;
}

// ==================== Export Types ====================

export type ExportFormat = 'json' | 'csv';

export interface ExportOptions {
  format: ExportFormat;
  includeHeaders?: boolean;
  dateFormat?: string;
}

// Type alias for backward compatibility
export type TaskDefinition = EnhancedTaskDefinition;
