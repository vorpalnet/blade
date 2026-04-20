package org.vorpal.blade.framework.v3.configuration.connectors;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// In-memory key→attributes lookup. Resolves `${keyExpression}`
/// from session state and finds the matching entry. The matched
/// entry is passed as a `Map<String,String>` payload to each
/// [org.vorpal.blade.framework.v3.configuration.selectors.Selector].
///
/// If no selectors are configured, the matched entry's values are
/// dumped wholesale into the SIP session — the common case for
/// credential lookups (customerId, apiKey, etc.).
@JsonPropertyOrder({ "type", "id", "description", "keyExpression", "entries", "selectors" })
public class MapConnector extends Connector implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String keyExpression;
	protected Map<String, Map<String, String>> entries = new LinkedHashMap<>();

	public MapConnector() {
	}

	@JsonPropertyDescription("${var} template that resolves to the lookup key, e.g. ${to-host}")
	public String getKeyExpression() { return keyExpression; }
	public void setKeyExpression(String keyExpression) { this.keyExpression = keyExpression; }

	@JsonPropertyDescription("Map of key to attributes; the matched entry is the payload")
	public Map<String, Map<String, String>> getEntries() { return entries; }
	public void setEntries(Map<String, Map<String, String>> entries) {
		this.entries = (entries != null) ? entries : new LinkedHashMap<>();
	}

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		Logger sipLogger = SettingsManager.getSipLogger();
		if (keyExpression == null) return CompletableFuture.completedFuture(null);

		String key = ctx.resolve(keyExpression);
		if (key == null || key.equals(keyExpression)) {
			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer("MapConnector[" + id + "] key resolved to null; skipping");
			}
			return CompletableFuture.completedFuture(null);
		}

		Map<String, String> matched = entries.get(key);
		if (matched == null) {
			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer("MapConnector[" + id + "] no entry for key=" + key);
			}
			return CompletableFuture.completedFuture(null);
		}

		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer("MapConnector[" + id + "] matched key=" + key + " → " + matched.keySet());
		}

		if (selectors == null || selectors.isEmpty()) {
			// Default behaviour: dump every matched key/value into SipSession.
			for (Map.Entry<String, String> e : matched.entrySet()) {
				ctx.put(e.getKey(), e.getValue());
			}
		} else {
			runSelectors(ctx, matched);
		}
		return CompletableFuture.completedFuture(null);
	}
}
