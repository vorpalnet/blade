// Minimal test harness for the Flow editor's browser code.
//
// Deliberately dependency-free and NOT wired into Maven. The build has no
// node toolchain and adding one (exec-maven-plugin, or worse a node download)
// would make `./build.sh` fail wherever node is absent. Run these by hand:
//
//   node admin/flow/src/test/js/run.js
//
// The browser modules are plain IIFEs that assign to `window`, so loading them
// only needs `window` plus whatever globals they touch. Everything here is a
// stub of exactly what the module under test uses — no jQuery, no DOM.

var assert = require('assert');

var tests = [];
var only = [];

function test(name, fn) {
	tests.push({ name: name, fn: fn });
}

// Focus a single test while debugging: `test.only('…', fn)`.
test.only = function(name, fn) {
	only.push({ name: name, fn: fn });
};

function run() {
	var list = only.length ? only : tests;
	var failed = 0;
	for (var i = 0; i < list.length; i++) {
		try {
			list[i].fn();
			console.log('  ok   ' + list[i].name);
		} catch (e) {
			failed++;
			console.log('  FAIL ' + list[i].name);
			console.log('       ' + (e && e.message ? e.message : e));
		}
	}
	if (only.length) {
		console.log('  (only-mode: ' + tests.length + ' other test(s) skipped)');
	}
	return { total: list.length, failed: failed };
}

// ----- browser stubs --------------------------------------------------------

/// A stand-in for a jQuery-wrapped <select>: just enough of the API that
/// flowMeta's fillSelect uses, plus inspection helpers for assertions.
function fakeSelect(initialValue) {
	var state = { html: '', value: initialValue || '', options: [] };
	var obj = {
		length: 1,
		html: function(markup) {
			state.html = markup;
			state.options = [];
			var re = /<option(?:\s+value="([^"]*)")?[^>]*>([^<]*)<\/option>/g;
			var m;
			while ((m = re.exec(markup)) !== null) {
				state.options.push({
					value: m[1] !== undefined ? m[1] : m[2],
					text: m[2]
				});
			}
			// A real <select> resets to its first option when repopulated.
			state.value = state.options.length ? state.options[0].value : '';
			return obj;
		},
		val: function(v) {
			if (v === undefined) return state.value;
			// A real <select> ignores a value it has no option for.
			for (var i = 0; i < state.options.length; i++) {
				if (state.options[i].value === v) {
					state.value = v;
					return obj;
				}
			}
			return obj;
		},
		_state: state
	};
	return obj;
}

/// Installs a `$` that satisfies flowMeta: document-ready, and `$.ajax`
/// returning a done/always chain. `ajaxResult` decides what the request does:
///   { ok: <object> }  -> done(object) then always()
///   { fail: true }    -> always() only
function installJQuery(ajaxResult) {
	var readyFns = [];
	var $ = function(arg) {
		if (typeof arg === 'function') {
			readyFns.push(arg);
			return;
		}
		return arg;
	};
	$.ajax = function() {
		var chain = {
			done: function(fn) {
				if (ajaxResult && ajaxResult.ok !== undefined) fn(ajaxResult.ok);
				return chain;
			},
			always: function(fn) {
				fn();
				return chain;
			}
		};
		return chain;
	};
	global.$ = $;
	return {
		/// Fire document-ready, which is what triggers flowMeta's fetch.
		ready: function() {
			readyFns.forEach(function(fn) { fn(); });
		}
	};
}

/// Loads a webapp module fresh, with `window` and `$` in place.
function loadModule(relativePath, ajaxResult) {
	var path = require('path');
	var fs = require('fs');
	var jq = installJQuery(ajaxResult);
	global.window = {};
	var file = path.join(__dirname, '../../main/webapp', relativePath);
	// Evaluated rather than require()d: these are browser scripts with no
	// module wrapper, and each test needs a clean instance.
	var src = fs.readFileSync(file, 'utf8');
	(0, eval)(src);
	return { window: global.window, jq: jq };
}

module.exports = {
	test: test,
	run: run,
	assert: assert,
	fakeSelect: fakeSelect,
	loadModule: loadModule
};
