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
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

export interface ServiceDeviceConfig {
  deviceType: string | null;    // "cpu", "cuda", or null
  cudaDeviceId: number | null;
  maxThreads: number | null;
  maxDeviceMemory: number | null;
}

export interface DeviceRoutingConfig {
  serviceRoutes: { [serviceType: string]: ServiceDeviceConfig };
  enabled: boolean;
}

export interface DeviceInfo {
  id: number;
  name: string;
  totalMemory: number;
  availableMemory: number;
  isCuda: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class DeviceRoutingService {
  private readonly apiUrl = `${backendUrl}/device-routing`;

  constructor(private http: HttpClient) {}

  getConfiguration(): Observable<DeviceRoutingConfig> {
    return this.http.get<DeviceRoutingConfig>(this.apiUrl);
  }

  saveConfiguration(config: DeviceRoutingConfig): Observable<DeviceRoutingConfig> {
    return this.http.post<DeviceRoutingConfig>(this.apiUrl, config);
  }

  getServiceConfig(serviceType: string): Observable<ServiceDeviceConfig> {
    return this.http.get<ServiceDeviceConfig>(`${this.apiUrl}/services/${serviceType}`);
  }

  updateServiceConfig(serviceType: string, config: ServiceDeviceConfig): Observable<DeviceRoutingConfig> {
    return this.http.put<DeviceRoutingConfig>(`${this.apiUrl}/services/${serviceType}`, config);
  }

  removeServiceConfig(serviceType: string): Observable<DeviceRoutingConfig> {
    return this.http.delete<DeviceRoutingConfig>(`${this.apiUrl}/services/${serviceType}`);
  }

  resetToDefaults(): Observable<DeviceRoutingConfig> {
    return this.http.post<DeviceRoutingConfig>(`${this.apiUrl}/reset`, {});
  }

  previewServiceConfig(serviceType: string): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/preview/${serviceType}`);
  }

  getSystemDevices(): Observable<any> {
    return this.http.get<any>(`${backendUrl}/system/devices`);
  }
}
