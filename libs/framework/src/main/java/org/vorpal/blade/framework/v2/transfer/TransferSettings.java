package org.vorpal.blade.framework.v2.transfer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.vorpal.blade.framework.v2.config.RouterConfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Configuration settings for transfer operations.
 *
 * <p>Defines transfer style defaults, allowed SIP methods, and header
 * preservation rules for INVITE and REFER requests.
 */
public class TransferSettings extends RouterConfig implements Serializable {
	private static final long serialVersionUID = 2L;

	/** Default allowed SIP methods. */
	private static final String DEFAULT_ALLOW = "MESSAGE, REFER, NOTIFY, CANCEL, ACK, UPDATE, PRACK, OPTIONS, INVITE, INFO, SUBSCRIBE, BYE";

	public enum TransferStyle {
		blind, attended, conference, refer
	}

	@JsonProperty(defaultValue = "false")
	protected Boolean transferAllRequests;

	@JsonProperty(defaultValue = "attended")
	protected TransferStyle defaultTransferStyle;

	@JsonPropertyDescription("SIP application name to handle conference calls")
	protected String conferenceApp;

	protected String allow = DEFAULT_ALLOW;

	protected List<String> preserveInviteHeaders = new ArrayList<>();

	protected List<String> preserveReferHeaders = new ArrayList<>();

	@JsonPropertyDescription("List of SIP header names to preserve when forwarding REFER requests during transfer")
	public List<String> getPreserveReferHeaders() {
		return preserveReferHeaders;
	}

	public void setPreserveReferHeaders(List<String> preserveReferHeaders) {
		this.preserveReferHeaders = preserveReferHeaders;
	}

	public TransferSettings() {
		// Default constructor
	}

	@JsonPropertyDescription("List of SIP header names to preserve when forwarding INVITE requests during transfer")
	public List<String> getPreserveInviteHeaders() {
		return preserveInviteHeaders;
	}

	public void setPreserveInviteHeaders(List<String> preserveInviteHeaders) {
		this.preserveInviteHeaders = preserveInviteHeaders;
	}

	@JsonPropertyDescription("Comma-separated list of allowed SIP methods for transfer operations")
	public String getAllow() {
		return allow;
	}

	public TransferSettings setAllow(String allow) {
		this.allow = allow;
		return this;
	}

	@JsonPropertyDescription("When true, all requests are eligible for transfer, not just INVITE")
	public Boolean getTransferAllRequests() {
		return transferAllRequests;
	}

	public void setTransferAllRequests(Boolean transferAllRequests) {
		this.transferAllRequests = transferAllRequests;
	}

	@JsonPropertyDescription("Default transfer method: blind, attended, conference, or refer")
	public TransferStyle getDefaultTransferStyle() {
		if (defaultTransferStyle != null) {
			return defaultTransferStyle;
		} else {
			return TransferStyle.blind;
		}
	}

	public void setDefaultTransferStyle(TransferStyle defaultTransferStyle) {
		this.defaultTransferStyle = defaultTransferStyle;
	}
}
