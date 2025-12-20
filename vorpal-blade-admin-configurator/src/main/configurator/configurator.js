// JSON Schema and Data (loaded from the provided files)
// These are set as default values, but can be changed via the schema selector
let schema = {
  "$schema" : "http://json-schema.org/draft-04/schema#",
  "title" : "App Router Configuration",
  "type" : "object",
  "additionalProperties" : false,
  "properties" : {
    "logging" : {
      "propertyOrder" : 1,
      "$ref" : "#/definitions/LogParameters",
      "description" : "Optional logging parameters",
      "title" : "Logging"
    },
    "session" : {
      "propertyOrder" : 2,
      "$ref" : "#/definitions/SessionParameters",
      "description" : "Optional session parameters",
      "title" : "Session"
    },
    "defaultApplication" : {
      "propertyOrder" : 3,
      "type" : "string",
      "title" : "Default Application"
    },
    "previous" : {
      "propertyOrder" : 4,
      "type" : "object",
      "additionalProperties" : {
        "$ref" : "#/definitions/State"
      },
      "title" : "Previous"
    }
  },
  "definitions" : {
    "LogParameters" : {
      "type" : "object",
      "additionalProperties" : false,
      "properties" : {
        "useParentLogging" : {
          "propertyOrder" : 1,
          "type" : "boolean",
          "description" : "Write to parent logger, i.e. the WebLogic engine log file. Default: false",
          "title" : "Use Parent Logging"
        },
        "directory" : {
          "propertyOrder" : 2,
          "type" : "string",
          "description" : "Location of log files. Supports environment and servlet context variables. Default: ./servers/${weblogic.Name}/logs/vorpal",
          "title" : "Directory"
        },
        "fileSize" : {
          "propertyOrder" : 3,
          "type" : "string",
          "description" : "Maximum file size written in human readable form. Default: 100MiB",
          "title" : "File Size"
        },
        "fileCount" : {
          "propertyOrder" : 4,
          "type" : "integer",
          "description" : "Maximum number of log files. Default: 25",
          "title" : "File Count"
        },
        "appendFile" : {
          "propertyOrder" : 5,
          "type" : "boolean",
          "description" : "Continue to use the same log file after reboot. Default: true",
          "title" : "Append File"
        },
        "loggingLevel" : {
          "propertyOrder" : 6,
          "type" : "string",
          "enum" : [ "OFF", "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "ALL" ],
          "description" : "Logging level. Levels include: OFF, SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST, ALL. Default: FINE",
          "title" : "Logging Level"
        },
        "sequenceDiagramLoggingLevel" : {
          "propertyOrder" : 7,
          "type" : "string",
          "enum" : [ "OFF", "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "ALL" ],
          "description" : "Level at which sequence diagrams will be logged. Default: FINE",
          "title" : "Sequence Diagram Logging Level"
        },
        "configurationLoggingLevel" : {
          "propertyOrder" : 8,
          "type" : "string",
          "enum" : [ "OFF", "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "ALL" ],
          "description" : "Level at which configuration changes will be logged. Default: FINE",
          "title" : "Configuration Logging Level"
        },
        "fileName" : {
          "propertyOrder" : 9,
          "type" : "string",
          "title" : "File Name"
        }
      }
    },
    "SessionParameters" : {
      "type" : "object",
      "additionalProperties" : false,
      "properties" : {
        "expiration" : {
          "propertyOrder" : 1,
          "type" : "integer",
          "description" : "Set Application Session expiration in minutes.",
          "title" : "Expiration"
        },
        "keepAlive" : {
          "propertyOrder" : 2,
          "$ref" : "#/definitions/KeepAliveParameters",
          "description" : "Set Keep-Alive parameters.",
          "title" : "Keep Alive"
        },
        "sessionSelectors" : {
          "propertyOrder" : 3,
          "type" : "array",
          "format" : "table",
          "items" : {
            "$ref" : "#/definitions/AttributeSelector"
          },
          "description" : "List of selectors for creating session (SipApplicationSession) lookup keys.",
          "title" : "Session Selectors"
        }
      }
    },
    "KeepAliveParameters" : {
      "type" : "object",
      "additionalProperties" : false,
      "properties" : {
        "style" : {
          "propertyOrder" : 1,
          "type" : "string",
          "enum" : [ "DISABLED", "UPDATE", "REINVITE" ],
          "description" : "Sets keep alive style: DISABLED, UPDATE, REINVITE",
          "title" : "Style"
        },
        "sessionExpires" : {
          "propertyOrder" : 2,
          "type" : "integer",
          "description" : "Sets Session-Expires header, in seconds",
          "title" : "Session Expires"
        },
        "minSE" : {
          "propertyOrder" : 3,
          "type" : "integer",
          "description" : "Sets Min-SE header, in seconds",
          "title" : "Min SE"
        }
      }
    },
    "AttributeSelector" : {
      "type" : "object",
      "additionalProperties" : false,
      "properties" : {
        "id" : {
          "propertyOrder" : 1,
          "type" : "string",
          "title" : "Id"
        },
        "description" : {
          "propertyOrder" : 2,
          "type" : "string",
          "title" : "Description"
        },
        "attribute" : {
          "propertyOrder" : 3,
          "type" : "string",
          "title" : "Attribute"
        },
        "pattern" : {
          "propertyOrder" : 4,
          "type" : "string",
          "title" : "Pattern"
        },
        "expression" : {
          "propertyOrder" : 5,
          "type" : "string",
          "title" : "Expression"
        },
        "dialog" : {
          "propertyOrder" : 6,
          "type" : "string",
          "enum" : [ "origin", "destination" ],
          "description" : "apply SipSession attributes to either origin or destination dialog",
          "title" : "Dialog"
        },
        "additionalExpressions" : {
          "propertyOrder" : 7,
          "type" : "object",
          "additionalProperties" : {
            "type" : "string"
          },
          "title" : "Additional Expressions"
        }
      }
    },
    "State" : {
      "type" : "object",
      "additionalProperties" : false,
      "properties" : {
        "triggers" : {
          "propertyOrder" : 1,
          "type" : "object",
          "additionalProperties" : {
            "$ref" : "#/definitions/Trigger"
          },
          "title" : "Triggers"
        }
      }
    },
    "Trigger" : {
      "type" : "object",
      "additionalProperties" : false,
      "properties" : {
        "transitions" : {
          "propertyOrder" : 1,
          "type" : "array",
          "format" : "table",
          "items" : {
            "$ref" : "#/definitions/Transition"
          },
          "title" : "Transitions"
        }
      }
    },
    "Transition" : {
      "type" : "object",
      "additionalProperties" : false,
      "properties" : {
        "id" : {
          "propertyOrder" : 1,
          "type" : "string",
          "title" : "Id"
        },
        "next" : {
          "propertyOrder" : 2,
          "type" : "string",
          "title" : "Next"
        },
        "condition" : {
          "propertyOrder" : 3,
          "type" : "object",
          "additionalProperties" : {
            "type" : "array",
            "format" : "table",
            "items" : {
              "type" : "object",
              "additionalProperties" : {
                "type" : "string"
              }
            }
          },
          "title" : "Condition"
        },
        "action" : {
          "propertyOrder" : 4,
          "$ref" : "#/definitions/Action",
          "title" : "Action"
        }
      }
    },
    "Action" : {
      "type" : "object",
      "additionalProperties" : false,
      "properties" : {
        "terminating" : {
          "propertyOrder" : 1,
          "type" : "string",
          "title" : "Terminating"
        },
        "originating" : {
          "propertyOrder" : 2,
          "type" : "string",
          "title" : "Originating"
        },
        "route" : {
          "propertyOrder" : 3,
          "type" : "array",
          "format" : "table",
          "items" : {
            "type" : "string"
          },
          "title" : "Route"
        },
        "route_back" : {
          "propertyOrder" : 4,
          "type" : "array",
          "format" : "table",
          "items" : {
            "type" : "string"
          },
          "title" : "Route _back"
        },
        "route_final" : {
          "propertyOrder" : 5,
          "type" : "array",
          "format" : "table",
          "items" : {
            "type" : "string"
          },
          "title" : "Route _final"
        }
      }
    }
  }
};

let initialData = {
  "logging" : {
    "useParentLogging" : false,
    "directory" : "./servers/${weblogic.Name}/logs/vorpal",
    "fileSize" : "100MiB",
    "fileCount" : 25,
    "appendFile" : true,
    "loggingLevel" : "WARNING",
    "sequenceDiagramLoggingLevel" : "FINE",
    "configurationLoggingLevel" : "FINE",
    "fileName" : "${sip.application.name}.%g.log"
  },
  "defaultApplication" : "b2bua",
  "previous" : {
    "keep-alive" : {
      "triggers" : {
        "INVITE" : {
          "transitions" : [ {
            "id" : "INV-2",
            "next" : "b2bua",
            "condition" : {
              "Session-Expires" : [ {
                "value" : "3600"
              }, {
                "refresher" : "uac"
              } ],
              "Region" : [ {
                "equals" : "ORIGINATING"
              } ],
              "Request-URI" : [ {
                "uri" : "^(sips?):([^@]+)(?:@(.+))?$"
              } ],
              "From" : [ {
                "address" : "^.*<(sips?):([^@]+)(?:@(.+))?>.*$"
              } ],
              "To" : [ {
                "user" : "bob"
              }, {
                "host" : "vorpal.net"
              }, {
                "equals" : "<sip:bob@vorpal.net>"
              } ],
              "Directive" : [ {
                "equals" : "CONTINUE"
              } ],
              "Region-Label" : [ {
                "equals" : "ORIGINATING"
              } ],
              "Allow" : [ {
                "contains" : "INV"
              }, {
                "includes" : "INVITE"
              } ]
            },
            "action" : {
              "originating" : "From",
              "route" : [ "sip:proxy1", "sip:proxy2" ]
            }
          }, {
            "id" : "INV-3",
            "next" : "b2bua"
          } ]
        }
      }
    },
    "b2bua" : {
      "triggers" : {
        "INVITE" : {
          "transitions" : [ {
            "next" : "proxy-registrar"
          } ]
        }
      }
    },
    "null" : {
      "triggers" : {
        "REGISTER" : {
          "transitions" : [ {
            "next" : "proxy-registrar"
          } ]
        },
        "PUBLISH" : {
          "transitions" : [ {
            "next" : "presence"
          } ]
        },
        "OPTIONS" : {
          "transitions" : [ {
            "next" : "options"
          } ]
        },
        "INVITE" : {
          "transitions" : [ {
            "id" : "INV-1",
            "next" : "keep-alive",
            "action" : {
              "originating" : "From"
            }
          } ]
        },
        "SUBSCRIBE" : {
          "transitions" : [ {
            "next" : "presence"
          } ]
        }
      }
    }
  }
};

// Schema registry - populated dynamically from server
let schemaRegistry = [];
const SCHEMAS_DIRECTORY = "./config/custom/vorpal/_schemas";

// Target directories - populated dynamically from server
let targetDirectories = [];
let selectedTargetDirectory = null;

let formIdCounter = 0;
let jsonEditor;
let currentData = initialData;
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

    console.log('Connecting to WebSocket:', wsUrl);
    updateWebSocketStatus('Connecting...', 'warning');

    websocket = new WebSocket(wsUrl);

    websocket.onopen = function() {
        console.log('WebSocket connected successfully');
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
        console.log('WebSocket connection closed. Code:', event.code, 'Reason:', event.reason);
        stopHeartbeat(); // Stop sending pings

        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++;
            const delay = reconnectDelay * reconnectAttempts; // Exponential backoff
            updateWebSocketStatus(`Reconnecting (${reconnectAttempts}/${maxReconnectAttempts})...`, 'warning');
            console.log(`Reconnecting in ${delay}ms...`);
            setTimeout(connectWebSocket, delay);
        } else {
            updateWebSocketStatus('Connection failed. Please reload page.', 'error');
            console.error('Max reconnection attempts reached. Please check server and reload page.');
        }
    };

    websocket.onerror = function(error) {
        console.error('WebSocket error:', error);
        console.log('WebSocket state:', websocket.readyState);
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
    console.log('Received WebSocket message:', message.type);

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
            console.log('Save successful');
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
            console.log('Received initial file_content (ignored)');
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

        default:
            console.log('Unhandled message type:', message.type);
    }
}

function sendWebSocketMessage(action, params) {
    if (websocket && websocket.readyState === WebSocket.OPEN) {
        const message = { action: action, ...params };
        websocket.send(JSON.stringify(message));
        console.log('Sent WebSocket message:', message);
    } else {
        console.error('WebSocket not connected');
        showSchemaLoadStatus('WebSocket not connected', 'error');
    }
}

// Heartbeat / Ping-Pong Functions
function startHeartbeat() {
    stopHeartbeat(); // Clear any existing intervals

    // Send ping every PING_INTERVAL milliseconds
    pingInterval = setInterval(function() {
        if (websocket && websocket.readyState === WebSocket.OPEN) {
            console.log('Sending ping...');
            sendWebSocketMessage('ping', {});

            // Set timeout to expect pong response
            pongTimeout = setTimeout(function() {
                console.error('No pong received within timeout. Connection may be dead.');
                // Close connection to trigger reconnect
                websocket.close(1000, 'No pong received');
            }, PONG_TIMEOUT);
        }
    }, PING_INTERVAL);

    console.log('Heartbeat started (ping every ' + (PING_INTERVAL/1000) + 's)');
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
    console.log('Heartbeat stopped');
}

function handlePong() {
    // Clear the pong timeout since we received a response
    if (pongTimeout) {
        clearTimeout(pongTimeout);
        pongTimeout = null;
    }

    lastPongTime = Date.now();
    console.log('Pong received - connection alive');
}

// Target Directory Functions
function requestTargetDirectories() {
    if (websocket && websocket.readyState === WebSocket.OPEN) {
        console.log('Requesting target directories...');
        sendWebSocketMessage('list_target_directories', {});
    } else {
        console.warn('WebSocket not connected, will retry target directories request');
        setTimeout(requestTargetDirectories, 1000);
    }
}

function handleTargetDirectoriesList(content) {
    try {
        targetDirectories = JSON.parse(content);
        console.log('Received', targetDirectories.length, 'target directories');
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

    console.log('Populated target dropdown with', targetDirectories.length, 'directories');
}

function onTargetDirectoryChange(path) {
    if (!path) {
        selectedTargetDirectory = null;
        return;
    }

    selectedTargetDirectory = targetDirectories.find(t => t.path === path);
    console.log('Selected target directory:', selectedTargetDirectory);
    showSchemaLoadStatus('Target: ' + (selectedTargetDirectory ? selectedTargetDirectory.displayName : 'None'), 'success');
}

// Populate the schema dropdown
// Request schema list from server
function requestSchemaList() {
    if (websocket && websocket.readyState === WebSocket.OPEN) {
        console.log('Requesting schema list from:', SCHEMAS_DIRECTORY);
        sendWebSocketMessage('list_schemas', { directory: SCHEMAS_DIRECTORY });
    } else {
        console.warn('WebSocket not connected, will retry schema list request');
        setTimeout(requestSchemaList, 1000);
    }
}

// Handle schemas list from server
function handleSchemasList(content) {
    try {
        schemaRegistry = JSON.parse(content);
        console.log('Received', schemaRegistry.length, 'schemas from server');
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

    console.log('Populated dropdown with', schemaRegistry.length, 'schemas');
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
    console.log('Requesting schema:', entry.schemaFile);
    sendWebSocketMessage('load_schema', { file: entry.schemaFile });

    // Request JSON file resolution based on selected target
    console.log('Resolving JSON file for schema:', schemaName, 'in target:', selectedTargetDirectory.path);
    sendWebSocketMessage('resolve_json_file', {
        schemaName: schemaName,
        targetDirectory: selectedTargetDirectory.path
    });
}

function handleJsonFileResolved(content) {
    try {
        const resolution = JSON.parse(content);
        console.log('JSON file resolved:', resolution);

        const entry = pendingRequests.get('schema');
        if (entry) {
            // Update entry with resolved JSON file info
            entry.jsonFile = resolution.jsonFile;
            entry.jsonFileType = resolution.jsonFileType;
            pendingRequests.set('schema', entry);

            // If JSON file exists, load it
            if (resolution.jsonFile) {
                console.log('Loading JSON data:', resolution.jsonFile);
                sendWebSocketMessage('load_json', { file: resolution.jsonFile });
            } else {
                console.log('No JSON data file for this schema in selected target');
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
            console.error('No pending schema request found');
            return;
        }

        const newSchema = JSON.parse(content);
        console.log('Schema loaded:', newSchema.title);

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
            console.log('JSON data loaded successfully');

            // Check if we loaded from a sample file
            const entry = pendingRequests.get('schema');
            if (entry && entry.jsonFileType === 'sample') {
                showSyncStatus('Loaded sample data (will save to primary location)', 'warning');
                console.log('Using sample data file:', entry.jsonFile);
            }
        } else {
            console.log('Empty JSON data received');
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

function createFormGroup(fieldSchema, title, description, path, value = null, isNested = false) {
    if (fieldSchema.$ref) {
        fieldSchema = resolveRef(fieldSchema.$ref);
    }

    if (fieldSchema.type === 'object') {
        if (fieldSchema.additionalProperties) {
            // This is a map
            return createMapGroup(fieldSchema, title, description, path, value, isNested);
        } else {
            // Regular object with defined properties
            return createObjectGroup(fieldSchema, title, description, path, value, isNested);
        }
    } else if (fieldSchema.type === 'array') {
        return createArrayGroup(fieldSchema, title, description, path, value, isNested);
    } else {
        // Simple field
        const group = document.createElement('div');
        group.className = `form-group${isNested ? ' nested' : ''}`;

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

        group.appendChild(label);

        const element = createFormElement(fieldSchema, path, value);

        if (fieldSchema.type === 'boolean') {
            const checkboxGroup = document.createElement('div');
            checkboxGroup.className = 'checkbox-group';
            checkboxGroup.appendChild(element);
            checkboxGroup.appendChild(label);
            group.innerHTML = '';
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

function createCollapsibleSection(title, description, content, hasData = false, autoCollapse = false) {
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

function createObjectGroup(fieldSchema, title, description, path, value = null, isNested = false) {
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

            const propGroup = createFormGroup(propSchema, propTitle, propDescription, propPath, propValue, true);
            content.appendChild(propGroup);
        });
    }

    return createCollapsibleSection(title || 'Object', description, content, hasData, autoCollapse);
}

function createMapGroup(fieldSchema, title, description, path, value = null, isNested = false) {
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

    const section = createCollapsibleSection(title || 'Map', description, content, hasData, autoCollapse);

    // Function to update section status when items are added/removed
    function updateSectionStatus() {
        const badge = section.querySelector('.collapsible-badge.null, .collapsible-badge.has-data');
        const hasItems = container.children.length > 0;
        badge.textContent = hasItems ? 'has data' : 'empty';
        badge.className = `collapsible-badge ${hasItems ? 'has-data' : 'null'}`;
    }

    return section;
}

function createArrayGroup(fieldSchema, title, description, path, value = null, isNested = false) {
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

    const section = createCollapsibleSection(title || 'Array', description, content, hasData, autoCollapse);

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
    const data = {};
    const form = document.getElementById('dynamicForm');

    // Get all regular form inputs
    const inputs = form.querySelectorAll('input, select, textarea');
    inputs.forEach(input => {
        const path = input.getAttribute('data-path');
        if (!path || input.classList.contains('map-key-input')) return;

        let value;
        if (input.type === 'checkbox') {
            value = input.checked;
        } else if (input.type === 'number') {
            value = input.value ? parseInt(input.value) : null;
        } else {
            value = input.value || null;
        }

        if (value !== null && value !== '') {
            setNestedValue(data, path, value);
        }
    });

    // Handle maps
    const mapContainers = form.querySelectorAll('.map-container');
    mapContainers.forEach(container => {
        const basePath = container.getAttribute('data-path');
        const mapData = {};

        const entries = container.querySelectorAll('.map-entry');
        entries.forEach(entry => {
            const keyInput = entry.querySelector('.map-key-input');
            const key = keyInput ? keyInput.value : '';

            if (key) {
                // Get value from the entry
                const valueInputs = entry.querySelectorAll('input:not(.map-key-input), select, textarea');
                if (valueInputs.length === 1) {
                    // Simple value
                    const valueInput = valueInputs[0];
                    let value;
                    if (valueInput.type === 'checkbox') {
                        value = valueInput.checked;
                    } else if (valueInput.type === 'number') {
                        value = valueInput.value ? parseInt(valueInput.value) : null;
                    } else {
                        value = valueInput.value || null;
                    }
                    if (value !== null && value !== '') {
                        mapData[key] = value;
                    }
                } else {
                    // Complex value - extract from nested structure
                    const entryData = {};
                    valueInputs.forEach(input => {
                        const inputPath = input.getAttribute('data-path');
                        if (inputPath) {
                            let value;
                            if (input.type === 'checkbox') {
                                value = input.checked;
                            } else if (input.type === 'number') {
                                value = input.value ? parseInt(input.value) : null;
                            } else {
                                value = input.value || null;
                            }

                            if (value !== null && value !== '') {
                                // Remove the base path to get relative path
                                const relativePath = inputPath.replace(new RegExp(`^${basePath}\\.${key}\\.?`), '');
                                if (relativePath) {
                                    setNestedValue(entryData, relativePath, value);
                                } else {
                                    entryData[key] = value;
                                }
                            }
                        }
                    });

                    if (Object.keys(entryData).length > 0) {
                        mapData[key] = entryData;
                    }
                }
            }
        });

        if (Object.keys(mapData).length > 0) {
            setNestedValue(data, basePath, mapData);
        }
    });

    // Handle arrays
    const arrayContainers = form.querySelectorAll('.array-container');
    arrayContainers.forEach(container => {
        const basePath = container.getAttribute('data-path');
        const arrayData = [];

        const items = container.querySelectorAll('.array-item');
        items.forEach(item => {
            const itemInputs = item.querySelectorAll('input, select, textarea');

            if (itemInputs.length === 1) {
                // Simple array item
                const input = itemInputs[0];
                let value;
                if (input.type === 'checkbox') {
                    value = input.checked;
                } else if (input.type === 'number') {
                    value = input.value ? parseInt(input.value) : null;
                } else {
                    value = input.value || null;
                }

                if (value !== null && value !== '') {
                    arrayData.push(value);
                }
            } else {
                // Complex array item
                const itemData = {};
                itemInputs.forEach(input => {
                    const inputPath = input.getAttribute('data-path');
                    if (inputPath) {
                        let value;
                        if (input.type === 'checkbox') {
                            value = input.checked;
                        } else if (input.type === 'number') {
                            value = input.value ? parseInt(input.value) : null;
                        } else {
                            value = input.value || null;
                        }

                        if (value !== null && value !== '') {
                            // Extract the property name from the path
                            const pathParts = inputPath.split('.');
                            const property = pathParts[pathParts.length - 1];
                            itemData[property] = value;
                        }
                    }
                });

                if (Object.keys(itemData).length > 0) {
                    arrayData.push(itemData);
                }
            }
        });

        if (arrayData.length > 0) {
            setNestedValue(data, basePath, arrayData);
        }
    });

    return data;
}

function setNestedValue(obj, path, value) {
    const keys = path.split('.');
    let current = obj;

    for (let i = 0; i < keys.length - 1; i++) {
        const key = keys[i];
        if (!(key in current)) {
            current[key] = {};
        }
        current = current[key];
    }

    current[keys[keys.length - 1]] = value;
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

    console.log('Saving to:', savePath);

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
        console.log('Form marked as dirty (unsaved changes)');
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
    console.log('Form marked as clean (saved)');
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

    console.log('Cloning to:', clonePath);

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
    generateForm();
    initializeJsonEditor();

    // Feature: Initialize enhancements
    loadRecentFiles(); // Load recent files from localStorage
    setupDirtyTracking(); // Set up unsaved changes tracking
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

    // Set initial JSON content
    jsonEditor.setValue(JSON.stringify(currentData, null, 2), -1);

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
            showSyncStatus('Form data automatically synced to JSON editor', 'success');
        } else if (tabName === 'form' && previousTab === 'json-tab') {
            // Switching to Form tab - sync JSON data to form
            if (jsonEditor) {
                const jsonData = JSON.parse(jsonEditor.getValue());
                currentData = jsonData;
                generateFormWithData(jsonData);
                showSyncStatus('JSON data automatically synced to form', 'success');
            }
        }
    } catch (e) {
        if (tabName === 'form' && previousTab === 'json-tab') {
            showSyncStatus('Cannot sync invalid JSON to form: ' + e.message, 'error');
            // Don't switch tabs if JSON is invalid
            return;
        } else {
            showSyncStatus('Error during auto-sync: ' + e.message, 'error');
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

            const group = createFormGroup(propSchema, propTitle, propDescription, prop, propValue);
            form.appendChild(group);
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
