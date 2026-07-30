package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/// Compiles what [EventSourceGenerator] emits.
///
/// **Why this exists.** Every other test here asserts on the generated *text* —
/// that a selector appears, that an identity is the subscription's name. Text
/// assertions cannot tell you that the file javac would reject: a missing import,
/// a payload class referenced from another package, an anonymous subclass with no
/// `serialVersionUID`, a duplicate `case` label when two event types derive the
/// same class name. Those are the failures a developer meets *after* downloading
/// the module, which is the worst possible moment to meet them.
///
/// The framework already builds against `javaee-api`, so `javax.ejb` and
/// `javax.jms` are on the test classpath and the generated MDB compiles here
/// exactly as it would in the developer's own project.
///
/// This does not exercise anything at runtime — no container, no JMS, no
/// delivery. It says the source is valid Java and nothing more.
class GeneratedSourceCompilesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static EventType meetingScheduled() {
		EventType declaration = new EventType("net.vorpal.attendant.meeting.scheduled");
		declaration.setTitle("Meeting Scheduled");
		declaration.setDescription("The attendant confirmed a meeting with the caller.");
		declaration.setJavaPackage("com.example.events");
		declaration.setJavaClassName("MeetingScheduled");
		declaration.setDestinationJndi("jms/BladeEventBusTopic");

		EventField who = new EventField("who", EventFieldType.STRING, true);
		who.setDescription("Who the meeting is with.");
		EventField whenText = new EventField("when_text", EventFieldType.STRING, true);
		EventField attendees = new EventField("attendees", EventFieldType.ARRAY, false);
		attendees.setItemType(EventFieldType.STRING);
		EventField status = new EventField("status", EventFieldType.ENUM, false);
		status.setEnumValues(Arrays.asList("confirmed", "needs-follow-up"));
		EventField room = new EventField("room", EventFieldType.STRING, false);
		EventField location = new EventField("location", EventFieldType.OBJECT, false);
		location.setFields(Arrays.asList(room));

		declaration.setFields(Arrays.asList(who, whenText, attendees, status, location));
		return declaration;
	}

	private static EventSubscription subscriber(String name, String... types) {
		EventSubscription subscription = new EventSubscription(name);
		subscription.setJavaPackage("com.example.consumer");
		if (types.length > 0) {
			subscription.setTypes(Arrays.asList(types));
		}
		return subscription;
	}

	/// Write each source to `<package>/<Class>.java` under a temp root and run
	/// javac over the lot, against this JVM's own classpath.
	private static void compile(Path root, List<String[]> sources) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assumptions.assumeTrue(compiler != null, "no JDK compiler on this JVM; nothing to check");

		List<File> files = new ArrayList<>();
		for (String[] each : sources) {
			Path file = root.resolve(each[0]);
			Files.createDirectories(file.getParent());
			Files.write(file, each[1].getBytes(StandardCharsets.UTF_8));
			files.add(file.toFile());
		}

		Path classes = root.resolve("classes");
		Files.createDirectories(classes);

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StandardJavaFileManager files0 = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
				StandardCharsets.UTF_8);
		try {
			List<String> options = Arrays.asList("-classpath", System.getProperty("java.class.path"), "-d",
					classes.toString(), "-nowarn", "-proc:none");
			boolean ok = compiler
					.getTask(null, files0, diagnostics, options, null, files0.getJavaFileObjectsFromFiles(files))
					.call();

			StringBuilder errors = new StringBuilder();
			for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
				if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
					errors.append(System.lineSeparator()).append(diagnostic.getSource() == null ? "?"
							: diagnostic.getSource().getName()).append(":").append(diagnostic.getLineNumber())
							.append(" ").append(diagnostic.getMessage(Locale.ROOT));
				}
			}
			assertTrue(ok && errors.length() == 0, "generated source does not compile:" + errors);
		} finally {
			files0.close();
		}
	}

	private static Path tempRoot() throws Exception {
		Path root = Files.createTempDirectory("blade-generated");
		root.toFile().deleteOnExit();
		return root;
	}

	@Nested
	@DisplayName("an application's own event")
	class ApplicationEvent {

		@Test
		@DisplayName("payload and consumer compile together")
		void payloadAndConsumerCompile() throws Exception {
			EventType declaration = meetingScheduled();
			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(Arrays.asList(declaration));

			EventSubscription calendar = subscriber("calendar", declaration.getType());

			List<String[]> sources = new ArrayList<>();
			sources.add(new String[] { "com/example/events/MeetingScheduled.java",
					EventSourceGenerator.javaSource(declaration) });
			sources.add(new String[] { "com/example/consumer/CalendarListener.java",
					EventSourceGenerator.consumerSource(calendar, catalog) });

			compile(tempRoot(), sources);
		}

		@Test
		@DisplayName("two consumers of the same event compile side by side")
		void twoConsumersCompileTogether() throws Exception {
			EventType declaration = meetingScheduled();
			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(Arrays.asList(declaration));

			List<String[]> sources = new ArrayList<>();
			sources.add(new String[] { "com/example/events/MeetingScheduled.java",
					EventSourceGenerator.javaSource(declaration) });
			sources.add(new String[] { "com/example/consumer/TransferListener.java",
					EventSourceGenerator.consumerSource(subscriber("transfer", declaration.getType()), catalog) });
			sources.add(new String[] { "com/example/consumer/AnalyticsDbListener.java",
					EventSourceGenerator.consumerSource(subscriber("analytics-db", declaration.getType()), catalog) });

			// Two MDBs in one compilation unit set: distinct class names, distinct
			// identities, no clash. The whole point, checked by the compiler.
			compile(tempRoot(), sources);
		}
	}

	@Nested
	@DisplayName("the framework's own events")
	class FrameworkEvents {

		@Test
		@DisplayName("all seventeen payload classes compile")
		void allPayloadsCompile() throws Exception {
			List<String[]> sources = new ArrayList<>();
			for (EventType type : BladeEventCatalog.analyticsTypes()) {
				sources.add(new String[] { "org/vorpal/blade/events/analytics/" + type.effectiveJavaClassName()
						+ ".java", EventSourceGenerator.javaSource(type) });
			}
			compile(tempRoot(), sources);
		}

		@Test
		@DisplayName("a transfer actor selecting five types compiles, alongside the sink")
		void transferActorAndSinkCompile() throws Exception {
			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(BladeEventCatalog.analyticsTypes());

			EventSubscription transfer = subscriber("transfer", BladeEventTypes.TRANSFER_REQUESTED,
					BladeEventTypes.TRANSFER_INITIATED, BladeEventTypes.TRANSFER_COMPLETED,
					BladeEventTypes.TRANSFER_DECLINED, BladeEventTypes.TRANSFER_ABANDONED);

			List<String[]> sources = new ArrayList<>();
			for (EventType type : catalog.typesOrEmpty()) {
				sources.add(new String[] { "org/vorpal/blade/events/analytics/" + type.effectiveJavaClassName()
						+ ".java", EventSourceGenerator.javaSource(type) });
			}
			sources.add(new String[] { "com/example/consumer/TransferListener.java",
					EventSourceGenerator.consumerSource(transfer, catalog) });
			sources.add(new String[] { "org/vorpal/blade/services/analytics/jms/AnalyticsEventListener.java",
					EventSourceGenerator.consumerSource(BladeEventCatalog.analyticsSubscription(), catalog) });

			compile(tempRoot(), sources);
		}
	}

	@Nested
	@DisplayName("the shapes that would not compile")
	class Degenerate {

		@Test
		@DisplayName("a subscription with no types still compiles — it has no payload to bind")
		void aSinkWithNoTypesCompiles() throws Exception {
			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(Arrays.asList(meetingScheduled()));

			List<String[]> sources = new ArrayList<>();
			sources.add(new String[] { "com/example/consumer/EverythingListener.java",
					EventSourceGenerator.consumerSource(subscriber("everything"), catalog) });
			compile(tempRoot(), sources);
		}

		@Test
		@DisplayName("a consumer whose payloads share its own package needs no imports")
		void samePackagePayloadsCompile() throws Exception {
			EventType declaration = meetingScheduled();
			declaration.setJavaPackage("com.example.consumer");

			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(Arrays.asList(declaration));

			List<String[]> sources = new ArrayList<>();
			sources.add(new String[] { "com/example/consumer/MeetingScheduled.java",
					EventSourceGenerator.javaSource(declaration) });
			sources.add(new String[] { "com/example/consumer/CalendarListener.java",
					EventSourceGenerator.consumerSource(subscriber("calendar", declaration.getType()), catalog) });

			compile(tempRoot(), sources);
		}
	}
}
