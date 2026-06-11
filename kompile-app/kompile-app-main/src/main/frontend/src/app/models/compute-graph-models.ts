export interface ComputeGraphConfig {
  enabled: boolean;
  scriptingEnabled: boolean;
  droolsEnabled: boolean;
  droolsInferenceEnabled: boolean;
  defaultMaxCpuTimeMs: number;
  defaultMaxHeapMemoryBytes: number;
  defaultMaxStackFrames: number;
  defaultAllowIO: boolean;
  defaultAllowNetwork: boolean;
  defaultAllowHostAccess: boolean;
  maxRuleFiringsPerNode: number;
  maxRuleFiringsTotal: number;
}

export interface ComputeGraphStatus {
  enabled: boolean;
  scriptingEnabled: boolean;
  droolsEnabled: boolean;
  droolsInferenceEnabled: boolean;
}

export type NodeExecutionType = 'JAVASCRIPT' | 'PYTHON' | 'DROOLS_RULE' | 'DROOLS_INFERENCE' | 'EXPRESSION' | 'PASSTHROUGH';

export type ExecutionStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'TIMED_OUT' | 'CANCELLED';

export interface ExecutionLimits {
  maxCpuTime?: string;
  maxHeapMemoryBytes?: number;
  maxStackFrames?: number;
  allowIO?: boolean;
  allowNetwork?: boolean;
  allowHostAccess?: boolean;
}

export interface ComputeNode {
  id: string;
  name: string;
  executionType: NodeExecutionType;
  script?: string;
  parameters?: { [key: string]: any };
  inputBindings?: { [key: string]: string };
  outputBindings?: { [key: string]: string };
  limits?: ExecutionLimits;
  metadata?: { [key: string]: any };
}

export interface ComputeEdge {
  sourceNodeId: string;
  targetNodeId: string;
  condition?: string;
  dataMapping?: { [key: string]: string };
  priority?: number;
  label?: string;
}

export interface ComputeGraph {
  id: string;
  name?: string;
  description?: string;
  nodes: ComputeNode[];
  edges: ComputeEdge[];
  globalParameters?: { [key: string]: any };
}

export interface ExecutionResult {
  nodeId: string;
  executionId: string;
  status: ExecutionStatus;
  outputs?: { [key: string]: any };
  error?: string;
  stackTrace?: string;
  consoleOutput?: string;
  startedAt?: string;
  completedAt?: string;
  duration?: string;
}

export interface GraphExecutionResult {
  executionId: string;
  graphId: string;
  status: ExecutionStatus;
  nodeResults: { [nodeId: string]: ExecutionResult };
  finalOutputs: { [key: string]: any };
  artifacts: ComputeArtifact[];
  executionOrder: string[];
  skippedNodes: string[];
  totalDuration?: string;
}

export interface ComputeArtifact {
  id: string;
  executionId: string;
  nodeId: string;
  name: string;
  contentType?: string;
  data?: { [key: string]: any };
  sizeBytes?: number;
  metadata?: { [key: string]: any };
}

export interface ValidationResult {
  valid: boolean;
  errors?: string;
}
