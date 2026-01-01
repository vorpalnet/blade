// JSON Schema and Data - loaded dynamically when user selects from dropdowns
let schema = {};
let initialData = {};

// Schema registry - populated dynamically from server
let schemaRegistry = [];
const SCHEMAS_DIRECTORY = "./config/custom/vorpal/_schemas";

// Target directories - populated dynamically from server
let targetDirectories = [];
let selectedTargetDirectory = null;

let formIdCounter = 0;
let jsonEditor;
let currentData = {};
let currentTheme = 'eclipse';
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

// Feature: Recent Files
const MAX_RECENT_FILES = 10;
let recentFiles = [];

// Feature: Validation
let validationErrors = new Map();

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

        case 'save_success':
            showSyncStatus('Data saved successfully', 'success');
            showSchemaLoadStatus('Saved', 'success');
            clearDirty(); // Feature: Clear dirty state after successful save
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
}

// Populate the schema dropdown
// Request schema list from server
function requestSchemaList() {
    if (websocket && websocket.readyState === WebSocket.OPEN) {
        sendWebSocketMessage('list_schemas', { directory: SCHEMAS_DIRECTORY });
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

        // Add indicator based on data file type
        let indicator = '';
        let tooltip = entry.fileName;

        if (entry.jsonFile) {
            if (entry.jsonFileType === 'primary') {
                indicator = ' ●'; // Has primary data file
                tooltip += ' (has data)';
            } else if (entry.jsonFileType === 'sample') {
                indicator = ' ○'; // Has sample data file only
                tooltip += ' (sample data)';
            }
        } else {
            indicator = ''; // No data file
            tooltip += ' (no data)';
        }

        option.textContent = entry.name + indicator;
        option.title = tooltip;
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

    // Feature: Add to recent files
    addToRecentFiles(
        selectedTargetDirectory.path,
        selectedTargetDirectory.displayName,
        schemaName
    );

    // Store the entry in a pending request
    pendingRequests.set('schema', entry);

    // Request the schema file via WebSocket
    sendWebSocketMessage('load_schema', { file: entry.schemaFile });

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

            // If JSON file exists, load it
            if (resolution.jsonFile) {
                sendWebSocketMessage('load_json', { file: resolution.jsonFile });
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

            // Check if we loaded from a sample file
            const entry = pendingRequests.get('schema');
            if (entry && entry.jsonFileType === 'sample') {
                showSyncStatus('Loaded sample data (will save to primary location)', 'warning');
            }
        }

        finalizeSchemaLoad(newData);

    } catch (error) {
        console.error('Error processing JSON data:', error);
        showSchemaLoadStatus('Warning: Could not parse JSON data', 'warning');
        // Continue with empty data
        finalizeSchemaLoad({});
    }
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
    if (ref.startsWith('#/definitions/')) {
        const defName = ref.substring('#/definitions/'.length);
        return schema.definitions[defName];
    }
    return null;
}

function createFormElement(fieldSchema, path, value = null, isMapKey = false) {
    const id = `field_${formIdCounter++}`;

    if (fieldSchema.$ref) {
        fieldSchema = resolveRef(fieldSchema.$ref);
    }

    if (isMapKey) {
        const input = document.createElement('input');
        input.type = 'text';
        input.value = value || '';
        input.className = 'map-key-input';
        input.setAttribute('data-path', path);
        return input;
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
            return checkbox;

        case 'integer':
            const numberInput = document.createElement('input');
            numberInput.type = 'number';
            numberInput.id = id;
            numberInput.value = value !== null && value !== undefined ? value : '';
            numberInput.setAttribute('data-path', path);
            return numberInput;

        case 'string':
            const textInput = document.createElement('input');
            textInput.type = 'text';
            textInput.id = id;
            textInput.value = value !== null && value !== undefined ? value : '';
            textInput.setAttribute('data-path', path);
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

    if (fieldSchema.$ref) {
        fieldSchema = resolveRef(fieldSchema.$ref);
    }

    if (fieldSchema.type === 'object') {
        if (fieldSchema.additionalProperties) {
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

        // Create header with label and delete button
        const headerRow = document.createElement('div');
        headerRow.className = 'form-group-header';

        const label = document.createElement('label');
        label.textContent = title || 'Field';

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

        group.appendChild(headerRow);

        const element = createFormElement(fieldSchema, path, value);

        if (fieldSchema.type === 'boolean') {
            const checkboxGroup = document.createElement('div');
            checkboxGroup.className = 'checkbox-group';
            checkboxGroup.appendChild(element);
            // Move label into checkbox group
            headerRow.removeChild(label);
            checkboxGroup.appendChild(label);
            // Keep delete button in header
            group.appendChild(checkboxGroup);
            if (description) {
                const desc = document.createElement('div');
                desc.className = 'description';
                desc.textContent = description;
                group.appendChild(desc);
            }
        } else {
            group.appendChild(element);
        }

        return group;
    }
}

function createCollapsibleSection(title, description, content, hasData = false, autoCollapse = false, deleteInfo = null) {
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
        const descBadge = document.createElement('span');
        descBadge.className = 'collapsible-badge';
        descBadge.textContent = description.length > 30 ? description.substring(0, 30) + '...' : description;
        descBadge.title = description;
        badgeContainer.appendChild(descBadge);
    }

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
        content.style.maxHeight = content.scrollHeight + 'px';

        // Auto-expand parent sections to provide room
        expandParentSections(header);

        // Update heights of all ancestor containers
        setTimeout(() => {
            updateAllParentContainers(header.closest('.collapsible-section'));
        }, 50);
    } else {
        // Collapse
        header.classList.add('collapsed');
        content.classList.add('collapsed');
        arrow.classList.add('collapsed');
        content.style.maxHeight = '0px';

        // Update parent heights after collapse
        setTimeout(() => {
            updateAllParentContainers(header.closest('.collapsible-section'));
        }, 300); // Wait for collapse animation
    }
}

function updateAllParentContainers(element) {
    if (!element) return;

    // Start from the current element and work up the DOM tree
    let currentElement = element;

    while (currentElement) {
        // Update collapsible section content height
        if (currentElement.classList.contains('collapsible-section')) {
            const content = currentElement.querySelector('.collapsible-content');
            if (content && !content.classList.contains('collapsed')) {
                content.style.maxHeight = 'none';
                setTimeout(() => {
                    content.style.maxHeight = content.scrollHeight + 'px';
                }, 10);
            }
        }

        // Find next parent collapsible section, map entry, or array item
        currentElement = currentElement.parentElement;
        while (currentElement &&
               !currentElement.classList.contains('collapsible-section') &&
               !currentElement.classList.contains('map-entry') &&
               !currentElement.classList.contains('array-item')) {
            currentElement = currentElement.parentElement;
        }

        // If we found a map entry or array item, check if it's inside a collapsible section
        if (currentElement && (currentElement.classList.contains('map-entry') || currentElement.classList.contains('array-item'))) {
            const parentSection = currentElement.closest('.collapsible-section');
            if (parentSection) {
                const parentContent = parentSection.querySelector('.collapsible-content');
                if (parentContent && !parentContent.classList.contains('collapsed')) {
                    parentContent.style.maxHeight = 'none';
                    setTimeout(() => {
                        parentContent.style.maxHeight = parentContent.scrollHeight + 'px';
                    }, 20);
                }
            }
            currentElement = parentSection;
        }
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
                parentContent.style.maxHeight = parentContent.scrollHeight + 'px';
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
        content.style.maxHeight = content.scrollHeight + 'px';
    }

    // Update parent section heights - including map entries and array items
    let parentElement = section.parentElement;
    while (parentElement) {
        // Check if parent is a map entry or array item
        if (parentElement.classList.contains('map-entry') || parentElement.classList.contains('array-item')) {
            // Force recalculation by temporarily removing max-height constraints
            const parentContent = parentElement.closest('.collapsible-content');
            if (parentContent && !parentContent.classList.contains('collapsed')) {
                parentContent.style.maxHeight = 'none';
                setTimeout(() => {
                    parentContent.style.maxHeight = parentContent.scrollHeight + 'px';
                }, 10);
            }
        }

        // Check if parent is a collapsible section
        const parentSection = parentElement.closest('.collapsible-section');
        if (parentSection && parentSection !== section) {
            const parentSectionContent = parentSection.querySelector('.collapsible-content');
            if (parentSectionContent && !parentSectionContent.classList.contains('collapsed')) {
                parentSectionContent.style.maxHeight = parentSectionContent.scrollHeight + 'px';
            }
            updateParentHeights(parentSection);
            break;
        } else {
            parentElement = parentElement.parentElement;
        }
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

    // Resolve $ref if present
    if (fieldSchema.$ref) {
        fieldSchema = resolveRef(fieldSchema.$ref);
        if (!fieldSchema) return null;
    }

    // Use schema default if provided
    if (fieldSchema.default !== undefined) {
        return JSON.parse(JSON.stringify(fieldSchema.default));
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
            updateAllParentContainers(propGroup.closest('.collapsible-section'));
        }, 10);
    };

    return placeholder;
}

function createObjectGroup(fieldSchema, title, description, path, value = null, isNested = false, originalSchema = null) {
    const schemaForDelete = originalSchema || fieldSchema;
    const content = document.createElement('div');

    const hasData = hasValue(value);
    const autoCollapse = !hasData;

    if (fieldSchema.properties) {
        Object.keys(fieldSchema.properties).forEach(prop => {
            const propSchema = fieldSchema.properties[prop];
            const propPath = path ? `${path}.${prop}` : prop;
            const propValue = value && value[prop] !== undefined ? value[prop] : null;
            const propTitle = propSchema.title || prop;
            const propDescription = propSchema.description;

            // If property has no value, show a placeholder with '+' icon
            if (!hasValue(propValue)) {
                const placeholder = createAddPropertyPlaceholder(propSchema, propTitle, propDescription, propPath, content, true);
                content.appendChild(placeholder);
            } else {
                const propGroup = createFormGroup(propSchema, propTitle, propDescription, propPath, propValue, true, propSchema);
                content.appendChild(propGroup);
            }
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
    addBtn.textContent = 'Add Entry';
    addBtn.onclick = () => {
        addMapEntry(container, fieldSchema.additionalProperties, path);
        updateSectionStatus();
    };
    header.appendChild(addBtn);

    content.appendChild(header);

    const container = document.createElement('div');
    container.className = 'map-container';
    container.setAttribute('data-path', path);
    content.appendChild(container);

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

    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'btn btn-primary';
    addBtn.textContent = 'Add Item';
    addBtn.onclick = () => {
        addArrayItem(container, fieldSchema.items, path);
        updateSectionStatus();
    };
    header.appendChild(addBtn);

    content.appendChild(header);

    const container = document.createElement('div');
    container.className = 'array-container';
    container.setAttribute('data-path', path);
    content.appendChild(container);

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

function addMapEntry(container, valueSchema, basePath, key = '', value = null) {
    const entry = document.createElement('div');
    entry.className = 'map-entry';

    const header = document.createElement('div');
    header.className = 'array-item-header';

    const title = document.createElement('div');
    title.className = 'array-item-title';
    title.textContent = 'Map Entry';
    header.appendChild(title);

    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'btn btn-danger';
    removeBtn.textContent = 'Remove';
    removeBtn.onclick = () => {
        entry.remove();
        // Update parent section status and heights
        updateParentSectionStatus(container);
        setTimeout(() => {
            updateAllParentContainers(container.closest('.collapsible-section'));
        }, 10);
    };
    header.appendChild(removeBtn);

    entry.appendChild(header);

    // Key input
    const keyLabel = document.createElement('label');
    keyLabel.textContent = 'Key';
    entry.appendChild(keyLabel);

    const keyInput = createFormElement({ type: 'string' }, '', key, true);
    entry.appendChild(keyInput);

    // Value input
    const valueLabel = document.createElement('label');
    valueLabel.textContent = 'Value';
    entry.appendChild(valueLabel);

    const valuePath = basePath ? `${basePath}.${key}` : key;

    if (valueSchema.$ref) {
        valueSchema = resolveRef(valueSchema.$ref);
    }

    if (valueSchema.type === 'object' || valueSchema.type === 'array') {
        const valueGroup = createFormGroup(valueSchema, null, null, valuePath, value, true);
        // Remove the outer form-group wrapper for inline display
        while (valueGroup.firstChild) {
            entry.appendChild(valueGroup.firstChild);
        }
    } else {
        const valueInput = createFormElement(valueSchema, valuePath, value);
        entry.appendChild(valueInput);
    }

    container.appendChild(entry);

    // Update parent heights after adding
    setTimeout(() => {
        updateAllParentContainers(container.closest('.collapsible-section'));
    }, 10);
}

function addArrayItem(container, itemSchema, basePath, value = null, index = null) {
    const currentIndex = index !== null ? index : container.children.length;
    const itemPath = `${basePath}[${currentIndex}]`;

    const item = document.createElement('div');
    item.className = 'array-item';

    const header = document.createElement('div');
    header.className = 'array-item-header';

    const title = document.createElement('div');
    title.className = 'array-item-title';
    title.textContent = `Item ${currentIndex + 1}`;
    header.appendChild(title);

    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'btn btn-danger';
    removeBtn.textContent = 'Remove';
    removeBtn.onclick = () => {
        item.remove();
        // Update parent section status and heights
        updateParentSectionStatus(container);
        setTimeout(() => {
            updateAllParentContainers(container.closest('.collapsible-section'));
        }, 10);
    };
    header.appendChild(removeBtn);

    item.appendChild(header);

    if (itemSchema.$ref) {
        itemSchema = resolveRef(itemSchema.$ref);
    }

    if (itemSchema.type === 'object' || itemSchema.type === 'array') {
        const itemGroup = createFormGroup(itemSchema, null, null, itemPath, value, true);
        // Remove the outer form-group wrapper for inline display
        while (itemGroup.firstChild) {
            item.appendChild(itemGroup.firstChild);
        }
    } else {
        const itemInput = createFormElement(itemSchema, itemPath, value);
        item.appendChild(itemInput);
    }

    container.appendChild(item);

    // Update parent heights after adding
    setTimeout(() => {
        updateAllParentContainers(container.closest('.collapsible-section'));
    }, 10);
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

        // Resolve $ref if present
        if (schemaNode.$ref) {
            schemaNode = resolveRef(schemaNode.$ref);
            if (!schemaNode) return null;
        }

        if (schemaNode.type === 'object') {
            if (schemaNode.additionalProperties) {
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
                // inside a .collapsible-content child (from the moved collapsible section)
                const collapsibleContent = container.querySelector(':scope > .collapsible-content');
                const searchContainer = collapsibleContent || container;

                Object.keys(schemaNode.properties).forEach(propName => {
                    let propSchema = schemaNode.properties[propName];
                    const propTitle = propSchema.title || propName;

                    // Resolve $ref for type checking
                    let resolvedSchema = propSchema;
                    if (propSchema.$ref) {
                        resolvedSchema = resolveRef(propSchema.$ref);
                    }

                    const isComplexType = resolvedSchema &&
                        (resolvedSchema.type === 'object' || resolvedSchema.type === 'array');

                    if (isComplexType) {
                        // Complex types (objects, arrays) - look for collapsible section
                        // Note: sections might be inside a wrapper div due to createObjectGroup structure
                        let targetContainer = null;
                        const sections = searchContainer.querySelectorAll(':scope > .collapsible-section, :scope > div > .collapsible-section');
                        sections.forEach(section => {
                            const header = section.querySelector('.collapsible-header .collapsible-title span:last-child');
                            if (header && header.textContent === propTitle) {
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
                        const formGroups = searchContainer.querySelectorAll(':scope > .form-group, :scope > .checkbox-group, :scope > div > .form-group, :scope > div > .checkbox-group');
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
                            let value;
                            if (foundInput.type === 'checkbox') {
                                value = foundInput.checked;
                            } else if (foundInput.type === 'number') {
                                value = foundInput.value ? parseInt(foundInput.value, 10) : null;
                            } else {
                                value = foundInput.value || null;
                            }

                            if (value !== null && value !== '') {
                                result[propName] = value;
                            }
                        }
                    }
                });

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
                const itemData = extractData(item, schemaNode.items, `${debugPath}[${idx}]`);
                if (itemData !== null) {
                    result.push(itemData);
                }
            });

            return result.length > 0 ? result : null;
        } else {
            // Primitive type - find the input
            const input = container.querySelector('input:not(.map-key-input), select, textarea');
            if (!input) return null;

            let value;
            if (input.type === 'checkbox') {
                value = input.checked;
            } else if (input.type === 'number') {
                value = input.value ? parseInt(input.value, 10) : null;
            } else {
                value = input.value || null;
            }

            return (value !== null && value !== '') ? value : null;
        }

        return null;
    }

    // Start extraction from the form using the schema
    const result = {};

    if (schema && schema.properties) {
        Object.keys(schema.properties).forEach(propName => {
            let propSchema = schema.properties[propName];
            const propTitle = propSchema.title || propName;

            // Resolve $ref for type checking
            let resolvedSchema = propSchema;
            if (propSchema.$ref) {
                resolvedSchema = resolveRef(propSchema.$ref);
            }

            const isComplexType = resolvedSchema &&
                (resolvedSchema.type === 'object' || resolvedSchema.type === 'array');

            if (isComplexType) {
                // Complex types - look for collapsible section
                const sections = form.querySelectorAll(':scope > .collapsible-section');
                let targetSection = null;

                sections.forEach(section => {
                    const header = section.querySelector('.collapsible-header .collapsible-title span:last-child');
                    if (header && header.textContent === propTitle) {
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
                    let value;
                    if (foundInput.type === 'checkbox') {
                        value = foundInput.checked;
                    } else if (foundInput.type === 'number') {
                        value = foundInput.value ? parseInt(foundInput.value, 10) : null;
                    } else {
                        value = foundInput.value || null;
                    }

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

function resetForm() {
    currentData = { ...initialData };
    generateFormWithData(currentData);
    if (jsonEditor) {
        jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);
    }
    showSyncStatus('Form reset to initial data', 'success');
}

function expandAll() {
    const headers = document.querySelectorAll('.collapsible-header.collapsed');
    headers.forEach(header => {
        const content = header.nextElementSibling;
        const arrow = header.querySelector('.collapsible-arrow');

        header.classList.remove('collapsed');
        content.classList.remove('collapsed');
        arrow.classList.remove('collapsed');
        content.style.maxHeight = content.scrollHeight + 'px';
    });
}

function collapseAll() {
    const headers = document.querySelectorAll('.collapsible-header:not(.collapsed)');
    headers.forEach(header => {
        const content = header.nextElementSibling;
        const arrow = header.querySelector('.collapsible-arrow');

        header.classList.add('collapsed');
        content.classList.add('collapsed');
        arrow.classList.add('collapsed');
        content.style.maxHeight = '0px';
    });
}

function collapseEmpty() {
    const sections = document.querySelectorAll('.collapsible-section');
    sections.forEach(section => {
        const badge = section.querySelector('.collapsible-badge.null');
        if (badge) {
            const header = section.querySelector('.collapsible-header');
            const content = header.nextElementSibling;
            const arrow = header.querySelector('.collapsible-arrow');

            if (!header.classList.contains('collapsed')) {
                header.classList.add('collapsed');
                content.classList.add('collapsed');
                arrow.classList.add('collapsed');
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
    if (isDirty) {
        return confirm('You have unsaved changes. Do you want to discard them?');
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
// Feature 4: Recent Files List
// ========================================

function loadRecentFiles() {
    try {
        const stored = localStorage.getItem('recentFiles');
        if (stored) {
            recentFiles = JSON.parse(stored);
            displayRecentFiles();
        }
    } catch (e) {
        console.error('Error loading recent files:', e);
        recentFiles = [];
    }
}

function saveRecentFilesToStorage() {
    try {
        localStorage.setItem('recentFiles', JSON.stringify(recentFiles));
    } catch (e) {
        console.error('Error saving recent files:', e);
    }
}

function addToRecentFiles(targetPath, targetName, schemaName) {
    if (!targetPath || !schemaName) return;

    // Create recent file entry
    const entry = {
        targetPath: targetPath,
        targetName: targetName || targetPath,
        schemaName: schemaName,
        timestamp: Date.now()
    };

    // Remove duplicates
    recentFiles = recentFiles.filter(f =>
        !(f.targetPath === targetPath && f.schemaName === schemaName)
    );

    // Add to beginning
    recentFiles.unshift(entry);

    // Keep only MAX_RECENT_FILES
    if (recentFiles.length > MAX_RECENT_FILES) {
        recentFiles = recentFiles.slice(0, MAX_RECENT_FILES);
    }

    saveRecentFilesToStorage();
    displayRecentFiles();
}

function displayRecentFiles() {
    const container = document.getElementById('recent-files-container');
    const list = document.getElementById('recent-files-list');

    if (!list || recentFiles.length === 0) {
        if (container) container.style.display = 'none';
        return;
    }

    list.innerHTML = '';
    recentFiles.forEach(file => {
        const chip = document.createElement('div');
        chip.className = 'recent-file-chip';
        chip.onclick = () => loadFromRecent(file);

        const badge = document.createElement('span');
        badge.className = 'target-badge';
        badge.textContent = file.targetName.length > 15 ?
            file.targetName.substring(0, 15) + '...' : file.targetName;

        const schemaText = document.createTextNode(file.schemaName);

        chip.appendChild(badge);
        chip.appendChild(schemaText);
        list.appendChild(chip);
    });

    container.style.display = 'block';
}

function loadFromRecent(file) {
    // Check for unsaved changes
    if (!checkUnsavedChanges()) return;

    // Find and select the target
    const targetSelect = document.getElementById('target-directory-select');
    if (targetSelect) {
        targetSelect.value = file.targetPath;
        onTargetDirectoryChange(file.targetPath);
    }

    // Wait a bit for target to load, then select schema
    setTimeout(() => {
        const schemaSelect = document.getElementById('schema-select');
        if (schemaSelect) {
            schemaSelect.value = file.schemaName;
            loadSchema(file.schemaName);
        }
    }, 100);
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
            // Handle $ref - simplified version
            const refPath = current.$ref.replace('#/definitions/', '');
            if (schema.definitions && schema.definitions[refPath]) {
                current = schema.definitions[refPath];
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
    connectWebSocket();
    requestTargetDirectories(); // Request target directories (domain, clusters, servers)
    requestSchemaList(); // Request schemas from server instead of using hardcoded list

    // Initialize with empty form and editor - user will select data from dropdowns
    initializeJsonEditor();

    // Feature: Initialize enhancements
    loadRecentFiles(); // Load recent files from localStorage
    setupDirtyTracking(); // Set up unsaved changes tracking

    // Add tab click handlers (backup for onclick attributes)
    document.querySelectorAll('.tab').forEach(tab => {
        tab.addEventListener('click', function(e) {
            const tabName = this.textContent.includes('Form') ? 'form' : 'json';
            switchTab(tabName);
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

    // Set the theme selector to the current theme
    document.getElementById('theme-select').value = currentTheme;

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
}

function changeTheme(themeName) {
    if (jsonEditor) {
        currentTheme = themeName;
        jsonEditor.setTheme("ace/theme/" + themeName);
        showSyncStatus(`Theme changed to ${themeName}`, 'success');
    }
}

function switchTab(tabName) {
    const previousTab = document.querySelector('.tab-content.active').id;

    // Auto-sync data when switching tabs
    try {
        if (tabName === 'json' && previousTab === 'form-tab') {
            // Switching to JSON tab - sync form data to JSON
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
        console.error('Error in switchTab:', e);
        if (tabName === 'form' && previousTab === 'json-tab') {
            showSyncStatus('Cannot sync invalid JSON to form: ' + e.message, 'error');
            return;
        }
    }

    // Update tab buttons
    document.querySelectorAll('.tab').forEach(tab => tab.classList.remove('active'));
    document.querySelector(`[onclick="switchTab('${tabName}')"]`).classList.add('active');

    // Update tab content
    document.querySelectorAll('.tab-content').forEach(content => content.classList.remove('active'));
    document.getElementById(tabName + '-tab').classList.add('active');

    // Refresh JSON editor layout if switching to JSON tab
    if (tabName === 'json' && jsonEditor) {
        setTimeout(() => jsonEditor.resize(), 100);
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

    sendWebSocketMessage('restore_version', {
        file: entry.jsonFile,
        timestamp: timestamp
    });

    showSyncStatus('Restoring version...', 'warning');
}

function handleVersionRestored(content) {
    try {
        const data = JSON.parse(content);
        currentData = data;
        initialData = data;

        // Update both form and JSON editor
        generateFormWithData(data);
        if (jsonEditor) {
            jsonEditor.setValue(JSON.stringify(data, null, 2), -1);
        }

        showSyncStatus('Version restored successfully', 'success');
        showSchemaLoadStatus('Restored', 'success');
        closeVersionHistory();
    } catch (e) {
        showSyncStatus('Error restoring version: ' + e.message, 'error');
    }
}

function previewVersion(timestamp) {
    const entry = schemaRegistry.find(e => e.name === currentSchemaName);
    if (!entry || !entry.jsonFile) return;

    sendWebSocketMessage('get_version_content', {
        file: entry.jsonFile,
        timestamp: timestamp
    });

    showSyncStatus('Loading version preview...', 'warning');
}

function handleVersionContent(content) {
    try {
        const data = JSON.parse(content);

        // Switch to JSON tab
        switchTab('json');

        // Show preview in JSON editor
        if (jsonEditor) {
            jsonEditor.setValue(JSON.stringify(data, null, 2), -1);
        }

        showSyncStatus('Preview loaded (not saved)', 'warning');
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
