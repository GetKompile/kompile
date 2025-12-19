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
import { BehaviorSubject, Observable } from 'rxjs';
import {
  LocalAgentSession,
  LocalAgentMessage,
  ChatTabState,
  AgentChatSettings,
  STORAGE_KEYS,
  DEFAULT_AGENT_CHAT_SETTINGS,
  createEmptyTabState,
  createNewSession
} from '../models/api-models';

/**
 * Service for persisting chat sessions and messages to local storage.
 *
 * Features:
 * - Session persistence across browser refreshes
 * - Multi-tab state management
 * - Settings persistence
 * - Auto-save with debouncing
 */
@Injectable({
  providedIn: 'root'
})
export class ChatStorageService {

  // State subjects
  private sessionsSubject = new BehaviorSubject<LocalAgentSession[]>([]);
  private activeSessionSubject = new BehaviorSubject<LocalAgentSession | null>(null);
  private tabsSubject = new BehaviorSubject<ChatTabState[]>([]);
  private activeTabSubject = new BehaviorSubject<ChatTabState | null>(null);
  private settingsSubject = new BehaviorSubject<AgentChatSettings>(DEFAULT_AGENT_CHAT_SETTINGS);

  // Public observables
  sessions$ = this.sessionsSubject.asObservable();
  activeSession$ = this.activeSessionSubject.asObservable();
  tabs$ = this.tabsSubject.asObservable();
  activeTab$ = this.activeTabSubject.asObservable();
  settings$ = this.settingsSubject.asObservable();

  // Auto-save debounce timer
  private saveTimer: any = null;
  private readonly SAVE_DELAY = 500; // ms

  // Max items to store
  private readonly MAX_SESSIONS = 50;
  private readonly MAX_MESSAGES_PER_SESSION = 500;
  private readonly MAX_TABS = 10;

  constructor() {
    this.loadFromStorage();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // INITIALIZATION
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load all data from local storage.
   */
  private loadFromStorage(): void {
    try {
      // Load sessions
      const sessionsJson = localStorage.getItem(STORAGE_KEYS.SESSIONS);
      if (sessionsJson) {
        const sessions = JSON.parse(sessionsJson) as LocalAgentSession[];
        this.sessionsSubject.next(sessions);
      }

      // Load active session ID
      const activeSessionId = localStorage.getItem(STORAGE_KEYS.ACTIVE_SESSION);
      if (activeSessionId) {
        const sessions = this.sessionsSubject.value;
        const activeSession = sessions.find(s => s.id === activeSessionId);
        if (activeSession) {
          this.activeSessionSubject.next(activeSession);
        }
      }

      // Load tabs
      const tabsJson = localStorage.getItem(STORAGE_KEYS.TABS);
      if (tabsJson) {
        const tabs = JSON.parse(tabsJson) as ChatTabState[];
        this.tabsSubject.next(tabs);
      }

      // Load active tab ID
      const activeTabId = localStorage.getItem(STORAGE_KEYS.ACTIVE_TAB);
      if (activeTabId) {
        const tabs = this.tabsSubject.value;
        const activeTab = tabs.find(t => t.tabId === activeTabId);
        if (activeTab) {
          this.activeTabSubject.next(activeTab);
        }
      }

      // Load settings
      const settingsJson = localStorage.getItem(STORAGE_KEYS.SETTINGS);
      if (settingsJson) {
        const settings = JSON.parse(settingsJson) as AgentChatSettings;
        this.settingsSubject.next({ ...DEFAULT_AGENT_CHAT_SETTINGS, ...settings });
      }

      // Ensure at least one tab exists
      if (this.tabsSubject.value.length === 0) {
        this.createNewTab();
      }

      console.log('[ChatStorage] Loaded from storage:', {
        sessions: this.sessionsSubject.value.length,
        tabs: this.tabsSubject.value.length
      });
    } catch (error) {
      console.error('[ChatStorage] Failed to load from storage:', error);
      // Reset to defaults on error
      this.createNewTab();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SESSION MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get all sessions.
   */
  getSessions(): LocalAgentSession[] {
    return this.sessionsSubject.value;
  }

  /**
   * Get session by ID.
   */
  getSession(sessionId: string): LocalAgentSession | undefined {
    return this.sessionsSubject.value.find(s => s.id === sessionId);
  }

  /**
   * Get active session.
   */
  getActiveSession(): LocalAgentSession | null {
    return this.activeSessionSubject.value;
  }

  /**
   * Create a new session.
   */
  createSession(name?: string): LocalAgentSession {
    const session = createNewSession(name);
    const sessions = [...this.sessionsSubject.value, session];

    // Trim to max sessions (remove oldest archived first, then oldest)
    const trimmed = this.trimSessions(sessions);
    this.sessionsSubject.next(trimmed);
    this.scheduleSave();

    return session;
  }

  /**
   * Update a session.
   */
  updateSession(session: LocalAgentSession): void {
    session.updatedAt = new Date().toISOString();
    const sessions = this.sessionsSubject.value.map(s =>
      s.id === session.id ? session : s
    );
    this.sessionsSubject.next(sessions);

    // Update active session if it's the same
    if (this.activeSessionSubject.value?.id === session.id) {
      this.activeSessionSubject.next(session);
    }

    this.scheduleSave();
  }

  /**
   * Set active session.
   */
  setActiveSession(sessionId: string | null): void {
    if (sessionId) {
      const session = this.getSession(sessionId);
      this.activeSessionSubject.next(session || null);
      localStorage.setItem(STORAGE_KEYS.ACTIVE_SESSION, sessionId);
    } else {
      this.activeSessionSubject.next(null);
      localStorage.removeItem(STORAGE_KEYS.ACTIVE_SESSION);
    }
  }

  /**
   * Delete a session.
   */
  deleteSession(sessionId: string): void {
    const sessions = this.sessionsSubject.value.filter(s => s.id !== sessionId);
    this.sessionsSubject.next(sessions);

    // Clear active session if deleted
    if (this.activeSessionSubject.value?.id === sessionId) {
      this.activeSessionSubject.next(null);
      localStorage.removeItem(STORAGE_KEYS.ACTIVE_SESSION);
    }

    this.scheduleSave();
  }

  /**
   * Archive a session.
   */
  archiveSession(sessionId: string): void {
    const session = this.getSession(sessionId);
    if (session) {
      session.archived = true;
      this.updateSession(session);
    }
  }

  /**
   * Trim sessions to max count.
   */
  private trimSessions(sessions: LocalAgentSession[]): LocalAgentSession[] {
    if (sessions.length <= this.MAX_SESSIONS) {
      return sessions;
    }

    // Sort by updatedAt descending, keep archived at the end
    const sorted = sessions.sort((a, b) => {
      if (a.archived !== b.archived) {
        return a.archived ? 1 : -1;
      }
      return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime();
    });

    return sorted.slice(0, this.MAX_SESSIONS);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // MESSAGE MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Add message to session.
   */
  addMessage(sessionId: string, message: LocalAgentMessage): void {
    const session = this.getSession(sessionId);
    if (!session) {
      console.error('[ChatStorage] Session not found:', sessionId);
      return;
    }

    session.messages.push(message);
    session.messageCount = session.messages.length;

    // Trim messages if needed
    if (session.messages.length > this.MAX_MESSAGES_PER_SESSION) {
      session.messages = session.messages.slice(-this.MAX_MESSAGES_PER_SESSION);
    }

    this.updateSession(session);
  }

  /**
   * Update message in session.
   */
  updateMessage(sessionId: string, message: LocalAgentMessage): void {
    const session = this.getSession(sessionId);
    if (!session) return;

    const index = session.messages.findIndex(m => m.id === message.id);
    if (index !== -1) {
      session.messages[index] = message;
      this.updateSession(session);
    }
  }

  /**
   * Get messages for session.
   */
  getMessages(sessionId: string): LocalAgentMessage[] {
    const session = this.getSession(sessionId);
    return session?.messages || [];
  }

  /**
   * Clear messages in session.
   */
  clearMessages(sessionId: string): void {
    const session = this.getSession(sessionId);
    if (session) {
      session.messages = [];
      session.messageCount = 0;
      this.updateSession(session);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // TAB MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get all tabs.
   */
  getTabs(): ChatTabState[] {
    return this.tabsSubject.value;
  }

  /**
   * Get active tab.
   */
  getActiveTab(): ChatTabState | null {
    return this.activeTabSubject.value;
  }

  /**
   * Create a new tab.
   */
  createNewTab(): ChatTabState {
    const tab = createEmptyTabState();
    let tabs = [...this.tabsSubject.value, tab];

    // Trim to max tabs
    if (tabs.length > this.MAX_TABS) {
      tabs = tabs.slice(-this.MAX_TABS);
    }

    this.tabsSubject.next(tabs);
    this.setActiveTab(tab.tabId);
    this.scheduleSave();

    return tab;
  }

  /**
   * Update a tab.
   */
  updateTab(tab: ChatTabState): void {
    const tabs = this.tabsSubject.value.map(t =>
      t.tabId === tab.tabId ? tab : t
    );
    this.tabsSubject.next(tabs);

    // Update active tab if it's the same
    if (this.activeTabSubject.value?.tabId === tab.tabId) {
      this.activeTabSubject.next(tab);
    }

    this.scheduleSave();
  }

  /**
   * Set active tab.
   */
  setActiveTab(tabId: string): void {
    const tab = this.tabsSubject.value.find(t => t.tabId === tabId);
    if (tab) {
      this.activeTabSubject.next(tab);
      localStorage.setItem(STORAGE_KEYS.ACTIVE_TAB, tabId);
    }
  }

  /**
   * Close a tab.
   */
  closeTab(tabId: string): void {
    let tabs = this.tabsSubject.value.filter(t => t.tabId !== tabId);

    // Ensure at least one tab exists
    if (tabs.length === 0) {
      tabs = [createEmptyTabState()];
    }

    this.tabsSubject.next(tabs);

    // Switch to another tab if closing active
    if (this.activeTabSubject.value?.tabId === tabId) {
      this.setActiveTab(tabs[tabs.length - 1].tabId);
    }

    this.scheduleSave();
  }

  /**
   * Get tab by ID.
   */
  getTab(tabId: string): ChatTabState | undefined {
    return this.tabsSubject.value.find(t => t.tabId === tabId);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SETTINGS MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get current settings.
   */
  getSettings(): AgentChatSettings {
    return this.settingsSubject.value;
  }

  /**
   * Update settings.
   */
  updateSettings(settings: Partial<AgentChatSettings>): void {
    const current = this.settingsSubject.value;
    const updated = { ...current, ...settings };
    this.settingsSubject.next(updated);
    this.scheduleSave();
  }

  /**
   * Reset settings to defaults.
   */
  resetSettings(): void {
    this.settingsSubject.next(DEFAULT_AGENT_CHAT_SETTINGS);
    this.scheduleSave();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // PERSISTENCE
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Schedule save with debouncing.
   */
  private scheduleSave(): void {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
    }
    this.saveTimer = setTimeout(() => this.saveToStorage(), this.SAVE_DELAY);
  }

  /**
   * Save all data to local storage.
   */
  private saveToStorage(): void {
    try {
      // Save sessions
      const sessions = this.sessionsSubject.value;
      localStorage.setItem(STORAGE_KEYS.SESSIONS, JSON.stringify(sessions));

      // Save tabs (without transient state)
      const tabs = this.tabsSubject.value.map(tab => ({
        ...tab,
        streamingContent: '', // Don't persist streaming content
        isStreaming: false,
        isLoading: false
      }));
      localStorage.setItem(STORAGE_KEYS.TABS, JSON.stringify(tabs));

      // Save settings
      const settings = this.settingsSubject.value;
      localStorage.setItem(STORAGE_KEYS.SETTINGS, JSON.stringify(settings));

      console.log('[ChatStorage] Saved to storage');
    } catch (error) {
      console.error('[ChatStorage] Failed to save to storage:', error);
    }
  }

  /**
   * Force immediate save.
   */
  saveNow(): void {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
    this.saveToStorage();
  }

  /**
   * Clear all stored data.
   */
  clearAll(): void {
    localStorage.removeItem(STORAGE_KEYS.SESSIONS);
    localStorage.removeItem(STORAGE_KEYS.ACTIVE_SESSION);
    localStorage.removeItem(STORAGE_KEYS.TABS);
    localStorage.removeItem(STORAGE_KEYS.ACTIVE_TAB);
    localStorage.removeItem(STORAGE_KEYS.SETTINGS);

    this.sessionsSubject.next([]);
    this.activeSessionSubject.next(null);
    this.settingsSubject.next(DEFAULT_AGENT_CHAT_SETTINGS);

    // Create default tab
    this.tabsSubject.next([]);
    this.createNewTab();

    console.log('[ChatStorage] Cleared all data');
  }

  /**
   * Export all data as JSON.
   */
  exportData(): string {
    const data = {
      sessions: this.sessionsSubject.value,
      settings: this.settingsSubject.value,
      exportedAt: new Date().toISOString()
    };
    return JSON.stringify(data, null, 2);
  }

  /**
   * Import data from JSON.
   */
  importData(json: string): boolean {
    try {
      const data = JSON.parse(json);

      if (data.sessions) {
        this.sessionsSubject.next(data.sessions);
      }

      if (data.settings) {
        this.settingsSubject.next({ ...DEFAULT_AGENT_CHAT_SETTINGS, ...data.settings });
      }

      this.saveNow();
      return true;
    } catch (error) {
      console.error('[ChatStorage] Failed to import data:', error);
      return false;
    }
  }
}
