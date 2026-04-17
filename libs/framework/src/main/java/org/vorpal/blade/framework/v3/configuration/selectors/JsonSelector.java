package org.vorpal.blade.framework.v3.configuration.selectors;

import java.io.Serializable;
import java.util.logging.Level;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

/// Extracts a value from a JSON payload via [JsonPath]. The
/// `attribute` field is the JsonPath expression (e.g.
/// `$.actionDirective`). Stores the raw extracted value under
/// this selector's `id`. Chain a [RegexSelector] after this one if
/// you need regex transformation on top.
///
/// Reads from:
/// - String payload (REST response body, etc.)
/// - byte[] payload
/// - SIP request body (`getContent()`) when the payload is a
///   [SipServletRequest]
@JsonPropertyOrder({ "type", "id", "description", "attribute", "index", "applicationSession" })
public class JsonSelector extends Selector implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final ObjectMapper mapper = new ObjectMapper();
	private static final com.jayway.jsonpath.Configuration JSON_CFG =
			com.jayway.jsonpath.Configuration.builder()
					.jsonProvider(new JacksonJsonNodeJsonProvider())
					.mappingProvider(new JacksonMappingProvider())
					.build();

	public JsonSelector() {
	}

	@Override
	public void extract(Context ctx, Object payload) {
		if (attribute == null) return;

		String text = textPayload(payload);
		if (text == null || text.isEmpty()) return;

		Logger sipLogger = SettingsManager.getSipLogger();

		try {
			JsonNode root = mapper.readTree(text);
			String raw = JsonPath.using(JSON_CFG).parse(root).read(attribute, String.class);
			if (raw == null) return;

			store(ctx, id, raw);

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer("JsonSelector[" + id + "] path=" + attribute + " value=" + raw);
			}
		} catch (Exception e) {
			sipLogger.warning("JsonSelector[" + id + "] failed: " + e.getMessage());
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
