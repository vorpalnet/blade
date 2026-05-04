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
let currentTheme = localStorage.getItem('blade-configurator-ace-theme') || 'eclipse';
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


// Feature: Validation
let validationErrors = new Map();

// Feature: Jackson Identity References
// Maps id strings to {path, object} for objects with an "id" field
let identityRegistry = new Map();

// WebSocket Connection Management
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    // Get the context path from the current location
    const contextPath = window.location.pathname.substring(0, window.location.pathname.indexOf('/', 1));
    const wsUrl = protocol + '//' + window.location.host + contextPath + '/websocket';

    updateWebSocketStatus('Connecting...', 'warning');

    websocket = new WebSocket(wsUrl);

    websocket.onopen = function() {
        updateWebSocketStatus('Connected', 'success');
        reconnectAttempts = 0; // Reset on successful connection
        lastPongTime = Date.now();
        startHeartbeat(); // Start sending pings
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

        case 'save_success':
            showSyncStatus('Data saved successfully', 'success');
            showSchemaLoadStatus('Saved', 'success');
            clearDirty(); // Feature: Clear dirty state after successful save
            if (typeof clearDraft === 'function') clearDraft();
            break;

        case 'template_list':
            handleTemplateList(message.content);
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

        case 'reload_success':
            showSyncStatus(message.content, 'success');
            showSchemaLoadStatus('Published', 'success');
            break;

        case 'version_list':
            displayVersionList(message.content);
            break;

        case 'version_restored':
            handleVersionRestored(message.content);
            break;

        case 'version_content':
            handleVersionContent(message.content);
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

        case 'error':
            showSchemaLoadStatus('Error: ' + message.content, 'error');
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

            // Set timeout to expect pong response
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

        // Auto-select domain if available
        const domain = targetDirectories.find(t => t.type === 'domain');
        if (domain) {
            document.getElementById('target-directory-select').value = domain.path;
            onTargetDirectoryChange(domain.path);
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
    if (!path) {
        selectedTargetDirectory = null;
        return;
    }

    selectedTargetDirectory = targetDirectories.find(t => t.path === path);
    showSchemaLoadStatus('Target: ' + (selectedTargetDirectory ? selectedTargetDirectory.displayName : 'None'), 'success');

    // Reload current schema's JSON file from the new target
    if (currentSchemaName && selectedTargetDirectory) {
        loadSchema(currentSchemaName);
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
    } catch (e) {
        console.error('Error parsing schemas list:', e);
        showSchemaLoadStatus('Error loading schema list', 'error');
    }
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
function loadSchema(schemaName) {
    if (!schemaName) return;

    // Feature: Check for unsaved changes before loading new schema
    if (!checkUnsavedChanges()) return;

    if (!selectedTargetDirectory) {
        showSchemaLoadStatus('Please select a target directory first', 'error');
        return;
    }

    const entry = schemaRegistry.find(e => e.name === schemaName);
    if (!entry) {
        showSchemaLoadStatus('Schema not found', 'error');
        return;
    }

    showSchemaLoadStatus('Loading schema...', 'warning');
    currentSchemaName = schemaName;

    // Store the entry in a pending request
    pendingRequests.set('schema', entry);

    // Request the schema from the filesystem
    sendWebSocketMessage('load_schema', { appName: entry.appName });

    // Request JSON file resolution based on selected target
    sendWebSocketMessage('resolve_json_file', {
        schemaName: schemaName,
        targetDirectory: selectedTargetDirectory.path
    });
}

function handleJsonFileResolved(content) {
    try {
        const resolution = JSON.parse(content);

        const entry = pendingRequests.get('schema');
        if (entry) {
            // Update entry with resolved JSON file info
            entry.jsonFile = resolution.jsonFile;
            entry.jsonFileType = resolution.jsonFileType;
            pendingRequests.set('schema', entry);

            if (resolution.exists) {
                if (resolution.jsonFileType === 'sample') {
                    // Load sample from filesystem
                    sendWebSocketMessage('load_sample', { appName: resolution.appName });
                } else {
                    // Load primary config from filesystem
                    sendWebSocketMessage('load_json', { file: resolution.jsonFile });
                }
            } else {
                finalizeSchemaLoad({});
            }
        }
    } catch (e) {
        console.error('Error processing JSON file resolution:', e);
        finalizeSchemaLoad({});
    }
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
        pendingRequests.delete('schema');
    }
}

function handleJsonLoaded(content) {
    try {
        let newData = {};
        if (content && content.trim() !== '') {
            newData = JSON.parse(content);
        }

        finalizeSchemaLoad(newData);

    } catch (error) {
        console.error('Error processing JSON data:', error);
        showSchemaLoadStatus('Warning: Could not parse JSON data', 'warning');
        // Continue with empty data
        finalizeSchemaLoad({});
    }
}

function handleSampleLoaded(content) {
    try {
        let sampleData = {};
        if (content && content.trim() !== '') {
            sampleData = JSON.parse(content);
            showSyncStatus('Loaded sample data (will save to primary location)', 'warning');
        }
        finalizeSchemaLoad(sampleData);
    } catch (error) {
        console.error('Error processing sample data:', error);
        showSchemaLoadStatus('Warning: Could not parse sample data', 'warning');
        finalizeSchemaLoad({});
    }
}

function loadSample() {
    if (!currentSchemaName) {
        showSchemaLoadStatus('Please select a schema first', 'error');
        return;
    }
    showSchemaLoadStatus('Loading sample...', 'warning');
    sendWebSocketMessage('load_sample', { appName: currentSchemaName });
}

function finalizeSchemaLoad(newData) {
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

    // Feature: Clear dirty state and validation errors when loading new data
    clearDirty();
    clearValidationErrors();

    // Clean up pending request
    pendingRequests.delete('schema');
}

function showSchemaLoadStatus(message, type) {
    const statusEl = document.getElementById('schema-load-status');
    statusEl.textContent = message;
    statusEl.className = `sync-status ${type}`;
    statusEl.style.display = 'inline-flex';

    // Auto-hide success messages after 3 seconds
    if (type === 'success') {
        setTimeout(() => {
            statusEl.style.display = 'none';
        }, 3000);
    }
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
/// already carry an encrypted prefix ({AES}, {3DES}) or have been marked
/// {CLEARTEXT} already are passed through unchanged.
function readInputValue(input) {
    if (!input) return null;
    if (input.type === 'checkbox') return input.checked;
    if (input.type === 'number') return input.value ? parseInt(input.value, 10) : null;
    let v = input.value;
    if (v == null || v === '') return null;
    if (input.dataset && input.dataset.password === 'true') {
        if (!v.startsWith('{CLEARTEXT}') && !v.startsWith('{AES}') && !v.startsWith('{3DES}')) {
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
    };
    input.addEventListener('input', validate);
    input.addEventListener('blur', validate);
    validate();
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

// Returns true if the given items schema could accept a Jackson identity
// reference (a bare id string). True when at least one resolved variant is
// an object with an `id` property — the same predicate isIdentityReference
// uses to decide on render.
function arrayItemsAcceptReferences(itemsSchema) {
    if (!itemsSchema) return false;
    let s = itemsSchema;
    if (s.$ref) s = deref(s);
    if (!s) return false;
    const variants = polymorphicVariants(s);
    if (variants) {
        return variants.some(v => {
            const r = v.$ref ? deref(v) : v;
            return r && r.type === 'object' && r.properties && r.properties.id;
        });
    }
    return s.type === 'object' && s.properties && s.properties.id;
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
        return variants.some(v => v.type === 'object' && v.properties && v.properties.id);
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
    const target = document.getElementById('identity-' + CSS.escape(id));
    if (target) {
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
        const input = document.createElement('input');
        input.type = 'text';
        const keyVal = value || '';
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

        return select;
    }

    switch (fieldSchema.type) {
        case 'boolean':
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.id = id;
            checkbox.checked = value === true;
            checkbox.setAttribute('data-path', path);
            checkbox._schema = fieldSchema;
            return checkbox;

        case 'integer':
            const numberInput = document.createElement('input');
            numberInput.type = 'number';
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
            if (fieldSchema.format === 'textarea') {
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
                    textInput.type = textInput.type === 'password' ? 'text' : 'password';
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

function createFormGroup(fieldSchema, title, description, path, value = null, isNested = false, originalSchema = null) {
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

        // Required-field asterisk. The parent schema sets `required: [propName]`;
        // it's passed via originalSchema['x-required-here'] by createObjectGroup
        // (see that function for the injection point).
        if (fieldSchema['x-required-here']) {
            const star = document.createElement('span');
            star.className = 'required-marker';
            star.textContent = '*';
            label.appendChild(star);
        }

        // Feature: Add help icon with tooltip if description exists
        if (description) {
            const helpIcon = document.createElement('span');
            helpIcon.className = 'field-help-icon';
            helpIcon.innerHTML = '?';

            const tooltip = document.createElement('div');
            tooltip.className = 'field-help-tooltip';
            tooltip.textContent = description;
            helpIcon.appendChild(tooltip);

            label.appendChild(helpIcon);
        }

        headerRow.appendChild(label);

        // Add delete button
        const deleteBtn = document.createElement('button');
        deleteBtn.type = 'button';
        deleteBtn.className = 'delete-property-btn';
        deleteBtn.innerHTML = '<span class="delete-property-icon">-</span>';
        deleteBtn.title = `Remove ${title}`;
        deleteBtn.onclick = (e) => {
            e.stopPropagation();
            // Replace with placeholder
            const placeholder = createAddPropertyPlaceholder(schemaForDelete, title, description, path, null, isNested);
            group.replaceWith(placeholder);
            setDirty();
        };
        headerRow.appendChild(deleteBtn);

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
            headerRow.removeChild(deleteBtn);
            checkboxGroup.appendChild(deleteBtn);
            // Remove empty header row
            group.removeChild(headerRow);
            group.appendChild(checkboxGroup);
        } else {
            group.appendChild(element);
        }

        return group;
    }
}

/// Update the small grey class-name label on a collapsible header.
/// Pass the FQN (e.g. "org.vorpal.blade.framework.v3.RouterConfiguration").
/// Visible text is the simple name; tooltip is the FQN. Falsy fqn hides the label.
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
    titleText.textContent = title;
    titleContainer.appendChild(titleText);

    header.appendChild(titleContainer);

    const badgeContainer = document.createElement('div');
    badgeContainer.style.display = 'flex';
    badgeContainer.style.gap = '8px';
    badgeContainer.style.alignItems = 'center';

    if (description) {
        const helpIcon = document.createElement('span');
        helpIcon.className = 'field-help-icon';
        helpIcon.innerHTML = '?';
        const tooltip = document.createElement('div');
        tooltip.className = 'field-help-tooltip';
        tooltip.textContent = description;
        helpIcon.appendChild(tooltip);
        titleContainer.appendChild(helpIcon);
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
        deleteBtn.innerHTML = '<span class="delete-property-icon">-</span>';
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

    return section;
}

function toggleCollapse(header, content, arrow) {
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
    if (typeof value === 'string' && value.trim() === '') return false;
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

    // Use schema default if provided
    if (fieldSchema.default !== undefined) {
        return JSON.parse(JSON.stringify(fieldSchema.default));
    }

    // Pinned value via const (e.g. discriminator: {const: "json"})
    if (fieldSchema.const !== undefined) {
        return JSON.parse(JSON.stringify(fieldSchema.const));
    }

    // Generate based on type
    if (fieldSchema.enum && fieldSchema.enum.length > 0) {
        return fieldSchema.enum[0];
    }

    switch (fieldSchema.type) {
        case 'string':
            return '';
        case 'integer':
        case 'number':
            return 0;
        case 'boolean':
            return false;
        case 'array':
            return [];
        case 'object':
            if (fieldSchema.additionalProperties) {
                // Map type - return empty object
                return {};
            } else if (fieldSchema.properties) {
                // Object with defined properties - generate defaults for each property
                const result = {};
                Object.keys(fieldSchema.properties).forEach(prop => {
                    const propDefault = generateDefaultValue(fieldSchema.properties[prop]);
                    if (propDefault !== null) {
                        result[prop] = propDefault;
                    }
                });
                return result;
            }
            return {};
        default:
            return null;
    }
}

function createAddPropertyPlaceholder(fieldSchema, title, description, path, container, isNested = false) {
    const placeholder = document.createElement('div');
    placeholder.className = `add-property-placeholder${isNested ? ' nested' : ''}`;

    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'add-property-btn';
    addBtn.innerHTML = '<span class="add-property-icon">+</span>';
    addBtn.title = `Add ${title}`;

    const label = document.createElement('span');
    label.className = 'add-property-label';
    label.textContent = title || 'Field';

    // Add help icon with tooltip if description exists
    if (description) {
        const helpIcon = document.createElement('span');
        helpIcon.className = 'field-help-icon';
        helpIcon.innerHTML = '?';

        const tooltip = document.createElement('div');
        tooltip.className = 'field-help-tooltip';
        tooltip.textContent = description;
        helpIcon.appendChild(tooltip);

        label.appendChild(helpIcon);
    }

    placeholder.appendChild(addBtn);
    placeholder.appendChild(label);

    addBtn.onclick = () => {
        // Generate default value and create the actual form element
        const defaultValue = generateDefaultValue(fieldSchema);
        const propGroup = createFormGroup(fieldSchema, title, description, path, defaultValue, isNested);

        // Replace placeholder with actual form group
        placeholder.replaceWith(propGroup);

        // Mark form as dirty since we're adding content
        setDirty();

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

        Object.keys(vs.properties).forEach(prop => {
            if (prop === 'type') return; // handled by hiddenType
            const propSchema = vs.properties[prop];
            const propPath = path ? `${path}.${prop}` : prop;
            const propValue = variantValue && variantValue[prop] !== undefined ? variantValue[prop] : null;
            const propTitle = propSchema.title || prop;
            const propDescription = propSchema.description;
            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(propSchema, propTitle, propDescription, propPath, content, true);
                content.appendChild(placeholder);
            } else {
                const propGroup = createFormGroup(propSchema, propTitle, propDescription, propPath, propValue, true, propSchema);
                content.appendChild(propGroup);
            }
        });
    }

    typeSelect.onchange = () => {
        const newIdx = parseInt(typeSelect.value, 10);
        renderVariant(newIdx, generateDefaultValue(variants[newIdx]));
        setDirty();
    };

    renderVariant(initialIdx, value);

    const hasData = hasValue(value);
    const autoCollapse = !hasData;
    const deleteInfo = {
        schema: schemaForDelete || fieldSchema,
        title: title,
        description: description,
        path: path,
        isNested: isNested
    };
    const section = createCollapsibleSection(title || 'Object', description, content, hasData, autoCollapse, deleteInfo, variantJavaClass(initialIdx));
    javaClassLabelRef = section.querySelector('.collapsible-java-class');
    return section;
}

function createObjectGroup(fieldSchema, title, description, path, value = null, isNested = false, originalSchema = null) {
    const schemaForDelete = originalSchema || fieldSchema;
    const content = document.createElement('div');
    content.className = 'object-content';

    const hasData = hasValue(value);
    const autoCollapse = !hasData;

    if (fieldSchema.properties) {
        const requiredSet = new Set(Array.isArray(fieldSchema.required) ? fieldSchema.required : []);
        const sections = Array.isArray(fieldSchema['x-form-sections']) ? fieldSchema['x-form-sections'] : [];
        const groups = Array.isArray(fieldSchema['x-form-groups']) ? fieldSchema['x-form-groups'] : [];

        // Fields claimed by any @FormSection render inside that section's
        // bordered container; everything else renders at the top level.
        const fieldsInSections = new Set();
        sections.forEach(s => (s.fields || []).forEach(f => fieldsInSections.add(f)));

        // Fields claimed by any @FormLayoutGroup render as a horizontal row;
        // ungrouped fields render on their own line (full-width).
        const groupOf = new Map();
        groups.forEach((row, i) => {
            (row || []).forEach(f => groupOf.set(f, i));
        });

        const renderPropInto = (container, prop, groupIndex) => {
            const propSchema = fieldSchema.properties[prop];
            if (!propSchema) return;
            const propPath = path ? `${path}.${prop}` : prop;
            const propValue = value && value[prop] !== undefined ? value[prop] : null;
            const propTitle = propSchema.title || prop;
            let propDescription = propSchema.description;
            if (!propDescription && prop === 'type' && propSchema.enum && propSchema.enum.length === 1) {
                propDescription = 'Map type: ' + propSchema.enum[0];
            }
            // Inject required marker via a shallow-cloned schema so we don't
            // mutate the shared $defs schema.
            const childSchema = requiredSet.has(prop)
                ? Object.assign({}, propSchema, { 'x-required-here': true })
                : propSchema;
            // Fields in a @FormLayoutGroup row flow L-to-R and share the
            // remaining width; standalone fields take a full row.
            const target = container;
            const wideTarget = groupIndex == null;
            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(childSchema, propTitle, propDescription, propPath, target, true);
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
                groupsHere.get(gi).forEach(pp => renderPropInto(row, pp, gi));
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
    const deleteInfo = {
        schema: schemaForDelete,
        title: title,
        description: description,
        path: path,
        isNested: isNested
    };

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
    const autoCollapse = !hasData;

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

    const deleteInfo = {
        schema: schemaForDelete,
        title: title,
        description: description,
        path: path,
        isNested: isNested
    };

    return createCollapsibleSection(title || 'Object', description, content, hasData, autoCollapse, deleteInfo);
}

function createMapGroup(fieldSchema, title, description, path, value = null, isNested = false, originalSchema = null) {
    const schemaForDelete = originalSchema || fieldSchema;
    const content = document.createElement('div');

    const hasData = hasValue(value);
    const autoCollapse = !hasData;

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
        addMapEntry(container, fieldSchema.additionalProperties, path, '', null, true);
        updateSectionStatus();
    };
    header.appendChild(addBtn);

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
            addMapEntry(container, fieldSchema.additionalProperties, path, key, value[key]);
        });
    }

    // Create deleteInfo for the collapsible section
    const deleteInfo = {
        schema: schemaForDelete,
        title: title,
        description: description,
        path: path,
        isNested: isNested
    };

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
    const autoCollapse = !hasData;

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
        ? polymorphicVariants(fieldSchema.items)
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
    const deleteInfo = {
        schema: schemaForDelete,
        title: title,
        description: description,
        path: path,
        isNested: isNested
    };

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
    removeBtn.innerHTML = '<span class="delete-property-icon">-</span>';
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

function addMapEntry(container, valueSchema, basePath, key = '', value = null, prepend = false) {
    const hint = container.querySelector(':scope > .empty-state-hint');
    if (hint) hint.remove();
    const entry = document.createElement('div');
    entry.className = 'map-entry';

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

    // Key input directly in header
    const keyInput = createFormElement({ type: 'string' }, '', key, true);
    keyInput.placeholder = 'Key';
    header.appendChild(keyInput);

    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'delete-property-btn';
    removeBtn.innerHTML = '<span class="delete-property-icon">-</span>';
    removeBtn.title = 'Remove entry';
    removeBtn.onclick = () => {
        entry.remove();
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

    if (valueSchema.$ref) {
        valueSchema = deref(valueSchema);
    }

    if (valueSchema.oneOf || valueSchema.anyOf) {
        valueSchema = resolveOneOf(valueSchema, value);
        if (valueSchema && valueSchema.$ref) {
            valueSchema = deref(valueSchema);
        }
    }

    if (valueSchema.type === 'object' && valueSchema.properties) {
        // Render object properties directly without a collapsible wrapper
        Object.keys(valueSchema.properties).forEach(prop => {
            const propSchema = valueSchema.properties[prop];
            const propPath = valuePath ? `${valuePath}.${prop}` : prop;
            const propValue = value && value[prop] !== undefined ? value[prop] : null;
            const propTitle = propSchema.title || prop;
            const propDescription = propSchema.description;

            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(propSchema, propTitle, propDescription, propPath, entryContent, true);
                entryContent.appendChild(placeholder);
            } else {
                const propGroup = createFormGroup(propSchema, propTitle, propDescription, propPath, propValue, true, propSchema);
                entryContent.appendChild(propGroup);
            }
        });
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
    } else if (valueSchema.type === 'array') {
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
        if (e.target.closest('input, button')) return;
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

    if (prepend && container.firstChild) {
        container.insertBefore(entry, container.firstChild);
    } else {
        container.appendChild(entry);
    }

    // Update parent heights after adding
    setTimeout(() => {
        recalculateAllAncestorHeights(container.closest('.collapsible-section'));
    }, 10);
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
        removeBtn.innerHTML = '<span class="delete-property-icon">-</span>';
        removeBtn.title = 'Remove item';
        removeBtn.onclick = (e) => {
            e.stopPropagation();
            item.remove();
            updateParentSectionStatus(container);
            setTimeout(() => {
                recalculateAllAncestorHeights(container.closest('.collapsible-section'));
            }, 10);
        };
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
    removeBtn.innerHTML = '<span class="delete-property-icon">-</span>';
    removeBtn.title = 'Remove item';
    removeBtn.onclick = (e) => {
        e.stopPropagation();
        item.remove();
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
        const idx = Array.from(container.children).filter(c => c.classList.contains('array-item')).indexOf(item);
        if (idx < 0) return;
        const all = getFormData();
        const arr = resolveJsonPath(all, basePath);
        if (!Array.isArray(arr) || idx >= arr.length) return;
        const clone = JSON.parse(JSON.stringify(arr[idx]));
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
                setDirty();
            }
        });
        header.appendChild(dragHandle);

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

        Object.keys(itemSchema.properties).forEach(prop => {
            const propSchema = itemSchema.properties[prop];
            const propPath = itemPath ? `${itemPath}.${prop}` : prop;
            const propValue = value && value[prop] !== undefined ? value[prop] : null;
            const propTitle = propSchema.title || prop;
            const propDescription = propSchema.description;

            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(propSchema, propTitle, propDescription, propPath, body, true);
                body.appendChild(placeholder);
            } else {
                const propGroup = createFormGroup(propSchema, propTitle, propDescription, propPath, propValue, true, propSchema);
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

function getFormData() {
    const form = document.getElementById('dynamicForm');

    // Recursively extract data from a container element
    function extractData(container, schemaNode, debugPath = '') {
        if (!schemaNode) return null;

        // Deref handles both bare $ref and the victools $ref+properties idiom.
        // The merged schema is what we walk for property discovery — without
        // this, polymorphic subtype wrappers would lose their base type's
        // properties (id, description, map, etc.) AND the const discriminator.
        if (schemaNode.$ref) {
            schemaNode = deref(schemaNode);
            if (!schemaNode) return null;
        }

        // Resolve polymorphic schemas (anyOf/oneOf) using the actual type
        // discriminator value from the form's hidden input. This preserves
        // the correct variant (e.g. "hash" vs "prefix") during extraction.
        // Use :scope > to find only direct-child type inputs, not nested ones.
        if (schemaNode.oneOf || schemaNode.anyOf) {
            // Schema/data-mismatch fallback (createFormGroup, addArrayItem):
            // we rendered an opaque textarea instead of a polymorphic form.
            // Parse the textarea directly so the value round-trips intact.
            const opaqueTa = container.querySelector(
                ':scope > .opaque-object-input, :scope > div > .opaque-object-input, :scope > .collapsible-content > .opaque-object-input');
            if (opaqueTa) {
                const txt = opaqueTa.value.trim();
                if (txt === '' || txt === '{}') return null;
                try { return JSON.parse(txt); }
                catch (e) {
                    console.warn('extractData: invalid JSON in opaque polymorphic at ' + debugPath + ': ' + e.message);
                    return null;
                }
            }
            const typeInput = container.querySelector(':scope > input[type="hidden"][data-path$=".type"], :scope > [style*="display: none"] > input[data-path$=".type"], :scope > div > input[type="hidden"][data-path$=".type"]');
            const typeVal = typeInput ? typeInput.value : null;
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
                        console.warn('extractData: invalid JSON in opaque object at ' +
                            debugPath + ': ' + e.message);
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
                const mapContainer = container.querySelector(':scope > .map-container, :scope > div > .map-container, :scope > .collapsible-section > .collapsible-content > .map-container');
                if (!mapContainer) return null;

                const result = {};
                const entries = Array.from(mapContainer.children).filter(el => el.classList.contains('map-entry'));

                entries.forEach((entry) => {
                    const keyInput = entry.querySelector('.map-key-input');
                    const key = keyInput ? keyInput.value.trim() : '';
                    if (key) {
                        const valueData = extractData(entry, schemaNode.additionalProperties, `${debugPath}.${key}`);
                        if (valueData !== null && (typeof valueData !== 'object' || Object.keys(valueData).length > 0)) {
                            result[key] = valueData;
                        }
                    }
                });

                return Object.keys(result).length > 0 ? result : null;
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
                orderedPropertyNames(schemaNode.properties).forEach(propName => {
                    let propSchema = schemaNode.properties[propName];
                    const propTitle = propSchema.title || propName;

                    if (propSchema.const !== undefined) {
                        result[propName] = propSchema.const;
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
                        const sections = searchContainer.querySelectorAll(
                            ':scope > .collapsible-section, ' +
                            ':scope > div > .collapsible-section, ' +
                            ':scope > .form-layout-group > .collapsible-section, ' +
                            ':scope > .form-section > .form-section-body > .collapsible-section, ' +
                            ':scope > .form-section > .form-section-body > .form-layout-group > .collapsible-section');
                        sections.forEach(section => {
                            const header = section.querySelector('.collapsible-header .collapsible-title > span:not(.field-help-icon):not(.collapsible-arrow)');
                            if (!header) return;
                            const headerText = (header.textContent || '').trim();
                            // Match either the schema-driven title or the bare
                            // property name (some sections render with the
                            // propName when no title is set in the schema).
                            if (headerText === propTitle || headerText === propName) {
                                targetContainer = section.querySelector('.collapsible-content');
                            }
                        });

                        if (targetContainer) {
                            const propData = extractData(targetContainer, propSchema, `${debugPath}.${propName}`);
                            if (propData !== null && (typeof propData !== 'object' || Object.keys(propData).length > 0 || Array.isArray(propData))) {
                                result[propName] = propData;
                            }
                        }
                    } else {
                        // Primitive types - look for form-group with matching label
                        // Note: form-groups might be inside a wrapper div due to createObjectGroup structure
                        const formGroups = searchContainer.querySelectorAll(
                            ':scope > .form-group, :scope > .checkbox-group, ' +
                            ':scope > div > .form-group, :scope > div > .checkbox-group, ' +
                            ':scope > .form-layout-group > .form-group, :scope > .form-layout-group > .checkbox-group, ' +
                            ':scope > .form-section > .form-section-body > .form-group, ' +
                            ':scope > .form-section > .form-section-body > .checkbox-group, ' +
                            ':scope > .form-section > .form-section-body > .form-layout-group > .form-group, ' +
                            ':scope > .form-section > .form-section-body > .form-layout-group > .checkbox-group');
                        let foundInput = null;

                        formGroups.forEach(group => {
                            const label = group.querySelector('label');
                            // Check if label text matches (label might contain help icon, so check first text node or textContent)
                            if (label) {
                                let labelText = label.childNodes[0]?.textContent?.trim() || label.textContent?.trim();
                                if (labelText === propTitle) {
                                    foundInput = group.querySelector('input, select, textarea');
                                }
                            }
                        });

                        if (foundInput) {
                            const value = readInputValue(foundInput);
                            if (value !== null && value !== '') {
                                result[propName] = value;
                            }
                        }
                    }
                });

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
                            const key = keyInput.value.trim();
                            if (!key || defined.has(key)) return;
                            const v = readInputValue(valueInput);
                            if (v !== null && v !== '') result[key] = v;
                        });
                    }
                }

                return Object.keys(result).length > 0 ? result : null;
            }
        } else if (schemaNode.type === 'array') {
            // Find array container
            // Note: array-container might be inside wrapper divs due to createCollapsibleSection/createArrayGroup structure
            const arrayContainer = container.querySelector(':scope > .array-container, :scope > div > .array-container, :scope > .form-group.array > .array-container, :scope > div > .form-group.array > .array-container');
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
                                console.warn('extractData: invalid JSON in opaque array item at ' +
                                    debugPath + '[' + idx + ']: ' + e.message);
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
                    const typeInput = item.querySelector(
                        ':scope > input[data-path$=".type"], ' +
                        ':scope > [style*="display: none"] > input[data-path$=".type"], ' +
                        ':scope > div > input[data-path$=".type"], ' +
                        ':scope > .array-item-body > input[data-path$=".type"], ' +
                        ':scope > .array-item-body > [style*="display: none"] > input[data-path$=".type"], ' +
                        ':scope > .array-item-body > div > input[data-path$=".type"]');
                    const typeVal = typeInput ? typeInput.value : null;
                    itemSchemaNode = resolveOneOf(itemSchemaNode, typeVal ? { type: typeVal } : null);
                }
                const itemData = extractData(item, itemSchemaNode, `${debugPath}[${idx}]`);
                if (itemData !== null) {
                    result.push(itemData);
                }
            });

            return result.length > 0 ? result : null;
        } else {
            // Primitive type - find the input
            const input = container.querySelector('input:not(.map-key-input), select, textarea');
            if (!input) return null;

            const value = readInputValue(input);
            return (value !== null && value !== '') ? value : null;
        }

        return null;
    }

    // Start extraction from the form using the schema
    const result = {};

    if (schema && schema.properties) {
        orderedPropertyNames(schema.properties).forEach(propName => {
            let propSchema = schema.properties[propName];
            const propTitle = propSchema.title || propName;

            // const-only top-level property — emit straight from the schema.
            if (propSchema.const !== undefined) {
                result[propName] = propSchema.const;
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
                    const header = section.querySelector('.collapsible-header .collapsible-title > span:not(.field-help-icon):not(.collapsible-arrow)');
                    if (!header) return;
                    const headerText = (header.textContent || '').trim();
                    // Match either the schema-driven title or the bare propName.
                    if (headerText === propTitle || headerText === propName) {
                        targetSection = section;
                    }
                });

                if (targetSection) {
                    const content = targetSection.querySelector('.collapsible-content');
                    if (content) {
                        const propData = extractData(content, propSchema, propName);
                        if (propData !== null && (typeof propData !== 'object' || Object.keys(propData).length > 0 || Array.isArray(propData))) {
                            result[propName] = propData;
                        }
                    }
                }
            } else {
                // Primitive types - look for form-group with matching label
                const formGroups = form.querySelectorAll(':scope > .form-group');
                let foundInput = null;

                formGroups.forEach(group => {
                    const label = group.querySelector('label');
                    if (label) {
                        let labelText = label.childNodes[0]?.textContent?.trim() || label.textContent?.trim();
                        if (labelText === propTitle) {
                            foundInput = group.querySelector('input, select, textarea');
                        }
                    }
                });

                if (foundInput) {
                    const value = readInputValue(foundInput);
                    if (value !== null && value !== '') {
                        result[propName] = value;
                    }
                }
            }
        });
    }

    return result;
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

function saveData() {
    let data;
    const activeTab = document.querySelector('.tab-content.active').id;

    // Get data from active tab
    if (activeTab === 'form-tab') {
        data = getFormData();
    } else {
        try {
            data = JSON.parse(jsonEditor.getValue());
        } catch (e) {
            showSyncStatus('Cannot save invalid JSON', 'error');
            return;
        }
    }

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
    let savePath;
    const entry = pendingRequests.get('schema');
    if (entry && entry.jsonFile) {
        savePath = entry.jsonFile;
    } else {
        // No file loaded, create new one in target directory
        savePath = selectedTargetDirectory.path + '/' + currentSchemaName + '.json';
    }

    // Send save request via WebSocket
    const jsonContent = JSON.stringify(data, null, 2);
    sendWebSocketMessage('save_json', {
        file: savePath,
        content: jsonContent
    });

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

function publishConfig() {
    if (!currentSchemaName) {
        showSyncStatus('Please select a schema first', 'warning');
        return;
    }

    sendWebSocketMessage('reload', { appName: currentSchemaName });
    showSyncStatus('Publishing configuration...', 'warning');
}

function resetForm() {
    currentData = { ...initialData };
    generateFormWithData(currentData);
    if (jsonEditor) {
        jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
    }
    showSyncStatus('Form reset to initial data', 'success');
}

// ---------------------------------------------------------------------------
// Export / Import — one-click JSON download/upload of the current config.

function exportConfig() {
    let data;
    const activeTab = document.querySelector('.tab-content.active');
    if (activeTab && activeTab.id === 'form-tab') {
        data = getFormData();
    } else if (jsonEditor) {
        try { data = JSON.parse(jsonEditor.getValue()); }
        catch (e) { showSyncStatus('Cannot export invalid JSON', 'error'); return; }
    } else {
        data = currentData;
    }
    const filename = (currentSchemaName || 'config') + '.json';
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
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
    const reader = new FileReader();
    reader.onload = () => {
        let data;
        try { data = JSON.parse(reader.result); }
        catch (e) {
            showSyncStatus('Import failed: ' + e.message, 'error');
            return;
        }
        currentData = data;
        generateFormWithData(currentData);
        if (jsonEditor) jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
        setDirty();
        showSyncStatus('Imported ' + file.name + ' — review and Save', 'success');
    };
    reader.onerror = () => showSyncStatus('Import failed: could not read file', 'error');
    reader.readAsText(file);
}

// ---------------------------------------------------------------------------
// Read-only / view mode — disables form inputs and hides mutation controls.

function toggleViewMode() {
    const on = !document.body.classList.contains('view-mode');
    document.body.classList.toggle('view-mode', on);
    const btn = document.getElementById('view-mode-toggle');
    if (btn) btn.textContent = on ? 'Edit Mode' : 'View Mode';
}

// ---------------------------------------------------------------------------
// Auto-save draft to localStorage — snapshot every 2s when dirty; restore
// banner on load when a draft exists. Cheap insurance against accidental tab
// closes and power outages.

function draftStorageKey() {
    const target = (selectedTargetDirectory && selectedTargetDirectory.path) || '';
    const schemaName = currentSchemaName || '';
    if (!target || !schemaName) return null;
    return 'configurator-draft:' + target + ':' + schemaName;
}

function saveDraft() {
    const key = draftStorageKey();
    if (!key) return;
    if (!document.getElementById('dirty-indicator') ||
        document.getElementById('dirty-indicator').style.display === 'none') {
        return; // only persist when the form is dirty
    }
    try {
        const data = getFormData();
        localStorage.setItem(key, JSON.stringify({
            data: data,
            savedAt: Date.now(),
            schema: currentSchemaName
        }));
    } catch (e) { /* localStorage full or unavailable — ignore */ }
}

function clearDraft() {
    const key = draftStorageKey();
    if (key) {
        try { localStorage.removeItem(key); } catch (e) { /* ignore */ }
    }
    hideDraftBanner();
}

function maybeOfferDraftRestore() {
    const key = draftStorageKey();
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
        text.textContent = 'Unsaved draft from ' + when + ' — restore?';
        banner.style.display = 'flex';
    }
}

function hideDraftBanner() {
    const banner = document.getElementById('draft-banner');
    if (banner) banner.style.display = 'none';
}

function restoreDraft() {
    const key = draftStorageKey();
    if (!key) return;
    try {
        const rec = JSON.parse(localStorage.getItem(key) || 'null');
        if (rec && rec.data) {
            currentData = rec.data;
            generateFormWithData(currentData);
            if (jsonEditor) jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
            setDirty();
            showSyncStatus('Draft restored', 'success');
        }
    } catch (e) { /* ignore */ }
    hideDraftBanner();
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
    textEditor.session.setMode('ace/mode/text');
    textEditor.setOptions({
        fontSize: 13,
        showPrintMargin: false,
        wrap: true,
        autoScrollEditorIntoView: true
    });
    textEditor.session.on('change', () => updateTextEditorDirtyState());
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
    textEditor.session.setMode('ace/mode/' + aceModeForFile(payload.fileName));
    textEditor.setValue(payload.content, -1);
    updateTextEditorDirtyState();
    showTextFileStatus('Loaded ' + currentTextFile, 'success');
}

function aceModeForFile(name) {
    const ext = (name.split('.').pop() || '').toLowerCase();
    const map = {
        sql: 'sql', json: 'json', xml: 'xml', yml: 'yaml', yaml: 'yaml',
        properties: 'properties', ldap: 'text', txt: 'text', conf: 'text'
    };
    return map[ext] || 'text';
}

function saveTextFile() {
    if (!textEditor || !currentTextFile) return;
    sendWebSocketMessage('save_text_file', {
        fileName: currentTextFile,
        content: textEditor.getValue()
    });
}

function handleTextFileSaved(name) {
    if (!textEditor) return;
    currentTextFileBaseline = textEditor.getValue();
    updateTextEditorDirtyState();
    showTextFileStatus('Saved ' + name, 'success');
}

function reloadTextFile() {
    if (!currentTextFile) return;
    sendWebSocketMessage('load_text_file', { fileName: currentTextFile });
}

function showTextFileStatus(text, kind) {
    const el = document.getElementById('text-file-status');
    if (!el) return;
    el.textContent = text;
    el.className = 'sync-status ' + (kind || '');
    el.style.display = 'inline-flex';
    setTimeout(() => { el.style.display = 'none'; }, 2500);
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
            sendWebSocketMessage('load_template', {
                schemaName: currentSchemaName,
                templateName: name
            });
        };
    });
}

function handleTemplateLoaded(raw) {
    let data;
    try { data = JSON.parse(raw); }
    catch (e) {
        showSyncStatus('Template contained invalid JSON: ' + e.message, 'error');
        return;
    }
    currentData = data;
    generateFormWithData(currentData);
    if (jsonEditor) jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
    setDirty();
    closeTemplateLibrary();
    showSyncStatus('Template loaded — review and Save', 'success');
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
        if (!(k in a)) out.push({ path: child, kind: 'added', to: b[k] });
        else if (!(k in b)) out.push({ path: child, kind: 'removed', from: a[k] });
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
    const baseline = initialData || {};
    const diffs = diffObjects(baseline, current);
    const summary = document.getElementById('diff-summary');
    const body = document.getElementById('diff-body');
    if (!summary || !body) return;

    if (diffs.length === 0) {
        summary.textContent = 'No changes vs. the last-loaded server copy.';
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
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    // Briefly highlight the target so it's obvious.
    el.classList.add('diff-highlight');
    setTimeout(() => el.classList.remove('diff-highlight'), 1600);
    if (typeof el.focus === 'function') el.focus({ preventScroll: true });
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
function runRegexTest() {
    const sampleEl = document.getElementById('regex-test-sample');
    const resultEl = document.getElementById('regex-test-result');
    if (!sampleEl || !resultEl) return;
    const pattern = regexTestBoundPatternInput ? (regexTestBoundPatternInput.value || '').trim() : '';
    const sample = sampleEl.value || '';
    if (!pattern) { resultEl.innerHTML = '<em>No pattern set.</em>'; return; }
    // Anchor for parity with RegexSelector.extract (which uses Matcher.matches()
    // — require the whole input to match, not a substring). Wrap in (?:...) so
    // existing numbered/named capture groups keep their indices.
    let re;
    try { re = new RegExp('^(?:' + pattern + ')$', 's'); }
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
        if ((t.tagName === 'INPUT' && (t.type === 'text' || t.type === 'password' || t.type === 'search' || t.type === 'number')) ||
            t.tagName === 'TEXTAREA') {
            lastFocusedTextInput = t;
        }
        if (t._schema) {
            renderInspectorSchema(t);
            if (document.body.classList.contains('inspector-open')) refreshInspectorVars();
        }
    });
})();

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
            if (typeof saveData === 'function') saveData();
        } else if (e.key === 'f' || e.key === 'F') {
            const f = document.getElementById('form-filter');
            if (f) {
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
}

// ========================================
// Feature 1: Unsaved Changes Warning
// ========================================

function setDirty() {
    if (!isDirty) {
        isDirty = true;
        const indicator = document.getElementById('dirty-indicator');
        if (indicator) {
            indicator.style.display = 'inline-flex';
        }
    }
}

function clearDirty() {
    isDirty = false;
    const indicator = document.getElementById('dirty-indicator');
    if (indicator) {
        indicator.style.display = 'none';
    }
    // Save current snapshot for comparison
    savedDataSnapshot = JSON.stringify(getFormData());
}

function checkUnsavedChanges() {
    if (isDirty && savedDataSnapshot !== null) {
        const currentData = JSON.stringify(getFormData());
        if (currentData !== savedDataSnapshot) {
            return confirm('You have unsaved changes. Do you want to discard them?');
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
            setDirty();
        });
    }

    // Warn before leaving page
    window.addEventListener('beforeunload', function(e) {
        if (isDirty) {
            e.preventDefault();
            e.returnValue = 'You have unsaved changes. Are you sure you want to leave?';
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

    targetDirectories.forEach(target => {
        // Don't include current target in list
        if (target.path !== selectedTargetDirectory?.path) {
            const option = document.createElement('option');
            option.value = target.path;
            option.textContent = target.displayName;
            select.appendChild(option);
        }
    });

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

    // Get current data
    let data;
    const activeTab = document.querySelector('.tab-content.active').id;
    if (activeTab === 'form-tab') {
        data = getFormData();
    } else {
        try {
            data = JSON.parse(jsonEditor.getValue());
        } catch (e) {
            alert('Cannot clone invalid JSON');
            return;
        }
    }

    // Construct save path for clone
    const clonePath = targetPath + '/' + currentSchemaName + '.json';

    // Send save request via WebSocket
    const jsonContent = JSON.stringify(data, null, 2);
    sendWebSocketMessage('save_json', {
        file: clonePath,
        content: jsonContent
    });

    closeCloneDialog();
    showSyncStatus('Configuration cloned to ' + targetPath, 'success');
}

// ========================================
// Feature 9: Validation Indicators
// ========================================

function validateField(fieldPath, value, fieldSchema) {
    const errors = [];

    if (!fieldSchema) return errors;

    // Required validation
    if (fieldSchema.required && (value === '' || value === null || value === undefined)) {
        errors.push('This field is required');
    }

    // Type validation
    if (value !== '' && value !== null && value !== undefined) {
        switch (fieldSchema.type) {
            case 'number':
            case 'integer':
                const num = Number(value);
                if (isNaN(num)) {
                    errors.push('Must be a valid number');
                } else {
                    if (fieldSchema.minimum !== undefined && num < fieldSchema.minimum) {
                        errors.push(`Minimum value is ${fieldSchema.minimum}`);
                    }
                    if (fieldSchema.maximum !== undefined && num > fieldSchema.maximum) {
                        errors.push(`Maximum value is ${fieldSchema.maximum}`);
                    }
                }
                break;

            case 'string':
                if (fieldSchema.minLength && value.length < fieldSchema.minLength) {
                    errors.push(`Minimum length is ${fieldSchema.minLength}`);
                }
                if (fieldSchema.maxLength && value.length > fieldSchema.maxLength) {
                    errors.push(`Maximum length is ${fieldSchema.maxLength}`);
                }
                if (fieldSchema.pattern) {
                    const regex = new RegExp(fieldSchema.pattern);
                    if (!regex.test(value)) {
                        errors.push('Value does not match required pattern');
                    }
                }
                break;
        }
    }

    return errors;
}

function validateForm() {
    validationErrors.clear();

    // Clear previous error displays
    document.querySelectorAll('.field-error').forEach(el => {
        el.classList.remove('field-error');
    });
    document.querySelectorAll('.field-error-message').forEach(el => {
        el.remove();
    });

    // Get all input fields
    const inputs = document.querySelectorAll('#dynamicForm input, #dynamicForm select, #dynamicForm textarea');

    inputs.forEach(input => {
        const fieldPath = input.dataset.path || input.name;
        if (!fieldPath) return;

        // Try to find field schema
        const fieldSchema = getFieldSchema(fieldPath);
        const errors = validateField(fieldPath, input.value, fieldSchema);

        if (errors.length > 0) {
            validationErrors.set(fieldPath, errors);

            // Add error class to input
            input.classList.add('field-error');

            // Add error message
            const errorDiv = document.createElement('div');
            errorDiv.className = 'field-error-message';
            errorDiv.textContent = '\u26a0 ' + errors[0];
            input.parentElement.appendChild(errorDiv);
        }
    });

    displayValidationSummary();
    return validationErrors.size === 0;
}

function getFieldSchema(fieldPath) {
    // This is a simplified schema lookup
    // In a full implementation, you'd traverse the schema tree
    const parts = fieldPath.split('.');
    let current = schema;

    for (const part of parts) {
        if (current.properties && current.properties[part]) {
            current = current.properties[part];
        } else if (current.$ref) {
            // Handle $ref - resolve via the shared resolveRef helper which
            // supports both victools $defs and kjetland definitions.
            const resolvedRef = resolveRef(current.$ref);
            if (resolvedRef) {
                current = resolvedRef;
                if (current.properties && current.properties[part]) {
                    current = current.properties[part];
                }
            }
        } else {
            return null;
        }
    }

    return current;
}

function displayValidationSummary() {
    const summary = document.getElementById('validation-summary');
    const list = document.getElementById('validation-summary-list');

    if (!summary || !list) return;

    if (validationErrors.size === 0) {
        summary.classList.remove('active');
        return;
    }

    list.innerHTML = '';
    validationErrors.forEach((errors, fieldPath) => {
        errors.forEach(error => {
            const li = document.createElement('li');
            li.textContent = `${fieldPath}: ${error}`;
            list.appendChild(li);
        });
    });

    summary.classList.add('active');
}

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
    // Restore saved theme
    const savedTheme = localStorage.getItem('blade-configurator-theme');
    if (savedTheme && savedTheme !== 'light') {
        document.documentElement.setAttribute('data-theme', savedTheme);
    }
    const themeSelect = document.getElementById('app-theme-select');
    if (themeSelect && savedTheme) {
        themeSelect.value = savedTheme;
    }

    connectWebSocket();
    requestTargetDirectories(); // Request target directories (domain, clusters, servers)
    requestSchemaList(); // Request schemas from server instead of using hardcoded list

    // Initialize with empty form and editor - user will select data from dropdowns
    initializeJsonEditor();

    // Feature: Initialize enhancements
    setupDirtyTracking(); // Set up unsaved changes tracking

    // Position help tooltips using fixed positioning to avoid overflow clipping
    document.addEventListener('mouseenter', function(e) {
        const icon = e.target.closest('.field-help-icon');
        if (!icon) return;
        const tooltip = icon.querySelector('.field-help-tooltip');
        if (!tooltip) return;
        const rect = icon.getBoundingClientRect();
        tooltip.style.display = 'block';
        // Position above the icon, centered
        const tooltipRect = tooltip.getBoundingClientRect();
        let left = rect.left + rect.width / 2 - tooltipRect.width / 2;
        let top = rect.top - tooltipRect.height - 8;
        // Keep within viewport
        if (left < 4) left = 4;
        if (left + tooltipRect.width > window.innerWidth - 4) left = window.innerWidth - tooltipRect.width - 4;
        if (top < 4) {
            // Show below if no room above
            top = rect.bottom + 8;
            tooltip.classList.add('below');
        } else {
            tooltip.classList.remove('below');
        }
        tooltip.style.left = left + 'px';
        tooltip.style.top = top + 'px';
    }, true);

    document.addEventListener('mouseleave', function(e) {
        const icon = e.target.closest('.field-help-icon');
        if (!icon) return;
        const tooltip = icon.querySelector('.field-help-tooltip');
        if (tooltip) tooltip.style.display = 'none';
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

    // Set the (legacy) ace-editor theme selector if it's still in the HTML.
    // The current build only ships an `app-theme-select` for the overall
    // app theme; the standalone ace-editor theme dropdown was removed.
    const aceThemeSelect = document.getElementById('theme-select');
    if (aceThemeSelect) {
        aceThemeSelect.value = currentTheme;
    }

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

// App theme to Ace editor theme mapping
const aceThemeMap = {
    light: 'eclipse',
    dark: 'tomorrow_night',
    midnight: 'cobalt',
    warm: 'chrome',
    glossy: 'tomorrow_night'
};

function switchAppTheme(themeName) {
    if (themeName === 'light') {
        document.documentElement.removeAttribute('data-theme');
    } else {
        document.documentElement.setAttribute('data-theme', themeName);
    }

    // Update Ace editors to match
    const aceTheme = aceThemeMap[themeName] || 'eclipse';
    currentTheme = aceTheme;
    if (jsonEditor) {
        jsonEditor.setTheme('ace/theme/' + aceTheme);
    }
    if (schemaEditor) {
        schemaEditor.setTheme('ace/theme/' + aceTheme);
    }

    // Persist
    localStorage.setItem('blade-configurator-theme', themeName);
    localStorage.setItem('blade-configurator-ace-theme', aceTheme);
}

function switchTab(tabName) {
    try {
        const activeContent = document.querySelector('.tab-content.active');
        const previousTab = activeContent ? activeContent.id : 'form-tab';

        // Auto-sync data when switching tabs
        try {
            if ((tabName === 'json' || tabName === 'schema') && previousTab === 'form-tab') {
                // Switching to JSON or Schema tab - sync form data to JSON
                const formData = getFormData();
                currentData = formData;
                if (jsonEditor) {
                    jsonEditor.setValue(JSON.stringify(formData, null, 2), -1);
                }
            } else if (tabName === 'form' && previousTab === 'json-tab') {
                // Switching to Form tab - sync JSON data to form
                if (jsonEditor) {
                    const jsonData = JSON.parse(jsonEditor.getValue());
                    currentData = jsonData;
                    generateFormWithData(jsonData);
                }
            }
        } catch (e) {
            console.error('Error syncing data in switchTab:', e);
            if (tabName === 'form' && previousTab === 'json-tab') {
                showSyncStatus('Cannot sync invalid JSON to form: ' + e.message, 'error');
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

function resetJson() {
    jsonEditor.setValue(JSON.stringify(initialData, null, 2), -1);
    currentData = { ...initialData };
    showSyncStatus('JSON reset to initial data', 'success');
}

function showSyncStatus(message, type) {
    const statusEl = document.getElementById('sync-status');
    statusEl.textContent = message;
    statusEl.className = `sync-status ${type}`;
    statusEl.style.display = 'inline-flex';

    // Auto-hide after 3 seconds
    setTimeout(() => {
        statusEl.style.display = 'none';
    }, 3000);
}

function generateFormWithData(data) {
    currentData = data;
    buildIdentityRegistry(data);
    const form = document.getElementById('dynamicForm');
    form.innerHTML = '';

    if (schema.properties) {
        Object.keys(schema.properties).forEach(prop => {
            const propSchema = schema.properties[prop];
            const propValue = data[prop];
            const propTitle = propSchema.title || prop;
            const propDescription = propSchema.description;

            // If property has no value, show a placeholder with '+' icon
            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(propSchema, propTitle, propDescription, prop, form, false);
                form.appendChild(placeholder);
            } else {
                const group = createFormGroup(propSchema, propTitle, propDescription, prop, propValue, false, propSchema);
                form.appendChild(group);
            }
        });
    }
    // After a fresh render, offer to restore any locally-saved draft that
    // survives from a prior session.
    if (typeof maybeOfferDraftRestore === 'function') {
        setTimeout(maybeOfferDraftRestore, 0);
    }
}

// Version History Functions
function showVersionHistory() {
    if (!currentSchemaName) {
        showSyncStatus('Please select a schema first', 'warning');
        return;
    }

    const entry = schemaRegistry.find(e => e.name === currentSchemaName);
    if (!entry || !entry.jsonFile) {
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
}

function displayVersionList(versionListJson) {
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
                    <div class="version-date">${dateStr} ${isRecent ? '(Latest)' : ''}</div>
                    <div class="version-size">${sizeKB} KB</div>
                </div>
                <div class="version-actions">
                    <button type="button" class="btn btn-secondary btn-small"
                            onclick="previewVersion('${version.timestamp}')">Preview</button>
                    <button type="button" class="btn btn-primary btn-small"
                            onclick="restoreVersionFromHistory('${version.timestamp}')">Restore</button>
                </div>
            </li>
        `;
    });
    html += '</ul>';

    container.innerHTML = html;
}

function restoreVersionFromHistory(timestamp) {
    if (!confirm('Are you sure you want to restore this version? The current version will be saved as a backup.')) {
        return;
    }

    const entry = schemaRegistry.find(e => e.name === currentSchemaName);
    if (!entry || !entry.jsonFile) return;

    pendingRequests.set('restoreTimestamp', timestamp);

    sendWebSocketMessage('restore_version', {
        file: entry.jsonFile,
        timestamp: timestamp
    });

    showSyncStatus('Restoring version...', 'warning');
}

function handleVersionRestored(content) {
    try {
        const data = JSON.parse(content);
        const timestamp = pendingRequests.get('restoreTimestamp');
        pendingRequests.delete('restoreTimestamp');
        const dateStr = timestamp ? new Date(Number(timestamp)).toLocaleString() : '';

        currentData = data;
        initialData = data;

        // Update both form and JSON editor
        generateFormWithData(data);
        if (jsonEditor) {
            jsonEditor.setValue(JSON.stringify(data, null, 2), -1);
        }

        showSyncStatus('Restored: ' + dateStr, 'success');
        showSchemaLoadStatus('Restored: ' + dateStr, 'success');
        closeVersionHistory();
    } catch (e) {
        showSyncStatus('Error restoring version: ' + e.message, 'error');
    }
}

function previewVersion(timestamp) {
    const entry = schemaRegistry.find(e => e.name === currentSchemaName);
    if (!entry || !entry.jsonFile) return;

    pendingRequests.set('versionTimestamp', timestamp);

    sendWebSocketMessage('get_version_content', {
        file: entry.jsonFile,
        timestamp: timestamp
    });

    showSyncStatus('Loading version preview...', 'warning');
}

function handleVersionContent(content) {
    try {
        const data = JSON.parse(content);
        const timestamp = pendingRequests.get('versionTimestamp');
        pendingRequests.delete('versionTimestamp');
        const dateStr = timestamp ? new Date(Number(timestamp)).toLocaleString() : '';

        // Switch to JSON tab
        switchTab('json');

        // Show preview in JSON editor
        if (jsonEditor) {
            jsonEditor.setValue(JSON.stringify(data, null, 2), -1);
        }

        showSyncStatus('Preview: ' + dateStr + ' (not saved)', 'warning');
        showSchemaLoadStatus('Preview: ' + dateStr, 'warning');
        closeVersionHistory();
    } catch (e) {
        showSyncStatus('Error loading preview: ' + e.message, 'error');
    }
}

// Close modal when clicking outside
document.addEventListener('click', function(event) {
    const modal = document.getElementById('versionModal');
    if (event.target === modal) {
        closeVersionHistory();
    }
});
