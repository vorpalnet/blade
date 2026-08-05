"use strict";

/*
 * BLADE Logs viewer.
 *
 * The pane holds a sliding WINDOW over the file, never the whole file. A few
 * 64 KiB chunks live in the DOM at once; scrolling near either edge fetches the
 * neighbouring chunk, drops the far one, and compensates scrollTop so the text
 * under the cursor does not move. The slider above the pane covers the whole
 * file; the pane's own scrollbar covers only the loaded window.
 *
 * Byte accounting is the fiddly part and it is done on BYTES, not on decoded
 * text. Slices are fetched as ArrayBuffers, trimmed to whole lines by scanning
 * for 0x0A, and only then decoded. Two things fall out of that: a window can
 * never split a multi-byte UTF-8 character (it always begins and ends just
 * after a newline), and `X-Log-NewOffset - byteLength` is a sound way to learn
 * where the server actually started reading. Decode first and both break.
 *
 * The parsing and rendering this file leans on lives in logsParse.js, which has
 * no DOM or network dependency and is exercised by src/test/js/.
 */

(function () {

	var P = window.logsParse;

	// ---- Constants --------------------------------------------------------

	var CHUNK_BYTES = 65536;        // one window chunk
	var MAX_SLICE = 1 << 20;        // VorpalLogReader.MAX_BYTES_PER_CALL
	var MAX_CHUNKS = 5;             // ~320 KiB resident
	var EDGE_PX = 800;              // how close to an edge triggers a fetch
	var FOLLOW_MS = 1000;
	var COLLAPSE_LINES = 8;         // records longer than this get a disclosure
	var BIG_DOWNLOAD = 100 * 1024 * 1024;

	var DECODER = new TextDecoder("utf-8");

	// ---- State ------------------------------------------------------------

	var state = {
		server: null,
		file: null,
		size: 0,
		chunks: [],        // [{start, end, text, recs, el}] ascending, contiguous
		loading: false,
		following: false,
		followTimer: null,
		scrubbing: false,
		needle: "",
		matches: [],       // [{rec, chunk}] — hits in the resident window
		matchIdx: -1,
		catalog: [],

		// Whole-file search, answered by the node that holds the file.
		fileMatches: [],
		fileMatchIdx: -1,
		searchNext: 0,
		searchComplete: true,
		searchScanned: 0,
		searchTerm: ""
	};

	// ---- Small helpers ----------------------------------------------------

	function $(id) { return document.getElementById(id); }

	function pill(text) { $("status-pill").textContent = text; }
	function panePill(text) { $("panePill").textContent = text; }

	function notice(title, body) {
		$("noticeTitle").textContent = title;
		$("noticeBody").textContent = body;
		$("notice").hidden = false;
	}
	function clearNotice() { $("notice").hidden = true; }

	function fmtSize(n) {
		if (n < 1024) return n + " B";
		if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
		if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + " MB";
		return (n / (1024 * 1024 * 1024)).toFixed(2) + " GB";
	}

	// ---- URLs -------------------------------------------------------------

	function segs(p) { return p.split("/").map(encodeURIComponent).join("/"); }

	function sliceUrl(off, max) {
		return "api/servers/" + encodeURIComponent(state.server) + "/logs/" + segs(state.file)
			+ "?offset=" + off + "&max=" + max;
	}
	function tailUrl(cursor, max) {
		return "api/servers/" + encodeURIComponent(state.server) + "/tail/" + segs(state.file)
			+ "?cursor=" + cursor + "&max=" + max;
	}
	function downloadUrl() {
		return "api/download/" + encodeURIComponent(state.server) + "/" + segs(state.file);
	}

	// ---- Transport --------------------------------------------------------

	function getJson(path) {
		return fetch(path, { credentials: "same-origin", cache: "no-cache" }).then(function (r) {
			if (!r.ok) throw new Error("HTTP " + r.status + " from " + path);
			return r.json().catch(function () {
				// A WebLogic HTML error page would land here. Say so rather than
				// throwing an opaque parse error.
				throw new Error("server did not return JSON (HTTP " + r.status + ")");
			});
		});
	}

	/// Fetch a slice as raw bytes and recover where the server read from.
	function apiBytes(url) {
		return fetch(url, { credentials: "same-origin", cache: "no-store" }).then(function (r) {
			if (!r.ok) {
				return r.text().catch(function () { return ""; }).then(function (body) {
					throw new Error("HTTP " + r.status + (body ? " — " + body.split("\n")[0] : ""));
				});
			}
			return r.arrayBuffer().then(function (ab) {
				var buf = new Uint8Array(ab);
				var newOffset = Number(r.headers.get("X-Log-NewOffset") || 0);
				return {
					buf: buf,
					start: newOffset - buf.length,
					end: newOffset,
					eof: r.headers.get("X-Log-EofReached") === "true",
					truncated: r.headers.get("X-Log-TruncatedAtStart") === "true"
				};
			});
		});
	}

	/// Current length of the selected file.
	///
	/// `offset=-1&max=0` means "position so the last 0 bytes are returned",
	/// i.e. seek to EOF and read nothing — so X-Log-NewOffset is the length.
	/// Cheaper than re-listing the directory.
	function fetchSize() {
		return apiBytes(sliceUrl(-1, 0)).then(function (res) { return res.end; });
	}

	/// Fetch a window and trim it to whole lines.
	///
	/// snapStart/snapEnd default on. They are turned OFF when the caller
	/// already knows an edge is a line boundary: extending upward requests
	/// exactly [prev.start - n, prev.start), and snapping that end would leave
	/// a gap between the new chunk and the one it must join.
	///
	/// A line longer than the request has no boundary to snap to, so the
	/// request is retried once, eight times larger, up to the reader's cap.
	function fetchWindow(offset, max, opts) {
		var snapStart = !opts || opts.snapStart !== false;
		var snapEnd = !opts || opts.snapEnd !== false;

		function attempt(want, tries) {
			return apiBytes(sliceUrl(offset, want)).then(function (res) {
				var snap = P.snapWindow(res.buf, res.start, res.eof, snapStart, snapEnd);
				if (snap.needMore && tries === 0 && want < MAX_SLICE) {
					return attempt(Math.min(want * 8, MAX_SLICE), 1);
				}
				return {
					start: res.start + snap.lo,
					end: res.start + snap.hi,
					text: DECODER.decode(res.buf.subarray(snap.lo, snap.hi)),
					eof: res.eof
				};
			});
		}
		return attempt(max, 0);
	}

	function renderRecords(recs) {
		var ansiOn = $("optAnsi").checked;
		var needle = state.needle;
		var html = "";

		for (var i = 0; i < recs.length; i++) {
			var r = recs[i];
			var head = r.lines[0];
			var tail = r.lines.length > 1 ? r.lines.slice(1).join("\n") : "";
			var body;

			if (r.lines.length > COLLAPSE_LINES) {
				body = "<details open><summary>" + P.renderText(head, ansiOn, needle)
					+ '<span class="log-more"> · ' + (r.lines.length - 1) + " more lines</span></summary>"
					+ P.renderText(tail, ansiOn, needle) + "</details>";
			} else {
				body = P.renderText(head, ansiOn, needle) + (tail ? "\n" + P.renderText(tail, ansiOn, needle) : "");
			}

			html += '<div class="log-rec" data-sev="' + r.sev + '" data-off="' + r.off + '">'
				+ '<span class="log-off">' + r.off + "</span>"
				+ '<span class="log-lvl">' + P.esc(r.lvl) + "</span>"
				+ '<span class="log-body">' + body + "</span>"
				+ "</div>";
		}
		return html || '<div class="log-placeholder">This window is empty.</div>';
	}

	// ---- Window management ------------------------------------------------

	function pane() { return $("pane"); }

	function makeChunk(win) {
		var recs = P.parseRecords(win.text, win.start);
		var el = document.createElement("div");
		el.className = "log-chunk";
		el.innerHTML = renderRecords(recs);
		return { start: win.start, end: win.end, text: win.text, recs: recs, el: el };
	}

	/// Remove chunks from whichever end is furthest from the viewport, keeping
	/// scrollTop pointing at the same text. Measuring scrollHeight either side
	/// of the removal is exact regardless of what the chunk contained.
	function trim(fromTop) {
		var p = pane();
		while (state.chunks.length > MAX_CHUNKS) {
			var before = p.scrollHeight;
			if (fromTop) {
				var first = state.chunks.shift();
				p.removeChild(first.el);
				p.scrollTop -= (before - p.scrollHeight);
			} else {
				var last = state.chunks.pop();
				p.removeChild(last.el);
			}
		}
	}

	function appendChunk(win) {
		var c = makeChunk(win);
		pane().appendChild(c.el);
		state.chunks.push(c);
		trim(true);
		return c;
	}

	function prependChunk(win) {
		var p = pane();
		var c = makeChunk(win);
		var before = p.scrollHeight;
		p.insertBefore(c.el, p.firstChild);
		p.scrollTop += (p.scrollHeight - before);
		state.chunks.unshift(c);
		trim(false);
		return c;
	}

	function clearPane() {
		pane().innerHTML = "";
		state.chunks = [];
		state.matches = [];
		state.matchIdx = -1;
		$("findCount").textContent = "—";
	}

	/// Load a fresh window. `offset` of -1 means the end of the file.
	function loadAt(offset) {
		if (!state.server || !state.file) return Promise.resolve();
		var atEnd = (offset < 0);
		return fetchWindow(atEnd ? -1 : offset, CHUNK_BYTES).then(function (win) {
			clearPane();
			var c = appendChunk(win);
			var p = pane();
			p.scrollTop = atEnd ? p.scrollHeight : Math.max(0, c.el.offsetTop - 4);
			// Pull in context above so the target is not stranded at the very
			// top of an otherwise empty pane.
			if (win.start > 0) {
				return extendUp().then(function () {
					if (atEnd) pane().scrollTop = pane().scrollHeight;
					syncStatus();
				});
			}
			syncStatus();
		});
	}

	function extendUp() {
		var first = state.chunks[0];
		if (!first || first.start <= 0) return Promise.resolve(false);
		var want = Math.min(CHUNK_BYTES, first.start);
		return fetchWindow(first.start - want, want, { snapEnd: false }).then(function (win) {
			if (win.end <= win.start) return false;
			prependChunk(win);
			return true;
		});
	}

	function extendDown() {
		var last = state.chunks[state.chunks.length - 1];
		if (!last) return Promise.resolve(false);
		if (state.size && last.end >= state.size) return Promise.resolve(false);
		return fetchWindow(last.end, CHUNK_BYTES, { snapStart: false }).then(function (win) {
			if (win.end <= last.end) return false;
			appendChunk(win);
			return true;
		});
	}

	/// Serialise window moves. Scroll fires far faster than a round trip, and
	/// two concurrent extends would interleave their scrollTop compensation.
	///
	/// Not re-entrant: a guarded call made while one is in flight is dropped,
	/// so the functions below never guard their own callees. Entry points wrap;
	/// everything under them runs bare.
	function guarded(fn) {
		if (state.loading) return Promise.resolve(false);
		state.loading = true;
		panePill("reading…");
		return Promise.resolve()
			.then(fn)
			.then(function (r) { clearNotice(); return r; })
			.catch(function (e) { notice("Read failed", String(e.message || e)); return false; })
			.then(function (r) {
				state.loading = false;
				panePill(state.following ? "following" : "ready");
				syncStatus();
				return r;
			});
	}

	// ---- Position readout -------------------------------------------------

	/// Byte offset of the topmost visible line, interpolated within its chunk.
	/// Approximate by design: exact would mean measuring every rendered record.
	function topOffset() {
		var p = pane(), st = p.scrollTop;
		for (var i = 0; i < state.chunks.length; i++) {
			var c = state.chunks[i];
			var top = c.el.offsetTop, h = c.el.offsetHeight;
			if (st < top + h || i === state.chunks.length - 1) {
				var frac = h > 0 ? Math.min(1, Math.max(0, (st - top) / h)) : 0;
				return Math.round(c.start + (c.end - c.start) * frac);
			}
		}
		return 0;
	}

	function syncStatus() {
		if (!state.chunks.length) {
			$("statusRange").textContent = "—";
			return;
		}
		var lo = state.chunks[0].start;
		var hi = state.chunks[state.chunks.length - 1].end;
		var top = topOffset();
		var pct = state.size ? (top / state.size * 100) : 0;

		var bits = ["window " + fmtSize(lo) + "–" + fmtSize(hi) + " of " + fmtSize(state.size)];
		bits.push("at " + top.toLocaleString() + " · " + pct.toFixed(1) + "%");
		if (hi >= state.size) bits.push("end of file");
		if (state.following) bits.push("following");
		$("statusRange").textContent = bits.join(" · ");

		if (!state.scrubbing && state.size) {
			$("scrubber").value = String(Math.round(top / state.size * 1000));
		}
	}

	// ---- Scrolling --------------------------------------------------------

	function onScroll() {
		syncStatus();
		if (state.loading) return;
		var p = pane();
		if (p.scrollTop < EDGE_PX) {
			guarded(extendUp);
		} else if (p.scrollHeight - p.scrollTop - p.clientHeight < EDGE_PX) {
			guarded(extendDown);
		}
	}

	function atBottom() {
		var p = pane();
		return p.scrollHeight - p.scrollTop - p.clientHeight < 8;
	}

	// ---- Find (within the loaded window) ----------------------------------

	function rerenderAll() {
		for (var i = 0; i < state.chunks.length; i++) {
			state.chunks[i].el.innerHTML = renderRecords(state.chunks[i].recs);
		}
	}

	function runFind() {
		var needle = $("find").value;
		state.needle = needle;
		state.matches = [];
		state.matchIdx = -1;
		rerenderAll();

		if (!needle) {
			$("findCount").textContent = "—";
			return;
		}
		var n = needle.toLowerCase();
		for (var i = 0; i < state.chunks.length; i++) {
			var recs = state.chunks[i].recs;
			for (var j = 0; j < recs.length; j++) {
				if (P.recText(recs[j]).toLowerCase().indexOf(n) !== -1) {
					state.matches.push({ chunk: i, rec: j });
				}
			}
		}
		$("findCount").textContent = state.matches.length
			? state.matches.length + " in window"
			: "no match in window";
		if (state.matches.length) step(1);
	}

	function step(dir) {
		if (!state.matches.length) return;
		state.matchIdx = (state.matchIdx + dir + state.matches.length) % state.matches.length;
		var m = state.matches[state.matchIdx];
		var el = state.chunks[m.chunk].el.querySelectorAll(".log-rec")[m.rec];
		if (!el) return;

		var prev = pane().querySelector("mark.is-current");
		if (prev) prev.classList.remove("is-current");
		var mk = el.querySelector("mark");
		if (mk) mk.classList.add("is-current");

		var det = el.querySelector("details");
		if (det) det.open = true;
		el.scrollIntoView({ block: "center" });
		$("findCount").textContent = (state.matchIdx + 1) + " of " + state.matches.length + " in window";
	}

	// ---- Go to: byte offset or timestamp ----------------------------------

	/// Locate the first record at or after `targetMs` by bisecting the file.
	///
	/// About twenty 8 KiB probes settle any file size, because each probe only
	/// has to answer "is this point before or after the target". It assumes the
	/// file is in time order, which holds within a single log.
	function seekTime(targetMs) {
		var lo = 0, hi = state.size, blind = 0, probes = 0;

		function probe(offset, size) {
			return fetchWindow(offset, size).then(function (win) {
				var recs = P.parseRecords(win.text, win.start);
				for (var i = 0; i < recs.length; i++) {
					if (!isNaN(recs[i].ts)) return recs[i].ts;
				}
				if (size < 65536) return probe(offset, 65536);
				return null;
			});
		}

		function loop() {
			if (hi - lo <= 16384 || probes++ > 40) return Promise.resolve(lo);
			var mid = Math.floor((lo + hi) / 2);
			return probe(mid, 8192).then(function (t) {
				if (t === null) { blind++; lo = mid; }
				else if (t < targetMs) lo = mid;
				else hi = mid;
				return loop();
			});
		}

		return loop().then(function (off) {
			if (blind > 0 && blind >= probes - 1) {
				notice("No timestamps found",
					"Nothing in this file parsed as a timestamp, so the search could not " +
					"narrow. Jumped to a byte offset instead.");
			}
			return off;
		});
	}

	function doGoto() {
		var raw = $("goto").value.trim();
		if (!raw || !state.file) return;

		if (/^\d+$/.test(raw)) {
			guarded(function () { return loadAt(Math.min(Number(raw), state.size)); });
			return;
		}

		// A bare time attaches to the date of the record currently on screen,
		// so "14:32" means today's log rather than 1970.
		var text = raw;
		if (/^\d{1,2}:\d{2}(:\d{2})?(\.\d+)?$/.test(raw)) {
			var day = null;
			for (var i = 0; i < state.chunks.length && !day; i++) {
				var recs = state.chunks[i].recs;
				for (var j = 0; j < recs.length; j++) {
					if (!isNaN(recs[j].ts)) { day = new Date(recs[j].ts); break; }
				}
			}
			if (!day) day = new Date();
			var iso = day.getFullYear() + "-"
				+ String(day.getMonth() + 1).padStart(2, "0") + "-"
				+ String(day.getDate()).padStart(2, "0");
			text = iso + " " + (raw.length <= 5 ? raw + ":00" : raw);
			if (!/\.\d+$/.test(text)) text += ".000";
		}

		var target = P.parseTime(text);
		if (isNaN(target)) {
			notice("Cannot read that position",
				"Enter a byte offset, a clock time such as 14:32:05, or a full " +
				"timestamp such as 2026-08-04 14:32:05.");
			return;
		}
		guarded(function () {
			panePill("seeking…");
			return seekTime(target).then(function (off) { return loadAt(off); });
		});
	}

	// ---- Follow -----------------------------------------------------------

	function followTick() {
		if (!state.following || state.loading || !state.file) return;
		var last = state.chunks[state.chunks.length - 1];
		var cursor = last ? last.end : 0;
		var pinned = atBottom();

		state.loading = true;
		apiBytes(tailUrl(cursor, CHUNK_BYTES)).then(function (res) {
			state.size = Math.max(state.size, res.end);

			if (res.truncated) {
				notice("File rotated",
					"The file shrank below the read position, so it was rotated or " +
					"truncated. Reloaded from the new beginning.");
				state.loading = false;
				return loadAt(0);
			}
			if (res.buf.length === 0) return;

			// Advance only to the last newline: bytes after it are a line the
			// writer has not finished, and re-reading them next tick costs
			// nothing.
			var nl = res.buf.lastIndexOf(10);
			if (nl < 0) return;

			var text = DECODER.decode(res.buf.subarray(0, nl + 1));
			appendChunk({ start: res.start, end: res.start + nl + 1, text: text, eof: res.eof });
			if (pinned) pane().scrollTop = pane().scrollHeight;
		}).catch(function (e) {
			notice("Follow stopped", String(e.message || e));
			setFollowing(false);
		}).then(function () {
			state.loading = false;
			syncStatus();
		});
	}

	function setFollowing(on) {
		state.following = on;
		$("followBtn").setAttribute("aria-pressed", on ? "true" : "false");
		$("followBtn").textContent = on ? "Following" : "Follow";
		if (state.followTimer) { clearInterval(state.followTimer); state.followTimer = null; }
		if (on) {
			state.followTimer = setInterval(followTick, FOLLOW_MS);
			guarded(function () { return loadAt(-1); });
		}
		panePill(on ? "following" : "ready");
		syncStatus();
	}

	// ---- Whole-file search ------------------------------------------------
	//
	// The scan runs on the node holding the file and returns byte offsets, so
	// the results list steers the pane instead of duplicating it. Each pass is
	// bounded; "Scan further" resumes from where the last one stopped.

	function searchUrl(from) {
		return "api/search/" + encodeURIComponent(state.server) + "/" + segs(state.file)
			+ "?q=" + encodeURIComponent(state.searchTerm)
			+ "&regex=" + ($("optRegex").checked ? "true" : "false")
			+ "&ignoreCase=true&from=" + from + "&maxMatches=500&maxScan=33554432";
	}

	function showResults(on) {
		$("results").hidden = !on;
		document.querySelector(".log-split").classList.toggle("with-results", on);
	}

	function renderResults() {
		var list = $("resultsList");
		if (!state.fileMatches.length) {
			list.innerHTML = '<li class="log-results-empty">No matches found'
				+ (state.searchComplete ? " in this file." : " so far.") + "</li>";
			return;
		}
		var html = "";
		for (var i = 0; i < state.fileMatches.length; i++) {
			var m = state.fileMatches[i];
			html += '<li data-i="' + i + '" data-off="' + m.offset + '">'
				+ '<span class="r-off">' + m.offset.toLocaleString() + "</span>"
				+ '<span class="r-text">' + P.escWithMarks(m.text, state.searchTerm) + "</span>"
				+ "</li>";
		}
		list.innerHTML = html;
	}

	function updateSearchMeta() {
		$("resultsMeta").textContent = state.fileMatches.length + " match"
			+ (state.fileMatches.length === 1 ? "" : "es")
			+ " · scanned " + fmtSize(state.searchScanned)
			+ (state.searchComplete ? " · whole file" : " · more to scan");
		$("searchMore").hidden = state.searchComplete;
	}

	function runFileSearch(from) {
		if (!state.file) return Promise.resolve();
		var term = $("find").value;
		if (!term) {
			notice("Nothing to search for", "Type something in the Find box first.");
			return Promise.resolve();
		}
		if (from === 0) {
			state.searchTerm = term;
			state.fileMatches = [];
			state.fileMatchIdx = -1;
			state.searchScanned = 0;
		}
		showResults(true);
		panePill("searching…");

		return getJson(searchUrl(from)).then(function (r) {
			if (r.supported === false) {
				showResults(false);
				notice("Search unavailable on " + state.server, r.reason);
				return;
			}
			state.fileMatches = state.fileMatches.concat(r.matches || []);
			state.searchNext = r.nextOffset;
			state.searchComplete = !!r.complete;
			state.searchScanned += r.bytesScanned || 0;
			renderResults();
			updateSearchMeta();
		});
	}

	function gotoMatch(i) {
		var m = state.fileMatches[i];
		if (!m) return;
		state.fileMatchIdx = i;

		var lis = $("resultsList").querySelectorAll("li");
		for (var k = 0; k < lis.length; k++) lis[k].classList.toggle("is-current", k === i);

		// Land a little above the hit so it has context, then highlight it in
		// the pane using the same needle the scan used.
		$("find").value = state.searchTerm;
		state.needle = state.searchTerm;
		guarded(function () {
			return loadAt(Math.max(0, m.offset)).then(function () {
				rerenderAll();
				var el = pane().querySelector('.log-rec[data-off="' + m.offset + '"]');
				if (el) el.scrollIntoView({ block: "center" });
			});
		});
	}

	// ---- Download ---------------------------------------------------------

	function saveBlob(name, text) {
		var url = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
		var a = document.createElement("a");
		a.href = url;
		a.download = name;
		document.body.appendChild(a);
		a.click();
		document.body.removeChild(a);
		setTimeout(function () { URL.revokeObjectURL(url); }, 0);
	}

	function baseName() {
		return (state.server + "-" + state.file).replace(/[\\/]/g, "_");
	}

	function doDownload(what) {
		if (!state.file) return;
		switch (what) {
			case "file":
				if (state.size > BIG_DOWNLOAD && !window.confirm(
						"This file is " + fmtSize(state.size) + ". It is streamed through the " +
						"AdminServer one megabyte at a time, so a file this size will take a " +
						"while. Download anyway?")) {
					return;
				}
				window.location.href = downloadUrl();
				break;
			case "window":
				saveBlob(baseName() + ".window.log", state.chunks.map(function (c) { return c.text; }).join(""));
				break;
			case "matches":
				// Whole-file results when a scan has run; otherwise whatever the
				// resident window matched.
				if (state.fileMatches.length) {
					saveBlob(baseName() + ".matches.log", state.fileMatches.map(function (m) {
						return m.offset + "\t" + m.text;
					}).join("\n") + "\n");
				} else if (state.matches.length) {
					saveBlob(baseName() + ".matches.log", state.matches.map(function (m) {
						return P.recText(state.chunks[m.chunk].recs[m.rec]);
					}).join("\n") + "\n");
				}
				break;
		}
	}

	// ---- Catalog ----------------------------------------------------------

	function loadServers() {
		return getJson("api/servers").then(function (list) {
			var sel = $("server");
			sel.innerHTML = "";
			list.forEach(function (s) {
				var opt = document.createElement("option");
				opt.value = s.name;
				opt.textContent = s.name + (s.cluster ? " [" + s.cluster + "]" : "");
				sel.appendChild(opt);
			});
			if (!list.length) throw new Error("No servers found via DomainConfiguration.");
			state.server = sel.value;
			pill(list.length + " server" + (list.length === 1 ? "" : "s"));
		});
	}

	function loadCatalog() {
		state.server = $("server").value;
		if (!state.server) return Promise.resolve();
		panePill("listing…");
		return getJson("api/servers/" + encodeURIComponent(state.server) + "/logs").then(function (files) {
			files.sort(function (a, b) { return a.relativePath.localeCompare(b.relativePath); });
			state.catalog = files;

			var sel = $("logFile");
			sel.innerHTML = "";
			files.forEach(function (f) {
				var opt = document.createElement("option");
				opt.value = f.relativePath;
				opt.textContent = f.relativePath + "  (" + f.kind + ", " + fmtSize(f.sizeBytes) + ")";
				sel.appendChild(opt);
			});

			if (!files.length) {
				panePill("no files");
				$("paneTitle").textContent = "No log files on " + state.server;
				return;
			}
			// Open the server log by default — it is what an operator wants
			// nine times out of ten.
			var pick = files.filter(function (f) { return f.kind === "WLS_SERVER"; })[0] || files[0];
			sel.value = pick.relativePath;
			return openFile();
		});
	}

	function openFile() {
		state.file = $("logFile").value;
		if (!state.file) return Promise.resolve();
		setFollowing(false);
		$("paneTitle").textContent = state.server + " : " + state.file;

		// Results index the file that produced them, so they do not survive it.
		state.fileMatches = [];
		state.fileMatchIdx = -1;
		state.searchComplete = true;
		state.searchScanned = 0;
		showResults(false);

		var meta = state.catalog.filter(function (f) { return f.relativePath === state.file; })[0];
		$("fileMeta").textContent = meta
			? fmtSize(meta.sizeBytes) + " · " + new Date(meta.lastModifiedMs).toLocaleString()
			: "—";

		return fetchSize().then(function (n) {
			state.size = n;
			return loadAt(-1);
		});
	}

	// ---- Wiring -----------------------------------------------------------

	function applyPaneClasses() {
		var p = pane();
		p.classList.toggle("wrap", $("optWrap").checked);
		p.classList.toggle("show-offsets", $("optOffsets").checked);
		var chips = $("sevChips").querySelectorAll(".log-chip");
		for (var i = 0; i < chips.length; i++) {
			p.classList.toggle("hide-" + chips[i].dataset.sev,
				chips[i].getAttribute("aria-pressed") !== "true");
		}
	}

	function wire() {
		$("server").addEventListener("change", function () { guarded(loadCatalog); });
		$("logFile").addEventListener("change", function () { guarded(openFile); });
		$("reloadBtn").addEventListener("click", function () { guarded(loadCatalog); });
		$("followBtn").addEventListener("click", function () { setFollowing(!state.following); });

		$("topBtn").addEventListener("click", function () { guarded(function () { return loadAt(0); }); });
		$("bottomBtn").addEventListener("click", function () { guarded(function () { return loadAt(-1); }); });
		$("gotoBtn").addEventListener("click", doGoto);
		$("goto").addEventListener("keydown", function (e) { if (e.key === "Enter") doGoto(); });

		$("scrubber").addEventListener("input", function () { state.scrubbing = true; });
		$("scrubber").addEventListener("change", function (e) {
			state.scrubbing = false;
			if (!state.size) return;
			var off = Math.floor(Number(e.target.value) / 1000 * state.size);
			guarded(function () { return loadAt(Math.min(off, state.size)); });
		});

		// Every keystroke re-renders the resident window, so wait for a pause.
		var findTimer = null;
		$("find").addEventListener("input", function () {
			clearTimeout(findTimer);
			findTimer = setTimeout(runFind, 150);
		});
		$("find").addEventListener("keydown", function (e) {
			if (e.key !== "Enter") return;
			e.preventDefault();
			if (e.ctrlKey || e.metaKey) {
				guarded(function () { return runFileSearch(0); });
			} else {
				step(e.shiftKey ? -1 : 1);
			}
		});
		$("findNext").addEventListener("click", function () { step(1); });
		$("findPrev").addEventListener("click", function () { step(-1); });

		$("searchFile").addEventListener("click", function () { guarded(function () { return runFileSearch(0); }); });
		$("searchMore").addEventListener("click", function () {
			guarded(function () { return runFileSearch(state.searchNext); });
		});
		$("resultsClose").addEventListener("click", function () { showResults(false); });
		$("resultsList").addEventListener("click", function (e) {
			var li = e.target.closest("li[data-i]");
			if (li) gotoMatch(Number(li.dataset.i));
		});

		$("sevChips").addEventListener("click", function (e) {
			var chip = e.target.closest(".log-chip");
			if (!chip) return;
			chip.setAttribute("aria-pressed", chip.getAttribute("aria-pressed") === "true" ? "false" : "true");
			applyPaneClasses();
		});

		$("optWrap").addEventListener("change", applyPaneClasses);
		$("optOffsets").addEventListener("change", applyPaneClasses);
		$("optAnsi").addEventListener("change", rerenderAll);
		$("optFont").addEventListener("change", function (e) {
			pane().style.fontSize = e.target.value + "px";
		});

		var menu = $("downloadMenu");
		$("downloadBtn").addEventListener("click", function (e) {
			e.stopPropagation();
			menu.hidden = !menu.hidden;
			$("downloadBtn").setAttribute("aria-expanded", menu.hidden ? "false" : "true");
			menu.querySelector('[data-download="matches"]').disabled =
				!state.matches.length && !state.fileMatches.length;
		});
		menu.addEventListener("click", function (e) {
			var b = e.target.closest("[data-download]");
			if (!b) return;
			menu.hidden = true;
			$("downloadBtn").setAttribute("aria-expanded", "false");
			doDownload(b.dataset.download);
		});
		document.addEventListener("click", function () {
			menu.hidden = true;
			$("downloadBtn").setAttribute("aria-expanded", "false");
		});

		pane().addEventListener("scroll", onScroll, { passive: true });

		document.addEventListener("keydown", function (e) {
			var t = e.target;
			if (t && (t.tagName === "INPUT" || t.tagName === "SELECT" || t.tagName === "TEXTAREA")) return;
			if (e.ctrlKey || e.metaKey || e.altKey) return;
			switch (e.key) {
				case "/": e.preventDefault(); $("find").focus(); break;
				case "g": e.preventDefault(); guarded(function () { return loadAt(0); }); break;
				case "G": e.preventDefault(); guarded(function () { return loadAt(-1); }); break;
				case "n": e.preventDefault(); step(1); break;
				case "N": e.preventDefault(); step(-1); break;
			}
		});
	}

	document.addEventListener("DOMContentLoaded", function () {
		wire();
		applyPaneClasses();
		loadServers()
			.then(function () { return guarded(loadCatalog); })
			.catch(function (e) {
				pill("error");
				panePill("failed");
				notice("Could not reach the log service", String(e.message || e));
			});
	});

})();
