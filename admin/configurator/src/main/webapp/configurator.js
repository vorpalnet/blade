// JSON Schema and Data - loaded dynamically when user selects from dropdowns
let schema = {};
let initialData = {};

// Schema registry - populated dynamically from MBeans
let schemaRegistry = [];

// Target directories - populated dynamically from server
let targetDirectories = [];
let selectedTargetDirectory = null;

let formIdCounter = 0;
let jsonEditor;
let schemaEditor;
let currentData = {};
// The editors' Ace theme follows the admin theme (data-theme on <html>, set
// by /blade/portal/brand/theme.js and its top-bar switch).
function aceThemeFor() {
    return document.documentElement.getAttribute('data-theme') === 'dark' ? 'tomorrow_night' : 'eclipse';
}
let currentTheme = aceThemeFor();
new MutationObserver(function () {
    currentTheme = aceThemeFor();
    [jsonEditor, schemaEditor, typeof textEditor !== 'undefined' ? textEditor : null].forEach(function (e) { if (e) e.setTheme('ace/theme/' + currentTheme); });
}).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
let currentSchemaName = null;
let websocket = null;
let pendingRequests = new Map();
let reconnectAttempts = 0;
let maxReconnectAttempts = 5;
let reconnectDelay = 3000;

// Heartbeat / Ping-Pong mechanism
let pingInterval = null;
let pongTimeout = null;
let lastPongTime = null;
const PING_INTERVAL = 30000; // Send ping every 30 seconds
const PONG_TIMEOUT = 10000; // Expect pong within 10 seconds

// Feature: Unsaved Changes Warning
let isDirty = false;
let savedDataSnapshot = null;

// Saved-to-disk-but-not-live state. Only relevant when auto-publish is off;
// when on, a save is republished automatically so there is no unpublished gap.
let isUnpublished = false;
let autoPublishEnabled = true;


// Feature: Validation
let validationErrors = new Map();

// Initial selection driven by query string (?domain=…&schema=…).
// Read once at DOMContentLoaded; consumed and cleared after the target +
// schema lists arrive, so they only apply on the first WebSocket handshake.
let urlInitialDomain = null;
let urlInitialSchema = null;

// Feature: Jackson Identity References
// Maps id strings to {path, object} for objects with an "id" field
let identityRegistry = new Map();

// WebSocket Connection Management
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    // Get the context path from the current location. The context-root is the
    // path up to (but not including) the current file — works for both
    // single-segment context roots (`/configurator`) and multi-segment ones
    // (`/blade/configurator`) since we go to the last slash, not the first.
    const contextPath = window.location.pathname.substring(0, window.location.pathname.lastIndexOf('/'));
    // A random k per connection: the server keys this socket's handshake by
    // it, so a page on another site cannot take this tab's access.
    const k = (crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2) + Date.now());
    const wsUrl = protocol + '//' + window.location.host + contextPath + '/websocket?k=' + encodeURIComponent(k);

    updateWebSocketStatus('Connecting...', 'warning');

    websocket = new WebSocket(wsUrl);

    websocket.onopen = function() {
        updateWebSocketStatus('Connected', 'success');
        // Nothing is out on a new socket.
        saving = false; publishing = false; publishingApps.clear(); cloneOut = false;
        textSaveContent = null;   // a template save sent while it was down never went
        // A load begun while it was down sent nothing: back to the form shown.
        if (schemaLoading) {
            abortLoad();
            showSchemaLoadStatus('The connection was down: pick the schema or target again to load it', 'warning');
        }
        reconnectAttempts = 0; // Reset on successful connection
        lastPongTime = Date.now();
        startHeartbeat(); // Start sending pings
        sendWebSocketMessage('get_autopublish', {}); // sync the auto-publish toggle
        sendWebSocketMessage('get_unpublished', {}); // saved with auto-publish off, by any page
        sendWebSocketMessage('get_ai_status', {});   // show the Ask Claude box only when enabled
    };

    websocket.onmessage = function(event) {
        try {
            const message = JSON.parse(event.data);
            handleWebSocketMessage(message);
        } catch (e) {
            console.error('Error parsing WebSocket message:', e);
        }
    };

    websocket.onclose = function(event) {
        stopHeartbeat(); // Stop sending pings
        publishing = false;   // its answer would come back on this socket
        publishingApps.clear();
        saving = false;
        cloneOut = false;
        textSaveContent = null;
        // A load's replies come back on this socket too: stop waiting, keep the
        // form that is there with its own name and target, and say so.
        if (schemaLoading) {
            abortLoad();
            showSchemaLoadStatus('The connection dropped while loading: load again once it reconnects', 'error');
        }
        // The answer to a chat message comes back on this socket; it is gone.
        if (aiPending) {
            const p = aiPending;
            aiPending = null;
            p.reject(new Error('The connection to the server dropped; ask again.'));
        }

        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++;
            const delay = reconnectDelay * reconnectAttempts; // Exponential backoff
            updateWebSocketStatus(`Reconnecting (${reconnectAttempts}/${maxReconnectAttempts})...`, 'warning');
            setTimeout(connectWebSocket, delay);
        } else {
            updateWebSocketStatus('Connection failed. Please reload page.', 'error');
        }
    };

    websocket.onerror = function(error) {
        console.error('WebSocket error:', error);
        updateWebSocketStatus('Connection error', 'error');
    };
}

function updateWebSocketStatus(message, type) {
    const statusEl = document.getElementById('websocket-status');
    if (statusEl) {
        statusEl.textContent = 'WebSocket: ' + message;
        statusEl.className = `sync-status ${type}`;
        statusEl.style.display = 'inline-flex';
    }
}

function handleWebSocketMessage(message) {
    switch(message.type) {
        case 'schema_loaded':
            handleSchemaLoaded(message.content);
            break;

        case 'json_loaded':
            handleJsonLoaded(message.content);
            break;

        case 'sample_loaded':
            handleSampleLoaded(message.content);
            break;

        case 'file_version': {
            let v = null; try { v = JSON.parse(message.content); } catch (e) { /* ignore */ }
            if (v && currentSchemaName && selectedTargetDirectory && v.file === currentSavePath()) fileVersion = v.version;
            break;
        }

        case 'save_conflict': {
            let v = null; try { v = JSON.parse(message.content); } catch (e) { /* ignore */ }
            if (cloneOut && v && v.file === lastClonePath) {
                // The reply to the clone that is out: its destination exists.
                cloneOut = false;
                cloneOverwrite = true;
                showSyncStatus('Not cloned: ' + v.file + ' already exists. Clone again to replace it.', 'error');
                break;
            }
            if (!v || v.file !== lastSavePath) break;
            saving = false;   // this page's save is answered
            if (v.file !== (currentSchemaName && selectedTargetDirectory ? currentSavePath() : null)) {
                // The operator moved on while it was out: the save did not happen.
                showSyncStatus('Not saved: ' + v.file + ' changed since it was loaded (another save). Open it again to redo the change.', 'error');
                lastSavedData = null;
                break;
            }
            lastSavedData = null;
            overwriteNext = true;
            showSyncStatus('Not saved: ' + ((v && v.file) || 'the file') + ' changed since you loaded it (another save). '
                + 'Reload to see it, or press Save again to overwrite it.', 'error');
            break;
        }

        case 'save_refused': {
            // The server would not write it: the application could not load it
            // (IdentityCheck: a repeated or unknown id, merged as the loader merges).
            let v = null; try { v = JSON.parse(message.content); } catch (e) { /* ignore */ }
            if (!v) break;
            const why = (v.problems || [])[0] + ((v.problems || []).length > 1 ? ' (and ' + (v.problems.length - 1) + ' more)' : '');
            if (cloneOut && v.file === lastClonePath) {
                cloneOut = false;
                showSyncStatus('Not cloned: ' + why, 'error');
                break;
            }
            if (v.file !== lastSavePath) break;
            saving = false;
            lastSavedData = null;
            showSyncStatus('Not saved: ' + why, 'error');
            break;
        }

        case 'save_success':
            // The server names the file it wrote (and its new version). A
            // clone writes another target's file: the form's edits are still unsaved.
            {
                let v = null; try { v = JSON.parse(message.content); } catch (e) { v = { file: message.content }; }
                // The reply to the clone that is out, first: with the form moved to
                // the clone's target and still loading, it would read as the form's own.
                if (cloneOut && v && v.file === lastClonePath) {
                    cloneOut = false;
                    cloneOverwrite = false;
                    showSyncStatus('Cloned to ' + v.file, 'success');
                    if (!autoPublishEnabled) setUnpublished(lastClonePath.split('/').pop().replace(/\.json$/, ''));
                    break;
                }
                // The reply to this page's save, even if the form moved on since:
                // the save is over (it is not a clone).
                if (v && v.file === lastSavePath && saving) {
                    saving = false;
                    if (!(currentSchemaName && selectedTargetDirectory && v.file === currentSavePath())) {
                        // That file's draft (this tab's) is what was saved.
                        if (lastSaveDraftKey) { try { localStorage.removeItem(lastSaveDraftKey); } catch (e) { /* ignore */ } }
                        showSyncStatus('Saved ' + v.file, 'success');
                        lastSavedData = null;
                        if (!lastSaveAutoPublish) setUnpublished(lastSaveApp);
                        break;
                    }
                }
                const isOwn = !(v && v.file && currentSchemaName && selectedTargetDirectory && v.file !== currentSavePath());
                if (!isOwn) {
                    if (v.file === lastClonePath) { cloneOut = false; cloneOverwrite = false; }
                    showSyncStatus('Cloned to ' + v.file, 'success');
                    // Saved, and with auto-publish off not live until Publish.
                    if (!autoPublishEnabled) setUnpublished(currentSchemaName);
                    break;
                }
                saving = false;
                draftBaseVersion = null;
                if (v && v.version) fileVersion = v.version;
            }
            showSyncStatus('Data saved successfully', 'success');
            showSchemaLoadStatus('Saved', 'success');
            // What is on disk now: Diff and Reset compare with this, not the
            // file as first loaded.
            {
                const written = lastSavedData;
                if (written) { initialData = written; currentData = written; lastSavedData = null; }
                // Edits made while the save was out are not in the file: the
                // form stays unsaved, with the file as its new baseline.
                const now = activeEditorData();
                const sameAsWritten = written && now !== undefined
                    && JSON.stringify(canonicalJson(now)) === JSON.stringify(canonicalJson(written));
                if (sameAsWritten || !written) {
                    clearDirty();
                    if (typeof clearDraft === 'function') clearDraft();
                } else {
                    savedDataSnapshot = JSON.stringify(written);
                }
            }
            // The file is now what was written (repaired and encrypted on the
            // server): the next Save repairs against the file itself (no source
            // text), and a broken file this replaced is no longer the baseline.
            loadedSourceText = initialText = null;
            if (noFileYet) { noFileYet = false; const ls = document.getElementById('schema-load-status'); if (ls) ls.style.display = 'none'; }
            leftEditorText = null;
            fileBroken = false;
            if (publishingApps.has(currentSchemaName)) savedDuringPublish.add(currentSchemaName);
            // Live or pending by auto-publish as it was when the save was sent:
            // the watcher started after this write never saw it.
            if (lastSaveAutoPublish) {
                // Live by the watcher, but an earlier failure elsewhere (another
                // engine, another file of this app) may still stand: the server's
                // list decides, not this page.
                sendWebSocketMessage('get_unpublished', {});
            } else {
                setUnpublished();
                if (autoPublishEnabled) publishConfig();   // turned on meanwhile
            }
            break;

        case 'template_list':
            handleTemplateList(message.content);
            break;

        case 'ai_status':
            handleAiStatus(message.content);
            break;
        case 'ai_result':
            handleAiResult(message.content);
            break;
        case 'ai_error':
            handleAiError(message.content);
            break;
        case 'template_loaded':
            handleTemplateLoaded(message.content);
            break;

        case 'text_file_list':
            handleTextFileList(message.content);
            break;

        case 'text_file_loaded':
            handleTextFileLoaded(message.content);
            break;

        case 'text_file_saved':
            handleTextFileSaved(message.content);
            break;

        case 'text_file_pushed':
            handleTextFilePushed(message.content);
            break;

        case 'text_save_error':
            textSaveContent = null;
            showTextFileStatus('Error: ' + message.content, 'error');
            break;

        case 'text_save_conflict':
            textSaveContent = null;
            // About another template than the one open now: arms nothing here.
            if (message.content !== currentTextFile) {
                showTextFileStatus('Not saved: ' + message.content + ' changed since it was opened (another save)', 'error');
                break;
            }
            textOverwrite = true;
            showTextFileStatus('Not saved: ' + message.content + ' changed since you opened it (another save). '
                + 'Revert to see it, or Save again to overwrite it.', 'error');
            break;

        case 'publish_error': {
            // Its own type: a failed publish is not a failed load.
            const m = /^Publish failed for ([^:]+):/.exec(message.content || '');
            if (m && m[1] === TEMPLATES_PENDING) setUnpublished(TEMPLATES_PENDING);   // tried again by the next Publish
            if (m) publishingApps.delete(m[1]); else publishingApps.clear();
            publishing = publishingApps.size > 0;
            showSyncStatus(message.content, 'error');
            break;
        }

        case 'reload_success': {
            // "Configuration published for <app>": that app is live now.
            const m = /published for (\S+)$/.exec(message.content || '');
            const app = m ? m[1] : currentSchemaName;
            publishingApps.delete(app);
            publishing = publishingApps.size > 0;
            // Saved again while it was publishing: the engines have the earlier
            // content, so it stays "Not Published".
            if (savedDuringPublish.has(app)) savedDuringPublish.delete(app); else clearUnpublished(app);
            // Another page may have saved it meanwhile: the server's list decides.
            sendWebSocketMessage('get_unpublished', {});
            showSyncStatus(message.content, 'success');
            if (app === currentSchemaName) showSchemaLoadStatus('Published', 'success');
            break;
        }

        case 'autopublish_state':
            updateAutoPublishToggle(message.content === 'true');
            break;

        case 'unpublished_list': {
            // The server's list, templates included: any page's saves while
            // auto-publish was off, and any push that failed.
            let list = []; try { list = JSON.parse(message.content) || []; } catch (e) { /* ignore */ }
            unpublishedApps.clear();
            list.forEach(app => unpublishedApps.add(app));
            renderUnpublished();
            break;
        }

        case 'version_list':
            displayVersionList(message.content);
            break;

        case 'version_restored':
            handleVersionRestored(message.content);
            break;

        case 'version_content':
            handleVersionContent(message.content);
            break;

        case 'text_version_list':
            displayVersionList(message.content, 'text');
            break;

        case 'text_version_restored':
            handleTextVersionRestored(message.content);
            break;

        case 'text_version_content':
            handleTextVersionContent(message.content);
            break;

        case 'file_content':
            // Ignore initial file_content message from generic file manager
            break;

        case 'pong':
            // Received pong response to ping
            handlePong();
            break;

        case 'schemas_list':
            handleSchemasList(message.content);
            break;

        case 'target_directories_list':
            handleTargetDirectoriesList(message.content);
            break;

        case 'json_file_resolved':
            handleJsonFileResolved(message.content);
            break;

        case 'save_error': {
            // A refused save or clone (encryption unavailable, no writer role),
            // naming its file: a clone and a save can both be out.
            let v = null; try { v = JSON.parse(message.content); } catch (e) { v = { message: message.content }; }
            if (cloneOut && v.file === lastClonePath) {
                cloneOut = false;
            } else if (saving && (!v.file || v.file === lastSavePath)) {
                saving = false;
                lastSavedData = null;
            }
            showSyncStatus('Error: ' + v.message, 'error');
            break;
        }

        case 'load_error': {
            let v = null; try { v = JSON.parse(message.content); } catch (e) { v = { message: message.content }; }
            // For a load replaced since (another schema picked): not this one's.
            if (v.seq != null && String(v.seq) !== String(loadSeq)) break;
            showSchemaLoadStatus('Error: ' + v.message, 'error');
            showSyncStatus('Error: ' + v.message, 'error');
            // A load that failed (an unreadable file): stop waiting and put
            // back the schema, name and target the form still holds, so Save
            // and the chat do not treat the old form as the new app's.
            if (schemaLoading) abortLoad();
            break;
        }

        case 'autopublish_error':
            // Refused (no writer role): the switch shows the server's state again.
            showSyncStatus('Error: ' + message.content, 'error');
            sendWebSocketMessage('get_autopublish', {});
            break;

        case 'version_error':
            // A version list, preview or restore failed: none of them is
            // still out, so a late reply cannot be taken for this one's.
            pendingRequests.delete('versionTimestamp');
            pendingRequests.delete('restoreTimestamp');
            pendingRequests.delete('restoreFile');
            showSyncStatus('Versions: ' + message.content, 'error');
            break;

        case 'text_error':
            showTextFileStatus('Error: ' + message.content, 'error');
            break;

        case 'error':
            // Answers nothing the page waits on (saves, loads and publishes
            // fail with their own types): it is only shown.
            showSyncStatus('Error: ' + message.content, 'error');
            console.error('Server error:', message.content);
            break;
    }
}

function sendWebSocketMessage(action, params) {
    if (websocket && websocket.readyState === WebSocket.OPEN) {
        const message = { action: action, ...params };
        websocket.send(JSON.stringify(message));
    } else {
        showSchemaLoadStatus('WebSocket not connected', 'error');
    }
}

// Heartbeat / Ping-Pong Functions
function startHeartbeat() {
    stopHeartbeat(); // Clear any existing intervals

    // Send ping every PING_INTERVAL milliseconds
    pingInterval = setInterval(function() {
        if (websocket && websocket.readyState === WebSocket.OPEN) {
            sendWebSocketMessage('ping', {});

            // Set timeout to expect pong response (one at a time: an earlier
            // one left running would close a healthy socket)
            clearTimeout(pongTimeout);
            pongTimeout = setTimeout(function() {
                // Close connection to trigger reconnect
                websocket.close(1000, 'No pong received');
            }, PONG_TIMEOUT);
        }
    }, PING_INTERVAL);
}

function stopHeartbeat() {
    if (pingInterval) {
        clearInterval(pingInterval);
        pingInterval = null;
    }
    if (pongTimeout) {
        clearTimeout(pongTimeout);
        pongTimeout = null;
    }
}

function handlePong() {
    // Clear the pong timeout since we received a response
    if (pongTimeout) {
        clearTimeout(pongTimeout);
        pongTimeout = null;
    }

    lastPongTime = Date.now();
}

// Target Directory Functions
function requestTargetDirectories() {
    if (websocket && websocket.readyState === WebSocket.OPEN) {
        sendWebSocketMessage('list_target_directories', {});
    } else {
        setTimeout(requestTargetDirectories, 1000);
    }
}

function handleTargetDirectoriesList(content) {
    try {
        targetDirectories = JSON.parse(content);
        populateTargetDirectoriesDropdown();

        // ?domain=… in the URL wins over the default domain auto-select.
        // Match against path first, then displayName (case-insensitive),
        // then type — whichever matches first.
        let initial = null;
        if (urlInitialDomain) {
            const wanted = urlInitialDomain;
            const wantedLc = wanted.toLowerCase();
            initial = targetDirectories.find(t => t.path === wanted)
                   || targetDirectories.find(t => (t.displayName || '').toLowerCase() === wantedLc)
                   || targetDirectories.find(t => (t.type || '').toLowerCase() === wantedLc)
                   // The cluster or server name alone, as an operator writes a link.
                   || targetDirectories.find(t => t.type !== 'domain' && (t.path || '').split('/').pop().toLowerCase() === wantedLc);
            // Said, not silent: the Domain file is not the overlay the link named.
            if (!initial) urlTargetMissing = wanted;
            urlInitialDomain = null;
        }
        if (!initial) {
            initial = targetDirectories.find(t => t.type === 'domain');
        }
        if (initial) {
            document.getElementById('target-directory-select').value = initial.path;
            onTargetDirectoryChange(initial.path);
        }

        maybeAutoLoadFromUrl();
        // No schema to load means no load to say it after: say it now.
        if (urlTargetMissing && !urlInitialSchema) {
            showSchemaLoadStatus('No target "' + urlTargetMissing + '": this is the Domain file', 'error');
            urlTargetMissing = null;
        }
    } catch (e) {
        console.error('Error parsing target directories list:', e);
        showSchemaLoadStatus('Error loading target directories', 'error');
    }
}

function populateTargetDirectoriesDropdown() {
    const select = document.getElementById('target-directory-select');
    // Clear existing options except the first one
    while (select.options.length > 1) {
        select.remove(1);
    }

    if (targetDirectories.length === 0) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = '-- No targets found --';
        option.disabled = true;
        select.appendChild(option);
        return;
    }

    targetDirectories.forEach(target => {
        const option = document.createElement('option');
        option.value = target.path;
        option.textContent = target.displayName;
        option.title = target.path + ' (' + target.type + ')';
        select.appendChild(option);
    });
}

function onTargetDirectoryChange(path) {
    // Ask before the switch, not after: Save writes to the selected target,
    // so a cancelled switch must leave it on the file the form came from.
    const previous = selectedTargetDirectory;
    // "-- Select Target --": the form would stay on a file no dropdown names.
    if (!path && previous) {
        const sel = document.getElementById('target-directory-select');
        if (sel) sel.value = previous.path;
        return;
    }
    if (!checkUnsavedChanges()) {
        const sel = document.getElementById('target-directory-select');
        if (sel) sel.value = previous ? previous.path : '';
        return;
    }
    if (!path) {
        selectedTargetDirectory = null;
        return;
    }

    // What the form holds now, before the target changes under it.
    if (!schemaLoading) loadFallback = { schema: schema, name: currentSchemaName, target: previous, version: fileVersion, draftBase: draftBaseVersion };
    selectedTargetDirectory = targetDirectories.find(t => t.path === path);
    showSchemaLoadStatus('Target: ' + (selectedTargetDirectory ? selectedTargetDirectory.displayName : 'None'), 'success');

    // Reload current schema's JSON file from the new target
    if (currentSchemaName && selectedTargetDirectory) {
        loadSchema(currentSchemaName, true);
    }
}

// Populate the schema dropdown
// Request schema list from server (discovers deployed apps via JMX MBeans)
function requestSchemaList() {
    if (websocket && websocket.readyState === WebSocket.OPEN) {
        sendWebSocketMessage('list_schemas', {});
    } else {
        setTimeout(requestSchemaList, 1000);
    }
}

// Handle schemas list from server
function handleSchemasList(content) {
    try {
        schemaRegistry = JSON.parse(content);
        populateSchemaDropdown();
        maybeAutoLoadFromUrl();
    } catch (e) {
        console.error('Error parsing schemas list:', e);
        showSchemaLoadStatus('Error loading schema list', 'error');
    }
}

// Apply ?schema=… once both the schema list and the target directory have
// arrived. Called from both handlers; whichever lands second triggers the
// load. Self-clears so the URL only affects the initial render.
function maybeAutoLoadFromUrl() {
    if (!urlInitialSchema) return;
    if (!selectedTargetDirectory) return;
    if (!schemaRegistry || schemaRegistry.length === 0) return;

    const wanted = urlInitialSchema;
    const wantedLc = wanted.toLowerCase();
    const entry = schemaRegistry.find(e => e.name === wanted)
               || schemaRegistry.find(e => (e.name || '').toLowerCase() === wantedLc);
    urlInitialSchema = null;
    if (!entry) {
        const target = urlTargetMissing ? '; no target "' + urlTargetMissing + '" either' : '';
        urlTargetMissing = null;
        showSchemaLoadStatus('Schema "' + wanted + '" from URL not found' + target, 'error');
        return;
    }
    const select = document.getElementById('schema-select');
    if (select) select.value = entry.name;
    loadSchema(entry.name);
}

function populateSchemaDropdown() {
    const select = document.getElementById('schema-select');
    // Clear existing options except the first one
    while (select.options.length > 1) {
        select.remove(1);
    }

    if (schemaRegistry.length === 0) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = '-- No schemas found --';
        option.disabled = true;
        select.appendChild(option);
        return;
    }

    schemaRegistry.forEach(entry => {
        const option = document.createElement('option');
        option.value = entry.name;
        option.textContent = entry.name;
        option.title = entry.name;
        select.appendChild(option);
    });
}

// Load a schema and its associated JSON data via WebSocket
function loadSchema(schemaName, alreadyAsked = false) {
    // The dropdown back on what the form holds, whenever this does not load.
    const keepSelect = () => { const sel = document.getElementById('schema-select'); if (sel) sel.value = currentSchemaName || ''; };
    if (!schemaName) { keepSelect(); return; }

    // Feature: Check for unsaved changes before loading new schema
    if (!alreadyAsked && !checkUnsavedChanges()) {
        const sel = document.getElementById('schema-select');
        if (sel) sel.value = currentSchemaName || '';
        return;
    }

    if (!selectedTargetDirectory) {
        showSchemaLoadStatus('Please select a target directory first', 'error');
        keepSelect();
        return;
    }

    const entry = schemaRegistry.find(e => e.name === schemaName);
    if (!entry) {
        showSchemaLoadStatus('Schema not found', 'error');
        keepSelect();
        return;
    }
    hideDraftBanner();   // the file being left's offer; the new file's comes with it

    showSchemaLoadStatus('Loading schema...', 'warning');
    // What the form holds now, to go back to if this load fails: taken once,
    // before the first of overlapping loads (a target change took it already).
    if (!schemaLoading && !alreadyAsked) loadFallback = { schema: schema, name: currentSchemaName, target: selectedTargetDirectory, version: fileVersion, draftBase: draftBaseVersion };
    loadSeq++;              // replies to an earlier load are dropped (handleJsonFileResolved/Loaded)
    // A message about the file being left (a conflict, a refusal) is not this one's.
    { const st = document.getElementById('sync-status'); if (st) { clearTimeout(st._hideTimer); st.style.display = 'none'; } }
    fileVersion = null;
    draftBaseVersion = null;   // a restored draft belongs to the form being replaced
    overwriteNext = false;
    sampleIsEdit = false;   // a Load Sample that failed must not mark this load an edit
    currentSchemaName = schemaName;
    // Until the file is in the form, Save would write the old form to the new file.
    schemaLoading = true;

    // Store the entry in a pending request. Its file is this load's, once
    // resolved: the last load's (another target's) must not name Save's file.
    entry.jsonFile = null;
    pendingRequests.set('schema', entry);

    // Request the schema from the filesystem
    sendWebSocketMessage('load_schema', { appName: entry.appName, seq: loadSeq });

    // Request JSON file resolution based on selected target
    sendWebSocketMessage('resolve_json_file', {
        schemaName: schemaName,
        targetDirectory: selectedTargetDirectory.path,
        seq: loadSeq
    });
}

function handleJsonFileResolved(content) {
    try {
        const resolution = JSON.parse(content);
        // The server names what it resolved: a reply for an earlier schema or
        // target (the operator moved on before it came) is dropped.
        if ((resolution.schemaName && resolution.schemaName !== currentSchemaName)
                || (resolution.targetDirectory && selectedTargetDirectory && resolution.targetDirectory !== selectedTargetDirectory.path)) {
            return;
        }
        // Only for a load still waiting: one aborted since (a schema that
        // failed to load) must not give the old form the file's newer version,
        // or its next Save would pass the conflict check.
        if (!schemaLoading) return;
        jsonLoadSeq = loadSeq;
        if (resolution.version) fileVersion = resolution.version;   // "none" before the file exists

        const entry = pendingRequests.get('schema');
        if (entry) {
            // Update entry with resolved JSON file info
            entry.jsonFile = resolution.jsonFile;
            entry.jsonFileType = resolution.jsonFileType;
            pendingRequests.set('schema', entry);

            if (resolution.exists) {
                if (resolution.jsonFileType === 'sample') {
                    // Load sample from filesystem
                    sendWebSocketMessage('load_sample', { appName: resolution.appName, seq: loadSeq });
                } else {
                    // Load primary config from filesystem
                    sendWebSocketMessage('load_json', { file: resolution.jsonFile, seq: loadSeq });
                }
            } else {
                loadedSourceText = initialText = null;
                fileBroken = false;
                finalizeSchemaLoad({});
                noFileYet = true;
                showSchemaLoadStatus('No file for this target yet: Save creates it.', 'warning');
            }
        }
    } catch (e) {
        console.error('Error processing JSON file resolution:', e);
        finalizeSchemaLoad({});
    }
}

/// A load that failed: stop waiting and put back the schema, name and target
/// the form still holds, so Save and the chat do not treat the old form as
/// the new app's; anything still coming for the load is dropped.
function abortLoad() {
    if (loadFallback) {
        schema = loadFallback.schema;
        currentSchemaName = loadFallback.name;
        selectedTargetDirectory = loadFallback.target;
        fileVersion = loadFallback.version || null;
        // A restored draft's base: without it the next Save would pass the
        // conflict check over versions the draft never saw.
        draftBaseVersion = loadFallback.draftBase || null;
        const ss = document.getElementById('schema-select');
        if (ss) ss.value = currentSchemaName || '';
        const ts = document.getElementById('target-directory-select');
        if (ts) ts.value = selectedTargetDirectory ? selectedTargetDirectory.path : '';
        const titleEl = document.getElementById('schema-title');
        if (titleEl && schema) titleEl.textContent = schema.title || currentSchemaName || 'Configurator';
    }
    pendingRequests.delete('schema');
    schemaLoading = false;
    loadSeq++;
    updateTitle();
}

function handleSchemaLoaded(content) {
    try {
        const entry = pendingRequests.get('schema');
        if (!entry) {
            return;
        }

        if (!content || content.trim() === '') {
            throw new Error('Empty schema received from server');
        }

        const newSchema = JSON.parse(content);

        // Update the global schema
        schema = newSchema;

        // Update the page title
        const titleEl = document.getElementById('schema-title');
        titleEl.textContent = newSchema.title || currentSchemaName;

        // JSON loading is now handled by handleJsonFileResolved() -> handleJsonLoaded() flow
        // Don't load JSON here, wait for the resolve_json_file response

    } catch (error) {
        console.error('Error processing schema:', error);
        showSchemaLoadStatus('Error: ' + error.message, 'error');
        abortLoad();
    }
}

/// The exact text the form's content came from (the file as loaded, a
/// sample, an imported file, a template, a restored version), sent with Save
/// and Clone: the server repairs what the browser's JavaScript did to the
/// document (key order, long integers) against it (JsonRepair). `initialText`
/// is the file's own, for Reset.
let loadedSourceText = null;
let initialText = null;
let writeEditorText = null;   // the JSON tab's text, when documentToWrite last read it
let leftEditorText = null;    // the JSON tab's text when the operator last left it (cleared by a load or Save)

/// The file as loaded does not parse: its text, not the empty form, is the baseline.
let fileBroken = false;

function handleJsonLoaded(content) {
    // Loaded for a load the operator has since replaced: not this form's file.
    if (jsonLoadSeq !== loadSeq) return;
    fileBroken = false;
    leftEditorText = null;
    try {
        let newData = {};
        if (content && content.trim() !== '') {
            newData = JSON.parse(content);
        }
        loadedSourceText = initialText = content || null;

        finalizeSchemaLoad(newData);

    } catch (error) {
        // Not an empty file: a broken one. Open its text in the JSON tab to
        // fix there (leaving the tab or saving is refused while it does not
        // parse); an empty form would save over it as if it were new.
        if (!jsonEditor) {
            // No JSON editor to fix it in: do not open it as an empty form that
            // a Save would write over.
            abortLoad();
            showSchemaLoadStatus('This file does not parse, and the JSON editor is unavailable: fix it on disk (' + error.message + ')', 'error');
            return;
        }
        finalizeSchemaLoad({});
        // The form's {} is not the file: Reset and Diff must not take it as
        // the baseline (Reset would put {} in the JSON tab, and Save write it).
        fileBroken = true;
        initialText = content;
        loadedSourceText = null;
        switchTab('json');
        setEditorText(content);
        showSchemaLoadStatus('This file does not parse: fix it in the JSON tab (' + error.message + ')', 'error');
    }
}

function handleSampleLoaded(content) {
    // Pressed Load Sample: the form now holds the sample, the file is unchanged.
    // The snapshot stays the file's, so Diff, Reset and the leave warning see an edit.
    if (sampleIsEdit) {
        sampleIsEdit = false;
        let sampleData;
        try { sampleData = (content && content.trim() !== '') ? JSON.parse(content) : {}; }
        catch (e) { showSchemaLoadStatus('Warning: Could not parse sample data', 'warning'); return; }
        loadedSourceText = content;   // the form's content now; Reset goes back to initialText
        leftEditorText = null;        // a JSON-tab text left before is not this content's
        currentData = sampleData;
        generateFormWithData(currentData);
        if (jsonEditor) jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
        setDirty();
        showSchemaLoadStatus('Sample loaded into the form · not saved', 'warning');
        return;
    }
    if (jsonLoadSeq !== loadSeq) return;   // a fallback sample for a load since replaced
    try {
        let sampleData = {};
        if (content && content.trim() !== '') {
            sampleData = JSON.parse(content);
            loadedSourceText = initialText = content;
            fileBroken = false;
        }
        finalizeSchemaLoad(sampleData);
        noFileYet = true;
        // Lasting, not a 3-second toast: the form looks like a file that exists.
        showSchemaLoadStatus('No file on disk yet: this is the sample. Save creates the file.', 'warning');
    } catch (error) {
        console.error('Error processing sample data:', error);
        showSchemaLoadStatus('Warning: Could not parse sample data', 'warning');
        loadedSourceText = initialText = null;   // not the previous file's
        finalizeSchemaLoad({});
    }
}

let sampleIsEdit = false;   // Load Sample pressed: the sample replaces the form as an edit

function loadSample() {
    if (viewModeRefuses('load the sample')) return;
    if (!currentSchemaName) {
        showSchemaLoadStatus('Please select a schema first', 'error');
        return;
    }
    if (schemaLoading) {
        showSchemaLoadStatus('Still loading: load the sample once the file is in', 'warning');
        return;
    }
    // A whole document saved as a cluster or server file would repeat every
    // list of the domain's (overlay lists are appended), as Clone refuses.
    if (selectedTargetDirectory && selectedTargetDirectory.type !== 'domain') {
        showSyncStatus('A sample is a whole configuration: load it on the Domain target', 'warning');
        return;
    }
    if (!checkUnsavedChanges()) return;
    sampleIsEdit = true;
    showSchemaLoadStatus('Loading sample...', 'warning');
    sendWebSocketMessage('load_sample', { appName: currentSchemaName, seq: loadSeq });
}

let urlTargetMissing = null;   // a ?domain= that named no target
let noFileYet = false;   // the form came from the sample or an empty overlay: nothing on disk

function finalizeSchemaLoad(newData) {
    noFileYet = false;
    leftEditorText = null;   // the JSON tab's text of the form this replaces
    // First schema load: retire the welcome panel and reveal the toolbar.
    const welcomePanel = document.getElementById('welcome-panel');
    if (welcomePanel) welcomePanel.style.display = 'none';
    document.getElementById('form-tab').classList.remove('no-schema');

    // Update the data
    initialData = newData;
    currentData = newData;

    // Regenerate the form with the new schema and data
    generateFormWithData(currentData);

    // Update the JSON editor
    if (jsonEditor) {
        jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
    }

    // Update the Schema editor (if already initialized)
    if (schemaEditor) {
        schemaEditor.setValue(JSON.stringify(schema, null, 2), -1);
    }
    // Note: If schema editor not yet initialized, it will get the schema when first shown

    showSchemaLoadStatus(`Loaded: ${schema.title || currentSchemaName}`, 'success');
    // The URL names the file the form holds: a reload or a copied link opens
    // this file, not the one the page was first opened on.
    try {
        const u = new URL(window.location.href);
        u.searchParams.set('schema', currentSchemaName);
        if (selectedTargetDirectory && selectedTargetDirectory.type !== 'domain') u.searchParams.set('domain', selectedTargetDirectory.path);
        else u.searchParams.delete('domain');
        history.replaceState(null, '', u);
    } catch (e) { /* no history API */ }
    if (urlTargetMissing) {   // said after the load, which would overwrite it
        showSchemaLoadStatus('No target "' + urlTargetMissing + '": this is the Domain file', 'error');
        urlTargetMissing = null;
    }

    // Feature: Clear dirty state and validation errors when loading new data
    clearDirty();
    renderUnpublished(); // other apps' unpublished saves stay listed
    clearValidationErrors();

    // Another app or target: the conversation was about that one.
    const chatKey = currentSchemaName + '|' + (selectedTargetDirectory ? selectedTargetDirectory.path : '');
    if (aiChat && lastChatKey !== null && lastChatKey !== chatKey) {
        aiChat.reset();
        aiPending = null;   // its answer, when it comes, belongs to the other app or target
    }
    lastChatKey = chatKey;

    // Clean up pending request
    pendingRequests.delete('schema');
    schemaLoading = false;
}

function showSchemaLoadStatus(message, type) {
    // Warnings and errors here describe the file (no file yet, a bad link):
    // they stay until the next message.
    const el = document.getElementById('schema-load-status');
    if (type === 'success') showStatusIn(el, message, type);
    else { showStatusIn(el, message, 'error'); el.className = 'sync-status ' + (type || ''); }
}

function resolveRef(ref) {
    // victools (Draft 2020-12) emits "$defs"; legacy kjetland (Draft 4)
    // emitted "definitions". Support both for now.
    if (ref.startsWith('#/$defs/')) {
        const defName = ref.substring('#/$defs/'.length);
        return schema.$defs && schema.$defs[defName];
    }
    if (ref.startsWith('#/definitions/')) {
        const defName = ref.substring('#/definitions/'.length);
        return schema.definitions && schema.definitions[defName];
    }
    return null;
}

// Resolve a schema node that may carry $ref alongside other keywords
// (the JSON Schema Draft 2019-09+ pattern that victools uses for
// polymorphic subtype variants: { $ref: "#/$defs/Base", properties: {
// type: { const: "hash" } } }). The referenced schema is the base; any
// sibling keywords on the input node override or extend it. Returns a
// fresh merged object — caller must not mutate it back into the schema.
/// Stable-sorts property names so const-valued props (polymorphic
/// discriminators like `type: {const: "hash"}`) come first. After deref
/// merges a variant wrapper onto its base, the discriminator lands at the
/// tail of iteration order — this restores it to the front so serialized
/// JSON shows the discriminator at the top of each object, matching
/// Jackson's usual output.
function orderedPropertyNames(properties) {
    const names = Object.keys(properties);
    const constNames = [];
    const others = [];
    names.forEach(name => {
        if (properties[name] && properties[name].const !== undefined) {
            constNames.push(name);
        } else {
            others.push(name);
        }
    });
    return constNames.concat(others);
}

/// Same shape as orderedPropertyNames, but preserves the original
/// JSON's key order for keys that appeared in the source object —
/// then appends any schema-only keys at the end. Without this, the
/// form/JSON round-trip rewrites every object's property order to
/// match the schema's declaration order, which is jarring for
/// hand-edited files (e.g. moves `session` to position 2 because the
/// schema lists it second). Const-valued properties (polymorphic
/// discriminators) still float to the top, matching orderedPropertyNames.
function mergedKeyOrder(properties, sourceObj) {
    if (!sourceObj || typeof sourceObj !== 'object') return orderedPropertyNames(properties);
    const schemaKeys = Object.keys(properties);
    const schemaSet = new Set(schemaKeys);
    const constNames = schemaKeys.filter(k => properties[k] && properties[k].const !== undefined);
    const seen = new Set(constNames);
    const sourceFirst = Object.keys(sourceObj).filter(k => schemaSet.has(k) && !seen.has(k));
    sourceFirst.forEach(k => seen.add(k));
    const trailing = schemaKeys.filter(k => !seen.has(k));
    return constNames.concat(sourceFirst).concat(trailing);
}

/// Walk a dotted-bracket path (e.g. "pipeline[2].selectors[0].id") into a
/// JSON object and return the value at that path, or undefined if any
/// segment is missing. Used for features like array-item duplication.
function resolveJsonPath(obj, path) {
    if (!path) return obj;
    const parts = String(path).replace(/\[(\d+)\]/g, '.$1').split('.').filter(Boolean);
    let cur = obj;
    for (const p of parts) {
        if (cur == null) return undefined;
        cur = cur[p];
    }
    return cur;
}

/// Read a primitive input's current value for JSON extraction. For inputs
/// marked data-password="true", cleartext values get the BLADE {CLEARTEXT}
/// prefix so the FileManagerServlet encrypts them on save. Values that
/// already carry an encrypted prefix ({AES}, {AES256}, {3DES}) or have been
/// marked {CLEARTEXT} already are passed through unchanged.
function readInputValue(input) {
    if (!input) return null;
    if (input.type === 'checkbox') return input.checked;
    // Number(), not parseInt: a decimal (39.0438) must not come back as 39.
    if (input.type === 'number') {
        // Text the browser cannot read as a number ("1,000") reads as empty:
        // saved, the field would vanish and the app's default go live.
        if (input.validity && input.validity.badInput) {
            readBackProblems.push((input.dataset.path || 'a number field') + ' is not a number');
            return null;
        }
        if (input.value === '') return null;
        const n = Number(input.value);
        // Past 2^53 the page cannot hold an integer: typed here it would be
        // saved rounded. The file's own value (the same double, however it
        // is typed) is repaired on the server; a new one goes in the JSON
        // tab, which keeps it. Refuses Save only, never leaving this tab:
        // the JSON tab is the way out. (A decimal in an integer field, a
        // value past its maximum: the server's schema check at Save.)
        if (Number.isInteger(n) && !Number.isSafeInteger(n) && n !== Number(input.getAttribute('value'))) {
            saveOnlyProblems.push((input.dataset.path || 'a number field')
                + ': a number this large loses digits in the form; type it in the JSON tab');
        }
        return n;
    }
    let v = input.value;
    // Untouched, a textarea's text is the file's, line endings and all (mixed
    // \r\n and \n, or a lone \r, which the textarea itself rewrites).
    if (input.tagName === 'TEXTAREA' && !input._edited && typeof input._original === 'string'
            && input._original.replace(/\r\n?/g, '\n') === (v || '').replace(/\r\n?/g, '\n')) {
        return input._original === '' ? null : input._original;
    }
    if (v == null || v === '') return null;
    if (input.dataset && input.dataset.crlf === 'true') v = v.replace(/\r?\n/g, '\r\n');
    if (input.dataset && input.dataset.password === 'true') {
        // Any {SCHEME}... prefix means the value is already cleartext-marked or
        // encrypted (WebLogic 14.1 emits {AES256}); only bare cleartext gets wrapped.
        // Re-wrapping an already-encrypted value produced {CLEARTEXT}{AES256}..., which
        // decrypts to the ciphertext and breaks the credential.
        // A ${variable} is a reference resolved at run time, not a secret:
        // encrypting it would store the text "${apiKey}" in place of the key.
        // Only BLADE's and WebLogic's own markers: a password that merely
        // starts with "{Word}" is still a password to encrypt.
        if (!/^\{(CLEARTEXT|AES|AES256|3DES|REDACTED)\}/.test(v) && !/^\$\{[^}]+\}$/.test(v)) {
            v = '{CLEARTEXT}' + v;
        }
    }
    return v;
}

/// Attach live validation to an input/textarea based on its schema. Paints
/// an `.invalid` class when the value fails `pattern` / min/maxLength /
/// minimum / maximum. The tooltip is the reason, for quick inspection.
function attachFieldValidation(input, schema) {
    if (!schema) return;
    const hasRules =
        schema.pattern || schema.minLength != null || schema.maxLength != null ||
        schema.minimum != null || schema.maximum != null;
    if (!hasRules) return;
    let regex = null;
    if (schema.pattern) {
        try { regex = new RegExp(schema.pattern); } catch (e) { /* skip invalid */ }
    }
    const validate = () => {
        const raw = input.value;
        let err = null;
        if (raw && raw !== '') {
            if (regex && !regex.test(raw)) err = "doesn't match pattern " + schema.pattern;
            if (!err && schema.minLength != null && raw.length < schema.minLength) err = 'min length ' + schema.minLength;
            if (!err && schema.maxLength != null && raw.length > schema.maxLength) err = 'max length ' + schema.maxLength;
            if (!err && (schema.type === 'integer' || schema.type === 'number')) {
                const n = parseFloat(raw);
                if (!isNaN(n)) {
                    if (schema.minimum != null && n < schema.minimum) err = 'minimum ' + schema.minimum;
                    if (!err && schema.maximum != null && n > schema.maximum) err = 'maximum ' + schema.maximum;
                }
            }
        }
        input.classList.toggle('invalid', !!err);
        if (err) input.title = err; else input.removeAttribute('title');
        input.setAttribute('aria-invalid', err ? 'true' : 'false');
        // Said in words, not by the red border alone. The stylesheet writes
        // "✗ invalid" beside a field in a labelled row; elsewhere (a list
        // item, a map value, a password) a marker goes after the field.
        const covered = input.parentElement && (input.parentElement.matches('.form-group-header.inline')
            || (input.tagName === 'TEXTAREA' && input.parentElement.matches('.form-group')));
        const anchor = input.closest('.password-field') || input;
        let mark = anchor.nextElementSibling && anchor.nextElementSibling.classList.contains('invalid-mark') ? anchor.nextElementSibling : null;
        if (err && !covered) {
            if (!mark) {
                mark = document.createElement('span');
                mark.className = 'invalid-mark';
                anchor.after(mark);
            }
            mark.textContent = '\u2717 ' + err;
        } else if (mark) {
            mark.remove();
        }
    };
    input.addEventListener('input', validate);
    input.addEventListener('blur', validate);
    validate();
}

/// Fields an `x-uri-preview`-flagged object (see FormUriPreview.java) must
/// expose; the configurator assembles them into a SIP URI client-side.
const URI_PREVIEW_FIELDS = ['scheme', 'transport', 'host', 'port', 'user', 'uriParams'];

/// Assembles the SIP URI from structured field values — mirrors the Java
/// side's own assembly (e.g. Endpoint.getUri()) exactly; keep both in sync.
function composeUriPreview(vals) {
    const scheme = vals.scheme || 'sip';
    const transport = vals.transport || 'udp';
    let uri = scheme + ':';
    if (vals.user && String(vals.user).trim() !== '') uri += vals.user + '@';
    uri += vals.host || '';
    if (vals.port) uri += ':' + vals.port;
    uri += ';transport=' + transport;
    if (vals.uriParams && String(vals.uriParams).trim() !== '') uri += ';' + vals.uriParams;
    return uri;
}

// Portable syntax checks, no SipFactory (the configurator can't reach one —
// it isn't a SIP servlet). Known limitation: plain-hostname shape only, no
// IPv6 literal ([::1]) support; not needed for the SBC/trunk endpoints this
// targets today.
const URI_HOST_RE = /^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$/;
const URI_USER_RE = /^[A-Za-z0-9._!~*'()%-]+$/;
const URI_PARAMS_RE = /^[A-Za-z0-9._-]+(=[A-Za-z0-9._%-]*)?(;[A-Za-z0-9._-]+(=[A-Za-z0-9._%-]*)?)*$/;

/// Validates the structured SIP URI fields, returning a specific reason on
/// failure so the badge tooltip can say exactly what's wrong.
function validateUriPreview(vals) {
    if (!vals.host || !String(vals.host).trim()) return { ok: false, reason: 'host is required' };
    if (!URI_HOST_RE.test(vals.host)) return { ok: false, reason: 'host has characters not valid in a SIP URI' };
    if (vals.user && !URI_USER_RE.test(vals.user)) return { ok: false, reason: 'user part has characters not valid in a SIP URI' };
    if (vals.port != null && vals.port !== '' && (vals.port < 1 || vals.port > 65535)) return { ok: false, reason: 'port must be 1-65535' };
    if (vals.uriParams && !URI_PARAMS_RE.test(vals.uriParams)) return { ok: false, reason: "extra URI params must be ';'-separated key=value pairs" };
    return { ok: true, uri: composeUriPreview(vals) };
}

/// Attaches a live ✓/✕ badge (never color-only) to `badge` for an
/// `x-uri-preview`-flagged object whose fields render as descendants of
/// `container`, addressed by `basePath.field` via the data-path attribute
/// every form input already carries. Listens on `container` (event
/// delegation) rather than per-field, since the optional port/user/uriParams
/// fields swap DOM nodes in and out via the +/- add-property placeholder.
function wireUriPreviewBadge(badge, container, basePath) {
    const recompute = () => {
        const vals = {};
        URI_PREVIEW_FIELDS.forEach(f => {
            const el = container.querySelector(`[data-path="${cssEscape(basePath + '.' + f)}"]`);
            vals[f] = el ? readInputValue(el) : null;
        });
        const result = validateUriPreview(vals);
        badge.textContent = result.ok ? '✓' : '✗'; // ✓ / ✕ — never color-only
        badge.classList.toggle('valid', result.ok);
        badge.classList.toggle('invalid', !result.ok);
        badge.title = result.ok ? 'Valid SIP URI: ' + result.uri : 'Invalid: ' + result.reason;
    };
    badge.style.display = '';
    container.addEventListener('input', recompute);
    container.addEventListener('change', recompute);
    container.addEventListener('click', recompute); // +/- placeholder swaps
    recompute();
}

/// Harvest `${var}` names available in the current form — every `id` field
/// across all selectors/connectors/routes/etc. These become autocomplete
/// candidates in template-style inputs.
function availableContextVars() {
    const names = new Set();
    document.querySelectorAll('input[data-path$=".id"]').forEach(input => {
        const v = (input.value || '').trim();
        if (v) names.add(v);
    });
    return Array.from(names).sort();
}

/// Attach ${var} autocomplete to a text input / textarea. Shows a floating
/// popup when the caret is inside an unterminated `${...` sequence.
function attachContextAutocomplete(input) {
    let popup = null;
    const close = () => { if (popup) { popup.remove(); popup = null; } };
    const onInput = () => {
        const caret = input.selectionStart;
        const before = (input.value || '').slice(0, caret);
        const m = before.match(/\$\{([^}]*)$/);
        if (!m) { close(); return; }
        const prefix = m[1];
        const opts = availableContextVars().filter(n => n.startsWith(prefix));
        if (opts.length === 0) { close(); return; }
        if (!popup) {
            popup = document.createElement('div');
            popup.className = 'context-var-popup';
            document.body.appendChild(popup);
        }
        popup.innerHTML = '';
        opts.forEach(name => {
            const opt = document.createElement('div');
            opt.className = 'context-var-option';
            opt.textContent = name;
            opt.onmousedown = (e) => {
                e.preventDefault(); // keep focus on the input
                const start = caret - prefix.length;
                const before2 = input.value.slice(0, start);
                const after2 = input.value.slice(caret);
                const insertion = name + '}';
                input.value = before2 + insertion + after2;
                const newCaret = start + insertion.length;
                input.setSelectionRange(newCaret, newCaret);
                input.focus();
                setDirty();
                close();
            };
            popup.appendChild(opt);
        });
        const rect = input.getBoundingClientRect();
        popup.style.position = 'absolute';
        popup.style.top = (rect.bottom + window.scrollY + 2) + 'px';
        popup.style.left = (rect.left + window.scrollX) + 'px';
    };
    input.addEventListener('input', onInput);
    input.addEventListener('blur', () => setTimeout(close, 150));
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') close();
    });
}

/// Extract a type name from a $ref path, stripping victools' numeric
/// variant suffix. e.g. "#/$defs/AttributeSelector-2" -> "AttributeSelector".
function refTypeName(ref) {
    const parts = String(ref).split('/');
    const last = parts[parts.length - 1] || '';
    return last.replace(/-\d+$/, '') || null;
}

/// Human-readable name for a schema node. Prefers explicit `title`, falls
/// back to the last segment of `$ref`.
function schemaDisplayName(schema) {
    if (!schema) return null;
    if (schema.title) return schema.title;
    if (schema.$ref) return refTypeName(schema.$ref);
    return null;
}

/// Derive a human label for an array's item type — the base class of all
/// variants if polymorphic, or the single type if not. Used to label
/// "Add <type>" buttons. Returns null when no good name can be inferred.
function arrayItemTypeName(itemsSchema) {
    if (!itemsSchema) return null;
    if (itemsSchema.title) return itemsSchema.title;
    if (itemsSchema.$ref) return refTypeName(itemsSchema.$ref);
    const variants = polymorphicVariants(itemsSchema);
    if (!variants || variants.length === 0) return null;
    const names = variants.map(v => {
        if (v.title) return v.title;
        if (v.$ref) return refTypeName(v.$ref);
        return null;
    }).filter(Boolean);
    if (names.length === 0) return null;
    // Longest common suffix across all variant names is the base class.
    const first = names[0];
    let i = 0;
    while (i < first.length &&
           names.every(n => i < n.length && n[n.length - 1 - i] === first[first.length - 1 - i])) {
        i++;
    }
    const suffix = first.slice(first.length - i);
    return /^[A-Z][A-Za-z0-9]+$/.test(suffix) ? suffix : null;
}

function deref(schemaNode) {
    if (!schemaNode || !schemaNode.$ref) return schemaNode;
    const target = resolveRef(schemaNode.$ref);
    if (!target) return schemaNode;
    // If the ref-target itself has a $ref (chained), recurse first.
    const base = target.$ref ? deref(target) : target;
    // Shallow-merge the input's siblings on top of the base. For
    // 'properties', do a deep merge so the discriminator combines with
    // the base properties.
    const merged = Object.assign({}, base);
    for (const key of Object.keys(schemaNode)) {
        if (key === '$ref') continue;
        if (key === 'properties' && base.properties) {
            merged.properties = Object.assign({}, base.properties, schemaNode.properties);
        } else if (key === 'required' && Array.isArray(base.required) && Array.isArray(schemaNode.required)) {
            merged.required = base.required.concat(
                schemaNode.required.filter(r => !base.required.includes(r)));
        } else {
            merged[key] = schemaNode[key];
        }
    }
    return merged;
}

// ---------------------------------------------------------------------------
// Feature: Jackson Identity References
// ---------------------------------------------------------------------------
// BLADE framework classes (Selector, TranslationsMap subclasses, Translation)
// use Jackson's @JsonIdentityInfo(generator = PropertyGenerator.class, property = "id").
//
// Jackson serializes the FIRST occurrence of an object with all its properties:
//   { "id": "From-User", "attribute": "From", "pattern": "...", "expression": "..." }
//
// SUBSEQUENT references to the same object are serialized as just the id string:
//   "From-User"
//
// The form editor detects these back-references by checking when the schema
// expects an object (with an "id" property) but the data contains a plain string.
// Instead of rendering editable form fields, it shows a clickable link that
// navigates to the original definition. On save, reference links emit just the
// id string, preserving the Jackson serialization format.
//
// See CLAUDE.md "Jackson Identity References" section for full documentation.
// ---------------------------------------------------------------------------

// Scan the entire JSON data tree and build a registry of all objects that
// have a string "id" field. Called once when data is loaded into the form.
// Result: identityRegistry maps id strings to { path, object }.
function buildIdentityRegistry(data, path = '') {
    identityRegistry = new Map();
    scanForIdentities(data, path);
}

function scanForIdentities(data, path) {
    if (data === null || data === undefined) return;

    if (Array.isArray(data)) {
        data.forEach((item, idx) => {
            scanForIdentities(item, `${path}[${idx}]`);
        });
    } else if (typeof data === 'object') {
        if (data.id && typeof data.id === 'string') {
            identityRegistry.set(data.id, { path: path, object: data });
        }
        Object.keys(data).forEach(key => {
            const childPath = path ? `${path}.${key}` : key;
            scanForIdentities(data[key], childPath);
        });
    }
}

// Return the polymorphic variants of a schema node, regardless of whether
// the generator emitted oneOf (kjetland) or anyOf (victools).
function polymorphicVariants(schemaNode) {
    if (!schemaNode) return null;
    const branch = schemaNode.oneOf || schemaNode.anyOf;
    if (!branch) return null;
    return branch.map(v => v.$ref ? resolveRef(v.$ref) : v).filter(Boolean);
}

// Build a "Reference ▼" dropdown that lets the user add an existing
// identity (a Jackson back-reference, rendered as a bare id string) to an
// array. Returns null if the array can't accept references at all.
//
// Options come from the global identityRegistry which is rebuilt every
// time the form is generated. We don't filter by type — the user picks
// any id; the registry only knows about objects that have an `id` field.
// If we ever want type-aware filtering we'd need to track the schema path
// of each registered identity.
function createReferencePicker(itemsSchema, container, path, updateStatus) {
    if (!arrayItemsAcceptReferences(itemsSchema)) return null;

    const select = document.createElement('select');
    select.className = 'btn btn-primary reference-picker add-menu-select';
    select.title = 'Add a reference to an existing identity';

    function rebuildOptions() {
        // Ids as the form holds them now: added, renamed or removed since load.
        try { buildIdentityRegistry(getFormData()); } catch (e) { /* keep the last registry */ }
        select.innerHTML = '';
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = '+ Add Reference';
        select.appendChild(placeholder);

        // Walk the registry, filtering to identities whose structure is
        // compatible with this array's item schema.
        const compatible = [];
        identityRegistry.forEach((reg, id) => {
            if (identityMatchesItemsSchema(reg.object, itemsSchema)) {
                compatible.push(id);
            }
        });
        compatible.sort();

        if (compatible.length === 0) {
            const empty = document.createElement('option');
            empty.value = '';
            empty.textContent = '(no compatible identities)';
            empty.disabled = true;
            select.appendChild(empty);
            return;
        }
        compatible.forEach(id => {
            const opt = document.createElement('option');
            opt.value = id;
            opt.textContent = id;
            const reg = identityRegistry.get(id);
            if (reg && reg.path) opt.title = reg.path;
            select.appendChild(opt);
        });
    }

    rebuildOptions();
    // Refresh on each focus so newly-added identities show up without a
    // full form rebuild.
    select.addEventListener('mousedown', rebuildOptions);

    select.onchange = () => {
        const id = select.value;
        if (!id) return;
        addArrayItem(container, itemsSchema, path, id, null, true);
        setDirty();
        if (updateStatus) updateStatus();
        select.value = ''; // reset to placeholder for the next pick
    };

    return select;
}

// Decide whether a registered identity object is structurally compatible
// with the schema describing what an array's items can hold. Used to filter
// the "+ Reference ▼" picker so users only see identities of the right
// type (e.g. only Selectors when picking for a `selectors:` array, not
// every id in the document).
//
// An identity is compatible with a variant when ALL of these hold:
//   1. The identity has at least one non-`id` key. An identity that's
//      ONLY {id} can't be structurally distinguished from anything
//      because every variant has `id` as a property — listing it in
//      every picker is noise. We require at least one discriminating
//      property; sparse `{id}`-only identities never appear in pickers.
//   2. Every key on the identity is also in the variant's `properties`
//      (i.e. the identity doesn't have extra fields the schema doesn't
//      know about).
//   3. If the variant carries a polymorphic discriminator
//      (`properties.type.const`), the identity's `type` matches it.
//
// For polymorphic items (anyOf / oneOf), the identity is compatible if it
// matches at least one variant.
function identityMatchesItemsSchema(identity, itemsSchema) {
    if (!identity || typeof identity !== 'object') return false;
    if (!itemsSchema) return true;

    const idKeys = Object.keys(identity);

    // Rule 1: must have at least one non-id key. {id}-only identities
    // don't carry enough information to filter on; better to hide than
    // to over-suggest in every dropdown.
    if (!idKeys.some(k => k !== 'id')) return false;

    let s = itemsSchema;
    if (s.$ref) s = deref(s);
    if (!s) return true;

    const variants = polymorphicVariants(s) || [s];

    for (const variant of variants) {
        const v = variant.$ref ? deref(variant) : variant;
        if (!v || !v.properties) continue;
        const vKeys = Object.keys(v.properties);

        // Rule 2: every identity key must be a known property of the variant.
        const allKeysKnown = idKeys.every(k => vKeys.includes(k));
        if (!allKeysKnown) continue;

        // Rule 3: if the variant has a const discriminator on `type`, enforce it.
        const tp = v.properties.type;
        if (tp && tp.const !== undefined) {
            if (identity.type !== tp.const) continue;
        }

        return true;
    }
    return false;
}

/// The classes Jackson reads with `@JsonIdentityInfo` (declared or inherited):
/// in them an item's `id` must be unique per class, and a bare id string
/// names an item stated elsewhere in the file. Not every v2 class: v2
/// `AttributeSelector` has none, so its ids may repeat. The v3 classes have
/// none on purpose. ConfiguratorIdentityTest checks this list against the
/// framework jar.
const IDENTITY_CLASSES = new Set([
    'ConfigAddressMap', 'ConfigHashMap', 'ConfigIPv4Map', 'ConfigLinkedHashMap', 'ConfigPrefixMap',
    'ConfigPrefixMapApache', 'ConfigTreeMap', 'Selector', 'ServerPool', 'Translation', 'TranslationsMap',
].map(n => 'org.vorpal.blade.framework.v2.config.' + n));

/// Whether a class takes Jackson identity references (an item named by its id string).
function takesIdentityReferences(r) {
    return !!r && IDENTITY_CLASSES.has(r['x-java-class']);
}

// Returns true if the given items schema could accept a Jackson identity
// reference (a bare id string): at least one resolved variant is an object
// with an `id` property whose class takes identity references.

function arrayItemsAcceptReferences(itemsSchema) {
    if (!itemsSchema) return false;
    let s = itemsSchema;
    if (s.$ref) s = deref(s);
    if (!s) return false;
    const variants = polymorphicVariants(s);
    if (variants) {
        return variants.some(v => {
            const r = v.$ref ? deref(v) : v;
            return r && r.type === 'object' && r.properties && r.properties.id && takesIdentityReferences(r);
        });
    }
    return s.type === 'object' && s.properties && s.properties.id && takesIdentityReferences(s);
}

// Compute a human-friendly label for one polymorphic variant in a
// type-selector dropdown. Looks for, in order:
//   1. variant.title (kjetland sets this directly)
//   2. base.title after deref (the underlying type may carry the title)
//   3. variant.properties.type.const  (victools discriminator value)
//   4. variant.properties.type.enum[0] (kjetland discriminator value)
//   5. fallback "Type N"
function variantLabel(variant, index) {
    if (!variant) return `Type ${index + 1}`;
    if (variant.title) return variant.title;

    const merged = variant.$ref ? deref(variant) : variant;
    if (merged && merged.title) return merged.title;

    const typeProp = merged && merged.properties && merged.properties.type;
    if (typeProp) {
        if (typeProp.const !== undefined) return String(typeProp.const);
        if (Array.isArray(typeProp.enum) && typeProp.enum.length > 0) {
            return String(typeProp.enum[0]);
        }
    }
    return `Type ${index + 1}`;
}

// Detect if a data value is a Jackson identity back-reference.
// Returns true when: the value is a plain string AND the schema expects an
// object type with an "id" property (indicating @JsonIdentityInfo usage).
function isIdentityReference(value, fieldSchema) {
    if (typeof value !== 'string') return false;
    if (!fieldSchema) return false;

    let resolved = fieldSchema;
    if (resolved.$ref) resolved = resolveRef(resolved.$ref);
    if (!resolved) return false;

    const variants = polymorphicVariants(resolved);
    if (variants) {
        // Each variant may be a $ref (+ properties) wrapper: look at what it
        // resolves to, or a plan's ["prefix-map", ...] renders as empty objects.
        return variants.some(v => {
            const r = v.$ref ? deref(v) : v;
            return r && r.type === 'object' && r.properties && r.properties.id;
        });
    }

    return resolved.type === 'object' && resolved.properties && resolved.properties.id;
}

// Render a read-only reference link for a Jackson identity back-reference.
// Displays as: ⇒ From-User (clickable, scrolls to original definition).
// The data-reference-id attribute is read by getFormData() to emit the id
// string when saving, preserving the Jackson serialization format.
function createReferenceLink(id) {
    const container = document.createElement('div');
    container.className = 'reference-link';
    container.setAttribute('data-reference-id', id);

    const icon = document.createElement('span');
    icon.className = 'reference-link-icon';
    icon.textContent = '\u21D2'; // ⇒ arrow

    const link = document.createElement('a');
    link.href = '#';
    link.className = 'reference-link-anchor';
    link.textContent = id;
    link.title = 'Go to definition of ' + id;
    link.onclick = (e) => {
        e.preventDefault();
        scrollToIdentity(id);
    };

    container.appendChild(icon);
    container.appendChild(link);
    return container;
}

// Scroll to and briefly highlight the original object definition.
// Full objects are tagged with DOM id="identity-{id}" during form generation.
function scrollToIdentity(id) {
    const target = document.getElementById('identity-' + id);   // an id, not a selector
    if (target) {
        revealField(target);
        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        target.classList.add('reference-highlight');
        setTimeout(() => target.classList.remove('reference-highlight'), 2000);
    }
}

// Does any variant claim this value's discriminator? Returns true when
// value has no `type` (creating-new is fine — first variant is the default)
// or when at least one variant matches. Returns false only on a real
// schema/value mismatch — caller should preserve the value as opaque JSON
// rather than letting resolveOneOf silently coerce it to variants[0].
function polymorphicMatches(variants, value) {
    if (!variants || variants.length === 0) return true;
    if (!value || typeof value !== 'object' || value.type === undefined) return true;
    const t = String(value.type);
    for (const variant of variants) {
        const typeProp = variant.properties && variant.properties.type;
        if (typeProp && typeProp.const !== undefined && String(typeProp.const) === t) return true;
        if (typeProp && Array.isArray(typeProp.enum) && typeProp.enum.map(String).includes(t)) return true;
        const opts = variant.options && variant.options.multiple_editor_select_via_property;
        if (opts && value[opts.property] === opts.value) return true;
    }
    return false;
}

// Resolve a polymorphic schema (oneOf or anyOf) to the variant matching
// the actual data value's discriminator. Supports three discriminator
// shapes:
//   1. victools (Draft 2020-12): variant has properties.type.const = "..."
//   2. kjetland legacy (Draft 4): variant has properties.type.enum = ["..."]
//   3. kjetland legacy: variant.options.multiple_editor_select_via_property
function resolveOneOf(schemaNode, value) {
    const variants = polymorphicVariants(schemaNode);
    if (!variants) return schemaNode;

    if (value && typeof value === 'object' && value.type) {
        // 1) victools const-based discriminator
        for (const variant of variants) {
            const typeProp = variant.properties && variant.properties.type;
            if (typeProp && typeProp.const !== undefined && typeProp.const === value.type) {
                return variant;
            }
        }
        // 2) kjetland enum-based discriminator
        for (const variant of variants) {
            const typeProp = variant.properties && variant.properties.type;
            if (typeProp && Array.isArray(typeProp.enum) && typeProp.enum.includes(value.type)) {
                return variant;
            }
        }
        // 3) kjetland multiple_editor_select_via_property extension
        for (const variant of variants) {
            const opts = variant.options && variant.options.multiple_editor_select_via_property;
            if (opts && value[opts.property] === opts.value) {
                return variant;
            }
        }
    }

    // No data or no match — return first variant as default
    return variants[0] || schemaNode;
}

function createFormElement(fieldSchema, path, value = null, isMapKey = false) {
    const id = `field_${formIdCounter++}`;

    if (fieldSchema.$ref) {
        fieldSchema = resolveRef(fieldSchema.$ref);
    }

    if (isMapKey) {
        const keyVal = value || '';
        // Constrained keys (schema `propertyNames.enum`, surfaced here as
        // fieldSchema.enum) render as a dropdown so typos can't create dead
        // entries. The current key is always offered even if it's no longer in
        // the list, so editing never silently drops it.
        if (Array.isArray(fieldSchema.enum) && fieldSchema.enum.length) {
            const select = document.createElement('select');
            select.className = 'map-key-input';
            select.setAttribute('data-path', path);
            const empty = document.createElement('option');
            empty.value = '';
            empty.textContent = '-- Select --';
            if (!keyVal) empty.selected = true;
            select.appendChild(empty);
            const opts = fieldSchema.enum.slice();
            if (keyVal && !opts.includes(keyVal)) opts.push(keyVal);
            opts.forEach(o => {
                const optionEl = document.createElement('option');
                optionEl.value = o;
                optionEl.textContent = o;
                if (o === keyVal) optionEl.selected = true;
                select.appendChild(optionEl);
            });
            return select;
        }
        const input = document.createElement('input');
        input.type = 'text';
        input.value = keyVal;
        // Also mirror to the value attribute so outerHTML / DOM dumps
        // reflect the actual displayed value (input.value alone only
        // sets the DOM property, not the HTML attribute).
        input.setAttribute('value', keyVal);
        input.className = 'map-key-input';
        input.setAttribute('data-path', path);
        return input;
    }

    // A schema with `const` is a fixed-value property — typically a
    // polymorphic discriminator (e.g. {const: "hash"} on a TranslationsMap
    // subtype's "type" property). Render as a hidden input pre-populated
    // with the const value, so getFormData picks it up via data-path
    // without exposing it for user editing.
    if (fieldSchema.const !== undefined) {
        const hidden = document.createElement('input');
        hidden.type = 'hidden';
        hidden.id = id;
        hidden.value = String(fieldSchema.const);
        hidden.setAttribute('data-path', path);
        return hidden;
    }

    if (fieldSchema.enum) {
        const select = document.createElement('select');
        select.id = id;
        select.setAttribute('data-path', path);

        // Add empty option when no value is set
        const emptyOption = document.createElement('option');
        emptyOption.value = '';
        emptyOption.textContent = '-- Select --';
        if (value === null || value === undefined || value === '') {
            emptyOption.selected = true;
        }
        select.appendChild(emptyOption);

        fieldSchema.enum.forEach(option => {
            const optionEl = document.createElement('option');
            optionEl.value = option;
            optionEl.textContent = option;
            if (value === option) optionEl.selected = true;
            select.appendChild(optionEl);
        });
        // A value the schema does not list (a renamed constant, a newer app):
        // offered and selected as it is, or Save would drop the field.
        if (value !== null && value !== undefined && value !== '' && !fieldSchema.enum.includes(value)) {
            const optionEl = document.createElement('option');
            optionEl.value = value;
            optionEl.textContent = value + ' (not in the schema)';
            optionEl.selected = true;
            select.appendChild(optionEl);
        }

        return select;
    }

    switch (fieldSchema.type) {
        case 'boolean':
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.id = id;
            checkbox.checked = value === true || value === 'true';   // Jackson reads "true" as true
            checkbox.setAttribute('data-path', path);
            checkbox._schema = fieldSchema;
            return checkbox;

        case 'integer':
        case 'number':
            const numberInput = document.createElement('input');
            numberInput.type = 'number';
            // A number field takes decimals (a site's latitude); an integer whole numbers.
            if (fieldSchema.type === 'number') numberInput.step = 'any';
            numberInput.id = id;
            const numVal = value !== null && value !== undefined ? value : '';
            numberInput.value = numVal;
            numberInput.setAttribute('value', numVal);
            numberInput.setAttribute('data-path', path);
            numberInput._schema = fieldSchema;
            if (fieldSchema.default !== undefined && fieldSchema.default !== null) {
                numberInput.placeholder = String(fieldSchema.default);
            }
            if (fieldSchema['x-readonly']) numberInput.disabled = true;
            attachFieldValidation(numberInput, fieldSchema);
            return numberInput;

        case 'string':
            let textInput;
            // A value with line breaks (JVM arguments, one per line) keeps them:
            // an <input> would silently join the lines.
            if (fieldSchema.format === 'textarea' || (typeof value === 'string' && /[\r\n]/.test(value))) {
                // Multi-line editor for long strings (regex, templates, body).
                textInput = document.createElement('textarea');
                textInput.rows = 3;
            } else {
                textInput = document.createElement('input');
                textInput.type = fieldSchema.format === 'password' ? 'password' : 'text';
            }
            textInput.id = id;
            const strVal = value !== null && value !== undefined ? value : '';
            textInput.value = strVal;
            if (textInput.tagName === 'INPUT') {
                textInput.setAttribute('value', strVal);
            } else {
                textInput.textContent = strVal;
            }
            textInput.setAttribute('data-path', path);
            textInput._schema = fieldSchema;
            // A textarea reports CRLF as LF: remember the file's line ends
            // (SDP and SIP text in CRUD values use CRLF).
            if (typeof value === 'string' && value.includes('\r\n')) textInput.dataset.crlf = 'true';
            // A textarea turns \r\n and a lone \r into \n: the value as drawn
            // is given back exactly while the field is untouched (readInputValue).
            if (typeof value === 'string' && textInput.tagName === 'TEXTAREA') textInput._original = value;
            // Use `default` (from @JsonProperty(defaultValue)) as the placeholder
            // hint so the user sees the expected format even before typing.
            if (fieldSchema.default !== undefined && fieldSchema.default !== null) {
                textInput.placeholder = String(fieldSchema.default);
            }
            if (fieldSchema['x-readonly']) textInput.disabled = true;
            attachFieldValidation(textInput, fieldSchema);
            // Password fields don't need ${var} autocomplete — and we don't
            // want secrets flowing through the variable scanner.
            if (fieldSchema.format !== 'password') {
                attachContextAutocomplete(textInput);
            }
            if (fieldSchema.format === 'password') {
                // Mark for {CLEARTEXT}-wrapping on save, and wrap in a span
                // with an eye toggle so the user can briefly verify the secret.
                textInput.dataset.password = 'true';
                // A multi-line secret (a PEM key) is a textarea, which has no
                // password type: masked by style instead.
                if (textInput.tagName === 'TEXTAREA') textInput.classList.add('secret-masked');
                const wrapper = document.createElement('span');
                wrapper.className = 'password-field';
                const toggle = document.createElement('button');
                toggle.type = 'button';
                toggle.className = 'password-toggle';
                toggle.setAttribute('aria-label', 'Show/hide password');
                toggle.title = 'Show/hide';
                toggle.textContent = '\u{1F441}'; // 👁
                toggle.onclick = (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    if (textInput.tagName === 'TEXTAREA') textInput.classList.toggle('secret-masked');
                    else textInput.type = textInput.type === 'password' ? 'text' : 'password';
                };
                wrapper.appendChild(textInput);
                wrapper.appendChild(toggle);
                return wrapper;
            }
            return textInput;

        default:
            const defaultInput = document.createElement('input');
            defaultInput.type = 'text';
            defaultInput.id = id;
            defaultInput.value = value !== null && value !== undefined ? value : '';
            defaultInput.setAttribute('data-path', path);
            return defaultInput;
    }
}

/// [#createFormGroupElement] with the property it edits named on the
/// element (`data-prop`, the last segment of `path`). getFormData finds a
/// property's field by that name, never by the words its label shows,
/// which are for people and may change.
function createFormGroup(fieldSchema, title, description, path, value = null, isNested = false, originalSchema = null) {
    // A property holding a v2 identity reference: a link, saved as the id.
    if (typeof value === 'string' && isIdentityReference(value, fieldSchema)) {
        const group = document.createElement('div');
        group.className = 'form-group' + (isNested ? ' nested' : '');
        const label = document.createElement('label');
        label.textContent = title || '';
        group.appendChild(label);
        group.appendChild(createReferenceLink(value));
        const prop = (path == null) ? '' : String(path).split('.').pop();
        if (prop && !/\]$/.test(prop)) group.dataset.prop = prop;
        return group;
    }
    const el = createFormGroupElement(fieldSchema, title, description, path, value, isNested, originalSchema);
    const prop = (path == null) ? '' : String(path).split('.').pop();
    if (el && prop && !/\]$/.test(prop)) {
        el.dataset.prop = prop;
        const SEL = '.collapsible-section, .form-group, .checkbox-group';
        if (!el.matches(SEL)) {
            const inner = el.querySelector(':scope > ' + SEL.split(', ').join(', :scope > '));
            if (inner && !inner.dataset.prop) inner.dataset.prop = prop;
        }
    }
    return el;
}

function createFormGroupElement(fieldSchema, title, description, path, value = null, isNested = false, originalSchema = null) {
    // Store original schema for delete functionality (before $ref resolution)
    const schemaForDelete = originalSchema || fieldSchema;

    // deref handles both bare $ref and the victools ref+properties idiom.
    if (fieldSchema.$ref) {
        fieldSchema = deref(fieldSchema);
    }

    if (fieldSchema.oneOf || fieldSchema.anyOf) {
        // When the polymorphic variants are objects (e.g. Authentication:
        // Basic / Bearer / ApiKey / OAuth2...), render a type-picker UI so
        // the user can switch the variant. Without this, the form locks in
        // whichever variant resolveOneOf picked first — the user can't
        // actually choose.
        const allVariants = polymorphicVariants(fieldSchema);
        // Schema/data mismatch: the value carries a `type` no variant claims
        // (e.g. data uses a subtype the deployed schema predates). Preserve
        // the value as opaque JSON so it round-trips intact rather than
        // letting resolveOneOf silently coerce it to variants[0] and drop
        // every field that variant doesn't recognize.
        if (allVariants && !polymorphicMatches(allVariants, value)) {
            return createOpaqueObjectGroup({ type: 'object' }, title, description, path, value, isNested, schemaForDelete);
        }
        const anyObjectVariant = allVariants && allVariants.some(v => {
            const r = v.$ref ? deref(v) : v;
            return r && r.type === 'object';
        });
        if (anyObjectVariant) {
            return createPolymorphicObjectGroup(fieldSchema, title, description, path, value, isNested, schemaForDelete, allVariants);
        }
        fieldSchema = resolveOneOf(fieldSchema, value);
        // Resolved variant may itself be a $ref+siblings node — deref again.
        if (fieldSchema && fieldSchema.$ref) {
            fieldSchema = deref(fieldSchema);
        }
    }

    // Polymorphic discriminator (e.g. {const: "hash"} for a TranslationsMap
    // subtype). Don't render a visible row — emit a hidden input wrapper so
    // getFormData still picks up the value via the data-path attribute.
    if (fieldSchema.const !== undefined) {
        const wrapper = document.createElement('div');
        wrapper.style.display = 'none';
        const hidden = document.createElement('input');
        hidden.type = 'hidden';
        hidden.value = String(fieldSchema.const);
        hidden.setAttribute('data-path', path);
        wrapper.appendChild(hidden);
        return wrapper;
    }

    if (fieldSchema.type === 'object') {
        // Opaque object: schema is just {type: "object"} with no properties
        // and no usable additionalProperties (e.g. v2 Map<String, Object>).
        // Render as a raw JSON textarea — the schema has nothing to drive a
        // form, so let the user edit the JSON directly.
        const hasProperties = fieldSchema.properties &&
            Object.keys(fieldSchema.properties).length > 0;
        const hasUsableAdditional = fieldSchema.additionalProperties &&
            typeof fieldSchema.additionalProperties === 'object';
        if (!hasProperties && !hasUsableAdditional) {
            return createOpaqueObjectGroup(fieldSchema, title, description, path, value, isNested, schemaForDelete);
        }
        // Hybrid (properties + additionalProperties) routes to createObjectGroup,
        // which renders defined props then an inline extras bag. A pure map (no
        // properties) keeps the createMapGroup path.
        if (hasUsableAdditional && !hasProperties) {
            // This is a map
            return createMapGroup(fieldSchema, title, description, path, value, isNested, schemaForDelete);
        } else {
            // Regular object with defined properties
            return createObjectGroup(fieldSchema, title, description, path, value, isNested, schemaForDelete);
        }
    } else if (fieldSchema.type === 'array') {
        return createArrayGroup(fieldSchema, title, description, path, value, isNested, schemaForDelete);
    } else {
        // Simple field
        const group = document.createElement('div');
        group.className = `form-group${isNested ? ' nested' : ''}`;
        // @FormLayout(wide=true) / multiline → full-row tile. The CSS rule on
        // .form-group.nested[data-wide="true"] overrides the default flex basis.
        if (fieldSchema['x-wide'] || fieldSchema.format === 'textarea') {
            group.dataset.wide = 'true';
        }

        // Create header with label and delete button
        const headerRow = document.createElement('div');
        headerRow.className = 'form-group-header';

        const label = document.createElement('label');
        label.textContent = title || 'Field';

        // Hovering the label shows the description tooltip (shared element;
        // see the delegated mouseenter handler in the DOMContentLoaded block).
        if (description) {
            label.classList.add('has-help');
            label.dataset.help = description;
        }

        headerRow.appendChild(label);

        // Add delete button — required properties can't be removed, so they
        // get no [-] button. `x-required-here` is injected by the parent
        // renderer (createObjectGroup.renderPropInto and its siblings) from
        // the parent schema's `required` list.
        let deleteBtn = null;
        if (!fieldSchema['x-required-here'] && !fieldSchema['x-readonly']) {
            deleteBtn = document.createElement('button');
            deleteBtn.type = 'button';
            deleteBtn.className = 'delete-property-btn';
            deleteBtn.innerHTML = '<span class="delete-property-icon">×</span>';
            deleteBtn.title = `Remove ${title}`;
            deleteBtn.onclick = (e) => {
                e.stopPropagation();
                // Replace with placeholder
                const placeholder = createAddPropertyPlaceholder(schemaForDelete, title, description, path, null, isNested);
                group.replaceWith(placeholder);
                setDirty();
            };
            headerRow.appendChild(deleteBtn);
        }

        // Regex-tester button — surfaced whenever the schema carries
        // x-regex-test=true (emitted by @FormLayout(regexTest=true)). Click
        // opens a modal that runs the regex against a sample input and
        // shows named/numbered capture groups.
        if (fieldSchema['x-regex-test']) {
            const testBtn = document.createElement('button');
            testBtn.type = 'button';
            testBtn.className = 'regex-test-btn';
            testBtn.title = 'Test this regex against a sample string';
            testBtn.textContent = 'test';
            testBtn.onclick = (e) => {
                e.stopPropagation();
                const input = group.querySelector('input, textarea');
                if (input) openRegexTest(input);
            };
            headerRow.insertBefore(testBtn, deleteBtn);
        }

        group.appendChild(headerRow);

        const element = createFormElement(fieldSchema, path, value);

        if (fieldSchema.type === 'boolean') {
            const checkboxGroup = document.createElement('div');
            checkboxGroup.className = 'checkbox-group';
            checkboxGroup.appendChild(element);
            headerRow.removeChild(label);
            checkboxGroup.appendChild(label);
            if (deleteBtn) {
                headerRow.removeChild(deleteBtn);
                checkboxGroup.appendChild(deleteBtn);
            }
            // Remove empty header row
            group.removeChild(headerRow);
            group.appendChild(checkboxGroup);
        } else if (element.tagName === 'TEXTAREA') {
            // Multi-line editors keep the two-row layout.
            group.appendChild(element);
        } else {
            // Single-line control rides in the header row — label, control,
            // delete on one line, matching the checkbox layout.
            headerRow.classList.add('inline');
            headerRow.insertBefore(element, label.nextSibling);
        }
        // The label names its control for a screen reader (and a click on it
        // focuses the control).
        const ctl = element.matches && element.matches('input, select, textarea') ? element
            : (element.querySelector && element.querySelector('input, select, textarea'));
        if (ctl) {
            if (!ctl.id || document.getElementById(ctl.id)) ctl.id = 'fld-' + (++fieldIds);
            label.htmlFor = ctl.id;
        }

        return group;
    }
}

/// Update the small grey class-name label on a collapsible header.
/// Pass the FQN (e.g. "org.vorpal.blade.framework.v3.RouterConfiguration").
/// Visible text is the simple name; tooltip is the FQN. Falsy fqn hides the label.
let fieldIds = 0;   // unique ids for label/control pairs

function setJavaClassOnSection(labelEl, fqn) {
    if (!labelEl) return;
    if (!fqn) {
        labelEl.textContent = '';
        labelEl.removeAttribute('title');
        labelEl.classList.add('hidden');
        return;
    }
    const dot = fqn.lastIndexOf('.');
    labelEl.textContent = dot >= 0 ? fqn.substring(dot + 1) : fqn;
    labelEl.title = fqn;
    labelEl.classList.remove('hidden');
}

/// @FormLayout(readOnly=true) on a complex property (object/map/array)
/// disables every control already rendered in its subtree — the data stays
/// visible (and copyable) but nothing is editable, including add/remove
/// buttons. Called after the section content is fully built.
function applyReadOnly(fieldSchema, content) {
    if (fieldSchema['x-readonly'] !== true) return;
    content.classList.add('form-readonly');
    content.querySelectorAll('input, select, textarea, button').forEach(el => el.disabled = true);
    content.querySelectorAll('[draggable="true"]').forEach(el => { el.draggable = false; });   // nor reordered
}

/// What a field shows as its label: the schema's title, else the property
/// name as words ("loggingLevel" as "Logging level"). Display only; the
/// data keeps the name, and the filter still matches it through data-path.
const LABEL_WORDS = { id: 'ID', sdp: 'SDP', url: 'URL', uri: 'URI', json: 'JSON', xml: 'XML', sip: 'SIP', ldap: 'LDAP',
    jdbc: 'JDBC', http: 'HTTP', https: 'HTTPS', ttl: 'TTL', tls: 'TLS', ip: 'IP', jms: 'JMS', snmp: 'SNMP', rest: 'REST',
    api: 'API', ms: 'ms' };
function humanLabel(name) {
    const words = String(name).replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
        .split(/[\s_]+/).filter(Boolean).map(w => w.toLowerCase());
    return words.map((w, i) => LABEL_WORDS[w] || (i ? w : w.charAt(0).toUpperCase() + w.slice(1))).join(' ');
}
function fieldTitle(propSchema, prop) {
    return propSchema.title || humanLabel(prop);
}

/// One line for a folded section: its own fields' values, then its
/// sub-sections by name, so it reads without opening.
function sectionSummary(content) {
    const mine = content.closest('.collapsible-section');
    const parts = [];
    // A list or map says how many items it holds, by name.
    const items = [...content.querySelectorAll('.array-item, .map-entry')].filter(it =>
        it.closest('.collapsible-section') === mine && !it.parentElement.closest('.array-item, .map-entry'));
    if (items.length) {
        const names = items.map((it, i) => {
            // The item's own key or id, not one from a list inside it.
            const key = it.querySelector(':scope > .map-entry-header .map-key-input');
            const ids = [...it.querySelectorAll('input[data-path$=".id"]')]
                .sort((a, b) => a.dataset.path.length - b.dataset.path.length);
            const fields = it.querySelectorAll('input:not([type=checkbox]):not([type=button]), select, textarea');
            const type = it.querySelector(':scope > .array-item-header .array-item-type-label');
            return (key && key.value) || (ids[0] && ids[0].value)
                || (fields.length === 1 && fields[0].type !== 'password' && !fields[0].dataset.password && fields[0].value)
                || (type && type.textContent.trim()) || '#' + (i + 1);
        });
        parts.push(items.length + (items.length === 1 ? ' item: ' : ' items: ') + names.join(', '));
    }
    content.querySelectorAll('.form-group').forEach(g => {
        if (g.closest('.collapsible-section') !== mine || g.querySelector('.collapsible-section')) return;
        if (g.closest('.array-item, .map-entry') && items.some(it => it.contains(g))) return;
        const label = g.querySelector('label');
        const input = g.querySelector('input, select, textarea');
        if (!label || !input) return;
        const name = label.textContent.trim();
        if (input.type === 'checkbox') { if (input.checked) parts.push(name.toLowerCase()); return; }
        // A password field, shown or not (the eye toggle makes it type=text).
        if (input.type === 'password' || input.dataset.password === 'true') { if (input.value) parts.push(name + ' set'); return; }
        const v = String(input.value || '').trim();
        if (v) parts.push(name + ' ' + (v.length > 40 ? v.slice(0, 40) + '…' : v));
    });
    content.querySelectorAll('.collapsible-section').forEach(sub => {
        if (sub.parentElement.closest('.collapsible-section') !== mine) return;
        if (items.some(it => it.contains(sub))) return;
        const t = sub.querySelector(':scope > .collapsible-header .collapsible-name');
        if (t) parts.push(t.textContent.trim());
    });
    return parts.join(' · ');
}
function refreshSectionSummary(header, content) {
    const sum = header.querySelector('.collapsible-summary');
    if (sum) sum.textContent = header.classList.contains('collapsed') ? sectionSummary(content) : '';
}

function createCollapsibleSection(title, description, content, hasData = false, autoCollapse = false, deleteInfo = null, javaClass = null) {
    const section = document.createElement('div');
    section.className = 'collapsible-section';

    const header = document.createElement('div');
    header.className = `collapsible-header${autoCollapse ? ' collapsed' : ''}`;

    const titleContainer = document.createElement('div');
    titleContainer.className = 'collapsible-title';

    const arrow = document.createElement('span');
    arrow.className = `collapsible-arrow${autoCollapse ? ' collapsed' : ''}`;
    arrow.textContent = '▼';
    titleContainer.appendChild(arrow);

    const titleText = document.createElement('span');
    titleText.className = 'collapsible-name';
    titleText.textContent = title;
    titleContainer.appendChild(titleText);
    // Folded, the section says what it holds.
    const summary = document.createElement('span');
    summary.className = 'collapsible-summary';
    titleContainer.appendChild(summary);

    header.appendChild(titleContainer);

    const badgeContainer = document.createElement('div');
    badgeContainer.style.display = 'flex';
    badgeContainer.style.gap = '8px';
    badgeContainer.style.alignItems = 'center';

    if (description) {
        titleText.classList.add('has-help');
        titleText.dataset.help = description;
    }

    // Backing Java class label (small grey text on the right). FQN as tooltip;
    // simple name as the visible text. Created always so polymorphic sections
    // can update the text in-place when the variant changes; .hidden when empty.
    const javaClassLabel = document.createElement('span');
    javaClassLabel.className = 'collapsible-java-class';
    setJavaClassOnSection(javaClassLabel, javaClass);
    badgeContainer.appendChild(javaClassLabel);

    const statusBadge = document.createElement('span');
    statusBadge.className = `collapsible-badge ${hasData ? 'has-data' : 'null'}`;
    statusBadge.textContent = hasData ? 'has data' : 'empty';
    badgeContainer.appendChild(statusBadge);

    // Add delete button if deleteInfo is provided
    if (deleteInfo) {
        const deleteBtn = document.createElement('button');
        deleteBtn.type = 'button';
        deleteBtn.className = 'delete-property-btn';
        deleteBtn.innerHTML = '<span class="delete-property-icon">×</span>';
        deleteBtn.title = `Remove ${title}`;
        deleteBtn.onclick = (e) => {
            e.stopPropagation();
            // Replace with placeholder
            const placeholder = createAddPropertyPlaceholder(
                deleteInfo.schema,
                deleteInfo.title,
                deleteInfo.description,
                deleteInfo.path,
                null,
                deleteInfo.isNested
            );
            section.replaceWith(placeholder);
            setDirty();
        };
        badgeContainer.appendChild(deleteBtn);
    }

    header.appendChild(badgeContainer);

    const contentDiv = document.createElement('div');
    contentDiv.className = `collapsible-content${autoCollapse ? ' collapsed' : ''}`;
    if (autoCollapse) {
        contentDiv.style.maxHeight = '0px';
    }

    contentDiv.appendChild(content);

    header.onclick = () => toggleCollapse(header, contentDiv, arrow);

    section.appendChild(header);
    section.appendChild(contentDiv);
    // Once the section is in the page, its fields hold their values.
    if (autoCollapse) setTimeout(() => refreshSectionSummary(header, contentDiv), 0);

    return section;
}

function toggleCollapse(header, content, arrow) {
    try {
    const isCollapsed = header.classList.contains('collapsed');

    if (isCollapsed) {
        // Expand this section
        header.classList.remove('collapsed');
        content.classList.remove('collapsed');
        arrow.classList.remove('collapsed');

        // Temporarily remove maxHeight restrictions on all ancestors to allow proper measurement
        const ancestors = getAncestorCollapsibleContents(header);
        ancestors.forEach(el => {
            el.style.maxHeight = 'none';
        });

        // Now measure and set the content height for animation
        content.style.maxHeight = content.scrollHeight + 'px';

        // Auto-expand parent sections to provide room
        expandParentSections(header);

        // After animation completes, switch to 'none' so content can grow freely
        setTimeout(() => {
            if (!content.classList.contains('collapsed')) {
                content.style.maxHeight = 'none';
            }
            // Set all expanded ancestors to 'none' as well
            ancestors.forEach(el => {
                if (!el.classList.contains('collapsed')) {
                    el.style.maxHeight = 'none';
                }
            });
        }, 350); // Wait for animation to complete
    } else {
        // Collapse — first set a pixel value so transition can animate from it
        content.style.maxHeight = content.scrollHeight + 'px';
        // Force reflow so browser sees the pixel value before we set 0
        content.offsetHeight;
        header.classList.add('collapsed');
        content.classList.add('collapsed');
        arrow.classList.add('collapsed');
        content.style.maxHeight = '0px';

        // Update parent heights after collapse
        setTimeout(() => {
            recalculateAllAncestorHeights(header.closest('.collapsible-section'));
        }, 350); // Wait for collapse animation
    }
    } finally {
        header.setAttribute('aria-expanded', String(!header.classList.contains('collapsed')));
        refreshSectionSummary(header, content);
    }
}

function getAncestorCollapsibleContents(element) {
    const ancestors = [];
    let current = element.parentElement;
    while (current) {
        if (current.classList.contains('collapsible-content') && !current.classList.contains('collapsed')) {
            ancestors.push(current);
        }
        current = current.parentElement;
    }
    return ancestors;
}

function recalculateAllAncestorHeights(element) {
    if (!element) return;

    // Find all ancestor collapsible contents and recalculate from innermost to outermost
    const ancestors = [];
    let current = element;
    while (current) {
        if (current.classList.contains('collapsible-section')) {
            const content = current.querySelector(':scope > .collapsible-content');
            if (content && !content.classList.contains('collapsed')) {
                ancestors.push(content);
            }
        }
        current = current.parentElement;
    }

    // Set all expanded ancestors to 'none' so they grow freely with content
    ancestors.forEach(content => {
        content.style.maxHeight = 'none';
    });
}

function updateAllParentContainers(element) {
    if (!element) return;

    // Walk up the DOM and set all expanded collapsible-content ancestors to max-height: none
    let current = element.closest('.collapsible-section');
    while (current) {
        const content = current.querySelector(':scope > .collapsible-content');
        if (content && !content.classList.contains('collapsed')) {
            content.style.maxHeight = 'none';
        }
        current = current.parentElement ? current.parentElement.closest('.collapsible-section') : null;
    }
}

function expandParentSections(element) {
    // Find the current collapsible section
    let currentSection = element.closest('.collapsible-section');

    // Look for parent collapsible sections
    let parentElement = currentSection.parentElement;
    while (parentElement) {
        const parentSection = parentElement.closest('.collapsible-section');
        if (parentSection && parentSection !== currentSection) {
            const parentHeader = parentSection.querySelector('.collapsible-header');
            const parentContent = parentSection.querySelector('.collapsible-content');
            const parentArrow = parentSection.querySelector('.collapsible-arrow');

            // If parent is collapsed, expand it
            if (parentHeader && parentHeader.classList.contains('collapsed')) {
                parentHeader.classList.remove('collapsed');
                parentContent.classList.remove('collapsed');
                parentArrow.classList.remove('collapsed');
                parentContent.style.maxHeight = 'none';
            }

            currentSection = parentSection;
            parentElement = parentSection.parentElement;
        } else {
            break;
        }
    }

    // After expanding parents, recalculate heights from bottom up
    setTimeout(() => {
        updateParentHeights(element.closest('.collapsible-section'));
    }, 10);
}

function updateParentHeights(section) {
    if (!section) return;

    const content = section.querySelector('.collapsible-content');
    if (content && !content.classList.contains('collapsed')) {
        content.style.maxHeight = 'none';
    }

    // Walk up and set all expanded ancestor sections to none
    let current = section.parentElement ? section.parentElement.closest('.collapsible-section') : null;
    while (current) {
        const parentContent = current.querySelector(':scope > .collapsible-content');
        if (parentContent && !parentContent.classList.contains('collapsed')) {
            parentContent.style.maxHeight = 'none';
        }
        current = current.parentElement ? current.parentElement.closest('.collapsible-section') : null;
    }
}

function hasValue(value) {
    if (value === null || value === undefined) return false;
    if (typeof value === 'string' && value === '') return false;   // " " is a value (a CRUD replacement)
    if (Array.isArray(value) && value.length === 0) return false;
    if (typeof value === 'object' && Object.keys(value).length === 0) return false;
    return true;
}

function generateDefaultValue(fieldSchema) {
    if (!fieldSchema) return null;

    // Resolve $ref if present. A variant wrapper like
    // {$ref: base, properties: {type: {const: "json"}}} pins the discriminator;
    // merge the wrapper's property overrides on top of the resolved base so
    // those pins survive resolution.
    if (fieldSchema.$ref) {
        const base = resolveRef(fieldSchema.$ref);
        if (!base) return null;
        fieldSchema = {
            ...base,
            ...fieldSchema,
            properties: {
                ...(base.properties || {}),
                ...(fieldSchema.properties || {})
            }
        };
        delete fieldSchema.$ref;
    }

    // Use schema default if provided. Not on a cluster or server file: a
    // default written there overrides whatever the domain file sets, when the
    // operator added the object to change one field.
    const overlay = selectedTargetDirectory && selectedTargetDirectory.type !== 'domain';
    if (fieldSchema.default !== undefined && !overlay) {
        return JSON.parse(JSON.stringify(fieldSchema.default));
    }

    // Pinned value via const (e.g. discriminator: {const: "json"})
    if (fieldSchema.const !== undefined) {
        return JSON.parse(JSON.stringify(fieldSchema.const));
    }

    // No default in the schema: leave a plain value unset, so the field shows
    // as "+ name" and the app's own default applies. Inventing one wrote 0
    // into a REST connector's timeoutSeconds (Java default 5), "" into every
    // string, and the first enum constant, each saved as if chosen.
    switch (fieldSchema.type) {
        case 'string':
        case 'integer':
        case 'number':
        case 'boolean':
            return null;
        case 'array':
            return null;   // no list until one is added
        case 'object':
            if (fieldSchema.properties) {
                // Only what the schema pins (a variant's type) or defaults; an
                // empty {} or [] here would be saved as if chosen.
                const result = {};
                Object.keys(fieldSchema.properties).forEach(prop => {
                    const propDefault = generateDefaultValue(fieldSchema.properties[prop]);
                    if (propDefault !== null) {
                        result[prop] = propDefault;
                    }
                });
                return Object.keys(result).length ? result : null;
            }
            return null;   // a map: no entries until one is added
        default:
            return null;
    }
}

/// A placeholder drawn for a value the file states empty ({}, [], "") keeps
/// that value on Save; one left by removing a field (×) has no mark and drops it.
function markStatedEmpty(placeholder, value) {
    if (isStatedEmpty(value)) {
        placeholder.dataset.statedEmpty = 'true';
        placeholder.title = 'Empty in the file, and saved that way';
    }
    // A null the file states: not the same as absent (it overrides a default,
    // or on a cluster or server file the value below). Said, and removable.
    if (value === null) {
        placeholder.dataset.statedNull = 'true';
        placeholder.title = 'null in this file' + (onOverlayTarget() ? ': overrides the value below it' : '') + '; × removes it';
        const label = placeholder.querySelector('.add-property-label');
        if (label) label.textContent += ' = null';
        const x = document.createElement('button');
        x.type = 'button';
        x.className = 'delete-property-btn';
        x.innerHTML = '<span class="delete-property-icon">×</span>';
        x.title = 'Remove the null';
        x.onclick = (e) => {
            e.stopPropagation();
            delete placeholder.dataset.statedNull;
            placeholder.dataset.nullRemoved = 'true';   // read back as absent
            placeholder.removeAttribute('title');
            if (label) label.textContent = label.textContent.replace(/ = null$/, '');
            x.remove();
            setDirty();
        };
        placeholder.appendChild(x);
    }
}

function createAddPropertyPlaceholder(fieldSchema, title, description, path, container, isNested = false) {
    const placeholder = document.createElement('div');
    placeholder.className = `add-property-placeholder${isNested ? ' nested' : ''}`;
    const prop = (path == null) ? '' : String(path).split('.').pop();
    if (prop && !/\]$/.test(prop)) placeholder.dataset.prop = prop;

    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'add-property-btn';
    addBtn.innerHTML = '<span class="add-property-icon">+</span>';
    addBtn.title = `Add ${title}`;

    const label = document.createElement('span');
    label.className = 'add-property-label';
    label.textContent = title || 'Field';

    // Hovering the label shows the description tooltip.
    if (description) {
        label.classList.add('has-help');
        label.dataset.help = description;
    }

    placeholder.appendChild(addBtn);
    placeholder.appendChild(label);
    // The name is a target too: the "+" alone is a small one.
    label.addEventListener('click', () => addBtn.click());
    label.style.cursor = 'pointer';

    addBtn.onclick = () => {
        // Generate default value and create the actual form element
        const defaultValue = generateDefaultValue(fieldSchema);
        const propGroup = createFormGroup(fieldSchema, title, description, path, defaultValue, isNested);
        // A new value: the one removed before (×) is not its source, or its
        // leftovers (an untyped variant, a mistyped value, unlisted keys) return.
        propGroup.dataset.fresh = 'true';

        // Replace placeholder with actual form group
        placeholder.replaceWith(propGroup);

        // Mark form as dirty since we're adding content
        setDirty();

        // Typing goes where the "+" put the field, as after Add Translation.
        const first = propGroup.querySelector('input:not([type="hidden"]):not([readonly]), textarea, select');
        if (first) first.focus();

        // Update parent heights
        setTimeout(() => {
            recalculateAllAncestorHeights(propGroup.closest('.collapsible-section'));
        }, 10);
    };

    return placeholder;
}

/// Render a polymorphic object field (oneOf/anyOf of object variants) with a
/// type-picker dropdown at the top so the user can switch which variant to
/// edit. Used for fields like RestConnector.authentication where the schema
/// offers several object shapes (Basic, Bearer, ApiKey, OAuth2...).
function createPolymorphicObjectGroup(fieldSchema, title, description, path, value, isNested, schemaForDelete, variants) {
    const content = document.createElement('div');
    content.className = 'polymorphic-content';

    // Header row with type selector.
    const typeRow = document.createElement('div');
    typeRow.className = 'polymorphic-type-row';
    const typeLabel = document.createElement('span');
    typeLabel.className = 'polymorphic-type-label';
    typeLabel.textContent = 'Type:';
    const typeSelect = document.createElement('select');
    typeSelect.className = 'polymorphic-type-select';
    variants.forEach((v, i) => {
        const opt = document.createElement('option');
        opt.value = String(i);
        opt.textContent = variantLabel(v, i);
        typeSelect.appendChild(opt);
    });
    typeRow.appendChild(typeLabel);
    typeRow.appendChild(typeSelect);
    content.appendChild(typeRow);

    // Hidden input carrying the discriminator for extractData/resolveOneOf.
    const hiddenType = document.createElement('input');
    hiddenType.type = 'hidden';
    hiddenType.className = 'polymorphic-type-hidden';
    hiddenType.setAttribute('data-path', path ? `${path}.type` : 'type');
    content.appendChild(hiddenType);

    function variantTypeValue(variant) {
        const tp = variant && variant.properties && variant.properties.type;
        if (!tp) return '';
        if (tp.const !== undefined) return String(tp.const);
        if (Array.isArray(tp.enum) && tp.enum.length > 0) return String(tp.enum[0]);
        return '';
    }

    function variantJavaClass(idx) {
        let vs = variants[idx];
        if (vs && vs.$ref) vs = deref(vs);
        return (vs && vs['x-java-class']) || null;
    }

    // Pick the initial variant from the current value's type, else default to [0].
    let initialIdx = 0;
    if (value && typeof value === 'object' && value.type) {
        for (let i = 0; i < variants.length; i++) {
            if (variantTypeValue(variants[i]) === String(value.type)) { initialIdx = i; break; }
        }
    }

    // Captured after section creation; used to refresh the class label
    // on the section header when the user picks a different variant.
    let javaClassLabelRef = null;

    function renderVariant(idx, variantValue) {
        // Clear everything after the typeRow + hiddenType (indices 0, 1).
        while (content.children.length > 2) content.removeChild(content.lastChild);

        typeSelect.value = String(idx);
        hiddenType.value = variantTypeValue(variants[idx]);

        if (javaClassLabelRef) setJavaClassOnSection(javaClassLabelRef, variantJavaClass(idx));

        let vs = variants[idx];
        if (vs && vs.$ref) vs = deref(vs);
        if (!vs || vs.type !== 'object' || !vs.properties) return;

        const requiredSet = new Set(Array.isArray(vs.required) ? vs.required : []);
        Object.keys(vs.properties).forEach(prop => {
            if (prop === 'type') return; // handled by hiddenType
            const propSchema = vs.properties[prop];
            const propPath = path ? `${path}.${prop}` : prop;
            const propValue = variantValue && variantValue[prop] !== undefined ? variantValue[prop] : null;
            const propTitle = fieldTitle(propSchema, prop);
            const propDescription = propSchema.description;
            // Required-flag injection (suppresses the [-] button), same as createObjectGroup.renderPropInto.
            const childSchema = (requiredSet.has(prop) && !onOverlayTarget())   // an overlay may leave any of them to the file below
                ? Object.assign({}, propSchema, { 'x-required-here': true })
                : propSchema;
            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(childSchema, propTitle, propDescription, propPath, content, true);
                markStatedEmpty(placeholder, propValue);
                content.appendChild(placeholder);
            } else {
                const propGroup = createFormGroup(childSchema, propTitle, propDescription, propPath, propValue, true, propSchema);
                content.appendChild(propGroup);
            }
        });
    }

    typeSelect.onchange = () => {
        if (typeSelect.value === '') {
            // Back to "as in the file below": no type written.
            delete content.dataset.variantChanged;
            setDirty();
            return;
        }
        const newIdx = parseInt(typeSelect.value, 10);
        renderVariant(newIdx, generateDefaultValue(variants[newIdx]));
        // The loaded value was another variant: none of its fields carry over.
        content.dataset.variantChanged = 'true';
        setDirty();
    };

    renderVariant(initialIdx, value);

    // A cluster or server file whose value states fields but no type (one it
    // changes a field of): the type is the file below's, which this page does
    // not show. Said so, and written only if picked; the fields drawn are the
    // first variant's. An item just added with "+" has nothing below it
    // (overlay lists append), so its type is the one drawn, and written.
    if (onOverlayTarget() && changesBelow(value)) {
        const below = document.createElement('option');
        below.value = '';
        below.textContent = 'As in the file below';
        typeSelect.insertBefore(below, typeSelect.firstChild);
        typeSelect.value = '';
    }

    const hasData = hasValue(value);
    // Folded to one line that says what it holds (sectionSummary); a click,
    // or View > Expand All, opens it.
    const autoCollapse = true;
    const deleteInfo = (fieldSchema['x-required-here'] || fieldSchema['x-readonly']) ? null : {
        schema: schemaForDelete || fieldSchema,
        title: title,
        description: description,
        path: path,
        isNested: isNested
    };
    applyReadOnly(fieldSchema, content);
    const section = createCollapsibleSection(title || 'Object', description, content, hasData, autoCollapse, deleteInfo, variantJavaClass(initialIdx));
    javaClassLabelRef = section.querySelector('.collapsible-java-class');
    return section;
}

function createObjectGroup(fieldSchema, title, description, path, value = null, isNested = false, originalSchema = null) {
    const schemaForDelete = originalSchema || fieldSchema;
    const content = document.createElement('div');
    content.className = 'object-content';

    const hasData = hasValue(value);
    // Folded to one line that says what it holds (sectionSummary); a click,
    // or View > Expand All, opens it.
    const autoCollapse = true;

    if (fieldSchema.properties) {
        const requiredSet = new Set(Array.isArray(fieldSchema.required) ? fieldSchema.required : []);
        const sections = Array.isArray(fieldSchema['x-form-sections']) ? fieldSchema['x-form-sections'] : [];
        const groups = Array.isArray(fieldSchema['x-form-groups']) ? fieldSchema['x-form-groups'] : [];
        const columnRows = Array.isArray(fieldSchema['x-form-columns']) ? fieldSchema['x-form-columns'] : [];

        // Fields claimed by any @FormSection render inside that section's
        // bordered container; everything else renders at the top level.
        const fieldsInSections = new Set();
        sections.forEach(s => (s.fields || []).forEach(f => fieldsInSections.add(f)));

        // Fields claimed by any @FormLayoutGroup render as a horizontal row;
        // ungrouped fields render on their own line (full-width). Flat rows
        // (x-form-groups) and column rows (x-form-columns) share one contiguous
        // group-index space so renderPropList's first-encounter walk orders them
        // together. columnsOf holds the per-row stacks; flat rows are absent from it.
        const groupOf = new Map();
        const columnsOf = new Map();
        groups.forEach((row, i) => {
            (row || []).forEach(f => groupOf.set(f, i));
        });
        columnRows.forEach((entry, j) => {
            const gi = groups.length + j;
            const cols = (entry && Array.isArray(entry.columns)) ? entry.columns : [];
            cols.forEach(col => (col || []).forEach(f => groupOf.set(f, gi)));
            columnsOf.set(gi, cols);
        });

        const renderPropInto = (container, prop, groupIndex) => {
            const propSchema = fieldSchema.properties[prop];
            if (!propSchema) return;
            const propPath = path ? `${path}.${prop}` : prop;
            const propValue = value && value[prop] !== undefined ? value[prop] : null;
            const propTitle = fieldTitle(propSchema, prop);
            let propDescription = propSchema.description;
            if (!propDescription && prop === 'type' && propSchema.enum && propSchema.enum.length === 1) {
                propDescription = 'Map type: ' + propSchema.enum[0];
            }
            // Inject required marker via a shallow-cloned schema so we don't
            // mutate the shared $defs schema.
            const childSchema = (requiredSet.has(prop) && !onOverlayTarget())   // an overlay may leave any of them to the file below
                ? Object.assign({}, propSchema, { 'x-required-here': true })
                : propSchema;
            // Fields in a @FormLayoutGroup row flow L-to-R and share the
            // remaining width; standalone fields take a full row.
            const target = container;
            const wideTarget = groupIndex == null;
            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(childSchema, propTitle, propDescription, propPath, target, true);
                markStatedEmpty(placeholder, propValue);
                if (wideTarget) placeholder.dataset.wide = 'true';
                target.appendChild(placeholder);
            } else {
                const propGroup = createFormGroup(childSchema, propTitle, propDescription, propPath, propValue, true, propSchema);
                if (wideTarget) propGroup.dataset.wide = 'true';
                target.appendChild(propGroup);
            }
        };

        // Helper: render a flat list of prop names into a parent, honoring
        // any @FormLayoutGroup rows that apply.
        const renderPropList = (parent, propList) => {
            // Collect group indices present in this subset, ordered by the
            // first occurrence of any group member in propList.
            const groupsHere = new Map(); // groupIndex -> [props in group, in schema order]
            const standalone = [];
            propList.forEach(p => {
                const gi = groupOf.get(p);
                if (gi == null) {
                    standalone.push(p);
                } else {
                    if (!groupsHere.has(gi)) groupsHere.set(gi, []);
                    groupsHere.get(gi).push(p);
                }
            });
            // Render in order: walk propList, emit group rows at the first
            // encounter of a group's props, skip subsequent members (already
            // rendered with the row), emit standalone fields inline.
            const seenGroups = new Set();
            propList.forEach(p => {
                const gi = groupOf.get(p);
                if (gi == null) {
                    renderPropInto(parent, p, null);
                    return;
                }
                if (seenGroups.has(gi)) return;
                seenGroups.add(gi);
                const row = document.createElement('div');
                row.className = 'form-layout-group';
                const cols = columnsOf.get(gi);
                if (cols) {
                    // Column row: each stack is a vertical .form-layout-column;
                    // the columns sit side-by-side in the horizontal row.
                    cols.forEach(colFields => {
                        const colDiv = document.createElement('div');
                        colDiv.className = 'form-layout-column';
                        // Only this list's fields: one a @FormSection draws elsewhere would be drawn twice.
                        (colFields || []).filter(f => propList.includes(f)).forEach(f => renderPropInto(colDiv, f, gi));
                        row.appendChild(colDiv);
                    });
                } else {
                    groupsHere.get(gi).forEach(pp => renderPropInto(row, pp, gi));
                }
                parent.appendChild(row);
            });
        };

        // Top-level (unsectioned) fields in schema order.
        const topLevelProps = Object.keys(fieldSchema.properties)
            .filter(p => !fieldsInSections.has(p));
        renderPropList(content, topLevelProps);

        // Then each @FormSection, in declaration order.
        sections.forEach(section => {
            const wrapper = document.createElement('div');
            wrapper.className = 'form-section';
            const heading = document.createElement('div');
            heading.className = 'form-section-title';
            heading.textContent = section.title || '';
            wrapper.appendChild(heading);
            const body = document.createElement('div');
            body.className = 'form-section-body';
            wrapper.appendChild(body);
            renderPropList(body, (section.fields || []).filter(p => fieldSchema.properties[p]));
            content.appendChild(wrapper);
        });
    }

    // Hybrid bag: defined properties + additionalProperties (e.g. Jackson
    // @JsonAnySetter). Render unknown keys as inline key/value rows.
    if (fieldSchema.additionalProperties && typeof fieldSchema.additionalProperties === 'object') {
        const defined = new Set(Object.keys(fieldSchema.properties || {}));
        const extras = {};
        if (value && typeof value === 'object') {
            Object.keys(value).forEach(k => {
                if (!defined.has(k)) extras[k] = value[k];
            });
        }
        renderInlineExtras(content, fieldSchema.additionalProperties, path, extras);
    }

    // Create deleteInfo for the collapsible section
    const deleteInfo = (fieldSchema['x-required-here'] || fieldSchema['x-readonly']) ? null : {
        schema: schemaForDelete,
        title: title,
        description: description,
        path: path,
        isNested: isNested
    };

    applyReadOnly(fieldSchema, content);
    const section = createCollapsibleSection(title || 'Object', description, content, hasData, autoCollapse, deleteInfo, fieldSchema['x-java-class'] || null);

    // Tag objects that have an "id" field so identity references can link to them
    if (value && typeof value === 'object' && typeof value.id === 'string') {
        section.id = 'identity-' + value.id;
    }

    return section;
}

// Render an opaque object (one with no schema-described properties) as a
// raw JSON textarea. The schema can't drive a form for these — they're
// usually leftover untyped Map<String, Object> fields from older code.
// Let the user edit the JSON directly; getFormData parses it on save.
function createOpaqueObjectGroup(fieldSchema, title, description, path, value = null, isNested = false, originalSchema = null) {
    const schemaForDelete = originalSchema || fieldSchema;
    const content = document.createElement('div');

    const hasData = hasValue(value);
    // Folded to one line that says what it holds (sectionSummary); a click,
    // or View > Expand All, opens it.
    const autoCollapse = true;

    const textarea = document.createElement('textarea');
    textarea.className = 'opaque-object-input';
    textarea.setAttribute('data-path', path);
    textarea.setAttribute('data-opaque-object', 'true');
    textarea.spellcheck = false;
    textarea.rows = Math.max(3, value ? Math.min(20,
        JSON.stringify(value, null, 2).split('\n').length) : 3);

    // Pretty-print the existing value (or empty object placeholder).
    let initialText;
    try {
        initialText = (value !== undefined && value !== null)
            ? JSON.stringify(value, null, 2)
            : '{}';
    } catch (e) {
        initialText = '{}';
    }
    textarea.value = initialText;
    textarea.textContent = initialText; // make outerHTML reflect the value

    // Inline status line for parse errors so the user knows when their
    // JSON is broken before they hit Save.
    const status = document.createElement('div');
    status.className = 'opaque-object-status';
    status.style.fontSize = '11px';
    status.style.marginTop = '4px';
    status.style.minHeight = '14px';

    function validate() {
        const txt = textarea.value.trim();
        if (txt === '' || txt === '{}' || txt === 'null') {
            status.textContent = '';
            textarea.classList.remove('opaque-object-invalid');
            return;
        }
        try {
            JSON.parse(txt);
            status.textContent = '✓ valid JSON';
            status.style.color = 'var(--text-secondary)';
            textarea.classList.remove('opaque-object-invalid');
        } catch (e) {
            status.textContent = '✗ ' + e.message;
            status.style.color = '#c43';
            textarea.classList.add('opaque-object-invalid');
        }
    }
    textarea.addEventListener('input', validate);
    setTimeout(validate, 0);

    content.appendChild(textarea);
    content.appendChild(status);

    const deleteInfo = (fieldSchema['x-required-here'] || fieldSchema['x-readonly']) ? null : {
        schema: schemaForDelete,
        title: title,
        description: description,
        path: path,
        isNested: isNested
    };

    applyReadOnly(fieldSchema, content);
    return createCollapsibleSection(title || 'Object', description, content, hasData, autoCollapse, deleteInfo);
}

function createMapGroup(fieldSchema, title, description, path, value = null, isNested = false, originalSchema = null) {
    const schemaForDelete = originalSchema || fieldSchema;
    // Constrained map keys (schema `propertyNames.enum`) render the key as a
    // dropdown rather than free text.
    const keyEnum = (fieldSchema.propertyNames && Array.isArray(fieldSchema.propertyNames.enum))
        ? fieldSchema.propertyNames.enum : null;
    const content = document.createElement('div');

    const hasData = hasValue(value);
    // Folded to one line that says what it holds (sectionSummary); a click,
    // or View > Expand All, opens it.
    const autoCollapse = true;

    const header = document.createElement('div');
    header.className = 'array-header';

    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'btn btn-primary';
    const valueTypeName = arrayItemTypeName(fieldSchema.additionalProperties);
    addBtn.textContent = valueTypeName ? `Add ${valueTypeName}` : 'Add Entry';
    addBtn.onclick = () => {
        const hint = container.querySelector(':scope > .empty-state-hint');
        if (hint) hint.remove();
        addMapEntry(container, fieldSchema.additionalProperties, path, '', null, true, keyEnum);
        setDirty();
        updateSectionStatus();
    };
    // Values of several types (a keyed pipeline's connectors): pick the type
    // when adding, as for a list; a plain Add always made the first variant.
    const valueVariants = polymorphicVariants(deref(fieldSchema.additionalProperties) || fieldSchema.additionalProperties);
    if (valueVariants && valueVariants.length > 1) {
        const addSelect = document.createElement('select');
        addSelect.className = 'btn btn-primary add-menu-select';
        addSelect.innerHTML = '<option value="">+ ' + escapeHtml(addBtn.textContent) + '</option>'
            + valueVariants.map((v, i) => '<option value="' + i + '">' + escapeHtml(variantLabel(v, i)) + '</option>').join('');
        addSelect.onchange = () => {
            if (addSelect.value === '') return;
            const hint = container.querySelector(':scope > .empty-state-hint');
            if (hint) hint.remove();
            const variant = valueVariants[parseInt(addSelect.value, 10)];
            addMapEntry(container, fieldSchema.additionalProperties, path, '', generateDefaultValue(variant), true, keyEnum);
            setDirty();
            updateSectionStatus();
            addSelect.value = '';
        };
        header.appendChild(addSelect);
    } else {
        header.appendChild(addBtn);
    }

    content.appendChild(header);

    const container = document.createElement('div');
    container.className = 'map-container';
    container.setAttribute('data-path', path);
    content.appendChild(container);

    // Empty-state hint: when there are no entries, show the map's description
    // (from @JsonPropertyDescription) so the user knows what to add.
    if (!hasData && description) {
        const hint = document.createElement('div');
        hint.className = 'empty-state-hint';
        hint.textContent = description;
        container.appendChild(hint);
    }

    // Add existing entries
    if (value) {
        Object.keys(value).forEach(key => {
            addMapEntry(container, fieldSchema.additionalProperties, path, key, value[key], false, keyEnum);
        });
    }

    // Create deleteInfo for the collapsible section
    const deleteInfo = (fieldSchema['x-required-here'] || fieldSchema['x-readonly']) ? null : {
        schema: schemaForDelete,
        title: title,
        description: description,
        path: path,
        isNested: isNested
    };

    applyReadOnly(fieldSchema, content);
    const section = createCollapsibleSection(title || 'Map', description, content, hasData, autoCollapse, deleteInfo);

    // Function to update section status when items are added/removed
    function updateSectionStatus() {
        const badge = section.querySelector('.collapsible-badge.null, .collapsible-badge.has-data');
        const hasItems = container.children.length > 0;
        badge.textContent = hasItems ? 'has data' : 'empty';
        badge.className = `collapsible-badge ${hasItems ? 'has-data' : 'null'}`;
    }

    return section;
}

function createArrayGroup(fieldSchema, title, description, path, value = null, isNested = false, originalSchema = null) {
    const schemaForDelete = originalSchema || fieldSchema;
    const content = document.createElement('div');
    content.className = 'form-group array';

    const hasData = Array.isArray(value) && value.length > 0;
    // Folded to one line that says what it holds (sectionSummary); a click,
    // or View > Expand All, opens it.
    const autoCollapse = true;

    const header = document.createElement('div');
    header.className = 'array-header';

    // Container is declared early so the reference picker (built inside the
    // header) can reference it before append order matters.
    const container = document.createElement('div');
    container.className = 'array-container';
    container.setAttribute('data-path', path);

    // Drag-to-reorder. Only .array-item children participate; the .dragging
    // class is set on the item whose drag handle is active. The cursor's
    // vertical position picks the insertion point among the siblings.
    container.addEventListener('dragover', (e) => {
        const dragging = container.querySelector(':scope > .array-item.dragging');
        if (!dragging) return;
        e.preventDefault();
        const siblings = Array.from(container.children)
            .filter(c => c.classList.contains('array-item') && c !== dragging);
        let afterEl = null;
        let closestOffset = Number.NEGATIVE_INFINITY;
        for (const sib of siblings) {
            const box = sib.getBoundingClientRect();
            const offset = e.clientY - box.top - box.height / 2;
            if (offset < 0 && offset > closestOffset) {
                closestOffset = offset;
                afterEl = sib;
            }
        }
        if (afterEl) container.insertBefore(dragging, afterEl);
        else container.appendChild(dragging);
    });

    // Resolve polymorphic variants (oneOf/anyOf) for the type selector
    const oneOfVariants = fieldSchema.items
        ? polymorphicVariants((fieldSchema.items.$ref ? deref(fieldSchema.items) : null) || fieldSchema.items)
        : null;

    const itemTypeName = arrayItemTypeName(fieldSchema.items);
    if (oneOfVariants && oneOfVariants.length > 1) {
        // Polymorphic items: a single "+ Add <Type> ▾" menu-select lists all
        // variants. Picking one adds an item of that type. More obvious than
        // the old split [Add Item button] + [type select] UI.
        const addSelect = document.createElement('select');
        addSelect.className = 'btn btn-primary add-menu-select';
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = `+ Add ${itemTypeName || 'Item'}`;
        addSelect.appendChild(placeholder);
        oneOfVariants.forEach((variant, i) => {
            const opt = document.createElement('option');
            opt.value = String(i);
            opt.textContent = variantLabel(variant, i);
            addSelect.appendChild(opt);
        });
        addSelect.onchange = () => {
            const raw = addSelect.value;
            if (raw === '') return;
            const selectedVariant = oneOfVariants[parseInt(raw, 10)];
            const defaultVal = generateDefaultValue(selectedVariant);
            addArrayItem(container, fieldSchema.items, path, defaultVal, null, true);
            setDirty();
            updateSectionStatus();
            addSelect.value = '';
        };
        // Project convention: add controls on the left, remove controls on the right.
        header.appendChild(addSelect);
        const refSelect = createReferencePicker(fieldSchema.items, container, path,
            () => updateSectionStatus());
        if (refSelect) header.appendChild(refSelect);
    } else {
        const addBtn = document.createElement('button');
        addBtn.type = 'button';
        addBtn.className = 'btn btn-primary';
        addBtn.textContent = `+ Add ${itemTypeName || 'Item'}`;
        addBtn.onclick = () => {
            addArrayItem(container, fieldSchema.items, path, null, null, true);
            setDirty();
            updateSectionStatus();
        };
        header.appendChild(addBtn);
        const refSelect = createReferencePicker(fieldSchema.items, container, path,
            () => updateSectionStatus());
        if (refSelect) header.appendChild(refSelect);
    }

    content.appendChild(header);
    content.appendChild(container);

    // Empty-state hint for empty arrays — surfaces @JsonPropertyDescription
    // as guidance so the user knows what this list is for before they add.
    if ((!Array.isArray(value) || value.length === 0) && description) {
        const hint = document.createElement('div');
        hint.className = 'empty-state-hint';
        hint.textContent = description;
        container.appendChild(hint);
    }

    // Add existing items
    if (Array.isArray(value)) {
        value.forEach((item, index) => {
            addArrayItem(container, fieldSchema.items, path, item, index);
        });
    }

    // Create deleteInfo for the collapsible section
    const deleteInfo = (fieldSchema['x-required-here'] || fieldSchema['x-readonly']) ? null : {
        schema: schemaForDelete,
        title: title,
        description: description,
        path: path,
        isNested: isNested
    };

    applyReadOnly(fieldSchema, content);
    const section = createCollapsibleSection(title || 'Array', description, content, hasData, autoCollapse, deleteInfo);

    // Function to update section status when items are added/removed
    function updateSectionStatus() {
        const badge = section.querySelector('.collapsible-badge.null, .collapsible-badge.has-data');
        const hasItems = container.children.length > 0;
        badge.textContent = hasItems ? 'has data' : 'empty';
        badge.className = `collapsible-badge ${hasItems ? 'has-data' : 'null'}`;
    }

    return section;
}

/// Render inline key/value rows for a hybrid object's "extras" bag —
/// an object schema with both defined `properties` and `additionalProperties`
/// (Jackson @JsonAnySetter / @JsonAnyGetter pattern). The extras live at the
/// same JSON level as the defined props, so we don't wrap them in a sub-map.
function renderInlineExtras(parent, valueTypeSchema, basePath, extras) {
    const wrapper = document.createElement('div');
    wrapper.className = 'extras-inline';
    wrapper.setAttribute('data-extras-path', basePath || '');

    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'btn btn-primary extras-add-btn';
    addBtn.textContent = '+ Add Extra';
    addBtn.onclick = () => {
        addExtraRow(wrapper, valueTypeSchema, basePath, '', null);
        setDirty();
    };
    wrapper.appendChild(addBtn);

    Object.keys(extras || {}).forEach(key => {
        addExtraRow(wrapper, valueTypeSchema, basePath, key, extras[key]);
    });

    parent.appendChild(wrapper);
}

/// A map key as read back: exactly as the file stated it while untouched
/// (the input's `value` attribute holds the key as loaded), trimmed once typed.
function mapKeyOf(input) {
    const raw = input.value;
    return raw === input.getAttribute('value') ? raw : raw.trim();
}

function addExtraRow(wrapper, valueTypeSchema, basePath, key, value) {
    const row = document.createElement('div');
    row.className = 'extras-row';

    const keyInput = document.createElement('input');
    keyInput.type = 'text';
    keyInput.className = 'extras-key-input';
    keyInput.placeholder = 'key';
    const keyVal = key || '';
    keyInput.value = keyVal;
    keyInput.setAttribute('value', keyVal);
    keyInput.oninput = () => setDirty();

    const valueInput = createFormElement(valueTypeSchema || { type: 'string' },
        basePath ? `${basePath}.${key}` : key, value);
    valueInput.classList.add('extras-value-input');

    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'delete-property-btn';
    removeBtn.innerHTML = '<span class="delete-property-icon">×</span>';
    removeBtn.title = 'Remove extra';
    removeBtn.onclick = () => {
        row.remove();
        setDirty();
    };

    row.appendChild(keyInput);
    row.appendChild(valueInput);
    row.appendChild(removeBtn);

    wrapper.appendChild(row);
}

/// One entry of a map. `added`: the operator added it (Add), else it is drawn
/// from the file. An added entry goes last: a map's order is run order (a
/// keyed pipeline, rule sets), and entries cannot be dragged.
function addMapEntry(container, valueSchema, basePath, key = '', value = null, added = false, keyEnum = null) {
    const hint = container.querySelector(':scope > .empty-state-hint');
    if (hint) hint.remove();
    const entry = document.createElement('div');
    entry.className = 'map-entry';
    // The value this entry was drawn from; it stays with the entry through a key rename.
    entry._source = value;
    // A null the file states (not one the operator added): kept until edited.
    entry._statedNull = value === null && !added;
    // The key as the file has it: "" is a legal key there, not a blank row.
    if (!added) entry._fileKey = key;

    // Tag map-entry values that have an id field so identity references
    // pointing at them (e.g. plan: ["callers"] referencing maps[0].map.callers)
    // can scroll/highlight on click. Without this, only top-level objects
    // and array items got DOM identity tags — map values were silently missed.
    if (value && typeof value === 'object' && typeof value.id === 'string') {
        entry.id = 'identity-' + value.id;
    }

    const header = document.createElement('div');
    header.className = 'map-entry-header';

    // Collapse arrow
    const arrow = document.createElement('span');
    arrow.className = 'collapsible-arrow';
    arrow.textContent = '▼';
    header.appendChild(arrow);

    // Key input directly in header. When the map declares a fixed key set
    // (propertyNames.enum, threaded in as keyEnum), render a dropdown.
    const keySchema = (keyEnum && keyEnum.length) ? { type: 'string', enum: keyEnum } : { type: 'string' };
    const keyInput = createFormElement(keySchema, '', key, true);
    keyInput.placeholder = 'Key';
    header.appendChild(keyInput);

    // Hidden unless valueSchema turns out to carry x-uri-preview (deref'd
    // below); cheap to create unconditionally, avoids an awkward mid-function
    // insertion point once the schema is resolved.
    const uriBadge = document.createElement('span');
    uriBadge.className = 'uri-preview-badge';
    uriBadge.style.display = 'none';
    header.appendChild(uriBadge);

    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'delete-property-btn';
    removeBtn.innerHTML = '<span class="delete-property-icon">×</span>';
    removeBtn.title = 'Remove entry';
    removeBtn.onclick = () => {
        entry.remove();
        setDirty();
        // Update parent section status and heights
        updateParentSectionStatus(container);
        setTimeout(() => {
            recalculateAllAncestorHeights(container.closest('.collapsible-section'));
        }, 10);
    };
    header.appendChild(removeBtn);

    entry.appendChild(header);

    // Collapsible content wrapper for the entry's values
    const entryContent = document.createElement('div');
    entryContent.className = 'map-entry-content';

    const valuePath = basePath ? `${basePath}.${key}` : key;

    // A v2 identity reference (the id of an object defined elsewhere): shown as
    // a link and saved as the id, not drawn as an empty object.
    if (typeof value === 'string' && isIdentityReference(value, valueSchema)) {
        entryContent.appendChild(createReferenceLink(value));
        entry.appendChild(entryContent);
        container.appendChild(entry);
        return;
    }

    if (valueSchema.$ref) {
        valueSchema = deref(valueSchema);
    }

    if (valueSchema.oneOf || valueSchema.anyOf) {
        // A value no variant claims (a type newer than the schema) is kept as
        // JSON, as for properties and list items; resolving it to the first
        // variant would change its type and drop its fields.
        const entryVariants = polymorphicVariants(valueSchema);
        if (entryVariants && value && !polymorphicMatches(entryVariants, value)) {
            valueSchema = { type: 'object' };
        } else {
            valueSchema = resolveOneOf(valueSchema, value);
            if (valueSchema && valueSchema.$ref) {
                valueSchema = deref(valueSchema);
            }
        }
    }

    if (valueSchema.type === 'object' && valueSchema.properties) {
        // Render object properties directly without a collapsible wrapper
        const requiredSet = new Set(Array.isArray(valueSchema.required) ? valueSchema.required : []);
        Object.keys(valueSchema.properties).forEach(prop => {
            const propSchema = valueSchema.properties[prop];
            const propPath = valuePath ? `${valuePath}.${prop}` : prop;
            const propValue = value && value[prop] !== undefined ? value[prop] : null;
            const propTitle = fieldTitle(propSchema, prop);
            const propDescription = propSchema.description;
            // Required-flag injection (suppresses the [-] button), same as createObjectGroup.renderPropInto.
            const childSchema = (requiredSet.has(prop) && !onOverlayTarget())   // an overlay may leave any of them to the file below
                ? Object.assign({}, propSchema, { 'x-required-here': true })
                : propSchema;

            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(childSchema, propTitle, propDescription, propPath, entryContent, true);
                markStatedEmpty(placeholder, propValue);
                entryContent.appendChild(placeholder);
            } else {
                const propGroup = createFormGroup(childSchema, propTitle, propDescription, propPath, propValue, true, propSchema);
                entryContent.appendChild(propGroup);
            }
        });
        if (valueSchema['x-uri-preview']) {
            wireUriPreviewBadge(uriBadge, entryContent, valuePath);
        }
        // Hybrid object: defined properties + additionalProperties (e.g. a
        // Jackson @JsonAnySetter bag like Translation.extras). Render the
        // unknown keys as inline key/value rows alongside the defined props.
        if (valueSchema.additionalProperties && typeof valueSchema.additionalProperties === 'object') {
            const defined = new Set(Object.keys(valueSchema.properties));
            const extras = {};
            if (value && typeof value === 'object') {
                Object.keys(value).forEach(k => {
                    if (!defined.has(k)) extras[k] = value[k];
                });
            }
            renderInlineExtras(entryContent, valueSchema.additionalProperties, valuePath, extras);
        }
    } else if (valueSchema.type === 'array' || valueSchema.type === 'object') {
        // A list, or a map / opaque object (a Translation is a map of strings):
        // a whole group. A plain input would show "[object Object]" and save it.
        const valueGroup = createFormGroup(valueSchema, null, null, valuePath, value, true);
        entryContent.appendChild(valueGroup);
    } else {
        const valueInput = createFormElement(valueSchema, valuePath, value);
        entryContent.appendChild(valueInput);
    }

    entry.appendChild(entryContent);

    // Wire up collapse toggle on the header
    header.style.cursor = 'pointer';
    header.onclick = (e) => {
        // Don't toggle when clicking input, button, or inside them
        if (e.target.closest('input, button, select')) return;
        toggleCollapse(header, entryContent, arrow);

        // When expanding, ensure inner non-toggleable sections are also expanded
        if (!header.classList.contains('collapsed')) {
            entryContent.querySelectorAll('.collapsible-header.collapsed').forEach(innerHeader => {
                const innerArrow = innerHeader.querySelector('.collapsible-arrow');
                if (innerArrow && innerArrow.style.display === 'none') {
                    const innerContent = innerHeader.nextElementSibling;
                    innerHeader.classList.remove('collapsed');
                    innerContent.classList.remove('collapsed');
                    innerContent.style.maxHeight = 'none';
                }
            });
        }
    };

    // Remove redundant collapse arrows from direct child collapsible sections —
    // the map entry's own arrow already handles collapsing all content.
    entryContent.querySelectorAll(':scope > .collapsible-section > .collapsible-header').forEach(innerHeader => {
        const innerArrow = innerHeader.querySelector('.collapsible-arrow');
        if (innerArrow) innerArrow.style.display = 'none';
        innerHeader.onclick = null;
        innerHeader.style.cursor = 'default';
    });

    container.appendChild(entry);
    if (added) {
        entry.scrollIntoView({ block: 'nearest' });
        const keyInput = entry.querySelector('.map-key-input');
        if (keyInput) keyInput.focus();
    }

    // Update parent heights after adding
    setTimeout(() => {
        recalculateAllAncestorHeights(container.closest('.collapsible-section'));
    }, 10);
}

/// The ☰ that reorders a list item. Every kind of item has one: Add puts a
/// new item first, and in an ordered list (a plan, rules) the order is what
/// runs, so a reference or a plain value must be movable too.
function makeDragHandle(item) {
    const dragHandle = document.createElement('span');
    dragHandle.className = 'drag-handle';
    dragHandle.draggable = true;
    dragHandle.title = 'Drag to reorder';
    dragHandle.textContent = '\u2630'; // ☰
    dragHandle.addEventListener('dragstart', (e) => {
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', '');
        item.classList.add('dragging');
    });
    dragHandle.addEventListener('dragend', () => {
        if (item.classList.contains('dragging')) {
            item.classList.remove('dragging');
            setDirty(true);   // moved, not edited
        }
    });
    return dragHandle;
}

function addArrayItem(container, itemSchema, basePath, value = null, index = null, prepend = false) {
    // Clear any empty-state hint — now that we're adding a real item, the
    // hint's job is done.
    const hint = container.querySelector(':scope > .empty-state-hint');
    if (hint) hint.remove();
    const currentIndex = index !== null ? index : container.children.length;
    const itemPath = `${basePath}[${currentIndex}]`;

    // Helper: insert at top when user clicks "Add Item" so the new entry
    // is visible right next to the button. Bulk-load (rendering existing
    // data) keeps document order via plain append.
    const placeItem = (el) => {
        if (prepend && container.firstChild) {
            container.insertBefore(el, container.firstChild);
        } else {
            container.appendChild(el);
        }
    };

    const item = document.createElement('div');
    item.className = 'array-item';
    // The value this item was drawn from, so the read-back compares it with
    // itself after items are added, removed or moved (not by position).
    item._source = value;
    // A null the file states (a new item is drawn with prepend): kept until edited.
    item._statedNull = value === null && !prepend;

    // Save original schema before resolution for identity reference check
    const originalItemSchema = itemSchema;
    if (itemSchema.$ref) {
        itemSchema = deref(itemSchema);
    }

    // Check for Jackson identity reference (string value where object expected)
    if (typeof value === 'string' && isIdentityReference(value, originalItemSchema)) {
        const header = document.createElement('div');
        header.className = 'array-item-header';

        const removeBtn = document.createElement('button');
        removeBtn.type = 'button';
        removeBtn.className = 'delete-property-btn';
        removeBtn.innerHTML = '<span class="delete-property-icon">×</span>';
        removeBtn.title = 'Remove item';
        removeBtn.onclick = (e) => {
            e.stopPropagation();
            item.remove();
            setDirty();
            updateParentSectionStatus(container);
            setTimeout(() => {
                recalculateAllAncestorHeights(container.closest('.collapsible-section'));
            }, 10);
        };
        header.appendChild(makeDragHandle(item));
        header.appendChild(removeBtn);
        item.appendChild(header);
        item.appendChild(createReferenceLink(value));
        placeItem(item);

        setTimeout(() => {
            recalculateAllAncestorHeights(container.closest('.collapsible-section'));
        }, 10);
        return;
    }

    if (itemSchema.oneOf || itemSchema.anyOf) {
        // Schema/data mismatch (see createFormGroup for the full rationale):
        // preserve the value as opaque JSON instead of letting resolveOneOf
        // silently coerce to variants[0] and drop unrecognized fields.
        const itemVariants = polymorphicVariants(itemSchema);
        if (itemVariants && !polymorphicMatches(itemVariants, value)) {
            itemSchema = { type: 'object' };
        } else {
            itemSchema = resolveOneOf(itemSchema, value);
            // resolveOneOf returns the matching variant — which in victools'
            // schema is a thin {$ref + properties} wrapper. Deref to merge
            // the base type's id/description/translations into the visible
            // schema, otherwise the form would only show the discriminator.
            if (itemSchema && itemSchema.$ref) {
                itemSchema = deref(itemSchema);
            }
        }
    }

    // Create remove button (shared across all item types)
    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'delete-property-btn';
    removeBtn.innerHTML = '<span class="delete-property-icon">×</span>';
    removeBtn.title = 'Remove item';
    removeBtn.onclick = (e) => {
        e.stopPropagation();
        item.remove();
        setDirty();
        // Update parent section status and heights
        updateParentSectionStatus(container);
        setTimeout(() => {
            recalculateAllAncestorHeights(container.closest('.collapsible-section'));
        }, 10);
    };

    // Duplicate button — extracts the current item's value, then renders a
    // fresh clone right after it. Only shown on object-with-properties items.
    const duplicateBtn = document.createElement('button');
    duplicateBtn.type = 'button';
    duplicateBtn.className = 'duplicate-item-btn';
    duplicateBtn.innerHTML = '<span class="duplicate-item-icon">\u29C9</span>'; // ⧉
    duplicateBtn.title = 'Duplicate item';
    duplicateBtn.onclick = (e) => {
        e.stopPropagation();
        // The item's own value from a fresh read-back, not the value now at
        // the path it was drawn at (an outer list may have been reordered).
        getFormData();
        // Inside a value read back whole (untouched, untyped or mistyped) the
        // items were not visited: an untouched item is its own source.
        let own = itemValues.get(item);
        if (own === undefined) own = item._source;
        if (own === undefined || own === null) return;
        const clone = JSON.parse(JSON.stringify(own));
        // Its own id: two items with one id fail to load where ids are
        // identities (v2), and are ambiguous anywhere else.
        // Every id inside it too (a map of translations, inline selectors):
        // ids are one namespace for the whole file. References inside the
        // copy to the copy's own objects follow the rename.
        {
            const taken = new Set();
            const collect = v => {
                if (Array.isArray(v)) v.forEach(collect);
                else if (v && typeof v === 'object') { if (typeof v.id === 'string') taken.add(v.id); Object.values(v).forEach(collect); }
            };
            collect(getFormData());
            const renamed = new Map();
            const rename = v => {
                if (Array.isArray(v)) v.forEach(rename);
                else if (v && typeof v === 'object') {
                    if (typeof v.id === 'string' && v.id) {
                        let n = 2, id = v.id + '-copy';
                        while (taken.has(id)) id = v.id + '-copy' + n++;
                        taken.add(id);
                        renamed.set(v.id, id);
                        v.id = id;
                    }
                    Object.values(v).forEach(rename);
                }
            };
            rename(clone);
            const follow = v => {
                if (Array.isArray(v)) return v.map(x => typeof x === 'string' && renamed.has(x) ? renamed.get(x) : follow(x));
                if (v && typeof v === 'object') {
                    for (const k of Object.keys(v)) {
                        if (k !== 'id' && typeof v[k] === 'string' && renamed.has(v[k])) v[k] = renamed.get(v[k]);
                        else v[k] = follow(v[k]);
                    }
                }
                return v;
            };
            follow(clone);
        }
        // Render at the end, then move right after the source item.
        const childrenBefore = container.children.length;
        addArrayItem(container, originalItemSchema, basePath, clone, null, false);
        const added = container.children[childrenBefore] || container.lastElementChild;
        if (added && added !== item) item.insertAdjacentElement('afterend', added);
        setDirty();
        updateParentSectionStatus(container);
    };

    if (itemSchema.type === 'object' && itemSchema.additionalProperties) {
        // Map type — render as inline key-value pairs within the array item
        const header = document.createElement('div');
        header.className = 'array-item-header';
        header.appendChild(makeDragHandle(item));
        header.appendChild(removeBtn);
        item.appendChild(header);

        const mapHeader = document.createElement('div');
        mapHeader.className = 'array-header';
        const addBtn = document.createElement('button');
        addBtn.type = 'button';
        addBtn.className = 'btn btn-primary';
        const valueTypeName = arrayItemTypeName(itemSchema.additionalProperties);
        addBtn.textContent = valueTypeName ? `Add ${valueTypeName}` : 'Add Entry';
        addBtn.onclick = () => {
            addMapEntry(mapContainer, itemSchema.additionalProperties, itemPath, '', null, true);
            setDirty();
        };
        mapHeader.appendChild(addBtn);
        item.appendChild(mapHeader);

        const mapContainer = document.createElement('div');
        mapContainer.className = 'map-container';
        mapContainer.setAttribute('data-path', itemPath);
        item.appendChild(mapContainer);

        // Add existing entries
        if (value && typeof value === 'object') {
            Object.keys(value).forEach(key => {
                addMapEntry(mapContainer, itemSchema.additionalProperties, itemPath, key, value[key]);
            });
        }
    } else if (itemSchema.type === 'object' && itemSchema.properties) {
        // Collapsible header with arrow + read-only type pill + duplicate/delete.
        const header = document.createElement('div');
        header.className = 'array-item-header';

        const arrow = document.createElement('span');
        arrow.className = 'collapsible-arrow';
        arrow.textContent = '\u25BC'; // ▼
        header.appendChild(arrow);

        // Drag handle — only element that initiates reorder. Kept narrow so
        // it doesn't steal clicks from the header's collapse toggle.
        header.appendChild(makeDragHandle(item));

        // Read-only pill — e.g. "Selector: sdp" — identifies the item's
        // variant. Users who want a different type delete this item and
        // pick from the "+ Add <Type> ▾" menu at the top of the array.
        {
            const baseName = schemaDisplayName(originalItemSchema) || schemaDisplayName(itemSchema);
            const labelParts = [];
            if (baseName) labelParts.push(baseName);
            if (value && typeof value === 'object' && typeof value.type === 'string' && value.type) {
                labelParts.push(value.type);
            }
            if (labelParts.length > 0) {
                const typeLabel = document.createElement('span');
                typeLabel.className = 'array-item-type-label';
                typeLabel.textContent = labelParts.join(': ');
                header.appendChild(typeLabel);
            }
        }

        // Backing Java class label (small grey monospace, FQN as tooltip).
        // Same x-java-class attribute used by createCollapsibleSection.
        const javaClassLabel = document.createElement('span');
        javaClassLabel.className = 'collapsible-java-class';
        setJavaClassOnSection(javaClassLabel, itemSchema['x-java-class'] || null);
        header.appendChild(javaClassLabel);

        header.appendChild(duplicateBtn);
        header.appendChild(removeBtn);
        item.appendChild(header);

        // Body wraps all rendered fields so the whole item collapses as a unit.
        const body = document.createElement('div');
        body.className = 'array-item-body';
        item.appendChild(body);

        // Tag full objects with an "id" field for identity reference linking
        if (value && typeof value === 'object' && typeof value.id === 'string') {
            item.id = 'identity-' + value.id;
        }

        const requiredSet = new Set(Array.isArray(itemSchema.required) ? itemSchema.required : []);
        Object.keys(itemSchema.properties).forEach(prop => {
            const propSchema = itemSchema.properties[prop];
            const propPath = itemPath ? `${itemPath}.${prop}` : prop;
            const propValue = value && value[prop] !== undefined ? value[prop] : null;
            const propTitle = fieldTitle(propSchema, prop);
            const propDescription = propSchema.description;
            // Required-flag injection (suppresses the [-] button), same as createObjectGroup.renderPropInto.
            const childSchema = (requiredSet.has(prop) && !onOverlayTarget())   // an overlay may leave any of them to the file below
                ? Object.assign({}, propSchema, { 'x-required-here': true })
                : propSchema;

            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(childSchema, propTitle, propDescription, propPath, body, true);
                markStatedEmpty(placeholder, propValue);
                body.appendChild(placeholder);
            } else {
                const propGroup = createFormGroup(childSchema, propTitle, propDescription, propPath, propValue, true, propSchema);
                body.appendChild(propGroup);
            }
        });

        // Clicking the header toggles the body — but let clicks on the
        // delete button, inputs, or the pill pass through without toggling.
        header.style.cursor = 'pointer';
        header.onclick = (e) => {
            if (e.target.closest('input, button, select, textarea')) return;
            toggleCollapse(header, body, arrow);
        };
    } else if (itemSchema.type === 'array') {
        const header = document.createElement('div');
        header.className = 'array-item-header';
        header.appendChild(removeBtn);
        item.appendChild(header);

        const itemGroup = createFormGroup(itemSchema, null, null, itemPath, value, true);
        while (itemGroup.firstChild) {
            item.appendChild(itemGroup.firstChild);
        }
    } else if (itemSchema.type === 'object') {
        // Opaque object (no properties, no additionalProperties). Reached
        // either for v2 untyped Map<String,Object> fields or for the
        // polymorphic schema/data-mismatch fallback set just above. Render
        // an inline JSON textarea so the value round-trips intact; getFormData
        // parses it on save via the same .opaque-object-input branch.
        const header = document.createElement('div');
        header.className = 'array-item-header';
        header.appendChild(removeBtn);
        item.appendChild(header);

        const textarea = document.createElement('textarea');
        textarea.className = 'opaque-object-input';
        textarea.setAttribute('data-path', itemPath);
        textarea.setAttribute('data-opaque-object', 'true');
        textarea.spellcheck = false;
        let initialText = '';
        if (value !== null && value !== undefined) {
            try { initialText = JSON.stringify(value, null, 2); } catch (e) { initialText = ''; }
        }
        textarea.value = initialText;
        textarea.rows = Math.max(3, Math.min(20, initialText.split('\n').length));
        textarea.addEventListener('input', () => setDirty());
        item.appendChild(textarea);
    } else {
        // Simple value — put input and remove button on same row
        const header = document.createElement('div');
        header.className = 'array-item-header';
        const itemInput = createFormElement(itemSchema, itemPath, value);
        header.appendChild(makeDragHandle(item));
        header.appendChild(itemInput);
        header.appendChild(removeBtn);
        item.appendChild(header);
    }

    placeItem(item);

    // Set all ancestor collapsible-content elements to max-height: none
    // so they grow to fit the new content
    let el = container.closest('.collapsible-content');
    while (el) {
        if (!el.classList.contains('collapsed')) {
            el.style.maxHeight = 'none';
        }
        el = el.parentElement ? el.parentElement.closest('.collapsible-content') : null;
    }
}

function updateParentSectionStatus(container) {
    // Find the parent collapsible section
    const section = container.closest('.collapsible-section');
    if (section) {
        const badge = section.querySelector('.collapsible-badge.null, .collapsible-badge.has-data');
        if (badge) {
            const hasItems = container.children.length > 0;
            badge.textContent = hasItems ? 'has data' : 'empty';
            badge.className = `collapsible-badge ${hasItems ? 'has-data' : 'null'}`;
        }
    }
}

function generateForm() {
    generateFormWithData(initialData);
}

/// The element that holds a value's own list, map or type field: the first
/// match under `root` with no list item or map entry between them, so a list
/// nested in one of this value's items is never taken for this value's own.
const VALUE_BOUNDARY = '.array-item, .map-entry';
function ownValueElement(root, selector) {
    const mine = root.closest(VALUE_BOUNDARY);
    return [...root.querySelectorAll(selector)].find(e => e.parentElement.closest(VALUE_BOUNDARY) === mine) || null;
}
/// The fields and sections an object owns: under `searchContainer`, with no
/// other section, list item or map entry between them.
const FIELD_OWNER = '.collapsible-section, .array-item, .map-entry';
function ownFields(searchContainer, selector) {
    const mine = searchContainer.closest(FIELD_OWNER);
    return [...searchContainer.querySelectorAll(selector)].filter(e => e.parentElement.closest(FIELD_OWNER) === mine);
}
/// Whether a value is of the JSON type its schema names (any, when the schema
/// names none or is a choice of variants). A file older or newer than its
/// schema can hold "30s" where a number goes, or "yes" for a boolean; the
/// form cannot show those, so an untouched field keeps them as they were.
function fitsSchemaType(v, s) {
    if (s && s.$ref) s = deref(s) || s;
    if (s && (s.oneOf || s.anyOf)) {
        // A choice of object variants holds an object (or, in v2, an id
        // string, drawn as a reference link). Anything else is drift.
        if (v !== null && typeof v === 'object' && !Array.isArray(v)) return true;
        const variants = (s.oneOf || s.anyOf).map(x => (x && x.$ref ? deref(x) : x) || {});
        if (typeof v === 'string' && variants.some(x => takesIdentityReferences(x))) return true;
        return !variants.every(x => x.type === 'object' || x.properties);
    }
    if (!s || !s.type) return true;
    const types = Array.isArray(s.type) ? s.type : [s.type];
    return types.some(t => {
        switch (t) {
            case 'string': return typeof v === 'string';
            case 'integer': return Number.isInteger(v);
            case 'number': return typeof v === 'number';
            case 'boolean': return typeof v === 'boolean';
            case 'array': return Array.isArray(v);
            case 'object': return (v !== null && typeof v === 'object' && !Array.isArray(v))
                || (typeof v === 'string' && takesIdentityReferences(s));
            case 'null': return v === null;
            default: return true;
        }
    });
}
/// Whether the operator changed anything in `el` (or `el` itself) since it was drawn.
function editedWithin(el) {
    if (!el) return false;
    if (el._touched || el._edited) return true;
    return !!el.querySelectorAll && [...el.querySelectorAll('input, select, textarea')]
        .some(i => i._edited && !i.classList.contains('map-key-input'));
}
/// Inside a property re-added with "+" after its ×: the file's value is not its source.
function isFresh(el) {
    return !!(el && el.closest && el.closest('[data-fresh="true"]'));
}

/// The file's value, kept, when it is not of its schema's type and nobody edited it.
function keptDrift(src, schema, el) {
    return src !== undefined && src !== null && !fitsSchemaType(src, schema) && !editedWithin(el);
}

/// The value a file states on purpose although it is empty: {}, [] or "".
/// An `analytics.events` entry is `{}` (the event, with its defaults); a
/// field left out means something else, so the form keeps what was there.
function isStatedEmpty(v) {
    return v === '' || (Array.isArray(v) && v.length === 0)
        || (v !== null && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === 0);
}
/// A list or map the operator emptied: the file has one (with items), the
/// read-back is the empty one. Kept as [] or {}: left out, the application's
/// default would come back (a grant's ["*"], every department).
function emptiedFrom(v, src) {
    if (Array.isArray(v)) return v.length === 0 && Array.isArray(src);
    return v !== null && typeof v === 'object' && Object.keys(v).length === 0
        && src !== null && typeof src === 'object' && !Array.isArray(src);
}
function isEmptyResult(v) {
    return v === null || v === undefined || v === '' || (typeof v === 'object' && Object.keys(v).length === 0);
}
/// A typed-in number stays a number, whatever kind of input showed it.
/// As [coerceToSchema], except a value that is the file's own string, kept
/// untouched (drift: "30" where the schema says integer), stays as written.
/// Converting it would change the file on a Save nobody made; a string past
/// 2^53 would also come back rounded, with nothing to repair it.
/// `o[k] = v` that keeps a key named `__proto__` (from a file) as a key:
/// plain assignment would set the object's prototype and drop it.
function putOwn(o, k, v) {
    Object.defineProperty(o, k, { value: v, enumerable: true, writable: true, configurable: true });
}

/// An overlay value the file states, with no type: the "As in the file
/// below" case. Nothing (an item just added with "+") is not.
function changesBelow(v) {
    return !!v && typeof v === 'object' && !Array.isArray(v) && v.type === undefined;
}

/// A cluster or server file: partial, so `required` does not apply to it on
/// its own (the merged document is checked on the server at Save).
function onOverlayTarget() {
    return !!(selectedTargetDirectory && selectedTargetDirectory.type !== 'domain');
}

function coerceUnlessKept(v, s, src) {
    return (typeof v === 'string' && v === src) ? v : coerceToSchema(v, s);
}

function coerceToSchema(v, s) {
    if (typeof v !== 'string' || !s) return v;
    const t = s.$ref ? (deref(s) || {}).type : s.type;
    if ((t === 'integer' || t === 'number') && v.trim() !== '' && !isNaN(Number(v))) return Number(v);
    return v;
}

/// What the last getFormData() could not read back faithfully (JSON that
/// does not parse, duplicate or empty map keys). Save and Clone refuse while
/// it is not empty: writing would drop or merge those values silently.
let readBackProblems = [];
/// Problems that refuse a Save but not leaving the Form tab (where the JSON
/// tab is how to fix them).
let saveOnlyProblems = [];
/// Each array item's value from the last read-back, so Duplicate copies the
/// item itself rather than whatever now sits at its old path.
let itemValues = new WeakMap();

function getFormData() {
    readBackProblems = [];
    saveOnlyProblems = [];
    itemValues = new WeakMap();
    const form = document.getElementById('dynamicForm');

    // Recursively extract data from a container element. sourceData is
    // the original JSON value at this path (when known) — used to
    // preserve the source file's key order on output.
    function extractData(container, schemaNode, debugPath = '', sourceData = null) {
        if (!schemaNode) return null;

        // Deref handles both bare $ref and the victools $ref+properties idiom.
        // The merged schema is what we walk for property discovery — without
        // this, polymorphic subtype wrappers would lose their base type's
        // properties (id, description, map, etc.) AND the const discriminator.
        if (schemaNode.$ref) {
            schemaNode = deref(schemaNode);
            if (!schemaNode) return null;
        }
        if (isFresh(container)) sourceData = null;   // re-added after ×: not the file's value
        if (keptDrift(sourceData, schemaNode, container)) return JSON.parse(JSON.stringify(sourceData));

        // Resolve polymorphic schemas (anyOf/oneOf) using the actual type
        // discriminator value from the form's hidden input. This preserves
        // the correct variant (e.g. "hash" vs "prefix") during extraction.
        // Use :scope > to find only direct-child type inputs, not nested ones.
        if (schemaNode.oneOf || schemaNode.anyOf) {
            // A file value with no type, untouched: kept as it is, before
            // anything below reads the first variant's fields (or a nested
            // field's raw-JSON box) as this value.
            if (sourceData && typeof sourceData === 'object' && !Array.isArray(sourceData)
                    && sourceData.type === undefined && !editedWithin(container)) {
                return JSON.parse(JSON.stringify(sourceData));
            }
            // Schema/data-mismatch fallback (createFormGroup, addArrayItem):
            // we rendered an opaque textarea instead of a polymorphic form.
            // Parse the textarea directly so the value round-trips intact.
            // Only this value's own box: a field nested in a typed value (an
            // untyped `authentication`, say) is drawn the same way, one level
            // deeper than the value's own type field. A typed value always
            // has that field; the fallback has none.
            const mineBoundary = container.closest(VALUE_BOUNDARY);
            const ownType = [...container.querySelectorAll('input[data-path$=".type"], select[data-path$=".type"]')]
                .filter(i => i.parentElement.closest(VALUE_BOUNDARY) === mineBoundary)
                .map(i => i.dataset.path.length - '.type'.length)
                .sort((a, b) => a - b)[0];
            const opaqueTa = [...container.querySelectorAll('.opaque-object-input')]
                .filter(t => t.parentElement.closest(VALUE_BOUNDARY) === mineBoundary)
                .sort((a, b) => (a.dataset.path || '').length - (b.dataset.path || '').length)
                .find(t => ownType === undefined || (t.dataset.path || '').length <= ownType) || null;
            if (opaqueTa) {
                const txt = opaqueTa.value.trim();
                if (txt === '' || txt === '{}') return null;
                try { return JSON.parse(txt); }
                catch (e) {
                    readBackProblems.push((debugPath || '(root)') + ': the JSON there does not parse (' + e.message + ')');
                    return null;
                }
            }
            // This value's own type field: under it with no list item or map
            // entry between, and the shortest path (a nested object's type is
            // longer). A connector in a keyed pipeline sits a level deeper than
            // fixed positions reached, so it read as the first variant (sip).
            const typeInputs = [...container.querySelectorAll('input[data-path$=".type"], select[data-path$=".type"]')]
                .filter(i => i.parentElement.closest(VALUE_BOUNDARY) === container.closest(VALUE_BOUNDARY))
                .sort((a, b) => a.dataset.path.length - b.dataset.path.length);
            const typeInput = typeInputs[0] || null;
            const typeVal = typeInput ? typeInput.value : null;
            // Switched to another variant since load: the loaded value is not
            // this one's, so none of its keys may be carried over.
            const switched = container.dataset.variantChanged === 'true'
                || !!container.querySelector(':scope > [data-variant-changed="true"]');
            // On a cluster or server file the type is written only when this
            // file states it or the operator picked one: the form draws the
            // first variant for an entry with none (or one just added with
            // "+"), and writing that type would change the kind of thing the
            // domain file's entry is, under it.
            container._omitType = onOverlayTarget() && !switched && changesBelow(sourceData);
            if (switched || (sourceData && typeof sourceData === 'object' && typeVal
                    && sourceData.type !== undefined && sourceData.type !== typeVal)) {
                sourceData = null;
            }
            // The file names no type (an overlay changing one field of the
            // domain's connector): the form drew the first variant to show it.
            // Untouched, it is the file's own value, with no type added.
            if (sourceData && typeof sourceData === 'object' && !Array.isArray(sourceData)
                    && sourceData.type === undefined && !editedWithin(container)) {
                return JSON.parse(JSON.stringify(sourceData));
            }
            // A file value whose type no variant claims, drawn as raw JSON, and
            // that box removed (×): there is no value to write, and the first
            // variant's type would turn it into another kind of thing.
            if (!typeVal && !opaqueTa && sourceData && typeof sourceData === 'object' && typeof sourceData.type === 'string'
                    && !(schemaNode.oneOf || schemaNode.anyOf).some(v => {
                        const w = (v && v.$ref) ? deref(v) : v;
                        const t = w && w.properties && w.properties.type;
                        return t && (t.const === sourceData.type || (Array.isArray(t.enum) && t.enum.includes(sourceData.type)));
                    })) {
                readBackProblems.push((debugPath || '(root)') + ': its value was removed; remove the entry instead');
                return null;
            }
            schemaNode = resolveOneOf(schemaNode, typeVal ? { type: typeVal } : null);
            if (schemaNode && schemaNode.$ref) {
                schemaNode = deref(schemaNode);
            }
            if (!schemaNode) return null;
        }

        if (schemaNode.type === 'object') {
            // Opaque object: rendered as a JSON textarea by createOpaqueObjectGroup.
            // Find the textarea (direct or via wrapper) and parse its value.
            const opaqueProps = schemaNode.properties &&
                Object.keys(schemaNode.properties).length > 0;
            const opaqueAdditional = schemaNode.additionalProperties &&
                typeof schemaNode.additionalProperties === 'object';
            if (!opaqueProps && !opaqueAdditional) {
                const textarea = container.querySelector(
                    ':scope > .opaque-object-input, :scope > div > .opaque-object-input, :scope > .collapsible-content > .opaque-object-input');
                if (textarea) {
                    const txt = textarea.value.trim();
                    if (txt === '' || txt === '{}') return null;
                    try {
                        return JSON.parse(txt);
                    } catch (e) {
                        readBackProblems.push((debugPath || '(root)') + ': the JSON there does not parse (' + e.message + ')');
                        return null;
                    }
                }
                return null;
            }
        }

        if (schemaNode.type === 'object') {
            // Pure map (additionalProperties only). Hybrid objects
            // (both properties AND additionalProperties, e.g. Jackson
            // @JsonAnySetter) fall through to the properties branch, which
            // now handles defined props + inline extras.
            if (schemaNode.additionalProperties && !schemaNode.properties) {
                // This is a map - find map container
                // Note: map-container might be inside a wrapper div due to createCollapsibleSection structure
                const mapContainer = ownValueElement(container, '.map-container');
                if (!mapContainer) return null;

                const result = {};
                const entries = Array.from(mapContainer.children).filter(el => el.classList.contains('map-entry'));

                const seenKeys = new Set();
                entries.forEach((entry) => {
                    const keyInput = entry.querySelector('.map-key-input');
                    const key = keyInput ? mapKeyOf(keyInput) : '';
                    // Two entries with one key would keep only one; an entry with
                    // no key would vanish. Neither is written silently. The file's
                    // own "" key, left as it is, is a key.
                    const fileEmptyKey = key === '' && entry._fileKey === '';
                    if (!key && !fileEmptyKey) {
                        if (entry.querySelector('input:not(.map-key-input), select, textarea')) {
                            readBackProblems.push((debugPath || '(root)') + ': an entry has no key');
                        }
                        return;
                    }
                    if (seenKeys.has(key)) {
                        readBackProblems.push((debugPath || '(root)') + ': the key "' + key + '" is used twice');
                        return;
                    }
                    seenKeys.add(key);
                    const refLink = entry.querySelector(':scope > .map-entry-content > .reference-link');
                    if (refLink) { putOwn(result, key, refLink.getAttribute('data-reference-id')); return; }
                    if (entry._statedNull && !entry._touched) { putOwn(result, key, null); return; }
                    {
                        const childSource = ('_source' in entry) ? entry._source
                            : ((sourceData && typeof sourceData === 'object') ? sourceData[key] : null);
                        const valueData = coerceUnlessKept(
                            extractData(entry, schemaNode.additionalProperties, `${debugPath}.${key}`, childSource),
                            schemaNode.additionalProperties, childSource);
                        if (!isEmptyResult(valueData)) {
                            putOwn(result, key, valueData);
                        } else if (childSource !== null && childSource !== undefined && isStatedEmpty(childSource)) {
                            // The entry is there, empty on purpose ("callRiskAssessed": {}).
                            putOwn(result, key, JSON.parse(JSON.stringify(childSource)));
                        } else if (emptiedFrom(valueData, childSource)) {
                            putOwn(result, key, valueData);
                        } else {
                            // An entry the operator added and left empty is still an entry.
                            const vs = schemaNode.additionalProperties.$ref ? deref(schemaNode.additionalProperties) : schemaNode.additionalProperties;
                            if (vs && vs.type === 'object') putOwn(result, key, {});
                            else if (vs && vs.type === 'array') putOwn(result, key, []);
                            // A key the form still shows is not dropped: an empty text
                            // value is "", any other kind has to be filled in.
                            else if (vs && vs.type === 'string') putOwn(result, key, '');
                            else if (!(vs && (vs.oneOf || vs.anyOf))) {
                                readBackProblems.push((debugPath || '(root)') + ': the entry "' + key + '" has no value');
                            }
                        }
                    }
                });

                if (Object.keys(result).length > 0) return result;
                // Every entry removed from a map the file has: the file says {}
                // (left out, the application's default would come back).
                return (sourceData && typeof sourceData === 'object' && !Array.isArray(sourceData)) ? {} : null;
            } else if (schemaNode.properties) {
                // Regular object with defined properties
                const result = {};

                // When extracting from array-items or map-entries, form content might be
                // inside a .collapsible-content or .map-entry-content child
                const collapsibleContent = container.querySelector(':scope > .collapsible-content, :scope > .map-entry-content');
                const searchContainer = collapsibleContent || container;

                // Emit const-valued props (polymorphic discriminators like
                // `type: {const: "hash"}`) first so they appear at the top of
                // the JSON output — matches Jackson's convention and avoids
                // the variant-merge shuffle that pushes discriminators to the
                // tail of the property iteration order.
                mergedKeyOrder(schemaNode.properties, sourceData).forEach(propName => {
                    let propSchema = schemaNode.properties[propName];
                    const propTitle = fieldTitle(propSchema, propName);

                    if (propSchema.const !== undefined) {
                        // A constant the file left out (a type an overlay inherits)
                        // stays out; a new value, or a switched variant, states it.
                        if (!(sourceData && typeof sourceData === 'object' && !(propName in sourceData))) {
                            result[propName] = propSchema.const;
                        }
                        return;
                    }

                    // Check if any polymorphic variant is a complex type (object/array).
                    // Do NOT resolve to a specific variant here — that would lose the
                    // actual type discriminator. The recursive extractData call at
                    // line ~2160 handles full resolution with real form data.
                    let resolvedSchema = propSchema;
                    if (propSchema.$ref) {
                        resolvedSchema = deref(propSchema);
                    }
                    let isComplexType = false;
                    if (resolvedSchema && (resolvedSchema.oneOf || resolvedSchema.anyOf)) {
                        const variants = polymorphicVariants(resolvedSchema);
                        if (variants) {
                            isComplexType = variants.some(v => {
                                const resolved = v.$ref ? deref(v) : v;
                                return resolved && (resolved.type === 'object' || resolved.type === 'array');
                            });
                        }
                    } else {
                        isComplexType = resolvedSchema &&
                            (resolvedSchema.type === 'object' || resolvedSchema.type === 'array');
                    }

                    if (isComplexType) {
                        // Complex types (objects, arrays) - look for collapsible section
                        // Note: sections might be inside a wrapper div due to createObjectGroup structure
                        let targetContainer = null;
                        // Every section this object owns, however the layout
                        // (groups, columns, @FormSection) nests it.
                        const sections = ownFields(searchContainer, '.collapsible-section');
                        sections.forEach(section => {
                            const header = section.querySelector('.collapsible-header .collapsible-title > span:not(.collapsible-arrow)');
                            if (!header) return;
                            const headerText = (header.textContent || '').trim();
                            // Match either the schema-driven title or the bare
                            // property name (some sections render with the
                            // propName when no title is set in the schema).
                            if (section.dataset.prop ? section.dataset.prop === propName
                                    : (headerText === propTitle || headerText === propName)) {
                                targetContainer = section.querySelector('.collapsible-content');
                            }
                        });

                        if (!targetContainer) {
                            // A property holding a reference: its link carries the id.
                            const refGroup = ownFields(searchContainer, '.form-group').find(g => g.dataset.prop === propName && g.querySelector(':scope > .reference-link'));
                            if (refGroup) result[propName] = refGroup.querySelector(':scope > .reference-link').getAttribute('data-reference-id');
                        }
                        if (targetContainer) {
                            const childSource = (sourceData && typeof sourceData === 'object') ? sourceData[propName] : null;
                            const propData = extractData(targetContainer, propSchema, `${debugPath}.${propName}`, childSource);
                            if (!isEmptyResult(propData)) {
                                result[propName] = propData;
                            } else if (childSource !== null && childSource !== undefined && isStatedEmpty(childSource)) {
                                result[propName] = JSON.parse(JSON.stringify(childSource));
                            } else if (emptiedFrom(propData, childSource)) {
                                result[propName] = propData;
                            }
                        }
                    } else {
                        // Primitive types - look for form-group with matching label
                        // Note: form-groups might be inside a wrapper div due to createObjectGroup structure
                        // createObjectGroup wraps fields in an intermediate .object-content
                        // div before createCollapsibleSection wraps that in .collapsible-content.
                        // The `:scope > div >` variants reach through that wrapper; without
                        // them, primitives inside x-form-groups silently round-trip to nothing.
                        // Every field this object owns, however the layout
                        // (groups, columns, @FormSection) nests it.
                        const formGroups = ownFields(searchContainer, '.form-group, .checkbox-group');
                        let foundInput = null;

                        formGroups.forEach(group => {
                            const label = group.querySelector('label');
                            // Check if label text matches (label might contain help icon, so check first text node or textContent)
                            if (label) {
                                let labelText = label.childNodes[0]?.textContent?.trim() || label.textContent?.trim();
                                if (group.dataset.prop ? group.dataset.prop === propName : labelText === propTitle) {
                                    foundInput = group.querySelector('input, select, textarea');
                                }
                            }
                        });

                        const src = (sourceData && typeof sourceData === 'object' && !(foundInput && isFresh(foundInput))) ? sourceData[propName] : undefined;
                        if (foundInput && keptDrift(src, propSchema, foundInput)) {
                            result[propName] = JSON.parse(JSON.stringify(src));
                        } else if (foundInput) {
                            const value = coerceToSchema(readInputValue(foundInput), propSchema);
                            if (value !== null && value !== '') {
                                result[propName] = value;
                            } else if (src === '') {
                                result[propName] = '';   // stated empty, kept
                            }
                        }
                    }
                });

                // Values the file states empty, drawn as placeholders: kept. An
                // explicit null too: it is not "absent" (it overrides a default,
                // or the domain's value from an overlay).
                if (sourceData && typeof sourceData === 'object') {
                    ownFields(searchContainer, '.add-property-placeholder[data-stated-empty="true"]').forEach(ph => {
                        const k = ph.dataset.prop;
                        if (k && !Object.hasOwn(result, k) && isStatedEmpty(sourceData[k])) putOwn(result, k, JSON.parse(JSON.stringify(sourceData[k])));
                    });
                    ownFields(searchContainer, '.add-property-placeholder').forEach(ph => {
                        const k = ph.dataset.prop;
                        if (k && ph.dataset.nullRemoved !== 'true' && !Object.hasOwn(result, k) && Object.prototype.hasOwnProperty.call(sourceData, k) && sourceData[k] === null) putOwn(result, k, null);
                    });
                }

                // Keys the file has and the schema does not list (a schema
                // older than the app): kept as they were. The form never drew
                // them, so nothing here can have changed them.
                if (sourceData && typeof sourceData === 'object' && !Array.isArray(sourceData)
                        && !(schemaNode.additionalProperties && typeof schemaNode.additionalProperties === 'object')) {
                    Object.keys(sourceData).forEach(k => {
                        if (!Object.hasOwn(schemaNode.properties, k) && !Object.hasOwn(result, k)) putOwn(result, k, JSON.parse(JSON.stringify(sourceData[k])));
                    });
                }

                // Hybrid object: collect inline extras (Jackson @JsonAnySetter)
                // rendered by renderInlineExtras. They live at the same JSON
                // level as defined props, so merge directly into `result`.
                if (schemaNode.additionalProperties && typeof schemaNode.additionalProperties === 'object') {
                    const defined = new Set(Object.keys(schemaNode.properties));
                    const extrasWrapper = searchContainer.querySelector(':scope > .extras-inline, :scope > div > .extras-inline');
                    if (extrasWrapper) {
                        const rows = extrasWrapper.querySelectorAll(':scope > .extras-row');
                        rows.forEach(row => {
                            const keyInput = row.querySelector('.extras-key-input');
                            const valueInput = row.querySelector('.extras-value-input');
                            if (!keyInput || !valueInput) return;
                            const key = mapKeyOf(keyInput);
                            if (!key || defined.has(key)) return;
                            const v = readInputValue(valueInput);
                            if (v !== null && v !== '') result[key] = v;
                        });
                    }
                }

                // Required properties, ranges, patterns and types are checked
                // by the server at Save, on the documents the servers build
                // (SaveCheck): one check for every way a value reaches the form
                // (typed, a draft, an import, a proposal, a copy).
                if (container._omitType) delete result.type;
                if (Object.keys(result).length > 0) return inSourceOrder(result, sourceData);
                return isStatedEmpty(sourceData) ? {} : null;
            }
        } else if (schemaNode.type === 'array') {
            // Find array container
            // Note: array-container might be inside wrapper divs due to createCollapsibleSection/createArrayGroup structure
            const arrayContainer = ownValueElement(container, '.array-container');
            if (!arrayContainer) return null;

            const result = [];
            const items = Array.from(arrayContainer.children).filter(el => el.classList.contains('array-item'));

            items.forEach((item, idx) => {
                // Check for identity reference link as a DIRECT child of the
                // array item — not as a nested descendant. addArrayItem
                // creates a reference link by appending it directly to the
                // .array-item element when the item value is a bare id string.
                // Without :scope-restricted matching, querySelector('.reference-link')
                // would dive into nested object structures (e.g. a hash map's
                // own selector list) and emit the first nested reference id
                // as if it were the value of the outer array item — silently
                // collapsing the entire item to a single nested reference.
                const refLink = item.querySelector(':scope > .reference-link');
                if (refLink) {
                    const refId = refLink.getAttribute('data-reference-id');
                    if (refId) {
                        result.push(refId);
                        return;
                    }
                }

                let itemSchemaNode = schemaNode.items;
                if (itemSchemaNode && (itemSchemaNode.oneOf || itemSchemaNode.anyOf)) {
                    // Schema/data-mismatch fallback: addArrayItem rendered an
                    // opaque JSON textarea when no variant matched the value's
                    // type. Read it back as-is so the item round-trips intact.
                    const opaqueTa = item.querySelector(':scope > .opaque-object-input');
                    if (opaqueTa) {
                        const txt = opaqueTa.value.trim();
                        if (txt !== '' && txt !== '{}') {
                            try { result.push(JSON.parse(txt)); }
                            catch (e) {
                                readBackProblems.push(debugPath + '[' + idx + ']: the JSON there does not parse (' + e.message + ')');
                            }
                        }
                        return;
                    }
                    // Peek at the type discriminator to resolve the right variant.
                    // Use :scope > to find only DIRECT child type inputs, not
                    // nested ones from deeper objects (which would return a
                    // wrong variant, e.g. "prefix" from a nested table instead
                    // of "hash" from the current item).
                    // Reach through the new .array-item-body wrapper and
                    // through the const hidden-input wrapper. Without these
                    // paths, the polymorphic discriminator is never found
                    // and resolveOneOf defaults to the first variant,
                    // silently converting every item's type.
                    const typeInput = [...item.querySelectorAll('input[data-path$=".type"], select[data-path$=".type"]')]
                        .filter(i => i.parentElement.closest(VALUE_BOUNDARY) === item)
                        .sort((a, b) => a.dataset.path.length - b.dataset.path.length)[0];
                    const typeVal = typeInput ? typeInput.value : null;
                    itemSchemaNode = resolveOneOf(itemSchemaNode, typeVal ? { type: typeVal } : null);
                }
                if (item._statedNull && !item._touched) { result.push(null); itemValues.set(item, null); return; }
                // The value the item was drawn from (null for one added since).
                const itemSource = ('_source' in item) ? item._source
                    : (Array.isArray(sourceData) ? sourceData[idx] : null);
                const itemData = coerceUnlessKept(extractData(item, itemSchemaNode, `${debugPath}[${idx}]`, itemSource), itemSchemaNode, itemSource);
                itemValues.set(item, itemData);
                if (itemData !== null) {
                    result.push(itemData);
                } else if (itemSource !== null && itemSource !== undefined && isStatedEmpty(itemSource)) {
                    result.push(JSON.parse(JSON.stringify(itemSource)));
                } else {
                    // An item emptied (every field removed) or added and left blank:
                    // dropping it would move the later ones up a place, silently.
                    readBackProblems.push(`${debugPath}[${idx}]` + ' is empty: fill it in or remove it');
                }
            });

            if (result.length > 0) return result;
            // Every item removed from a list the file has: the file says []. Left
            // out, the default would come back (a grant's ["*"]: every department).
            // Added with "+" and left empty: the operator stated a list, and a
            // required one may legitimately be [].
            const freshGroup = container.closest && container.closest('[data-fresh="true"]');
            if (freshGroup && ownValueElement(freshGroup, '.array-container') === arrayContainer) return [];
            return Array.isArray(sourceData) ? [] : null;
        } else {
            // Primitive type - find the input
            const input = container.querySelector('input:not(.map-key-input), select:not(.map-key-input), textarea');
            if (!input) return null;

            const value = readInputValue(input);
            return (value !== null && value !== '') ? value : null;
        }

        return null;
    }

    // Start extraction from the form using the schema
    const result = {};

    if (schema && schema.properties) {
        mergedKeyOrder(schema.properties, currentData).forEach(propName => {
            let propSchema = schema.properties[propName];
            const propTitle = fieldTitle(propSchema, propName);

            // const-only top-level property — emit straight from the schema.
            if (propSchema.const !== undefined) {
                // Left out by the file: stays out (as nested constants).
                if (!(currentData && typeof currentData === 'object' && !(propName in currentData))) {
                    result[propName] = propSchema.const;
                }
                return;
            }

            // deref handles both bare $ref and the victools $ref+properties idiom
            let resolvedSchema = propSchema;
            if (propSchema.$ref) {
                resolvedSchema = deref(propSchema);
            }

            // Polymorphic schemas (oneOf/anyOf) are rendered as collapsible
            // sections just like plain objects, so treat them as complex when
            // any variant resolves to an object/array. Without this, top-level
            // polymorphic fields (e.g. `routing` = anyOf[TableRouting, DirectRouting])
            // fall through to the primitive path and are silently dropped.
            let isComplexType = false;
            if (resolvedSchema && (resolvedSchema.oneOf || resolvedSchema.anyOf)) {
                const variants = polymorphicVariants(resolvedSchema);
                if (variants) {
                    isComplexType = variants.some(v => {
                        const resolved = v.$ref ? deref(v) : v;
                        return resolved && (resolved.type === 'object' || resolved.type === 'array');
                    });
                }
            } else {
                isComplexType = resolvedSchema &&
                    (resolvedSchema.type === 'object' || resolvedSchema.type === 'array');
            }

            if (isComplexType) {
                // Complex types - look for collapsible section
                const sections = form.querySelectorAll(':scope > .collapsible-section');
                let targetSection = null;

                sections.forEach(section => {
                    const header = section.querySelector('.collapsible-header .collapsible-title > span:not(.collapsible-arrow)');
                    if (!header) return;
                    const headerText = (header.textContent || '').trim();
                    // Match either the schema-driven title or the bare propName.
                    if (section.dataset.prop ? section.dataset.prop === propName
                            : (headerText === propTitle || headerText === propName)) {
                        targetSection = section;
                    }
                });

                if (!targetSection) {
                    const refGroup = ownFields(form, '.form-group').find(g => g.dataset.prop === propName && g.querySelector(':scope > .reference-link'));
                    if (refGroup) result[propName] = refGroup.querySelector(':scope > .reference-link').getAttribute('data-reference-id');
                }
                if (targetSection) {
                    const content = targetSection.querySelector('.collapsible-content');
                    if (content) {
                        const childSource = (currentData && typeof currentData === 'object') ? currentData[propName] : null;
                        const propData = extractData(content, propSchema, propName, childSource);
                        if (!isEmptyResult(propData)) {
                            result[propName] = propData;
                        } else if (childSource !== null && childSource !== undefined && isStatedEmpty(childSource)) {
                            result[propName] = JSON.parse(JSON.stringify(childSource));
                        } else if (emptiedFrom(propData, childSource)) {
                            result[propName] = propData;
                        }
                    }
                }
            } else {
                // Primitive types - the top-level field named for this property
                const formGroups = ownFields(form, '.form-group, .checkbox-group');
                let foundInput = null;

                formGroups.forEach(group => {
                    const label = group.querySelector('label');
                    if (label) {
                        let labelText = label.childNodes[0]?.textContent?.trim() || label.textContent?.trim();
                        if (group.dataset.prop ? group.dataset.prop === propName : labelText === propTitle) {
                            foundInput = group.querySelector('input, select, textarea');
                        }
                    }
                });

                const src = currentData && typeof currentData === 'object' && !(foundInput && isFresh(foundInput)) ? currentData[propName] : undefined;
                if (foundInput && keptDrift(src, propSchema, foundInput)) {
                    result[propName] = JSON.parse(JSON.stringify(src));
                } else if (foundInput) {
                    const value = coerceToSchema(readInputValue(foundInput), propSchema);
                    if (value !== null && value !== '') {
                        result[propName] = value;
                    } else if (currentData && currentData[propName] === '') {
                        result[propName] = '';   // stated empty, kept
                    }
                }
            }
        });
    }

    // Top-level values the file states empty, drawn as placeholders: kept (an
    // explicit null too).
    if (currentData && typeof currentData === 'object') {
        ownFields(form, '.add-property-placeholder[data-stated-empty="true"]').forEach(ph => {
            const k = ph.dataset.prop;
            if (k && !Object.hasOwn(result, k) && isStatedEmpty(currentData[k])) putOwn(result, k, JSON.parse(JSON.stringify(currentData[k])));
        });
        ownFields(form, '.add-property-placeholder').forEach(ph => {
            const k = ph.dataset.prop;
            if (k && ph.dataset.nullRemoved !== 'true' && !Object.hasOwn(result, k) && Object.prototype.hasOwnProperty.call(currentData, k) && currentData[k] === null) putOwn(result, k, null);
        });
    }

    // Top-level keys the schema does not list (a schema older than the app):
    // kept as the file had them; the form never drew them.
    if (schema && schema.properties && currentData && typeof currentData === 'object') {
        Object.keys(currentData).forEach(k => {
            if (!Object.hasOwn(schema.properties, k) && !Object.hasOwn(result, k)) putOwn(result, k, JSON.parse(JSON.stringify(currentData[k])));
        });
    }

    return inSourceOrder(result, currentData);
}

/// `obj` with its keys in the order `src` (the file) has them, new keys after.
/// Values kept from the file (a stated-empty list, a key the schema does not
/// list) are added after the form's own, so without this an untouched key
/// would move to the end and show up in every diff.
function inSourceOrder(obj, src) {
    if (!src || typeof src !== 'object' || Array.isArray(src)) return obj;
    const out = {};
    Object.keys(src).forEach(k => { if (Object.hasOwn(obj, k)) putOwn(out, k, obj[k]); });
    Object.keys(obj).forEach(k => { if (!Object.hasOwn(out, k)) putOwn(out, k, obj[k]); });
    return out;
}

function setNestedValue(obj, path, value) {
    // Parse path into segments, handling array notation like "items[0]"
    const segments = [];
    const regex = /([^.\[\]]+)|\[(\d+)\]/g;
    let match;
    while ((match = regex.exec(path)) !== null) {
        if (match[1] !== undefined) {
            segments.push({ type: 'key', value: match[1] });
        } else if (match[2] !== undefined) {
            segments.push({ type: 'index', value: parseInt(match[2], 10) });
        }
    }

    let current = obj;

    for (let i = 0; i < segments.length - 1; i++) {
        const segment = segments[i];
        const nextSegment = segments[i + 1];
        const key = segment.value;

        if (!(key in current) || current[key] === null || current[key] === undefined) {
            // Create array or object based on next segment type
            current[key] = nextSegment.type === 'index' ? [] : {};
        }
        current = current[key];
    }

    const lastSegment = segments[segments.length - 1];
    current[lastSegment.value] = value;
}

let schemaLoading = false;   // a schema's file is on its way into the form
let loadSeq = 0;             // which load the form is waiting for
let loadFallback = null;     // the schema the form holds, if a load fails
let jsonLoadSeq = -1;        // the load a load_json / load_sample in flight belongs to
let lastSavePath = null;     // the file the last save_json was for
let lastSaveDraftKey = null; // that file's draft key, cleared when its reply comes after a switch

/// The file the form shows, for Version History, Preview and Restore: the one
/// Save writes, never a registry entry a failed load left pointing elsewhere.
function formFile() {
    return (currentSchemaName && selectedTargetDirectory && !schemaLoading) ? currentSavePath() : null;
}

/// The file Save writes: the loaded file, else this schema in the selected target.
function currentSavePath() {
    const entry = pendingRequests.get('schema');
    if (entry && entry.jsonFile) return entry.jsonFile;
    return selectedTargetDirectory.path + '/' + currentSchemaName + '.json';
}

/// The document Save or Clone would write, or null (with the reason shown):
/// the JSON tab's text when that tab is active, else the form. Refused while
/// a schema is loading, while the form holds something it cannot write
/// faithfully, and while any secret still reads {REDACTED}.
function documentToWrite(what) {
    if (document.body.classList.contains('view-mode')) {
        showSyncStatus('View Mode: switch to Edit Mode to ' + what, 'warning');
        return null;
    }
    if (schemaLoading) {
        showSyncStatus('Still loading: ' + what + ' once the file is in the form', 'warning');
        return null;
    }
    const activeTab = document.querySelector('.tab-content.active').id;
    let data;
    writeEditorText = leftEditorText;   // typed in the JSON tab, then left
    if (activeTab === 'json-tab') {
        // Sent too, as the last repair source: the page's own serialization
        // (already reordered), so it only counts when nothing else parses (a
        // broken file fixed in this tab).
        writeEditorText = jsonEditor.getValue();
        try {
            data = JSON.parse(writeEditorText);
        } catch (e) {
            showSyncStatus('Cannot ' + what + ' invalid JSON', 'error');
            return null;
        }
    } else {
        data = getFormData();
        // What the schema says (a port past its maximum, a missing required
        // property) the server checks at Save, against the file on disk.
        readBackProblems.push(...saveOnlyProblems);
        if (readBackProblems.length) {
            const n = readBackProblems.length;
            showSyncStatus('Not ' + (what === 'save' ? 'saved' : 'cloned') + ': ' + readBackProblems[0]
                + (n > 1 ? ' (and ' + (n - 1) + ' more)' : ''), 'error');
            return null;
        }
    }
    // A configuration is one JSON object (the JSON tab could hold null, a list, a string).
    if (data === null || typeof data !== 'object' || Array.isArray(data)) {
        showSyncStatus('Not ' + (what === 'save' ? 'saved' : 'cloned') + ': a configuration is a JSON object { … }', 'error');
        return null;
    }
    // A secret the assistant (or a restored draft) could not carry over is
    // still the marker: writing it would make "{REDACTED}" the credential. A
    // value that was "{REDACTED}" in the file as loaded is the file's own text.
    const marker = newRedactedMarkers(data, initialData);
    if (marker) {
        // Where: in a password field the marker shows only as dots.
        showSyncStatus('Not ' + (what === 'save' ? 'saved' : 'cloned') + ': the secret at ' + marker + ' still reads {REDACTED}; enter it again first', 'error');
        return null;
    }
    return data;
}

let lastSavedData = null;   // what the last save_json of this file wrote
let fileVersion = null;     // the version of the file the form was loaded from (server's)
let draftBaseVersion = null;   // a restored draft's base version, for its first save
let overwriteNext = false;  // the operator saw a conflict and pressed Save again
let lastSaveAutoPublish = true;   // auto-publish as it was when that save was sent
let lastSaveApp = null;           // the app that save was for

/// Whether `data` holds a whole-value "{REDACTED}" where `loaded` did not.
function newRedactedMarkers(data, loaded) {
    const at = (v, path, out) => {
        if (typeof v === 'string' && v.includes('{REDACTED}')) out.add(JSON.stringify(path));
        else if (Array.isArray(v)) v.forEach((x, i) => at(x, path.concat(i), out));
        else if (v && typeof v === 'object') Object.keys(v).forEach(k => at(v[k], path.concat(k), out));
        return out;
    };
    const was = at(loaded, [], new Set());
    const fresh = [...at(data, [], new Set())].find(p => !was.has(p));
    // The first one's path, as the form names fields (a.b[2].c); null when none.
    return fresh ? JSON.parse(fresh).map((k, i) => typeof k === 'number' ? '[' + k + ']' : (i ? '.' : '') + k).join('') : null;
}

let saving = false;   // a save_json from this page is out
let lastClonePath = null;     // the destination of the last Clone
let cloneOut = false;         // a Clone's save_json is out
let cloneOverwrite = false;   // the operator saw it exists and cloned again

function saveData() {
    // One save at a time: a second, sent before the first's reply, carries the
    // old version and would come back as a conflict with the first.
    if (saving) { showSyncStatus('Still saving…', 'warning'); return; }
    if (!websocket || websocket.readyState !== WebSocket.OPEN) {
        showSyncStatus('Not connected to the server: save once it reconnects', 'error');
        return;
    }
    const data = documentToWrite('save');
    if (data === null) return;

    // Validate that we have a target directory selected
    if (!selectedTargetDirectory) {
        showSyncStatus('Please select a target directory first', 'warning');
        return;
    }

    // Validate that we have a schema selected
    if (!currentSchemaName) {
        showSyncStatus('Please select a schema first', 'warning');
        return;
    }

    // Construct save path based on selected target and schema
    // If we loaded from a sample file, the server will automatically convert to primary location
    const savePath = currentSavePath();

    // Send save request via WebSocket
    const jsonContent = JSON.stringify(data, null, 2);
    lastSavePath = savePath;
    lastSaveDraftKey = draftStorageKey();
    lastSavedData = data;
    lastSaveAutoPublish = autoPublishEnabled;
    lastSaveApp = currentSchemaName;
    const msg = { file: savePath, content: jsonContent };
    if (loadedSourceText) msg.sourceText = loadedSourceText;
    msg.sourceFile = savePath;   // the form's file, as it is now (after earlier Saves)
    if (writeEditorText) msg.editorText = writeEditorText;
    // The version the form came from: the server refuses the save if someone
    // saved the file since, unless the operator chose to overwrite.
    // A restored draft is checked against the version it was taken over.
    const base = draftBaseVersion || fileVersion;
    if (base) msg.baseVersion = base;
    if (overwriteNext) msg.force = true;
    overwriteNext = false;
    saving = true;
    sendWebSocketMessage('save_json', msg);

    showSyncStatus('Saving data...', 'warning');
}

function loadConfig() {
    if (!currentSchemaName) {
        showSyncStatus('Please select a schema first', 'warning');
        return;
    }

    // Re-load the config file from disk into the editor
    loadSchema(currentSchemaName);
}

let publishing = false;            // a Publish from this page is out
const publishingApps = new Set();  // which apps it is publishing
const savedDuringPublish = new Set();   // apps saved while their publish was out

/// Publishes this app and every other app saved here but not yet published.
/// Refused in View Mode, saying so: it changes the servers or the files.
function viewModeRefuses(what) {
    if (!document.body.classList.contains('view-mode')) return false;
    showSyncStatus('View Mode: switch to Edit Mode to ' + what, 'warning');
    return true;
}

function publishConfig() {
    if (viewModeRefuses('publish')) return;
    if (publishing) { showSyncStatus('Already publishing…', 'warning'); return; }
    if (!websocket || websocket.readyState !== WebSocket.OPEN) {
        showSyncStatus('Not connected to the server: publish once it reconnects', 'error');
        return;
    }
    const apps = new Set(unpublishedApps);
    if (currentSchemaName) apps.add(currentSchemaName);
    if (!apps.size) {
        showSyncStatus('Please select a schema first', 'warning');
        return;
    }
    publishing = true;
    // "_templates": the templates saved while auto-publish was off, pushed to every server.
    apps.forEach(app => {
        publishingApps.add(app);
        if (app === TEMPLATES_PENDING) sendWebSocketMessage('publish_templates', {});
        else sendWebSocketMessage('reload', { appName: app });
    });
    showSyncStatus('Publishing ' + [...apps].join(', ') + '...', 'warning');
}

// --- Auto-publish toggle ---------------------------------------------------
// Global Configurator behavior: when ON, *.json files saved under
// ./config/custom/vorpal/ are republished to live services automatically, so the
// Publish buttons are hidden (body.autopublish-on). Writing the flag triggers a
// server-side reload that starts/stops the watcher thread immediately.

function setAutoPublish(enabled) {
    if (viewModeRefuses('change auto-publish')) {
        const toggle = document.getElementById('autopublish-toggle');
        if (toggle) toggle.checked = autoPublishEnabled;   // the switch back where it was
        return;
    }
    sendWebSocketMessage('set_autopublish', { enabled: enabled });
    showSyncStatus(enabled ? 'Auto-publish on…' : 'Auto-publish off…', 'warning');
}

function updateAutoPublishToggle(enabled) {
    autoPublishEnabled = enabled;
    const toggle = document.getElementById('autopublish-toggle');
    const stateLabel = document.getElementById('autopublish-state');
    if (toggle) toggle.checked = enabled;
    if (stateLabel) stateLabel.textContent = enabled ? 'on' : 'off';
    document.body.classList.toggle('autopublish-on', enabled);
    // The watcher publishes changes from now on. What was saved while it was
    // off the server publishes itself on the switch (every page's saves, and
    // every template), and its list tells each page as they go live.
    if (enabled) {
        unpublishedApps.delete(TEMPLATES_PENDING);
        renderUnpublished();
    }
}

function resetForm() {
    if (schemaLoading) { showSyncStatus('Still loading: reset once the file is in the form', 'warning'); return false; }
    if (!checkUnsavedChanges()) return false;   // the edits, and the draft that holds them
    leftEditorText = null;   // what was typed in the JSON tab is discarded with the rest
    if (fileBroken) {
        // Back to the file as it is: its own (unparseable) text, in the JSON tab.
        switchTab('json');
        setEditorText(initialText || '');
        clearDirty();
        showSyncStatus('Reset: the file as it is on disk, which does not parse', 'warning');
        return true;
    }
    currentData = JSON.parse(JSON.stringify(initialData));
    generateFormWithData(currentData);
    if (jsonEditor) {
        jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
    }
    // Back to the file as it is on disk: nothing unsaved (the JSON editor's
    // change event above marked it dirty), and no restored draft to check.
    draftBaseVersion = null;
    overwriteNext = false;   // the conflict it answered was about the edits just dropped
    loadedSourceText = initialText;   // the file's text again, not a sample's or an import's
    clearDirty();
    if (typeof clearDraft === 'function') clearDraft();
    showSyncStatus('Form reset to initial data', 'success');
    return true;
}

// ---------------------------------------------------------------------------
// Export / Import — one-click JSON download/upload of the current config.

/// What JSON.stringify cannot write back as the file had it: an integer past
/// 2^53 (rounded) or an integer-like key (listed first, ascending, whatever
/// the file's order, which in a keyed pipeline is run order).
function exportWouldAlter(v) {
    if (typeof v === 'number') return Number.isInteger(v) && Math.abs(v) > Number.MAX_SAFE_INTEGER;
    if (v === null || typeof v !== 'object') return false;
    if (Array.isArray(v)) return v.some(exportWouldAlter);
    return Object.keys(v).some(k => /^(0|[1-9][0-9]*)$/.test(k)) || Object.values(v).some(exportWouldAlter);
}

/// Typed secrets are read back as {CLEARTEXT}<text>; a downloaded file is no
/// place for them. {REDACTED} instead: an Import then Save keeps the
/// destination's secret.
function exportWithoutCleartext(v) {
    if (typeof v === 'string') return v.startsWith('{CLEARTEXT}') ? '{REDACTED}' : v;
    if (v === null || typeof v !== 'object') return v;
    if (Array.isArray(v)) return v.map(exportWithoutCleartext);
    const out = {};
    for (const k of Object.keys(v)) out[k] = exportWithoutCleartext(v[k]);
    return out;
}

function exportConfig() {
    if (!currentSchemaName || schemaLoading) { showSyncStatus('Nothing to export yet', 'warning'); return; }
    const t = selectedTargetDirectory;
    // An overlay is a fragment: named so, and Import refuses it on the Domain target.
    const filename = currentSchemaName + (t && t.type !== 'domain' ? '.' + t.type + '-' + t.name.replace(/^[^:]*:\s*/, '').replace(/[^A-Za-z0-9_-]/g, '_') : '') + '.json';
    let text;
    if (!isDirty && !fileBroken && loadedSourceText && loadedSourceText === initialText
            && !loadedSourceText.includes('{CLEARTEXT}')) {
        text = loadedSourceText;   // the file as loaded: exact longs, its key order, ciphertext
    } else {
        let data;
        const activeTab = document.querySelector('.tab-content.active');
        if (activeTab && activeTab.id === 'form-tab') {
            data = getFormData();
            if (readBackProblems.length) {
                showSyncStatus('Not exported: ' + readBackProblems[0], 'error');
                return;
            }
        } else if (jsonEditor) {
            try { data = JSON.parse(jsonEditor.getValue()); }
            catch (e) { showSyncStatus('Cannot export invalid JSON', 'error'); return; }
        } else {
            data = currentData;
        }
        if (exportWouldAlter(data)) {
            showSyncStatus('Not exported: this file has numbers past 2^53 or numbered keys the browser cannot write back as they are. Save first, load it again, then Export.', 'warning');
            return;
        }
        text = JSON.stringify(exportWithoutCleartext(data), null, 2);
    }
    const blob = new Blob([text], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    showSyncStatus('Exported ' + filename, 'success');
}

function importConfig() {
    const picker = document.getElementById('import-file');
    if (picker) { picker.value = ''; picker.click(); }
}

function handleImportFile(event) {
    const file = event.target && event.target.files && event.target.files[0];
    if (!file) return;
    if (viewModeRefuses('import')) { event.target.value = ''; return; }
    if (/\.(cluster|server)-[^.]+\.json$/.test(file.name)) {
        event.target.value = '';
        showSyncStatus('Import refused: ' + file.name + ' is a cluster or server overlay\'s export, not a whole configuration', 'warning');
        return;
    }
    if (selectedTargetDirectory && selectedTargetDirectory.type !== 'domain') {
        event.target.value = '';
        showSyncStatus('An import is a whole configuration: import it on the Domain target', 'warning');
        return;
    }
    if (!checkUnsavedChanges()) { event.target.value = ''; return; }
    const forApp = currentSchemaName, forTarget = selectedTargetDirectory && selectedTargetDirectory.path, forLoad = loadSeq;
    const reader = new FileReader();
    reader.onload = () => {
        // Read later: still the form it was imported into?
        if (schemaLoading || currentSchemaName !== forApp || loadSeq !== forLoad
                || (selectedTargetDirectory && selectedTargetDirectory.path) !== forTarget) {
            showSyncStatus('Import not applied: the editor moved to another application or file', 'warning');
            return;
        }
        let data;
        try { data = JSON.parse(reader.result); }
        catch (e) {
            showSyncStatus('Import failed: ' + e.message, 'error');
            return;
        }
        if (data === null || typeof data !== 'object' || Array.isArray(data)) {
            showSyncStatus('Import refused: a configuration is a JSON object { … }', 'error');
            return;
        }
        loadedSourceText = reader.result;   // its own key order and long integers, at Save
        leftEditorText = null;
        currentData = data;
        generateFormWithData(currentData);
        if (jsonEditor) jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
        setDirty();
        showSyncStatus('Imported ' + file.name + ': review and Save', 'success');
    };
    reader.onerror = () => showSyncStatus('Import failed: could not read file', 'error');
    reader.readAsText(file);
}

// ---------------------------------------------------------------------------
// Read-only / view mode — disables form inputs and hides mutation controls.

function toggleViewMode() {
    const on = !document.body.classList.contains('view-mode');
    document.body.classList.toggle('view-mode', on);
    // Every control disabled (Tab and the keyboard reach a field too), not
    // the form made inert: sections still open, Diff still jumps to a field,
    // and a screen reader still reads the values.
    lockForViewMode(document.getElementById('dynamicForm'), on);
    if (jsonEditor) jsonEditor.setReadOnly(on);
    if (textEditor) textEditor.setReadOnly(on);
    const btn = document.getElementById('view-mode-toggle');
    if (btn) btn.textContent = on ? 'Edit Mode' : 'View Mode';
    const badge = document.getElementById('view-mode-indicator');
    if (badge) badge.style.display = on ? 'inline-flex' : 'none';
}

/// Disables (or re-enables) the controls under `root` that View Mode locks;
/// ones already disabled for their own reasons stay as they are.
function lockForViewMode(root, on) {
    if (!root) return;
    root.querySelectorAll('input, select, textarea, button').forEach(el => {
        // Tools that change nothing (show a password, test a regex) stay usable.
        if (el.matches('.password-toggle, .regex-test-btn')) return;
        // Text stays focusable (the inspector and breadcrumb follow focus):
        // read-only, not disabled. A select or checkbox has no read-only.
        const textual = (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && !/^(checkbox|radio|button|file|hidden)$/.test(el.type)));
        if (textual) {
            if (on && !el.readOnly) { el.readOnly = true; el.dataset.viewLocked = 'ro'; }
            else if (!on && el.dataset.viewLocked === 'ro') { el.readOnly = false; delete el.dataset.viewLocked; }
        } else if (on && !el.disabled) { el.disabled = true; el.dataset.viewLocked = '1'; }
        else if (!on && el.dataset.viewLocked === '1') { el.disabled = false; delete el.dataset.viewLocked; }
    });
}

/// What the form draws later (a section filled on expand, a re-render): the
/// section arrows become keyboard buttons, and in View Mode new controls
/// are locked like the rest.
(function watchForm() {
    if (typeof document === 'undefined') return;
    const prep = (root) => {
        const all = (sel) => (root.matches && root.matches(sel) ? [root] : []).concat([...root.querySelectorAll(sel)]);
        all('.collapsible-arrow:not([tabindex])').forEach(a => {
            a.tabIndex = 0;
            a.setAttribute('role', 'button');
            a.setAttribute('aria-label', 'Expand or collapse');
            const h = a.closest('.collapsible-header, .map-entry-header, .array-item-header');
            if (h) h.setAttribute('aria-expanded', String(!h.classList.contains('collapsed')));
        });
        // A control with no label of its own (a list item, a map value) is
        // named by its path.
        all('input, select, textarea').forEach(el => {
            if (!el.getAttribute('aria-label') && !(el.labels && el.labels.length) && el.dataset.path && el.type !== 'hidden') {
                el.setAttribute('aria-label', el.dataset.path);
            }
        });
        if (document.body.classList.contains('view-mode')) lockForViewMode(root, true);
    };
    document.addEventListener('DOMContentLoaded', () => {
        const form = document.getElementById('dynamicForm');
        if (!form) return;
        // Only what was added (a render appends one node per property: the
        // whole form scanned per node was quadratic), each subtree once.
        new MutationObserver(muts => {
            const roots = [];
            for (const m of muts) {
                if (m.type === 'attributes') {
                    const h = m.target;
                    if (h.matches && h.matches('.collapsible-header, .map-entry-header, .array-item-header') && h.hasAttribute('aria-expanded')) {
                        h.setAttribute('aria-expanded', String(!h.classList.contains('collapsed')));
                    }
                    continue;
                }
                m.addedNodes.forEach(n => { if (n.nodeType === 1 && n.isConnected) roots.push(n); });
            }
            roots.filter(n => !roots.some(o => o !== n && o.contains(n))).forEach(n => prep(n));
        }).observe(form, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
        // A + or × replaces the control that was clicked: focus goes to
        // what took its place, not back to the top of the page.
        form.addEventListener('click', e => {
            const btn = e.target.closest && e.target.closest('button');
            if (!btn) return;
            const spot = btn.closest('.form-group, .add-property-placeholder, .collapsible-section, .array-item, .map-entry');
            const parent = spot && spot.parentElement;
            const at = parent ? [...parent.children].indexOf(spot) : -1;
            setTimeout(() => {
                if (btn.isConnected || !parent || !parent.isConnected) return;
                if (document.activeElement && document.activeElement !== document.body) return;
                const near = parent.children[Math.min(at, parent.children.length - 1)] || parent;
                const target = near.querySelector('input:not([type=hidden]), select, textarea, button, [tabindex="0"]');
                if (target) target.focus();
            }, 0);
        }, true);
        // Enter or Space on an arrow opens or folds its section.
        form.addEventListener('keydown', e => {
            if ((e.key === 'Enter' || e.key === ' ') && e.target.classList && e.target.classList.contains('collapsible-arrow')) {
                e.preventDefault();
                const h = e.target.closest('.collapsible-header, .map-entry-header, .array-item-header');
                if (h && h.onclick) h.onclick({ target: e.target });
            }
        });
    });
})();

// ---------------------------------------------------------------------------
// Auto-save draft to localStorage — snapshot every 2s when dirty; restore
// banner on load when a draft exists. Cheap insurance against accidental tab
// closes and power outages.

/// This page load's id. Each tab keeps its own draft of a file: two tabs on
/// one file used to share a key, so the last writer won, one tab's Save
/// cleared the other's crash copy, and a fresh tab was offered (and could
/// discard) another tab's live edits.
const DRAFT_TAB = Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
const DRAFT_LIVE = 'configurator-live:';   // + tab id: a heartbeat every 2 s while the tab is open
let offeredDraftKey = null;   // the draft the banner offers (perhaps a closed tab's)
let draftStoreFull = false;   // said once: drafts stopped because storage is full

function draftFileKey() {
    const target = (selectedTargetDirectory && selectedTargetDirectory.path) || '';
    const schemaName = currentSchemaName || '';
    if (!target || !schemaName) return null;
    return 'configurator-draft:' + target + ':' + schemaName;
}

function draftStorageKey() {
    const base = draftFileKey();
    return base ? base + '#' + DRAFT_TAB : null;
}

/// Tabs known open by the Web Locks they hold (a background tab's timers
/// are throttled to once a minute, so a heartbeat alone goes stale while the
/// tab is open): refreshed every 2 s and before a draft is offered.
let lockedTabs = null;
function refreshLockedTabs() {
    if (!(navigator.locks && navigator.locks.query)) return Promise.resolve();
    return navigator.locks.query().then(q => {
        lockedTabs = new Set((q.held || []).map(l => l.name).filter(n => n.startsWith('configurator-tab:')).map(n => n.slice('configurator-tab:'.length)));
    }).catch(() => {});
}
if (typeof navigator !== 'undefined' && navigator.locks && navigator.locks.request) {
    // Held for the life of this page: released when the tab closes or crashes.
    navigator.locks.request('configurator-tab:' + DRAFT_TAB, () => new Promise(() => {})).catch(() => {});
    refreshLockedTabs();
    setInterval(refreshLockedTabs, 2000);
}

/// Whether the tab that wrote a draft is still open: it holds its lock, or
/// (no Web Locks) its heartbeat is fresh.
function draftTabLive(tab) {
    if (tab === DRAFT_TAB) return true;
    if (lockedTabs) return lockedTabs.has(tab);
    try {
        const beat = Number(localStorage.getItem(DRAFT_LIVE + tab) || 0);
        return Date.now() - beat < 6000;
    } catch (e) { return false; }
}

/// The draft to offer for this file: this tab's, else the newest left by a
/// tab that is gone (a crash, a closed tab). Never another open tab's.
function offerableDraftKey() {
    const base = draftFileKey();
    if (!base) return null;
    let best = null, bestAt = -1;
    try {
        for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (k !== base && !k.startsWith(base + '#')) continue;   // the bare key: drafts from before tab ids
            const tab = k === base ? null : k.slice(base.length + 1);
            if (tab && tab !== DRAFT_TAB && draftTabLive(tab)) continue;
            if (tab === DRAFT_TAB) return k;
            let at = 0;
            try { at = (JSON.parse(localStorage.getItem(k)) || {}).savedAt || 0; } catch (e) { /* unreadable */ }
            if (at > bestAt) { bestAt = at; best = k; }
        }
    } catch (e) { return null; }
    return best;
}

(function draftHousekeeping() {
    if (typeof window === 'undefined' || !window.localStorage) return;
    const beat = () => { try { localStorage.setItem(DRAFT_LIVE + DRAFT_TAB, String(Date.now())); } catch (e) { /* full */ } };
    beat();
    setInterval(beat, 2000);
    window.addEventListener('pagehide', () => { try { localStorage.removeItem(DRAFT_LIVE + DRAFT_TAB); } catch (e) { /* ignore */ } });
    // Drafts and heartbeats nobody will come back for: a month old.
    try {
        const old = Date.now() - 30 * 24 * 3600 * 1000, stale = [];
        for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (k.startsWith(DRAFT_LIVE)) { if (Number(localStorage.getItem(k)) < Date.now() - 60000) stale.push(k); continue; }
            if (!k.startsWith('configurator-draft:')) continue;
            let at = 0;
            try { at = (JSON.parse(localStorage.getItem(k)) || {}).savedAt || 0; } catch (e) { /* unreadable */ }
            if (at < old) stale.push(k);
        }
        stale.forEach(k => localStorage.removeItem(k));
    } catch (e) { /* unavailable */ }
})();

/// A field's schema within an object schema: its own property, the map's
/// value, or the variant that has it (a password one first).
function draftChildSchema(sch, key) {
    if (!sch) return null;
    if (sch.$ref) sch = deref(sch) || sch;
    if (sch.properties && sch.properties[key]) return sch.properties[key];
    if (sch.additionalProperties && typeof sch.additionalProperties === 'object') return sch.additionalProperties;
    const variants = polymorphicVariants(sch) || [];
    let found = null;
    for (const v of variants) {
        const c = draftChildSchema(v, key);
        if (!c) continue;
        const r = c.$ref ? (deref(c) || c) : c;
        if (r.format === 'password') return c;
        if (!found) found = c;
    }
    return found;
}

function saveDraft() {
    // Mid-load the key already names the new file while the form still holds
    // the old one: a draft now would file the old form under the new file.
    if (schemaLoading) return;
    const key = draftStorageKey();
    if (!key) return;
    if (!document.getElementById('dirty-indicator') ||
        document.getElementById('dirty-indicator').style.display === 'none') {
        return; // only persist when the form is dirty
    }
    try {
        // Typed secrets ({CLEARTEXT}...) stay out of browser storage. They
        // come back as {REDACTED}, which Save refuses until each is typed
        // again; dropped, Save would write the file without the credential.
        // A password typed in the JSON tab is bare: found by the schema's
        // password fields, as the server finds it to encrypt.
        // A secret that is the file's own (a bare password the file holds,
        // untouched) is stored as {FROM-FILE}: restoring takes it from the
        // file. A typed one is {REDACTED}, never filled from the file: that
        // would put the old credential back over the new one, silently.
        const strip = (v, sch, f) => {
            sch = (sch && sch.$ref) ? (deref(sch) || sch) : sch;
            if (typeof v === 'string') {
                const bare = v !== '' && !/^\{(AES|AES256|3DES|REDACTED|FROM-FILE)\}/.test(v) && !/^\$\{[^}]+\}$/.test(v);
                if (!v.startsWith('{CLEARTEXT}') && !(bare && sch && sch.format === 'password')) return v;
                // Compared without the marker on either side: after a Save the
                // snapshot holds what was sent ({CLEARTEXT}pw), not the file's pw.
                const plain = x => String(x).replace(/^\{CLEARTEXT\}/, '');
                return (typeof f === 'string' && plain(f) === plain(v)) ? '{FROM-FILE}' : '{REDACTED}';
            }
            if (Array.isArray(v)) return v.map((x, i) => strip(x, sch && sch.items, Array.isArray(f) ? f[i] : undefined));
            if (v && typeof v === 'object') {
                const fo = (f && typeof f === 'object' && !Array.isArray(f)) ? f : {};
                return Object.keys(v).reduce((o, k) => { o[k] = strip(v[k], draftChildSchema(sch, k), fo[k]); return o; }, {});
            }
            return v;
        };
        const now = activeEditorData();   // the JSON tab's text when that is where the edits are
        if (now === undefined) return;    // invalid JSON mid-edit: keep the last good draft
        const data = strip(now, schema, initialData);
        localStorage.setItem(key, JSON.stringify({
            data: data,
            savedAt: Date.now(),
            schema: currentSchemaName,
            version: draftBaseVersion || fileVersion   // a restored draft keeps the version it was taken over
        }));
        draftStoreFull = false;
    } catch (e) {
        // Full (or unavailable): the crash copy has stopped. Said once.
        if (!draftStoreFull) showSchemaLoadStatus('Drafts are not being kept: browser storage is full or unavailable. Save to keep your edits.', 'warning');
        draftStoreFull = true;
    }
}

function clearDraft() {
    // This tab's draft, and the one the banner offered (a closed tab's) once
    // the operator has dealt with it.
    for (const key of [draftStorageKey(), offeredDraftKey]) {
        if (key) { try { localStorage.removeItem(key); } catch (e) { /* ignore */ } }
    }
    hideDraftBanner();
}

function maybeOfferDraftRestore(locksFresh = false) {
    // Asked again once the lock list is fresh: a draft must not be offered
    // from a tab that is open.
    if (lockedTabs && !locksFresh) {
        refreshLockedTabs().then(() => maybeOfferDraftRestore(true));
        return;
    }
    const key = offerableDraftKey();
    offeredDraftKey = key;
    if (!key) { hideDraftBanner(); return; }
    let rec;
    try {
        const raw = localStorage.getItem(key);
        if (!raw) { hideDraftBanner(); return; }
        rec = JSON.parse(raw);
    } catch (e) { hideDraftBanner(); return; }
    if (!rec || !rec.data) { hideDraftBanner(); return; }
    const banner = document.getElementById('draft-banner');
    const text = document.getElementById('draft-banner-text');
    if (banner && text) {
        const when = rec.savedAt ? new Date(rec.savedAt).toLocaleString() : 'recently';
        text.textContent = 'Unsaved draft from ' + when + '. Restore it?';
        banner.style.display = 'flex';
    }
}

function hideDraftBanner() {
    offeredDraftKey = null;
    const banner = document.getElementById('draft-banner');
    if (banner) banner.style.display = 'none';
}

function restoreDraft() {
    // Mid-load the key names the file being opened, the form the one left.
    if (schemaLoading) { showSyncStatus('Still loading: restore the draft once the file is in', 'warning'); return; }
    const key = offeredDraftKey;
    if (!key || !key.startsWith(draftFileKey() || '\u0000')) { hideDraftBanner(); return; }
    try {
        const rec = JSON.parse(localStorage.getItem(key) || 'null');
        if (rec && rec.data) {
            // Secrets were kept out of storage as {REDACTED}: put back the
            // file's own where the draft's item is still recognisably that one.
            currentData = fillRedactedFrom(rec.data, initialData);
            generateFormWithData(currentData);
            if (jsonEditor) jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
            setDirty();
            // Taken over an older file: restoring it and saving would undo
            // what was saved since. Saving is still possible; it is not hidden.
            draftBaseVersion = rec.version || null;
            if (key !== draftStorageKey()) { try { localStorage.removeItem(key); } catch (e) { /* ignore */ } }   // now this tab's
            if (rec.version && fileVersion && rec.version !== fileVersion) {
                showSyncStatus('Draft restored, but the file has changed since it was taken: check before saving', 'warning');
            } else {
                showSyncStatus('Draft restored', 'success');
            }
        }
    } catch (e) { /* ignore */ }
    hideDraftBanner();
}

/// `draft` with each {FROM-FILE} secret (the file's own, untouched when the
/// draft was taken) replaced by the file's value at the same place: a list
/// item is matched by being equal apart from secrets, else by a unique id.
/// Unmatched, it becomes {REDACTED}, as a typed secret always stays: Save
/// asks for it again.
function fillRedactedFrom(draft, file) {
    const R = '{FROM-FILE}';
    const same = (a, b) => {
        if (a === R || a === '{REDACTED}') return typeof b === 'string';
        if (Array.isArray(a)) return Array.isArray(b) && a.length === b.length && a.every((x, i) => same(x, b[i]));
        if (a && typeof a === 'object') return b && typeof b === 'object' && !Array.isArray(b)
            && Object.keys(a).length === Object.keys(b).length && Object.keys(a).every(k => same(a[k], b[k]));
        return a === b;
    };
    const pick = (d, list) => {
        const eq = list.filter(f => same(d, f));
        if (eq.length === 1) return eq[0];
        if (d && typeof d === 'object' && typeof d.id === 'string') {
            const byId = list.filter(f => f && f.id === d.id);
            if (byId.length === 1) return byId[0];
        }
        return undefined;
    };
    if (draft === R) return (typeof file === 'string') ? file : '{REDACTED}';
    if (Array.isArray(draft)) {
        // An original claimed by two draft items gives its secret to neither.
        const picks = draft.map(d => Array.isArray(file) ? pick(d, file) : undefined);
        return draft.map((d, i) => fillRedactedFrom(d,
            picks[i] !== undefined && picks.filter(p => p === picks[i]).length === 1 ? picks[i] : undefined));
    }
    if (draft && typeof draft === 'object') {
        const out = {};
        const f = (file && typeof file === 'object' && !Array.isArray(file)) ? file : {};
        Object.keys(draft).forEach(k => { out[k] = fillRedactedFrom(draft[k], f[k]); });
        return out;
    }
    return draft;
}

function discardDraft() {
    clearDraft();
    showSyncStatus('Draft discarded', 'success');
}

(function initAutoSave() {
    if (typeof window === 'undefined') return;
    // Drive on a cheap timer rather than setDirty() hooks — avoids coupling.
    setInterval(saveDraft, 2000);
})();

// ---------------------------------------------------------------------------
// Files tab — free-form text editor for everything under _templates/. Used
// to maintain REST body templates, LDAP queries, JDBC SQL, and other plain-
// text artifacts the iRouter (and other services) load at runtime.

let textEditor = null;           // Ace instance
let currentTextFile = null;      // relative path currently loaded
let currentTextFileBaseline = ''; // last-saved content, for Revert + dirty detection

function initTextEditorIfNeeded() {
    if (textEditor || typeof ace === 'undefined') return;
    const el = document.getElementById('text-editor');
    if (!el) return;
    textEditor = ace.edit('text-editor');
    textEditor.setTheme('ace/theme/' + (currentTheme || 'eclipse'));
    if (document.body.classList.contains('view-mode')) textEditor.setReadOnly(true);   // turned on before this tab opened
    textEditor.session.setMode('ace/mode/text');
    textEditor.setOptions({
        fontSize: 13,
        showPrintMargin: false,
        wrap: true,
        autoScrollEditorIntoView: true
    });
    textEditor.session.on('change', () => updateTextEditorDirtyState());
}

function textFileDirty() {
    return !!(textEditor && currentTextFile && textEditor.getValue() !== currentTextFileBaseline);
}

function updateTextEditorDirtyState() {
    const saveBtn = document.getElementById('save-text-file-btn');
    const revertBtn = document.getElementById('reload-text-file-btn');
    const dirty = textEditor && currentTextFile &&
        textEditor.getValue() !== currentTextFileBaseline;
    if (saveBtn) saveBtn.disabled = !dirty;
    if (revertBtn) revertBtn.disabled = !dirty;
}

function refreshTextFileList() {
    const body = document.getElementById('files-rail-body');
    if (body) body.innerHTML = '<em>Loading…</em>';
    sendWebSocketMessage('list_text_files', {});
}

function handleTextFileList(raw) {
    const body = document.getElementById('files-rail-body');
    if (!body) return;
    let items = [];
    try { items = JSON.parse(raw); } catch (e) { items = []; }
    if (!Array.isArray(items) || items.length === 0) {
        body.innerHTML = '<em>No template files found under <code>_templates/</code>. ' +
            'Drop <code>.txt</code>, <code>.sql</code>, <code>.ldap</code>, etc. into that directory.</em>';
        return;
    }
    body.innerHTML = items.map(it =>
        '<button type="button" class="files-rail-entry" data-path="' + escapeHtml(it.path) + '">' +
        escapeHtml(it.path) + '</button>'
    ).join('');
    body.querySelectorAll('.files-rail-entry').forEach(btn => {
        btn.onclick = () => {
            if (textFileDirty() && !confirm('Discard the unsaved changes to ' + currentTextFile + '?')) return;
            body.querySelectorAll('.files-rail-entry.active').forEach(a => a.classList.remove('active'));
            btn.classList.add('active');
            sendWebSocketMessage('load_text_file', { fileName: btn.dataset.path });
        };
    });
}

function handleTextFileLoaded(raw) {
    let payload;
    try { payload = JSON.parse(raw); } catch (e) { return; }
    if (!payload || typeof payload.content !== 'string') return;
    initTextEditorIfNeeded();
    if (!textEditor) return;
    currentTextFile = payload.fileName;
    currentTextFileBaseline = payload.content;
    textFileVersion = payload.version || null;
    textOverwrite = false;
    textEditor.session.setMode('ace/mode/' + aceModeForFile(payload.fileName));
    textEditor.setValue(payload.content, -1);
    // The editor holds one kind of line ending (the first it finds): a file
    // mixing CRLF and LF, or holding a lone CR, comes back uniform. Its
    // baseline is the editor's text, so opening it is not an edit; a Save
    // writes them all one way, and says so here first.
    const c = payload.content;
    const mixed = /\r(?!\n)/.test(c) || (/\r\n/.test(c) && /(^|[^\r])\n/.test(c));
    if (mixed) currentTextFileBaseline = textEditor.getValue();
    updateTextEditorDirtyState();
    const histBtn = document.getElementById('text-history-btn');
    if (histBtn) histBtn.disabled = false;
    if (mixed) {
        showTextFileStatus('Loaded ' + currentTextFile + '. It mixes line endings: a Save writes every one as '
            + (textEditor.session.getDocument().getNewLineCharacter() === '\r\n' ? 'CRLF' : 'LF') + '.', 'warning');
    } else {
        showTextFileStatus('Loaded ' + currentTextFile, 'success');
    }
}

function aceModeForFile(name) {
    const ext = (name.split('.').pop() || '').toLowerCase();
    const map = {
        sql: 'sql', json: 'json', xml: 'xml', yml: 'yaml', yaml: 'yaml',
        properties: 'properties', ldap: 'text', txt: 'text', conf: 'text'
    };
    return map[ext] || 'text';
}

let textSaveContent = null;   // what the last save_text_file sent
let textFileVersion = null;   // the template's version as loaded
let textOverwrite = false;    // the operator saw a conflict and saved again

function saveTextFile() {
    if (viewModeRefuses('save')) return;
    if (!websocket || websocket.readyState !== WebSocket.OPEN) {
        showTextFileStatus('Not connected to the server: save once it reconnects', 'error');
        return;
    }
    if (!textEditor || !currentTextFile) return;
    // One at a time: a second, sent before the first's reply, carries the
    // same version and would come back as a conflict with the first.
    if (textSaveContent !== null) { showTextFileStatus('Still saving…', 'warning'); return; }
    textSaveContent = textEditor.getValue();
    const msg = { fileName: currentTextFile, content: textSaveContent };
    if (textFileVersion) msg.baseVersion = textFileVersion;
    if (textOverwrite) msg.force = true;
    textOverwrite = false;
    sendWebSocketMessage('save_text_file', msg);
}

function handleTextFileSaved(raw) {
    if (!textEditor) return;
    let name = raw, auto = false, version = null;
    try { const p = JSON.parse(raw); name = p.fileName; version = p.version || null; auto = p.autoPublish === true; } catch (e) { /* plain name */ }
    // Another template opened since it was sent: its version and text are
    // not this editor's.
    if (name && name !== currentTextFile) {
        textSaveContent = null;
        showTextFileStatus('Saved ' + name, 'success');
        return;
    }
    textFileVersion = version;
    // The file holds what was sent; typing since then is still unsaved.
    currentTextFileBaseline = (textSaveContent !== null) ? textSaveContent : textEditor.getValue();
    textSaveContent = null;
    updateTextEditorDirtyState();
    // Pushed to every server like a configuration: now with auto-publish on
    // (text_file_pushed says how it went), else by the next Publish.
    if (auto) showTextFileStatus('Saved ' + name + '; sending it to the servers…', 'warning');
    else {
        setUnpublished(TEMPLATES_PENDING);
        showTextFileStatus('Saved ' + name + '. Auto-publish is off: Publish sends it to the servers.', 'warning');
    }
}

/// The entry in "Not Published" for templates saved with auto-publish off.
const TEMPLATES_PENDING = '_templates';

function handleTextFilePushed(raw) {
    let p = null; try { p = JSON.parse(raw); } catch (e) { return; }
    showTextFileStatus(p.fileName + ': ' + p.summary, p.ok ? 'success' : 'error');
}

function reloadTextFile() {
    if (!currentTextFile) return;
    sendWebSocketMessage('load_text_file', { fileName: currentTextFile });
}

function showTextFileStatus(text, kind) {
    showStatusIn(document.getElementById('text-file-status'), text, kind);
}

// Template-file version history. Reuses the shared version modal, but its
// Preview/Restore buttons dispatch to the text handlers below (the content is
// arbitrary text, not JSON, so it can't go through the form-based JSON flow).
function showTextVersionHistory() {
    if (!currentTextFile) {
        showTextFileStatus('Load a template file first', 'warning');
        return;
    }
    sendWebSocketMessage('list_text_versions', { fileName: currentTextFile });
    const modal = document.getElementById('versionModal');
    if (modal) modal.classList.add('active');
    const container = document.getElementById('versionListContainer');
    if (container) {
        container.innerHTML = '<div style="text-align: center; padding: 20px;">Loading versions...</div>';
    }
}

function previewTextVersion(timestamp) {
    if (!currentTextFile) return;
    pendingRequests.set('textVersionTimestamp', timestamp);
    sendWebSocketMessage('get_text_version_content', { fileName: currentTextFile, timestamp: timestamp });
}

function restoreTextVersion(timestamp) {
    if (!currentTextFile) return;
    if (viewModeRefuses('restore')) return;
    if (textFileDirty() && !confirm('Discard the unsaved changes to ' + currentTextFile + ' and restore this version?')) return;
    if (!confirm('Restore this version? The current content will be saved as a backup first.')) {
        return;
    }
    sendWebSocketMessage('restore_text_version', { fileName: currentTextFile, timestamp: timestamp });
}

function handleTextVersionContent(raw) {
    let payload;
    try { payload = JSON.parse(raw); } catch (e) { return; }
    if (!textEditor || typeof payload.content !== 'string') return;
    // Shown read-only in the dialog: dropping it into the editor would
    // overwrite unsaved edits there. Restore loads a version.
    const pre = document.getElementById('versionPreview');
    if (pre) {
        pre.textContent = (payload.fileName || currentTextFile) + ', earlier version (read-only; Restore to load it):\n\n' + payload.content;
        pre.style.display = 'block';
    }
}

function handleTextVersionRestored(raw) {
    let payload;
    try { payload = JSON.parse(raw); } catch (e) { return; }
    if (!textEditor || typeof payload.content !== 'string') return;
    // Another template opened since: the restore happened, but this editor
    // holds the other file.
    if (payload.fileName && payload.fileName !== currentTextFile) {
        showTextFileStatus('Restored ' + payload.fileName, 'success');
        return;
    }
    // Restore already wrote the file server-side; sync the editor to match.
    textEditor.setValue(payload.content, -1);
    currentTextFileBaseline = payload.content;
    if (payload.version) textFileVersion = payload.version;   // the next Save is over the restored file
    if (!autoPublishEnabled) setUnpublished(TEMPLATES_PENDING);   // a restore is a save
    textOverwrite = false;   // a conflict seen before is about a file this replaced
    updateTextEditorDirtyState();
    closeVersionHistory();
    showTextFileStatus('Restored ' + (payload.fileName || currentTextFile), 'success');
}

// ---------------------------------------------------------------------------
// Template library — "New from Template…" loads a starter config from
// _templates/<schemaName>/*.json into the form. The file becomes a draft —
// user reviews and Saves to persist.

function showTemplateLibrary() {
    if (!currentSchemaName) {
        showSyncStatus('Please select a schema first', 'warning');
        return;
    }
    const list = document.getElementById('template-list');
    if (list) list.innerHTML = '<em>Loading…</em>';
    const modal = document.getElementById('templateModal');
    if (modal) modal.style.display = 'flex';
    sendWebSocketMessage('list_templates', { schemaName: currentSchemaName });
}

function closeTemplateLibrary() {
    const modal = document.getElementById('templateModal');
    if (modal) modal.style.display = 'none';
}

function handleTemplateList(raw) {
    const list = document.getElementById('template-list');
    if (!list) return;
    let items = [];
    try { items = JSON.parse(raw); } catch (e) { items = []; }
    if (!Array.isArray(items) || items.length === 0) {
        list.innerHTML = '<em>No templates found. Drop *.json files into ' +
            '<code>_templates/' + escapeHtml(currentSchemaName || '&lt;schema&gt;') + '/</code> ' +
            'in the domain config directory.</em>';
        return;
    }
    list.innerHTML = items.map(entry =>
        '<button type="button" class="template-entry" data-name="' + escapeHtml(entry.name) + '">' +
        '<span class="template-entry-name">' + escapeHtml(entry.name) + '</span>' +
        '</button>'
    ).join('');
    list.querySelectorAll('.template-entry').forEach(btn => {
        btn.onclick = () => {
            const name = btn.dataset.name;
            templateAskedFor = currentSchemaName;
            sendWebSocketMessage('load_template', {
                schemaName: currentSchemaName,
                templateName: name
            });
        };
    });
}

let templateAskedFor = null;   // the application whose template was asked for

function handleTemplateLoaded(raw) {
    if (viewModeRefuses('load a template')) return;
    // Asked for one app's template: the editor may have moved on since.
    if (schemaLoading || currentSchemaName !== templateAskedFor) {
        showSyncStatus('Template not loaded: the editor moved to another application', 'warning');
        return;
    }
    if (selectedTargetDirectory && selectedTargetDirectory.type !== 'domain') {
        showSyncStatus('A template is a whole configuration: use it on the Domain target', 'warning');
        return;
    }
    if (!checkUnsavedChanges()) return;
    let data;
    try { data = JSON.parse(raw); }
    catch (e) {
        showSyncStatus('Template contained invalid JSON: ' + e.message, 'error');
        return;
    }
    if (data === null || typeof data !== 'object' || Array.isArray(data)) {
        showSyncStatus('Template not loaded: it is not a JSON object { … }', 'error');
        return;
    }
    loadedSourceText = raw;
    leftEditorText = null;
    currentData = data;
    generateFormWithData(currentData);
    if (jsonEditor) jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
    setDirty();
    closeTemplateLibrary();
    showSyncStatus('Template loaded: review and Save', 'success');
}

// ---------------------------------------------------------------------------
// Diff viewer — structural diff of the current form state vs. the last-loaded
// server copy (initialData). Reports added/removed/changed paths; clicking a
// change scrolls to and focuses the matching form element via its data-path.

/// Walk two JSON values in parallel, collecting per-path differences.
/// For arrays, index-aligned (no fancy move detection). For objects,
/// union of keys.
function diffObjects(a, b, path) {
    path = path || '';
    const out = [];
    if (a === b) return out;
    const bothObj = a && b && typeof a === 'object' && typeof b === 'object';
    const aArr = Array.isArray(a);
    const bArr = Array.isArray(b);
    if (!bothObj || aArr !== bArr) {
        out.push({ path: path, kind: 'changed', from: a, to: b });
        return out;
    }
    if (aArr) {
        const n = Math.max(a.length, b.length);
        for (let i = 0; i < n; i++) {
            const child = path + '[' + i + ']';
            if (i >= a.length) out.push({ path: child, kind: 'added', to: b[i] });
            else if (i >= b.length) out.push({ path: child, kind: 'removed', from: a[i] });
            else for (const d of diffObjects(a[i], b[i], child)) out.push(d);
        }
        return out;
    }
    const keys = new Set(Object.keys(a).concat(Object.keys(b)));
    keys.forEach(k => {
        const child = path ? path + '.' + k : k;
        if (!Object.hasOwn(a, k)) out.push({ path: child, kind: 'added', to: b[k] });
        else if (!Object.hasOwn(b, k)) out.push({ path: child, kind: 'removed', from: a[k] });
        else for (const d of diffObjects(a[k], b[k], child)) out.push(d);
    });
    return out;
}

function showDiff() {
    const current = (() => {
        const tab = document.querySelector('.tab-content.active');
        if (tab && tab.id === 'form-tab') return getFormData();
        if (jsonEditor) {
            try { return JSON.parse(jsonEditor.getValue()); } catch (e) { return null; }
        }
        return currentData;
    })();
    if (current == null) { showSyncStatus('Cannot diff invalid JSON', 'error'); return; }
    if (fileBroken) { showSyncStatus('The file on disk does not parse: there is nothing to compare with', 'warning'); return; }
    const baseline = initialData || {};
    // A bare password the file holds reads back marked {CLEARTEXT} (to be
    // encrypted on Save): the same value, not a change.
    const unmark = (c, b) => {
        if (typeof c === 'string' && c.startsWith('{CLEARTEXT}') && b === c.slice('{CLEARTEXT}'.length)) return b;
        if (Array.isArray(c)) return c.map((x, i) => unmark(x, Array.isArray(b) ? b[i] : undefined));
        if (c && typeof c === 'object') {
            const o = {};
            Object.keys(c).forEach(k => { o[k] = unmark(c[k], b && typeof b === 'object' ? b[k] : undefined); });
            return o;
        }
        return c;
    };
    const diffs = diffObjects(baseline, unmark(current, baseline));
    const summary = document.getElementById('diff-summary');
    const body = document.getElementById('diff-body');
    if (!summary || !body) return;

    if (diffs.length === 0) {
        summary.textContent = noFileYet ? 'No changes, and no file on disk yet: Save creates it.'
            : 'No changes vs. the last-loaded server copy.';
        body.innerHTML = '';
    } else {
        const counts = { added: 0, removed: 0, changed: 0 };
        diffs.forEach(d => counts[d.kind]++);
        summary.textContent =
            diffs.length + ' change' + (diffs.length === 1 ? '' : 's') +
            ' (' + counts.added + ' added, ' + counts.removed + ' removed, ' + counts.changed + ' modified)';
        body.innerHTML = diffs.map((d, i) => renderDiffRow(d, i)).join('');
        body.querySelectorAll('.diff-row').forEach(row => {
            row.onclick = () => jumpToPath(row.dataset.path);
            // By keyboard too: a row is a button that jumps to its field.
            row.tabIndex = 0;
            row.setAttribute('role', 'button');
            row.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); row.onclick(); } };
        });
    }
    const modal = document.getElementById('diffModal');
    if (modal) modal.style.display = 'flex';
}

function renderDiffRow(d, i) {
    const cls = 'diff-row diff-' + d.kind;
    const sign = d.kind === 'added' ? '+' : d.kind === 'removed' ? '\u2212' : '~';
    const valFrom = d.from === undefined ? '' : renderDiffValue(d.from);
    const valTo = d.to === undefined ? '' : renderDiffValue(d.to);
    let rhs;
    if (d.kind === 'added') rhs = '<span class="diff-val">' + valTo + '</span>';
    else if (d.kind === 'removed') rhs = '<span class="diff-val">' + valFrom + '</span>';
    else rhs = '<span class="diff-val diff-old">' + valFrom + '</span>' +
        '<span class="diff-arrow">\u2192</span>' +
        '<span class="diff-val diff-new">' + valTo + '</span>';
    return '<div class="' + cls + '" data-path="' + escapeHtml(d.path) + '" title="Click to jump">' +
        '<span class="diff-sign">' + sign + '</span>' +
        '<span class="diff-path">' + escapeHtml(d.path || '(root)') + '</span>' +
        rhs + '</div>';
}

function renderDiffValue(v) {
    let s;
    try { s = JSON.stringify(v); } catch (e) { s = String(v); }
    if (s === undefined) s = 'undefined';
    // A typed secret is not shown, here or in a value holding one.
    s = s.replace(/\{CLEARTEXT\}(?:[^"\\]|\\.)*/g, '{CLEARTEXT}••••');
    if (s.length > 120) s = s.slice(0, 117) + '\u2026';
    return escapeHtml(s);
}

function jumpToPath(path) {
    if (!path) return;
    // Try the exact path first (works for primitive fields and array items
    // whose input carries the leaf data-path).
    let el = document.querySelector('[data-path="' + cssEscape(path) + '"]');
    if (!el) {
        // Fallback: find any descendant whose data-path begins with this path
        // (useful when the diff is at a whole-object level, e.g. a new item).
        const all = document.querySelectorAll('[data-path]');
        for (const c of all) {
            const p = c.getAttribute('data-path') || '';
            if (p === path || p.indexOf(path + '.') === 0 || p.indexOf(path + '[') === 0) {
                el = c; break;
            }
        }
    }
    if (!el) return;
    // Close the modal so the scroll target is visible.
    closeDiffModal();
    revealField(el);   // folded sections around it open: a hidden field cannot be seen or focused
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    // Briefly highlight the target so it's obvious.
    el.classList.add('diff-highlight');
    setTimeout(() => el.classList.remove('diff-highlight'), 1600);
    if (typeof el.focus === 'function') el.focus({ preventScroll: true });
}

/// Opens every folded section, list item and map entry around `el`.
function revealField(el) {
    for (let n = el.parentElement; n && n.id !== 'dynamicForm'; n = n.parentElement) {
        if (!n.classList.contains('collapsed')) continue;
        if (!n.matches('.collapsible-content, .array-item-body, .map-entry-content')) continue;
        n.classList.remove('collapsed');
        n.style.maxHeight = 'none';
        const h = n.parentElement && n.parentElement.querySelector(':scope > .collapsible-header, :scope > .array-item-header, :scope > .map-entry-header');
        if (h) {
            h.classList.remove('collapsed');
            h.setAttribute('aria-expanded', 'true');
            const a = h.querySelector('.collapsible-arrow');
            if (a) a.classList.remove('collapsed');
        }
    }
}

function cssEscape(s) {
    // CSS attribute selector needs " and \ escaped. Keep it simple.
    return String(s).replace(/["\\]/g, '\\$&');
}

function closeDiffModal() {
    const modal = document.getElementById('diffModal');
    if (modal) modal.style.display = 'none';
}

/// Remember the last text input/textarea the user focused. The variable
/// palette uses this to know where to insert `${name}` when the user picks.
let lastFocusedTextInput = null;

/// Show/hide the right-side inspector panel.
function toggleInspector() {
    document.body.classList.toggle('inspector-open');
    const panel = document.getElementById('inspector-panel');
    const open = document.body.classList.contains('inspector-open');
    if (panel) panel.setAttribute('aria-hidden', open ? 'false' : 'true');
    if (open) refreshInspectorVars();
}

/// Render the focused field's schema into the inspector panel. Runs on
/// every focusin; cheap because it walks one object's keys.
function renderInspectorSchema(input) {
    const body = document.getElementById('inspector-schema-body');
    if (!body) return;
    const schema = input && input._schema;
    const path = input ? input.getAttribute('data-path') : null;
    if (!schema) {
        body.innerHTML = '<em>Focus a field to see its schema.</em>';
        return;
    }
    const rows = [];
    const push = (k, v) => { if (v !== undefined && v !== null && v !== '') rows.push([k, v]); };
    if (path) push('Path', path);
    push('Type', schema.type);
    if (schema.format) push('Format', schema.format);
    if (Array.isArray(schema.enum)) push('Enum', schema.enum.join(' | '));
    if (schema['default'] !== undefined) push('Default', JSON.stringify(schema['default']));
    if (schema.pattern) push('Pattern', schema.pattern);
    if (schema.minLength != null) push('Min length', schema.minLength);
    if (schema.maxLength != null) push('Max length', schema.maxLength);
    if (schema.minimum != null) push('Minimum', schema.minimum);
    if (schema.maximum != null) push('Maximum', schema.maximum);
    if (schema['x-wide']) push('Wide', 'yes');
    if (schema['x-readonly']) push('Read-only', 'yes');
    if (schema.description) push('Description', schema.description);
    if (rows.length === 0) { body.innerHTML = '<em>No schema details.</em>'; return; }
    body.innerHTML = rows.map(([k, v]) =>
        `<div class="inspector-row"><span class="inspector-key">${escapeHtml(k)}</span>` +
        `<span class="inspector-val">${escapeHtml(String(v))}</span></div>`
    ).join('');
}

/// Render the variable palette — every ${var} currently defined by any
/// `.id` field in the form. Clicking a variable inserts it at the caret
/// of the last-focused text input.
function refreshInspectorVars() {
    const body = document.getElementById('inspector-vars-body');
    const countEl = document.getElementById('inspector-vars-count');
    if (!body) return;
    const vars = availableContextVars();
    if (countEl) countEl.textContent = vars.length ? '(' + vars.length + ')' : '';
    if (vars.length === 0) {
        body.innerHTML = '<em>No variables defined yet. Add selectors with an <code>id</code>.</em>';
        return;
    }
    body.innerHTML = vars.map(v =>
        '<button type="button" class="inspector-var" data-var="' + escapeHtml(v) + '">${' + escapeHtml(v) + '}</button>'
    ).join('');
    body.querySelectorAll('.inspector-var').forEach(btn => {
        btn.onclick = () => insertVariableIntoFocused(btn.dataset.var);
    });
}

function insertVariableIntoFocused(name) {
    const input = lastFocusedTextInput;
    if (!input) return;
    const insertion = '${' + name + '}';
    const start = input.selectionStart != null ? input.selectionStart : (input.value || '').length;
    const end = input.selectionEnd != null ? input.selectionEnd : start;
    const before = (input.value || '').slice(0, start);
    const after = (input.value || '').slice(end);
    input.value = before + insertion + after;
    input.dispatchEvent(new Event('input', { bubbles: true }));   // an edit like typing: kept, marked, validated
    const caret = start + insertion.length;
    input.focus();
    if (input.setSelectionRange) input.setSelectionRange(caret, caret);
    setDirty();
}

/// Minimal HTML escape for inspector strings.
function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, ch => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[ch]);
}

/// Regex-tester modal. Opened from the "test" button beside any
/// RegexSelector.pattern input; runs the pattern against a sample string
/// and shows matched groups (numbered + named).
let regexTestBoundPatternInput = null;
// ---------------------------------------------------------------------------
// AI config assistant — the operator describes the configuration in plain
// language; the server asks Claude for a draft constrained by the app's own
// schema, validates it, and returns a proposal. The proposal lands in the
// editor exactly like a template or restored version (currentData +
// generateFormWithData + setDirty) so the diff view shows AI changes vs. the
// server copy, and Save/Publish stay the operator's explicit steps.

// The "Ask Claude" chat (shared blade-chat.js): one conversation for the
// loaded configuration. A change loads into the form as an unsaved
// proposal; a question gets an answer and changes nothing.
const aiChat = (typeof BladeChat !== 'undefined') ? BladeChat.create({
    ask: 'Ask Claude about this configuration…',
    askTitle: 'Chat with Claude about the loaded configuration: it drafts changes and answers questions',
    title: 'Claude · this configuration',
    intro: 'Ask for a change, or ask about a setting. For example:<ul>'
        + '<li>Set the logging level to FINE</li>'
        + '<li>What does session expiration do?</li>'
        + '<li>Turn on the keep-alive with a 60-second interval</li></ul>'
        + 'It sees this app\'s schema and the configuration as it is in the editor. Secrets in the configuration are never sent; what you type here is, so do not type one. '
        + 'A change loads into the form for you to check; nothing is saved until you Save.',
    send: askClaude,
}) : null;
let aiPending = null;
let lastChatKey = null;   // the app and target the conversation is about
let aiSeq = 0;            // numbers each question; an answer names the one it is for   // {resolve, reject, app} while a message is out

function handleAiStatus(content) {
    if (aiChat) aiChat.enable(content === 'true');
}

let aiLoads = 0;          // proposals loaded into the form, to retire older "Load it anyway" buttons

function askClaude(text, history, onSteps) {
    if (document.body.classList.contains('view-mode')) return Promise.reject(new Error('View Mode: switch to Edit Mode to ask for changes.'));
    if (!currentSchemaName) return Promise.reject(new Error('Pick a target and a schema first.'));
    if (schemaLoading) return Promise.reject(new Error('Still loading the file: ask once it is in the form.'));
    // Baseline = whatever the operator is looking at right now (same source
    // selection as saveData): unsaved edits are part of the message's context.
    let data;
    const activeTab = document.querySelector('.tab-content.active').id;
    if (activeTab === 'form-tab') {
        data = getFormData();
        if (readBackProblems.length) return Promise.reject(new Error('Fix this first: ' + readBackProblems[0]));
    } else {
        try {
            data = JSON.parse(jsonEditor.getValue());
        } catch (e) {
            return Promise.reject(new Error('The JSON tab holds invalid JSON: fix it, or switch to the form.'));
        }
    }
    if (!websocket || websocket.readyState !== WebSocket.OPEN) {
        return Promise.reject(new Error('Not connected to the server; reload the page and ask again.'));
    }
    onSteps(['asking Claude: a large schema can take a minute']);
    return new Promise((resolve, reject) => {
        // What the proposal is built from, to tell whether the operator kept
        // editing (or changed target) while it was written.
        aiSeq++;
        aiPending = { id: String(aiSeq), resolve, reject, app: currentSchemaName,
            target: selectedTargetDirectory ? selectedTargetDirectory.path : null,
            baseline: JSON.stringify(canonicalJson(data)),
            version: draftBaseVersion || fileVersion };
        sendWebSocketMessage('ai_generate', {
            appName: currentSchemaName,
            instruction: text,
            config: JSON.stringify(data),
            history: history,
            requestId: String(aiSeq),
            // An overlay is drafted, and checked, as a partial file.
            targetDirectory: selectedTargetDirectory ? selectedTargetDirectory.path : ''
        });
    });
}

function handleAiResult(raw) {
    let result;
    try { result = JSON.parse(raw); }
    catch (e) {
        const p = aiPending; aiPending = null;
        if (p) p.reject(new Error('The server returned an unreadable result: ' + e.message));
        return;
    }
    // The answer to an earlier question (asked before a switch): not this one's.
    if (!aiPending || (result.requestId && result.requestId !== aiPending.id)) return;
    const pending = aiPending;
    aiPending = null;
    if (!result.config) {
        pending.resolve({ text: result.reply || '(no answer)' });
        return;
    }
    const here = selectedTargetDirectory ? selectedTargetDirectory.path : null;
    if (pending.app !== currentSchemaName || pending.target !== here) {
        pending.resolve({ text: (result.reply || '') + '\n\nNot loaded: the editor now shows another '
            + (pending.app !== currentSchemaName ? 'schema' : 'target') + '. Ask again there.' });
        return;
    }
    const count = (result.errors || []).length;
    const loadsAtAnswer = aiLoads, loadAtAnswer = loadSeq;
    const apply = (later) => {
        if (viewModeRefuses('load a proposal')) return false;
        // A button left in the log: a later proposal, or a reload, since
        // replaced what this one was offered over.
        if (later && (aiLoads !== loadsAtAnswer || loadSeq !== loadAtAnswer)) {
            showSyncStatus('Not loaded: the form has changed since (another proposal or a reload). Ask again.', 'warning');
            return false;
        }
        // Clicked later: still the schema and target the proposal was made for?
        const nowTarget = selectedTargetDirectory ? selectedTargetDirectory.path : null;
        if (currentSchemaName !== pending.app || nowTarget !== pending.target) {
            showSyncStatus('Not loaded: this proposal was for ' + pending.app + ' on another target or schema', 'warning');
            return;
        }
        if (schemaLoading) {
            showSyncStatus('Not loaded: the file is reloading; ask again', 'warning');
            return;
        }
        // Built from the file as it was when asked. If the form now holds a
        // later version (a reload, a restore), loading it and saving would
        // quietly undo that version's changes: the save carries its version.
        const nowData = activeEditorData();
        const sameBase = (draftBaseVersion || fileVersion) === pending.version
            || (nowData !== undefined && JSON.stringify(canonicalJson(nowData)) === pending.baseline);
        if (!sameBase) {
            showSyncStatus('Not loaded: the file changed since you asked (a reload or restore brought in another version). Ask again.', 'error');
            return;
        }
        aiLoads++;
        currentData = result.config;
        leftEditorText = null;   // the proposal replaces what the JSON tab held
        setDirty();   // first: a form that fails to draw is still not the file
        try {
            generateFormWithData(currentData);
            if (jsonEditor) jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
        } catch (e) {
            showSyncStatus('The proposal could not be drawn (' + e.message + '): Reset goes back to the file', 'error');
            return false;
        }
        return true;
    };
    // What the server found, shown (a secret it could not carry over, a schema error).
    const problemsLine = count ? ' · ' + (result.errors || []).slice(0, 2).join('; ') + (count > 2 ? ' (and ' + (count - 2) + ' more)' : '') : '';
    if (schemaLoading) {
        // The file is being reloaded: what was asked about is being replaced.
        pending.resolve({ text: (result.reply || '') + '\n\nNot loaded: the file was reloading. Ask again.' });
        return;
    }
    const now = activeEditorData();
    const edited = now === undefined || JSON.stringify(canonicalJson(now)) !== pending.baseline;
    if (edited) {
        // The operator kept editing while this was written: their edits win
        // unless they choose the proposal, which was built without them.
        pending.resolve({
            text: result.reply || 'A proposal is ready.',
            meta: 'Not loaded: you edited the form while this was written' + (count ? ' · ' + count + ' problem' + (count === 1 ? '' : 's') + problemsLine : ''),
            actions: [{ label: 'Load it anyway', title: 'Replace the form, and the edits made since asking, with this proposal', run: () => apply(true) }],
        });
        return;
    }
    if (!apply(false)) {
        pending.resolve({ text: result.reply || 'A proposal is ready.', meta: 'Not loaded: see the status line' });
        return;
    }
    pending.resolve({
        text: result.reply || 'Loaded a proposal.',
        meta: result.valid ? 'Loaded into the form · not saved'
            : 'Loaded with ' + count + ' problem' + (count === 1 ? '' : 's') + problemsLine,
        actions: [{ label: 'Show changes', title: 'What this changes against the file on disk', run: showDiff }],
    });
}

function handleAiError(raw) {
    let message = raw, id = null;
    try { const e = JSON.parse(raw); message = e.error; id = e.requestId || null; } catch (x) { /* plain text */ }
    if (!aiPending || (id && id !== aiPending.id)) return;   // an earlier question's failure
    const pending = aiPending;
    aiPending = null;
    pending.reject(new Error(message || 'No answer.'));
}

function openRegexTest(patternInput) {
    regexTestBoundPatternInput = patternInput;
    const modal = document.getElementById('regexTestModal');
    const patternEl = document.getElementById('regex-test-pattern');
    const sampleEl = document.getElementById('regex-test-sample');
    const resultEl = document.getElementById('regex-test-result');
    if (!modal) return;
    if (patternEl) patternEl.textContent = (patternInput.value || '').trim() || '(empty)';
    if (sampleEl && !sampleEl.value) sampleEl.value = '';
    if (resultEl) resultEl.innerHTML = '';
    modal.style.display = 'flex';
}
function closeRegexTest() {
    const modal = document.getElementById('regexTestModal');
    if (modal) modal.style.display = 'none';
    regexTestBoundPatternInput = null;
}
/// A Java pattern (java.util.regex, as RegexSelector compiles it) as a
/// JavaScript one: leading inline flags ((?i), (?im)) become flags, \A and
/// \z/\Z become ^ and $ (outside a character class). Constructs JavaScript
/// lacks or reads differently (possessive and atomic groups, \p{Alpha}-style
/// classes) are named in `javaOnly` instead of being tested wrongly.
function javaRegexForJs(p) {
    let flags = '';
    const lead = p.match(/^\(\?([imsux]+)\)/);
    if (lead) {
        if (lead[1].includes('i')) flags += 'i';
        if (lead[1].includes('m')) flags += 'm';
        if (lead[1].includes('x')) return { javaOnly: 'the (?x) comments flag' };
        p = p.slice(lead[0].length);
    }
    let out = '', inClass = false;
    for (let i = 0; i < p.length; i++) {
        const c = p[i];
        if (c === '\\' && i + 1 < p.length) {
            const d = p[i + 1];
            if (!inClass && d === 'A') { out += '^'; i++; continue; }
            if (!inClass && (d === 'z' || d === 'Z')) { out += '$'; i++; continue; }
            if (d === 'p' || d === 'P') {
                if (p[i + 2] === '{' && /^\{(Alpha|Digit|Alnum|Upper|Lower|Punct|Space|XDigit|Blank|Cntrl|Graph|Print|ASCII|java\w+|Is\w+|In\w+)\}/.test(p.slice(i + 2))) {
                    return { javaOnly: '\\' + d + p.slice(i + 2, p.indexOf('}', i) + 1) };
                }
                return { javaOnly: '\\' + d + ' classes' };
            }
            out += c + d; i++; continue;
        }
        if (c === '[' && !inClass) inClass = true;
        else if (c === ']' && inClass) inClass = false;
        if (!inClass) {
            if (c === '(' && p.startsWith('(?>', i)) return { javaOnly: 'an atomic group (?>...)' };
            if (c === '+' && i > 0 && /[*+?}]/.test(p[i - 1]) && p[i - 2] !== '\\') return { javaOnly: 'a possessive quantifier' };
            if (c === '(' && /^\(\?[imsux-]+[):]/.test(p.slice(i))) return { javaOnly: 'an inline flag inside the pattern' };
        }
        out += c;
    }
    return { source: out, flags: flags };
}

function runRegexTest() {
    const sampleEl = document.getElementById('regex-test-sample');
    const resultEl = document.getElementById('regex-test-result');
    if (!sampleEl || !resultEl) return;
    // The pattern as saved: not trimmed (a trailing space is part of it).
    const pattern = regexTestBoundPatternInput ? (regexTestBoundPatternInput.value || '') : '';
    const sample = sampleEl.value || '';
    if (!pattern) { resultEl.innerHTML = '<em>No pattern set.</em>'; return; }
    // Anchor for parity with RegexSelector.extract (which uses Matcher.matches()
    // — require the whole input to match, not a substring), DOTALL as there.
    // Wrap in (?:...) so existing numbered/named capture groups keep their indices.
    const j = javaRegexForJs(pattern);
    if (j.javaOnly) {
        resultEl.innerHTML = '<span class="regex-test-error">This pattern uses Java-only syntax (' + escapeHtml(j.javaOnly)
            + '). The server accepts it; this browser tester cannot run it.</span>';
        return;
    }
    let re;
    try { re = new RegExp('^(?:' + j.source + ')$', 's' + j.flags); }
    catch (err) { resultEl.innerHTML = '<span class="regex-test-error">Invalid pattern: ' + escapeHtml(err.message) + '</span>'; return; }
    const m = re.exec(sample);
    if (!m) { resultEl.innerHTML = '<span class="regex-test-nomatch">No match. (full-string match, same as iRouter)</span>'; return; }
    const rows = [];
    rows.push('<div class="regex-test-hit">Matched.</div>');
    for (let i = 0; i < m.length; i++) {
        rows.push('<div class="inspector-row"><span class="inspector-key">$' + i + '</span>' +
            '<span class="inspector-val">' + escapeHtml(String(m[i])) + '</span></div>');
    }
    if (m.groups) {
        Object.keys(m.groups).forEach(name => {
            rows.push('<div class="inspector-row"><span class="inspector-key">${' + escapeHtml(name) + '}</span>' +
                '<span class="inspector-val">' + escapeHtml(String(m.groups[name] == null ? '(null)' : m.groups[name])) + '</span></div>');
        });
    }
    resultEl.innerHTML = rows.join('');
}

/// Wire inspector + lastFocusedTextInput once at startup.
(function initInspector() {
    if (typeof document === 'undefined') return;
    document.addEventListener('focusin', (e) => {
        const t = e.target;
        if (!t) return;
        // The form's text fields only: a number field cannot hold "${var}"
        // (the browser blanks it), and the filter, chat and regex boxes are
        // not configuration.
        if (t.closest && t.closest('#dynamicForm') && !t.classList.contains('map-key-input')
                && ((t.tagName === 'INPUT' && (t.type === 'text' || t.type === 'password')) || t.tagName === 'TEXTAREA')) {
            lastFocusedTextInput = t;
        }
        if (t._schema) {
            renderInspectorSchema(t);
            if (document.body.classList.contains('inspector-open')) refreshInspectorVars();
        }
    });
})();

/// An edit inside a map entry or list item marks it, and every entry around
/// it, as touched: a null the file states is kept only while untouched.
/// What the latest user action could have changed: every element around its
/// target, taken when the event fires (a × removes its own element before
/// the change is reported). setDirty, which every action that changes the
/// form calls, marks them all touched; an action that changes nothing (help,
/// the password eye) never calls it. A key's rename touches the map that
/// holds the entry, not the entry's value: renaming keeps a null or a
/// mistyped value as the file had it.
let lastActionAround = [];
(function initTouchTracking() {
    if (typeof document === 'undefined') return;
    const mark = (e) => {
        const t = e.target;
        if (!t || !t.closest || !t.closest('#dynamicForm')) return;
        if (e.type === 'input' || e.type === 'change') t._edited = true;
        const isKey = t.classList && t.classList.contains('map-key-input');
        const around = [];
        const form = document.getElementById('dynamicForm');
        let el = isKey ? (t.closest('.map-entry') || t).parentElement : t;
        for (; el && el !== form; el = el.parentElement) around.push(el);
        lastActionAround = around;
        // Typing marks at once (an input event is a change by itself).
        if (e.type === 'input' || e.type === 'change') touchAround();
    };
    ['input', 'change', 'click', 'dragstart', 'drop'].forEach(type => document.addEventListener(type, mark, true));
})();
function touchAround() {
    lastActionAround.forEach(el => { el._touched = true; });
}

/// Global keyboard shortcuts. Ctrl/Cmd-S saves; Ctrl/Cmd-F focuses the
/// field-filter input; Escape clears it. Scoped to the configurator so
/// other typing inside inputs is untouched.
(function initShortcuts() {
    if (typeof document === 'undefined') return;
    document.addEventListener('keydown', (e) => {
        const mod = e.metaKey || e.ctrlKey;
        if (!mod) {
            if (e.key === 'Escape') {
                const f = document.getElementById('form-filter');
                if (f && f.value) {
                    f.value = '';
                    if (typeof filterForm === 'function') filterForm('');
                }
            }
            return;
        }
        if (e.key === 's' || e.key === 'S') {
            e.preventDefault();
            // Save what the visible tab edits: a template on the Files tab, the
            // config on the Form and JSON tabs, nothing on the read-only Schema tab.
            const tab = document.querySelector('.tab-content.active');
            if (tab && tab.id === 'files-tab') { if (textFileDirty()) saveTextFile(); }
            else if (tab && tab.id === 'schema-tab') { /* read-only */ }
            else if (typeof saveData === 'function') saveData();
        } else if (e.key === 'f' || e.key === 'F') {
            const f = document.getElementById('form-filter');
            const onForm = document.querySelector('.tab-content.active#form-tab');
            if (f && onForm) {
                e.preventDefault();
                f.focus();
                f.select();
            }
        }
    });
})();

/// Wire the toolbar dropdown menus. One menu is open at a time; any click
/// inside the menu (on an action button) or outside the menu closes it.
(function initToolbarMenus() {
    if (typeof document === 'undefined') return;
    const bind = () => {
        document.querySelectorAll('.toolbar-menu-trigger').forEach(trigger => {
            if (trigger.dataset.wired === 'true') return;
            trigger.dataset.wired = 'true';
            trigger.addEventListener('click', (e) => {
                e.stopPropagation();
                const menu = trigger.closest('.toolbar-menu');
                if (!menu) return;
                const wasOpen = menu.classList.contains('open');
                document.querySelectorAll('.toolbar-menu.open').forEach(m => m.classList.remove('open'));
                if (!wasOpen) menu.classList.add('open');
            });
        });
        document.querySelectorAll('.toolbar-menu-items > button').forEach(btn => {
            if (btn.dataset.wired === 'true') return;
            btn.dataset.wired = 'true';
            btn.addEventListener('click', () => {
                const menu = btn.closest('.toolbar-menu');
                if (menu) menu.classList.remove('open');
            });
        });
        if (!document._toolbarMenuGlobal) {
            document._toolbarMenuGlobal = true;
            document.addEventListener('click', () => {
                document.querySelectorAll('.toolbar-menu.open').forEach(m => m.classList.remove('open'));
            });
            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape') {
                    document.querySelectorAll('.toolbar-menu.open').forEach(m => m.classList.remove('open'));
                }
            });
        }
    };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bind);
    } else {
        bind();
    }
})();

/// Wire the breadcrumb bar once at startup. Tracks the focused field's
/// data-path so the user can see where they are in a large config, with
/// click-to-copy for pasting into bug reports / docs.
(function initBreadcrumb() {
    if (typeof document === 'undefined') return;
    const bind = () => {
        const crumb = document.getElementById('field-breadcrumb');
        if (!crumb || crumb.dataset.wired === 'true') return;
        crumb.dataset.wired = 'true';
        document.addEventListener('focusin', (e) => {
            const el = e.target.closest('[data-path]');
            const path = el ? (el.getAttribute('data-path') || '') : '';
            if (path) {
                crumb.textContent = path;
                crumb.style.display = '';
            }
        });
        crumb.addEventListener('click', () => {
            const txt = crumb.textContent || '';
            if (!txt || !navigator.clipboard) return;
            navigator.clipboard.writeText(txt).then(() => {
                crumb.classList.add('copied');
                setTimeout(() => crumb.classList.remove('copied'), 800);
            }).catch(() => { /* ignore */ });
        });
    };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bind);
    } else {
        bind();
    }
})();

/// Live form filter. Hides every leaf (.form-group, .add-property-placeholder,
/// .extras-row, .map-entry) whose label / data-path / input value doesn't
/// contain the query substring. Collapsible containers (sections, map-entries,
/// array-items) stay visible whenever any descendant matched, and auto-expand
/// to surface the hit. Empty query → clear all filters.
function filterForm(query) {
    const form = document.getElementById('dynamicForm');
    if (!form) return;
    const q = (query || '').trim().toLowerCase();
    // Clear previous filter state on every keystroke; we reapply from scratch.
    form.querySelectorAll('.filtered-out').forEach(el => el.classList.remove('filtered-out'));
    if (!q) return;

    // Build a searchable string for a leaf element.
    const leafMatches = (el) => {
        const path = el.querySelector('[data-path]') ?
            (el.querySelector('[data-path]').getAttribute('data-path') || '') : '';
        const label = (el.querySelector('label, .add-property-label') || {}).textContent || '';
        const inputVals = Array.from(el.querySelectorAll('input, textarea, select'))
            .filter(i => i.type !== 'password' && i.dataset.password !== 'true')   // no guessing a secret
            .map(i => i.value || '').join(' ');
        const key = (el.querySelector('.map-key-input, .extras-key-input') || {}).value || '';
        const title = el.querySelector('.collapsible-title, .array-item-type-label');
        const titleText = title ? (title.textContent || '') : '';
        return (path + ' ' + label + ' ' + inputVals + ' ' + key + ' ' + titleText).toLowerCase().includes(q);
    };

    // Hide every leaf that doesn't match. Track which containers hold a hit.
    const keepers = new Set();
    const leafSelector = '.form-group, .add-property-placeholder, .extras-row, .map-entry';
    form.querySelectorAll(leafSelector).forEach(leaf => {
        if (leafMatches(leaf)) {
            // Walk up: mark every collapsible ancestor as a keeper.
            let cur = leaf.parentElement;
            while (cur && cur !== form) {
                keepers.add(cur);
                cur = cur.parentElement;
            }
        } else {
            leaf.classList.add('filtered-out');
        }
    });

    // Hide any collapsible container that didn't have a matching descendant.
    form.querySelectorAll('.collapsible-section, .array-item').forEach(c => {
        if (!keepers.has(c) && !leafMatches(c)) c.classList.add('filtered-out');
    });

    // Auto-expand surviving collapsed containers so the matches are visible.
    form.querySelectorAll('.collapsible-header.collapsed, .array-item-header.collapsed').forEach(h => {
        const body = h.classList.contains('array-item-header')
            ? h.parentElement.querySelector(':scope > .array-item-body')
            : h.nextElementSibling;
        const arrow = h.querySelector('.collapsible-arrow');
        if (body && arrow) toggleCollapse(h, body, arrow);
    });
}

function refreshAllSummaries() {
    document.querySelectorAll('.collapsible-header').forEach(h => {
        if (h.nextElementSibling) refreshSectionSummary(h, h.nextElementSibling);
    });
}

function expandAll() {
    // Expand collapsible sections
    document.querySelectorAll('.collapsible-header.collapsed').forEach(header => {
        const content = header.nextElementSibling;
        const arrow = header.querySelector('.collapsible-arrow');
        header.classList.remove('collapsed');
        content.classList.remove('collapsed');
        if (arrow) arrow.classList.remove('collapsed');
        content.style.maxHeight = 'none';
    });
    // Expand map entries
    document.querySelectorAll('.map-entry-header.collapsed').forEach(header => {
        const content = header.nextElementSibling;
        const arrow = header.querySelector('.collapsible-arrow');
        header.classList.remove('collapsed');
        content.classList.remove('collapsed');
        if (arrow) arrow.classList.remove('collapsed');
        content.style.maxHeight = 'none';
    });
    refreshAllSummaries();
}

function collapseAll() {
    // Collapse map entries (these handle their inner sections)
    document.querySelectorAll('.map-entry-header:not(.collapsed)').forEach(header => {
        const content = header.nextElementSibling;
        const arrow = header.querySelector('.collapsible-arrow');
        header.classList.add('collapsed');
        content.classList.add('collapsed');
        if (arrow) arrow.classList.add('collapsed');
        content.style.maxHeight = '0px';
    });
    // Collapse collapsible sections (skip ones inside map-entry-content whose arrow is hidden)
    document.querySelectorAll('.collapsible-header:not(.collapsed)').forEach(header => {
        const arrow = header.querySelector('.collapsible-arrow');
        if (arrow && arrow.style.display === 'none') return;
        const content = header.nextElementSibling;
        header.classList.add('collapsed');
        content.classList.add('collapsed');
        if (arrow) arrow.classList.add('collapsed');
        content.style.maxHeight = '0px';
    });
    refreshAllSummaries();
}

function collapseEmpty() {
    document.querySelectorAll('.collapsible-section').forEach(section => {
        const badge = section.querySelector('.collapsible-badge.null');
        if (badge) {
            const header = section.querySelector('.collapsible-header');
            const arrow = header.querySelector('.collapsible-arrow');
            if (arrow && arrow.style.display === 'none') return;
            const content = header.nextElementSibling;
            if (!header.classList.contains('collapsed')) {
                header.classList.add('collapsed');
                content.classList.add('collapsed');
                if (arrow) arrow.classList.add('collapsed');
                content.style.maxHeight = '0px';
            }
        }
    });
    refreshAllSummaries();
}

// ========================================
// Feature 1: Unsaved Changes Warning
// ========================================

/// Key order does not make two documents differ.
function canonicalJson(v) {
    return Array.isArray(v) ? v.map(canonicalJson)
        : (v && typeof v === 'object') ? Object.keys(v).sort().reduce((o, k) => (o[k] = canonicalJson(v[k]), o), {}) : v;
}

function setDirty(moved = false) {
    // What the action that made the change was in; not a drag, which moves
    // values without editing them (a file's mistyped value stays its own).
    if (!moved) touchAround();
    if (!isDirty) {
        isDirty = true;
        const indicator = document.getElementById('dirty-indicator');
        if (indicator) {
            indicator.style.display = 'inline-flex';
        }
        updateTitle();
    }
}

/// The tab's title names the file and whether it has unsaved edits, so two
/// tabs on two files (or one file) can be told apart.
function updateTitle() {
    const t = selectedTargetDirectory;
    const where = currentSchemaName
        ? currentSchemaName + (t && t.type !== 'domain' ? ' (' + t.displayName + ')' : '') + ' · '
        : 'Editor · ';
    document.title = (isDirty ? '* ' : '') + where + 'BLADE Configurator';
}

function clearDirty() {
    isDirty = false;
    const indicator = document.getElementById('dirty-indicator');
    if (indicator) {
        indicator.style.display = 'none';
    }
    // Save current snapshot for comparison
    savedDataSnapshot = JSON.stringify(getFormData());
    updateTitle();
}

/// Apps saved (auto-publish off) and not yet published. Kept across schema
/// switches: leaving an app does not publish it, and the indicator and Publish
/// keep it in view until it is.
const unpublishedApps = new Set();

function renderUnpublished() {
    isUnpublished = unpublishedApps.size > 0;
    document.body.classList.toggle('has-unpublished', isUnpublished);   // Publish stays reachable
    const indicator = document.getElementById('unpublished-indicator');
    if (!indicator) return;
    indicator.style.display = isUnpublished ? 'inline-flex' : 'none';
    const names = [...unpublishedApps];
    indicator.textContent = '▲ Not Published' + (names.length === 1 && names[0] === currentSchemaName ? '' : ': ' + names.join(', '));
    indicator.title = 'Saved but not live yet: ' + names.join(', ') + '. Publish pushes them to the running services.';
}

function setUnpublished(app = currentSchemaName) {
    if (app) unpublishedApps.add(app);
    renderUnpublished();
}

function clearUnpublished(app = currentSchemaName) {
    if (app) unpublishedApps.delete(app);
    renderUnpublished();
}

/// The editor's data as the active tab holds it: the JSON tab's text is not
/// in the form until it is applied, and edits made there count too.
function activeEditorData() {
    const active = document.querySelector('.tab-content.active');
    if (active && active.id === 'json-tab' && jsonEditor) {
        try { return JSON.parse(jsonEditor.getValue()); } catch (e) { return undefined; }
    }
    return getFormData();
}

function checkUnsavedChanges() {
    if (isDirty && savedDataSnapshot !== null) {
        const now = activeEditorData();
        const currentData = (now === undefined) ? null : JSON.stringify(now);
        if (currentData === null || JSON.stringify(canonicalJson(JSON.parse(currentData))) !== JSON.stringify(canonicalJson(JSON.parse(savedDataSnapshot)))) {
            const discard = confirm('You have unsaved changes. Do you want to discard them?');
            // Discarded: not offered back as a draft on the next open.
            if (discard && typeof clearDraft === 'function') clearDraft();
            return discard;
        }
    }
    return true;
}

// Track form changes
function setupDirtyTracking() {
    // Track form input changes
    document.addEventListener('input', function(e) {
        if (e.target.closest('#dynamicForm') || e.target.closest('#json-editor')) {
            setDirty();
        }
    });

    // Track JSON editor changes
    if (jsonEditor) {
        jsonEditor.session.on('change', function() {
            if (!syncingEditor) setDirty();
        });
    }

    // Warn before leaving page — unsaved edits (data loss) or saved-but-not-live
    // changes (auto-publish off, no Publish yet).
    window.addEventListener('beforeunload', function(e) {
        if (isDirty || textFileDirty()) {
            e.preventDefault();
            e.returnValue = 'You have unsaved changes. Are you sure you want to leave?';
            return e.returnValue;
        }
        if (isUnpublished) {
            e.preventDefault();
            e.returnValue = 'You have saved changes that are not published yet. Leave anyway?';
            return e.returnValue;
        }
    });
}

// ========================================
// Feature 6: Clone Configuration
// ========================================

function showCloneDialog() {
    if (!currentSchemaName) {
        showSyncStatus('Please load a schema first', 'warning');
        return;
    }

    // Populate clone target dropdown
    const select = document.getElementById('clone-target-select');
    select.innerHTML = '<option value="">-- Select Target --</option>';

    // Only between targets of one kind. A cluster or server file is merged
    // over the domain file, its lists added to the domain's: a whole domain
    // file cloned there repeats every list, and an overlay cloned to the
    // domain replaces the whole domain file with a partial one.
    const kind = selectedTargetDirectory ? selectedTargetDirectory.type : null;
    targetDirectories.forEach(target => {
        // Don't include current target in list
        if (target.path !== selectedTargetDirectory?.path && target.type === kind) {
            const option = document.createElement('option');
            option.value = target.path;
            option.textContent = target.displayName;
            select.appendChild(option);
        }
    });

    if (select.options.length === 1) {
        showSyncStatus('Nothing to clone to: a ' + (kind || 'domain') + ' file clones only to another ' + (kind || 'domain') + ' target', 'warning');
        return;
    }

    // Show modal
    document.getElementById('cloneModal').style.display = 'flex';
}

function closeCloneDialog() {
    document.getElementById('cloneModal').style.display = 'none';
}

function executeClone() {
    const select = document.getElementById('clone-target-select');
    const targetPath = select.value;

    if (!targetPath) {
        alert('Please select a destination target');
        return;
    }

    if (!websocket || websocket.readyState !== WebSocket.OPEN) {
        showSyncStatus('Not connected to the server: clone once it reconnects', 'error');
        return;
    }
    // One at a time: a second would leave the first's answer unmatched.
    if (cloneOut) { showSyncStatus('Still cloning…', 'warning'); return; }
    // The same document, and the same checks, as Save.
    const data = documentToWrite('clone');
    if (data === null) return;
    // Refused by the server if the destination exists, until cloned again.
    const sameAsLast = lastClonePath === targetPath + '/' + currentSchemaName + '.json';
    const replace = cloneOverwrite && sameAsLast;
    cloneOverwrite = false;
    lastClonePath = targetPath + '/' + currentSchemaName + '.json';

    // Construct save path for clone
    const clonePath = targetPath + '/' + currentSchemaName + '.json';

    // Send save request via WebSocket
    const jsonContent = JSON.stringify(data, null, 2);
    cloneOut = true;
    sendWebSocketMessage('save_json', {
        file: clonePath,
        content: jsonContent,
        sourceText: loadedSourceText || undefined,
        sourceFile: currentSavePath(),   // the clone's source: this form's file
        editorText: writeEditorText || undefined,
        clone: true,
        force: replace
    });

    closeCloneDialog();
    showSyncStatus('Cloning to ' + targetPath + '…', 'warning');   // save_success or a refusal says how it went
}

// ========================================
// Feature 9: Validation Indicators
// ========================================

function clearValidationErrors() {
    validationErrors.clear();
    document.querySelectorAll('.field-error').forEach(el => {
        el.classList.remove('field-error');
    });
    document.querySelectorAll('.field-error-message').forEach(el => {
        el.remove();
    });
    const summary = document.getElementById('validation-summary');
    if (summary) {
        summary.classList.remove('active');
    }
}

// Initialize the application when DOM is ready
document.addEventListener('DOMContentLoaded', function() {
    // ?domain=…&schema=… on the page URL — read once, applied as the
    // initial selection.
    const urlParams = new URLSearchParams(window.location.search);
    urlInitialDomain = urlParams.get('domain');
    urlInitialSchema = urlParams.get('schema');

    connectWebSocket();
    requestTargetDirectories(); // Request target directories (domain, clusters, servers)
    requestSchemaList(); // Request schemas from server instead of using hardcoded list

    // Initialize with empty form and editor - user will select data from dropdowns
    // Ace comes from a CDN: without it (an admin host with no internet) only the
    // JSON tab is lost; dirty tracking, drafts and the leave warning below must
    // still be wired.
    try {
        initializeJsonEditor();
    } catch (e) {
        jsonEditor = null;
        console.error('JSON editor unavailable:', e);
        const je = document.getElementById('json-editor');
        if (je) je.textContent = 'The JSON editor could not load (no access to its CDN). The form still works.';
    }

    // Feature: Initialize enhancements
    setupDirtyTracking(); // Set up unsaved changes tracking

    // Field help: hovering a label/title with a schema description (marked
    // .has-help, text in data-help) shows one shared fixed-position tooltip —
    // fixed positioning avoids overflow clipping, and keeping the tooltip out
    // of the label's DOM keeps textContent-based title matching intact.
    const helpTooltip = document.createElement('div');
    helpTooltip.className = 'field-help-tooltip';
    document.body.appendChild(helpTooltip);

    document.addEventListener('mouseenter', function(e) {
        const el = e.target.closest('.has-help');
        if (!el || !el.dataset.help) return;
        const rect = el.getBoundingClientRect();
        helpTooltip.textContent = el.dataset.help;
        helpTooltip.style.display = 'block';
        // Position above the label, centered
        const tooltipRect = helpTooltip.getBoundingClientRect();
        let left = rect.left + rect.width / 2 - tooltipRect.width / 2;
        let top = rect.top - tooltipRect.height - 8;
        // Keep within viewport
        if (left < 4) left = 4;
        if (left + tooltipRect.width > window.innerWidth - 4) left = window.innerWidth - tooltipRect.width - 4;
        if (top < 4) {
            // Show below if no room above
            top = rect.bottom + 8;
            helpTooltip.classList.add('below');
        } else {
            helpTooltip.classList.remove('below');
        }
        helpTooltip.style.left = left + 'px';
        helpTooltip.style.top = top + 'px';
    }, true);

    document.addEventListener('mouseleave', function(e) {
        if (e.target.closest && e.target.closest('.has-help')) {
            helpTooltip.style.display = 'none';
        }
    }, true);

    // Add tab click handlers using data-tab attribute
    document.querySelectorAll('.tab').forEach(tab => {
        tab.addEventListener('click', function(e) {
            const tabName = this.getAttribute('data-tab');
            if (tabName) {
                switchTab(tabName);
            }
        });
    });
});

function initializeJsonEditor() {
    jsonEditor = ace.edit("json-editor");
    jsonEditor.setTheme("ace/theme/" + currentTheme);
    jsonEditor.session.setMode("ace/mode/json");
    jsonEditor.setOptions({
        fontSize: 14,
        showPrintMargin: false,
        wrap: true,
        autoScrollEditorIntoView: true
    });

    // Set initial JSON content (empty until user selects a schema)
    jsonEditor.setValue('{\n  \n}', -1);

    // Auto-sync on content change (debounced)
    let syncTimeout;
    jsonEditor.on('change', () => {
        clearTimeout(syncTimeout);
        syncTimeout = setTimeout(() => {
            try {
                const jsonData = JSON.parse(jsonEditor.getValue());
                showSyncStatus('JSON is valid', 'success');
            } catch (e) {
                showSyncStatus('Invalid JSON: ' + e.message, 'error');
            }
        }, 500);
    });

    // Schema editor will be initialized lazily when first shown
}

/// Sets the JSON editor's text without counting it as an edit (its change
/// event would otherwise mark a mere tab switch unsaved).
let syncingEditor = false;
function setEditorText(text) {
    syncingEditor = true;
    try { jsonEditor.setValue(text, -1); } finally { syncingEditor = false; }
}

function switchTab(tabName) {
    // Mid-load the schema is the new app's while the form is still the old
    // one's: syncing the two tabs now would read one with the other.
    if (schemaLoading && (tabName === 'json' || tabName === 'form')) {
        showSyncStatus('Still loading: switch tabs once the file is in the form', 'warning');
        return;
    }
    if (tabName === 'json' && !jsonEditor) {
        showSyncStatus('The JSON editor could not load (no access to its CDN); use the form', 'warning');
        return;
    }
    try {
        const activeContent = document.querySelector('.tab-content.active');
        const previousTab = activeContent ? activeContent.id : 'form-tab';

        // Auto-sync data when switching tabs
        try {
            if (previousTab === 'form-tab' && tabName !== 'form') {
                // Leaving the form, for any tab: the JSON editor takes the form
                // (Form → Files → JSON showed, and saved, a stale copy). Not while
                // the form holds something it cannot read back faithfully.
                const formData = getFormData();
                if (readBackProblems.length) {
                    showSyncStatus('Fix this first: ' + readBackProblems[0], 'error');
                    return;
                }
                currentData = formData;
                if (jsonEditor) setEditorText(JSON.stringify(formData, null, 2));
            } else if (previousTab === 'json-tab' && tabName !== 'json') {
                // Leaving the JSON tab, for any tab: its text becomes the form,
                // or edits made there would be lost by way of Schema or Files.
                if (jsonEditor) {
                    const jsonData = JSON.parse(jsonEditor.getValue());
                    // The form draws an object; anything else would read back as {}.
                    if (jsonData === null || typeof jsonData !== 'object' || Array.isArray(jsonData)) {
                        showSyncStatus('A configuration is a JSON object { … }: fix it here first', 'error');
                        return;
                    }
                    // The text, kept as a repair source for a Save from the form:
                    // a long or a key order typed there is not in the form's copy.
                    leftEditorText = jsonEditor.getValue();
                    currentData = jsonData;
                    generateFormWithData(jsonData);
                }
            }
        } catch (e) {
            console.error('Error syncing data in switchTab:', e);
            if (previousTab === 'json-tab' && tabName !== 'json') {
                showSyncStatus('Cannot leave the JSON tab with invalid JSON: ' + e.message, 'error');
                return;
            }
        }

        // Update tab buttons
        document.querySelectorAll('.tab').forEach(tab => tab.classList.remove('active'));
        const activeTabBtn = document.querySelector(`.tab[data-tab="${tabName}"]`);
        if (activeTabBtn) {
            activeTabBtn.classList.add('active');
        }

        // Update tab content
        document.querySelectorAll('.tab-content').forEach(content => content.classList.remove('active'));
        const tabContent = document.getElementById(tabName + '-tab');
        if (tabContent) {
            tabContent.classList.add('active');
        }

        // Refresh editor layout if switching to JSON or Schema tab
        if (tabName === 'json' && jsonEditor) {
            setTimeout(() => jsonEditor.resize(), 100);
        } else if (tabName === 'schema') {
            // Initialize schema editor lazily on first show
            if (!schemaEditor) {
                initializeSchemaEditor();
            }
            if (schemaEditor) {
                setTimeout(() => schemaEditor.resize(), 100);
            }
        } else if (tabName === 'files') {
            // Lazy-init the text editor, then refresh the file list every
            // time the tab is shown so newly-added templates appear.
            initTextEditorIfNeeded();
            if (textEditor) setTimeout(() => textEditor.resize(), 100);
            refreshTextFileList();
        }
    } catch (e) {
        console.error('Error in switchTab:', e);
    }
}

function initializeSchemaEditor() {
    try {
        const schemaEditorEl = document.getElementById('schema-editor');
        if (schemaEditorEl) {
            schemaEditor = ace.edit("schema-editor");
            schemaEditor.setTheme("ace/theme/" + currentTheme);
            schemaEditor.session.setMode("ace/mode/json");
            schemaEditor.setOptions({
                fontSize: 14,
                showPrintMargin: false,
                wrap: true,
                autoScrollEditorIntoView: true,
                readOnly: true
            });
            // Set the current schema content
            if (schema && Object.keys(schema).length > 0) {
                schemaEditor.setValue(JSON.stringify(schema, null, 2), -1);
            } else {
                schemaEditor.setValue('{\n  \n}', -1);
            }
        }
    } catch (e) {
        console.error('Error initializing schema editor:', e);
    }
}

function formatJson() {
    try {
        const jsonData = JSON.parse(jsonEditor.getValue());
        jsonEditor.setValue(JSON.stringify(jsonData, null, 2), -1);
        showSyncStatus('JSON formatted successfully', 'success');
    } catch (e) {
        showSyncStatus('Cannot format invalid JSON: ' + e.message, 'error');
    }
}

function validateJson() {
    try {
        JSON.parse(jsonEditor.getValue());
        showSyncStatus('JSON is valid', 'success');
    } catch (e) {
        showSyncStatus('Invalid JSON: ' + e.message, 'error');
    }
}

/// The JSON tab's Reset: the same as the form's, both editors back to the file.
function resetJson() {
    if (!resetForm()) return;
    showSyncStatus('JSON reset to initial data', 'success');
}

function showSyncStatus(message, type) {
    showStatusIn(document.getElementById('sync-status'), message, type);
}

/// Shows `message` in a status line. Each message owns the line until the
/// next one: an earlier message's timer never cuts a later one short. A
/// success goes after 4 s; a warning stays 12 s; an error stays until the
/// next message replaces it (a conflict's "press Save again" must be read).
function showStatusIn(el, message, type) {
    if (!el) return;
    clearTimeout(el._hideTimer);
    el.textContent = message;
    el.className = 'sync-status ' + (type || '');
    el.style.display = 'inline-flex';
    const ms = type === 'success' ? 4000 : type === 'warning' ? 12000 : 0;
    if (ms) el._hideTimer = setTimeout(() => { el.style.display = 'none'; }, ms);
}

function generateFormWithData(data) {
    currentData = data;
    buildIdentityRegistry(data);
    const form = document.getElementById('dynamicForm');
    form.innerHTML = '';

    if (schema.properties) {
        const requiredSet = new Set(Array.isArray(schema.required) ? schema.required : []);
        Object.keys(schema.properties).forEach(prop => {
            const propSchema = schema.properties[prop];
            const propValue = data[prop];
            const propTitle = fieldTitle(propSchema, prop);
            const propDescription = propSchema.description;
            // Same required-flag injection as createObjectGroup.renderPropInto:
            // shallow clone so the shared schema object isn't mutated.
            const childSchema = (requiredSet.has(prop) && !onOverlayTarget())   // an overlay may leave any of them to the file below
                ? Object.assign({}, propSchema, { 'x-required-here': true })
                : propSchema;

            // If property has no value, show a placeholder with '+' icon
            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(childSchema, propTitle, propDescription, prop, form, false);
                markStatedEmpty(placeholder, propValue);
                form.appendChild(placeholder);
            } else {
                const group = createFormGroup(childSchema, propTitle, propDescription, prop, propValue, false, propSchema);
                form.appendChild(group);
            }
        });
    }
    // After a fresh render, offer to restore any locally-saved draft that
    // survives from a prior session.
    if (typeof maybeOfferDraftRestore === 'function' && schemaLoading) {
        // Only for a load: after a Restore, a tab switch or a proposal the
        // draft is the editor's own state, not something to offer back.
        setTimeout(maybeOfferDraftRestore, 0);
    }
}

// Version History Functions
function showVersionHistory() {
    if (!currentSchemaName) {
        showSyncStatus('Please select a schema first', 'warning');
        return;
    }

    const entry = { jsonFile: formFile() };
    if (!entry.jsonFile) {
        showSyncStatus('No file associated with this schema', 'warning');
        return;
    }

    // Request version list
    sendWebSocketMessage('list_versions', { file: entry.jsonFile });

    // Show modal
    const modal = document.getElementById('versionModal');
    modal.classList.add('active');

    // Show loading state
    const container = document.getElementById('versionListContainer');
    container.innerHTML = '<div style="text-align: center; padding: 20px;">Loading versions...</div>';
}

function closeVersionHistory() {
    const modal = document.getElementById('versionModal');
    modal.classList.remove('active');
    // A preview belongs to the list it came from.
    const pre = document.getElementById('versionPreview');
    if (pre) { pre.textContent = ''; pre.style.display = 'none'; }
}

// 'json' (schema-backed config) or 'text' (a _templates/ file). Controls which
// Preview/Restore handlers the rendered buttons call.
let versionHistoryContext = 'json';

function displayVersionList(versionListJson, context) {
    versionHistoryContext = context || 'json';
    const isText = versionHistoryContext === 'text';
    const previewFn = isText ? 'previewTextVersion' : 'previewVersion';
    const restoreFn = isText ? 'restoreTextVersion' : 'restoreVersionFromHistory';
    const container = document.getElementById('versionListContainer');
    const versions = JSON.parse(versionListJson);

    if (versions.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state-icon">📋</div>
                <p>No version history available yet.</p>
                <p style="font-size: 14px;">Versions will be created automatically when you save changes.</p>
            </div>
        `;
        return;
    }

    let html = '<ul class="version-list">';
    versions.forEach((version, index) => {
        const date = new Date(version.timestamp);
        const dateStr = date.toLocaleString();
        const sizeKB = (version.size / 1024).toFixed(2);
        const isRecent = index === 0;

        html += `
            <li class="version-item ${isRecent ? 'version-current' : ''}">
                <div class="version-info">
                    <div class="version-date">${dateStr} ${isRecent ? '(the content before the last save)' : ''}</div>
                    <div class="version-size">${sizeKB} KB</div>
                </div>
                <div class="version-actions">
                    <button type="button" class="btn btn-secondary btn-small"
                            onclick="${previewFn}('${version.timestamp}')">Preview</button>
                    <button type="button" class="btn btn-primary btn-small"
                            onclick="${restoreFn}('${version.timestamp}')">Restore</button>
                </div>
            </li>
        `;
    });
    html += '</ul>';

    container.innerHTML = html;
}

function restoreVersionFromHistory(timestamp) {
    if (viewModeRefuses('restore')) return;
    if (!checkUnsavedChanges()) return;
    if (!confirm('Are you sure you want to restore this version? The current version will be saved as a backup.')) {
        return;
    }

    const entry = { jsonFile: formFile() };
    if (!entry.jsonFile) return;

    pendingRequests.set('restoreTimestamp', timestamp);
    pendingRequests.set('restoreFile', entry.jsonFile);

    sendWebSocketMessage('restore_version', {
        file: entry.jsonFile,
        timestamp: timestamp
    });

    showSyncStatus('Restoring version...', 'warning');
}

function handleVersionRestored(content) {
    leftEditorText = null;
    let restoredHere = false;
    try {
        const timestamp = pendingRequests.get('restoreTimestamp');
        pendingRequests.delete('restoreTimestamp');
        const dateStr = timestamp ? new Date(Number(timestamp)).toLocaleString() : '';
        const restoredFile = pendingRequests.get('restoreFile');
        pendingRequests.delete('restoreFile');
        if (restoredFile && restoredFile !== formFile()) {
            // The operator moved to another file while it was out: that file's
            // form and draft are not this restore's.
            const app = restoredFile.split('/').pop().replace(/\.json$/, '');
            if (!autoPublishEnabled) setUnpublished(app);
            showSyncStatus('Restored ' + restoredFile + ' (' + dateStr + ')', 'success');
            return;
        }
        restoredHere = true;
        const data = JSON.parse(content);

        currentData = data;
        initialData = data;
        loadedSourceText = initialText = content;
        fileBroken = false;

        // Update both form and JSON editor
        generateFormWithData(data);
        if (jsonEditor) {
            jsonEditor.setValue(JSON.stringify(data, null, 2), -1);
        }

        // The form now equals the file on disk: clean, with no draft. The
        // restore wrote the file, so with auto-publish off it waits for Publish.
        draftBaseVersion = null;
        overwriteNext = false;   // a conflict seen before is about a file this replaced
        clearDirty();
        if (typeof clearDraft === 'function') clearDraft();
        if (!autoPublishEnabled) setUnpublished();

        showSyncStatus('Restored: ' + dateStr, 'success');
        showSchemaLoadStatus('Restored: ' + dateStr, 'success');
        closeVersionHistory();
    } catch (e) {
        if (!restoredHere) { showSyncStatus('Error restoring version: ' + e.message, 'error'); return; }
        // The file on disk is the restored one, and file_version is about to
        // name it: the old form left in place would Save over the restore.
        // Load the file again instead.
        clearDirty();
        if (typeof clearDraft === 'function') clearDraft();
        closeVersionHistory();
        showSyncStatus('Restored, but the page could not show it (' + e.message + '): loading the file again', 'warning');
        loadSchema(currentSchemaName, true);
    }
}

function previewVersion(timestamp) {
    const entry = { jsonFile: formFile() };
    if (!entry.jsonFile) return;

    pendingRequests.set('versionTimestamp', timestamp);

    sendWebSocketMessage('get_version_content', {
        file: entry.jsonFile,
        timestamp: timestamp
    });

    showSyncStatus('Loading version preview...', 'warning');
}

function handleVersionContent(raw) {
    let reply;
    try { reply = JSON.parse(raw); } catch (e) { showSyncStatus('Error loading preview: ' + e.message, 'error'); return; }
    const timestamp = pendingRequests.get('versionTimestamp');
    // Asked for another file, or another version, since: not this preview.
    if (reply.file !== formFile() || String(reply.timestamp) !== String(timestamp)) return;
    pendingRequests.delete('versionTimestamp');
    const dateStr = timestamp ? new Date(Number(timestamp)).toLocaleString() : '';
    // Shown in the dialog, read-only, as the file has it (JSON.parse would
    // round long integers and reorder numbered keys, and refuse a broken one).
    // Loading it into the editor would overwrite the edits in progress.
    const pre = document.getElementById('versionPreview');
    if (pre) {
        pre.textContent = 'Version of ' + dateStr + ' (read-only; Restore to load it):\n\n' + (reply.content || '');
        pre.style.display = 'block';
    }
}

// Close modal when clicking outside
document.addEventListener('click', function(event) {
    const modal = document.getElementById('versionModal');
    if (event.target === modal) {
        closeVersionHistory();
    }
});

/// The Form tab's round-trip check: loads every schema's file (and, with
/// `{ samples: true }`, its sample), reads the form back with getFormData()
/// and compares it with what was loaded. A Save from the Form tab writes
/// exactly that read-back, so any difference is a value Save would change
/// without the operator touching it. Run from the browser console:
///
/// ```js
/// await configuratorRoundTripCheck()                  // {schema: 'same' | [differences]}
/// await configuratorRoundTripCheck({ samples: true })
/// ```
///
/// Read-only: it loads files into the editor and saves nothing. It leaves
/// the last schema loaded.
async function configuratorRoundTripCheck(opts = {}) {
    const canon = v => Array.isArray(v) ? v.map(canon)
        : (v && typeof v === 'object') ? Object.keys(v).sort().reduce((o, k) => (o[k] = canon(v[k]), o), {}) : v;
    const diffs = (a, b, p = '') => {
        if (JSON.stringify(canon(a)) === JSON.stringify(canon(b))) return [];
        if (a && b && typeof a === 'object' && typeof b === 'object' && Array.isArray(a) === Array.isArray(b)) {
            const keys = Array.isArray(a) ? [...Array(Math.max(a.length, b.length)).keys()]
                : [...new Set([...Object.keys(a), ...Object.keys(b)])];
            return keys.flatMap(k => diffs(a[k], b[k], p + (Array.isArray(a) ? `[${k}]` : `.${k}`)));
        }
        return [`${p}: loaded ${JSON.stringify(a)} · read back ${JSON.stringify(b)}`];
    };
    const wait = async (test, tries) => { for (let i = 0; i < tries && !test(); i++) await new Promise(r => setTimeout(r, 250)); };
    const names = [...document.getElementById('schema-select').options].map(o => o.value).filter(Boolean);
    const results = {};
    for (const name of names) {
        clearDirty();
        currentData = null;
        loadSchema(name);
        await wait(() => currentSchemaName === name && currentData, 40);
        if (opts.samples) {
            const file = currentData;
            currentData = null;
            loadSample();
            await wait(() => currentData && currentData !== file, 24);
        }
        await new Promise(r => setTimeout(r, 400));
        const d = currentData ? diffs(JSON.parse(JSON.stringify(currentData)), getFormData()) : ['did not load'];
        results[name] = d.length ? d : 'same';
    }
    clearDirty();
    return results;
}


// ---------------------------------------------------------------------------
// Dialogs: Esc closes the one open (before anything behind it sees the key),
// focus moves into it when it opens, Tab stays inside it, and focus goes back
// to where it was when it closes. Every .modal in the page, however it is
// opened (a class or a style).
(function dialogs() {
    if (typeof document === 'undefined') return;
    const isOpen = m => getComputedStyle(m).display !== 'none';
    const focusables = m => [...m.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')]
        .filter(el => !el.disabled && el.offsetParent !== null);
    let lastFocus = null;
    document.addEventListener('focusin', e => { if (!e.target.closest || !e.target.closest('.modal')) lastFocus = e.target; });
    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('.modal').forEach(m => {
            const box = m.querySelector('.modal-content') || m;
            box.setAttribute('role', 'dialog');
            box.setAttribute('aria-modal', 'true');
            const h = m.querySelector('.modal-header h2, .modal-header h3');
            if (h) { if (!h.id) h.id = m.id + '-title'; box.setAttribute('aria-labelledby', h.id); }
            const x = m.querySelector('.modal-close');
            if (x) x.setAttribute('aria-label', 'Close');
            let was = false, back = null;
            new MutationObserver(() => {
                const open = isOpen(m);
                if (open && !was) {
                    // A menu item closes its menu before the dialog opens, so
                    // focus is already on the page body: the last real focus.
                    back = (document.activeElement && document.activeElement !== document.body) ? document.activeElement : lastFocus;
                    setTimeout(() => { const f = focusables(m); (f.find(el => !el.classList.contains('modal-close')) || f[0] || box).focus(); }, 0);
                } else if (!open && was && back && document.contains(back)) {
                    // Only if nothing else took focus (jump-to-field focuses
                    // its field), and to the menu's button when the item that
                    // opened it is in a closed menu.
                    const a = document.activeElement;
                    if (a && a !== document.body && !m.contains(a)) { was = open; return; }
                    let to = back;
                    if (to.offsetParent === null) {
                        const menu = to.closest('.toolbar-menu');
                        to = menu ? menu.querySelector('.toolbar-menu-trigger') : null;
                    }
                    if (to) to.focus();
                }
                was = open;
            }).observe(m, { attributes: true, attributeFilter: ['class', 'style'] });
        });
    });
    document.addEventListener('keydown', (e) => {
        const m = [...document.querySelectorAll('.modal')].reverse().find(isOpen);
        if (!m) return;
        if (e.key === 'Escape') {
            e.preventDefault();
            e.stopPropagation();   // not the form filter behind it
            const x = m.querySelector('.modal-close');
            if (x) x.click();
        } else if (e.key === 'Tab') {
            const f = focusables(m);
            if (!f.length) return;
            const first = f[0], last = f[f.length - 1];
            if (!m.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
            else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        }
    }, true);
})();
