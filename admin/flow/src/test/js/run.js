// Runs the Flow editor's browser-side tests.
//
//   node admin/flow/src/test/js/run.js
//
// Not part of `./build.sh`: the Maven build has no node toolchain, and wiring
// one in would break the build wherever node is absent. Run it by hand after
// touching anything under src/main/webapp/js.
//
// Exits non-zero on the first failing file's failures, so it can be dropped
// into a pre-commit hook or CI step later without changes.

var fs = require('fs');
var path = require('path');

var dir = __dirname;
var files = fs.readdirSync(dir)
	.filter(function(f) { return f.endsWith('.test.js'); })
	.sort();

if (!files.length) {
	console.error('No .test.js files found in ' + dir);
	process.exit(1);
}

var harness = require('./harness');
var total = 0;
var failed = 0;

files.forEach(function(file) {
	console.log(file);
	require(path.join(dir, file));
	var result = harness.run();
	total += result.total;
	failed += result.failed;
});

console.log('');
console.log(failed === 0
	? 'PASS: ' + total + ' browser test(s)'
	: 'FAIL: ' + failed + ' of ' + total + ' browser test(s)');

process.exit(failed === 0 ? 0 : 1);
