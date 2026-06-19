/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */

/** Empirical (Beta-Binomial) statistics for one observed event. */
export interface ObservedEventStat {
  eventKey: string;
  eventType: string;
  probability: number;
  occurrences: number;
  opportunities: number;
  evidenceStrength: number;
  credibleLow: number;
  credibleHigh: number;
  lastObservedAt: string | null;
}

/** A row in the observed-events table. */
export interface ObservedEventView {
  eventKey: string;
  eventType: string;
  subjectNodeId?: string;
  sourceNodeId?: string;
  edgeType?: string;
  targetNodeId?: string;
  factSheetId?: number;
  probability: number;
  occurrenceCount: number;
  opportunityCount: number;
  evidenceStrength: number;
  lastObservedAt?: string;
}

export interface NodePriorResponse {
  nodeId: string;
  hasPrior: boolean;
  prior: number | null;
  stat: ObservedEventStat | null;
}

export interface ConnectionPriorResponse {
  source: string;
  edgeType: string;
  target: string;
  hasPrior: boolean;
  prior: number | null;
  stat: ObservedEventStat | null;
}

/** One point on an event's prior time-series. */
export interface ObservationHistoryPoint {
  observedAt: string;
  probability: number;
  occurrences: number;
  opportunities: number;
  source: string;
}

export interface EventObservationConfig {
  enabled?: boolean;
  opportunityModel?: string;
  halfLifeDays?: number;
  priorAlpha?: number;
  priorBeta?: number;
  priorBlendK?: number;
  minEvidenceForPrior?: number;
  storageBackends?: string[];
  decayOnEachCrawl?: boolean;
  entityEventsEnabled?: boolean;
  connectionEventsEnabled?: boolean;
  processStepEventsEnabled?: boolean;
  fineGrainedMutationsEnabled?: boolean;
}

export interface ScanResult {
  entitiesObserved: number;
  connectionsObserved: number;
  ran: boolean;
  total?: number;
}

export const OPPORTUNITY_MODELS = ['PRESENCE', 'RELATIVE_FREQUENCY', 'DECAYED_RATE'];
export const EVENT_TYPES = ['ENTITY_OCCURRENCE', 'CONNECTION_OCCURRENCE', 'PROCESS_STEP_OCCURRENCE', 'USER_DEFINED'];
