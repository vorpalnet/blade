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

	function rawJsonBound(target) {
		const ta = document.createElement('textarea');
		ta.className = 'raw-op';
		ta.value = JSON.stringify(target, null, 2);
		ta.addEventListener('change', () => {
			try {
				const parsed = JSON.parse(ta.value);
				for (const k of Object.keys(target)) delete target[k];
				Object.assign(target, parsed);
				ta.style.borderColor = '';
				dirty();
			} catch (e) {
				ta.style.borderColor = 'var(--bad)';
			}
		});
		return ta;
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

	return {
		init, escapeHtml, cleanJson,
		derefNode, variantsFromAnyOf, isStringMapDef,
		titleEl, miniBtn, smallAddBtn, moveInArr,
		fieldEl, blurFieldEl, listFieldEl, selectFieldEl, checkboxFieldEl, kvFieldEl,
		renderSchemaField, renderFieldsInto,
		polyListField, polyCard, objListField, mapOfObjectsField, polyObjectField,
		rawJsonField, rawJsonBound,
		helpMini, openHelp, jsonViewer,
	};
})();
