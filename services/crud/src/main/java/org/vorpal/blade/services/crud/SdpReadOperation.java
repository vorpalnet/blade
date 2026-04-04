package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Extracts values from SDP body content by converting SDP to JSON
 * and evaluating JsonPath expressions. Results are saved as
 * SipApplicationSession attributes.
 *
 * <p>Example expressions:
 * <ul>
 *   <li>{@code $.connection.address} — session-level connection address</li>
 *   <li>{@code $.media[0].port} — first media line port</li>
 *   <li>{@code $.media[0].attributes[?(@.name=='label')].value} — label attribute of first media</li>
 *   <li>{@code $.origin.address} — origin address</li>
 * </ul>
 */
@JsonPropertyOrder({ "contentType", "expressions" })
public class SdpReadOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private Map<String, String> expressions = new LinkedHashMap<>();

	public SdpReadOperation() {
	}

	/**
	 * Converts the SDP body to JSON, evaluates each JsonPath expression,
	 * and saves results to the SipApplicationSession.
	 */
	public void process(SipServletMessage msg) {
		try {
			String sdp = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (sdp == null || sdp.isEmpty()) {
				return;
			}

			SipApplicationSession appSession = msg.getApplicationSession();

			for (Map.Entry<String, String> entry : expressions.entrySet()) {
				String attrName = entry.getKey();
				String jsonPath = entry.getValue();
				String value = SdpHelper.readValue(sdp, jsonPath);
				if (value != null && !value.isEmpty()) {
					appSession.setAttribute(attrName, value);
					SettingsManager.getSipLogger().finer(msg,
							"SdpReadOperation - saved " + attrName + "=" + value);
				}
			}

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("Content type for targeting a specific MIME part, e.g. application/sdp. Null reads entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("Map of attribute name to JsonPath expression on the SDP-as-JSON, e.g. {\"mediaAddr\": \"$.media[0].connection.address\"}")
	public Map<String, String> getExpressions() {
		return expressions;
	}

	public void setExpressions(Map<String, String> expressions) {
		this.expressions = expressions;
	}
}
