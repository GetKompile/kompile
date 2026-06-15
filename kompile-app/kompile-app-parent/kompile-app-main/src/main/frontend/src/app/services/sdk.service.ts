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
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { backendUrl } from './base.service';

export interface SdkArtifact {
  platform: string;
  artifactFileName: string;
  packaging: string;
  downloadUrl: string;
  cached: boolean;
}

export interface SdkEntry {
  sdkId: string;
  version: string;
  artifacts: SdkArtifact[];
}

export interface SdzModel {
  modelId: string;
  version: string;
  downloadUrl: string;
  metadata: Record<string, any>;
  cached: boolean;
}

export interface SdkListResponse {
  sdks?: SdkEntry[];
  models?: SdzModel[];
}

export interface SdkPlatformsResponse {
  basePlatforms: string[];
  mobilePlatforms: string[];
  iosPlatforms: string[];
  androidPlatforms: string[];
  desktopPlatforms: string[];
  extendedClassifiers: Record<string, string[]>;
}

export interface ScaffoldInfo {
  platform: string;
  templateType: string;
  language: string;
  sdkPlatform: string;
  features: string[];
  requirements: Record<string, string>;
  availableModels: { modelId: string; version: string; metadata: Record<string, any> }[];
}

export interface SdkStatus {
  cacheDirectory: string;
  cachedSdkPlatforms: number;
  totalSdkPlatforms: number;
  cachedModels: number;
  totalModels: number;
}

export interface DownloadResult {
  status: string;
  platform?: string;
  modelId?: string;
  path: string;
  fileName: string;
}

export interface ScaffoldRequest {
  platform: string;
  projectName: string;
  packageName: string;
  modelId: string;
  inferenceMode: string;
  includeModel: boolean;
  includeSdk: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class SdkService {
  private baseUrl: string;

  private statusSubject = new BehaviorSubject<SdkStatus | null>(null);
  private loadingSubject = new BehaviorSubject<boolean>(false);

  status$ = this.statusSubject.asObservable();
  loading$ = this.loadingSubject.asObservable();

  constructor(private http: HttpClient) {
    this.baseUrl = `${backendUrl}/sdk`;
  }

  getPlatforms(): Observable<SdkPlatformsResponse> {
    return this.http.get<SdkPlatformsResponse>(`${this.baseUrl}/platforms`);
  }

  listSdks(platform?: string, type?: string): Observable<SdkListResponse> {
    let params: any = {};
    if (platform) params.platform = platform;
    if (type) params.type = type;
    return this.http.get<SdkListResponse>(`${this.baseUrl}/list`, { params });
  }

  getStatus(): Observable<SdkStatus> {
    return this.http.get<SdkStatus>(`${this.baseUrl}/status`).pipe(
      tap(status => this.statusSubject.next(status))
    );
  }

  getScaffoldInfo(platform: string): Observable<ScaffoldInfo> {
    return this.http.get<ScaffoldInfo>(`${this.baseUrl}/scaffold-info`, { params: { platform } });
  }

  downloadSdk(platform: string, chip?: string, sdkVersion?: string): Observable<DownloadResult> {
    this.loadingSubject.next(true);
    const body: any = { platform };
    if (chip) body.chip = chip;
    if (sdkVersion) body.sdkVersion = sdkVersion;
    return this.http.post<DownloadResult>(`${this.baseUrl}/download-sdk`, body).pipe(
      tap({
        next: () => {
          this.loadingSubject.next(false);
          this.getStatus().subscribe();
        },
        error: () => this.loadingSubject.next(false)
      })
    );
  }

  downloadModel(modelId: string): Observable<DownloadResult> {
    this.loadingSubject.next(true);
    return this.http.post<DownloadResult>(`${this.baseUrl}/download-model`, { modelId }).pipe(
      tap({
        next: () => {
          this.loadingSubject.next(false);
          this.getStatus().subscribe();
        },
        error: () => this.loadingSubject.next(false)
      })
    );
  }

  scaffoldProject(request: ScaffoldRequest): Observable<Blob> {
    return this.http.post(`${this.baseUrl}/scaffold`, request, {
      responseType: 'blob'
    });
  }
}
