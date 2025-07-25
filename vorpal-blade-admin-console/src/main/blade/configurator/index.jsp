<%@ page import="org.vorpal.blade.applications.console.config.*"%>
<%@ page import="java.util.*"%>
<%@ page import="java.security.*"%>
<%@ page import="org.vorpal.blade.applications.console.config.test.*"%>
<%@ page import="java.util.*"%>
<%@ page import="java.nio.file.*"%>
<%
StringBuilder appsHtml = new StringBuilder();

Set<String> apps = ConfigurationMonitor.queryApps();

// ServletContext sc = getServletContext();
Principal principal = request.getUserPrincipal();
String user = principal.getName();

for (String app : apps) {
	System.out.println("Running apps: " + app);

	appsHtml.append("<li class=\"nav-item\"><a href=\"./index.jsp?configType=Domain&app=" + app
	+ "\" class=\"nav-link\" target=\"content_iframe\">" + app + "</a></li>\n");

}

System.out.println("Test #2");

String exception = "No Exceptions.";
String app = request.getParameter("app");
String configType = request.getParameter("configType");
System.out.println("app=" + app + ", configType=" + configType);

String jsonData = request.getParameter("jsonData");

System.out.println("Form submitted jsonData: ");
System.out.println(jsonData);

ConfigHelper cfgHelper = new ConfigHelper(app, configType);
cfgHelper.getSettings();

String jsonSchema = cfgHelper.loadFile("SCHEMA");
String jsonSample = cfgHelper.loadFile("SAMPLE");

if (jsonData != null && jsonData.length() > 0) {
	System.out.println("Saving submitted form data locally..." + jsonData.length());
	System.out.println(jsonData);
	cfgHelper.saveFileLocally(configType, jsonData);
} else {

	try {

		System.out.println("Loading json data for type " + configType);
		jsonData = cfgHelper.loadFile(configType);

		if (jsonData == null || jsonData.length() == 0) {
	System.out.println("No json data found, using sample.");
	jsonData = jsonSample;
		}
	} catch (Exception ex) {
		System.out.println("Exception loading jasonData: " + ex.getMessage());
		jsonData = jsonSample;
	}

}

cfgHelper.closeSettings();

Set<String> dirContents = cfgHelper.listFilesUsingFilesList("config/custom/vorpal/");
%>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Dynamic JSON Schema Form Generator</title>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/ace.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/mode-json.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-monokai.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-github.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-tomorrow.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-twilight.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-textmate.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-solarized_dark.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-solarized_light.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-chrome.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-clouds.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-crimson_editor.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-eclipse.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-tomorrow_night.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-tomorrow_night_blue.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-tomorrow_night_bright.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-tomorrow_night_eighties.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-dracula.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/ace/1.32.8/theme-cobalt.js"></script>
<style>
* {
	box-sizing: border-box;
}

body {
	font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
		sans-serif;
	max-width: 1200px;
	margin: 0 auto;
	padding: 20px;
	background: #f8fafc;
	line-height: 1.6;
}

.container {
	background: white;
	border-radius: 12px;
	box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);
	padding: 30px;
}

h1 {
	color: #1e293b;
	margin-bottom: 30px;
	font-size: 2rem;
	font-weight: 600;
}

.form-group {
	margin-bottom: 20px;
}

.form-group.nested {
	background: #f8fafc;
	border: 1px solid #e2e8f0;
	border-radius: 8px;
	padding: 20px;
	margin-left: 20px;
}

.form-group.array {
	border: 1px solid #cbd5e1;
	border-radius: 8px;
	padding: 15px;
	background: #f1f5f9;
}

.collapsible-section {
	border: 1px solid #e2e8f0;
	border-radius: 8px;
	margin-bottom: 15px;
	background: white;
	overflow: hidden;
}

.collapsible-header {
	display: flex;
	justify-content: space-between;
	align-items: center;
	padding: 15px 20px;
	background: #f8fafc;
	border-bottom: 1px solid #e2e8f0;
	cursor: pointer;
	user-select: none;
	transition: background-color 0.2s;
}

.collapsible-header:hover {
	background: #f1f5f9;
}

.collapsible-header.collapsed {
	border-bottom: none;
}

.collapsible-title {
	display: flex;
	align-items: center;
	gap: 10px;
	font-weight: 600;
	color: #374151;
}

.collapsible-arrow {
	transition: transform 0.2s;
	font-size: 12px;
	color: #6b7280;
}

.collapsible-arrow.collapsed {
	transform: rotate(-90deg);
}

.collapsible-content {
	padding: 20px;
	transition: max-height 0.3s ease-out;
	overflow: hidden;
}

.collapsible-content.collapsed {
	max-height: 0;
	padding: 0 20px;
}

.collapsible-badge {
	background: #e5e7eb;
	color: #6b7280;
	padding: 2px 8px;
	border-radius: 12px;
	font-size: 11px;
	font-weight: 500;
}

.collapsible-badge.null {
	background: #fef3c7;
	color: #92400e;
}

.collapsible-badge.has-data {
	background: #d1fae5;
	color: #065f46;
}

.tabs {
	display: flex;
	border-bottom: 2px solid #e5e7eb;
	margin-bottom: 20px;
	background: #f8fafc;
	border-radius: 8px 8px 0 0;
	overflow: hidden;
}

.tab {
	flex: 1;
	padding: 15px 20px;
	background: #e5e7eb;
	color: #6b7280;
	cursor: pointer;
	font-weight: 500;
	transition: all 0.2s;
	border: none;
	font-size: 14px;
}

.tab:hover {
	background: #d1d5db;
	color: #374151;
}

.tab.active {
	background: white;
	color: #1f2937;
	box-shadow: 0 -2px 0 #3b82f6;
}

.tab-content {
	display: none;
}

.tab-content.active {
	display: block;
}

.json-editor-container {
	height: 600px;
	border: 1px solid #d1d5db;
	border-radius: 8px;
	overflow: hidden;
}

.json-controls {
	display: flex;
	gap: 10px;
	margin-bottom: 15px;
	flex-wrap: wrap;
}

.sync-status {
	padding: 8px 12px;
	border-radius: 6px;
	font-size: 12px;
	font-weight: 500;
	display: inline-flex;
	align-items: center;
	gap: 6px;
}

.sync-status.success {
	background: #d1fae5;
	color: #065f46;
}

.sync-status.error {
	background: #fee2e2;
	color: #991b1b;
}

.sync-status.warning {
	background: #fef3c7;
	color: #92400e;
}

.theme-selector {
	display: flex;
	align-items: center;
	gap: 8px;
}

.theme-selector label {
	font-size: 14px;
	font-weight: 500;
	color: #374151;
	margin: 0;
}

.theme-selector select {
	padding: 6px 10px;
	border: 1px solid #d1d5db;
	border-radius: 4px;
	font-size: 14px;
	background: white;
	min-width: 140px;
}

.floating-controls {
	position: fixed;
	bottom: 20px;
	right: 20px;
	background: white;
	border: 1px solid #d1d5db;
	border-radius: 12px;
	padding: 15px 20px;
	box-shadow: 0 10px 25px rgba(0, 0, 0, 0.15);
	display: flex;
	gap: 10px;
	z-index: 1000;
	backdrop-filter: blur(10px);
}

.floating-controls .btn {
	font-size: 14px;
	font-weight: 600;
}

label {
	display: block;
	margin-bottom: 5px;
	font-weight: 500;
	color: #374151;
}

.label-required {
	color: #dc2626;
}

input, select, textarea {
	width: 100%;
	padding: 10px 12px;
	border: 1px solid #d1d5db;
	border-radius: 6px;
	font-size: 14px;
	transition: border-color 0.2s, box-shadow 0.2s;
}

input:focus, select:focus, textarea:focus {
	outline: none;
	border-color: #3b82f6;
	box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.checkbox-group {
	display: flex;
	align-items: center;
	gap: 6px;
}

.checkbox-group input[type="checkbox"] {
	width: auto;
}

.array-header {
	display: flex;
	justify-content: space-between;
	align-items: center;
	margin-bottom: 15px;
}

.array-title {
	font-weight: 600;
	color: #374151;
}

.btn {
	padding: 8px 16px;
	border: none;
	border-radius: 6px;
	cursor: pointer;
	font-size: 14px;
	font-weight: 500;
	transition: all 0.2s;
}

.btn-primary {
	background: #3b82f6;
	color: white;
}

.btn-primary:hover {
	background: #2563eb;
}

.btn-danger {
	background: #dc2626;
	color: white;
}

.btn-danger:hover {
	background: #b91c1c;
}

.btn-secondary {
	background: #6b7280;
	color: white;
}

.btn-icon {
	padding: 6px 8px;
	border: none;
	border-radius: 4px;
	cursor: pointer;
	font-size: 14px;
	font-weight: 500;
	transition: all 0.2s;
	display: inline-flex;
	align-items: center;
	justify-content: center;
	min-width: 30px;
	height: 30px;
}

.btn-icon.btn-primary {
	background: #3b82f6;
	color: white;
}

.btn-icon.btn-primary:hover {
	background: #2563eb;
}

.btn-icon.btn-danger {
	background: #dc2626;
	color: white;
}

.btn-icon.btn-danger:hover {
	background: #b91c1c;
}

.array-item {
	background: white;
	border: 1px solid #e5e7eb;
	border-radius: 6px;
	padding: 15px;
	margin-bottom: 10px;
	position: relative;
}

.array-item-header {
	display: flex;
	justify-content: space-between;
	align-items: center;
	margin-bottom: 10px;
}

.array-item-title {
	font-weight: 500;
	color: #4b5563;
}

.map-entry {
	background: white;
	border: 1px solid #e5e7eb;
	border-radius: 6px;
	padding: 15px;
	margin-bottom: 10px;
}

.map-key-input {
	background: #fef3c7;
	border-color: #f59e0b;
	margin-bottom: 10px;
}

.description {
	font-size: 11px;
	color: #6b7280;
	margin-top: 3px;
	font-style: italic;
	line-height: 1.4;
}

.export-section {
	margin-top: 40px;
	padding-top: 30px;
	border-top: 1px solid #e5e7eb;
}

.output {
	background: #1f2937;
	color: #f9fafb;
	padding: 20px;
	border-radius: 8px;
	font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
	font-size: 13px;
	white-space: pre-wrap;
	max-height: 400px;
	overflow-y: auto;
}
</style>
</head>
<body>
	<div class="container">
		<h1><%=app%>
		</h1>

		<div class="tabs">
			<button class="tab active" onclick="switchTab('form')">Form Editor</button>
			<button class="tab" onclick="switchTab('json')">JSON Editor</button>
		</div>

		<div id="form-tab" class="tab-content active">
			<div style="display: flex; gap: 10px; margin-bottom: 20px; flex-wrap: wrap;">
				<button type="button" class="btn btn-secondary" onclick="resetForm()">Reset Form</button>
				<button type="button" class="btn btn-secondary" onclick="expandAll()">Expand All</button>
				<button type="button" class="btn btn-secondary" onclick="collapseAll()">Collapse All</button>
				<button type="button" class="btn btn-secondary" onclick="collapseEmpty()">Collapse Empty</button>
			</div>

			<form id="dynamicForm">
				<!-- Form will be generated here -->
			</form>
		</div>

		<div id="json-tab" class="tab-content">
			<div class="json-controls">
				<button type="button" class="btn btn-secondary" onclick="formatJson()">Format JSON</button>
				<button type="button" class="btn btn-secondary" onclick="validateJson()">Validate</button>
				<button type="button" class="btn btn-secondary" onclick="resetJson()">Reset JSON</button>
				<div class="theme-selector">
					<label for="theme-select">Theme:</label> <select id="theme-select" onchange="changeTheme(this.value)">
						<option value="monokai">Monokai</option>
						<option value="github">GitHub</option>
						<option value="tomorrow">Tomorrow</option>
						<option value="twilight">Twilight</option>
						<option value="textmate">TextMate</option>
						<option value="solarized_dark">Solarized Dark</option>
						<option value="solarized_light">Solarized Light</option>
						<option value="chrome">Chrome</option>
						<option value="clouds">Clouds</option>
						<option value="crimson_editor">Crimson Editor</option>
						<option value="eclipse" selected>Eclipse</option>
						<option value="tomorrow_night">Tomorrow Night</option>
						<option value="tomorrow_night_blue">Tomorrow Night Blue</option>
						<option value="tomorrow_night_bright">Tomorrow Night Bright</option>
						<option value="tomorrow_night_eighties">Tomorrow Night 80s</option>
						<option value="dracula">Dracula</option>
						<option value="cobalt">Cobalt</option>
					</select>
				</div>
				<div id="sync-status" class="sync-status" style="display: none;"></div>
			</div>

			<div id="json-editor" class="json-editor-container"></div>
		</div>

		<div class="floating-controls">
			<button type="button" class="btn btn-primary" onclick="exportData()">Export JSON</button>
			<button type="button" class="btn btn-secondary" onclick="importData()">Import JSON</button>
		</div>
	</div>

	<script>
        // JSON Schema and Data (loaded from the provided files)
        const schema = <%=jsonSchema%>;


        const initialData = <%=jsonData%>;

        let formIdCounter = 0;
        let jsonEditor;
        let currentData = initialData;
        let currentTheme = 'eclipse';

        function resolveRef(ref) {
            if (ref.startsWith('#/definitions/')) {
                const defName = ref.substring('#/definitions/'.length);
                return schema.definitions[defName];
            }
            return null;
        }

        function createFormElement(fieldSchema, path, value = null, isMapKey = false) {
            const id = `field_\${formIdCounter++}`;
            
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
                group.appendChild(label);

                if (description) {
                    const desc = document.createElement('div');
                    desc.className = 'description';
                    desc.textContent = description;
                    group.appendChild(desc);
                }

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
            removeBtn.onclick = () => entry.remove();
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
            removeBtn.onclick = () => item.remove();
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

        function exportData() {
            let data;
            const activeTab = document.querySelector('.tab-content.active').id;
            
            if (activeTab === 'form-tab') {
                data = getFormData();
            } else {
                try {
                    data = JSON.parse(jsonEditor.getValue());
                } catch (e) {
                    showSyncStatus('Cannot export invalid JSON', 'error');
                    return;
                }
            }
            
            // Create downloadable file
            const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'app-router-config.json';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            
            showSyncStatus('Data exported successfully', 'success');
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

        // Initialize the form
        generateForm();
        initializeJsonEditor();

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

			console.log("tabName="+tabName+", previousTab="+previousTab);

            
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

        function syncFormToJson() {
            try {
                const formData = getFormData();
                currentData = formData;
                jsonEditor.setValue(JSON.stringify(formData, null, 2), -1);
                showSyncStatus('Form data synced to JSON editor', 'success');
            } catch (e) {
                showSyncStatus('Error syncing form to JSON: ' + e.message, 'error');
            }
        }

        function syncJsonToForm() {
            try {
                const jsonData = JSON.parse(jsonEditor.getValue());
                currentData = jsonData;
                generateFormWithData(jsonData);
                showSyncStatus('JSON data synced to form', 'success');
            } catch (e) {
                showSyncStatus('Error syncing JSON to form: ' + e.message, 'error');
                return;
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

        function importData() {
            const input = document.createElement('input');
            input.type = 'file';
            input.accept = '.json';
            
            input.onchange = (e) => {
                const file = e.target.files[0];
                if (file) {
                    const reader = new FileReader();
                    reader.onload = (e) => {
                        try {
                            const importedData = JSON.parse(e.target.result);
                            currentData = importedData;
                            generateFormWithData(importedData);
                            jsonEditor.setValue(JSON.stringify(importedData, null, 2), -1);
                            showSyncStatus('Data imported successfully', 'success');
                        } catch (error) {
                            showSyncStatus('Error importing JSON: ' + error.message, 'error');
                        }
                    };
                    reader.readAsText(file);
                }
            };
            
            input.click();
        }
    </script>
</body>
</html>