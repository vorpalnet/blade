package org.vorpal.blade.framework.v3.events;

import java.io.Serializable;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// A CloudEvents 1.0 envelope — the on-the-wire message the BLADE v3 event bus
/// carries.
///
/// This is deliberately the *same* envelope the Gumball attendant already emits
/// (`action/emit.py:to_cloudevent`), so a producer speaking HTTP CloudEvents and
/// a BLADE app publishing natively put identical bytes on the topic. Unlike the
/// analytics queue — which sends Java-serialized JPA entities as `ObjectMessage`
/// and so binds every consumer to BLADE's classpath — the event bus carries this
/// as a JSON `TextMessage`, so any consumer in any language can read it.
///
/// The `type` and `subject` attributes are lifted into JMS string properties by
/// [EventPublisher] so consumers can filter with message selectors without
/// deserializing the body. `subject` carries the Vorpal session id, the same
/// correlation key BLADE already matches on for the live call.
/// **Unknown attributes are ignored on parse.** CloudEvents senders may stamp
/// extension attributes this class predates (exactly what `dataversion` is to a
/// consumer built before it existed). A consumer must keep reading such an
/// envelope, not throw — otherwise adding any attribute would break every
/// consumer built against an older framework jar.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudEvent implements Serializable {

	private static final long serialVersionUID = 1L;

	/// Shared, thread-safe mapper for envelope (de)serialization.
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private String specversion = "1.0";
	private String type;
	private String source;
	private String id;
	private String time;
	private String datacontenttype = "application/json";
	private String subject;
	private Integer dataversion;
	private JsonNode data;

	public CloudEvent() {
	}

	/// Build a fresh event, minting the `id` (a random UUID) and `time` (now, in
	/// RFC-3339 / ISO-8601 UTC) the way the attendant does.
	///
	/// @param type    the CloudEvents `type` (e.g. `net.vorpal.attendant.meeting.scheduled`)
	/// @param source  the emitting context (e.g. `/blade/events`)
	/// @param subject the correlation key — the Vorpal session id — or null
	/// @param data    the typed payload, or null
	/// @return a populated envelope ready to publish
	public static CloudEvent create(String type, String source, String subject, JsonNode data) {
		CloudEvent ce = new CloudEvent();
		ce.type = type;
		ce.source = source;
		ce.subject = subject;
		ce.data = data;
		ce.id = UUID.randomUUID().toString();
		ce.time = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
		return ce;
	}

	/// [#create(String, String, String, JsonNode)] plus the payload's declared
	/// revision — [EventType#getVersion] at the time the producer was written.
	///
	/// @param dataversion the declared payload revision, or null when the type is
	///                    undeclared (an operator-invented name)
	public static CloudEvent create(String type, String source, String subject, JsonNode data, Integer dataversion) {
		CloudEvent ce = create(type, source, subject, data);
		ce.dataversion = dataversion;
		return ce;
	}

	/// Parse a CloudEvents JSON envelope. Throws if the JSON is malformed.
	public static CloudEvent fromJson(String json) throws java.io.IOException {
		return MAPPER.readValue(json, CloudEvent.class);
	}

	/// Serialize this envelope to its CloudEvents JSON form.
	public String toJson() throws java.io.IOException {
		return MAPPER.writeValueAsString(this);
	}

	public String getSpecversion() {
		return specversion;
	}

	public void setSpecversion(String specversion) {
		this.specversion = specversion;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getTime() {
		return time;
	}

	public void setTime(String time) {
		this.time = time;
	}

	public String getDatacontenttype() {
		return datacontenttype;
	}

	public void setDatacontenttype(String datacontenttype) {
		this.datacontenttype = datacontenttype;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	/// The payload revision this event was published with — [EventType#getVersion]
	/// as of when its producer was generated. A CloudEvents *extension attribute*
	/// (spec 1.0 reserves the core names and passes extensions through), absent
	/// from the wire when the producer predates versioning or the type is
	/// undeclared. Consumers compare it against the revision they were generated
	/// from and warn on mismatch — see the generated MDB.
	public Integer getDataversion() {
		return dataversion;
	}

	public void setDataversion(Integer dataversion) {
		this.dataversion = dataversion;
	}

	public JsonNode getData() {
		return data;
	}

	public void setData(JsonNode data) {
		this.data = data;
	}
}
