package org.vorpal.blade.framework.v3.events;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// The declaration of one event type — what it is called, what it carries, and
/// where it flows.
///
/// This is the single fact the rest of the system derives from. Before the
/// catalog existed, an event was four hand-written things that nothing checked
/// against each other: the destination (provisioned by a WLST script), the
/// publisher (a JNDI constant), the consumer (an MDB with eight activation
/// properties and a hand-typed message selector), and the payload shape
/// (nowhere at all — `CloudEvent.data` is a raw `JsonNode` and the consumer
/// reached into it by string key and hoped). A typo in the selector was a silent
/// no-op that looked exactly like "no events yet".
///
/// From one declaration, [EventSourceGenerator] emits the payload class, its
/// JSON Schema, a consumer MDB whose selector **cannot** be mistyped, a producer
/// snippet, and a sample envelope; the console provisions the destination; and
/// the ingress validates against the schema. The selector in particular is never
/// typed by a human again — see [#selector()].
///
/// **What is deliberately not here: who listens.** A durable subscription name
/// and a client id used to live on this class, and the generator derived both
/// from the event type's own name. That silently assumed one consumer per event.
/// Two apps interested in the same fact — transfer acting on a refer, analytics
/// recording it — would have generated the same identity and so competed for one
/// stream instead of each receiving a copy. Subscriber identity belongs to the
/// subscriber; see [EventSubscription].
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventType implements Serializable {

	private static final long serialVersionUID = 1L;

	private String type;
	private String title;
	private String description;
	private String owner;
	private Integer version;
	private String destinationJndi;
	private DestinationKind destinationKind = DestinationKind.TOPIC;
	private boolean persist;
	private String javaPackage;
	private String javaClassName;
	private List<String> sensitiveFields;
	private List<EventField> fields;

	public EventType() {
	}

	/// Convenience for tests and samples.
	///
	/// @param type the CloudEvents `type`, e.g. `net.vorpal.attendant.meeting.scheduled`
	public EventType(String type) {
		this.type = type;
	}

	/// The JMS message selector that matches exactly this event type.
	///
	/// Built from [EventPublisher#PROP_TYPE] and [#getType], in one place, so the
	/// generated MDB, the console's drift check, and the inspector's filter
	/// cannot disagree with each other or with what the publisher actually
	/// stamps. This method existing is the whole reason a typo in a selector
	/// string is no longer a class of bug in BLADE.
	///
	/// @return e.g. `eventType = 'net.vorpal.attendant.meeting.scheduled'`, or
	///         null when no type has been declared yet
	@JsonIgnore
	public String selector() {
		return (type == null) ? null : EventPublisher.PROP_TYPE + " = '" + type + "'";
	}

	/// The Java class name for the generated payload — [#getJavaClassName] when
	/// set, otherwise derived from the last dotted segment of [#getType], so
	/// `net.vorpal.attendant.meeting.scheduled` yields `Scheduled`.
	///
	/// A derived name is a starting point, not a good one; the designer should
	/// offer it and let the developer replace it.
	@JsonIgnore
	public String effectiveJavaClassName() {
		if (javaClassName != null && !javaClassName.isEmpty()) {
			return javaClassName;
		}
		if (type == null || type.isEmpty()) {
			return null;
		}
		int dot = type.lastIndexOf('.');
		String segment = (dot < 0) ? type : type.substring(dot + 1);
		if (segment.isEmpty()) {
			return null;
		}
		return Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
	}

	@JsonPropertyDescription("The CloudEvents 'type' — the identity of this event, and the value the publisher stamps into the eventType JMS property. Reverse-DNS by convention, e.g. net.vorpal.attendant.meeting.scheduled.")
	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	@JsonPropertyDescription("Human-readable label shown in the console and emitted as the schema title.")
	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	@JsonPropertyDescription("What this event means and when it is emitted. Becomes the schema description and the generated class's Javadoc.")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("Who owns this event type — the team or app to ask when its shape needs to change.")
	public String getOwner() {
		return owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	@JsonPropertyDescription("Revision of this declaration. Bump it when the payload shape changes so consumers can tell which shape they were generated against.")
	public Integer getVersion() {
		return version;
	}

	public void setVersion(Integer version) {
		this.version = version;
	}

	@JsonPropertyDescription("JNDI name of the destination carrying this event type. Leave empty to use the catalog's default destination.")
	public String getDestinationJndi() {
		return destinationJndi;
	}

	public void setDestinationJndi(String destinationJndi) {
		this.destinationJndi = destinationJndi;
	}

	@JsonPropertyDescription("TOPIC fans out to every subscribing app; QUEUE delivers to exactly one consumer. Drives the generated MDB's destinationType.")
	public DestinationKind getDestinationKind() {
		return destinationKind;
	}

	public void setDestinationKind(DestinationKind destinationKind) {
		this.destinationKind = (destinationKind == null) ? DestinationKind.TOPIC : destinationKind;
	}

	@JsonPropertyDescription("Whether the analytics sink writes this event to the database. Read at runtime from the live catalog rather than baked into a generated selector, so a type marked persisted today is recorded today.")
	public boolean isPersist() {
		return persist;
	}

	public void setPersist(boolean persist) {
		this.persist = persist;
	}

	@JsonPropertyDescription("Java package for the generated payload class.")
	public String getJavaPackage() {
		return javaPackage;
	}

	public void setJavaPackage(String javaPackage) {
		this.javaPackage = javaPackage;
	}

	@JsonPropertyDescription("Java class name for the generated payload. Leave empty to derive it from the last segment of the event type.")
	public String getJavaClassName() {
		return javaClassName;
	}

	public void setJavaClassName(String javaClassName) {
		this.javaClassName = javaClassName;
	}

	@JsonPropertyDescription("Payload field names whose values the console masks when inspecting live events. Use for anything carrying caller identity or other customer content.")
	public List<String> getSensitiveFields() {
		return sensitiveFields;
	}

	public void setSensitiveFields(List<String> sensitiveFields) {
		this.sensitiveFields = sensitiveFields;
	}

	@JsonPropertyDescription("The payload shape — what the CloudEvent 'data' block carries.")
	public List<EventField> getFields() {
		return fields;
	}

	public void setFields(List<EventField> fields) {
		this.fields = fields;
	}

	/// The field list, never null.
	@JsonIgnore
	public List<EventField> fieldsOrEmpty() {
		return (fields == null) ? new ArrayList<>() : fields;
	}

	/// The sensitive-field list, never null.
	@JsonIgnore
	public List<String> sensitiveFieldsOrEmpty() {
		return (sensitiveFields == null) ? new ArrayList<>() : sensitiveFields;
	}
}
