/*
 * BLADE editor kit — shared machinery for the purpose-built config editors
 * (crud-editor, irouter-editor). Served from the portal like brand.css:
 *
 *   <script src="/blade/portal/lib/blade-editor.js"></script>
 *   <link rel="stylesheet" href="/blade/portal/lib/blade-editor.css">
 *
 * The kit is schema-driven and app-agnostic: it renders form fields, ordered
 * card lists, polymorphic type-discriminated objects, and string/object maps
 * straight from a victools-generated JSON Schema ($defs with `<Type>-N`
 * variant defs carrying `properties.type.const`). Anything it cannot resolve
 * degrades to a raw-JSON textarea — never silently dropped.
 *
 * Pages call BladeEditor.init({defs, onDirty}) once the schema is loaded;
 * every model mutation made by kit-rendered controls flows through onDirty.
 */
window.BladeEditor = (function () {
	'use strict';

	let defs = null;
	let onDirty = function () {};

	function init(opts) {
		defs = (opts && opts.defs) || null;
		onDirty = (opts && opts.onDirty) || function () {};
	}

	function dirty() { onDirty(); }

	function escapeHtml(s) {
		return String(s).replace(/[&<>"']/g, c =>
			({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
	}

	// Deep copy with empty-string and null values pruned, so unfilled form
	// fields don't land in the config as `"method": ""`.
	function cleanJson(value) {
		return JSON.parse(JSON.stringify(value, (k, v) => {
			if (v === '' || v === null) return undefined;
			return v;
		}));
	}

	// ---- schema resolution ----

	function derefNode(node) {
		if (node && node.$ref && defs) {
			const target = defs[node.$ref.replace('#/$defs/', '')] || {};
			const merged = Object.assign({}, target, node);
			delete merged.$ref;
			// A $ref that itself points at a variant def (-2 → -1) — one
			// more hop covers every shape victools emits here.
			if (target.$ref) {
				const deeper = defs[target.$ref.replace('#/$defs/', '')] || {};
				return Object.assign({}, deeper, merged);
			}
			return merged;
		}
		return node || {};
	}

	function variantsFromAnyOf(anyOf) {
		if (!anyOf || !defs) return null;
		const out = [];
		for (const ref of anyOf) {
			try {
				const v2 = defs[ref.$ref.replace('#/$defs/', '')];
				const typeConst = v2.properties.type.const;
				// Accumulate fields down the $ref chain (a variant def may
				// carry its own properties on top of the def it refs).
				const fields = {};
				let cur = v2;
				let guard = 0;
				while (cur && guard++ < 5) {
					if (cur.properties) {
						for (const [k, v] of Object.entries(cur.properties)) {
							if (!(k in fields)) fields[k] = v;
						}
					}
					cur = cur.$ref ? defs[cur.$ref.replace('#/$defs/', '')] : null;
				}
				delete fields.type;
				out.push({ type: typeConst, fields: fields });
			} catch (e) { /* skip unresolvable variant */ }
		}
		return out.length ? out : null;
	}

	function isStringMapDef(d) {
		return d && d.type === 'object' && d.additionalProperties
			&& d.additionalProperties.type === 'string' && !d.properties;
	}

	// ---- small element builders ----

	function titleEl(text) {
		const t = document.createElement('span');
		t.className = 'card-title';
		t.textContent = text;
		return t;
	}

	function miniBtn(glyph, title, onClick, danger) {
		const b = document.createElement('button');
		b.type = 'button';
		b.className = 'mini' + (danger ? ' danger' : '');
		b.title = title;
		b.textContent = glyph;
		b.addEventListener('click', onClick);
		return b;
	}

	function smallAddBtn(text, onClick) {
		const b = document.createElement('button');
		b.type = 'button';
		b.className = 'small-add';
		b.textContent = text;
		b.addEventListener('click', onClick);
		return b;
	}

	function moveInArr(arr, idx, delta) {
		const to = idx + delta;
		if (to < 0 || to >= arr.length) return;
		const [item] = arr.splice(idx, 1);
		arr.splice(to, 0, item);
		dirty();
	}

	function fieldEl(name, value, hint, onChange) {
		const wrap = document.createElement('div');
		wrap.className = 'field';
		const label = document.createElement('label');
		label.textContent = name;
		if (hint) label.title = hint;
		const input = document.createElement('input');
		input.value = value;
		if (hint) input.title = hint;
		input.addEventListener('input', () => onChange(input.value));
		wrap.appendChild(label);
		wrap.appendChild(input);
		return wrap;
	}

	// Like fieldEl, but commits on change (blur/Enter) — for edits whose
	// handler re-renders the panel and would otherwise steal focus.
	function blurFieldEl(name, value, hint, onChange) {
		const wrap = document.createElement('div');
		wrap.className = 'field';
		const label = document.createElement('label');
		label.textContent = name;
		if (hint) label.title = hint;
		const input = document.createElement('input');
		input.value = value;
		if (hint) input.title = hint;
		input.addEventListener('change', () => onChange(input.value));
		wrap.appendChild(label);
		wrap.appendChild(input);
		return wrap;
	}

	function listFieldEl(name, value, hint, suggestions, onChange) {
		const wrap = fieldEl(name, value, hint, onChange);
		const listId = 'dl-' + name + '-' + (listFieldEl._n = (listFieldEl._n || 0) + 1);
		const dl = document.createElement('datalist');
		dl.id = listId;
		dl.innerHTML = suggestions.map(s => `<option value="${escapeHtml(s)}">`).join('');
		wrap.querySelector('input').setAttribute('list', listId);
		wrap.appendChild(dl);
		return wrap;
	}

	function selectFieldEl(name, value, hint, options, onChange) {
		const wrap = document.createElement('div');
		wrap.className = 'field';
		const label = document.createElement('label');
		label.textContent = name;
		if (hint) label.title = hint;
		const sel = document.createElement('select');
		sel.innerHTML = options.map(o =>
			`<option value="${escapeHtml(o)}">${o === '' ? '(any)' : escapeHtml(o)}</option>`).join('');
		sel.value = value;
		if (hint) sel.title = hint;
		sel.addEventListener('change', () => onChange(sel.value));
		wrap.appendChild(label);
		wrap.appendChild(sel);
		return wrap;
	}

	function checkboxFieldEl(name, checked, hint, onChange) {
		const wrap = document.createElement('div');
		wrap.className = 'field';
		const label = document.createElement('label');
		label.textContent = name;
		if (hint) label.title = hint;
		const input = document.createElement('input');
		input.type = 'checkbox';
		input.checked = checked;
		if (hint) input.title = hint;
		input.addEventListener('change', () => onChange(input.checked));
		wrap.appendChild(label);
		wrap.appendChild(input);
		return wrap;
	}

	// Map(String,String) editor — name → value grid bound to obj[name].
	function kvFieldEl(name, obj, hint) {
		const wrap = document.createElement('div');
		wrap.className = 'field wide';
		const label = document.createElement('label');
		label.textContent = name;
		if (hint) label.title = hint;
		wrap.appendChild(label);

		const grid = document.createElement('div');
		grid.className = 'kv-grid';
		wrap.appendChild(grid);

		if (!obj[name]) obj[name] = {};
		const map = obj[name];

		function rebuild() {
			grid.innerHTML = '';
			for (const key of Object.keys(map)) {
				addRow(key, map[key]);
			}
		}
		function addRow(key, value) {
			const kIn = document.createElement('input');
			kIn.placeholder = 'name';
			kIn.value = key;
			const vIn = document.createElement('input');
			vIn.placeholder = 'value';
			vIn.value = value;
			const rm = document.createElement('button');
			rm.type = 'button';
			rm.className = 'remove';
			rm.textContent = '×';
			let currentKey = key;
			kIn.addEventListener('input', () => {
				const nk = kIn.value.trim();
				if (nk === currentKey) return;
				delete map[currentKey];
				if (nk) map[nk] = vIn.value;
				currentKey = nk;
				dirty();
			});
			vIn.addEventListener('input', () => {
				if (currentKey) map[currentKey] = vIn.value;
				dirty();
			});
			rm.addEventListener('click', () => {
				delete map[currentKey];
				dirty();
				rebuild();
			});
			grid.appendChild(kIn);
			grid.appendChild(vIn);
			grid.appendChild(rm);
		}
		rebuild();

		wrap.appendChild(smallAddBtn('+ add', () => addRow('', '')));
		return wrap;
	}

	// ---- schema-driven rendering ----

	/// Renders one schema property into `row`, bound to obj[name].
	function renderSchemaField(name, prop, obj, row) {
		const d = derefNode(prop);
		const hint = prop.description || d.description || '';

		// Polymorphic single object (e.g. RestConnector.authentication)
		if (d.anyOf || prop.anyOf) {
			row.appendChild(polyObjectField(name, prop.anyOf || d.anyOf, obj, hint));
			return;
		}
		if (d.enum) {
			row.appendChild(selectFieldEl(name, obj[name] || '', hint, [''].concat(d.enum),
				v => { obj[name] = v || null; dirty(); }));
			return;
		}
		if (d.type === 'boolean') {
			row.appendChild(checkboxFieldEl(name, !!obj[name], hint, v => { obj[name] = v; dirty(); }));
			return;
		}
		if (d.type === 'integer' || d.type === 'number') {
			const f = fieldEl(name, obj[name] != null ? obj[name] : '', hint, v => {
				obj[name] = (v === '') ? null : Number(v);
				dirty();
			});
			f.querySelector('input').type = 'number';
			row.appendChild(f);
			return;
		}
		if (d.type === 'string'
				|| (d.type === undefined && !d.properties && !d.additionalProperties)) {
			const f = fieldEl(name, obj[name] != null ? obj[name] : '', hint,
				v => { obj[name] = v; dirty(); });
			const input = f.querySelector('input');
			if (d.format === 'password') input.type = 'password';
			if (d['x-wide'] || prop['x-wide'] || d.format === 'textarea') f.classList.add('wide');
			row.appendChild(f);
			return;
		}
		if (d.type === 'array') {
			const items = d.items || {};
			if (items.anyOf) {
				row.appendChild(polyListField(name, items.anyOf, obj, hint));
				return;
			}
			if (items.$ref) {
				row.appendChild(objListField(name, items, obj, hint));
				return;
			}
		}
		if (isStringMapDef(d)) {
			row.appendChild(kvFieldEl(name, obj, hint));
			return;
		}
		if (d.type === 'object' && d.additionalProperties && d.additionalProperties.$ref) {
			row.appendChild(mapOfObjectsField(name, d.additionalProperties, obj, hint));
			return;
		}
		if (d.type === 'object' && d.properties) {
			row.appendChild(nestedObjectField(name, d, obj, hint));
			return;
		}
		// Unresolvable — raw JSON so nothing is ever lost.
		row.appendChild(rawJsonField(name, obj, hint));
	}

	function renderFieldsInto(fields, obj, container) {
		const row = document.createElement('div');
		row.className = 'field-row';
		for (const [name, prop] of Object.entries(fields)) {
			renderSchemaField(name, prop, obj, row);
		}
		container.appendChild(row);
	}

	/// Ordered list of polymorphic objects (selectors, nested lists).
	function polyListField(name, anyOf, obj, hint) {
		const wrap = document.createElement('div');
		wrap.className = 'field wide';
		const label = document.createElement('label');
		label.textContent = name;
		if (hint) label.title = hint;
		wrap.appendChild(label);

		const list = document.createElement('div');
		list.className = 'nested-list';
		wrap.appendChild(list);
		if (!obj[name]) obj[name] = [];
		const arr = obj[name];
		const variants = variantsFromAnyOf(anyOf);

		function rebuild() {
			list.innerHTML = '';
			arr.forEach((item, idx) => list.appendChild(polyCard(arr, item, idx, variants, rebuild)));
			list.appendChild(smallAddBtn('+ add ' + name.replace(/s$/, ''), () => {
				arr.push({ type: variants ? variants[0].type : '' });
				dirty(); rebuild();
			}));
		}
		rebuild();
		return wrap;
	}

	/// One polymorphic card: type dropdown + schema fields + reorder.
	function polyCard(arr, item, idx, variants, rebuild) {
		const card = document.createElement('div');
		card.className = 'nested-card';
		const head = document.createElement('div');
		head.className = 'card-head';

		const typeSel = document.createElement('select');
		typeSel.className = 'type-select';
		const types = variants ? variants.map(v => v.type) : [];
		typeSel.innerHTML = types.map(t => `<option value="${escapeHtml(t)}">${escapeHtml(t)}</option>`).join('');
		if (item.type && !types.includes(item.type)) {
			typeSel.innerHTML += `<option value="${escapeHtml(item.type)}">${escapeHtml(item.type)}</option>`;
		}
		typeSel.value = item.type || (types[0] || '');
		typeSel.addEventListener('change', () => {
			const keepId = item.id;
			for (const k of Object.keys(item)) delete item[k];
			item.type = typeSel.value;
			if (keepId) item.id = keepId;
			dirty(); rebuild();
		});
		head.appendChild(typeSel);
		head.appendChild(titleEl(item.id || ''));
		head.appendChild(miniBtn('▲', 'Move up', () => { moveInArr(arr, idx, -1); rebuild(); }));
		head.appendChild(miniBtn('▼', 'Move down', () => { moveInArr(arr, idx, +1); rebuild(); }));
		head.appendChild(miniBtn('⧉', 'Duplicate', () => {
			arr.splice(idx + 1, 0, JSON.parse(JSON.stringify(item)));
			dirty(); rebuild();
		}));
		head.appendChild(miniBtn('×', 'Remove', () => {
			arr.splice(idx, 1);
			dirty(); rebuild();
		}, true));
		card.appendChild(head);

		const body = document.createElement('div');
		body.className = 'card-body';
		const variant = variants ? variants.find(v => v.type === (item.type || typeSel.value)) : null;
		if (variant) renderFieldsInto(variant.fields, item, body);
		else body.appendChild(rawJsonBound(item));
		card.appendChild(body);
		return card;
	}

	/// Ordered list of plain (non-polymorphic) objects — e.g. a table
	/// connector's TranslationTable list.
	function objListField(name, items, obj, hint) {
		const wrap = document.createElement('div');
		wrap.className = 'field wide';
		const label = document.createElement('label');
		label.textContent = name;
		if (hint) label.title = hint;
		wrap.appendChild(label);

		const list = document.createElement('div');
		list.className = 'nested-list';
		wrap.appendChild(list);
		if (!obj[name]) obj[name] = [];
		const arr = obj[name];
		const itemDef = derefNode(items);

		function rebuild() {
			list.innerHTML = '';
			arr.forEach((item, idx) => {
				const card = document.createElement('div');
				card.className = 'nested-card';
				const head = document.createElement('div');
				head.className = 'card-head';
				head.appendChild(titleEl(name.replace(/s$/, '') + ' ' + (idx + 1)));
				head.appendChild(miniBtn('▲', 'Move up', () => { moveInArr(arr, idx, -1); rebuild(); }));
				head.appendChild(miniBtn('▼', 'Move down', () => { moveInArr(arr, idx, +1); rebuild(); }));
				head.appendChild(miniBtn('⧉', 'Duplicate', () => {
					arr.splice(idx + 1, 0, JSON.parse(JSON.stringify(item)));
					dirty(); rebuild();
				}));
				head.appendChild(miniBtn('×', 'Remove', () => {
					arr.splice(idx, 1);
					dirty(); rebuild();
				}, true));
				card.appendChild(head);
				const body = document.createElement('div');
				body.className = 'card-body';
				if (itemDef.properties) renderFieldsInto(itemDef.properties, item, body);
				else body.appendChild(rawJsonBound(item));
				card.appendChild(body);
				list.appendChild(card);
			});
			list.appendChild(smallAddBtn('+ add ' + name.replace(/s$/, ''), () => {
				arr.push({});
				dirty(); rebuild();
			}));
		}
		rebuild();
		return wrap;
	}

	/// Map whose values are objects — e.g. a table's `translations`
	/// (lookup key → row of variables).
	function mapOfObjectsField(name, valueRef, obj, hint) {
		const wrap = document.createElement('div');
		wrap.className = 'field wide';
		const label = document.createElement('label');
		label.textContent = name;
		if (hint) label.title = hint;
		wrap.appendChild(label);

		if (!obj[name]) obj[name] = {};
		const map = obj[name];
		const valueDef = derefNode(valueRef);

		const holder = document.createElement('div');
		wrap.appendChild(holder);

		function rebuild() {
			holder.innerHTML = '';
			for (const key of Object.keys(map)) {
				const block = document.createElement('div');
				block.className = 'map-obj';
				const keyRow = document.createElement('div');
				keyRow.className = 'map-key-row';
				const keyIn = document.createElement('input');
				keyIn.className = 'map-key';
				keyIn.value = key;
				keyIn.title = 'Lookup key — commit with Enter or by leaving the field';
				keyIn.addEventListener('change', () => {
					const nk = keyIn.value.trim();
					if (!nk || nk === key || map[nk]) { keyIn.value = key; return; }
					const rebuilt = {};
					for (const [k, v] of Object.entries(map)) {
						rebuilt[k === key ? nk : k] = v;
					}
					obj[name] = rebuilt;
					dirty();
					wrap.replaceWith(mapOfObjectsField(name, valueRef, obj, hint));
				});
				keyRow.appendChild(keyIn);
				keyRow.appendChild(miniBtn('×', 'Remove this entry', () => {
					delete map[key];
					dirty(); rebuild();
				}, true));
				block.appendChild(keyRow);

				const value = map[key];
				if (isStringMapDef(valueDef) || (!valueDef.properties && valueDef.additionalProperties)) {
					// Value is itself a bag of strings — key/value grid bound
					// through a holder; drop the grid's own label.
					const bag = { v: value };
					block.appendChild(kvFieldEl('v', bag,
						'Values this entry writes when it matches'));
					block.querySelector('.field.wide > label').remove();
				} else if (valueDef.properties) {
					renderFieldsInto(valueDef.properties, value, block);
				} else {
					block.appendChild(rawJsonBound(value));
				}
				holder.appendChild(block);
			}
			holder.appendChild(smallAddBtn('+ add entry', () => {
				let n = 1, k;
				do { k = 'key-' + n++; } while (map[k]);
				map[k] = {};
				dirty(); rebuild();
			}));
		}
		rebuild();
		return wrap;
	}

	/// Optional polymorphic single object — e.g. RestConnector.authentication.
	function polyObjectField(name, anyOf, obj, hint) {
		const wrap = document.createElement('div');
		wrap.className = 'field wide';
		const label = document.createElement('label');
		label.textContent = name;
		if (hint) label.title = hint;
		wrap.appendChild(label);

		const variants = variantsFromAnyOf(anyOf);
		const sel = document.createElement('select');
		sel.className = 'type-select';
		sel.innerHTML = '<option value="">(none)</option>'
			+ (variants || []).map(v => `<option value="${escapeHtml(v.type)}">${escapeHtml(v.type)}</option>`).join('');
		sel.value = (obj[name] && obj[name].type) || '';
		wrap.appendChild(sel);

		const body = document.createElement('div');
		wrap.appendChild(body);

		function rebuild() {
			body.innerHTML = '';
			if (!obj[name]) return;
			const variant = variants ? variants.find(v => v.type === obj[name].type) : null;
			if (variant) renderFieldsInto(variant.fields, obj[name], body);
			else body.appendChild(rawJsonBound(obj[name]));
		}
		sel.addEventListener('change', () => {
			obj[name] = sel.value ? { type: sel.value } : null;
			dirty(); rebuild();
		});
		rebuild();
		return wrap;
	}

	function nestedObjectField(name, def, obj, hint) {
		const wrap = document.createElement('div');
		wrap.className = 'field wide';
		const label = document.createElement('label');
		label.textContent = name;
		if (hint) label.title = hint;
		wrap.appendChild(label);
		if (!obj[name]) obj[name] = {};
		const body = document.createElement('div');
		body.className = 'map-obj';
		renderFieldsInto(def.properties, obj[name], body);
		wrap.appendChild(body);
		return wrap;
	}

	function rawJsonField(name, obj, hint) {
		const wrap = document.createElement('div');
		wrap.className = 'field wide';
		const label = document.createElement('label');
		label.textContent = name + ' (JSON)';
		if (hint) label.title = hint;
		wrap.appendChild(label);
		if (obj[name] == null) obj[name] = {};
		wrap.appendChild(rawJsonBound(obj[name]));
		return wrap;
	}

	// Raw-JSON boxes whose text does not parse. The model still holds the
	// last good value, so a page must not save while any is listed here:
	// see invalidRawJson().
	const invalidRaw = new Set();

	function rawJsonBound(target) {
		const box = document.createElement('div');
		const ta = document.createElement('textarea');
		ta.className = 'raw-op';
		ta.value = JSON.stringify(target, null, 2);
		const error = document.createElement('div');
		error.className = 'raw-error';
		error.setAttribute('role', 'alert');
		error.hidden = true;
		ta.addEventListener('change', () => {
			try {
				const parsed = JSON.parse(ta.value);
				for (const k of Object.keys(target)) delete target[k];
				Object.assign(target, parsed);
				ta.style.borderColor = '';
				error.hidden = true;
				invalidRaw.delete(ta);
				dirty();
			} catch (e) {
				ta.style.borderColor = 'var(--bad)';
				error.textContent = 'Not valid JSON, so not applied: ' + e.message;
				error.hidden = false;
				invalidRaw.add(ta);
			}
		});
		box.appendChild(ta);
		box.appendChild(error);
		return box;
	}

	/// How many raw-JSON boxes on the page hold text that does not parse.
	function invalidRawJson() {
		for (const ta of invalidRaw) if (!ta.isConnected) invalidRaw.delete(ta);
		return invalidRaw.size;
	}

	// ---- help dialog (both pages use the same #helpDialog / #help-<topic>
	// conventions) ----

	function helpMini(topic) {
		const b = document.createElement('button');
		b.type = 'button';
		b.className = 'mini help-mini';
		b.textContent = '?';
		b.title = 'What is this?';
		b.addEventListener('click', () => openHelp(topic));
		return b;
	}

	function openHelp(topic) {
		const dialog = document.getElementById('helpDialog');
		if (!dialog) return;
		dialog.showModal();
		const target = topic ? document.getElementById('help-' + topic) : null;
		if (target) target.scrollIntoView({ block: 'start' });
		else dialog.querySelector('.help-body').scrollTop = 0;
	}

	// ---- read-only JSON viewer ----

	/// Wires a toolbar toggle to a read-only pretty-printed view of the
	/// current draft document. `getJson` should return exactly what Save
	/// would write, so what the operator sees is what lands on disk.
	/// Returns {refresh} — call it from the page's dirty handler so an open
	/// viewer tracks edits live.
	function jsonViewer(opts) {
		const { button, panel, pre, copyButton, getJson } = opts;
		function isOpen() { return panel.style.display !== 'none'; }
		function refresh() {
			if (!isOpen()) return;
			try {
				pre.textContent = JSON.stringify(getJson(), null, 2);
			} catch (e) {
				pre.textContent = '(failed to render draft: ' + e.message + ')';
			}
		}
		button.addEventListener('click', () => {
			panel.style.display = isOpen() ? 'none' : '';
			refresh();
			if (isOpen()) panel.scrollIntoView({ block: 'nearest' });
		});
		if (copyButton) {
			copyButton.addEventListener('click', async () => {
				try {
					await navigator.clipboard.writeText(pre.textContent);
					const prev = copyButton.textContent;
					copyButton.textContent = 'copied';
					setTimeout(() => { copyButton.textContent = prev; }, 1200);
				} catch (e) {
					copyButton.textContent = 'copy failed';
				}
			});
		}
		return { refresh };
	}

	/// A non-empty list of objects that each name a non-empty `id`.
	function keyable(v) {
		return Array.isArray(v) && v.length > 0 && v.every(i => i !== null && typeof i === 'object'
			&& !Array.isArray(i) && typeof i.id === 'string' && i.id !== '');
	}

	/// The list keyed by each item's `id`, without it; a repeated id becomes
	/// `<id>-2` (or the next free), as the server reads it.
	function keyedForm(list) {
		const out = {};
		for (const item of list) {
			const copy = Object.assign({}, item);
			const id = copy.id;
			delete copy.id;
			let key = id;
			for (let k = 2; Object.prototype.hasOwnProperty.call(out, key); k++) key = id + '-' + k;
			out[key] = copy;
		}
		return out;
	}

	/// `over` merged onto `base` the way the settings loader merges a
	/// cluster or server file (Jackson `readerForUpdating` on the tree):
	/// objects merge key by key, all the way down; an array is appended to
	/// the array below it, so an overlay can add list items but never
	/// replace or remove one; anything else (an array over a missing value or
	/// a non-array included) replaces what is below it. An empty array adds
	/// nothing to an array. As v3 `ConfigFiles.merge`: where one side is an
	/// object and the other a list of objects each naming an `id`, the list
	/// is read keyed by those ids first ([keyedForm]), so the two merge by id.
	function mergeOver(base, over) {
		if (over === undefined) return base;
		const plain = v => v !== null && typeof v === 'object' && !Array.isArray(v);
		if (plain(base) && keyable(over)) over = keyedForm(over);
		else if (plain(over) && keyable(base)) base = keyedForm(base);
		if (Array.isArray(over)) return Array.isArray(base) ? base.concat(over) : over;
		if (over === null || typeof over !== 'object' || typeof base !== 'object' || base === null || Array.isArray(base)) return over;
		const out = Object.assign({}, base);
		for (const [k, v] of Object.entries(over)) out[k] = mergeOver(base[k], v);
		return out;
	}

	/// The application's configuration files, beside its editor, laid out
	/// in the order the settings loader merges them: the domain file, then
	/// each cluster, then each server under the cluster it is in
	/// (`GET …/files`, v3 `ConfigFiles` and `ClusterMembership`). A file lower
	/// down merges over the ones above it on its line. Only files that exist
	/// are listed; a cluster with no file but a server file under it is shown
	/// as a heading. Picking one calls `onPick(id)`; the editor then edits
	/// that file as it is.
	///
	/// With `mergedUrl` and `shell`, the list ends with a read-only
	/// "merged" entry: it hides `shell` and shows [#mergedView] of the
	/// current file in its place, until picked again.
	function filePicker(opts) {
		const rail = document.createElement('nav');
		rail.className = 'file-picker';
		rail.setAttribute('aria-label', 'Configuration files');
		rail.innerHTML = '<div class="file-picker-title">Files</div><div class="file-picker-body">loading…</div>';
		const body = rail.querySelector('.file-picker-body');
		const currentId = opts.current || 'domain';
		let mergedPanel = null;

		function item(f, label, depth) {
			const b = document.createElement('button');
			b.type = 'button';
			b.className = 'file-picker-item depth-' + depth;
			const current = f.id === currentId;
			if (current) {
				b.classList.add('current');
				b.setAttribute('aria-current', 'true');
			}
			b.textContent = (current ? '▸ ' : '') + label + (f.exists ? '' : ' (not written yet)');
			b.title = f.path;
			b.addEventListener('click', () => {
				if (mergedPanel) closeMerged();
				if (!current) opts.onPick(f.id);
			});
			return b;
		}
		function heading(text, depth, title) {
			const h = document.createElement('div');
			h.className = 'file-picker-group depth-' + depth;
			h.textContent = text;
			if (title) h.title = title;
			return h;
		}
		function closeMerged() {
			mergedPanel.remove();
			mergedPanel = null;
			opts.shell.style.display = '';
			mergedBtn.classList.remove('current');
			mergedBtn.removeAttribute('aria-current');
			mergedBtn.textContent = 'merged (read-only)';
		}
		const mergedBtn = document.createElement('button');
		mergedBtn.type = 'button';
		mergedBtn.className = 'file-picker-item file-picker-merged';
		mergedBtn.textContent = 'merged (read-only)';
		mergedBtn.title = 'What the current file runs: it and the files below it, merged as the server merges them';
		mergedBtn.addEventListener('click', () => {
			if (mergedPanel) { closeMerged(); return; }
			mergedPanel = mergedView({ url: opts.mergedUrl, onClose: closeMerged });
			opts.shell.parentNode.insertBefore(mergedPanel, opts.shell);
			opts.shell.style.display = 'none';
			mergedBtn.classList.add('current');
			mergedBtn.setAttribute('aria-current', 'true');
			mergedBtn.textContent = '▸ merged (read-only)';
		});

		fetch(opts.listUrl).then(r => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status))).then(files => {
			body.innerHTML = '';
			const domain = files.find(f => f.scope === 'domain');
			const clusterFiles = files.filter(f => f.scope === 'cluster');
			const servers = files.filter(f => f.scope === 'server');
			if (domain) {
				body.appendChild(heading('Domain', 0));
				body.appendChild(item(domain, domain.path.split('/').pop(), 0));
			}
			const clusters = [...new Set(clusterFiles.map(f => f.name).concat(servers.map(f => f.cluster).filter(Boolean)))].sort();
			for (const c of clusters) {
				const cf = clusterFiles.find(f => f.name === c);
				body.appendChild(heading('Cluster', 1));
				if (cf) body.appendChild(item(cf, c, 1));
				else body.appendChild(heading(c + ' · no file', 1, 'No ' + c + ' file: its servers merge over the domain file alone'));
				const inC = servers.filter(f => f.cluster === c);
				if (inC.length) {
					body.appendChild(heading('Server', 2));
					for (const f of inC) body.appendChild(item(f, f.name, 2));
				}
			}
			const loose = servers.filter(f => !f.cluster);
			if (loose.length) {
				body.appendChild(heading('Server, no cluster', 1));
				for (const f of loose) body.appendChild(item(f, f.name, 1));
			}
			if (files.length === 1) {
				const note = document.createElement('div');
				note.className = 'file-picker-note';
				note.textContent = 'domain only';
				note.title = 'A file in _clusters/<name>/ or _servers/<name>/ shows here.';
				body.appendChild(note);
			}
			if (opts.mergedUrl && opts.shell) {
				const rule = document.createElement('div');
				rule.className = 'file-picker-rule';
				body.appendChild(rule);
				body.appendChild(mergedBtn);
			}
		}).catch(e => {
			body.textContent = 'Could not list files: ' + e.message;
		});
		return rail;
	}

	/// `layers` merged as the loader merges them ([#mergeOver]), each value
	/// remembering which layer set it: `{src, kind: 'obj'|'arr'|'val', …}`.
	/// A value that replaced another keeps what it replaced in `was`.
	function traceMerge(layers) {
		function wrap(v, src) {
			if (Array.isArray(v)) return { kind: 'arr', src, items: v.map(x => wrap(x, src)) };
			if (v !== null && typeof v === 'object') {
				const keys = {};
				for (const [k, x] of Object.entries(v)) keys[k] = wrap(x, src);
				return { kind: 'obj', src, keys };
			}
			return { kind: 'val', src, v };
		}
		function over(base, v, src) {
			if (v === undefined) return base;
			if (base === undefined) return wrap(v, src);
			if (Array.isArray(v) && base.kind === 'arr') {
				return { kind: 'arr', src: base.src, items: base.items.concat(v.map(x => wrap(x, src))) };
			}
			if (v !== null && typeof v === 'object' && !Array.isArray(v) && base.kind === 'obj') {
				const keys = Object.assign({}, base.keys);
				for (const [k, x] of Object.entries(v)) keys[k] = over(base.keys[k], x, src);
				return { kind: 'obj', src: base.src, keys };
			}
			const t = wrap(v, src);
			t.was = { src: base.src, text: plainText(unwrap(base)) };
			return t;
		}
		let out;
		for (const l of layers) if (l.config !== null && l.config !== undefined) out = over(out, l.config, l.label);
		return out;
	}
	function unwrap(t) {
		if (t === undefined) return undefined;
		if (t.kind === 'arr') return t.items.map(unwrap);
		if (t.kind === 'obj') {
			const o = {};
			for (const [k, x] of Object.entries(t.keys)) o[k] = unwrap(x);
			return o;
		}
		return t.v;
	}
	function plainText(v) {
		const s = JSON.stringify(v);
		return s.length > 60 ? s.slice(0, 57) + '…' : s;
	}
	/// JSON with object keys sorted, to compare two documents by content.
	function canonical(v) {
		if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
		if (v !== null && typeof v === 'object') {
			return '{' + Object.keys(v).sort().map(k => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}';
		}
		return JSON.stringify(v === undefined ? null : v);
	}

	/// What file `mergedUrl` (`GET …/merged`) merges over: every layer below
	/// it, merged, and those files' short names (`crudlab.json`, `cluster`).
	/// `{doc: null, names: []}` for the domain file. `raw` is the same merge
	/// of the files as they are, which is what the loader builds; `doc` has
	/// each layer through `normalize(config, name)` first, when given,
	/// when given, rewrites each layer before it is merged (an editor bringing
	/// an older file form up to date).
	async function below(mergedUrl, normalize) {
		const r = await fetch(mergedUrl, { cache: 'no-store' });
		const data = r.ok ? await r.json() : null;
		const layers = (data && data.layers) ? data.layers.slice(0, -1) : [];
		let doc, raw;
		const names = [];
		for (const l of layers) {
			if (l.config == null) continue;
			const name = l.scope === 'domain' ? l.path.split('/').pop() : l.scope === 'server' ? l.name : 'cluster';
			const config = normalize ? normalize(l.config, name) : l.config;
			doc = doc === undefined ? config : mergeOver(doc, config);
			// As the loader merges them, files unchanged: what a preview runs.
			raw = raw === undefined ? l.config : mergeOver(raw, l.config);
			names.push(name);
		}
		return { doc: doc === undefined ? null : doc, raw: raw === undefined ? null : raw, names, data };
	}

	/// The read-only merged view of one file (`GET …/merged`): the files the
	/// loader merges for it, in order, then the merged document with the
	/// file that set each line beside it. A value an upper file replaced
	/// says what it was and where; list items an upper file appended carry
	/// that file's name. The page's own merge is checked against the
	/// server's (Jackson's), and a difference is shown, not hidden.
	function mergedView(opts) {
		const panel = document.createElement('section');
		panel.className = 'merged-view';
		panel.innerHTML = '<div class="merged-head"><strong title="Read-only, saved files only. Each file merges over '
			+ 'the ones before it: objects key by key, lists get items added on the end, other values replaced. '
			+ 'Nothing can be removed.">Merged</strong><span class="merged-chain"></span></div>'
			+ '<div class="merged-body">loading…</div>';
		const close = document.createElement('button');
		close.type = 'button';
		close.className = 'merged-close';
		close.textContent = '✕';
		close.title = 'Back to editing';
		close.addEventListener('click', opts.onClose);
		panel.querySelector('.merged-head').appendChild(close);
		const body = panel.querySelector('.merged-body');

		fetch(opts.url, { cache: 'no-store' }).then(r => r.json()).then(data => {
			if (data.error) throw new Error(data.error);
			body.innerHTML = '';
			const layers = data.layers.map(l => Object.assign({}, l, {
				label: l.scope === 'server' ? l.name : l.scope,
			}));

			const chain = panel.querySelector('.merged-chain');
			layers.forEach((l, i) => {
				if (i) chain.appendChild(document.createTextNode(' → '));
				const tag = document.createElement('span');
				tag.className = 'merged-tag';
				tag.textContent = l.label + (l.exists ? '' : ' (no file)');
				tag.title = l.path;
				chain.appendChild(tag);
			});

			// Each server whose files include this one: does it run what its
			// files (domain, its cluster, itself) merge to?
			const running = data.running || [];
			const same = running.filter(r => r.matches).map(r => r.server);
			if (same.length) {
				const row = document.createElement('div');
				row.className = 'merged-running';
				row.textContent = '✓ running as filed: ' + same.join(', ');
				row.title = 'Each server\'s configuration (CurrentJson) holds every value its own files merge to.';
				body.appendChild(row);
			}
			for (const r of running.filter(r => !r.matches)) {
				const row = document.createElement('div');
				row.className = 'merged-running differs';
				row.textContent = '✕ ' + r.server + ' differs at ' + r.differences.join(', ');
				row.title = 'Not reloaded since a file changed, or it could not load one. Publish reloads it.';
				body.appendChild(row);
			}

			const traced = traceMerge(layers);
			if (traced === undefined) {
				body.appendChild(Object.assign(document.createElement('p'), { textContent: 'No file is written yet.' }));
				return;
			}
			if (canonical(unwrap(traced)) !== canonical(data.merged)) {
				const warn = document.createElement('p');
				warn.className = 'merged-warn';
				warn.textContent = '✕ This page merged the files differently from the server. What the server builds '
					+ 'is below, without the per-line file names.';
				body.appendChild(warn);
				const pre = document.createElement('pre');
				pre.className = 'merged-lines';
				pre.textContent = JSON.stringify(data.merged, null, 2);
				body.appendChild(pre);
				return;
			}

			const top = layers[0].label;
			// True when an upper file set or replaced something in `t`.
			function touched(t) {
				if (t.src !== top || t.was) return true;
				if (t.kind === 'obj') return Object.values(t.keys).some(touched);
				if (t.kind === 'arr') return t.items.some(touched);
				return false;
			}
			// Folded: a block only the domain file sets is one line, to open
			// on demand, so what the upper files change stands out.
			let folded = layers.length > 1;
			const open = new Set();
			const bar = document.createElement('div');
			bar.className = 'merged-bar';
			const foldBtn = document.createElement('button');
			foldBtn.type = 'button';
			bar.appendChild(foldBtn);
			body.appendChild(bar);
			const table = document.createElement('div');
			table.className = 'merged-lines';
			body.appendChild(table);
			function render() {
				foldBtn.textContent = folded ? 'Show every line' : 'Fold what only ' + top + ' sets';
				table.innerHTML = '';
				emit(traced, 0, '', '', '$');
			}
			foldBtn.addEventListener('click', () => { folded = !folded; open.clear(); render(); });
			function line(src, indent, text, note) {
				const row = document.createElement('div');
				row.className = 'merged-line' + (src && src !== top ? ' upper' : '');
				const g = document.createElement('span');
				g.className = 'merged-gutter';
				g.textContent = src || '';
				const c = document.createElement('span');
				c.className = 'merged-code';
				c.textContent = '  '.repeat(indent) + text;
				row.appendChild(g);
				row.appendChild(c);
				if (note) {
					const n = document.createElement('span');
					n.className = 'merged-note';
					n.textContent = note;
					row.appendChild(n);
				}
				table.appendChild(row);
			}
			function emit(t, indent, prefix, comma, path) {
				const note = t.was ? 'replaces ' + t.was.text + ' from ' + t.was.src : '';
				if (folded && path !== '$' && t.kind !== 'val' && !touched(t) && !open.has(path)) {
					const n = t.kind === 'obj' ? Object.keys(t.keys).length : t.items.length;
					const id = t.kind === 'obj' && t.keys.id && t.keys.id.kind === 'val' ? t.keys.id.v : null;
					const what = id != null ? '{ "id": ' + JSON.stringify(id) + ', … }'
						: t.kind === 'obj' ? '{ … ' + n + ' key' + (n === 1 ? '' : 's') + ' }'
						: '[ … ' + n + ' item' + (n === 1 ? '' : 's') + ' ]';
					line(t.src, indent, prefix + what + comma, '');
					const row = table.lastChild;
					row.classList.add('folded');
					row.title = 'Click to open';
					row.addEventListener('click', () => { open.add(path); render(); });
					return;
				}
				if (t.kind === 'obj') {
					const keys = Object.keys(t.keys);
					if (!keys.length) { line(t.src, indent, prefix + '{}' + comma, note); return; }
					line(t.src, indent, prefix + '{', note);
					keys.forEach((k, i) => emit(t.keys[k], indent + 1, JSON.stringify(k) + ': ', i < keys.length - 1 ? ',' : '', path + '.' + k));
					line('', indent, '}' + comma);
				} else if (t.kind === 'arr') {
					if (!t.items.length) { line(t.src, indent, prefix + '[]' + comma, note); return; }
					line(t.src, indent, prefix + '[', note);
					t.items.forEach((x, i) => emit(x, indent + 1, '', i < t.items.length - 1 ? ',' : '', path + '[' + i + ']'));
					line('', indent, ']' + comma);
				} else {
					line(t.src, indent, prefix + JSON.stringify(t.v) + comma, note);
				}
			}
			render();
		}).catch(e => {
			body.textContent = 'Could not build the merged view: ' + e.message;
		});
		return panel;
	}

	return {
		init, escapeHtml, cleanJson,
		derefNode, variantsFromAnyOf, isStringMapDef,
		titleEl, miniBtn, smallAddBtn, moveInArr,
		fieldEl, blurFieldEl, listFieldEl, selectFieldEl, checkboxFieldEl, kvFieldEl,
		renderSchemaField, renderFieldsInto,
		polyListField, polyCard, objListField, mapOfObjectsField, polyObjectField,
		rawJsonField, rawJsonBound, invalidRawJson,
		helpMini, openHelp, jsonViewer, filePicker, mergeOver, mergedView, below,
	};
})();
