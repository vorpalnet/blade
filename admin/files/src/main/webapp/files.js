/* BLADE Files — domain-file editor client.
 *
 * Talks to the JAX-RS API rooted at ./api (see FilesAPI):
 *   GET  api/files                       → registry + on-disk status
 *   GET  api/content?path=...            → file content (text/plain)
 *   POST api/content?path=...            → save (body = content); 400 on bad input
 *   GET  api/versions?path=...           → backup list
 *   POST api/restore?path=...&timestamp= → restore a backup
 */
(function () {
	'use strict';

	var API = 'api';
	var ACE_MODE = { XML: 'ace/mode/xml', JSON: 'ace/mode/json', PROPERTIES: 'ace/mode/properties', TEXT: 'ace/mode/text' };

	var editor, registry = [], current = null, dirty = false;

	var els = {
		status: document.getElementById('status'),
		fileSelect: document.getElementById('file-select'),
		fileMeta: document.getElementById('file-meta'),
		versionSelect: document.getElementById('version-select'),
		restoreBtn: document.getElementById('restore-btn'),
		reloadBtn: document.getElementById('reload-btn'),
		saveBtn: document.getElementById('save-btn'),
		message: document.getElementById('message')
	};

	function setStatus(text) { els.status.textContent = text; }

	function showMessage(text, isError) {
		els.message.textContent = text;
		els.message.hidden = false;
		els.message.classList.toggle('files-error', !!isError);
		els.message.classList.toggle('files-ok', !isError);
	}

	function clearMessage() { els.message.hidden = true; }

	function setDirty(d) {
		dirty = d;
		els.saveBtn.disabled = !d || !current;
		setStatus(d ? 'Unsaved changes' : (current ? 'Saved' : 'Ready'));
	}

	function fmtBytes(n) {
		if (n == null) { return ''; }
		if (n < 1024) { return n + ' B'; }
		if (n < 1024 * 1024) { return (n / 1024).toFixed(1) + ' KB'; }
		return (n / 1024 / 1024).toFixed(1) + ' MB';
	}

	function fmtTime(ms) {
		try { return new Date(ms).toLocaleString(); } catch (e) { return String(ms); }
	}

	function initEditor() {
		editor = ace.edit('editor');
		editor.setTheme('ace/theme/eclipse');
		editor.session.setMode('ace/mode/text');
		editor.setOptions({ fontSize: '13px', showPrintMargin: false, useWorker: false });
		editor.on('change', function () { if (current) { setDirty(true); } });
	}

	function loadRegistry() {
		setStatus('Loading…');
		return fetch(API + '/files', { headers: { 'Accept': 'application/json' } })
			.then(function (r) { return r.json(); })
			.then(function (entries) {
				registry = entries || [];
				els.fileSelect.innerHTML = '';
				if (!registry.length) {
					var opt = document.createElement('option');
					opt.textContent = '— no files registered —';
					opt.value = '';
					els.fileSelect.appendChild(opt);
					setStatus('Empty registry');
					showMessage('No editable files are registered. Add entries in the Configurator under the "files" app (config/custom/vorpal/files.json).', false);
					return;
				}
				registry.forEach(function (e, i) {
					var opt = document.createElement('option');
					opt.value = String(i);
					opt.textContent = e.label + (e.exists ? '' : '  (not present)');
					els.fileSelect.appendChild(opt);
				});
				setStatus('Ready');
				selectIndex(0);
			})
			.catch(function (err) {
				setStatus('Error');
				showMessage('Could not load the file registry: ' + err, true);
			});
	}

	function selectIndex(i) {
		var entry = registry[i];
		if (!entry) { return; }
		current = entry;
		clearMessage();
		editor.session.setMode(ACE_MODE[entry.type] || 'ace/mode/text');
		els.fileMeta.textContent = entry.path + ' · ' + entry.type
			+ (entry.exists ? ' · ' + fmtBytes(entry.sizeBytes) : ' · new file');
		loadContent(entry);
		loadVersions(entry);
	}

	function loadContent(entry) {
		fetch(API + '/content?path=' + encodeURIComponent(entry.path))
			.then(function (r) {
				if (!r.ok) { throw new Error('HTTP ' + r.status); }
				return r.text();
			})
			.then(function (text) {
				editor.setValue(text, -1);
				setDirty(false);
			})
			.catch(function (err) {
				showMessage('Could not read file: ' + err, true);
			});
	}

	function loadVersions(entry) {
		els.versionSelect.innerHTML = '';
		els.restoreBtn.disabled = true;
		fetch(API + '/versions?path=' + encodeURIComponent(entry.path), { headers: { 'Accept': 'application/json' } })
			.then(function (r) { return r.json(); })
			.then(function (versions) {
				var placeholder = document.createElement('option');
				placeholder.value = '';
				placeholder.textContent = versions.length ? 'Versions (' + versions.length + ')…' : 'No backups';
				els.versionSelect.appendChild(placeholder);
				versions.forEach(function (v) {
					var opt = document.createElement('option');
					opt.value = String(v.timestamp);
					opt.textContent = fmtTime(v.timestamp) + ' · ' + fmtBytes(v.sizeBytes);
					els.versionSelect.appendChild(opt);
				});
			})
			.catch(function () { /* versions are best-effort */ });
	}

	function save() {
		if (!current) { return; }
		setStatus('Saving…');
		fetch(API + '/content?path=' + encodeURIComponent(current.path), {
			method: 'POST',
			headers: { 'Content-Type': 'text/plain' },
			body: editor.getValue()
		})
			.then(function (r) { return r.json().then(function (j) { return { ok: r.ok, body: j }; }); })
			.then(function (res) {
				if (res.ok && res.body.ok) {
					setDirty(false);
					showMessage(res.body.message || 'Saved.', false);
					loadVersions(current);
				} else {
					setStatus('Rejected');
					showMessage(res.body.message || 'Save rejected.', true);
				}
			})
			.catch(function (err) {
				setStatus('Error');
				showMessage('Save failed: ' + err, true);
			});
	}

	function restore() {
		var ts = els.versionSelect.value;
		if (!current || !ts) { return; }
		if (!window.confirm('Restore this backup? The current content is backed up first.')) { return; }
		setStatus('Restoring…');
		fetch(API + '/restore?path=' + encodeURIComponent(current.path) + '&timestamp=' + encodeURIComponent(ts), {
			method: 'POST'
		})
			.then(function (r) {
				if (!r.ok) { throw new Error('HTTP ' + r.status); }
				return r.text();
			})
			.then(function (text) {
				editor.setValue(text, -1);
				setDirty(false);
				showMessage('Restored backup from ' + fmtTime(Number(ts)) + '.', false);
				loadVersions(current);
			})
			.catch(function (err) {
				setStatus('Error');
				showMessage('Restore failed: ' + err, true);
			});
	}

	function guardUnsaved(action) {
		if (dirty && !window.confirm('Discard unsaved changes?')) { return; }
		action();
	}

	// Wire up
	els.fileSelect.addEventListener('change', function () {
		guardUnsaved(function () { selectIndex(Number(els.fileSelect.value)); });
	});
	els.reloadBtn.addEventListener('click', function () {
		guardUnsaved(function () { if (current) { loadContent(current); } });
	});
	els.saveBtn.addEventListener('click', save);
	els.restoreBtn.addEventListener('click', restore);
	els.versionSelect.addEventListener('change', function () {
		els.restoreBtn.disabled = !els.versionSelect.value;
	});
	window.addEventListener('beforeunload', function (e) {
		if (dirty) { e.preventDefault(); e.returnValue = ''; }
	});

	initEditor();
	loadRegistry();
})();
