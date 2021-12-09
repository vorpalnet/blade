package org.vorpal.blade.services.transfer;

import java.io.Serializable;
import java.util.HashMap;

public class TransferSettings implements Serializable {

	private Boolean transferAllRequests;
	private String featureEnableHeader;
	private String featureEnableValue;

	public TransferSettings() {
		transferAllRequests = false;
		featureEnableHeader = "OSM-Features";
		featureEnableValue = "transfer";
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

}
