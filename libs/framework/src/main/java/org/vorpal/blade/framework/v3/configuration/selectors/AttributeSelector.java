package org.vorpal.blade.framework.v3.configuration.selectors;

import java.io.Serializable;
import java.util.logging.Level;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Reads a named attribute and stores its raw value in the SIP
/// session. No transformation — the value goes through verbatim.
///
/// Payload sources (via [Selector#readSource]):
///
/// - `Map<String,String>` (REST/JDBC/LDAP/Map adapters) — `attribute`
///   is the map key.
/// - [javax.servlet.sip.SipServletRequest] (SipAdapter) —
///   `attribute` is a SIP header name, with pseudo-headers
///   `Request-URI`, `Remote-IP`, `content`/`body` handled directly.
///
/// If you need regex parsing on top of the raw value, chain a
/// [RegexSelector] after this one in the same adapter's selectors.
@JsonPropertyOrder({ "type", "id", "description", "attribute", "index", "applicationSession" })
public class AttributeSelector extends Selector implements Serializable {
	private static final long serialVersionUID = 1L;

	public AttributeSelector() {
	}

	public AttributeSelector(String id, String attribute) {
		this.id = id;
		this.attribute = attribute;
	}

	@Override
	public void extract(Context ctx, Object payload) {
		if (attribute == null) return;

		String raw = readSource(payload, attribute);
		if (raw == null) return;

		store(ctx, id, raw);

		Logger sipLogger = SettingsManager.getSipLogger();
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer("AttributeSelector[" + id + "] attribute=" + attribute + " value=" + raw);
		}
	}
}
