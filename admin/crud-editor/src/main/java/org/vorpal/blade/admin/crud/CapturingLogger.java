package org.vorpal.blade.admin.crud;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.logging.Logger;

/// A SIP-style [Logger] that doesn't NPE when called from a context that
/// never initialised the framework's full logging stack (i.e. the
/// AdminServer-side preview WAR), and — critically — captures every
/// per-request error into a thread-local buffer so [PreviewEngine] callers
/// can surface them to the operator instead of silently logging to a file.
///
/// Wire it up once in [PreviewServlet#init], then bracket each preview
/// request with [#begin] / [#end].
public class CapturingLogger extends Logger {
	private static final long serialVersionUID = 1L;

	private static final ThreadLocal<List<String>> CAPTURED = new ThreadLocal<>();

	public CapturingLogger() {
		super("crud-preview", null);
	}

	/// Start capturing for the current request. Call before invoking the
	/// preview engine; pair with [#end].
	public static void begin() {
		CAPTURED.set(new ArrayList<>());
	}

	/// Returns and clears the per-request capture buffer.
	public static List<String> end() {
		List<String> r = CAPTURED.get();
		CAPTURED.remove();
		return r != null ? r : Collections.emptyList();
	}

	private static void capture(String severity, String body) {
		System.err.println("[CRUD-preview/" + severity + "] " + body);
		List<String> buf = CAPTURED.get();
		if (buf != null) buf.add(severity + ": " + body);
	}

	private static String trace(Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return t.getClass().getSimpleName() + ": " + t.getMessage() + "\n" + sw;
	}

	// --- error capture ---

	@Override
	public void logStackTrace(SipServletMessage msg, Exception e) { capture("ERROR", trace(e)); }

	@Override
	public void logStackTrace(SipApplicationSession s, Exception e) { capture("ERROR", trace(e)); }

	@Override
	public void logStackTrace(SipSession s, Exception e) { capture("ERROR", trace(e)); }

	@Override
	public void logStackTrace(Exception e) { capture("ERROR", trace(e)); }

	@Override
	public void severe(Exception e) { capture("ERROR", trace(e)); }

	@Override
	public void severe(SipServletMessage msg, Exception e) { capture("ERROR", trace(e)); }

	@Override
	public void severe(SipServletMessage msg, String comments) { capture("ERROR", comments); }

	@Override
	public void severe(String msg) { capture("ERROR", msg); }

	// --- warnings (e.g. JsonPath parent-not-found) ---

	@Override
	public void warning(SipServletMessage msg, String comments) { capture("WARN", comments); }

	@Override
	public void warning(String msg) { capture("WARN", msg); }

	// --- finer / fine — info noise; don't flood the buffer, just stderr ---

	@Override
	public void finer(String msg) { /* swallow */ }

	@Override
	public void finer(SipServletMessage msg, String comments) { /* swallow */ }

	@Override
	public void finer(SipSession s, String comments) { /* swallow */ }

	@Override
	public void finer(SipApplicationSession s, String comments) { /* swallow */ }
}
