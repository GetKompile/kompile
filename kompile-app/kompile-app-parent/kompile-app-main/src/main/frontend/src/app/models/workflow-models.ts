export interface WorkflowListItem {
  id: string;
  name: string;
  contentType?: string;
  sizeBytes?: number;
  description?: string;
  tags?: string[];
  updatedAt?: string;
}

export interface WorkflowListResponse {
  workflows: WorkflowListItem[];
  count: number;
  status: string;
  error?: string;
}

export interface WorkflowDetail {
  engineType: string;
  name: string;
  content: string;
  metadata: { [key: string]: any };
}

export interface WorkflowSaveResponse {
  id: string;
  name: string;
  storagePath: string;
  sizeBytes: number;
  status: string;
  error?: string;
}

export interface WorkflowInspectionNode {
  id?: string;
  name: string;
  type?: string;
  componentType?: string;
  sourcePath?: string;
  disabled?: boolean;
  isTrigger?: boolean;
  ports?: WorkflowPort[];
  credentialTypes?: string[];
}

export interface WorkflowPort {
  name: string;
  label: string;
  dataType: string;
  direction: 'input' | 'output';
}

export interface WorkflowInspectionLink {
  id?: string;
  type: string;
  source: string;
  target: string;
  sourcePort?: string;
  targetPort?: string;
  sourceOutput?: number;
  targetInput?: number;
  connectionType?: string;
}

export interface WorkflowArgument {
  nodeId?: string;
  fullName: string;
  type?: string;
  name?: string;
}

export interface WorkflowInspection {
  engineType: string;
  id?: string;
  name?: string;
  active?: boolean;
  nodes: WorkflowInspectionNode[];
  links?: WorkflowInspectionLink[];
  edges?: WorkflowInspectionLink[];
  arguments?: WorkflowArgument[];
  triggerNode?: string;
  requiredCredentialTypes?: string[];
  nodeCount: number;
  linkCount?: number;
  edgeCount?: number;
  status: string;
  error?: string;
}

export interface WorkflowExecutionResult {
  executionId: string;
  executionStatus: string;
  executionOrder: string[];
  outputs?: { [key: string]: any };
  error?: string;
  nodeResults?: { [nodeId: string]: WorkflowNodeResult };
  status: string;
}

export interface WorkflowNodeResult {
  status: string;
  duration?: string;
  consoleOutput?: string;
  error?: string;
  outputs?: { [key: string]: any };
}

export type WorkflowEngineType = 'xircuits' | 'n8n';
