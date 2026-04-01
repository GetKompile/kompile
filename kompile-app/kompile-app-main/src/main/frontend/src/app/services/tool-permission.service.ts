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
import { BaseService } from './base.service';
import { ToolPermissionConfig, ToolPermissionStatus, PermissionLevel } from '../models/api-models';

@Injectable({
  providedIn: 'root'
})
export class ToolPermissionService extends BaseService {

  private readonly permissionsUrl: string;

  constructor(private http: HttpClient) {
    super();
    this.permissionsUrl = `${this.backendUrl}/tool-permissions`;
  }

  getConfig(): Observable<ToolPermissionConfig> {
    return this.http.get<ToolPermissionConfig>(this.permissionsUrl);
  }

  getToolsWithStatus(): Observable<ToolPermissionStatus> {
    return this.http.get<ToolPermissionStatus>(`${this.permissionsUrl}/tools-with-status`);
  }

  setDefaultPermission(permission: PermissionLevel): Observable<any> {
    return this.http.put(`${this.permissionsUrl}/default`, { permission });
  }

  setCategoryRule(category: string, permission: PermissionLevel): Observable<any> {
    return this.http.put(`${this.permissionsUrl}/category/${category}`, { permission });
  }

  removeCategoryRule(category: string): Observable<any> {
    return this.http.delete(`${this.permissionsUrl}/category/${category}`);
  }

  setToolRule(toolName: string, permission: PermissionLevel): Observable<any> {
    return this.http.put(`${this.permissionsUrl}/tool/${toolName}`, { permission });
  }

  removeToolRule(toolName: string): Observable<any> {
    return this.http.delete(`${this.permissionsUrl}/tool/${toolName}`);
  }

  bulkUpdate(body: {
    defaultPermission?: PermissionLevel;
    categoryRules?: { [key: string]: PermissionLevel };
    toolRules?: { [key: string]: PermissionLevel };
  }): Observable<any> {
    return this.http.post(`${this.permissionsUrl}/bulk`, body);
  }
}
