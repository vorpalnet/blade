// FSMAR Flow editor — session-aware request wrapper.
//
// The admin session (BLADEADMINSESSION, 1h idle) can expire mid-edit. A
// /fsmar* request then comes back as the FORM auth layer, not our servlet:
//   - small body  → the login page HTML (status 200 or a followed 302)
//   - large body  → a WebLogic 500 from FORM auth's save-post buffer (the
//                   mxGraph XML exceeds the fixed MaxSavePostSize=4096; this
//                   is not configurable per-WAR — it's an appclient-only knob)
// Either way the raw HTML used to land in the editor UI ("Export failed: 500
// <!DOCTYPE…").
//
// This wrapper detects that case, shows a re-login banner, and after the user
// re-authenticates in a popup, RETRIES the request. The diagram lives in the
// mxGraph model (untouched), so nothing is lost — and the retried request now
// carries a valid session, so it reaches the servlet normally (no save-post,
// no 500). BLADEADMINSESSION is shared across the admin tier, so logging in
// anywhere refreshes it for the editor.
(function () {
	// A protected admin page: hitting it unauthenticated triggers FORM login,
	// and a successful login refreshes the shared BLADEADMINSESSION cookie.
	var LOGIN_URL = '/blade/portal/';

	var pending = [];   // requests parked during expiry, retried on re-auth
	var banner = null;

	// True when a response is the auth/login layer talking, not our servlet.
	// Our endpoints only ever return JSON or text/xml, so any of these markers
	// means "session expired", NOT a genuine servlet error (which lacks them
	// and falls through to the caller's normal error handling).
	function isAuthExpiry(req) {
		var status = req.getStatus();
		if (status === 401 || status === 403) {
			return true;
		}
		var body = '';
		try { body = req.getText() || ''; } catch (e) { /* ignore */ }
		// Small-body expiry: the FORM login page.
		if (/j_security_check|j_username|login\.jsp/i.test(body)) {
			return true;
		}
		// Large-body expiry: WebLogic's FORM-auth save-post 500.
		if (status === 500 && /MaxSavePostSize|MaxPostSizeExceededException|FormSecurityModule/i.test(body)) {
			return true;
		}
		return false;
	}

	function showBanner() {
		if (banner) { return; }
		banner = document.createElement('div');
		banner.style.cssText = 'position:fixed; top:0; left:0; right:0; z-index:10000;'
			+ ' background:#5a2a2a; color:#fff; padding:8px 14px;'
			+ ' font:13px Arial, sans-serif; text-align:center;'
			+ ' box-shadow:0 1px 4px rgba(0,0,0,0.4);';
		banner.appendChild(document.createTextNode('Your admin session expired. '));
		var btn = document.createElement('button');
		btn.type = 'button';
		btn.textContent = 'Log in';
		btn.style.cssText = 'margin:0 8px; padding:2px 12px; cursor:pointer;';
		btn.onclick = reauth;
		banner.appendChild(btn);
		var note = document.createElement('span');
		note.style.opacity = '0.85';
		note.textContent = 'Your diagram is safe — the action retries after you log in.';
		banner.appendChild(note);
		document.body.appendChild(banner);
	}

	function hideBanner() {
		if (banner) { banner.parentNode.removeChild(banner); banner = null; }
	}

	function reauth() {
		var popup = window.open(LOGIN_URL, 'bladeLogin', 'width=480,height=640');
		if (!popup) {
			alert('Please allow popups, or open ' + LOGIN_URL + ' in another tab to log in,'
				+ ' then the editor will retry automatically once it is reachable.');
			return;
		}

		// Auto-close the popup the moment login completes, so the user doesn't
		// have to close it by hand for the retry to fire. The portal is
		// same-origin with this app (both under /blade/), so we can read where
		// the popup has navigated. FORM auth bounces an unauthenticated request
		// to login.jsp first, then on success redirects back to the portal. We
		// wait until we've SEEN the login page (sawLogin) and THEN see the
		// popup land back on a non-login portal URL — that ordering avoids the
		// brief pre-redirect instant where the URL is still the portal.
		var sawLogin = false;
		var timer = setInterval(function () {
			if (popup.closed) {           // user closed it manually — still retry
				finish();
				return;
			}
			var path = null;
			try { path = popup.location.pathname; } catch (e) { return; } // mid-redirect: not readable yet
			if (/login|j_security_check/i.test(path)) {
				sawLogin = true;
			} else if (sawLogin && path.indexOf('/blade/') === 0) {
				try { popup.close(); } catch (e) { /* ignore */ }
				finish();
			}
		}, 400);

		function finish() {
			clearInterval(timer);
			hideBanner();
			// Retry everything parked during the expiry.
			var queued = pending.splice(0, pending.length);
			queued.forEach(function (p) {
				flowRequest(p.url, p.params, p.method, p.onResp);
			});
		}
	}

	// Drop-in replacement for `new mxXmlRequest(url, params, method).send(cb)`.
	// On a normal response, calls onResp(resp) exactly as before. On detected
	// session expiry, parks the request and shows the re-login banner instead
	// of handing login HTML / a 500 page to the caller.
	function flowRequest(url, params, method, onResp) {
		var req = new mxXmlRequest(url, params, method || 'POST');
		req.send(function (resp) {
			if (isAuthExpiry(resp)) {
				pending.push({ url: url, params: params, method: method, onResp: onResp });
				showBanner();
				return;
			}
			onResp(resp);
		});
	}

	window.flowRequest = flowRequest;

	// ---------------------------------------------------------- unsaved work
	//
	// Warn before the user navigates away / closes the tab / refreshes while
	// the diagram has edits that were never published. The editor keeps its
	// work only in the in-memory mxGraph model — there's no autosave — so an
	// accidental reload loses it. We track a single "dirty" flag: set on any
	// model change, cleared when the diagram is published or (re)loaded from a
	// known source. app.js establishes the clean baseline after the initial
	// config load and feeds model-change events here.
	var dirty = false;

	window.flowDirty = {
		set: function () { dirty = true; },
		clear: function () { dirty = false; },
		is: function () { return dirty; }
	};

	// Native beforeunload prompt. Browsers show their own generic wording and
	// ignore custom text, but a non-empty returnValue is what triggers the
	// dialog. Use addEventListener (not window.onbeforeunload) so it survives
	// mxXmlRequest's save/restore of window.onbeforeunload during requests.
	window.addEventListener('beforeunload', function (e) {
		if (dirty) {
			e.preventDefault();
			e.returnValue = '';   // required by Chrome to show the prompt
			return '';
		}
	});
})();
