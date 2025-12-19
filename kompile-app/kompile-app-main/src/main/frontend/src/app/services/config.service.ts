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
import { BehaviorSubject, Observable, of } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { environment } from '../../environments/environment';

export interface AppConfig {
  appTitle: string;
  applicationName: string;
}

@Injectable({
  providedIn: 'root'
})
export class ConfigService {
  private configSubject = new BehaviorSubject<AppConfig>({
    appTitle: environment.appTitle,
    applicationName: 'kompile-rag-app'
  });

  public config$ = this.configSubject.asObservable();

  constructor(private http: HttpClient) {
    this.loadConfig();
  }

  /**
   * Load application configuration from the backend.
   * Falls back to environment defaults if the API call fails.
   */
  loadConfig(): void {
    this.http.get<AppConfig>(`${environment.apiUrl}/config`)
      .pipe(
        tap(config => {
          this.configSubject.next(config);
        }),
        catchError(error => {
          console.warn('Failed to load app config from backend, using defaults:', error);
          // Keep the default values from environment
          return of(this.configSubject.getValue());
        })
      )
      .subscribe();
  }

  /**
   * Get the current application title.
   */
  getAppTitle(): string {
    return this.configSubject.getValue().appTitle;
  }

  /**
   * Get the current configuration as an observable.
   */
  getConfig(): Observable<AppConfig> {
    return this.config$;
  }
}
