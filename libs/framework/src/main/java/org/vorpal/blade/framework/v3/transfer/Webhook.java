package org.vorpal.blade.framework.v3.transfer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// A web service the transfer service POSTs each transfer event to, named in
/// the `webhooks` section and chosen by a REST request's
/// `notification.callbackName`, or by `defaultWebhook`.
@JsonPropertyOrder({ "url", "headers", "events", "timeoutMillis" })
public class Webhook implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Connect and read timeout when none is set: short, because a slow web
	/// service must not hold up the next event.
	public static final int DEFAULT_TIMEOUT_MILLIS = 2000;

	protected String url;
	protected Map<String, String> headers = new LinkedHashMap<>();
	protected List<String> events = new ArrayList<>();
	protected Integer timeoutMillis;

	public Webhook() {
	}

	public Webhook(String url) {
		this.url = url;
	}

	@JsonPropertyDescription("The URL each event is POSTed to, as JSON")
	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	@JsonPropertyDescription("Headers sent with every POST, such as Authorization")
	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = (headers != null) ? headers : new LinkedHashMap<>();
	}

	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	@JsonPropertyDescription("The events to send, such as transferCompleted; empty sends all of them")
	public List<String> getEvents() {
		return events;
	}

	public void setEvents(List<String> events) {
		this.events = (events != null) ? events : new ArrayList<>();
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("Connect and read timeout in milliseconds; default 2000")
	public Integer getTimeoutMillis() {
		return timeoutMillis;
	}

	public void setTimeoutMillis(Integer timeoutMillis) {
		this.timeoutMillis = timeoutMillis;
	}

	/// Whether this webhook wants `event`.
	public boolean wants(String event) {
		return events == null || events.isEmpty() || events.contains(event);
	}

	/// The timeout to use.
	public int timeout() {
		return (timeoutMillis != null && timeoutMillis > 0) ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
	}
}
