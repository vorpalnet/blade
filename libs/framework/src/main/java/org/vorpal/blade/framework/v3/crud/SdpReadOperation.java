package org.vorpal.blade.framework.v3.crud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Reads values from an SDP body. The SDP is parsed to a JSON tree (using
/// the framework's native [org.vorpal.blade.framework.v2.sdp.Sdp] model) so
/// expressions like `$.media[0].port` and
/// `$.media[0].attributes[?(@.name=='rtpmap')].value` work directly.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SdpReadOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private Map<String, String> expressions = new LinkedHashMap<>();

	public SdpReadOperation() {
	}

	@Override
	public void process(SipServletMessage msg) {
		try {
			String sdp = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (sdp == null || sdp.isEmpty()) return;

			SipApplicationSession appSession = msg.getApplicationSession();
			for (Map.Entry<String, String> entry : expressions.entrySet()) {
				String value = SdpHelper.readValue(sdp, entry.getValue());
				if (value != null && !value.isEmpty()) {
					appSession.setAttribute(entry.getKey(), value);
					SettingsManager.getSipLogger().finer(msg,
							"SdpReadOperation - saved " + entry.getKey() + "=" + value);
				}
			}
		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@Override
	public List<String> variableNames() {
		return new ArrayList<>(expressions.keySet());
	}

	@JsonPropertyDescription("Optional MIME content type, e.g. application/sdp. Null reads the entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("Map of variable name to JsonPath against the SDP-as-JSON, e.g. {\"port\": \"$.media[0].port\"}")
	public Map<String, String> getExpressions() {
		return expressions;
	}

	public void setExpressions(Map<String, String> expressions) {
		this.expressions = expressions;
	}
}
