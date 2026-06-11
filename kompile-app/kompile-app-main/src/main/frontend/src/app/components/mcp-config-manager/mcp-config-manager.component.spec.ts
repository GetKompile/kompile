/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatMenuModule } from '@angular/material/menu';
import { of, throwError } from 'rxjs';
import { McpConfigManagerComponent } from './mcp-config-manager.component';
import {
  ExternalMcpServerService,
  ExternalMcpServerConfig,
  ServerStatus
} from '../../services/external-mcp-server.service';

describe('McpConfigManagerComponent', () => {
  let component: McpConfigManagerComponent;
  let fixture: ComponentFixture<McpConfigManagerComponent>;
  let mcpServiceSpy: jasmine.SpyObj<ExternalMcpServerService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  const mockServer: ExternalMcpServerConfig = {
    id: 'test-server',
    transportType: 'STDIO',
    command: 'node',
    args: ['server.js'],
    env: { API_KEY: 'abc' },
    enabled: true,
    status: 'STOPPED' as ServerStatus
  };

  const mockDialogRef = {
    afterClosed: () => of(false)
  } as unknown as MatDialogRef<any>;

  const mockConfirmDialogRef = {
    afterClosed: () => of(true)
  } as unknown as MatDialogRef<any>;

  const defaultConfig: ExternalMcpServerConfig = {
    id: '',
    transportType: 'STDIO',
    command: '',
    args: [],
    env: {},
    enabled: true
  };

  const defaultRestConfig: ExternalMcpServerConfig = {
    id: '',
    transportType: 'REST',
    url: '',
    headers: {},
    enabled: true
  };

  const defaultSseConfig: ExternalMcpServerConfig = {
    id: '',
    transportType: 'SSE',
    url: '',
    headers: {},
    enabled: true
  };

  beforeEach(async () => {
    mcpServiceSpy = jasmine.createSpyObj('ExternalMcpServerService', [
      'listServers', 'getServerStatus', 'createDefaultConfig', 'createDefaultRestConfig',
      'createDefaultSseConfig', 'addServer', 'updateServer', 'deleteServer',
      'startServer', 'stopServer', 'restartServer', 'formatConfigJson', 'validateConfig',
      'replaceConfig', 'importConfig', 'exportConfig', 'isStdio', 'isRest', 'isSse'
    ]);
    mcpServiceSpy.listServers.and.returnValue(of([mockServer]));
    mcpServiceSpy.getServerStatus.and.returnValue(of({ id: 'test-server', status: 'RUNNING' as ServerStatus }));
    mcpServiceSpy.createDefaultConfig.and.returnValue({ ...defaultConfig });
    mcpServiceSpy.createDefaultRestConfig.and.returnValue({ ...defaultRestConfig });
    mcpServiceSpy.createDefaultSseConfig.and.returnValue({ ...defaultSseConfig });
    mcpServiceSpy.addServer.and.returnValue(of(mockServer));
    mcpServiceSpy.updateServer.and.returnValue(of(mockServer));
    mcpServiceSpy.deleteServer.and.returnValue(of(undefined));
    mcpServiceSpy.startServer.and.returnValue(of({ ...mockServer, status: 'RUNNING' as ServerStatus }));
    mcpServiceSpy.stopServer.and.returnValue(of({ ...mockServer, status: 'STOPPED' as ServerStatus }));
    mcpServiceSpy.restartServer.and.returnValue(of({ ...mockServer, status: 'RUNNING' as ServerStatus }));
    mcpServiceSpy.formatConfigJson.and.returnValue('{}');
    mcpServiceSpy.validateConfig.and.returnValue(of({ valid: true }));
    mcpServiceSpy.replaceConfig.and.returnValue(of({ message: 'OK', serverCount: 1 }));
    mcpServiceSpy.importConfig.and.returnValue(of({ message: 'Imported', serverCount: 1 }));
    mcpServiceSpy.exportConfig.and.returnValue(of('{"mcpServers":{}}'));
    mcpServiceSpy.isStdio.and.callFake((s: ExternalMcpServerConfig) => s.transportType === 'STDIO' || !s.transportType);
    mcpServiceSpy.isRest.and.callFake((s: ExternalMcpServerConfig) => s.transportType === 'REST');
    mcpServiceSpy.isSse.and.callFake((s: ExternalMcpServerConfig) => s.transportType === 'SSE');

    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);
    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);
    dialogSpy.open.and.returnValue(mockDialogRef);

    await TestBed.configureTestingModule({
      declarations: [McpConfigManagerComponent],
      imports: [NoopAnimationsModule, MatMenuModule],
      providers: [
        { provide: ExternalMcpServerService, useValue: mcpServiceSpy },
        { provide: MatSnackBar, useValue: snackBarSpy },
        { provide: MatDialog, useValue: dialogSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(McpConfigManagerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load servers on init', () => {
    expect(mcpServiceSpy.listServers).toHaveBeenCalled();
    expect(component.servers).toEqual([mockServer]);
  });

  it('should set isLoading to false after servers loaded', () => {
    expect(component.isLoading).toBeFalse();
  });

  describe('loadServers - error', () => {
    it('should call showError on failure', fakeAsync(() => {
      mcpServiceSpy.listServers.and.returnValue(throwError(() => ({ message: 'fail' })));
      component.loadServers();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalled();
    }));
  });

  describe('refreshServerStatuses', () => {
    it('should update server status from getServerStatus', fakeAsync(() => {
      component.servers = [{ ...mockServer }];
      mcpServiceSpy.getServerStatus.and.returnValue(of({ id: 'test-server', status: 'RUNNING' as ServerStatus, pid: 123 }));
      component.refreshServerStatuses();
      tick();
      expect(component.servers[0].status).toBe('RUNNING');
    }));
  });

  describe('createServer', () => {
    it('should create STDIO server config and open dialog', () => {
      component.createServer('STDIO');
      expect(mcpServiceSpy.createDefaultConfig).toHaveBeenCalled();
      expect(component.showServerDialog).toBeTrue();
      expect(component.isEditing).toBeFalse();
    });

    it('should create REST server config', () => {
      component.createServer('REST');
      expect(mcpServiceSpy.createDefaultRestConfig).toHaveBeenCalled();
    });

    it('should create SSE server config', () => {
      component.createServer('SSE');
      expect(mcpServiceSpy.createDefaultSseConfig).toHaveBeenCalled();
    });
  });

  describe('editServer', () => {
    it('should copy server data and open dialog in edit mode', () => {
      component.editServer(mockServer);
      expect(component.isEditing).toBeTrue();
      expect(component.showServerDialog).toBeTrue();
      expect(component.editingServer).toBeTruthy();
      expect(component.editingServer!.id).toBe('test-server');
    });

    it('should deep copy args array', () => {
      component.editServer(mockServer);
      expect(component.editingServer!.args).toEqual(['server.js']);
      expect(component.editingServer!.args).not.toBe(mockServer.args);
    });
  });

  describe('saveServer - create', () => {
    it('should call addServer when not editing', fakeAsync(() => {
      component.editingServer = { ...defaultConfig, id: 'new-server' };
      component.isEditing = false;
      component.envKeys = [];
      component.envValues = [];
      component.saveServer();
      tick();
      expect(mcpServiceSpy.addServer).toHaveBeenCalled();
      expect(snackBarSpy.open).toHaveBeenCalled();
    }));
  });

  describe('saveServer - update', () => {
    it('should call updateServer when editing', fakeAsync(() => {
      component.editingServer = { ...mockServer };
      component.isEditing = true;
      component.envKeys = ['API_KEY'];
      component.envValues = ['newval'];
      component.saveServer();
      tick();
      expect(mcpServiceSpy.updateServer).toHaveBeenCalled();
    }));

    it('should handle save error', fakeAsync(() => {
      component.editingServer = { ...mockServer };
      component.isEditing = true;
      component.envKeys = [];
      component.envValues = [];
      mcpServiceSpy.updateServer.and.returnValue(throwError(() => ({ message: 'Save failed' })));
      component.saveServer();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalled();
    }));
  });

  describe('deleteServer', () => {
    it('should open confirm dialog before deleting', () => {
      component.deleteServer(mockServer);
      expect(dialogSpy.open).toHaveBeenCalled();
    });

    it('should delete when confirmed', fakeAsync(() => {
      dialogSpy.open.and.returnValue(mockConfirmDialogRef);
      component.deleteServer(mockServer);
      tick();
      expect(mcpServiceSpy.deleteServer).toHaveBeenCalledWith('test-server');
    }));

    it('should not delete when cancelled', fakeAsync(() => {
      dialogSpy.open.and.returnValue(mockDialogRef);
      component.deleteServer(mockServer);
      tick();
      expect(mcpServiceSpy.deleteServer).not.toHaveBeenCalled();
    }));
  });

  describe('startServer', () => {
    it('should call startServer and show success', fakeAsync(() => {
      component.startServer(mockServer);
      tick();
      expect(mcpServiceSpy.startServer).toHaveBeenCalledWith('test-server');
      expect(snackBarSpy.open).toHaveBeenCalled();
    }));

    it('should handle start error', fakeAsync(() => {
      mcpServiceSpy.startServer.and.returnValue(throwError(() => ({ message: 'fail' })));
      component.startServer(mockServer);
      tick();
      expect(snackBarSpy.open).toHaveBeenCalled();
    }));
  });

  describe('stopServer', () => {
    it('should call stopServer and show success', fakeAsync(() => {
      component.stopServer(mockServer);
      tick();
      expect(mcpServiceSpy.stopServer).toHaveBeenCalledWith('test-server');
      expect(snackBarSpy.open).toHaveBeenCalled();
    }));
  });

  describe('restartServer', () => {
    it('should call restartServer and show success', fakeAsync(() => {
      component.restartServer(mockServer);
      tick();
      expect(mcpServiceSpy.restartServer).toHaveBeenCalledWith('test-server');
    }));
  });

  describe('view mode switching', () => {
    it('should switch to JSON view', () => {
      component.switchToJsonView();
      expect(component.viewMode).toBe('json');
    });

    it('should switch to list view', () => {
      component.viewMode = 'json';
      component.switchToListView();
      expect(component.viewMode).toBe('list');
    });
  });

  describe('validateJson', () => {
    it('should call validateConfig and update jsonValid', fakeAsync(() => {
      mcpServiceSpy.validateConfig.and.returnValue(of({ valid: true }));
      component.validateJson();
      tick();
      expect(component.jsonValid).toBeTrue();
      expect(snackBarSpy.open).toHaveBeenCalled();
    }));

    it('should mark invalid on error response', fakeAsync(() => {
      mcpServiceSpy.validateConfig.and.returnValue(of({ valid: false, errors: ['Syntax error'] }));
      component.validateJson();
      tick();
      expect(component.jsonValid).toBeFalse();
      expect(component.jsonError).toContain('Syntax error');
    }));
  });

  describe('environment variables', () => {
    beforeEach(() => {
      component.editingServer = { ...mockServer, env: { KEY1: 'val1' } };
      component.initEnvArrays();
    });

    it('should initialize env arrays from server', () => {
      expect(component.envKeys).toEqual(['KEY1']);
      expect(component.envValues).toEqual(['val1']);
    });

    it('should add new env var', () => {
      component.addEnvVar();
      expect(component.envKeys.length).toBe(2);
      expect(component.envValues.length).toBe(2);
    });

    it('should remove env var at index', () => {
      component.removeEnvVar(0);
      expect(component.envKeys.length).toBe(0);
    });
  });

  describe('HTTP headers', () => {
    beforeEach(() => {
      component.editingServer = { ...defaultRestConfig, headers: { Authorization: 'Bearer xyz' } };
      component.initHeaderArrays();
    });

    it('should initialize header arrays from server', () => {
      expect(component.headerKeys).toEqual(['Authorization']);
      expect(component.headerValues).toEqual(['Bearer xyz']);
    });

    it('should add new header', () => {
      component.addHeader();
      expect(component.headerKeys.length).toBe(2);
    });

    it('should remove header at index', () => {
      component.removeHeader(0);
      expect(component.headerKeys.length).toBe(0);
    });
  });

  describe('args editing', () => {
    it('should get args as newline-separated string', () => {
      component.editingServer = { ...mockServer, args: ['arg1', 'arg2'] };
      expect(component.getArgsString()).toBe('arg1\narg2');
    });

    it('should set args from newline-separated string', () => {
      component.editingServer = { ...mockServer, args: [] };
      component.setArgsFromString('arg1\narg2\n');
      expect(component.editingServer!.args).toEqual(['arg1', 'arg2']);
    });

    it('should return empty string when no editing server', () => {
      component.editingServer = null;
      expect(component.getArgsString()).toBe('');
    });
  });

  describe('getStatusColor', () => {
    it('should return green for RUNNING', () => {
      expect(component.getStatusColor('RUNNING')).toBe('green');
    });

    it('should return red for ERROR', () => {
      expect(component.getStatusColor('ERROR')).toBe('red');
    });

    it('should return orange for STARTING', () => {
      expect(component.getStatusColor('STARTING')).toBe('orange');
    });

    it('should return gray for unknown', () => {
      expect(component.getStatusColor(undefined)).toBe('gray');
    });
  });

  describe('getStatusIcon', () => {
    it('should return play_circle for RUNNING', () => {
      expect(component.getStatusIcon('RUNNING')).toBe('play_circle');
    });

    it('should return error for ERROR', () => {
      expect(component.getStatusIcon('ERROR')).toBe('error');
    });
  });

  describe('getTransportIcon', () => {
    it('should return terminal for STDIO', () => {
      expect(component.getTransportIcon(mockServer)).toBe('terminal');
    });

    it('should return http for REST', () => {
      const restServer = { ...mockServer, transportType: 'REST' as any };
      mcpServiceSpy.isStdio.and.returnValue(false);
      mcpServiceSpy.isSse.and.returnValue(false);
      expect(component.getTransportIcon(restServer)).toBe('http');
    });
  });

  describe('getTransportLabel', () => {
    it('should return transportType', () => {
      expect(component.getTransportLabel(mockServer)).toBe('STDIO');
    });

    it('should return STDIO as default when no transportType', () => {
      const server = { ...mockServer, transportType: undefined as any };
      expect(component.getTransportLabel(server)).toBe('STDIO');
    });
  });

  describe('isStdioServer / isRestServer / isSseServer', () => {
    it('should delegate to mcpService.isStdio', () => {
      component.isStdioServer(mockServer);
      expect(mcpServiceSpy.isStdio).toHaveBeenCalledWith(mockServer);
    });

    it('should delegate to mcpService.isRest', () => {
      component.isRestServer(mockServer);
      expect(mcpServiceSpy.isRest).toHaveBeenCalledWith(mockServer);
    });

    it('should delegate to mcpService.isSse', () => {
      component.isSseServer(mockServer);
      expect(mcpServiceSpy.isSse).toHaveBeenCalledWith(mockServer);
    });
  });

  describe('cancelServerDialog', () => {
    it('should close dialog and clear editingServer', () => {
      component.showServerDialog = true;
      component.editingServer = mockServer;
      component.cancelServerDialog();
      expect(component.showServerDialog).toBeFalse();
      expect(component.editingServer).toBeNull();
    });
  });

  describe('cancelImportDialog', () => {
    it('should close import dialog and clear importJson', () => {
      component.showImportDialog = true;
      component.importJson = '{}';
      component.cancelImportDialog();
      expect(component.showImportDialog).toBeFalse();
      expect(component.importJson).toBe('');
    });
  });

  describe('openImportDialog', () => {
    it('should clear importJson and show dialog', () => {
      component.importJson = 'previous';
      component.openImportDialog();
      expect(component.importJson).toBe('');
      expect(component.showImportDialog).toBeTrue();
    });
  });

  describe('importConfig', () => {
    it('should not import if importJson is empty', fakeAsync(() => {
      component.importJson = '   ';
      component.importConfig();
      tick();
      expect(mcpServiceSpy.importConfig).not.toHaveBeenCalled();
    }));

    it('should call mcpService.importConfig with json', fakeAsync(() => {
      component.importJson = '{"mcpServers":{}}';
      component.importConfig();
      tick();
      expect(mcpServiceSpy.importConfig).toHaveBeenCalledWith('{"mcpServers":{}}');
    }));
  });

  describe('viewServerDetails', () => {
    it('should set viewingServer and open details dialog', () => {
      component.viewServerDetails(mockServer);
      expect(component.viewingServer).toBe(mockServer);
      expect(component.showDetailsDialog).toBeTrue();
    });

    it('should generate server JSON', () => {
      component.viewServerDetails(mockServer);
      expect(component.viewingServerJson).toContain('test-server');
    });
  });

  describe('cancelDetailsDialog', () => {
    it('should close dialog and clear viewingServer', () => {
      component.showDetailsDialog = true;
      component.viewingServer = mockServer;
      component.cancelDetailsDialog();
      expect(component.showDetailsDialog).toBeFalse();
      expect(component.viewingServer).toBeNull();
    });
  });

  describe('formatSingleServerJson', () => {
    it('should include server id and transportType', () => {
      const json = component.formatSingleServerJson(mockServer);
      const parsed = JSON.parse(json);
      expect(parsed.mcpServers['test-server']).toBeDefined();
      expect(parsed.mcpServers['test-server'].transportType).toBe('STDIO');
    });
  });

  describe('objectKeys', () => {
    it('should return keys of an object', () => {
      expect(component.objectKeys({ a: 1, b: 2 })).toEqual(['a', 'b']);
    });

    it('should return empty array for null', () => {
      expect(component.objectKeys(null)).toEqual([]);
    });
  });

  describe('ngOnDestroy', () => {
    it('should complete destroy$ subject', () => {
      spyOn(component['destroy$'], 'next');
      spyOn(component['destroy$'], 'complete');
      component.ngOnDestroy();
      expect(component['destroy$'].next).toHaveBeenCalled();
      expect(component['destroy$'].complete).toHaveBeenCalled();
    });
  });
});
