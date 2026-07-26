import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { ProjectService } from './project.service';

describe('ProjectService portable knowledge bases', () => {
  let service: ProjectService;
  let httpMock: HttpTestingController;
  const file = new File(['archive'], 'research.kproject', { type: 'application/octet-stream' });

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ProjectService]
    });
    service = TestBed.inject(ProjectService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('starts and polls a portable export maintenance job', () => {
    service.startPortableKnowledgeBaseExport().subscribe();
    const start = httpMock.expectOne(request =>
      request.url.endsWith('/projects/current/portability/exports'));
    expect(start.request.method).toBe('POST');
    start.flush({ id: 'job-1', status: 'QUEUED' });

    service.getPortableKnowledgeBaseJob('job-1').subscribe();
    const poll = httpMock.expectOne(request =>
      request.url.endsWith('/projects/current/portability/jobs/job-1'));
    expect(poll.request.method).toBe('GET');
    poll.flush({ id: 'job-1', status: 'RUNNING' });
  });

  it('inspects an uploaded archive using multipart form data', () => {
    service.inspectPortableKnowledgeBase(file).subscribe();

    const req = httpMock.expectOne(request =>
      request.url.endsWith('/projects/current/portability/inspect'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBeTrue();
    const uploaded = (req.request.body as FormData).get('file') as File;
    expect(uploaded.name).toBe(file.name);
    expect(uploaded.size).toBe(file.size);
    req.flush({ name: 'research', formatVersion: 2 });
  });

  it('starts a staged import with an optional target name', () => {
    service.startPortableKnowledgeBaseImport(file, 'research-copy').subscribe();

    const req = httpMock.expectOne(request =>
      request.url.endsWith('/projects/current/portability/imports'));
    const form = req.request.body as FormData;
    expect(req.request.method).toBe('POST');
    const uploaded = form.get('file') as File;
    expect(uploaded.name).toBe(file.name);
    expect(uploaded.size).toBe(file.size);
    expect(form.get('targetName')).toBe('research-copy');
    req.flush({ id: 'job-2', status: 'QUEUED' });
  });

  it('downloads a completed export as a blob', () => {
    service.downloadPortableKnowledgeBase('job-3').subscribe(value =>
      expect(value.size).toBe(3));

    const req = httpMock.expectOne(request =>
      request.url.endsWith('/projects/current/portability/jobs/job-3/download'));
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['zip']));
  });

  it('loads current and staged restoration checklists', () => {
    service.getRestorationReadiness().subscribe();
    const current = httpMock.expectOne(request =>
      request.url.endsWith('/projects/current/portability/restoration-readiness'));
    expect(current.request.method).toBe('GET');
    current.flush({ active: true, items: [] });

    service.getImportedRestorationReadiness('job with space').subscribe();
    const staged = httpMock.expectOne(request =>
      request.url.endsWith('/projects/current/portability/jobs/job%20with%20space/restoration-readiness'));
    expect(staged.request.method).toBe('GET');
    staged.flush({ active: false, activationMode: 'RESTART_REQUIRED', items: [] });
  });

  it('defaults catalog restoration to a dry run and supports explicit apply', () => {
    service.restorePortableCatalogs().subscribe();
    const dryRun = httpMock.expectOne(request =>
      request.url.endsWith('/projects/current/portability/restore')
        && request.params.get('dryRun') === 'true');
    expect(dryRun.request.method).toBe('POST');
    dryRun.flush({ dryRun: true, applied: false });

    service.restorePortableCatalogs(false).subscribe();
    const apply = httpMock.expectOne(request =>
      request.url.endsWith('/projects/current/portability/restore')
        && request.params.get('dryRun') === 'false');
    expect(apply.request.method).toBe('POST');
    apply.flush({ dryRun: false, applied: true });
  });
});
