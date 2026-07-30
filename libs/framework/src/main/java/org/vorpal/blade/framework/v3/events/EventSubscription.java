package org.vorpal.blade.framework.v3.events;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// One consumer of the bus — who is listening, and to what.
///
/// **Why this is a first-class thing rather than a field on [EventType].** An
/// event is a fact; a subscription is somebody's interest in that fact. The two
/// were conflated at first: `EventType` carried `subscriptionName` and `durable`,
/// and the generator derived the durable subscription name from the *event
/// type's* class name and used that same string for both the JMS
/// `subscriptionName` and the `clientId`. That works for exactly one consumer per
/// event. Add a second — the transfer app acting on a refer while analytics
/// records it — and both apps generate the same pair, which is not two
/// subscriptions clashing but literally one subscription named twice: instead of
/// each app getting its own copy, they compete for one stream.
///
/// A subscription's identity therefore comes from the **subscriber**, never from
/// what it happens to consume. Two apps interested in one event are two entries
/// here, and uniqueness holds by construction.
///
/// **Two shapes of subscriber, and the difference is deliberate.**
///
/// - An **actor** — the transfer app, a calendar app — names the types it
///   handles. A selector is derived from them, the broker filters, and the app
///   never wakes for an event it would ignore. It fails *closed*: a type nobody
///   added to [#getTypes] is never enqueued for it.
/// - A **sink** — analytics — names nothing and takes everything, filtering in
///   code. It fails *open*: it sees a newly-declared type immediately rather than
///   waiting for someone to remember to regenerate. It pays for that by holding
///   messages it will drop.
///
/// Failing open is right for the thing whose job is to miss nothing, and failing
/// closed is right for the thing that takes action.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventSubscription implements Serializable {

	private static final long serialVersionUID = 1L;

	/// Above this many types the derived selector stops being worth its length
	/// and the generator emits a code filter instead.
	///
	/// The JMS specification imposes no selector length limit and **I have not
	/// found a documented WebLogic maximum** — this is not a limit being respected,
	/// it is a readability bound. The selector lands in generated source as a
	/// single-line string literal inside an `@ActivationConfigProperty`, and past
	/// a handful of reverse-DNS type names nobody reads it, which defeats the
	/// point of generating something a human can check.
	public static final int MAX_SELECTOR_TYPES = 8;

	/// Companion bound to [#MAX_SELECTOR_TYPES], for a few types with very long
	/// names.
	public static final int MAX_SELECTOR_LENGTH = 512;

	private String name;
	private String description;
	private String owner;
	private List<String> types;
	private boolean durable = true;
	private SelectorMode selectorMode = SelectorMode.DERIVED;
	private String javaPackage;
	private String javaClassName;

	public EventSubscription() {
	}

	public EventSubscription(String name) {
		this.name = name;
	}

	/// True when a name is safe to use as a JMS identity and as a Java class name
	/// stem.
	///
	/// The same string reaches a JMS `clientId`, a durable `subscriptionName`, a
	/// generated Java class name and a Maven artifactId. Constraining it up front
	/// is strictly stronger than escaping it at each of those four points, because
	/// only two of the four could be escaped at all.
	public static boolean isLegalName(String candidate) {
		if (candidate == null || candidate.isEmpty()) {
			return false;
		}
		for (int i = 0; i < candidate.length(); i++) {
			char c = candidate.charAt(i);
			if (!Character.isLetterOrDigit(c) && c != '.' && c != '_' && c != '-') {
				return false;
			}
		}
		return true;
	}

	/// The JMS durable subscription name — the subscription's own name, always.
	///
	/// [#getClientId] returns the same string. They may equal each other; what
	/// matters is that they differ *between* subscriptions, which they do because
	/// [#getName] is unique within the catalog.
	@JsonIgnore
	public String subscriptionName() {
		return name;
	}

	/// The JMS client id.
	///
	/// Deliberately not a settable field. A separately-configurable client id is a
	/// second string that can drift from the subscription name — the exact class of
	/// bug this catalog exists to abolish. If the two ever need to differ, that is
	/// a code change with a reason attached, not a form field.
	@JsonIgnore
	public String clientId() {
		return name;
	}

	/// The JMS message selector for this subscription, or null when it should
	/// consume everything on its destination and filter in code.
	///
	/// Built here, in one place, from [EventPublisher#PROP_TYPE] and the declared
	/// types — so the generated MDB, the console's drift check and the inspector
	/// cannot disagree with each other or with what the publisher actually stamps.
	/// A selector is never typed by a human.
	///
	/// Note the null semantics this relies on: a message carrying no `eventType`
	/// property makes the `IN` test UNKNOWN and is not delivered. That is wanted —
	/// but it also means a typeless event is invisible to every selecting
	/// subscription while still consuming the destination's quota, which is why
	/// the ingress rejects events without a type.
	///
	/// @return e.g. `eventType IN ('net.vorpal.blade.transfer.requested')`, or null
	@JsonIgnore
	public String selector() {
		if (selectorMode == SelectorMode.NONE) {
			return null;
		}
		List<String> declared = typesOrEmpty();
		if (declared.isEmpty() || declared.size() > MAX_SELECTOR_TYPES) {
			return null;
		}
		StringBuilder sb = new StringBuilder(128);
		sb.append(EventPublisher.PROP_TYPE).append(" IN (");
		for (int i = 0; i < declared.size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append("'").append(declared.get(i)).append("'");
		}
		sb.append(")");
		return (sb.length() > MAX_SELECTOR_LENGTH) ? null : sb.toString();
	}

	/// Why [#selector] came out the way it did, in one sentence, so the generated
	/// source can say which branch was taken instead of leaving the reader to
	/// infer it from an annotation that is present or absent.
	@JsonIgnore
	public String selectorRationale() {
		int count = typesOrEmpty().size();
		if (selectorMode == SelectorMode.NONE) {
			return "No selector by declaration: this subscription takes everything on its destination "
					+ "and decides in code, so a type declared tomorrow reaches it without a regeneration.";
		}
		if (count == 0) {
			return "No selector: this subscription names no types, so it takes everything on its destination.";
		}
		if (selector() == null) {
			return "No selector: " + count + " declared types exceed the "
					+ "readability bound for a generated selector, so the filtering happens in code below.";
		}
		return "Selector derived from the " + count + " type" + (count == 1 ? "" : "s")
				+ " this subscription declares. The broker filters, so this app never wakes for an event it "
				+ "would ignore.";
	}

	/// The Java class name for the generated consumer — [#getJavaClassName] when
	/// set, otherwise derived from [#getName]: `transfer-refer` yields
	/// `TransferRefer`, and the generator appends `Listener`.
	///
	/// **This name must be unique across applications too**, not only within the
	/// catalog. `AnalyticsEventListener` locates its own MBean by the JMX pattern
	/// `Type=MessageDrivenEJBRuntime,Name=<simpleClassName>,*` in order to
	/// suspend and resume itself under backpressure; two MDBs sharing a simple
	/// class name would both match, and one app's backpressure would stop the
	/// other app's delivery. Deriving from the subscription name gets this right
	/// for free, since subscription names are unique.
	@JsonIgnore
	public String effectiveJavaClassName() {
		if (javaClassName != null && !javaClassName.isEmpty()) {
			return javaClassName;
		}
		if (name == null || name.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder(name.length());
		boolean capitalize = true;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (!Character.isLetterOrDigit(c)) {
				capitalize = true;
				continue;
			}
			sb.append(capitalize ? Character.toUpperCase(c) : c);
			capitalize = false;
		}
		if (sb.length() == 0) {
			return null;
		}
		if (Character.isDigit(sb.charAt(0))) {
			sb.insert(0, 'S');
		}
		return sb.toString();
	}

	/// True when this subscription declares an interest in the given event type —
	/// either by naming it, or by naming nothing at all and so taking everything.
	@JsonIgnore
	public boolean covers(String type) {
		List<String> declared = typesOrEmpty();
		return declared.isEmpty() || declared.contains(type);
	}

	@JsonPropertyDescription("Unique name for this subscription. Becomes both the JMS clientId and the durable subscription name, and the stem of the generated consumer's class name — so it identifies the SUBSCRIBER, not the event. Name it for the app that listens, e.g. transfer-refer or analytics-db. Letters, digits, dot, underscore and hyphen only.")
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@JsonPropertyDescription("What this subscriber does with these events. Read by whoever finds an orphaned subscription holding a backlog and needs to know who to ask.")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("Who owns this subscriber — the team or app to contact when it stops consuming.")
	public String getOwner() {
		return owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	@JsonPropertyDescription("The event types this subscriber wants. Leave empty to take everything on the destination and filter in code — right for a sink that must not miss a newly-declared type, wrong for an actor, which should name exactly what it handles.")
	public List<String> getTypes() {
		return types;
	}

	public void setTypes(List<String> types) {
		this.types = types;
	}

	@JsonPropertyDescription("Hold events for this subscriber while it is down rather than let it miss them. On for a sink that must not lose a row; consider off for an actor, where a stale action is worse than a missed one — a transfer request that has sat in a subscription for an hour is probably not worth executing on restart.")
	public boolean isDurable() {
		return durable;
	}

	public void setDurable(boolean durable) {
		this.durable = durable;
	}

	@JsonPropertyDescription("DERIVED builds a JMS selector from the declared types so the broker filters. NONE takes everything and filters in code, which is what a sink wants: a selector freezes the type list at generation time, so a type declared later would be silently missed until somebody regenerated.")
	public SelectorMode getSelectorMode() {
		return selectorMode;
	}

	public void setSelectorMode(SelectorMode selectorMode) {
		this.selectorMode = (selectorMode == null) ? SelectorMode.DERIVED : selectorMode;
	}

	@JsonPropertyDescription("Java package for the generated consumer. Put it in the consuming application's own source tree — never in the framework jar, which ships inside every BLADE WAR and would activate this consumer in all of them at once.")
	public String getJavaPackage() {
		return javaPackage;
	}

	public void setJavaPackage(String javaPackage) {
		this.javaPackage = javaPackage;
	}

	@JsonPropertyDescription("Java class name for the generated consumer. Leave empty to derive it from the subscription name.")
	public String getJavaClassName() {
		return javaClassName;
	}

	public void setJavaClassName(String javaClassName) {
		this.javaClassName = javaClassName;
	}

	/// The declared types, never null.
	@JsonIgnore
	public List<String> typesOrEmpty() {
		return (types == null) ? new ArrayList<>() : types;
	}
}
