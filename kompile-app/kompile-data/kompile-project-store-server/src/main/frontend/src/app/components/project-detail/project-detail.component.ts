import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ProjectStoreService, ProjectSummary, TreeEntry } from '../../services/project-store.service';

/** One clickable segment of the file-browser breadcrumb. */
interface Crumb {
  name: string;
  path: string;
}

/**
 * Detail + download view for a single project: clone commands, a one-click ZIP download,
 * the parsed manifest, and a drill-down git file browser with per-file downloads.
 */
@Component({
  selector: 'app-project-detail',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    MatSnackBarModule
  ],
  templateUrl: './project-detail.component.html',
  styleUrls: ['./project-detail.component.css']
})
export class ProjectDetailComponent implements OnInit {
  @Input() namespace!: string;
  @Input() slug!: string;
  @Output() back = new EventEmitter<void>();

  project: ProjectSummary | null = null;
  loading = false;
  error: string | null = null;

  manifestObj: Record<string, any> | null = null;
  manifestRaw: string | null = null;

  ref = 'main';
  currentPath = '';
  entries: TreeEntry[] = [];
  treeLoading = false;
  treeError: string | null = null;

  constructor(private store: ProjectStoreService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.loadProject();
  }

  loadProject(): void {
    this.loading = true;
    this.error = null;
    this.store.getProject(this.namespace, this.slug).subscribe({
      next: (project) => {
        this.project = project;
        this.ref = project.defaultBranch || 'main';
        this.parseManifest(project.manifest);
        this.loading = false;
        this.loadTree('');
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to load project.';
        this.loading = false;
      }
    });
  }

  private parseManifest(manifest?: string | null): void {
    this.manifestObj = null;
    this.manifestRaw = manifest ?? null;
    if (!manifest) {
      return;
    }
    try {
      const parsed = JSON.parse(manifest);
      this.manifestObj = parsed;
      this.manifestRaw = JSON.stringify(parsed, null, 2);
    } catch {
      // Leave manifestRaw as the unparsed string so it can still be shown verbatim.
    }
  }

  // ── File browser ────────────────────────────────────────────────

  loadTree(path: string): void {
    this.treeLoading = true;
    this.treeError = null;
    this.currentPath = path;
    this.store.listTree(this.namespace, this.slug, this.ref, path).subscribe({
      next: (entries) => {
        this.entries = this.sortEntries(entries ?? []);
        this.treeLoading = false;
      },
      error: (err) => {
        this.treeError = err?.error?.message || 'Failed to load files.';
        this.entries = [];
        this.treeLoading = false;
      }
    });
  }

  private sortEntries(entries: TreeEntry[]): TreeEntry[] {
    return [...entries].sort((a, b) => {
      if (a.type !== b.type) {
        return a.type === 'tree' ? -1 : 1;
      }
      return a.name.localeCompare(b.name);
    });
  }

  openEntry(entry: TreeEntry): void {
    if (entry.type === 'tree') {
      this.loadTree(entry.path);
    }
  }

  get breadcrumbs(): Crumb[] {
    const crumbs: Crumb[] = [{ name: this.slug, path: '' }];
    if (this.currentPath) {
      const parts = this.currentPath.split('/');
      let acc = '';
      for (const part of parts) {
        acc = acc ? `${acc}/${part}` : part;
        crumbs.push({ name: part, path: acc });
      }
    }
    return crumbs;
  }

  blobHref(entry: TreeEntry): string {
    return this.store.blobUrl(this.namespace, this.slug, this.ref, entry.path);
  }

  get archiveHref(): string {
    return this.store.archiveUrl(this.namespace, this.slug, this.ref);
  }

  get gitCloneCommand(): string {
    return this.project ? `git clone ${this.project.cloneUrl}` : '';
  }

  // ── Clipboard ───────────────────────────────────────────────────

  copy(text: string, label: string): void {
    const done = () => this.snackBar.open(`${label} copied`, 'Dismiss',
      { duration: 2000, panelClass: 'snackbar-success' });
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(() => this.fallbackCopy(text, done));
    } else {
      this.fallbackCopy(text, done);
    }
  }

  private fallbackCopy(text: string, done: () => void): void {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      done();
    } finally {
      document.body.removeChild(ta);
    }
  }

  // ── Presentation helpers ────────────────────────────────────────

  formatSize(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} B`;
    }
    const units = ['KB', 'MB', 'GB', 'TB'];
    let value = bytes / 1024;
    let i = 0;
    while (value >= 1024 && i < units.length - 1) {
      value /= 1024;
      i++;
    }
    return `${value.toFixed(value < 10 ? 1 : 0)} ${units[i]}`;
  }

  iconForEntry(entry: TreeEntry): string {
    if (entry.type === 'tree') {
      return 'folder';
    }
    const ext = entry.name.includes('.') ? entry.name.split('.').pop()!.toLowerCase() : '';
    switch (ext) {
      case 'json': return 'data_object';
      case 'md': case 'txt': case 'rst': return 'article';
      case 'yml': case 'yaml': case 'toml': case 'xml': case 'properties': return 'settings';
      case 'png': case 'jpg': case 'jpeg': case 'gif': case 'svg': case 'webp': return 'image';
      case 'java': case 'ts': case 'js': case 'py': case 'go': case 'rs': case 'c': case 'cpp': case 'sh':
        return 'code';
      case 'zip': case 'gz': case 'tar': case 'jar': return 'folder_zip';
      default: return 'description';
    }
  }

  manifestField(key: string): string | null {
    const value = this.manifestObj?.[key];
    return typeof value === 'string' ? value : null;
  }
}
