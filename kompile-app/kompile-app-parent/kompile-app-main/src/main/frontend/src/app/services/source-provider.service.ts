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
import { catchError, tap, map } from 'rxjs/operators';
import { environment } from '../../environments/environment';

/**
 * Form field types supported by source providers.
 */
export type SourceFormFieldType =
  | 'TEXT'
  | 'TEXTAREA'
  | 'PASSWORD'
  | 'NUMBER'
  | 'CHECKBOX'
  | 'SELECT'
  | 'MULTI_SELECT'
  | 'FILE'
  | 'DATE'
  | 'DATE_RANGE'
  | 'URL'
  | 'EMAIL'
  | 'SLIDER'
  | 'TOGGLE'
  | 'HIDDEN';

/**
 * Select option for dropdown fields.
 */
export interface SelectOption {
  value: string;
  label: string;
  description?: string;
  disabled?: boolean;
}

/**
 * Form field configuration for source provider dialogs.
 */
export interface SourceFormField {
  id: string;
  label: string;
  type: SourceFormFieldType;
  placeholder?: string;
  helpText?: string;
  required?: boolean;
  defaultValue?: any;
  pattern?: string;
  patternError?: string;
  min?: number;
  max?: number;
  step?: number;
  minLength?: number;
  maxLength?: number;
  options?: SelectOption[];
  accept?: string;
  multiple?: boolean;
  order?: number;
  group?: string;
  showWhen?: { [key: string]: any };
  attributes?: { [key: string]: any };
  prefixIcon?: string;
  suffixIcon?: string;
}

/**
 * Source provider metadata from the backend.
 */
export interface SourceProvider {
  id: string;
  displayName: string;
  description: string;
  icon: string;
  category: string;
  order: number;
  available: boolean;
  unavailableReason?: string;
  requiresAuth: boolean;
  authType?: string;
  oauthProvider?: string;
  supportsBatch: boolean;
  hasCustomDialog: boolean;
  customDialogComponent?: string;
  formFields: SourceFormField[];
  configuration?: { [key: string]: any };
}

/**
 * Category metadata.
 */
export interface SourceCategory {
  id: string;
  displayName: string;
  icon: string;
  order: number;
  description: string;
}

/**
 * API response for providers list.
 */
export interface SourceProvidersResponse {
  providers: SourceProvider[];
  totalCount: number;
  availableCount: number;
}

/**
 * Service for managing source providers.
 * Provides dynamic access to available source types based on backend modules.
 */
@Injectable({
  providedIn: 'root'
})
export class SourceProviderService {
  private providersSubject = new BehaviorSubject<SourceProvider[]>([]);
  private categoriesSubject = new BehaviorSubject<SourceCategory[]>([]);
  private loadedSubject = new BehaviorSubject<boolean>(false);

  public providers$ = this.providersSubject.asObservable();
  public categories$ = this.categoriesSubject.asObservable();
  public loaded$ = this.loadedSubject.asObservable();

  private readonly apiUrl = `${environment.apiUrl}/source-providers`;

  constructor(private http: HttpClient) {
    // Load providers on service initialization (include unavailable to show them grayed out)
    this.loadProviders(true);
    this.loadCategories();
  }

  /**
   * Load source providers from the backend.
   * @param includeUnavailable If true, includes unavailable providers (shown grayed out in UI)
   */
  loadProviders(includeUnavailable: boolean = true): Observable<SourceProvider[]> {
    return this.http.get<SourceProvidersResponse>(
      `${this.apiUrl}?includeUnavailable=${includeUnavailable}`
    ).pipe(
      tap(response => {
        this.providersSubject.next(response.providers);
        this.loadedSubject.next(true);
      }),
      map(response => response.providers),
      catchError(error => {
        console.error('Failed to load source providers:', error);
        this.loadedSubject.next(true);
        return of([]);
      })
    );
  }

  /**
   * Load category metadata from the backend.
   */
  loadCategories(): Observable<SourceCategory[]> {
    return this.http.get<SourceCategory[]>(`${this.apiUrl}/categories`).pipe(
      tap(categories => {
        this.categoriesSubject.next(categories);
      }),
      catchError(error => {
        console.error('Failed to load source categories:', error);
        // Return default categories on error
        const defaults: SourceCategory[] = [
          { id: 'local', displayName: 'Local Sources', icon: 'computer', order: 1, description: 'Local files and paths' },
          { id: 'web', displayName: 'Web Sources', icon: 'language', order: 2, description: 'Web pages and URLs' },
          { id: 'cloud', displayName: 'Cloud Storage', icon: 'cloud', order: 3, description: 'Cloud storage services' },
          { id: 'collaboration', displayName: 'Collaboration', icon: 'groups', order: 4, description: 'Team collaboration tools' }
        ];
        this.categoriesSubject.next(defaults);
        return of(defaults);
      })
    );
  }

  /**
   * Get a specific provider by ID.
   */
  getProvider(providerId: string): Observable<SourceProvider | null> {
    return this.http.get<SourceProvider>(`${this.apiUrl}/${providerId}`).pipe(
      catchError(error => {
        console.error(`Failed to load provider ${providerId}:`, error);
        return of(null);
      })
    );
  }

  /**
   * Get all available providers.
   */
  getProviders(): SourceProvider[] {
    return this.providersSubject.getValue();
  }

  /**
   * Get providers by category (includes unavailable providers for display).
   * @param categoryId The category to filter by
   * @param onlyAvailable If true, only returns available providers
   */
  getProvidersByCategory(categoryId: string, onlyAvailable: boolean = false): SourceProvider[] {
    return this.providersSubject.getValue()
      .filter(p => p.category === categoryId && (!onlyAvailable || p.available))
      .sort((a, b) => {
        // Sort available providers first, then by order
        if (a.available !== b.available) {
          return a.available ? -1 : 1;
        }
        return a.order - b.order;
      });
  }

  /**
   * Get providers grouped by category (includes unavailable providers for display).
   * @param onlyAvailable If true, only returns available providers
   */
  getProvidersGroupedByCategory(onlyAvailable: boolean = false): Map<string, SourceProvider[]> {
    const allProviders = this.providersSubject.getValue();
    const providers = onlyAvailable ? allProviders.filter(p => p.available) : allProviders;
    const categories = this.categoriesSubject.getValue();
    const grouped = new Map<string, SourceProvider[]>();

    // Initialize with empty arrays for each category in order
    categories.forEach(cat => {
      grouped.set(cat.id, []);
    });

    // Group providers
    providers.forEach(provider => {
      const categoryProviders = grouped.get(provider.category) || [];
      categoryProviders.push(provider);
      grouped.set(provider.category, categoryProviders);
    });

    // Sort providers within each category (available first, then by order)
    grouped.forEach((categoryProviders, categoryId) => {
      categoryProviders.sort((a, b) => {
        if (a.available !== b.available) {
          return a.available ? -1 : 1;
        }
        return a.order - b.order;
      });
    });

    return grouped;
  }

  /**
   * Get categories with their providers (includes unavailable providers for display).
   * @param onlyAvailable If true, only returns available providers
   */
  getCategoriesWithProviders(onlyAvailable: boolean = false): Array<{ category: SourceCategory; providers: SourceProvider[] }> {
    const categories = this.categoriesSubject.getValue();
    const grouped = this.getProvidersGroupedByCategory(onlyAvailable);

    return categories
      .filter(cat => {
        const providers = grouped.get(cat.id);
        return providers && providers.length > 0;
      })
      .map(cat => ({
        category: cat,
        providers: grouped.get(cat.id) || []
      }))
      .sort((a, b) => a.category.order - b.category.order);
  }

  /**
   * Get the count of available providers.
   */
  getAvailableCount(): number {
    return this.providersSubject.getValue().filter(p => p.available).length;
  }

  /**
   * Get the count of unavailable providers.
   */
  getUnavailableCount(): number {
    return this.providersSubject.getValue().filter(p => !p.available).length;
  }

  /**
   * Check if a provider has a custom dialog component.
   */
  hasCustomDialog(providerId: string): boolean {
    const provider = this.providersSubject.getValue().find(p => p.id === providerId);
    return provider?.hasCustomDialog ?? false;
  }

  /**
   * Get the custom dialog component name for a provider.
   */
  getCustomDialogComponent(providerId: string): string | undefined {
    const provider = this.providersSubject.getValue().find(p => p.id === providerId);
    return provider?.customDialogComponent;
  }

  /**
   * Check if providers have been loaded.
   */
  isLoaded(): boolean {
    return this.loadedSubject.getValue();
  }

  /**
   * Refresh providers from the backend.
   */
  refresh(): Observable<SourceProvider[]> {
    return this.loadProviders();
  }
}
