package org.vorpal.blade.services.transfer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;

import org.vorpal.blade.framework.transfer.TransferCondition;

public class TransferSettings implements Serializable {

	public enum TransferStyle {
		blind, assisted, media
	};

	public enum LoggingLevel {
		OFF, SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST, ALL
	}

	protected LoggingLevel loggingLevel = LoggingLevel.INFO;
	protected Boolean transferAllRequests;
	protected TransferStyle defaultTransferStyle;
	protected ArrayList<String> preserveInviteHeaders = new ArrayList<>();
	protected LinkedList<TransferCondition> transferConditions = new LinkedList<>();

	public TransferSettings() {

	}

	public LoggingLevel getLoggingLevel() {
		return loggingLevel;
	}

	public void setLoggingLevel(LoggingLevel loggingLevel) {
		this.loggingLevel = loggingLevel;
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
