package org.vorpal.blade.services.transfer;

import java.io.Serializable;
import java.util.ArrayList;

public class TransferSettings implements Serializable {

	private Boolean transferAllRequests;
	private String featureEnableHeader;
	private String featureEnableValue;
	private ArrayList<String> preserveInviteHeaders;

	public TransferSettings() {
		transferAllRequests = false;
		featureEnableHeader = "OSM-Features";
		featureEnableValue = "transfer";

		preserveInviteHeaders = new ArrayList<>();
		preserveInviteHeaders.add("Cisco-GUCID");
		preserveInviteHeaders.add("User-to-User");
	}

	public Boolean getTransferAllRequests() {
		return transferAllRequests;
	}

	public void setTransferAllRequests(Boolean transferAllRequests) {
		this.transferAllRequests = transferAllRequests;
	}

	public String getFeatureEnableHeader() {
		return featureEnableHeader;
	}

	public void setFeatureEnableHeader(String featureEnableHeader) {
		this.featureEnableHeader = featureEnableHeader;
	}

	public String getFeatureEnableValue() {
		return featureEnableValue;
	}

	public void setFeatureEnableValue(String featureEnableValue) {
		this.featureEnableValue = featureEnableValue;
	}

	public ArrayList<String> getPreserveInviteHeaders() {
		return preserveInviteHeaders;
	}

	public void setPreserveInviteHeaders(ArrayList<String> preserveInviteHeaders) {
		this.preserveInviteHeaders = preserveInviteHeaders;
	}

}
