package org.vorpal.blade.services.transfer;

import java.io.Serializable;
import java.util.ArrayList;

import org.vorpal.blade.framework.config.RouterConfig;

public class TransferSettings extends RouterConfig implements Serializable {
	private static final long serialVersionUID = 2L;

	public enum TransferStyle {
		none, blind, attended, conference
	};

//	protected Boolean transferAllRequests;
//	protected TransferStyle defaultStyle;

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

//	public Boolean getTransferAllRequests() {
//		return transferAllRequests;
//	}
//
//	public void setTransferAllRequests(Boolean transferAllRequests) {
//		this.transferAllRequests = transferAllRequests;
//	}

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

//	public TransferStyle getDefaultStyle() {
//		return defaultStyle;
//	}
//
//	public TransferSettings setDefaultStyle(TransferStyle defaultStyle) {
//		this.defaultStyle = defaultStyle;
//		return this;
//	}

}
