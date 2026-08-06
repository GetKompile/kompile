import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { CrawlSettingsComponent } from './crawl-settings.component';

describe('CrawlSettingsComponent', () => {
  let fixture: ComponentFixture<CrawlSettingsComponent>;
  let component: CrawlSettingsComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CrawlSettingsComponent, HttpClientTestingModule, NoopAnimationsModule]
    }).compileComponents();

    fixture = TestBed.createComponent(CrawlSettingsComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('loads the managed model-staging endpoint', () => {
    const request = http.expectOne('/api/service-endpoints');
    expect(request.request.method).toBe('GET');
    request.flush({ stagingUrl: 'http://staging.example:18090' });

    expect(component.stagingUrl).toBe('http://staging.example:18090');
  });

  it('saves only the crawl dependency endpoint', () => {
    http.expectOne('/api/service-endpoints').flush({ stagingUrl: 'http://localhost:8090' });
    component.stagingUrl = 'http://staging.example:18090/';

    component.save();

    const request = http.expectOne('/api/service-endpoints');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ stagingUrl: 'http://staging.example:18090' });
    request.flush({ stagingUrl: 'http://staging.example:18090' });
  });
});
