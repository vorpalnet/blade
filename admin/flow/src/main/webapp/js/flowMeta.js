/**
 * flowMeta — the FSMAR model's closed value sets, in one place.
 *
 * These lists used to be typed out separately in transition.html (three
 * <select>s), flowTasks.js (selector types) and flowPlans.js (its own copy of
 * the method list), with nothing keeping them in step with the framework
 * classes they describe. They are now fetched once from /fsmarMeta, which
 * derives them from the model itself (see FsmarMeta.java).
 *
 * The literals below are a fallback for when that request fails: a stale
 * dropdown beats an empty one. FsmarMetaTest asserts they still agree with the
 * model, so they cannot drift silently.
 */
window.flowMeta = (function() {

	// Fallback only — the served values replace these on load.
	var meta = {
		// Initial-request methods. The container invokes an application router
		// for initial requests only, so in-dialog methods (BYE, CANCEL, ACK,
		// INFO, PRACK, UPDATE) are deliberately absent: a trigger keyed by one
		// could never fire.
		methods: ['INVITE', 'REGISTER', 'OPTIONS', 'SUBSCRIBE',
		          'PUBLISH', 'MESSAGE', 'NOTIFY', 'REFER'],
		regions: ['ORIGINATING', 'TERMINATING', 'NEUTRAL'],
		routeModifiers: ['ROUTE', 'ROUTE_BACK', 'ROUTE_FINAL', 'NO_ROUTE'],
		selectorTypes: ['attribute', 'json', 'xml', 'sdp', 'regex', 'table']
	};

	var loaded = false;
	var waiting = [];

	function apply(served) {
		['methods', 'regions', 'routeModifiers', 'selectorTypes'].forEach(function(k) {
			if (Array.isArray(served[k]) && served[k].length > 0) {
				meta[k] = served[k];
			}
		});
	}

	/** Fill a <select> with options, preserving the current value if it is
	 *  still offered. `blankFirst` adds the empty "unset" option that region
	 *  and routeModifier use to mean "container default". */
	function fillSelect($sel, values, blankFirst) {
		if (!$sel || !$sel.length) return;
		var current = $sel.val();
		var html = blankFirst ? '<option value=""></option>' : '';
		for (var i = 0; i < values.length; i++) {
			html += '<option>' + values[i] + '</option>';
		}
		$sel.html(html);
		if (current && values.indexOf(current) >= 0) {
			$sel.val(current);
		}
	}

	/** Run `fn` once the served values are in (immediately if already). */
	function ready(fn) {
		if (loaded) { fn(meta); return; }
		waiting.push(fn);
	}

	function load() {
		$.ajax({ url: 'fsmarMeta', dataType: 'json', cache: false })
			.done(function(served) {
				if (served && typeof served === 'object') apply(served);
			})
			.always(function() {
				loaded = true;
				var fns = waiting;
				waiting = [];
				fns.forEach(function(fn) { fn(meta); });
			});
	}

	$(load);

	return {
		get: function() { return meta; },
		methods: function() { return meta.methods; },
		regions: function() { return meta.regions; },
		routeModifiers: function() { return meta.routeModifiers; },
		selectorTypes: function() { return meta.selectorTypes; },
		fillSelect: fillSelect,
		ready: ready
	};
})();
