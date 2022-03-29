package org.vorpal.blade.services.transfer;

import java.io.Serializable;
import java.util.ArrayList;

import org.vorpal.blade.framework.config.Condition;

public class TransferSettings implements Serializable {
	public enum TransferType { blind, assisted, media };
	
	private Boolean transferAllRequests;
	private Condition featureEnable;
	private Condition blindTransfer;
	private Condition assistedTransfer;
	private Condition mediaTransfer;
	private TransferType defaultTransferType;
	private ArrayList<String> preserveInviteHeaders;

	public TransferSettings() {
		defaultTransferType = TransferType.blind;
		transferAllRequests = false;
		
		featureEnable = new Condition();
		featureEnable.addComparison("OSM-Features", "includes", "transfer");

		blindTransfer = new Condition();
		blindTransfer.addComparison("Request-URI", "txfer", "blind");
		
		assistedTransfer = new Condition();
		assistedTransfer.addComparison("Request-URI", "txfer", "assisted");
		
		mediaTransfer = new Condition();
		mediaTransfer.addComparison("Request-URI", "txfer", "media");

		preserveInviteHeaders = new ArrayList<>();
		preserveInviteHeaders.add("Cisco-Gucid");
		preserveInviteHeaders.add("User-to-User");
	}

	public Boolean getTransferAllRequests() {
		return transferAllRequests;
	}

	public void setTransferAllRequests(Boolean transferAllRequests) {
		this.transferAllRequests = transferAllRequests;
	}



	public ArrayList<String> getPreserveInviteHeaders() {
		return preserveInviteHeaders;
	}

	public void setPreserveInviteHeaders(ArrayList<String> preserveInviteHeaders) {
		this.preserveInviteHeaders = preserveInviteHeaders;
	}

	public Condition getFeatureEnable() {
		return featureEnable;
	}

	public void setFeatureEnable(Condition featureEnable) {
		this.featureEnable = featureEnable;
	}

	public Condition getBlindTransfer() {
		return blindTransfer;
	}

	public void setBlindTransfer(Condition blindTransfer) {
		this.blindTransfer = blindTransfer;
	}

	public Condition getAssistedTransfer() {
		return assistedTransfer;
	}

	public void setAssistedTransfer(Condition assistedTransfer) {
		this.assistedTransfer = assistedTransfer;
	}

	public Condition getMediaTransfer() {
		return mediaTransfer;
	}

	public void setMediaTransfer(Condition mediaTransfer) {
		this.mediaTransfer = mediaTransfer;
	}

	public TransferType getDefaultTransferType() {
		return defaultTransferType;
	}

	public void setDefaultTransferType(TransferType defaultTransferType) {
		this.defaultTransferType = defaultTransferType;
	}

}
