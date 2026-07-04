package org.vorpal.blade.framework.v3.diagnostics;

import org.vorpal.blade.framework.v3.CallStep;

/// [TraceMXBean] implementation — a thin JMX face over the app's [TraceLog]
/// statics. JSON is built by hand: the shapes are flat and fixed, and this
/// runs on engine nodes where dragging in a mapper for two strings isn't worth
/// it.
public class Trace implements TraceMXBean {

	private final String appName;

	public Trace(String appName) {
		this.appName = appName;
	}

	@Override
	public String getStepsJson() {
		StringBuilder json = new StringBuilder();
		json.append("{\"app\":").append(quote(appName)).append(",\"steps\":[");
		boolean first = true;
		for (CallStep s : TraceLog.snapshot()) {
			if (!first) {
				json.append(',');
			}
			first = false;
			json.append("{\"sessionId\":").append(quote(s.getSessionId()))
					.append(",\"epochMillis\":").append(s.getEpochMillis())
					.append(",\"order\":").append(s.getOrder())
					.append(",\"direction\":").append(quote(s.getDirection()))
					.append(",\"kind\":").append(quote(s.getKind()))
					.append(",\"label\":").append(quote(s.getLabel()))
					.append(",\"className\":").append(quote(s.getClassName()))
					.append(",\"methodName\":").append(quote(s.getMethodName()))
					.append(",\"line\":").append(s.getLine())
					.append(",\"message\":").append(quote(s.getMessage()))
					.append('}');
		}
		json.append("]}");
		return json.toString();
	}

	@Override
	public String getRulesJson() {
		Diagnostics d = TraceLog.diagnostics();
		StringBuilder json = new StringBuilder();
		json.append("{\"enabled\":").append(d.isEnabled()).append(",\"rules\":[");
		boolean first = true;
		for (TraceRule r : d.getRules()) {
			if (r == null) {
				continue;
			}
			if (!first) {
				json.append(',');
			}
			first = false;
			String attribute = r.getSelector() != null ? r.getSelector().getAttribute() : null;
			String pattern = r.getSelector() != null ? r.getSelector().getPattern() : null;
			json.append("{\"label\":").append(quote(r.getLabel()))
					.append(",\"attribute\":").append(quote(attribute))
					.append(",\"pattern\":").append(quote(pattern))
					.append(",\"maxCaptures\":").append(r.getMaxCaptures())
					.append(",\"captured\":").append(r.getCaptured())
					.append(",\"exhausted\":").append(r.isExhausted())
					.append('}');
		}
		json.append("]}");
		return json.toString();
	}

	@Override
	public void arm(String label, String attribute, String pattern, int maxCaptures) {
		TraceLog.arm(label, attribute, pattern, maxCaptures);
	}

	@Override
	public void disarm() {
		TraceLog.disarm();
	}

	@Override
	public void clearSteps() {
		TraceLog.clear();
	}

	static String quote(String s) {
		if (s == null) {
			return "null";
		}
		StringBuilder out = new StringBuilder("\"");
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"':
				out.append("\\\"");
				break;
			case '\\':
				out.append("\\\\");
				break;
			case '\n':
				out.append("\\n");
				break;
			case '\r':
				out.append("\\r");
				break;
			case '\t':
				out.append("\\t");
				break;
			default:
				if (c < 0x20) {
					out.append(String.format("\\u%04x", (int) c));
				} else {
					out.append(c);
				}
			}
		}
		return out.append('"').toString();
	}
}
