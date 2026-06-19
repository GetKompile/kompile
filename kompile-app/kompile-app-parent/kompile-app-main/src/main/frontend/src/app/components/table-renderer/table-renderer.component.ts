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

import { Component, Input, OnChanges, SimpleChanges, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ParsedTable, IndexedPassageItem } from '../../models/api-models';

/**
 * Table renderer component for displaying markdown tables as HTML tables.
 *
 * This component parses markdown table content and renders it as a proper
 * Material Design table with sorting, pagination, and export capabilities.
 *
 * Usage:
 * <app-table-renderer [passage]="passageWithTableContent"></app-table-renderer>
 * <app-table-renderer [markdownContent]="'| Col1 | Col2 |\n|---|---|\n| A | B |'"></app-table-renderer>
 */
@Component({
  selector: 'app-table-renderer',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatButtonModule, MatChipsModule, MatTooltipModule],
  templateUrl: './table-renderer.component.html',
  styleUrls: ['./table-renderer.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TableRendererComponent implements OnChanges {

  /** Input: A passage containing table content */
  @Input() passage?: IndexedPassageItem;

  /** Input: Raw markdown table content (alternative to passage) */
  @Input() markdownContent?: string;

  /** Input: Whether to show the table header */
  @Input() showHeader: boolean = true;

  /** Input: Whether to show table metadata (row/column counts) */
  @Input() showMetadata: boolean = true;

  /** Input: Whether to enable row hover highlighting */
  @Input() enableHover: boolean = true;

  /** Input: Maximum rows to display before pagination */
  @Input() maxRowsBeforePagination: number = 25;

  /** Input: Whether to show export button */
  @Input() showExport: boolean = true;

  /** Input: Compact mode for smaller tables */
  @Input() compact: boolean = false;

  /** Parsed table data */
  parsedTable: ParsedTable | null = null;

  /** Current page for pagination */
  currentPage: number = 0;

  /** Page size for pagination */
  pageSize: number = 25;

  /** Error message if parsing fails */
  parseError: string | null = null;

  /** Whether the table is collapsed (for large tables) */
  isCollapsed: boolean = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['passage'] || changes['markdownContent']) {
      this.parseTableContent();
    }
  }

  /**
   * Parse the markdown table content into structured data.
   */
  private parseTableContent(): void {
    this.parseError = null;
    this.parsedTable = null;

    const content = this.getTableContent();
    if (!content) {
      return;
    }

    try {
      this.parsedTable = this.parseMarkdownTable(content);
    } catch (error) {
      // Not strict GFM markdown — fall back to a delimited parse (TSV / CSV / pipe table without a
      // separator row) so tabular content from ANY loader or graph backend still renders as a table
      // instead of an error.
      this.parsedTable = this.parseDelimitedTable(content);
      if (!this.parsedTable) {
        this.parseError = `Failed to parse table: ${error instanceof Error ? error.message : 'Unknown error'}`;
        console.error('Table parsing error:', error);
        return;
      }
    }

    // Auto-collapse large tables
    if (this.parsedTable.rowCount > this.maxRowsBeforePagination) {
      this.isCollapsed = true;
    }
  }

  /**
   * Fallback parser for tabular content that is not strict GFM markdown. Detects the most likely
   * delimiter (tab, pipe, or comma) from the first line and treats it as the header row. Returns
   * null when the content has no consistent multi-column structure.
   */
  private parseDelimitedTable(content: string): ParsedTable | null {
    const lines = content.trim().split('\n').filter(line => line.trim());
    if (lines.length < 1) {
      return null;
    }

    // Pick the delimiter that yields the most columns on the first line.
    const candidates = [
      { delim: '\t', count: lines[0].split('\t').length },
      { delim: '|', count: lines[0].split('|').length },
      { delim: ',', count: lines[0].split(',').length }
    ].sort((a, b) => b.count - a.count);
    const best = candidates[0];
    if (best.count < 2) {
      return null; // single column — not actually tabular
    }

    const splitRow = (line: string): string[] => {
      let cells = line.split(best.delim).map(c => c.trim());
      // Pipe rows usually carry empty leading/trailing cells from the outer pipes.
      if (best.delim === '|') {
        if (cells.length && cells[0] === '') cells = cells.slice(1);
        if (cells.length && cells[cells.length - 1] === '') cells = cells.slice(0, -1);
      }
      return cells;
    };

    const headers = splitRow(lines[0]);
    const rows: string[][] = [];
    for (let i = 1; i < lines.length; i++) {
      if (this.isSeparatorRow(lines[i])) {
        continue; // tolerate a markdown separator if one happens to be present
      }
      const row = splitRow(lines[i]);
      while (row.length < headers.length) {
        row.push('');
      }
      rows.push(row.slice(0, headers.length));
    }

    return {
      headers,
      rows,
      rowCount: rows.length,
      columnCount: headers.length,
      tableType: 'delimited',
      rawMarkdown: content
    };
  }

  /**
   * Get the table content from either passage or direct markdown input.
   */
  private getTableContent(): string | null {
    // Priority: fullContent > markdownContent > contentPreview
    if (this.passage?.fullContent) {
      return this.passage.fullContent;
    }
    if (this.markdownContent) {
      return this.markdownContent;
    }
    if (this.passage?.contentPreview) {
      return this.passage.contentPreview;
    }
    return null;
  }

  /**
   * Parse a markdown table into structured data.
   */
  private parseMarkdownTable(markdown: string): ParsedTable {
    const lines = markdown.trim().split('\n').filter(line => line.trim());

    if (lines.length < 2) {
      throw new Error('Invalid table: needs at least header and separator rows');
    }

    // Parse header row
    const headerLine = lines[0];
    const headers = this.parseTableRow(headerLine);

    // Verify separator row exists
    const separatorLine = lines[1];
    if (!this.isSeparatorRow(separatorLine)) {
      throw new Error('Invalid table: missing separator row');
    }

    // Parse data rows
    const rows: string[][] = [];
    for (let i = 2; i < lines.length; i++) {
      const row = this.parseTableRow(lines[i]);
      // Ensure row has same number of columns as headers
      while (row.length < headers.length) {
        row.push('');
      }
      rows.push(row.slice(0, headers.length));
    }

    return {
      headers,
      rows,
      rowCount: rows.length,
      columnCount: headers.length,
      tableType: 'markdown',
      rawMarkdown: markdown
    };
  }

  /**
   * Parse a single table row into cells.
   */
  private parseTableRow(line: string): string[] {
    // Remove leading/trailing pipes and split
    let trimmed = line.trim();
    if (trimmed.startsWith('|')) {
      trimmed = trimmed.substring(1);
    }
    if (trimmed.endsWith('|')) {
      trimmed = trimmed.substring(0, trimmed.length - 1);
    }

    // Split by pipe and trim each cell
    return trimmed.split('|').map(cell => cell.trim());
  }

  /**
   * Check if a line is a table separator row.
   */
  private isSeparatorRow(line: string): boolean {
    // Separator rows contain only pipes, dashes, colons, and spaces
    return /^\|?[\s\-:|]+\|?$/.test(line.trim());
  }

  /**
   * Get visible rows based on pagination.
   */
  getVisibleRows(): string[][] {
    if (!this.parsedTable) return [];

    if (this.parsedTable.rowCount <= this.maxRowsBeforePagination) {
      return this.parsedTable.rows;
    }

    const start = this.currentPage * this.pageSize;
    const end = start + this.pageSize;
    return this.parsedTable.rows.slice(start, end);
  }

  /**
   * Get total pages for pagination.
   */
  get totalPages(): number {
    if (!this.parsedTable) return 0;
    return Math.ceil(this.parsedTable.rowCount / this.pageSize);
  }

  /**
   * Navigate to a specific page.
   */
  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages) {
      this.currentPage = page;
    }
  }

  /**
   * Toggle table collapse state.
   */
  toggleCollapse(): void {
    this.isCollapsed = !this.isCollapsed;
  }

  /**
   * Export table as CSV.
   */
  exportAsCsv(): void {
    if (!this.parsedTable) return;

    const csv = this.convertToCsv(this.parsedTable);
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    const url = URL.createObjectURL(blob);

    link.setAttribute('href', url);
    link.setAttribute('download', `table-${Date.now()}.csv`);
    link.style.visibility = 'hidden';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }

  /**
   * Convert parsed table to CSV string.
   */
  private convertToCsv(table: ParsedTable): string {
    const escapeCell = (cell: string): string => {
      if (cell.includes(',') || cell.includes('"') || cell.includes('\n')) {
        return `"${cell.replace(/"/g, '""')}"`;
      }
      return cell;
    };

    const headerRow = table.headers.map(escapeCell).join(',');
    const dataRows = table.rows.map(row => row.map(escapeCell).join(','));

    return [headerRow, ...dataRows].join('\n');
  }

  /**
   * Copy table as markdown to clipboard.
   */
  copyAsMarkdown(): void {
    if (!this.parsedTable) return;

    navigator.clipboard.writeText(this.parsedTable.rawMarkdown).then(() => {
      // Could add snackbar notification here
      console.log('Table copied to clipboard');
    }).catch(err => {
      console.error('Failed to copy table:', err);
    });
  }

  /**
   * Track by function for ngFor.
   */
  trackByIndex(index: number): number {
    return index;
  }
}
