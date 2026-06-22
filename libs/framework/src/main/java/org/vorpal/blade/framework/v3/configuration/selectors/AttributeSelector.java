package org.vorpal.blade.framework.v3.configuration.selectors;

import java.io.Serializable;
import java.util.ListIterator;
import java.util.logging.Level;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.FormLayoutGroup;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Reads a named attribute and stores its raw value in the SIP
/// session. No transformation — the value goes through verbatim.
///
/// Payload sources (via [Selector#readSource]):
///
/// - `Map<String,String>` (REST/JDBC/LDAP/Map connectors) — `attribute`
///   is the map key.
/// - [javax.servlet.sip.SipServletRequest] (SipConnector) —
///   `attribute` is a SIP header name, with pseudo-headers
///   `requestURI`, `originIP`, `body` handled directly (see
///   [Selector#readSource] for the full set).
///
/// With [#isAllInstances] set, a repeating SIP header (RFC 3261 permits the
/// same header on multiple lines or as a comma list) is read in FULL — every
/// instance joined by [Context#MULTI_VALUE_DELIMITER] — so a `matches` /
/// `contains` condition tests *any* instance. Default reads only the first.
///
/// If you need regex parsing on top of the raw value, chain a
/// [RegexSelector] after this one in the same connector's selectors.
@JsonPropertyOrder({ "type", "id", "attribute", "allInstances" })
@FormLayoutGroup({ "id", "attribute" })
public class AttributeSelector extends Selector implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Read every instance of a repeating header rather than just the first.
	/// Only affects a [SipServletRequest] payload reading a real (repeating)
	/// header; no effect on map payloads or single-valued pseudo-headers.
	private boolean allInstances;

	public AttributeSelector() {
	}

	public AttributeSelector(String id, String attribute) {
		this.id = id;
		this.attribute = attribute;
	}

	public AttributeSelector(String id, String attribute, boolean allInstances) {
		this.id = id;
		this.attribute = attribute;
		this.allInstances = allInstances;
	}

	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	@JsonPropertyDescription("Read every instance of a repeating header (joined) so a condition tests any instance; default reads the first only")
	public boolean isAllInstances() {
		return allInstances;
	}

	public void setAllInstances(boolean allInstances) {
		this.allInstances = allInstances;
	}

	@Override
	public void extract(Context ctx, Object payload) {
		if (attribute == null) return;

		String raw = (allInstances && payload instanceof SipServletRequest)
				? readAllInstances((SipServletRequest) payload, attribute)
				: readSource(payload, attribute);
		if (raw == null) return;

		store(ctx, id, raw);

		Logger sipLogger = SettingsManager.getSipLogger();
		if (sipLogger != null && sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(ctx != null ? ctx.getRequest() : null,
					"AttributeSelector[" + id + "] attribute=" + attribute + " value=" + raw);
		}
	}

	/// Reads every instance of a repeating header, joined by
	/// [Context#MULTI_VALUE_DELIMITER]. Falls back to [Selector#readSource]
	/// (the single/first read) for a pseudo-header, a header with no instances,
	/// or a container-managed "system" header that `getHeaders` rejects — so
	/// `allInstances` is safe to set on any attribute.
	private static String readAllInstances(SipServletRequest request, String name) {
		try {
			@SuppressWarnings("unchecked")
			ListIterator<String> it = request.getHeaders(name);
			if (it != null && it.hasNext()) {
				StringBuilder sb = new StringBuilder();
				while (it.hasNext()) {
					if (sb.length() > 0) sb.append(Context.MULTI_VALUE_DELIMITER);
					sb.append(it.next());
				}
				return sb.toString();
			}
		} catch (Exception e) {
			// Pseudo-header, system header, or container quirk — fall back.
		}
		return readSource(request, name);
	}
}
