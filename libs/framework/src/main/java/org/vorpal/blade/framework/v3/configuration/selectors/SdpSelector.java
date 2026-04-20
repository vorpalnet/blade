package org.vorpal.blade.framework.v3.configuration.selectors;

import java.io.Serializable;
import java.util.logging.Level;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.FormLayoutGroup;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Extracts the first matching SDP line for a single-letter field
/// code (`v`, `o`, `s`, `c`, `t`, `m`, `a`, …). Returns the text
/// after `X=` and stores it under this selector's `id`.
///
/// To dissect the line further (e.g. pull just the IP out of
/// `c=IN IP4 192.168.1.1`), chain a [RegexSelector] after this one
/// — it can read `attribute=<thisId>` from the session and apply a
/// regex like `IN IP4 (?<ip>[\d.]+)` with `expression=${ip}`.
@JsonPropertyOrder({ "type", "id", "description", "attribute", "index", "applicationSession" })
@FormLayoutGroup({ "id", "attribute" })
public class SdpSelector extends Selector implements Serializable {
	private static final long serialVersionUID = 1L;

	public SdpSelector() {
	}

	@Override
	public void extract(Context ctx, Object payload) {
		if (attribute == null) return;

		String text = textPayload(payload);
		if (text == null || text.isEmpty()) return;

		String prefix = attribute + "=";
		String raw = null;
		for (String line : text.split("\\r?\\n")) {
			if (line.startsWith(prefix)) {
				raw = line.substring(prefix.length());
				break;
			}
		}
		if (raw == null) return;

		store(ctx, id, raw);

		Logger sipLogger = SettingsManager.getSipLogger();
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer("SdpSelector[" + id + "] field=" + attribute + " value=" + raw);
		}
	}

	private static String textPayload(Object payload) {
		if (payload instanceof String) return (String) payload;
		if (payload instanceof byte[]) return new String((byte[]) payload);
		if (payload instanceof SipServletRequest) {
			try {
				Object content = ((SipServletRequest) payload).getContent();
				if (content instanceof String) return (String) content;
				if (content instanceof byte[]) return new String((byte[]) content);
			} catch (Exception ignore) {
			}
		}
		return null;
	}
}
