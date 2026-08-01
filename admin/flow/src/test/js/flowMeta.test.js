// flowMeta — the model-derived dropdown values shared by transition.html,
// flowTasks.js and flowPlans.js.
//
// The Java side (FsmarMetaTest) checks the values match the model. This checks
// the browser side actually uses them: that a failed fetch degrades to the
// built-in list rather than emptying every dropdown, that served values win,
// and that fillSelect preserves a selection instead of silently resetting a
// transition's region to blank.

var h = require('./harness');
var test = h.test;
var assert = h.assert;

var FALLBACK_METHODS = ['INVITE', 'REGISTER', 'OPTIONS', 'SUBSCRIBE',
	'PUBLISH', 'MESSAGE', 'NOTIFY', 'REFER'];

test('falls back to built-in values when /fsmarMeta fails', function() {
	var m = h.loadModule('js/flowMeta.js', { fail: true });
	m.jq.ready();

	assert.deepStrictEqual(m.window.flowMeta.methods(), FALLBACK_METHODS);
	assert.deepStrictEqual(m.window.flowMeta.regions(),
		['ORIGINATING', 'TERMINATING', 'NEUTRAL']);
	assert.deepStrictEqual(m.window.flowMeta.selectorTypes(),
		['attribute', 'json', 'xml', 'sdp', 'regex', 'table']);
});

test('served values replace the fallback', function() {
	var m = h.loadModule('js/flowMeta.js', {
		ok: {
			methods: ['INVITE', 'REFER'],
			regions: ['NEUTRAL'],
			routeModifiers: ['ROUTE'],
			selectorTypes: ['attribute', 'table']
		}
	});
	m.jq.ready();

	assert.deepStrictEqual(m.window.flowMeta.methods(), ['INVITE', 'REFER']);
	assert.deepStrictEqual(m.window.flowMeta.selectorTypes(), ['attribute', 'table']);
});

test('an empty served list does not blank a dropdown', function() {
	// A server that answers with nothing must not leave the editor unable to
	// pick a SIP method.
	var m = h.loadModule('js/flowMeta.js', {
		ok: { methods: [], regions: [], routeModifiers: [], selectorTypes: [] }
	});
	m.jq.ready();

	assert.deepStrictEqual(m.window.flowMeta.methods(), FALLBACK_METHODS);
});

test('a malformed response does not blank a dropdown', function() {
	var m = h.loadModule('js/flowMeta.js', { ok: 'not an object' });
	m.jq.ready();

	assert.deepStrictEqual(m.window.flowMeta.methods(), FALLBACK_METHODS);
});

test('ready() callbacks run once values are in', function() {
	var m = h.loadModule('js/flowMeta.js', { ok: { methods: ['INVITE'] } });
	var seen = null;
	m.window.flowMeta.ready(function(meta) { seen = meta.methods; });

	assert.strictEqual(seen, null, 'must not fire before the fetch settles');
	m.jq.ready();
	assert.deepStrictEqual(seen, ['INVITE']);
});

test('ready() fires immediately once already loaded', function() {
	var m = h.loadModule('js/flowMeta.js', { fail: true });
	m.jq.ready();

	var fired = false;
	m.window.flowMeta.ready(function() { fired = true; });
	assert.strictEqual(fired, true);
});

test('fillSelect populates options in order', function() {
	var m = h.loadModule('js/flowMeta.js', { fail: true });
	m.jq.ready();
	var sel = h.fakeSelect();

	m.window.flowMeta.fillSelect(sel, ['A', 'B', 'C'], false);

	assert.deepStrictEqual(sel._state.options.map(function(o) { return o.value; }),
		['A', 'B', 'C']);
});

test('fillSelect adds a blank first option when asked', function() {
	// Region and routeModifier use blank to mean "container default"
	// (NEUTRAL / ROUTE); losing it would force an explicit value.
	var m = h.loadModule('js/flowMeta.js', { fail: true });
	m.jq.ready();
	var sel = h.fakeSelect();

	m.window.flowMeta.fillSelect(sel, ['ORIGINATING'], true);

	assert.strictEqual(sel._state.options[0].value, '');
	assert.strictEqual(sel._state.options.length, 2);
});

test('fillSelect keeps a selection that is still offered', function() {
	// Reopening a transition panel must not silently reset its region.
	var m = h.loadModule('js/flowMeta.js', { fail: true });
	m.jq.ready();
	var sel = h.fakeSelect();
	m.window.flowMeta.fillSelect(sel, ['ORIGINATING', 'TERMINATING'], true);
	sel.val('TERMINATING');

	m.window.flowMeta.fillSelect(sel, ['ORIGINATING', 'TERMINATING'], true);

	assert.strictEqual(sel.val(), 'TERMINATING');
});

test('fillSelect drops a selection that is no longer offered', function() {
	var m = h.loadModule('js/flowMeta.js', { fail: true });
	m.jq.ready();
	var sel = h.fakeSelect();
	m.window.flowMeta.fillSelect(sel, ['GONE', 'KEPT'], false);
	sel.val('GONE');

	m.window.flowMeta.fillSelect(sel, ['KEPT'], false);

	assert.strictEqual(sel.val(), 'KEPT', 'should land on a real option, not a stale one');
});

test('fillSelect tolerates a missing element', function() {
	// Panels load asynchronously; a select may not exist yet.
	var m = h.loadModule('js/flowMeta.js', { fail: true });
	m.jq.ready();

	m.window.flowMeta.fillSelect(null, ['A'], false);
	m.window.flowMeta.fillSelect({ length: 0 }, ['A'], false);
});

test('no in-dialog method is offered', function() {
	// The application router only ever sees initial requests.
	var m = h.loadModule('js/flowMeta.js', { fail: true });
	m.jq.ready();
	var methods = m.window.flowMeta.methods();

	['BYE', 'CANCEL', 'ACK', 'INFO', 'PRACK', 'UPDATE'].forEach(function(inDialog) {
		assert.ok(methods.indexOf(inDialog) < 0, inDialog + ' must not be offered');
	});
});
