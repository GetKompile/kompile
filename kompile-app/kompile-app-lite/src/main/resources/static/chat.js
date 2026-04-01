// Kompile Lite Chat UI
(function() {
    'use strict';

    const API_BASE = window.location.origin + '/api/lite';
    let sessionId = localStorage.getItem('kompile-lite-session') || generateSessionId();
    let isStreaming = false;

    // DOM elements
    const messagesEl = document.getElementById('messages');
    const inputEl = document.getElementById('chat-input');
    const sendBtn = document.getElementById('send-btn');
    const typingEl = document.getElementById('typing-indicator');
    const uploadZone = document.getElementById('upload-zone');
    const fileInput = document.getElementById('file-input');
    const themeBtn = document.getElementById('theme-btn');
    const clearBtn = document.getElementById('clear-btn');

    // Initialize
    localStorage.setItem('kompile-lite-session', sessionId);
    loadStatus();
    loadHistory();

    // Theme toggle
    themeBtn.addEventListener('click', () => {
        const current = document.body.getAttribute('data-theme');
        document.body.setAttribute('data-theme', current === 'light' ? 'dark' : 'light');
    });

    // Clear chat
    clearBtn.addEventListener('click', async () => {
        if (confirm('Clear conversation history?')) {
            await fetch(`${API_BASE}/chat/history/${sessionId}`, { method: 'DELETE' });
            messagesEl.innerHTML = '';
            showWelcome();
        }
    });

    // Send message
    sendBtn.addEventListener('click', sendMessage);
    inputEl.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    // Auto-resize textarea
    inputEl.addEventListener('input', () => {
        inputEl.style.height = 'auto';
        inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + 'px';
    });

    // File upload
    uploadZone.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', (e) => uploadFiles(e.target.files));

    uploadZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        uploadZone.classList.add('drag-over');
    });
    uploadZone.addEventListener('dragleave', () => uploadZone.classList.remove('drag-over'));
    uploadZone.addEventListener('drop', (e) => {
        e.preventDefault();
        uploadZone.classList.remove('drag-over');
        uploadFiles(e.dataTransfer.files);
    });

    async function sendMessage() {
        const text = inputEl.value.trim();
        if (!text || isStreaming) return;

        addMessage('user', text);
        inputEl.value = '';
        inputEl.style.height = 'auto';
        isStreaming = true;
        sendBtn.disabled = true;
        showTyping(true);

        // Remove welcome message
        const welcome = messagesEl.querySelector('.welcome');
        if (welcome) welcome.remove();

        try {
            const response = await fetch(`${API_BASE}/chat`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message: text, sessionId: sessionId })
            });

            const data = await response.json();
            showTyping(false);

            let meta = '';
            if (data.vectorResultCount > 0 || data.graphResultCount > 0) {
                meta = `Sources: ${data.vectorResultCount} docs`;
                if (data.graphResultCount > 0) meta += `, ${data.graphResultCount} graph`;
            }
            addMessage('assistant', data.response, meta);
        } catch (err) {
            showTyping(false);
            addMessage('assistant', 'Error: ' + err.message);
        } finally {
            isStreaming = false;
            sendBtn.disabled = false;
            inputEl.focus();
        }
    }

    async function uploadFiles(files) {
        for (const file of files) {
            addMessage('user', `Uploading: ${file.name} (${formatSize(file.size)})`);

            const formData = new FormData();
            formData.append('file', file);

            try {
                const resp = await fetch(`${API_BASE}/documents/upload`, {
                    method: 'POST',
                    body: formData
                });
                const result = await resp.json();
                addMessage('assistant',
                    `Indexed "${result.filename}": ${result.chunks} chunks, ${result.indexed} indexed. Status: ${result.status}`);
                loadStatus();
            } catch (err) {
                addMessage('assistant', `Upload failed: ${err.message}`);
            }
        }
    }

    function addMessage(role, text, meta) {
        const div = document.createElement('div');
        div.className = `message ${role}`;
        div.textContent = text;
        if (meta) {
            const metaEl = document.createElement('div');
            metaEl.className = 'meta';
            metaEl.textContent = meta;
            div.appendChild(metaEl);
        }
        messagesEl.appendChild(div);
        messagesEl.scrollTop = messagesEl.scrollHeight;
    }

    function showTyping(show) {
        typingEl.classList.toggle('visible', show);
        if (show) messagesEl.scrollTop = messagesEl.scrollHeight;
    }

    function showWelcome() {
        const welcome = document.createElement('div');
        welcome.className = 'welcome';
        welcome.innerHTML = '<h2>Kompile Lite</h2><p>Chat with your documents using RAG and Graph RAG.<br>Upload files using the sidebar or drag and drop.</p>';
        messagesEl.appendChild(welcome);
    }

    async function loadStatus() {
        try {
            const resp = await fetch(`${API_BASE}/status`);
            const data = await resp.json();

            updateStatusDot('embedding-status', data.embedding?.available);
            updateStatusDot('vectorstore-status', data.vectorStore?.available);
            updateStatusDot('graph-status', data.graphRag?.available);
            updateStatusDot('llm-status', data.llm?.available);

            const countEl = document.getElementById('doc-count');
            if (countEl) countEl.textContent = data.vectorStore?.documentCount || 0;

            const modelEl = document.getElementById('model-name');
            if (modelEl) modelEl.textContent = data.embedding?.model || 'None';

            const llmEl = document.getElementById('llm-provider');
            if (llmEl) llmEl.textContent = data.llm?.provider || 'None';
        } catch (err) {
            console.warn('Status load failed:', err);
        }
    }

    async function loadHistory() {
        try {
            const resp = await fetch(`${API_BASE}/chat/history?sessionId=${sessionId}`);
            const messages = await resp.json();
            if (messages.length === 0) {
                showWelcome();
            } else {
                messages.forEach(m => addMessage(m.role, m.content));
            }
        } catch (err) {
            showWelcome();
        }
    }

    function updateStatusDot(id, active) {
        const el = document.getElementById(id);
        if (el) {
            el.className = `status-dot ${active ? 'active' : 'inactive'}`;
        }
    }

    function generateSessionId() {
        return 'lite-' + Math.random().toString(36).substring(2, 10);
    }

    function formatSize(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / 1048576).toFixed(1) + ' MB';
    }

    // Poll status every 30s
    setInterval(loadStatus, 30000);
})();
