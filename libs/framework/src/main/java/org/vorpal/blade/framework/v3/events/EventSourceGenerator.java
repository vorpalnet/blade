package org.vorpal.blade.framework.v3.events;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Turns one [EventType] declaration into everything a developer needs to
/// produce or consume that event.
///
/// Six outputs from one input: the payload class, its JSON Schema, a consumer
/// MDB, a producer snippet, a sample envelope, and a downloadable Maven module
/// carrying all of them. They are generated from the same declaration, so they
/// cannot disagree — which is the entire point. The message selector in the
/// generated MDB comes from [EventType#selector()] rather than a human typing
/// the string a second time, and that alone removes the failure mode where a
/// consumer silently receives nothing because someone mistyped an event name
/// inside a quoted string that no compiler ever checks.
///
/// **Pure.** Every method here is a static function of its arguments: no file
/// IO, no JNDI, no MBeans, no `SettingsManager` state. Same contract as
/// [org.vorpal.blade.framework.v2.config.SettingsManager#generateSchemaNode],
/// and for the same reason — it means the whole generator can be exercised in a
/// plain JVM with no container and no WebLogic.
///
/// **The server never compiles this.** The output is source, delivered to the
/// developer as text or as a zip. There is deliberately no runtime compilation
/// and no publishing of a generated model jar into the `blade-shared` library:
/// the wire contract for this bus is the JSON Schema, not a shared Java class,
/// so a consumer written in another language stays a first-class citizen. Please
/// do not add compilation here later without revisiting that decision.
///
/// **Java 11 target.** This class runs on the OCCAS 8.1 bytecode target, so it
/// builds its output with [StringBuilder] — no text blocks (Java 15+). The
/// *generated* code is a developer's own source and carries no such limit,
/// though note that its `///` Markdown Javadoc only renders under JDK 23+; it
/// compiles fine anywhere, being ordinary line comments.
public final class EventSourceGenerator {

	/// The JSON Schema dialect the generated schema declares — the same draft
	/// [org.vorpal.blade.framework.v2.config.SettingsManager#generateSchemaNode]
	/// emits for config classes, so the console renders event payloads and app
	/// configuration through one vocabulary.
	public static final String SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";

	private static final String NL = "\n";

	private EventSourceGenerator() {
	}

	// ---------------------------------------------------------------- Java POJO

	/// Generate the payload class — the typed view of the CloudEvent `data`
	/// block.
	///
	/// Wire names are preserved exactly: a field named `when_text` becomes a
	/// Java `whenText` property carrying `@JsonProperty("when_text")`, so the
	/// bytes on the topic never change shape to suit Java naming.
	///
	/// @param declaration the event type to generate for
	/// @return Java source, or a comment explaining what is missing when the
	///         declaration is too incomplete to generate from
	public static String javaSource(EventType declaration) {
		String className = (declaration == null) ? null : declaration.effectiveJavaClassName();
		if (className == null) {
			return "// Declare an event type before generating: the class name comes from it." + NL;
		}

		StringBuilder sb = new StringBuilder(2048);
		appendPackage(sb, declaration);
		appendImports(sb, declaration);

		appendTypeJavadoc(sb, declaration, "", "the payload of");
		sb.append("public class ").append(className).append(" implements Serializable {").append(NL);
		sb.append(NL);
		sb.append("\tprivate static final long serialVersionUID = 1L;").append(NL);
		sb.append(NL);
		if (declaration.getVersion() != null) {
			sb.append("\t/// The declaration revision this class was generated from. The producer").append(NL);
			sb.append("\t/// stamps it as the envelope's `dataversion`, so a consumer can tell which").append(NL);
			sb.append("\t/// shape an event was published with.").append(NL);
			sb.append("\tpublic static final int VERSION = ").append(declaration.getVersion()).append(";").append(NL);
			sb.append(NL);
		}

		appendBody(sb, declaration.fieldsOrEmpty(), "\t");

		sb.append("}").append(NL);
		return sb.toString();
	}

	/// Emit the fields, nested types and accessors for one level of the field
	/// tree, indented by `indent`. Recurses through [EventFieldType#OBJECT] and
	/// arrays of objects.
	private static void appendBody(StringBuilder sb, List<EventField> fields, String indent) {
		for (EventField field : fields) {
			if (field.javaName() == null) {
				continue;
			}
			sb.append(indent).append("private ").append(javaType(field)).append(" ").append(field.javaName())
					.append(";").append(NL);
		}
		sb.append(NL);

		for (EventField field : fields) {
			appendNestedType(sb, field, indent);
		}

		for (EventField field : fields) {
			appendAccessors(sb, field, indent);
		}
	}

	/// Emit the nested `enum` or `static class` a field needs, if any.
	private static void appendNestedType(StringBuilder sb, EventField field, String indent) {
		String typeName = field.javaTypeName();
		if (typeName == null) {
			return;
		}

		if (field.getType() == EventFieldType.ENUM) {
			sb.append(indent).append("/// Permitted values of `").append(field.getName()).append("`.").append(NL);
			sb.append(indent).append("public enum ").append(typeName).append(" {").append(NL);
			List<String> values = field.enumValuesOrEmpty();
			for (int i = 0; i < values.size(); i++) {
				String value = values.get(i);
				String constant = enumConstant(value);
				sb.append(indent).append("\t@JsonProperty(\"").append(escape(value)).append("\")").append(NL);
				sb.append(indent).append("\t").append(constant).append(i < values.size() - 1 ? "," : ";").append(NL);
			}
			sb.append(indent).append("}").append(NL);
			sb.append(NL);
			return;
		}

		boolean objectField = field.getType() == EventFieldType.OBJECT;
		boolean objectArray = field.getType() == EventFieldType.ARRAY
				&& field.getItemType() == EventFieldType.OBJECT;
		if (!objectField && !objectArray) {
			return;
		}

		sb.append(indent).append("/// The shape of `").append(field.getName()).append("`.").append(NL);
		sb.append(indent).append("public static class ").append(typeName).append(" implements Serializable {")
				.append(NL);
		sb.append(NL);
		sb.append(indent).append("\tprivate static final long serialVersionUID = 1L;").append(NL);
		sb.append(NL);
		appendBody(sb, field.fieldsOrEmpty(), indent + "\t");
		sb.append(indent).append("}").append(NL);
		sb.append(NL);
	}

	/// Emit the getter/setter pair for a field. `@JsonPropertyDescription` and
	/// any `@JsonProperty` rename go on the **getter** — Jackson treats the
	/// accessor pair as one logical property, and the house rule puts the
	/// description there.
	private static void appendAccessors(StringBuilder sb, EventField field, String indent) {
		String javaName = field.javaName();
		if (javaName == null) {
			return;
		}
		String type = javaType(field);
		String capitalized = Character.toUpperCase(javaName.charAt(0)) + javaName.substring(1);

		if (field.getDescription() != null && !field.getDescription().isEmpty()) {
			sb.append(indent).append("/// ").append(field.getDescription()).append(NL);
		}
		if (field.needsJsonPropertyBinding()) {
			sb.append(indent).append("@JsonProperty(\"").append(escape(field.getName())).append("\")").append(NL);
		}
		if (field.getDescription() != null && !field.getDescription().isEmpty()) {
			sb.append(indent).append("@JsonPropertyDescription(\"").append(escape(field.getDescription()))
					.append("\")").append(NL);
		}
		sb.append(indent).append("public ").append(type).append(" get").append(capitalized).append("() {").append(NL);
		sb.append(indent).append("\treturn ").append(javaName).append(";").append(NL);
		sb.append(indent).append("}").append(NL);
		sb.append(NL);

		sb.append(indent).append("public void set").append(capitalized).append("(").append(type).append(" ")
				.append(javaName).append(") {").append(NL);
		sb.append(indent).append("\tthis.").append(javaName).append(" = ").append(javaName).append(";").append(NL);
		sb.append(indent).append("}").append(NL);
		sb.append(NL);
	}

	// ------------------------------------------------------------- Consumer MDB

	/// Generate the consumer — a message-driven bean already wired to the right
	/// destination, with the right subscription identity and the right selector.
	///
	/// **One MDB per subscription, not per event type.** The identity properties
	/// come from the subscription's own name, which is what lets two applications
	/// consume the same event and each receive a copy. When they came from the
	/// event type, two consumers of one event generated the same
	/// `subscriptionName` and the same `clientId` — not two subscriptions
	/// clashing, but literally one subscription named twice, so the two apps
	/// competed for a single stream. That is the defect this signature exists to
	/// make impossible.
	///
	/// Every activation property is derived, and the reasoning for each is emitted
	/// as a comment in the output so the developer who inherits the file knows why
	/// it is shaped that way:
	///
	/// - `destinationType` follows the destination the subscription's types live on.
	/// - `subscriptionName` and `clientId` are both the subscription's name. They
	///   may equal each other; what matters is that they differ *between*
	///   subscriptions.
	/// - A durable subscription (topics only) means an event is held rather than
	///   missed while the consumer is down.
	/// - `topicMessagesDistributionMode = One-Copy-Per-Application` makes the
	///   whole cluster act as one logical subscriber, so an event is handled once
	///   cluster-wide rather than once per engine node.
	/// - `messageSelector` comes from [EventSubscription#selector()] — never typed
	///   twice, and omitted entirely when the subscription filters in code.
	///
	/// @param subscription the subscriber to generate for
	/// @param catalog      the catalog its types are declared in
	/// @return Java source for the MDB
	public static String consumerSource(EventSubscription subscription, EventCatalog catalog) {
		String className = (subscription == null) ? null : subscription.effectiveJavaClassName();
		if (className == null) {
			return "// Name a subscription before generating its consumer: the class name, the" + NL
					+ "// JMS client id and the durable subscription name all come from it." + NL;
		}
		EventCatalog resolved = (catalog == null) ? new EventCatalog() : catalog;
		String listenerName = className + "Listener";
		boolean durableTopic = resolved.isDurableTopic(subscription);
		String selector = subscription.selector();
		List<EventType> handled = handledTypes(subscription, resolved);
		boolean versionGuard = false;
		for (EventType declared : handled) {
			if (declared.getVersion() != null) {
				versionGuard = true;
				break;
			}
		}

		StringBuilder sb = new StringBuilder(4096);
		if (subscription.getJavaPackage() != null && !subscription.getJavaPackage().isEmpty()) {
			sb.append("package ").append(subscription.getJavaPackage()).append(";").append(NL).append(NL);
		}

		sb.append("import javax.servlet.ServletContextEvent;").append(NL);
		sb.append("import javax.servlet.ServletContextListener;").append(NL);
		sb.append("import javax.servlet.annotation.WebListener;").append(NL);
		sb.append(NL);
		sb.append("import java.util.Arrays;").append(NL);
		sb.append("import java.util.Collections;").append(NL);
		sb.append("import java.util.List;").append(NL);
		if (versionGuard) {
			sb.append("import java.util.HashSet;").append(NL);
		}
		sb.append("import java.util.LinkedHashMap;").append(NL);
		sb.append("import java.util.Map;").append(NL);
		if (versionGuard) {
			sb.append("import java.util.Set;").append(NL);
		}
		sb.append("import java.util.logging.Level;").append(NL);
		sb.append("import java.util.logging.Logger;").append(NL);
		sb.append(NL);
		sb.append("import org.vorpal.blade.framework.v3.events.CloudEvent;").append(NL);
		for (String payloadImport : payloadImports(subscription, handled)) {
			sb.append("import ").append(payloadImport).append(";").append(NL);
		}
		sb.append(NL);
		sb.append("import org.vorpal.blade.framework.v3.events.EventSubscriber;").append(NL);
		sb.append("import org.vorpal.blade.framework.v3.events.SubscriptionRegistrar;").append(NL);
		sb.append(NL);
		sb.append("import com.fasterxml.jackson.databind.ObjectMapper;").append(NL);
		sb.append(NL);

		appendConsumerJavadoc(sb, subscription, resolved, durableTopic, selector, handled);

		sb.append("@WebListener").append(NL);
		sb.append("public class ").append(listenerName).append(NL);
		sb.append("\t\timplements EventSubscriber.Handler, ServletContextListener {").append(NL);
		sb.append(NL);

		sb.append("\t/// This SUBSCRIBER's name, never an event type's. Two applications")
				.append(NL);
		sb.append("\t/// consuming the same event MUST differ here, or they share one")
				.append(NL);
		sb.append("\t/// subscription and compete for messages instead of each getting a copy.")
				.append(NL);
		sb.append("\tpublic static final String SUBSCRIPTION = \"")
				.append(escape(subscription.getName())).append("\";").append(NL);
		sb.append(NL);

		sb.append("\t/// The types this consumer was generated for, used when the catalog")
				.append(NL);
		sb.append("\t/// has nothing to say about this subscription. Change the catalog to")
				.append(NL);
		sb.append("\t/// change what a DEPLOYED consumer receives -- these are the fallback,")
				.append(NL);
		sb.append("\t/// not the authority.").append(NL);
		appendWrapped(sb, "\t", "/// ", subscription.selectorRationale());
		// From what the SUBSCRIPTION asked for, not from `handled`. A type the
		// catalog has not declared yet has no payload class, so it cannot be
		// dispatched below — but the operator still asked to receive it, and
		// dropping it here would silently widen this consumer to everything
		// else instead.
		List<String> requested = subscription.typesOrEmpty();
		sb.append("\tpublic static final List<String> TYPES = Collections.unmodifiableList(Arrays.asList(")
				.append(NL);
		for (int i = 0; i < requested.size(); i++) {
			sb.append("\t\t\t\"").append(escape(requested.get(i))).append("\"")
					.append(i + 1 < requested.size() ? "," : "").append(NL);
		}
		if (requested.isEmpty()) {
			sb.append("\t\t\t// no types declared: this consumer receives nothing until the")
					.append(NL);
			sb.append("\t\t\t// catalog names some for it").append(NL);
		}
		sb.append("\t\t\t));").append(NL);
		sb.append(NL);
		sb.append(NL);
		sb.append("\tprivate static final Logger logger = Logger.getLogger(").append(listenerName)
				.append(".class.getName());").append(NL);
		sb.append("\tprivate static final ObjectMapper MAPPER = new ObjectMapper();").append(NL);
		sb.append(NL);

		sb.append("\t/// Keeps the live subscription matching the catalog for as long as")
				.append(NL);
		sb.append("\t/// this application is deployed.").append(NL);
		sb.append("\tprivate SubscriptionRegistrar registrar;").append(NL);
		sb.append(NL);
		sb.append("\t@Override").append(NL);
		sb.append("\tpublic void contextInitialized(ServletContextEvent event) {").append(NL);
		sb.append("\t\tregistrar = SubscriptionRegistrar.start(SUBSCRIPTION, TYPES, this);").append(NL);
		sb.append("\t}").append(NL);
		sb.append(NL);
		sb.append("\t@Override").append(NL);
		sb.append("\tpublic void contextDestroyed(ServletContextEvent event) {").append(NL);
		sb.append("\t\tif (registrar != null) {").append(NL);
		sb.append("\t\t\tregistrar.stop();").append(NL);
		sb.append("\t\t}").append(NL);
		sb.append("\t}").append(NL);
		sb.append(NL);

		appendDedupe(sb);
		if (versionGuard) {
			appendVersionGuard(sb);
		}
		appendOnMessage(sb, subscription, handled, selector);
		appendHandlers(sb, handled);

		sb.append("}").append(NL);
		return sb.toString();
	}

	/// The declared types this subscription names, in order, skipping any the
	/// catalog does not know — an unknown type is reported by
	/// [EventCatalog#validate], not turned into source that will not compile.
	private static List<EventType> handledTypes(EventSubscription subscription, EventCatalog catalog) {
		List<EventType> handled = new ArrayList<>();
		for (String type : subscription.typesOrEmpty()) {
			EventType declared = catalog.findType(type);
			if (declared != null && declared.effectiveJavaClassName() != null) {
				handled.add(declared);
			}
		}
		return handled;
	}

	/// Imports for payload classes that live in a package other than the
	/// consumer's own — a consumer in the transfer app binding to payload classes
	/// generated under the catalog's package.
	private static List<String> payloadImports(EventSubscription subscription, List<EventType> handled) {
		String consumerPackage = nullToEmpty(subscription.getJavaPackage());
		Set<String> imports = new TreeSet<>();
		for (EventType declared : handled) {
			String pkg = nullToEmpty(declared.getJavaPackage());
			if (!pkg.isEmpty() && !pkg.equals(consumerPackage)) {
				imports.add(pkg + "." + declared.effectiveJavaClassName());
			}
		}
		return new ArrayList<>(imports);
	}

	private static void appendConsumerJavadoc(StringBuilder sb, EventSubscription subscription, EventCatalog catalog,
			boolean durableTopic, String selector, List<EventType> handled) {

		sb.append("/// The `").append(subscription.getName()).append("` subscription.").append(NL);
		sb.append("///").append(NL);
		if (subscription.getDescription() != null && !subscription.getDescription().isEmpty()) {
			appendWrapped(sb, "", "/// ", subscription.getDescription());
			sb.append("///").append(NL);
		}
		sb.append("/// Generated by the BLADE Event Bus designer. Fill in the handler bodies").append(NL);
		sb.append("/// below; everything above them is already wired.").append(NL);
		sb.append("///").append(NL);
		sb.append("/// **This file belongs in the consuming application's own source tree.**").append(NL);
		sb.append("/// The framework jar ships inside every BLADE WAR and EJB annotation").append(NL);
		sb.append("/// scanning covers `WEB-INF/lib`, so a consumer placed there would activate").append(NL);
		sb.append("/// in every deployed application at once, all of them contending for one").append(NL);
		sb.append("/// client id.").append(NL);
		sb.append("///").append(NL);
		if (handled.isEmpty()) {
			sb.append("/// This subscription names no types, so it receives everything on").append(NL);
			sb.append("/// `").append(catalog.destinationForSubscription(subscription)).append("`.").append(NL);
		} else {
			sb.append("/// Handles:").append(NL);
			for (EventType declared : handled) {
				sb.append("///  - `").append(declared.getType()).append("`");
				if (declared.getTitle() != null && !declared.getTitle().isEmpty()) {
					sb.append(" — ").append(declared.getTitle());
				}
				sb.append(NL);
			}
		}
		sb.append("///").append(NL);
		if (selector != null) {
			sb.append("/// The selector is derived from those types rather than typed by a human,").append(NL);
			sb.append("/// so it cannot drift from what the publisher actually stamps — a mistyped").append(NL);
			sb.append("/// selector is a silent no-op that looks exactly like \"no events yet\".").append(NL);
		} else {
			sb.append("/// There is no selector: this consumer receives everything on its").append(NL);
			sb.append("/// destination and decides below. That is deliberate — see the note on the").append(NL);
			sb.append("/// activation config — and it means the subscription's store holds events").append(NL);
			sb.append("/// this consumer will drop, against the destination's quota.").append(NL);
		}
		if (durableTopic) {
			sb.append("///").append(NL);
			sb.append("/// A **durable** subscription means an event is held while this app is").append(NL);
			sb.append("/// down rather than missed. `One-Copy-Per-Application` makes the whole").append(NL);
			sb.append("/// cluster one logical subscriber, so the event is handled once").append(NL);
			sb.append("/// cluster-wide and not once per engine node.").append(NL);
			sb.append("///").append(NL);
			sb.append("/// It also means **redelivery is routine**, not an edge case: a rolling").append(NL);
			sb.append("/// restart, a failover or a rollback all replay. A redelivered fact is").append(NL);
			sb.append("/// harmless; a redelivered *action* is not. The event carries no delivery").append(NL);
			sb.append("/// contract, so idempotency is this consumer's job — see `firstSight`.").append(NL);
		}
	}

	/// Emit the redelivery guard. Generated by default rather than mentioned in a
	/// comment: a durable subscriber sees redeliveries as a matter of course, and
	/// a note nobody acts on is not a guard.
	private static void appendDedupe(StringBuilder sb) {
		sb.append("\t/// How many recently-seen CloudEvent ids to remember.").append(NL);
		sb.append("\tprivate static final int RECENT_IDS = 4096;").append(NL);
		sb.append(NL);
		sb.append("\tprivate static final Map<String, Boolean> RECENT = Collections.synchronizedMap(").append(NL);
		sb.append("\t\t\tnew LinkedHashMap<String, Boolean>(512, 0.75f, false) {").append(NL);
		sb.append("\t\t\t\tprivate static final long serialVersionUID = 1L;").append(NL);
		sb.append(NL);
		sb.append("\t\t\t\t@Override").append(NL);
		sb.append("\t\t\t\tprotected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {").append(NL);
		sb.append("\t\t\t\t\treturn size() > RECENT_IDS;").append(NL);
		sb.append("\t\t\t\t}").append(NL);
		sb.append("\t\t\t});").append(NL);
		sb.append(NL);
		sb.append("\t/// True the first time this node sees an event id.").append(NL);
		sb.append("\t///").append(NL);
		sb.append("\t/// **A cheap first filter, not an exactly-once guarantee.** It is per-JVM").append(NL);
		sb.append("\t/// and it forgets: it catches the ordinary redelivery — the same event").append(NL);
		sb.append("\t/// arriving again within the last few thousand messages on this node — and").append(NL);
		sb.append("\t/// nothing beyond that. If repeating this consumer's action is expensive or").append(NL);
		sb.append("\t/// wrong, dedupe against whatever durable state the action already writes").append(NL);
		sb.append("\t/// (a row, a flag, a session attribute) and treat this as the fast path.").append(NL);
		sb.append("\t///").append(NL);
		sb.append("\t/// An event with no id is treated as new rather than dropped: losing a").append(NL);
		sb.append("\t/// real event is worse than acting twice on a malformed one.").append(NL);
		sb.append("\tprivate static boolean firstSight(String id) {").append(NL);
		sb.append("\t\treturn id == null || RECENT.put(id, Boolean.TRUE) == null;").append(NL);
		sb.append("\t}").append(NL);
		sb.append(NL);
	}

	/// Emit the version-skew guard. Fail open on purpose: the event is still
	/// handled — a field addition is the common shape change — but the skew is
	/// visible instead of silent. Warned combinations are remembered so a skewed
	/// producer logs once per shape, not once per event; at bus volume a line per
	/// event would be the loudest thing in the log and say nothing new.
	private static void appendVersionGuard(StringBuilder sb) {
		sb.append("\t/// Type+version pairs already warned about, so a skewed producer logs").append(NL);
		sb.append("\t/// once per shape rather than once per event.").append(NL);
		sb.append("\tprivate static final Set<String> WARNED_VERSIONS = Collections.synchronizedSet(new HashSet<String>());")
				.append(NL);
		sb.append(NL);
		sb.append("\t/// Warn when an event was published from a different declaration revision").append(NL);
		sb.append("\t/// than this consumer was generated against. The event is still handled —").append(NL);
		sb.append("\t/// field additions are the common change — but the skew becomes visible.").append(NL);
		sb.append("\t/// An envelope with no dataversion predates versioning and is not a skew.").append(NL);
		sb.append("\tprivate static void checkVersion(CloudEvent event, int generatedAgainst) {").append(NL);
		sb.append("\t\tInteger published = event.getDataversion();").append(NL);
		sb.append("\t\tif (published == null || published.intValue() == generatedAgainst) {").append(NL);
		sb.append("\t\t\treturn;").append(NL);
		sb.append("\t\t}").append(NL);
		sb.append("\t\tif (WARNED_VERSIONS.add(event.getType() + \":\" + published)) {").append(NL);
		sb.append("\t\t\tlogger.warning(event.getType() + \" arrived at version \" + published").append(NL);
		sb.append("\t\t\t\t\t+ \" but this consumer was generated against version \" + generatedAgainst").append(NL);
		sb.append("\t\t\t\t\t+ \" — regenerate this consumer from the catalog\");").append(NL);
		sb.append("\t\t}").append(NL);
		sb.append("\t}").append(NL);
		sb.append(NL);
	}

	private static void appendOnMessage(StringBuilder sb, EventSubscription subscription, List<EventType> handled,
			String selector) {

		sb.append("\t@Override").append(NL);
		sb.append("\tpublic void handle(List<CloudEvent> batch) throws Exception {").append(NL);
		sb.append("\t\tfor (CloudEvent event : batch) {").append(NL);
		sb.append("\t\t\thandle(event);").append(NL);
		sb.append("\t\t}").append(NL);
		sb.append("\t}").append(NL);
		sb.append(NL);
		sb.append("\tprivate void handle(CloudEvent event) {").append(NL);
		sb.append("\t\ttry {").append(NL);
		sb.append("\t\t\tif (!firstSight(event.getId())) {").append(NL);
		sb.append("\t\t\t\tlogger.fine(\"already handled \" + event.getId());").append(NL);
		sb.append("\t\t\t\treturn;").append(NL);
		sb.append("\t\t\t}").append(NL);
		sb.append(NL);

		if (handled.isEmpty()) {
			sb.append("\t\t\tonEvent(event);").append(NL);
		} else {
			sb.append("\t\t\tswitch (String.valueOf(event.getType())) {").append(NL);
			for (EventType declared : handled) {
				String payload = declared.effectiveJavaClassName();
				sb.append("\t\t\tcase \"").append(escape(declared.getType())).append("\":").append(NL);
				if (declared.getVersion() != null) {
					sb.append("\t\t\t\tcheckVersion(event, ").append(declared.getVersion()).append(");").append(NL);
				}
				sb.append("\t\t\t\t").append(handlerName(declared))
						.append("(event, MAPPER.treeToValue(event.getData(), ").append(payload).append(".class));")
						.append(NL);
				sb.append("\t\t\t\tbreak;").append(NL);
			}
			sb.append("\t\t\tdefault:").append(NL);
			if (selector != null) {
				sb.append("\t\t\t\t// The broker filters to exactly the types above, so reaching here").append(NL);
				sb.append("\t\t\t\t// means the selector and this switch disagree: regenerate.").append(NL);
				sb.append("\t\t\t\tlogger.warning(\"unexpected type \" + event.getType()").append(NL);
				sb.append("\t\t\t\t\t\t+ \" — selector and handler disagree; regenerate this consumer\");")
						.append(NL);
			} else {
				sb.append("\t\t\t\t// No selector, so unwanted types arrive here as a matter of course.").append(NL);
				sb.append("\t\t\t\t// FINE rather than a warning: at bus volume one line per ignored").append(NL);
				sb.append("\t\t\t\t// event would be the loudest thing in the log and mean nothing.").append(NL);
				sb.append("\t\t\t\tlogger.fine(\"not handled here: \" + event.getType());").append(NL);
			}
			sb.append("\t\t\t\tbreak;").append(NL);
			sb.append("\t\t\t}").append(NL);
		}

		sb.append("\t\t} catch (Exception e) {").append(NL);
		sb.append("\t\t\t// Swallowed rather than rethrown, and the distinction matters:").append(NL);
		sb.append("\t\t\t// throwing would roll this event back and the broker would redeliver").append(NL);
		sb.append("\t\t\t// it until the redelivery limit moved it to the error destination.").append(NL);
		sb.append("\t\t\t// That is right for a failure that might succeed next time; a payload").append(NL);
		sb.append("\t\t\t// this consumer cannot parse is not one of those. Rethrow here if the").append(NL);
		sb.append("\t\t\t// action is worth retrying.").append(NL);
		sb.append("\t\t\tlogger.log(Level.SEVERE, \"failed to handle an event on ")
				.append(escape(nullToEmpty(subscription.getName()))).append("\", e);").append(NL);
		sb.append("\t\t}").append(NL);
		sb.append("\t}").append(NL);
		sb.append(NL);
	}

	/// Emit one stub per handled type — or a single one, when the subscription
	/// takes everything and there are no types to switch on.
	private static void appendHandlers(StringBuilder sb, List<EventType> handled) {
		if (handled.isEmpty()) {
			sb.append("\t/// Every event on this destination arrives here.").append(NL);
			sb.append("\t///").append(NL);
			sb.append("\t/// Decide what to do with it from `event.getType()` — and prefer reading").append(NL);
			sb.append("\t/// the live catalog over hard-coding a list, since taking everything is").append(NL);
			sb.append("\t/// the whole reason this subscription has no selector.").append(NL);
			sb.append("\tprivate void onEvent(CloudEvent event) {").append(NL);
			sb.append("\t\t// TODO: handle the event.").append(NL);
			sb.append("\t\tlogger.info(\"received \" + event.getType() + \" [id=\" + event.getId()").append(NL);
			sb.append("\t\t\t\t+ \", subject=\" + event.getSubject() + \"]\");").append(NL);
			sb.append("\t}").append(NL);
			return;
		}

		for (int i = 0; i < handled.size(); i++) {
			EventType declared = handled.get(i);
			String payload = declared.effectiveJavaClassName();
			if (declared.getDescription() != null && !declared.getDescription().isEmpty()) {
				appendWrapped(sb, "\t", "/// ", declared.getDescription());
				sb.append("\t///").append(NL);
			}
			sb.append("\t/// `").append(declared.getType()).append("`").append(NL);
			sb.append("\tprivate void ").append(handlerName(declared)).append("(CloudEvent event, ").append(payload)
					.append(" payload) {").append(NL);
			sb.append("\t\t// TODO: act on the event.").append(NL);
			sb.append("\t\tlogger.info(\"received ").append(escape(declared.getType()))
					.append(" [id=\" + event.getId() + \", subject=\" + event.getSubject() + \"]\");").append(NL);
			sb.append("\t}").append(NL);
			if (i < handled.size() - 1) {
				sb.append(NL);
			}
		}
	}

	/// `org.vorpal.blade.transfer.requested` becomes `onTransferRequested`.
	private static String handlerName(EventType declaration) {
		return "on" + declaration.effectiveJavaClassName();
	}

	/// Emit prose as wrapped comment lines.
	///
	/// A catalog description is a paragraph — the transfer-requested one runs to
	/// four lines of English — and pasting it verbatim produces a 300-column
	/// comment that a developer's editor either truncates or reflows into a diff.
	/// Generated code is code someone has to read.
	///
	/// @param prefix the line lead, e.g. `"/// "` or `"// "`, already indented
	private static void appendWrapped(StringBuilder sb, String indent, String prefix, String text) {
		if (text == null || text.isEmpty()) {
			return;
		}
		int width = 100 - indent.length() - prefix.length();
		StringBuilder line = new StringBuilder(width + 16);
		for (String word : text.split("\\s+")) {
			if (line.length() > 0 && line.length() + 1 + word.length() > width) {
				sb.append(indent).append(prefix).append(line).append(NL);
				line.setLength(0);
			}
			if (line.length() > 0) {
				line.append(' ');
			}
			line.append(word);
		}
		if (line.length() > 0) {
			sb.append(indent).append(prefix).append(line).append(NL);
		}
	}

	// ---------------------------------------------------------- Producer snippet

	/// Generate the publish-side snippet — how an app emits this event.
	///
	/// @param declaration the event type to publish
	/// @return a Java fragment, ready to paste into a callflow or servlet
	public static String producerSnippet(EventType declaration) {
		String className = (declaration == null) ? null : declaration.effectiveJavaClassName();
		if (className == null) {
			return "// Declare an event type before generating its producer." + NL;
		}

		StringBuilder sb = new StringBuilder(512);
		sb.append("// Publish a ").append(declaration.getType()).append(" event.").append(NL);
		sb.append("// EventBus.publish is a no-op when the bus is not up on this node, so a").append(NL);
		sb.append("// producer is never coupled to bus liveness — check isReady() only if you").append(NL);
		sb.append("// need to know.").append(NL);
		sb.append(className).append(" payload = new ").append(className).append("();").append(NL);
		for (EventField field : declaration.fieldsOrEmpty()) {
			String javaName = field.javaName();
			if (javaName == null) {
				continue;
			}
			String capitalized = Character.toUpperCase(javaName.charAt(0)) + javaName.substring(1);
			sb.append("payload.set").append(capitalized).append("(").append(placeholderLiteral(field)).append(");")
					.append(NL);
		}
		sb.append(NL);
		sb.append("CloudEvent event = CloudEvent.create(").append(NL);
		sb.append("\t\t\"").append(escape(declaration.getType())).append("\",").append(NL);
		sb.append("\t\t\"/blade/events\",").append(NL);
		sb.append("\t\tsipSession.getId(),   // subject: the correlation key for this call").append(NL);
		if (declaration.getVersion() != null) {
			sb.append("\t\tnew ObjectMapper().valueToTree(payload),").append(NL);
			sb.append("\t\t").append(className).append(".VERSION);   // the declaration revision this shape has")
					.append(NL);
		} else {
			sb.append("\t\tnew ObjectMapper().valueToTree(payload));").append(NL);
		}
		sb.append("EventBus.publish(event);").append(NL);
		return sb.toString();
	}

	// ------------------------------------------------------------- JSON Schema

	/// Generate the JSON Schema for this event's payload — the wire contract the
	/// ingress validates against and any consumer, in any language, can bind to.
	///
	/// @param declaration the event type
	/// @param mapper      the mapper to build nodes with
	/// @return a Draft 2020-12 schema describing the CloudEvent `data` block
	public static JsonNode schema(EventType declaration, ObjectMapper mapper) {
		ObjectNode root = mapper.createObjectNode();
		root.put("$schema", SCHEMA_DIALECT);
		if (declaration == null) {
			root.put("type", "object");
			return root;
		}
		String title = (declaration.getTitle() != null && !declaration.getTitle().isEmpty())
				? declaration.getTitle()
				: declaration.getType();
		if (title != null) {
			root.put("title", title);
		}
		if (declaration.getDescription() != null && !declaration.getDescription().isEmpty()) {
			root.put("description", declaration.getDescription());
		}
		appendObjectSchema(root, declaration.fieldsOrEmpty(), mapper);
		return root;
	}

	/// Fill `target` with the `type`/`properties`/`required` of an object schema.
	private static void appendObjectSchema(ObjectNode target, List<EventField> fields, ObjectMapper mapper) {
		target.put("type", "object");
		ObjectNode properties = target.putObject("properties");
		ArrayNode required = mapper.createArrayNode();
		for (EventField field : fields) {
			if (field.getName() == null || field.getName().isEmpty()) {
				continue;
			}
			properties.set(field.getName(), fieldSchema(field, mapper));
			if (field.isRequired()) {
				required.add(field.getName());
			}
		}
		if (required.size() > 0) {
			target.set("required", required);
		}
	}

	private static ObjectNode fieldSchema(EventField field, ObjectMapper mapper) {
		ObjectNode node = mapper.createObjectNode();
		EventFieldType type = field.getType();

		if (type == EventFieldType.OBJECT) {
			if (field.getDescription() != null && !field.getDescription().isEmpty()) {
				node.put("description", field.getDescription());
			}
			appendObjectSchema(node, field.fieldsOrEmpty(), mapper);
			return node;
		}

		if (type == EventFieldType.ARRAY) {
			node.put("type", "array");
			if (field.getDescription() != null && !field.getDescription().isEmpty()) {
				node.put("description", field.getDescription());
			}
			node.set("items", itemSchema(field, mapper));
			return node;
		}

		node.put("type", type.schemaType());
		String format = (field.getFormat() != null && !field.getFormat().isEmpty())
				? field.getFormat()
				: type.schemaFormat();
		if (format != null) {
			node.put("format", format);
		}
		if (field.getDescription() != null && !field.getDescription().isEmpty()) {
			node.put("description", field.getDescription());
		}
		if (type == EventFieldType.ENUM) {
			ArrayNode values = node.putArray("enum");
			for (String value : field.enumValuesOrEmpty()) {
				values.add(value);
			}
		}
		if (field.getPattern() != null && !field.getPattern().isEmpty()) {
			node.put("pattern", field.getPattern());
		}
		if (field.getDefaultValue() != null && !field.getDefaultValue().isEmpty()) {
			putTypedDefault(node, "default", type, field.getDefaultValue());
		}
		return node;
	}

	private static ObjectNode itemSchema(EventField field, ObjectMapper mapper) {
		EventFieldType itemType = (field.getItemType() == null) ? EventFieldType.STRING : field.getItemType();
		ObjectNode items = mapper.createObjectNode();
		if (itemType == EventFieldType.OBJECT) {
			appendObjectSchema(items, field.fieldsOrEmpty(), mapper);
			return items;
		}
		items.put("type", itemType.schemaType());
		String format = itemType.schemaFormat();
		if (format != null) {
			items.put("format", format);
		}
		if (itemType == EventFieldType.ENUM) {
			ArrayNode values = items.putArray("enum");
			for (String value : field.enumValuesOrEmpty()) {
				values.add(value);
			}
		}
		return items;
	}

	/// Write a declared default into the schema as the JSON type the field
	/// actually is — a numeric field's default must be a number, not the string
	/// the operator typed into the form. An unparseable value falls back to a
	/// string rather than failing the whole preview.
	private static void putTypedDefault(ObjectNode node, String key, EventFieldType type, String raw) {
		try {
			switch (type) {
			case INTEGER:
				node.put(key, Integer.parseInt(raw.trim()));
				return;
			case LONG:
				node.put(key, Long.parseLong(raw.trim()));
				return;
			case NUMBER:
				node.put(key, Double.parseDouble(raw.trim()));
				return;
			case BOOLEAN:
				node.put(key, Boolean.parseBoolean(raw.trim()));
				return;
			default:
				node.put(key, raw);
				return;
			}
		} catch (NumberFormatException e) {
			node.put(key, raw);
		}
	}

	// ---------------------------------------------------------- Sample envelope

	/// Generate a complete, plausible CloudEvents envelope for this type — what
	/// a real published event looks like, for pasting into a `curl` or a test.
	///
	/// @param declaration the event type
	/// @param mapper      the mapper to build and serialize with
	/// @return pretty-printed CloudEvents 1.0 JSON
	/// @throws IOException if the envelope cannot be serialized
	public static String sampleEnvelope(EventType declaration, ObjectMapper mapper) throws IOException {
		ObjectNode data = mapper.createObjectNode();
		if (declaration != null) {
			appendSampleData(data, declaration.fieldsOrEmpty(), mapper);
		}
		CloudEvent event = CloudEvent.create(
				(declaration == null) ? null : declaration.getType(),
				"/blade/events",
				"vorpal-session-1",
				data,
				(declaration == null) ? null : declaration.getVersion());
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(event);
	}

	private static void appendSampleData(ObjectNode target, List<EventField> fields, ObjectMapper mapper) {
		for (EventField field : fields) {
			String name = field.getName();
			if (name == null || name.isEmpty()) {
				continue;
			}
			switch (field.getType()) {
			case OBJECT:
				appendSampleData(target.putObject(name), field.fieldsOrEmpty(), mapper);
				break;
			case ARRAY:
				ArrayNode array = target.putArray(name);
				EventFieldType itemType = (field.getItemType() == null) ? EventFieldType.STRING : field.getItemType();
				if (itemType == EventFieldType.OBJECT) {
					appendSampleData(array.addObject(), field.fieldsOrEmpty(), mapper);
				} else {
					ObjectNode holder = mapper.createObjectNode();
					putSampleScalar(holder, "item", itemType, field);
					array.add(holder.get("item"));
				}
				break;
			default:
				putSampleScalar(target, name, field.getType(), field);
				break;
			}
		}
	}

	private static void putSampleScalar(ObjectNode target, String key, EventFieldType type, EventField field) {
		String declared = field.getDefaultValue();
		if (declared != null && !declared.isEmpty()) {
			putTypedDefault(target, key, type, declared);
			return;
		}
		switch (type) {
		case INTEGER:
			target.put(key, 1);
			return;
		case LONG:
			target.put(key, 1L);
			return;
		case NUMBER:
			target.put(key, 1.0);
			return;
		case BOOLEAN:
			target.put(key, true);
			return;
		case INSTANT:
			target.put(key, "2026-01-01T00:00:00Z");
			return;
		case ENUM:
			List<String> values = field.enumValuesOrEmpty();
			target.put(key, values.isEmpty() ? "value" : values.get(0));
			return;
		default:
			target.put(key, "text");
			return;
		}
	}

	// ------------------------------------------------------------- Module zip

	/// Package one event type's outputs into a self-contained Maven module the
	/// developer can unzip into their own project and build.
	///
	/// **No consumer in here any more.** A consumer belongs to a *subscriber*, not
	/// to an event — see [#subscriptionModuleZip]. Shipping one alongside the
	/// payload was what made it look as though an event had exactly one consumer,
	/// and it is why two apps consuming the same event used to collide.
	///
	/// @param declaration the event type
	/// @param mapper      the mapper used for the schema and sample
	/// @return the zip bytes
	/// @throws IOException if the zip cannot be written
	public static byte[] moduleZip(EventType declaration, ObjectMapper mapper) throws IOException {
		String className = (declaration == null) ? null : declaration.effectiveJavaClassName();
		if (className == null) {
			throw new IOException("Declare an event type before downloading a module.");
		}
		String artifact = artifactId(declaration);
		String packagePath = nullToEmpty(declaration.getJavaPackage()).replace('.', '/');
		String base = artifact + "/";
		String sourceDir = base + "src/main/java/" + (packagePath.isEmpty() ? "" : packagePath + "/");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
		ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8);
		try {
			write(zip, base + "pom.xml", modulePom(nullToEmpty(declaration.getJavaPackage()), artifact));
			write(zip, sourceDir + className + ".java", javaSource(declaration));
			write(zip, base + "src/main/resources/schema/" + artifact + ".schema.json",
					mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema(declaration, mapper)));
			write(zip, base + "sample.json", sampleEnvelope(declaration, mapper));
			write(zip, base + "README.md", moduleReadme(declaration, artifact));
		} finally {
			zip.close();
		}
		return bytes.toByteArray();
	}

	/// Package a subscriber: the consumer MDB, plus the payload class and schema
	/// of every type it handles.
	///
	/// This is the module that goes into the *consuming* application. A
	/// subscription that names no types gets the listener alone — it takes
	/// everything and reads the catalog at runtime, so there is no fixed set of
	/// payload classes to ship with it.
	///
	/// @param subscription the subscriber
	/// @param catalog      the catalog its types are declared in
	/// @param mapper       the mapper used for the schemas
	/// @return the zip bytes
	/// @throws IOException if the zip cannot be written
	public static byte[] subscriptionModuleZip(EventSubscription subscription, EventCatalog catalog,
			ObjectMapper mapper) throws IOException {

		String className = (subscription == null) ? null : subscription.effectiveJavaClassName();
		if (className == null) {
			throw new IOException("Name a subscription before downloading its consumer module.");
		}
		EventCatalog resolved = (catalog == null) ? new EventCatalog() : catalog;
		String artifact = slug(subscription.getName()) + "-consumer";
		String base = artifact + "/";
		String consumerPackage = nullToEmpty(subscription.getJavaPackage());
		String consumerDir = base + "src/main/java/"
				+ (consumerPackage.isEmpty() ? "" : consumerPackage.replace('.', '/') + "/");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(16384);
		ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8);
		try {
			write(zip, base + "pom.xml", modulePom(consumerPackage, artifact));
			write(zip, consumerDir + className + "Listener.java", consumerSource(subscription, resolved));
			for (EventType declared : handledTypes(subscription, resolved)) {
				String payloadPackage = nullToEmpty(declared.getJavaPackage());
				String payloadDir = base + "src/main/java/"
						+ (payloadPackage.isEmpty() ? "" : payloadPackage.replace('.', '/') + "/");
				write(zip, payloadDir + declared.effectiveJavaClassName() + ".java", javaSource(declared));
				write(zip, base + "src/main/resources/schema/" + artifactId(declared) + ".schema.json",
						mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema(declared, mapper)));
			}
			write(zip, base + "README.md", subscriptionReadme(subscription, resolved, artifact));
		} finally {
			zip.close();
		}
		return bytes.toByteArray();
	}

	private static void write(ZipOutputStream zip, String path, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(path));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	private static String modulePom(String groupId, String artifact) {
		StringBuilder sb = new StringBuilder(1024);
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(NL);
		sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"").append(NL);
		sb.append("\txmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"").append(NL);
		sb.append("\txsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd\">")
				.append(NL);
		sb.append("\t<modelVersion>4.0.0</modelVersion>").append(NL);
		sb.append(NL);
		sb.append("\t<groupId>").append(escapeXml(nullToEmpty(groupId))).append("</groupId>").append(NL);
		sb.append("\t<artifactId>").append(escapeXml(artifact)).append("</artifactId>").append(NL);
		sb.append("\t<version>1.0.0-SNAPSHOT</version>").append(NL);
		sb.append(NL);
		sb.append("\t<!-- Both dependencies are provided: the framework jar ships in every").append(NL);
		sb.append("\t     BLADE WAR's WEB-INF/lib, and the Java EE API comes from the").append(NL);
		sb.append("\t     container. Do not bundle either one. -->").append(NL);
		sb.append("\t<dependencies>").append(NL);
		sb.append("\t\t<dependency>").append(NL);
		sb.append("\t\t\t<groupId>org.vorpal.blade</groupId>").append(NL);
		sb.append("\t\t\t<artifactId>vorpal-blade-library-framework</artifactId>").append(NL);
		sb.append("\t\t\t<version>3.0.0</version>").append(NL);
		sb.append("\t\t\t<scope>provided</scope>").append(NL);
		sb.append("\t\t</dependency>").append(NL);
		sb.append("\t\t<dependency>").append(NL);
		sb.append("\t\t\t<groupId>javax</groupId>").append(NL);
		sb.append("\t\t\t<artifactId>javaee-api</artifactId>").append(NL);
		sb.append("\t\t\t<version>8.0</version>").append(NL);
		sb.append("\t\t\t<scope>provided</scope>").append(NL);
		sb.append("\t\t</dependency>").append(NL);
		sb.append("\t</dependencies>").append(NL);
		sb.append(NL);
		sb.append("\t<build>").append(NL);
		sb.append("\t\t<plugins>").append(NL);
		sb.append("\t\t\t<plugin>").append(NL);
		sb.append("\t\t\t\t<groupId>org.apache.maven.plugins</groupId>").append(NL);
		sb.append("\t\t\t\t<artifactId>maven-compiler-plugin</artifactId>").append(NL);
		sb.append("\t\t\t\t<configuration>").append(NL);
		sb.append("\t\t\t\t\t<release>11</release>").append(NL);
		sb.append("\t\t\t\t</configuration>").append(NL);
		sb.append("\t\t\t</plugin>").append(NL);
		sb.append("\t\t</plugins>").append(NL);
		sb.append("\t</build>").append(NL);
		sb.append(NL);
		sb.append("</project>").append(NL);
		return sb.toString();
	}

	private static String moduleReadme(EventType declaration, String artifact) {
		StringBuilder sb = new StringBuilder(1024);
		sb.append("# ").append(artifact).append(NL);
		sb.append(NL);
		sb.append("Generated by the BLADE Event Bus designer for event type").append(NL);
		sb.append("`").append(declaration.getType()).append("`.").append(NL);
		sb.append(NL);
		if (declaration.getDescription() != null && !declaration.getDescription().isEmpty()) {
			sb.append(declaration.getDescription()).append(NL).append(NL);
		}
		sb.append("## What's here").append(NL);
		sb.append(NL);
		String className = declaration.effectiveJavaClassName();
		sb.append("| File | Role |").append(NL);
		sb.append("|---|---|").append(NL);
		sb.append("| `").append(className).append(".java` | the payload — the typed view of the CloudEvent `data` block |")
				.append(NL);
		sb.append("| `schema/").append(artifact)
				.append(".schema.json` | the wire contract, for consumers in any language |").append(NL);
		sb.append("| `sample.json` | a complete example envelope |").append(NL);
		sb.append(NL);
		sb.append("## No consumer in here").append(NL);
		sb.append(NL);
		sb.append("A consumer belongs to a *subscriber*, not to an event — several applications").append(NL);
		sb.append("may want this same event, and each needs its own subscription identity to").append(NL);
		sb.append("receive its own copy. Declare a subscription in the catalog and download its").append(NL);
		sb.append("consumer module; this one is the payload contract that any of them binds to.").append(NL);
		sb.append(NL);
		sb.append("## Using it").append(NL);
		sb.append(NL);
		sb.append("Drop this module into your own project and build it.").append(NL);
		return sb.toString();
	}

	private static String subscriptionReadme(EventSubscription subscription, EventCatalog catalog, String artifact) {
		String className = subscription.effectiveJavaClassName();
		List<EventType> handled = handledTypes(subscription, catalog);

		StringBuilder sb = new StringBuilder(2048);
		sb.append("# ").append(artifact).append(NL);
		sb.append(NL);
		sb.append("The `").append(subscription.getName()).append("` subscription on the BLADE event bus.").append(NL);
		sb.append(NL);
		if (subscription.getDescription() != null && !subscription.getDescription().isEmpty()) {
			sb.append(subscription.getDescription()).append(NL).append(NL);
		}
		sb.append("## What's here").append(NL);
		sb.append(NL);
		sb.append("| File | Role |").append(NL);
		sb.append("|---|---|").append(NL);
		sb.append("| `").append(className)
				.append("Listener.java` | the consumer MDB, already bound to the destination, the subscription identity and the selector |")
				.append(NL);
		for (EventType declared : handled) {
			sb.append("| `").append(declared.effectiveJavaClassName()).append(".java` | the payload of `")
					.append(declared.getType()).append("` |").append(NL);
		}
		sb.append(NL);
		sb.append("## Where it goes").append(NL);
		sb.append(NL);
		sb.append("**Into the consuming application's own source tree.** Every BLADE WAR bundles").append(NL);
		sb.append("the framework jar into its `WEB-INF/lib`, and EJB annotation scanning covers").append(NL);
		sb.append("`WEB-INF/lib` — so a consumer placed in the framework would activate in every").append(NL);
		sb.append("deployed BLADE application at once, all of them contending for one client id.").append(NL);
		sb.append(NL);
		sb.append("The listener binds to `").append(catalog.destinationForSubscription(subscription)).append("`.")
				.append(NL);
		sb.append("That destination must exist before the app starts, or the container will").append(NL);
		sb.append("refuse to deploy the MDB — provision it from the Events console.").append(NL);
		sb.append(NL);
		sb.append("## Subscription identity").append(NL);
		sb.append(NL);
		sb.append("| Property | Value |").append(NL);
		sb.append("|---|---|").append(NL);
		sb.append("| `subscriptionName` | `").append(subscription.subscriptionName()).append("` |").append(NL);
		sb.append("| `clientId` | `").append(subscription.clientId()).append("` |").append(NL);
		sb.append("| `durable` | `").append(catalog.isDurableTopic(subscription)).append("` |").append(NL);
		sb.append(NL);
		sb.append("Both identity values are the **subscription's** name, not any event type's.").append(NL);
		sb.append("That is what lets another application consume these same events and get its").append(NL);
		sb.append("own copy: two subscribers sharing these values would not be two subscriptions").append(NL);
		sb.append("clashing, they would be one subscription named twice, and the two apps would").append(NL);
		sb.append("compete for a single stream. Do not copy this file into a second app and edit").append(NL);
		sb.append("the handler bodies — declare a second subscription and generate from it.").append(NL);
		sb.append(NL);
		sb.append("## Do not hand-edit the selector").append(NL);
		sb.append(NL);
		sb.append(subscription.selectorRationale()).append(NL);
		sb.append(NL);
		sb.append("A selector that does not match what the publisher stamps receives nothing,").append(NL);
		sb.append("silently — which looks exactly like no events being published. If the").append(NL);
		sb.append("subscription's types change, regenerate rather than edit.").append(NL);
		sb.append(NL);
		sb.append("Changing the selector or the subscription name on a live domain **abandons").append(NL);
		sb.append("the old durable subscription**, which then accumulates messages forever").append(NL);
		sb.append("against the destination's quota. Check the Destinations page afterwards and").append(NL);
		sb.append("remove what you orphaned.").append(NL);
		return sb.toString();
	}

	// ------------------------------------------------------------------ helpers

	/// The Maven artifactId / directory name for the generated module: the event
	/// type lowercased with dots turned into hyphens.
	private static String artifactId(EventType declaration) {
		String slug = slug(declaration.getType());
		return "event".equals(slug) ? "event" : slug;
	}

	/// A name lowercased with everything that is not a letter or digit turned
	/// into a hyphen, for use as a Maven artifactId or a directory name.
	private static String slug(String raw) {
		String value = nullToEmpty(raw);
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			sb.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '-');
		}
		return (sb.length() == 0) ? "event" : sb.toString();
	}

	private static void appendPackage(StringBuilder sb, EventType declaration) {
		String pkg = declaration.getJavaPackage();
		if (pkg != null && !pkg.isEmpty()) {
			sb.append("package ").append(pkg).append(";").append(NL);
			sb.append(NL);
		}
	}

	/// Emit exactly the imports the generated payload class needs — Jackson
	/// annotations only when a field actually uses them, so the output does not
	/// carry unused imports the developer then has to clean up.
	private static void appendImports(StringBuilder sb, EventType declaration) {
		Set<String> imports = new TreeSet<>();
		imports.add("java.io.Serializable");
		collectImports(declaration.fieldsOrEmpty(), imports);
		for (String each : imports) {
			sb.append("import ").append(each).append(";").append(NL);
		}
		sb.append(NL);
	}

	private static void collectImports(List<EventField> fields, Set<String> imports) {
		for (EventField field : fields) {
			EventFieldType type = field.getType();
			if (type == EventFieldType.ARRAY) {
				imports.add("java.util.List");
			}
			if (type == EventFieldType.ENUM || field.needsJsonPropertyBinding()) {
				imports.add("com.fasterxml.jackson.annotation.JsonProperty");
			}
			if (field.getDescription() != null && !field.getDescription().isEmpty()) {
				imports.add("com.fasterxml.jackson.annotation.JsonPropertyDescription");
			}
			if (type.scalarJavaImport() != null) {
				imports.add(type.scalarJavaImport());
			}
			if (field.getItemType() != null && field.getItemType().scalarJavaImport() != null) {
				imports.add(field.getItemType().scalarJavaImport());
			}
			collectImports(field.fieldsOrEmpty(), imports);
		}
	}

	private static void appendTypeJavadoc(StringBuilder sb, EventType declaration, String indent, String role) {
		if (declaration.getDescription() != null && !declaration.getDescription().isEmpty()) {
			appendWrapped(sb, indent, "/// ", declaration.getDescription());
			sb.append(indent).append("///").append(NL);
		}
		sb.append(indent).append("/// Generated by the BLADE Event Bus designer — ").append(role).append(" event type")
				.append(NL);
		sb.append(indent).append("/// `").append(declaration.getType()).append("`.").append(NL);
		sb.append(indent).append("///").append(NL);
		sb.append(indent).append("/// Regenerate rather than hand-edit: this class is derived from the").append(NL);
		sb.append(indent).append("/// event catalog, and the catalog is what the publisher, the consumer's")
				.append(NL);
		sb.append(indent).append("/// selector and the ingress validation all agree on.").append(NL);
	}

	private static String javaType(EventField field) {
		switch (field.getType()) {
		case ENUM:
		case OBJECT:
			return field.javaTypeName();
		case ARRAY:
			return "List<" + itemJavaType(field) + ">";
		default:
			return field.getType().scalarJavaType();
		}
	}

	private static String itemJavaType(EventField field) {
		EventFieldType itemType = (field.getItemType() == null) ? EventFieldType.STRING : field.getItemType();
		switch (itemType) {
		case ENUM:
		case OBJECT:
			return field.javaTypeName();
		case ARRAY:
			// An array of arrays is outside what EventField models; fall back to
			// Object rather than emit something that will not compile.
			return "Object";
		default:
			return itemType.scalarJavaType();
		}
	}

	/// A plausible literal for the producer snippet, so the pasted code compiles
	/// as-is and the developer replaces values rather than fixing syntax.
	private static String placeholderLiteral(EventField field) {
		switch (field.getType()) {
		case INTEGER:
			return "1";
		case LONG:
			return "1L";
		case NUMBER:
			return "1.0";
		case BOOLEAN:
			return "true";
		case INSTANT:
			return "Instant.now()";
		case ENUM:
			List<String> values = field.enumValuesOrEmpty();
			return field.javaTypeName() + "." + (values.isEmpty() ? "VALUE" : enumConstant(values.get(0)));
		case OBJECT:
			return "new " + field.javaTypeName() + "()";
		case ARRAY:
			return "new ArrayList<>()";
		default:
			return "\"\"";
		}
	}

	/// Turn a wire value into a legal Java enum constant: letters and digits
	/// uppercased, everything else an underscore, prefixed if it would otherwise
	/// start with a digit.
	private static String enumConstant(String value) {
		if (value == null || value.isEmpty()) {
			return "VALUE";
		}
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			sb.append(Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
		}
		if (Character.isDigit(sb.charAt(0))) {
			sb.insert(0, '_');
		}
		return sb.toString();
	}

	private static String escape(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
	}

	private static String escapeXml(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String nullToEmpty(String raw) {
		return (raw == null) ? "" : raw;
	}

	/// The generated file names for an event type, for a console that wants to
	/// label its download buttons without regenerating the content.
	public static List<String> generatedFileNames(EventType declaration) {
		List<String> names = new ArrayList<>();
		String className = (declaration == null) ? null : declaration.effectiveJavaClassName();
		if (className != null) {
			names.add(className + ".java");
		}
		return names;
	}

	/// The generated file names for a subscription: the listener, plus a payload
	/// class per type it handles.
	public static List<String> generatedFileNames(EventSubscription subscription, EventCatalog catalog) {
		List<String> names = new ArrayList<>();
		String className = (subscription == null) ? null : subscription.effectiveJavaClassName();
		if (className == null) {
			return names;
		}
		names.add(className + "Listener.java");
		for (EventType declared : handledTypes(subscription, (catalog == null) ? new EventCatalog() : catalog)) {
			names.add(declared.effectiveJavaClassName() + ".java");
		}
		return names;
	}
}
