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
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  SourceInfo,
  SourceListResponse,
  TextContentResponse,
  MarkdownConversionResponse,
  SupportedTypesResponse
} from '../models/api-models';

/**
 * Service for viewing and retrieving source document content.
 */
@Injectable({
  providedIn: 'root'
})
export class SourceViewerService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * Get list of available source documents.
   * @param limit Maximum number of sources to return
   * @param offset Offset for pagination
   */
  listSources(limit: number = 100, offset: number = 0): Observable<SourceListResponse> {
    let params = new HttpParams()
      .set('limit', limit.toString())
      .set('offset', offset.toString());

    return this.http.get<SourceListResponse>(`${this.backendUrl}/sources`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get source file information by filename.
   * @param fileName The file name to get info for
   */
  getSourceInfo(fileName: string): Observable<SourceInfo> {
    return this.http.get<SourceInfo>(`${this.backendUrl}/sources/info/${encodeURIComponent(fileName)}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get text content of a file for inline viewing.
   * @param fileName The file name to read
   * @param maxLines Maximum lines to return
   * @param encoding Character encoding
   */
  getTextContent(fileName: string, maxLines: number = 10000, encoding: string = 'UTF-8'): Observable<TextContentResponse> {
    let params = new HttpParams()
      .set('maxLines', maxLines.toString())
      .set('encoding', encoding);

    return this.http.get<TextContentResponse>(`${this.backendUrl}/sources/text/${encodeURIComponent(fileName)}`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get the URL to view/download a file by filename.
   * @param fileName The file name
   * @param download Whether to force download (vs inline view)
   */
  getFileUrl(fileName: string, download: boolean = false): string {
    const url = `${this.backendUrl}/sources/file/${encodeURIComponent(fileName)}`;
    return download ? `${url}?download=true` : url;
  }

  /**
   * Get the URL to view/download a file by checksum.
   * @param checksum The SHA-256 checksum
   * @param download Whether to force download
   */
  getFileUrlByChecksum(checksum: string, download: boolean = false): string {
    const url = `${this.backendUrl}/sources/checksum/${encodeURIComponent(checksum)}`;
    return download ? `${url}?download=true` : url;
  }

  /**
   * Get list of supported file types.
   */
  getSupportedTypes(): Observable<SupportedTypesResponse> {
    return this.http.get<SupportedTypesResponse>(`${this.backendUrl}/sources/supported-types`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Convert a source file into Markdown and store it in the source browser.
   */
  convertToMarkdown(fileName: string, checksum?: string | null): Observable<MarkdownConversionResponse> {
    return this.http.post<MarkdownConversionResponse>(`${this.backendUrl}/sources/markdown/convert`, {
      fileName,
      checksum: checksum || null
    }).pipe(catchError(this.handleError));
  }

  /**
   * Download a file by triggering a browser download.
   * @param fileName The file name to download
   */
  downloadFile(fileName: string): void {
    const url = this.getFileUrl(fileName, true);
    window.open(url, '_blank');
  }

  /**
   * Download a file by checksum.
   * @param checksum The SHA-256 checksum
   */
  downloadFileByChecksum(checksum: string): void {
    const url = this.getFileUrlByChecksum(checksum, true);
    window.open(url, '_blank');
  }

  /**
   * Check if a file extension is viewable as text.
   * @param extension The file extension (lowercase, without dot)
   */
  isTextViewable(extension: string): boolean {
    const textExtensions = new Set([
      'txt', 'md', 'markdown', 'json', 'xml', 'html', 'htm', 'css', 'js', 'ts',
      'java', 'py', 'rb', 'go', 'rs', 'c', 'cpp', 'h', 'hpp', 'yaml', 'yml',
      'csv', 'tsv', 'log', 'conf', 'cfg', 'ini', 'sh', 'bash', 'zsh', 'fish',
      'sql', 'properties', 'env', 'gitignore', 'dockerfile', 'makefile'
    ]);
    return textExtensions.has(extension.toLowerCase());
  }

  /**
   * Check if a file extension is viewable as an image.
   * @param extension The file extension (lowercase, without dot)
   */
  isImageViewable(extension: string): boolean {
    const imageExtensions = new Set([
      'jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'ico'
    ]);
    return imageExtensions.has(extension.toLowerCase());
  }

  /**
   * Check if a file extension is viewable as embedded content (PDF).
   * @param extension The file extension (lowercase, without dot)
   */
  isEmbeddedViewable(extension: string): boolean {
    const embeddedExtensions = new Set(['pdf']);
    return embeddedExtensions.has(extension.toLowerCase());
  }

  /**
   * Get syntax highlighting language for an extension.
   * @param extension The file extension
   */
  getSyntaxLanguage(extension: string): string {
    const languageMap: { [key: string]: string } = {
      'js': 'javascript',
      'ts': 'typescript',
      'py': 'python',
      'rb': 'ruby',
      'java': 'java',
      'cpp': 'cpp',
      'c': 'c',
      'h': 'c',
      'hpp': 'cpp',
      'go': 'go',
      'rs': 'rust',
      'sh': 'bash',
      'bash': 'bash',
      'zsh': 'bash',
      'fish': 'shell',
      'json': 'json',
      'xml': 'xml',
      'html': 'html',
      'htm': 'html',
      'css': 'css',
      'yaml': 'yaml',
      'yml': 'yaml',
      'sql': 'sql',
      'md': 'markdown',
      'markdown': 'markdown',
      'dockerfile': 'dockerfile',
      'makefile': 'makefile',
      'properties': 'properties',
      'ini': 'ini',
      'cfg': 'ini',
      'conf': 'ini'
    };
    return languageMap[extension.toLowerCase()] || 'text';
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'Unknown error!';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      const serverError = error.error;
      if (serverError && (serverError.error || serverError.message)) {
        errorMessage = `Error Code: ${error.status}\nMessage: ${serverError.error || serverError.message}`;
      } else if (error.message) {
        errorMessage = `Error Code: ${error.status}\nMessage: ${error.message}`;
      } else {
        errorMessage = `Error Code: ${error.status}\nMessage: Server error`;
      }
    }
    console.error('SourceViewerService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
