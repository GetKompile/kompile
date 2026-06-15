/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { Subject, Observable } from 'rxjs';

/**
 * Event types for registry changes.
 */
export type RegistryChangeType =
  | 'archive_loaded'
  | 'archive_unloaded'
  | 'model_extracted'
  | 'model_loaded'
  | 'model_unloaded'
  | 'registry_refreshed'
  | 'staging_applied';

export interface RegistryChangeEvent {
  type: RegistryChangeType;
  modelId?: string;
  archiveId?: string;
  timestamp: Date;
}

/**
 * Service for broadcasting model registry change events.
 * Components that modify the registry should call notifyChange() after making changes.
 * Components that display registry data should subscribe to changes$ to refresh their data.
 */
@Injectable({
  providedIn: 'root'
})
export class ModelRegistryService {

  private changesSubject = new Subject<RegistryChangeEvent>();

  /**
   * Observable of registry change events.
   * Subscribe to this to be notified when the registry is modified.
   */
  public changes$: Observable<RegistryChangeEvent> = this.changesSubject.asObservable();

  constructor() {}

  /**
   * Notify subscribers that the registry has changed.
   * Call this after any operation that modifies the registry.
   */
  notifyChange(type: RegistryChangeType, modelId?: string, archiveId?: string): void {
    this.changesSubject.next({
      type,
      modelId,
      archiveId,
      timestamp: new Date()
    });
  }

  /**
   * Notify that an archive was loaded.
   */
  notifyArchiveLoaded(archiveId: string): void {
    this.notifyChange('archive_loaded', undefined, archiveId);
  }

  /**
   * Notify that an archive was unloaded.
   */
  notifyArchiveUnloaded(archiveId?: string): void {
    this.notifyChange('archive_unloaded', undefined, archiveId);
  }

  /**
   * Notify that a model was extracted from an archive.
   */
  notifyModelExtracted(modelId: string, archiveId?: string): void {
    this.notifyChange('model_extracted', modelId, archiveId);
  }

  /**
   * Notify that a model was loaded for inference.
   */
  notifyModelLoaded(modelId: string): void {
    this.notifyChange('model_loaded', modelId);
  }

  /**
   * Notify that a model was unloaded.
   */
  notifyModelUnloaded(modelId?: string): void {
    this.notifyChange('model_unloaded', modelId);
  }

  /**
   * Notify that staging configuration was applied (models installed to registry).
   */
  notifyStagingApplied(): void {
    this.notifyChange('staging_applied');
  }

  /**
   * Notify that the registry was refreshed.
   */
  notifyRegistryRefreshed(): void {
    this.notifyChange('registry_refreshed');
  }
}
