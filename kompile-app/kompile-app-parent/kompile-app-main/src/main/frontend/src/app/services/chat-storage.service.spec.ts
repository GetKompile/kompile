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

import { TestBed } from '@angular/core/testing';

import { ChatStorageService } from './chat-storage.service';
import {
  AgentChatSettings,
  ChatTabState,
  DEFAULT_AGENT_CHAT_SETTINGS,
  LocalAgentMessage,
  LocalAgentSession,
  STORAGE_KEYS
} from '../models/api-models';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeMessage(overrides: Partial<LocalAgentMessage> = {}): LocalAgentMessage {
  return {
    id: 'msg-' + Math.random().toString(36).substr(2, 9),
    sessionId: 'session-001',
    role: 'USER',
    content: 'Hello, world!',
    timestamp: new Date().toISOString(),
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────

describe('ChatStorageService', () => {
  let service: ChatStorageService;

  beforeEach(() => {
    // Clear localStorage before each test so the service initialises cleanly
    localStorage.clear();

    TestBed.configureTestingModule({
      providers: [ChatStorageService]
    });

    service = TestBed.inject(ChatStorageService);
  });

  afterEach(() => {
    localStorage.clear();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Instantiation
  // ─────────────────────────────────────────────────────────────────────────────

  describe('constructor', () => {
    it('should be created', () => {
      expect(service).toBeTruthy();
    });

    it('should initialise with an empty session list', () => {
      expect(service.getSessions()).toEqual([]);
    });

    it('should create at least one default tab on construction', () => {
      expect(service.getTabs().length).toBeGreaterThanOrEqual(1);
    });

    it('should load sessions from localStorage if they exist', () => {
      const storedSession: LocalAgentSession = {
        id: 'session-stored',
        name: 'Persisted Session',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        messages: [],
        archived: false,
        totalTokens: 0,
        messageCount: 0
      };
      localStorage.setItem(STORAGE_KEYS.SESSIONS, JSON.stringify([storedSession]));

      // Re-create service to trigger loadFromStorage
      TestBed.resetTestingModule();
      localStorage.setItem(STORAGE_KEYS.SESSIONS, JSON.stringify([storedSession]));
      TestBed.configureTestingModule({ providers: [ChatStorageService] });
      const freshService = TestBed.inject(ChatStorageService);

      expect(freshService.getSessions().length).toBe(1);
      expect(freshService.getSessions()[0].id).toBe('session-stored');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. createSession()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('createSession()', () => {
    it('should create a session and add it to the session list', () => {
      const session = service.createSession('My Session');

      expect(session).toBeTruthy();
      expect(session.name).toBe('My Session');
      expect(session.id).toBeTruthy();
      expect(service.getSessions().length).toBe(1);
    });

    it('should generate a name with date when no name is provided', () => {
      const session = service.createSession();
      expect(session.name).toBeTruthy();
      expect(session.name.length).toBeGreaterThan(0);
    });

    it('should initialise session with empty messages and zero tokens', () => {
      const session = service.createSession('Fresh');
      expect(session.messages).toEqual([]);
      expect(session.messageCount).toBe(0);
      expect(session.totalTokens).toBe(0);
      expect(session.archived).toBeFalse();
    });

    it('should update the sessions$ BehaviorSubject', () => {
      const emissions: LocalAgentSession[][] = [];
      service.sessions$.subscribe(s => emissions.push(s));

      service.createSession('Update Test');

      const last = emissions[emissions.length - 1];
      expect(last.length).toBe(1);
    });

    it('should not exceed MAX_SESSIONS (50) when creating many sessions', () => {
      for (let i = 0; i < 55; i++) {
        service.createSession(`Session ${i}`);
      }
      expect(service.getSessions().length).toBeLessThanOrEqual(50);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. getSession()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getSession()', () => {
    it('should return the session with the matching ID', () => {
      const session = service.createSession('Find Me');
      const found = service.getSession(session.id);
      expect(found).toBeTruthy();
      expect(found?.id).toBe(session.id);
      expect(found?.name).toBe('Find Me');
    });

    it('should return undefined for a non-existent session ID', () => {
      expect(service.getSession('does-not-exist')).toBeUndefined();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. getSessions()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getSessions()', () => {
    it('should return all sessions', () => {
      service.createSession('A');
      service.createSession('B');
      service.createSession('C');
      expect(service.getSessions().length).toBe(3);
    });

    it('should return empty array when no sessions exist', () => {
      expect(service.getSessions()).toEqual([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. updateSession()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('updateSession()', () => {
    it('should update session name in the session list', () => {
      const session = service.createSession('Original Name');
      session.name = 'Updated Name';
      service.updateSession(session);

      const updated = service.getSession(session.id);
      expect(updated?.name).toBe('Updated Name');
    });

    it('should update the updatedAt timestamp', () => {
      const session = service.createSession('Timestamped');
      const originalTime = session.updatedAt;

      // Simulate a small time delay so the timestamp changes
      session.name = 'Modified';
      service.updateSession(session);

      const updated = service.getSession(session.id);
      expect(updated?.updatedAt).toBeDefined();
    });

    it('should also update activeSession$ when the active session is modified', () => {
      const session = service.createSession('Active One');
      service.setActiveSession(session.id);

      session.name = 'Active One — Renamed';
      service.updateSession(session);

      let emittedSession: LocalAgentSession | null = null;
      service.activeSession$.subscribe(s => (emittedSession = s));

      expect((emittedSession as LocalAgentSession | null)?.name).toBe('Active One — Renamed');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. deleteSession()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteSession()', () => {
    it('should remove session from the session list', () => {
      const session = service.createSession('Delete Me');
      service.deleteSession(session.id);
      expect(service.getSession(session.id)).toBeUndefined();
      expect(service.getSessions().length).toBe(0);
    });

    it('should clear activeSession$ when the active session is deleted', () => {
      const session = service.createSession('Active');
      service.setActiveSession(session.id);

      let activeSession: LocalAgentSession | null = session;
      service.activeSession$.subscribe(s => (activeSession = s));

      service.deleteSession(session.id);
      expect(activeSession).toBeNull();
    });

    it('should not affect other sessions when one is deleted', () => {
      service.createSession('Keep A');
      const toDelete = service.createSession('Delete');
      service.createSession('Keep B');

      service.deleteSession(toDelete.id);

      expect(service.getSessions().length).toBe(2);
      expect(service.getSessions().every(s => s.id !== toDelete.id)).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. archiveSession()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('archiveSession()', () => {
    it('should set archived=true on the specified session', () => {
      const session = service.createSession('Archive Me');
      expect(session.archived).toBeFalse();

      service.archiveSession(session.id);

      const updated = service.getSession(session.id);
      expect(updated?.archived).toBeTrue();
    });

    it('should do nothing for an unknown session ID', () => {
      // Should not throw
      expect(() => service.archiveSession('nonexistent')).not.toThrow();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. addMessage()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('addMessage()', () => {
    it('should add a message to the specified session', () => {
      const session = service.createSession();
      const msg = makeMessage({ sessionId: session.id, content: 'Test message' });

      service.addMessage(session.id, msg);

      const messages = service.getMessages(session.id);
      expect(messages.length).toBe(1);
      expect(messages[0].content).toBe('Test message');
    });

    it('should update messageCount after adding a message', () => {
      const session = service.createSession();
      service.addMessage(session.id, makeMessage({ sessionId: session.id }));
      service.addMessage(session.id, makeMessage({ sessionId: session.id }));

      const updated = service.getSession(session.id);
      expect(updated?.messageCount).toBe(2);
    });

    it('should not exceed MAX_MESSAGES_PER_SESSION (500)', () => {
      const session = service.createSession();
      for (let i = 0; i < 510; i++) {
        service.addMessage(session.id, makeMessage({ sessionId: session.id, id: `msg-${i}` }));
      }

      const messages = service.getMessages(session.id);
      expect(messages.length).toBeLessThanOrEqual(500);
    });

    it('should log an error for a non-existent session and not throw', () => {
      spyOn(console, 'error');
      const msg = makeMessage({ sessionId: 'ghost-session' });
      expect(() => service.addMessage('ghost-session', msg)).not.toThrow();
      expect(console.error).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. updateMessage()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('updateMessage()', () => {
    it('should update an existing message in the session', () => {
      const session = service.createSession();
      const msg = makeMessage({ sessionId: session.id, content: 'Original' });
      service.addMessage(session.id, msg);

      const updatedMsg = { ...msg, content: 'Updated content' };
      service.updateMessage(session.id, updatedMsg);

      const messages = service.getMessages(session.id);
      expect(messages[0].content).toBe('Updated content');
    });

    it('should not throw when updating message in unknown session', () => {
      const msg = makeMessage({ sessionId: 'ghost', id: 'msg-ghost' });
      expect(() => service.updateMessage('ghost', msg)).not.toThrow();
    });

    it('should not modify other messages when updating one', () => {
      const session = service.createSession();
      const msg1 = makeMessage({ sessionId: session.id, id: 'msg-1', content: 'First' });
      const msg2 = makeMessage({ sessionId: session.id, id: 'msg-2', content: 'Second' });
      service.addMessage(session.id, msg1);
      service.addMessage(session.id, msg2);

      service.updateMessage(session.id, { ...msg1, content: 'First — Updated' });

      const messages = service.getMessages(session.id);
      expect(messages.find(m => m.id === 'msg-1')?.content).toBe('First — Updated');
      expect(messages.find(m => m.id === 'msg-2')?.content).toBe('Second');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. getMessages()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getMessages()', () => {
    it('should return all messages for a session', () => {
      const session = service.createSession();
      service.addMessage(session.id, makeMessage({ sessionId: session.id, id: 'msg-a', content: 'A' }));
      service.addMessage(session.id, makeMessage({ sessionId: session.id, id: 'msg-b', content: 'B' }));

      const messages = service.getMessages(session.id);
      expect(messages.length).toBe(2);
    });

    it('should return empty array for unknown session', () => {
      expect(service.getMessages('nonexistent')).toEqual([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. clearMessages()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('clearMessages()', () => {
    it('should remove all messages from a session', () => {
      const session = service.createSession();
      service.addMessage(session.id, makeMessage({ sessionId: session.id }));
      service.addMessage(session.id, makeMessage({ sessionId: session.id }));

      service.clearMessages(session.id);

      expect(service.getMessages(session.id)).toEqual([]);
    });

    it('should reset messageCount to zero', () => {
      const session = service.createSession();
      service.addMessage(session.id, makeMessage({ sessionId: session.id }));

      service.clearMessages(session.id);

      const updated = service.getSession(session.id);
      expect(updated?.messageCount).toBe(0);
    });

    it('should not throw for unknown session', () => {
      expect(() => service.clearMessages('unknown-session')).not.toThrow();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. createNewTab()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('createNewTab()', () => {
    it('should create a new tab and add it to the tab list', () => {
      const initialCount = service.getTabs().length;
      const tab = service.createNewTab();

      expect(tab).toBeTruthy();
      expect(tab.tabId).toBeTruthy();
      expect(service.getTabs().length).toBe(initialCount + 1);
    });

    it('should set the new tab as active', () => {
      const tab = service.createNewTab();
      expect(service.getActiveTab()?.tabId).toBe(tab.tabId);
    });

    it('should initialise tab with default state', () => {
      const tab = service.createNewTab();
      expect(tab.session).toBeNull();
      expect(tab.messages).toEqual([]);
      expect(tab.isStreaming).toBeFalse();
      expect(tab.isLoading).toBeFalse();
      expect(tab.displayName).toBe('New Chat');
    });

    it('should not exceed MAX_TABS (10)', () => {
      // Clear any existing tabs
      (service as any).tabsSubject.next([]);

      for (let i = 0; i < 15; i++) {
        service.createNewTab();
      }
      expect(service.getTabs().length).toBeLessThanOrEqual(10);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. getTabs()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getTabs()', () => {
    it('should return all current tabs', () => {
      const initialCount = service.getTabs().length;
      service.createNewTab();
      expect(service.getTabs().length).toBe(initialCount + 1);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. updateTab()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('updateTab()', () => {
    it('should update tab properties in the tab list', () => {
      const tab = service.createNewTab();
      const updatedTab: ChatTabState = { ...tab, displayName: 'Renamed Tab', userInput: 'Hello' };

      service.updateTab(updatedTab);

      const found = service.getTab(tab.tabId);
      expect(found?.displayName).toBe('Renamed Tab');
      expect(found?.userInput).toBe('Hello');
    });

    it('should also update activeTab$ when the active tab is modified', () => {
      const tab = service.createNewTab();
      service.setActiveTab(tab.tabId);

      const updatedTab: ChatTabState = { ...tab, displayName: 'Active Renamed' };
      service.updateTab(updatedTab);

      expect(service.getActiveTab()?.displayName).toBe('Active Renamed');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. setActiveTab()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('setActiveTab()', () => {
    it('should set the active tab by ID', () => {
      const tab1 = service.createNewTab();
      const tab2 = service.createNewTab();

      service.setActiveTab(tab1.tabId);

      expect(service.getActiveTab()?.tabId).toBe(tab1.tabId);
    });

    it('should persist active tab ID to localStorage', () => {
      const tab = service.createNewTab();
      service.setActiveTab(tab.tabId);

      expect(localStorage.getItem(STORAGE_KEYS.ACTIVE_TAB)).toBe(tab.tabId);
    });

    it('should not change active tab when an unknown tabId is provided', () => {
      const tab = service.createNewTab();
      service.setActiveTab(tab.tabId);

      service.setActiveTab('nonexistent-tab-id');

      // Active tab should remain unchanged (unknown ID is silently ignored)
      expect(service.getActiveTab()?.tabId).toBe(tab.tabId);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 15. closeTab()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('closeTab()', () => {
    it('should remove the specified tab from the tab list', () => {
      (service as any).tabsSubject.next([]);
      const tab1 = service.createNewTab();
      const tab2 = service.createNewTab();

      service.closeTab(tab1.tabId);

      expect(service.getTabs().some(t => t.tabId === tab1.tabId)).toBeFalse();
      expect(service.getTabs().some(t => t.tabId === tab2.tabId)).toBeTrue();
    });

    it('should ensure at least one tab remains after closing', () => {
      // Clear all tabs first
      (service as any).tabsSubject.next([]);
      const onlyTab = service.createNewTab();

      service.closeTab(onlyTab.tabId);

      expect(service.getTabs().length).toBeGreaterThanOrEqual(1);
    });

    it('should switch active tab when the active tab is closed', () => {
      (service as any).tabsSubject.next([]);
      const tab1 = service.createNewTab();
      const tab2 = service.createNewTab();
      service.setActiveTab(tab2.tabId);

      service.closeTab(tab2.tabId);

      // Should have switched to tab1
      expect(service.getActiveTab()?.tabId).toBe(tab1.tabId);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 16. getSettings() / updateSettings() / resetSettings()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getSettings()', () => {
    it('should return default settings initially', () => {
      const settings = service.getSettings();
      expect(settings.maxHistoryMessages).toBe(DEFAULT_AGENT_CHAT_SETTINGS.maxHistoryMessages);
      expect(settings.autoScroll).toBe(DEFAULT_AGENT_CHAT_SETTINGS.autoScroll);
      expect(settings.enableRag).toBe(DEFAULT_AGENT_CHAT_SETTINGS.enableRag);
    });
  });

  describe('updateSettings()', () => {
    it('should partially update settings', () => {
      service.updateSettings({ maxHistoryMessages: 50, autoScroll: false });

      const settings = service.getSettings();
      expect(settings.maxHistoryMessages).toBe(50);
      expect(settings.autoScroll).toBeFalse();
    });

    it('should preserve unchanged settings after partial update', () => {
      service.updateSettings({ maxHistoryMessages: 100 });

      const settings = service.getSettings();
      // These should still be defaults
      expect(settings.enableRag).toBe(DEFAULT_AGENT_CHAT_SETTINGS.enableRag);
      expect(settings.theme).toBe(DEFAULT_AGENT_CHAT_SETTINGS.theme);
    });

    it('should update settings$ BehaviorSubject', () => {
      const emissions: AgentChatSettings[] = [];
      service.settings$.subscribe(s => emissions.push(s));

      service.updateSettings({ maxHistoryMessages: 75 });

      const last = emissions[emissions.length - 1];
      expect(last.maxHistoryMessages).toBe(75);
    });

    it('should support enabling RAG and setting threshold', () => {
      service.updateSettings({ enableRag: true, ragSimilarityThreshold: 0.75 });

      const settings = service.getSettings();
      expect(settings.enableRag).toBeTrue();
      expect(settings.ragSimilarityThreshold).toBe(0.75);
    });
  });

  describe('resetSettings()', () => {
    it('should restore all settings to defaults', () => {
      service.updateSettings({ maxHistoryMessages: 99, autoScroll: false, enableRag: true });
      service.resetSettings();

      const settings = service.getSettings();
      expect(settings.maxHistoryMessages).toBe(DEFAULT_AGENT_CHAT_SETTINGS.maxHistoryMessages);
      expect(settings.autoScroll).toBe(DEFAULT_AGENT_CHAT_SETTINGS.autoScroll);
      expect(settings.enableRag).toBe(DEFAULT_AGENT_CHAT_SETTINGS.enableRag);
    });

    it('should emit default settings via settings$ after reset', () => {
      service.updateSettings({ maxHistoryMessages: 99 });

      let lastSettings: AgentChatSettings = service.getSettings();
      service.settings$.subscribe(s => (lastSettings = s));

      service.resetSettings();

      expect(lastSettings.maxHistoryMessages).toBe(DEFAULT_AGENT_CHAT_SETTINGS.maxHistoryMessages);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 17. exportData() / importData()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('exportData()', () => {
    it('should return a JSON string containing sessions and settings', () => {
      service.createSession('Export Session');
      const json = service.exportData();

      expect(() => JSON.parse(json)).not.toThrow();

      const data = JSON.parse(json);
      expect(data.sessions).toBeDefined();
      expect(data.settings).toBeDefined();
      expect(data.exportedAt).toBeDefined();
    });

    it('should include all current sessions in export', () => {
      service.createSession('Session A');
      service.createSession('Session B');

      const data = JSON.parse(service.exportData());
      expect(data.sessions.length).toBe(2);
    });

    it('should include current settings in export', () => {
      service.updateSettings({ maxHistoryMessages: 42 });

      const data = JSON.parse(service.exportData());
      expect(data.settings.maxHistoryMessages).toBe(42);
    });
  });

  describe('importData()', () => {
    it('should import sessions from JSON and return true', () => {
      const importPayload = {
        sessions: [
          {
            id: 'imported-session-1',
            name: 'Imported',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            messages: [],
            archived: false,
            totalTokens: 0,
            messageCount: 0
          }
        ],
        settings: { maxHistoryMessages: 30 }
      };

      const result = service.importData(JSON.stringify(importPayload));

      expect(result).toBeTrue();
      expect(service.getSessions().length).toBe(1);
      expect(service.getSessions()[0].id).toBe('imported-session-1');
    });

    it('should apply imported settings', () => {
      const importPayload = {
        sessions: [],
        settings: { maxHistoryMessages: 77, enableRag: true }
      };

      service.importData(JSON.stringify(importPayload));

      expect(service.getSettings().maxHistoryMessages).toBe(77);
      expect(service.getSettings().enableRag).toBeTrue();
    });

    it('should merge imported settings with defaults', () => {
      const importPayload = {
        sessions: [],
        settings: { maxHistoryMessages: 15 }
        // Other settings not included
      };

      service.importData(JSON.stringify(importPayload));

      // Explicit value should be imported
      expect(service.getSettings().maxHistoryMessages).toBe(15);
      // Unspecified values should fall back to defaults
      expect(service.getSettings().autoScroll).toBe(DEFAULT_AGENT_CHAT_SETTINGS.autoScroll);
    });

    it('should return false when JSON is malformed', () => {
      const result = service.importData('{ this is not valid json }');
      expect(result).toBeFalse();
    });

    it('should not throw on malformed JSON — should handle error gracefully', () => {
      expect(() => service.importData('INVALID')).not.toThrow();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 18. clearAll()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('clearAll()', () => {
    it('should remove all sessions', () => {
      service.createSession('A');
      service.createSession('B');

      service.clearAll();

      expect(service.getSessions()).toEqual([]);
    });

    it('should clear the active session', () => {
      const session = service.createSession('Active');
      service.setActiveSession(session.id);

      let activeSession: LocalAgentSession | null = session;
      service.activeSession$.subscribe(s => (activeSession = s));

      service.clearAll();

      expect(activeSession).toBeNull();
    });

    it('should reset settings to defaults', () => {
      service.updateSettings({ maxHistoryMessages: 99 });
      service.clearAll();

      expect(service.getSettings().maxHistoryMessages).toBe(DEFAULT_AGENT_CHAT_SETTINGS.maxHistoryMessages);
    });

    it('should ensure at least one tab exists after clearAll', () => {
      service.clearAll();
      expect(service.getTabs().length).toBeGreaterThanOrEqual(1);
    });

    it('should remove all relevant keys from localStorage', () => {
      service.createSession('Persist me');
      service.saveNow(); // force immediate write

      service.clearAll();

      expect(localStorage.getItem(STORAGE_KEYS.SESSIONS)).toBeNull();
      expect(localStorage.getItem(STORAGE_KEYS.ACTIVE_SESSION)).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 19. sessions$ / activeSession$ BehaviorSubjects
  // ─────────────────────────────────────────────────────────────────────────────

  describe('sessions$', () => {
    it('should emit empty array initially (no stored data)', () => {
      const emissions: LocalAgentSession[][] = [];
      service.sessions$.subscribe(s => emissions.push(s));
      expect(emissions[0]).toEqual([]);
    });

    it('should emit updated list after createSession', () => {
      const emissions: LocalAgentSession[][] = [];
      service.sessions$.subscribe(s => emissions.push(s));

      service.createSession('Reactive Session');

      const last = emissions[emissions.length - 1];
      expect(last.length).toBe(1);
      expect(last[0].name).toBe('Reactive Session');
    });
  });

  describe('activeSession$', () => {
    it('should emit null initially', (done) => {
      service.activeSession$.subscribe(session => {
        expect(session).toBeNull();
        done();
      });
    });

    it('should emit the session after setActiveSession is called', () => {
      const session = service.createSession('Will be active');
      let emitted: LocalAgentSession | null = null;
      service.activeSession$.subscribe(s => (emitted = s));

      service.setActiveSession(session.id);

      expect((emitted as LocalAgentSession | null)?.id).toBe(session.id);
    });

    it('should emit null after setActiveSession(null) is called', () => {
      const session = service.createSession('Active');
      service.setActiveSession(session.id);

      let emitted: LocalAgentSession | null = session;
      service.activeSession$.subscribe(s => (emitted = s));

      service.setActiveSession(null);

      expect(emitted).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 20. Limits enforcement
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Limits', () => {
    it('MAX_SESSIONS=50: should not store more than 50 sessions', () => {
      for (let i = 0; i < 55; i++) {
        service.createSession(`Session ${i}`);
      }
      expect(service.getSessions().length).toBe(50);
    });

    it('MAX_MESSAGES_PER_SESSION=500: should trim messages to 500 when exceeded', () => {
      const session = service.createSession('Lots of messages');
      for (let i = 0; i < 510; i++) {
        service.addMessage(session.id, makeMessage({ sessionId: session.id, id: `msg-${i}` }));
      }
      expect(service.getMessages(session.id).length).toBe(500);
    });

    it('MAX_TABS=10: should not store more than 10 tabs', () => {
      // Reset tabs to start from 0
      (service as any).tabsSubject.next([]);
      for (let i = 0; i < 15; i++) {
        service.createNewTab();
      }
      expect(service.getTabs().length).toBe(10);
    });
  });
});
