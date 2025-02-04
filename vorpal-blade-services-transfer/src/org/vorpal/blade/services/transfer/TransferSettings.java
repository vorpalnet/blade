package org.vorpal.blade.services.transfer;

import java.io.Serializable;
import java.util.ArrayList;

import org.vorpal.blade.framework.v2.config.RouterConfig;

public class TransferSettings extends RouterConfig implements Serializable {
	private static final long serialVersionUID = 2L;

	public enum TransferStyle {
		blind, attended, conference
	};

	protected Boolean transferAllRequests;
	protected TransferStyle defaultTransferStyle;
	protected String conferenceApp;

	protected String allow = "MESSAGE, REFER, NOTIFY, CANCEL, ACK, UPDATE, PRACK, OPTIONS, INVITE, INFO, SUBSCRIBE, BYE";
	protected ArrayList<String> preserveInviteHeaders = new ArrayList<>();
	protected ArrayList<String> preserveReferHeaders = new ArrayList<>();

	public ArrayList<String> getPreserveReferHeaders() {
		return preserveReferHeaders;
	}

	public void setPreserveReferHeaders(ArrayList<String> preserveReferHeaders) {
		this.preserveReferHeaders = preserveReferHeaders;
	}

	public TransferSettings() {

	}

	public ArrayList<String> getPreserveInviteHeaders() {
		return preserveInviteHeaders;
	}

	public void setPreserveInviteHeaders(ArrayList<String> preserveInviteHeaders) {
		this.preserveInviteHeaders = preserveInviteHeaders;
	}

	public String getAllow() {
		return allow;
	}

	public TransferSettings setAllow(String allow) {
		this.allow = allow;
		return this;
	}

	public Boolean getTransferAllRequests() {
		return transferAllRequests;
	}

	public void setTransferAllRequests(Boolean transferAllRequests) {
		this.transferAllRequests = transferAllRequests;
	}

	public TransferStyle getDefaultTransferStyle() {
		return defaultTransferStyle;
	}

	public void setDefaultTransferStyle(TransferStyle defaultTransferStyle) {
		this.defaultTransferStyle = defaultTransferStyle;
	}
}
