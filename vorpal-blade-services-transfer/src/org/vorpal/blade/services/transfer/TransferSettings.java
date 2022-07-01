package org.vorpal.blade.services.transfer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;

import org.vorpal.blade.framework.config.Condition;
import org.vorpal.blade.framework.transfer.TransferCondition;
import org.vorpal.blade.services.transfer.TransferSettings.TransferStyle;

public class TransferSettings implements Serializable {
	public enum TransferStyle {
		blind, assisted, media
	};

	private Boolean transferAllRequests;
	private TransferStyle defaultTransferStyle;
	private ArrayList<String> preserveInviteHeaders = new ArrayList<>();
	private LinkedList<TransferCondition> transferConditions = new LinkedList<>();

	public TransferSettings() {
		this.setTransferAllRequests(false);
		this.setDefaultTransferStyle(TransferSettings.TransferStyle.blind);

		TransferCondition tc1 = new TransferCondition();
		tc1.setStyle(TransferStyle.blind);
		tc1.getCondition().addComparison("OSM-Features", "includes", "transfer");
		this.getTransferConditions().add(tc1);

		TransferCondition tc2 = new TransferCondition();
		tc2.setStyle(TransferStyle.blind);
		tc2.getCondition().addComparison("Refer-To", "matches", ".*sip:1996.*");
		this.getTransferConditions().add(tc2);

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

	public TransferStyle getDefaultTransferStyle() {
		return defaultTransferStyle;
	}

	public void setDefaultTransferStyle(TransferStyle defaultTransferStyle) {
		this.defaultTransferStyle = defaultTransferStyle;
	}

	public LinkedList<TransferCondition> getTransferConditions() {
		return transferConditions;
	}

	public void setTransferConditions(LinkedList<TransferCondition> transferConditions) {
		this.transferConditions = transferConditions;
	}

}
