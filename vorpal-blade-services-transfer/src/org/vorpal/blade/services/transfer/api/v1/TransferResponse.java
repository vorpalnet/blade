package org.vorpal.blade.services.transfer.api.v1;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"event", "method", "status", "description", "request"})
public class TransferResponse implements Serializable {
	private static final long serialVersionUID = 1L;
	public String event;
	public String method;
	public Integer status;
	public String description;
	public TransferRequest request;
}
