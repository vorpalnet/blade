package org.vorpal.blade.framework.v3.diagnostics;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

import org.vorpal.blade.framework.v2.config.Selector;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// One arming rule for per-call tracing: a [Selector] that decides WHICH calls
/// to trace, plus a capture cap so an armed rule disarms itself.
///
/// The Selector reuses BLADE's existing matching primitive — match on `From`,
/// `Request-URI`, any header — so "trace calls from +1-555-…" is just a Selector
/// on the `From` attribute. `maxCaptures` is the footgun guard: a busy `From`
/// could match thousands of calls, so a rule grabs at most `maxCaptures` of them
/// and then stops matching, even if an operator forgets to remove it. Set
/// `maxCaptures <= 0` for unlimited (use with a TTL / manual removal).
public class TraceRule implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String label;
	protected Selector selector;
	protected int maxCaptures = 5;

	/// Runtime-only counter of how many calls this rule has captured. Not
	/// persisted in the config JSON — it resets when the config is (re)loaded,
	/// which is the desired semantics (re-publishing a rule re-arms it).
	@JsonIgnore
	private final transient AtomicInteger captured = new AtomicInteger(0);

	public TraceRule() {
	}

	public TraceRule(String label, Selector selector, int maxCaptures) {
		this.label = label;
		this.selector = selector;
		this.maxCaptures = maxCaptures;
	}

	@JsonPropertyDescription("Human label for this trace rule, e.g. \"debugging customer_a dropped calls\".")
	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	@JsonPropertyDescription("Which calls to trace — a Selector (match on From, Request-URI, any header).")
	public Selector getSelector() {
		return selector;
	}

	public void setSelector(Selector selector) {
		this.selector = selector;
	}

	@JsonPropertyDescription("Capture at most this many matching calls, then stop (self-disarm). <= 0 means unlimited. Default 5.")
	public int getMaxCaptures() {
		return maxCaptures;
	}

	public void setMaxCaptures(int maxCaptures) {
		this.maxCaptures = maxCaptures;
	}

	@JsonIgnore
	public int getCaptured() {
		return captured.get();
	}

	/// Has this rule hit its cap? Once true it never matches again (until the
	/// config is re-published, which resets the counter).
	@JsonIgnore
	public boolean isExhausted() {
		return maxCaptures > 0 && captured.get() >= maxCaptures;
	}

	/// Try to claim a capture slot. Returns true (and increments) if this call
	/// should be traced under this rule; false if the cap is already reached.
	/// Atomic so concurrent calls on the engine can't overrun the cap.
	public boolean tryConsume() {
		if (maxCaptures <= 0) {
			captured.incrementAndGet();
			return true;
		}
		while (true) {
			int c = captured.get();
			if (c >= maxCaptures) {
				return false;
			}
			if (captured.compareAndSet(c, c + 1)) {
				return true;
			}
		}
	}
}
