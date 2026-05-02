package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One rule. Filters by `method`, `messageType`, and `event` (any null
/// filter is a wildcard), then runs `operations` top-to-bottom — order
/// matters, because a `read` further down can't feed a `create` further up.
///
/// `resetVariables: true` clears this rule's read variables from the session
/// before the rule fires. Useful when the same rule re-fires across a long
/// dialog and you don't want stale captures from earlier matches leaking
/// into a later create/update.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "id", "description", "method", "messageType", "event",
		"resetVariables", "operations" })
public class Rule implements Serializable {
	private static final long serialVersionUID = 1L;

	private String id;
	private String description;
	private String method;
	private String messageType;
	private String event;
	private boolean resetVariables;
	private List<Operation> operations = new LinkedList<>();

	public Rule() {
	}

	/// Returns true if this rule should fire for the given message and
	/// lifecycle event.
	public boolean matches(SipServletMessage msg, String lifecycleEvent) {
		if (method != null) {
			String msgMethod = (msg instanceof SipServletRequest)
					? ((SipServletRequest) msg).getMethod()
					: ((SipServletResponse) msg).getRequest().getMethod();
			if (!method.equalsIgnoreCase(msgMethod)) return false;
		}
		if (messageType != null) {
			boolean isRequest = (msg instanceof SipServletRequest);
			if ("request".equalsIgnoreCase(messageType) && !isRequest) return false;
			if ("response".equalsIgnoreCase(messageType) && isRequest) return false;
		}
		if (event != null && lifecycleEvent != null && !event.equalsIgnoreCase(lifecycleEvent)) {
			return false;
		}
		return true;
	}

	/// Runs every operation against `msg` in declaration order. If
	/// `resetVariables` is set, this rule's read variables are removed from
	/// the session before the first operation runs.
	public void process(SipServletMessage msg) {
		if (resetVariables) {
			SipApplicationSession appSession = msg.getApplicationSession();
			if (appSession != null) {
				for (Operation op : operations) {
					for (String name : op.variableNames()) {
						appSession.removeAttribute(name);
					}
				}
			}
		}
		for (Operation op : operations) op.process(msg);
	}

	@JsonPropertyDescription("Unique identifier for this rule.")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("Human-readable description shown in the configurator.")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("SIP method filter, e.g. INVITE, BYE, INFO. Null matches any method.")
	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	@JsonPropertyDescription("Message type filter: request or response. Null matches both.")
	public String getMessageType() {
		return messageType;
	}

	public void setMessageType(String messageType) {
		this.messageType = messageType;
	}

	@JsonPropertyDescription("Lifecycle event filter: callStarted, callAnswered, callConnected, callCompleted, callDeclined, callAbandoned, requestEvent, responseEvent. Null matches all.")
	public String getEvent() {
		return event;
	}

	public void setEvent(String event) {
		this.event = event;
	}

	@JsonPropertyDescription("Clear this rule's read variables from the session before running. Default false.")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	public boolean isResetVariables() {
		return resetVariables;
	}

	public void setResetVariables(boolean resetVariables) {
		this.resetVariables = resetVariables;
	}

	@JsonPropertyDescription("Ordered list of operations: read / create / update / delete and the xml/json/sdp variants.")
	public List<Operation> getOperations() {
		return operations;
	}

	public void setOperations(List<Operation> operations) {
		this.operations = operations;
	}
}
