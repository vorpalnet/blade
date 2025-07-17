package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "event", "method", "status", "description", "request" })
public class TransferResponse implements Serializable {
	private static final long serialVersionUID = 1L;
	public String event;
	public String method;
	public Integer status;
	public String description;
	public TransferRequest request;
}
