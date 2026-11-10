package org.vorpal.blade.framework.v2.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipSession;


/// A SIP-style [Logger] for the AdminServer-side preview WARs: captures each
/// warning, error and FINE note logged on a thread that called [#begin] into
/// a buffer, so the preview can show the operator what the engine logged
/// (a rule undone, an operation skipped for an unset variable).
///
/// Built around the logger the WAR already has ([#wrap]), it passes every
/// call on to it, so the editor's own log keeps its lines. Without the
/// wrapper an editor that had configured logging got no capture at all.
///
/// Install it once in the preview servlet's `init()`, then bracket each
/// preview request with [#begin] / [#end].
public class CapturingLogger extends Logger {
	private static final long serialVersionUID = 1L;

	private static final ThreadLocal<List<String>> CAPTURED = new ThreadLocal<>();

	private final transient Logger delegate;

	public CapturingLogger() {
		this(null);
	}

	private CapturingLogger(Logger delegate) {
		super("editor-preview", null);
		this.delegate = delegate;
	}

	/// A capturing logger that also passes every call on to `delegate`
	/// (which may be null), or `delegate` itself if it already captures.
	public static CapturingLogger wrap(Logger delegate) {
		return (delegate instanceof CapturingLogger) ? (CapturingLogger) delegate : new CapturingLogger(delegate);
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

	private void capture(String severity, String body) {
		List<String> buf = CAPTURED.get();
		if (buf != null) buf.add(severity + ": " + body);
		if (delegate == null) System.err.println("[editor-preview/" + severity + "] " + body);
	}

	private static String trace(Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return t.getClass().getSimpleName() + ": " + t.getMessage() + "\n" + sw;
	}

	// --- error capture ---

	@Override
	public void logStackTrace(SipServletMessage msg, Exception e) {
		capture("ERROR", trace(e));
		if (delegate != null) delegate.logStackTrace(msg, e);
	}

	@Override
	public void logStackTrace(SipApplicationSession s, Exception e) {
		capture("ERROR", trace(e));
		if (delegate != null) delegate.logStackTrace(s, e);
	}

	@Override
	public void logStackTrace(SipSession s, Exception e) {
		capture("ERROR", trace(e));
		if (delegate != null) delegate.logStackTrace(s, e);
	}

	@Override
	public void logStackTrace(Exception e) {
		capture("ERROR", trace(e));
		if (delegate != null) delegate.logStackTrace(e);
	}

	@Override
	public void severe(Exception e) {
		capture("ERROR", trace(e));
		if (delegate != null) delegate.severe(e);
	}

	@Override
	public void severe(SipServletMessage msg, Exception e) {
		capture("ERROR", trace(e));
		if (delegate != null) delegate.severe(msg, e);
	}

	@Override
	public void severe(SipServletMessage msg, String comments) {
		capture("ERROR", comments);
		if (delegate != null) delegate.severe(msg, comments);
	}

	@Override
	public void severe(String msg) {
		capture("ERROR", msg);
		if (delegate != null) delegate.severe(msg);
	}

	// --- warnings (a rule undone, a JsonPath parent not found) ---

	@Override
	public void warning(SipServletMessage msg, String comments) {
		capture("WARN", comments);
		if (delegate != null) delegate.warning(msg, comments);
	}

	@Override
	public void warning(String msg) {
		capture("WARN", msg);
		if (delegate != null) delegate.warning(msg);
	}

	// --- fine: notes the operator needs (an operation skipped) ---

	@Override
	public void fine(SipServletMessage msg, String comments) {
		capture("NOTE", comments);
		if (delegate != null) delegate.fine(msg, comments);
	}

	@Override
	public void fine(String msg) {
		if (delegate != null) delegate.fine(msg);
	}

	@Override
	public void info(String msg) {
		if (delegate != null) delegate.info(msg);
	}

	// --- finer: trace noise, never captured ---

	@Override
	public void finer(String msg) {
		if (delegate != null) delegate.finer(msg);
	}

	@Override
	public void finer(SipServletMessage msg, String comments) {
		if (delegate != null) delegate.finer(msg, comments);
	}

	@Override
	public void finer(SipSession s, String comments) {
		if (delegate != null) delegate.finer(s, comments);
	}

	@Override
	public void finer(SipApplicationSession s, String comments) {
		if (delegate != null) delegate.finer(s, comments);
	}
}
