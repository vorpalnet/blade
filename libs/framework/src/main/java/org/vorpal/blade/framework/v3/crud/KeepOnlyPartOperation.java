package org.vorpal.blade.framework.v3.crud;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Collapses a multipart body down to only the part(s) matching
/// `contentType`. The classic use is stripping a SIPREC INVITE to bare SDP
/// (`contentType: application/sdp`) so a plain softphone can parse it —
/// the inverse of attaching metadata with a `create` operation.
///
/// No-op (with a warning) when the message isn't multipart or no part
/// matches, so a misconfigured rule never destroys a body.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "contentType" })
public class KeepOnlyPartOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String contentType;

	public KeepOnlyPartOperation() {
	}

	public KeepOnlyPartOperation(String contentType) {
		this.contentType = contentType;
	}

	@Override
	public void process(SipServletMessage msg) {
		try {
			if (msg.getContent() == null) return;
			boolean kept = MimeHelper.keepOnlyPart(msg, contentType);
			if (kept) {
				SettingsManager.getSipLogger().finer(msg,
						"KeepOnlyPartOperation - kept only " + contentType + " part(s)");
			} else if (msg.getContentType() != null
					&& msg.getContentType().toLowerCase().startsWith("multipart/")) {
				SettingsManager.getSipLogger().warning(msg,
						"KeepOnlyPartOperation - no " + contentType + " part in multipart body; leaving body alone");
			}
		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("MIME content type of the part(s) to keep, e.g. application/sdp. All other parts are removed.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
}
