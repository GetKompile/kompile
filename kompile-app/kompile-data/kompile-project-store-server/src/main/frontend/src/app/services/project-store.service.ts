import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

/** A hosted project, mirroring the server's ProjectDto. */
export interface ProjectSummary {
  id: string;
  namespace: string;
  slug: string;
  fullName: string;
  repoType: string;
  visibility: string;
  defaultBranch: string;
  description?: string | null;
  cloneUrl: string;
  cliCloneCommand: string;
  /** Raw kompile.project.json contents (only populated by getProject). */
  manifest?: string | null;
}

/** A single entry in a project's git tree, mirroring GitRepoService.TreeEntry. */
export interface TreeEntry {
  name: string;
  path: string;
  type: 'blob' | 'tree';
  size: number;
}

/**
 * Client for the project-store REST API. The UI is served from the same origin as the
 * API, so all calls use relative URLs.
 */
@Injectable({ providedIn: 'root' })
export class ProjectStoreService {
  private readonly base = 'api/projects';

  constructor(private http: HttpClient) {}

  /** List every hosted project. */
  listProjects(): Observable<ProjectSummary[]> {
    return this.http.get<ProjectSummary[]>(this.base);
  }

  /** List only public projects. */
  listPublicProjects(): Observable<ProjectSummary[]> {
    return this.http.get<ProjectSummary[]>(`${this.base}/public`);
  }

  /** Fetch a single project, including its manifest. */
  getProject(namespace: string, slug: string): Observable<ProjectSummary> {
    return this.http.get<ProjectSummary>(`${this.base}/${seg(namespace)}/${seg(slug)}`);
  }

  /** List entries directly under {@code path} (root when omitted) at the given ref. */
  listTree(namespace: string, slug: string, ref: string, path?: string): Observable<TreeEntry[]> {
    let params = new HttpParams();
    if (path) {
      params = params.set('path', path);
    }
    return this.http.get<TreeEntry[]>(
      `${this.base}/${seg(namespace)}/${seg(slug)}/tree/${seg(ref)}`, { params });
  }

  /** Direct URL to download a single file blob. */
  blobUrl(namespace: string, slug: string, ref: string, path: string): string {
    return `${this.base}/${seg(namespace)}/${seg(slug)}/blob/${seg(ref)}?path=${encodeURIComponent(path)}`;
  }

  /** Direct URL to download the whole project tree at a ref as a ZIP. */
  archiveUrl(namespace: string, slug: string, ref: string): string {
    return `${this.base}/${seg(namespace)}/${seg(slug)}/archive/${seg(ref)}`;
  }
}

/** Encode a single path segment (namespace/slug/ref may contain reserved characters). */
function seg(value: string): string {
  return encodeURIComponent(value);
}
