package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.AttributeSelector.DialogType;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.AnalyticsEvent;
import org.vorpal.blade.framework.v3.events.AnalyticsEventMapper;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventBusSettings;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/// This application's analytics configuration, and the extraction that runs off
/// it.
///
/// **Two halves that stay apart on purpose.** The [EventSelector] map here says
/// *how to extract* a value from a SIP message in this particular application —
/// the same logical event legitimately comes from different headers in different
/// apps, so it is per-app configuration. What an event *is* — its name, its
/// payload shape, where it flows — is domain-wide and lives in the event catalog.
/// The console cross-checks the two and reports drift; it does not merge them.
///
/// **Analytics publishes to the event bus like everything else.** It used to own
/// a second JMS client, `JmsPublisher`, writing Java-serialized JPA entities to a
/// queue of its own — which meant two messaging systems, two mental models, and a
/// consumer that had to be on BLADE's classpath to read a message at all. Now
/// [#sendEvent] puts a CloudEvent on the same topic every other BLADE event goes
/// to, so the analytics database is simply one subscriber among however many the
/// operator declares.
@JsonPropertyOrder({ "enabled", "events" })
public class Analytics implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonProperty(defaultValue = "false")
	private Boolean enabled = false;

	private Map<String, EventSelector> events = new HashMap<>();

	// For associating SIP with HTTP
	@JsonIgnore
	public static final ThreadLocal<SipServletRequest> sipServletRequest = new ThreadLocal<>();

	/// When this application instance started, stamped once at
	/// [#applicationStart] and carried on every event afterwards.
	///
	/// **This is the application's identity, not a decoration.** An instance is
	/// one app, on one server, with one configuration — a restart is a new
	/// instance, deliberately — and `(name, domain, server, startedAt)` says
	/// exactly that. It replaces a random 64-bit `application_id` the producer
	/// used to mint and put on the wire: a surrogate primary key invented by the
	/// one participant that has no database.
	///
	/// A per-WAR static, which is right — the framework jar ships inside each WAR,
	/// so each application gets its own.
	@JsonIgnore
	private static volatile Date applicationStartedAt;

	public Analytics() {
	}

	public EventSelector createEventSelector(String event) {
		EventSelector evsel = new EventSelector();
		events.put(event, evsel);
		return evsel;
	}

	/// Create an event and apply this application's `origin` attribute selectors.
	///
	/// The destination attributes are added later, by
	/// [#addDestinationAttributes], when the message that carries them is
	/// actually sent — which is why the event is a mutable [AnalyticsEvent] until
	/// [#sendEvent] closes it.
	public AnalyticsEvent createEvent(String eventName, SipServletMessage message) {
		SipApplicationSession appSession = message.getApplicationSession();
		AnalyticsEvent event = new AnalyticsEvent(eventName, getVorpalId(appSession), getCallStartedAt(appSession));
		applyOrigin(event, selectorsFor(eventName), message);
		return event;
	}

	public AnalyticsEvent createEvent(String eventName, SipServletContextEvent context) {
		AnalyticsEvent event = new AnalyticsEvent(eventName, null, null);
		EventSelector evsel = selectorsFor(eventName);
		if (evsel != null) {
			for (AttributeSelector attrSel : evsel.getAttributes()) {
				if (DialogType.origin.equals(attrSel.getDialog())) {
					add(event, attrSel, attrSel.findKey(context));
				}
			}
		}
		return event;
	}

	public AnalyticsEvent createEvent(String eventName, JsonNode jsonNode) {
		AnalyticsEvent event = new AnalyticsEvent(eventName, null, null);
		EventSelector evsel = selectorsFor(eventName);
		if (evsel != null) {
			for (AttributeSelector attrSel : evsel.getAttributes()) {
				if (DialogType.origin.equals(attrSel.getDialog())) {
					add(event, attrSel, attrSel.findKey(jsonNode));
				}
			}
		}
		return event;
	}

	public AnalyticsEvent addDestinationAttributes(AnalyticsEvent event, SipServletMessage message) {
		EventSelector evsel = selectorsFor(event.getName());
		if (evsel != null) {
			for (AttributeSelector attrSel : evsel.getAttributes()) {
				if (!DialogType.origin.equals(attrSel.getDialog())) {
					add(event, attrSel, attrSel.findKey(message));
				}
			}
		}
		return event;
	}

	public AnalyticsEvent addDestinationAttributes(AnalyticsEvent event, SipServletContextEvent ssce) {
		EventSelector evsel = selectorsFor(event.getName());
		if (evsel != null) {
			for (AttributeSelector attrSel : evsel.getAttributes()) {
				if (!DialogType.origin.equals(attrSel.getDialog())) {
					add(event, attrSel, attrSel.findKey(ssce));
				}
			}
		}
		return event;
	}

	public AnalyticsEvent addDestinationAttributes(AnalyticsEvent event, HttpServletResponse response) {
		return addDestinationAttributes(event, response, null);
	}

	public AnalyticsEvent addDestinationAttributes(AnalyticsEvent event, HttpServletResponse response,
			byte[] responseBody) {

		EventSelector evsel = selectorsFor(event.getName());
		if (evsel != null) {
			for (AttributeSelector attrSel : evsel.getAttributes()) {
				if (!DialogType.origin.equals(attrSel.getDialog())) {
					add(event, attrSel, attrSel.findKey(response, responseBody));
				}
			}
		}
		return event;
	}

	private EventSelector selectorsFor(String eventName) {
		return (eventName == null) ? null : events.get(eventName);
	}

	private void applyOrigin(AnalyticsEvent event, EventSelector evsel, SipServletMessage message) {
		if (evsel == null) {
			return;
		}
		for (AttributeSelector attrSel : evsel.getAttributes()) {
			if (DialogType.origin.equals(attrSel.getDialog())) {
				add(event, attrSel, attrSel.findKey(message));
			}
		}
	}

	private static void add(AnalyticsEvent event, AttributeSelector selector, AttributesKey matched) {
		if (matched != null) {
			event.addAttribute(selector.getId(), matched.key);
		}
	}

	@JsonPropertyDescription("Map of analytics event definitions keyed by event name")
	public Map<String, EventSelector> getEvents() {
		return events;
	}

	public Analytics setEvents(Map<String, EventSelector> events) {
		this.events = events;
		return this;
	}

	/// Close the event and put it on the bus.
	///
	/// Never throws. Publishing runs on the SIP container thread, and an
	/// unreachable or unprovisioned bus must not cost a call — the same contract
	/// [EventBus#publish] has always had, restated here because this is where an
	/// application's own code reaches it.
	public void sendEvent(AnalyticsEvent event) {
		if (event == null) {
			return;
		}
		try {
			EventBus.publish(event.toCloudEvent(source(), SettingsManager.getApplicationName(),
					SettingsManager.getDomainName(), SettingsManager.getServerName(), applicationStartedAt));
		} catch (Throwable t) {
			// Swallowed on purpose — a lost event must not cost a call — but say
			// what was dropped and where. A full destination quota lands here, and
			// this line plus the events.publish.failures counter is its only trace.
			//
			// The logger is resolved defensively because it is not always installed
			// yet: an application whose WebSocket container starts before its SIP
			// servlet can reach this line during deployment, and turning a dropped
			// event into a NullPointerException would defeat the whole point of the
			// catch it is standing in.
			org.vorpal.blade.framework.v2.logging.Logger logger = Callflow.getSipLogger();
			if (logger != null) {
				logger.warning("Analytics.sendEvent - " + event.getName() + " DROPPED, not published to "
						+ EventBus.getDefaultDestinationJndi() + ": " + t.getClass().getSimpleName() + ": "
						+ t.getMessage());
			}
		}
	}

	@JsonPropertyDescription("Enable or disable analytics event collection")
	public Boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}

	/// When this application instance started. Null before [#applicationStart].
	public static Date getApplicationStartedAt() {
		return applicationStartedAt;
	}

	/// The CloudEvents `source` for events this application publishes — the
	/// `events` config block's own value, else derived from the application name.
	private static String source() {
		EventBusSettings settings = SettingsManager.getEventBus();
		if (settings != null && settings.getSource() != null && !settings.getSource().isEmpty()) {
			return settings.getSource();
		}
		return "/blade/" + SettingsManager.getApplicationName();
	}

	/// The cluster-unique vorpal-id for the call (the X-Vorpal-ID Callflow mints
	/// at first-touch), as a long. Returns null if the application session
	/// carries no vorpal-id.
	public static Long getVorpalId(SipApplicationSession appSession) {
		String hex = Callflow.getVorpalSessionId(appSession);
		if (hex == null) {
			return null;
		}
		try {
			return Long.parseLong(hex, 16);
		} catch (NumberFormatException ex) {
			Callflow.getSipLogger().warning("Analytics.getVorpalId - unparseable vorpal-id '" + hex + "'");
			return null;
		}
	}

	/// When the call began, from the X-Vorpal-ID `ts` parameter cached on the
	/// appSession (upper-case hex of epoch millis), so every app in the chain
	/// reports the same instant for the same call.
	///
	/// Falls back to now only when no `ts` was stamped — a legacy-only peer, or an
	/// appSession that never went through Callflow first-touch. That fallback is a
	/// correlation hazard worth knowing about: two applications that both fall
	/// back will disagree by however long the call took to reach the second one,
	/// and to a consumer keyed on `(vorpalId, startedAt)` that is two calls.
	public static Date getCallStartedAt(SipApplicationSession appSession) {
		if (appSession == null) {
			return null;
		}
		String ts = Callflow.getVorpalTimestamp(appSession);
		if (ts != null) {
			try {
				return new Date(Long.parseLong(ts, 16));
			} catch (NumberFormatException ex) {
				Callflow.getSipLogger()
						.warning("Analytics.getCallStartedAt - unparseable vorpal-timestamp '" + ts + "'");
			}
		}
		return new Date();
	}

	/// Publish this application instance's start.
	///
	/// Runs at `servletInitialized`, before the load balancer sends any SIP to
	/// this node, so the fact is always on the bus before any session or event
	/// can reference it. That ordering is why nothing downstream has to cope with
	/// a session arriving before its application.
	public static void applicationStart() {
		applicationStartedAt = new Date();
		publish(AnalyticsEventMapper.application(source(), SettingsManager.getApplicationName(),
				SettingsManager.getDomainName(), SettingsManager.getServerName(), SettingsManager.getHostname(),
				SettingsManager.getTenant(), SettingsManager.getApplicationVersion(), applicationStartedAt, null));
	}

	/// Publish this application instance's stop.
	public static void applicationStop() {
		if (applicationStartedAt == null) {
			// Reachable on a shutdown path where applicationStart never ran.
			return;
		}
		publish(AnalyticsEventMapper.application(source(), SettingsManager.getApplicationName(),
				SettingsManager.getDomainName(), SettingsManager.getServerName(), SettingsManager.getHostname(),
				SettingsManager.getTenant(), SettingsManager.getApplicationVersion(), applicationStartedAt,
				new Date()));
	}

	public static void sessionStart(SipServletMessage msg) {
		publishSession(msg, null);
	}

	public static void sessionStop(SipServletMessage msg) {
		publishSession(msg, new Date());
	}

	/// Publish a call's end from a thread that has no SIP message.
	///
	/// **This exists because not every call ends on a container thread.** An
	/// application that tears a call down from its own timer — a media anchor
	/// reaping a caller who vanished without a BYE — has the correlator and the
	/// birth instant in its own state, but no `SipServletMessage` and no safe way
	/// to reach the application session from off-container. Without this, such an
	/// application can never close a call, and its rows sit open forever with a
	/// null `destroyed`: every duration null, "live calls" counting the dead.
	///
	/// The two arguments are exactly what [#publishSession] extracts from a
	/// message, so the event is indistinguishable on the wire from one published
	/// the ordinary way.
	///
	/// @param vorpalId  the call's correlator
	/// @param startedAt the call's birth instant — identity, not a report time, so
	///                  it must be the same value the start was published with
	public static void sessionStop(long vorpalId, Date startedAt) {
		publish(AnalyticsEventMapper.session(source(), vorpalId, startedAt, new Date(),
				SettingsManager.getApplicationName(), SettingsManager.getDomainName(),
				SettingsManager.getServerName(), applicationStartedAt));
	}

	private static void publishSession(SipServletMessage msg, Date stoppedAt) {
		SipApplicationSession appSession = (msg == null) ? null : msg.getApplicationSession();
		Long vorpalId = getVorpalId(appSession);
		if (vorpalId == null) {
			// Without the correlator there is nothing a consumer could join this
			// to, so publishing would only add an orphan row.
			return;
		}
		publish(AnalyticsEventMapper.session(source(), vorpalId.longValue(), getCallStartedAt(appSession), stoppedAt,
				SettingsManager.getApplicationName(), SettingsManager.getDomainName(),
				SettingsManager.getServerName(), applicationStartedAt));
	}

	/// Publish an index key attached to a call — a configured origin selector that
	/// matched, used to find the call afterwards by something other than its
	/// Vorpal-ID.
	public static void sessionKey(SipApplicationSession appSession, String name, String value) {
		Long vorpalId = getVorpalId(appSession);
		if (vorpalId == null) {
			return;
		}
		publish(AnalyticsEventMapper.sessionKey(source(), vorpalId.longValue(), getCallStartedAt(appSession), name,
				value, SettingsManager.getApplicationName(), SettingsManager.getDomainName(),
				SettingsManager.getServerName(), applicationStartedAt));
	}

	/// The one place the framework's own analytics facts reach the bus. Never
	/// throws: this runs on the SIP container thread.
	private static void publish(CloudEvent event) {
		try {
			EventBus.publish(event);
		} catch (Throwable t) {
			// Same contract as sendEvent: swallowed, but the drop is named.
			Callflow.getSipLogger()
					.warning("Analytics - " + event.getType() + " DROPPED, not published to "
							+ EventBus.getDefaultDestinationJndi() + ": " + t.getClass().getSimpleName() + ": "
							+ t.getMessage());
		}
	}
}
