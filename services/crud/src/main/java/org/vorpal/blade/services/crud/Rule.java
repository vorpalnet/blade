package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A rule that filters by SIP method, message type, and lifecycle event,
 * then applies an ordered sequence of CRUD operations.
 * Null filter fields act as wildcards (match all).
 */
@JsonPropertyOrder({ "id", "description", "method", "messageType", "event", "read", "create", "update", "delete", "xpathRead", "xpathCreate", "xpathUpdate", "xpathDelete", "jsonPathRead", "jsonPathCreate", "jsonPathUpdate", "jsonPathDelete", "sdpRead", "sdpCreate", "sdpUpdate", "sdpDelete" })
public class Rule implements Serializable {
	private static final long serialVersionUID = 1L;

	private String id;
	private String description;
	private String method;
	private String messageType;
	private String event;
	private List<ReadOperation> read = new LinkedList<>();
	private List<CreateOperation> create = new LinkedList<>();
	private List<UpdateOperation> update = new LinkedList<>();
	private List<DeleteOperation> delete = new LinkedList<>();
	private List<XPathReadOperation> xpathRead = new LinkedList<>();
	private List<XPathCreateOperation> xpathCreate = new LinkedList<>();
	private List<XPathUpdateOperation> xpathUpdate = new LinkedList<>();
	private List<XPathDeleteOperation> xpathDelete = new LinkedList<>();
	private List<JsonPathReadOperation> jsonPathRead = new LinkedList<>();
	private List<JsonPathCreateOperation> jsonPathCreate = new LinkedList<>();
	private List<JsonPathUpdateOperation> jsonPathUpdate = new LinkedList<>();
	private List<JsonPathDeleteOperation> jsonPathDelete = new LinkedList<>();
	private List<SdpReadOperation> sdpRead = new LinkedList<>();
	private List<SdpCreateOperation> sdpCreate = new LinkedList<>();
	private List<SdpUpdateOperation> sdpUpdate = new LinkedList<>();
	private List<SdpDeleteOperation> sdpDelete = new LinkedList<>();

	public Rule() {
	}

	/**
	 * Checks whether this rule should fire for the given message and lifecycle event.
	 */
	public boolean matches(SipServletMessage msg, String lifecycleEvent) {
		if (method != null) {
			String msgMethod;
			if (msg instanceof SipServletRequest) {
				msgMethod = ((SipServletRequest) msg).getMethod();
			} else {
				msgMethod = ((SipServletResponse) msg).getRequest().getMethod();
			}
			if (!method.equalsIgnoreCase(msgMethod)) {
				return false;
			}
		}

		if (messageType != null) {
			boolean isRequest = (msg instanceof SipServletRequest);
			if ("request".equalsIgnoreCase(messageType) && !isRequest) {
				return false;
			}
			if ("response".equalsIgnoreCase(messageType) && isRequest) {
				return false;
			}
		}

		if (event != null && lifecycleEvent != null) {
			if (!event.equalsIgnoreCase(lifecycleEvent)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Applies all CRUD operations in order: read, create, update, delete
	 * (regex first, then XPath).
	 */
	public void process(SipServletMessage msg) {
		for (ReadOperation op : read) {
			op.process(msg);
		}
		for (XPathReadOperation op : xpathRead) {
			op.process(msg);
		}
		for (CreateOperation op : create) {
			op.process(msg);
		}
		for (XPathCreateOperation op : xpathCreate) {
			op.process(msg);
		}
		for (UpdateOperation op : update) {
			op.process(msg);
		}
		for (XPathUpdateOperation op : xpathUpdate) {
			op.process(msg);
		}
		for (DeleteOperation op : delete) {
			op.process(msg);
		}
		for (XPathDeleteOperation op : xpathDelete) {
			op.process(msg);
		}
		for (JsonPathReadOperation op : jsonPathRead) {
			op.process(msg);
		}
		for (JsonPathCreateOperation op : jsonPathCreate) {
			op.process(msg);
		}
		for (JsonPathUpdateOperation op : jsonPathUpdate) {
			op.process(msg);
		}
		for (JsonPathDeleteOperation op : jsonPathDelete) {
			op.process(msg);
		}
		for (SdpReadOperation op : sdpRead) {
			op.process(msg);
		}
		for (SdpCreateOperation op : sdpCreate) {
			op.process(msg);
		}
		for (SdpUpdateOperation op : sdpUpdate) {
			op.process(msg);
		}
		for (SdpDeleteOperation op : sdpDelete) {
			op.process(msg);
		}
	}

	@JsonPropertyDescription("Unique identifier for this rule")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("Description of this rule's purpose")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("SIP method filter, e.g. INVITE, BYE, INFO. Null matches all methods.")
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

	@JsonPropertyDescription("Lifecycle event filter: callStarted, callAnswered, requestEvent, etc. Null matches all.")
	public String getEvent() {
		return event;
	}

	public void setEvent(String event) {
		this.event = event;
	}

	@JsonPropertyDescription("Read operations: extract values from message into session attributes")
	public List<ReadOperation> getRead() {
		return read;
	}

	public void setRead(List<ReadOperation> read) {
		this.read = read;
	}

	@JsonPropertyDescription("Create operations: add headers or body content")
	public List<CreateOperation> getCreate() {
		return create;
	}

	public void setCreate(List<CreateOperation> create) {
		this.create = create;
	}

	@JsonPropertyDescription("Update operations: modify headers or body content using regex")
	public List<UpdateOperation> getUpdate() {
		return update;
	}

	public void setUpdate(List<UpdateOperation> update) {
		this.update = update;
	}

	@JsonPropertyDescription("Delete operations: remove headers or body content")
	public List<DeleteOperation> getDelete() {
		return delete;
	}

	public void setDelete(List<DeleteOperation> delete) {
		this.delete = delete;
	}

	@JsonPropertyDescription("XPath read operations: extract values from XML body into session attributes")
	public List<XPathReadOperation> getXpathRead() {
		return xpathRead;
	}

	public void setXpathRead(List<XPathReadOperation> xpathRead) {
		this.xpathRead = xpathRead;
	}

	@JsonPropertyDescription("XPath create operations: add elements or attributes to XML body")
	public List<XPathCreateOperation> getXpathCreate() {
		return xpathCreate;
	}

	public void setXpathCreate(List<XPathCreateOperation> xpathCreate) {
		this.xpathCreate = xpathCreate;
	}

	@JsonPropertyDescription("XPath update operations: modify XML node values")
	public List<XPathUpdateOperation> getXpathUpdate() {
		return xpathUpdate;
	}

	public void setXpathUpdate(List<XPathUpdateOperation> xpathUpdate) {
		this.xpathUpdate = xpathUpdate;
	}

	@JsonPropertyDescription("XPath delete operations: remove nodes from XML body")
	public List<XPathDeleteOperation> getXpathDelete() {
		return xpathDelete;
	}

	public void setXpathDelete(List<XPathDeleteOperation> xpathDelete) {
		this.xpathDelete = xpathDelete;
	}

	@JsonPropertyDescription("JsonPath read operations: extract values from JSON body into session attributes")
	public List<JsonPathReadOperation> getJsonPathRead() {
		return jsonPathRead;
	}

	public void setJsonPathRead(List<JsonPathReadOperation> jsonPathRead) {
		this.jsonPathRead = jsonPathRead;
	}

	@JsonPropertyDescription("JsonPath create operations: add properties to JSON body")
	public List<JsonPathCreateOperation> getJsonPathCreate() {
		return jsonPathCreate;
	}

	public void setJsonPathCreate(List<JsonPathCreateOperation> jsonPathCreate) {
		this.jsonPathCreate = jsonPathCreate;
	}

	@JsonPropertyDescription("JsonPath update operations: modify JSON values")
	public List<JsonPathUpdateOperation> getJsonPathUpdate() {
		return jsonPathUpdate;
	}

	public void setJsonPathUpdate(List<JsonPathUpdateOperation> jsonPathUpdate) {
		this.jsonPathUpdate = jsonPathUpdate;
	}

	@JsonPropertyDescription("JsonPath delete operations: remove properties from JSON body")
	public List<JsonPathDeleteOperation> getJsonPathDelete() {
		return jsonPathDelete;
	}

	public void setJsonPathDelete(List<JsonPathDeleteOperation> jsonPathDelete) {
		this.jsonPathDelete = jsonPathDelete;
	}

	@JsonPropertyDescription("SDP read operations: extract values from SDP body via SDP-to-JSON conversion and JsonPath")
	public List<SdpReadOperation> getSdpRead() {
		return sdpRead;
	}

	public void setSdpRead(List<SdpReadOperation> sdpRead) {
		this.sdpRead = sdpRead;
	}

	@JsonPropertyDescription("SDP create operations: add attributes or media to SDP body")
	public List<SdpCreateOperation> getSdpCreate() {
		return sdpCreate;
	}

	public void setSdpCreate(List<SdpCreateOperation> sdpCreate) {
		this.sdpCreate = sdpCreate;
	}

	@JsonPropertyDescription("SDP update operations: modify SDP values via SDP-to-JSON conversion and JsonPath")
	public List<SdpUpdateOperation> getSdpUpdate() {
		return sdpUpdate;
	}

	public void setSdpUpdate(List<SdpUpdateOperation> sdpUpdate) {
		this.sdpUpdate = sdpUpdate;
	}

	@JsonPropertyDescription("SDP delete operations: remove media lines or attributes from SDP body")
	public List<SdpDeleteOperation> getSdpDelete() {
		return sdpDelete;
	}

	public void setSdpDelete(List<SdpDeleteOperation> sdpDelete) {
		this.sdpDelete = sdpDelete;
	}
}
