/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

/** The Kompile-managed pointer to an external project store. */
export interface ProjectStoreConfig {
  url?: string;
  gitXet?: boolean;
  configured?: boolean;
}

/** A project advertised by the external store (mirrors the server's ProjectDto). */
export interface RemoteProject {
  id?: string;
  namespace: string;
  slug: string;
  fullName: string;
  repoType?: string;
  visibility?: string;
  defaultBranch?: string;
  description?: string;
  cloneUrl?: string;
  cliCloneCommand?: string;
  manifest?: string;
}

/** Result of cloning a store project into the local workspace. */
export interface CloneResult {
  cloned: boolean;
  fullName: string;
  path: string;
  cloneUrl: string;
}

/**
 * Client for the meta multi-project store integration (server endpoints under
 * {@code /api/project-store}). Reads/writes the Kompile-managed store pointer, lists the
 * store's projects, and clones one into the local workspace.
 */
@Injectable({ providedIn: 'root' })
export class ProjectStoreService {
  private readonly base = `${backendUrl}/api/project-store`;

  constructor(private http: HttpClient) {}

  getConfig(): Observable<ProjectStoreConfig> {
    return this.http.get<ProjectStoreConfig>(`${this.base}/config`);
  }

  setConfig(config: ProjectStoreConfig): Observable<ProjectStoreConfig> {
    return this.http.put<ProjectStoreConfig>(`${this.base}/config`, config);
  }

  listProjects(): Observable<RemoteProject[]> {
    return this.http.get<RemoteProject[]>(`${this.base}/projects`);
  }

  clone(namespace: string, slug: string, targetPath?: string): Observable<CloneResult> {
    return this.http.post<CloneResult>(`${this.base}/clone`, { namespace, slug, targetPath });
  }
}
