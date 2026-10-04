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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { of } from 'rxjs';

import { FolderFilesDialogComponent } from './folder-files-dialog.component';
import { ChatFolder, ChatSessionDto, FolderFile } from '@shared/models/api-models';
import { ChatHistoryService } from '@shared/services/chat-history.service';
import { FolderService } from '@shared/services/folder.service';

describe('FolderFilesDialogComponent tab bar', () => {
  let fixture: ComponentFixture<FolderFilesDialogComponent>;
  let component: FolderFilesDialogComponent;
  let host: HTMLElement;
  let themeStyle: HTMLStyleElement | null = null;

  const files = [{
    fileId: 'file-1',
    fileName: 'notes.md',
    storedPath: '/data/folders/folder-1/notes.md',
    fileSize: 12,
    uploadedAt: '2026-10-01T09:00:00Z'
  }] as FolderFile[];
  const sessions = [{
    sessionId: 'session-1',
    title: 'Planning',
    messageCount: 3,
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-02T09:00:00Z'
  }] as ChatSessionDto[];

  beforeEach(async () => {
    const folderServiceSpy = jasmine.createSpyObj('FolderService', ['getFolderFiles', 'getFolderSessions']);
    folderServiceSpy.getFolderFiles.and.returnValue(of(files));
    folderServiceSpy.getFolderSessions.and.returnValue(of(sessions));
    const chatHistoryServiceSpy = jasmine.createSpyObj('ChatHistoryService', ['getSessions']);
    chatHistoryServiceSpy.getSessions.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      // The real tabs module: the tab bar's roles, roving tabindex and keyboard
      // handling are what these tests check.
      imports: [FormsModule, NoopAnimationsModule, MatButtonModule, MatDialogModule, MatIconModule, MatTabsModule],
      declarations: [FolderFilesDialogComponent],
      providers: [
        { provide: MatDialogRef, useValue: jasmine.createSpyObj('MatDialogRef', ['close']) },
        { provide: MAT_DIALOG_DATA, useValue: { folder: { folderId: 'folder-1', name: 'Research' } as ChatFolder } },
        { provide: FolderService, useValue: folderServiceSpy },
        { provide: ChatHistoryService, useValue: chatHistoryServiceSpy },
        { provide: MatDialog, useValue: jasmine.createSpyObj('MatDialog', ['open']) }
      ],
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(FolderFilesDialogComponent);
    component = fixture.componentInstance;
    host = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => {
    themeStyle?.remove();
    themeStyle = null;
    document.body.classList.remove('dark-theme');
    document.body.style.removeProperty('--color-primary');
    document.body.style.removeProperty('--text-secondary');
  });

  function tabs(): HTMLElement[] {
    return Array.from(host.querySelectorAll<HTMLElement>('nav [role="tab"]'));
  }

  function panel(): HTMLElement {
    return host.querySelector<HTMLElement>('mat-tab-nav-panel')!;
  }

  function label(tab: HTMLElement): HTMLElement {
    return tab.querySelector<HTMLElement>('.mdc-tab__text-label')!;
  }

  function rawButtons(): string[] {
    return Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .filter(button => !button.classList.contains('mat-mdc-button-base'))
      .map(button => (button.textContent ?? '').trim());
  }

  it('renders Files and Chats as a Material tab bar over one tab panel', () => {
    const tabList = host.querySelector<HTMLElement>('nav.mat-mdc-tab-nav-bar');
    expect(tabList).withContext('mat-tab-nav-bar').not.toBeNull();
    expect(tabList!.getAttribute('role')).toBe('tablist');
    expect(tabList!.getAttribute('aria-label')).toBe('Folder contents');

    const [filesTab, chatsTab] = tabs();
    expect(tabs().length).toBe(2);
    expect(tabs().every(tab => tab.classList.contains('mat-mdc-tab-link'))).toBeTrue();
    expect(filesTab.textContent).toContain('Files (1)');
    expect(chatsTab.textContent).toContain('Chats (1)');
    expect(filesTab.getAttribute('aria-selected')).toBe('true');
    expect(chatsTab.getAttribute('aria-selected')).toBe('false');

    expect(panel()).withContext('mat-tab-nav-panel').not.toBeNull();
    expect(panel().getAttribute('role')).toBe('tabpanel');
    expect(panel().getAttribute('aria-labelledby')).toBe(filesTab.id);
    expect(panel().querySelector('.file-list')).withContext('files tab content').not.toBeNull();
    expect(rawButtons()).toEqual([]);
  });

  it('switches tabs by click and from the keyboard', () => {
    const [filesTab, chatsTab] = tabs();
    expect(filesTab.getAttribute('tabindex')).withContext('the active tab is the tab stop').toBe('0');
    expect(chatsTab.getAttribute('tabindex')).toBe('-1');

    chatsTab.click();
    fixture.detectChanges();
    expect(component.activeTab).toBe('chats');
    expect(chatsTab.getAttribute('aria-selected')).toBe('true');
    expect(panel().getAttribute('aria-labelledby')).toBe(chatsTab.id);
    expect(panel().querySelector('.session-list')).withContext('chats tab content').not.toBeNull();
    expect(panel().querySelector('.file-list')).toBeNull();
    expect(rawButtons()).toEqual([]);

    // A tab link has no href, so Enter only activates it through the tab panel wiring.
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    Object.defineProperty(enter, 'keyCode', { get: () => 13 });
    filesTab.dispatchEvent(enter);
    fixture.detectChanges();
    expect(component.activeTab).toBe('files');
    expect(filesTab.getAttribute('aria-selected')).toBe('true');
  });

  it('colors the tabs from the chat palette over the Material theme, light and dark', () => {
    document.body.style.setProperty('--color-primary', 'rgb(1, 2, 3)');
    document.body.style.setProperty('--text-secondary', 'rgb(4, 5, 6)');
    // The Material tabs theme declares its color tokens on the nav bar itself; this mirrors
    // the dark theme's indigo declaration (styles.scss) so the override is tested against it.
    themeStyle = document.createElement('style');
    themeStyle.textContent = 'body.dark-theme .mat-mdc-tab-nav-bar {'
      + ' --mat-tab-header-active-label-text-color: rgb(63, 81, 181);'
      + ' --mat-tab-header-inactive-label-text-color: rgba(255, 255, 255, 0.6);'
      + ' --mdc-tab-indicator-active-indicator-color: rgb(63, 81, 181); }';
    document.head.appendChild(themeStyle);

    const [filesTab, chatsTab] = tabs();
    const indicator = filesTab.querySelector<HTMLElement>('.mdc-tab-indicator__content--underline')!;
    for (const dark of [false, true]) {
      document.body.classList.toggle('dark-theme', dark);
      const theme = dark ? 'dark' : 'light';
      expect(getComputedStyle(label(filesTab)).color).withContext(`${theme} active label`).toBe('rgb(1, 2, 3)');
      expect(getComputedStyle(label(chatsTab)).color).withContext(`${theme} inactive label`).toBe('rgb(4, 5, 6)');
      expect(getComputedStyle(indicator).borderTopColor).withContext(`${theme} indicator`).toBe('rgb(1, 2, 3)');
    }
  });
});
