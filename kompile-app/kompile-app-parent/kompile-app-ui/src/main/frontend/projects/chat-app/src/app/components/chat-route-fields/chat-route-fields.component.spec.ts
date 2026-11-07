import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChatRouteFieldsComponent, ChatSetupCatalog } from './chat-route-fields.component';

describe('ChatRouteFieldsComponent saved-account-only fallback editor', () => {
  let fixture: ComponentFixture<ChatRouteFieldsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ChatRouteFieldsComponent] }).compileComponents();
    fixture = TestBed.createComponent(ChatRouteFieldsComponent);
    fixture.componentInstance.selection = { runtime: 'direct', vendor: 'custom', authMethod: 'api-key' };
    fixture.componentInstance.catalog = {
      available: true, defaults: {}, profiles: [], modes: [], runtimes: [{ id: 'direct', label: 'Direct' }],
      webSupported: true, leadModes: [], passthroughStyles: [], frameworks: [],
      vendors: [{ id: 'custom', label: 'Custom', endpointRequired: true,
        authMethods: [{ id: 'api-key', label: 'API key', acceptsKey: true }] }],
      credentials: [{ name: 'work', label: 'Work account' }], models: [{ id: 'model', label: 'Model' }],
      thinkingOptions: [], workflows: [], judgeProfiles: [], fastModeSupported: false, ultracodeSupported: false,
      fallbackRoute: { provider: 'custom', authenticationMethod: 'api-key' }
    } satisfies ChatSetupCatalog;
  });

  it('retains the complete setup fields by default', () => {
    fixture.detectChanges();
    for (const name of ['apiKey', 'baseUrl', 'authScope', 'credential']) {
      expect(fixture.nativeElement.querySelector(`[name="${name}"]`)).withContext(name).not.toBeNull();
    }
  });

  it('does not render key, endpoint or authentication-scope inputs for fallback routes', () => {
    fixture.componentInstance.existingAccountsOnly = true;
    fixture.detectChanges();
    for (const name of ['apiKey', 'baseUrl', 'authScope']) {
      expect(fixture.nativeElement.querySelector(`[name="${name}"]`)).withContext(name).toBeNull();
    }
    for (const name of ['credential', 'vendor', 'auth', 'modelChoice', 'thinking']) {
      expect(fixture.nativeElement.querySelector(`[name="${name}"]`)).withContext(name).not.toBeNull();
    }
  });
});
