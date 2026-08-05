// Minimal test harness for the log viewer's browser code.
//
// Deliberately dependency-free and NOT wired into Maven, matching
// admin/flow/src/test/js/harness.js: the build has no node toolchain and
// adding one would make `./build.sh` fail wherever node is absent. Run by hand:
//
//   node admin/logs/src/test/js/run.js
//
// logsParse.js is a plain IIFE that assigns to `window`, so loading it needs
// nothing but an object to assign to.

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var tests = [];
var only = [];

function test(name, fn) {
	tests.push({ name: name, fn: fn });
}

// Focus a single test while debugging: `test.only('…', fn)`.
test.only = function (name, fn) {
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
	tests = [];
	only = [];
	return { total: list.length, failed: failed };
}

/// Load logsParse.js the way the browser does — evaluate it with a `window`
/// in scope — rather than adding module plumbing the shipped file would not
/// otherwise need.
function loadParse() {
	var file = path.join(__dirname, '..', '..', 'main', 'webapp', 'logsParse.js');
	var src = fs.readFileSync(file, 'utf8');
	var window = {};
	new Function('window', src)(window);
	if (!window.logsParse) throw new Error('logsParse.js did not assign window.logsParse');
	return window.logsParse;
}

/// Encode a string to the bytes a log file would actually hold.
function bytes(s) {
	return new Uint8Array(Buffer.from(s, 'utf8'));
}

module.exports = {
	test: test,
	assert: assert,
	run: run,
	loadParse: loadParse,
	bytes: bytes
};
