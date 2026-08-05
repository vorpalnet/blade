package org.vorpal.blade.framework.v3.configuration.connectors;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/// A protocol connector handles "how to talk to" a data source — SIP
/// passively, REST over HTTP, JDBC over SQL, LDAP over LDAP, or an
/// in-memory translation table. Each connector fetches its raw payload,
/// then runs its [Selector]s against the payload to extract values into
/// the SIP session.
///
/// Connectors run sequentially in the iRouter pipeline; later connectors
/// can reference values produced by earlier ones via `${var}`
/// substitution in their config (URLs, queries, body templates,
/// etc.).
///
/// ## Asynchronous contract
///
/// [#invoke] returns a [CompletableFuture] because some connectors
/// (notably [RestConnector]) must not block the SIP container thread.
/// Synchronous connectors return [CompletableFuture#completedFuture]
/// immediately. The iRouter chains them with
/// [CompletableFuture#thenCompose] so each connector runs after the
/// previous one has finished.
// NO @JsonIdentityInfo here, deliberately — same reasoning as
// [org.vorpal.blade.framework.v3.configuration.selectors.Selector], and it bit
// harder on this class. The v2 config classes keep the annotation as a house
// pattern and there it earns its keep (a v2 config declares selectors once and
// references them by bare-string id). No v3 shape does that: connectors live
// only in RouterConfiguration.pipeline and IRouterInvite iterates that list in
// order — nothing ever looks a connector up by id, and `id` here is purely a
// log label (see #tag()).
//
// What the annotation cost, measured against the real model:
//   - a connector with NO id failed the whole config — "No Object Id found for
//     an instance of MapConnector, to assign to property 'id'". One omission,
//     anywhere in the pipeline, was enough. Nothing in this class declared id
//     mandatory (no required=true), so a hand-written pipeline hit this easily.
//   - two connectors sharing an id — even different subclasses — failed with
//     "Already had POJO for id".
//
// Either way the failure was nastily timed: Settings.reload() swallows it, so a
// running node keeps serving on its last-good config and the publish looks
// successful. A cold start leaves config null, the router throws, and OCCAS
// answers 500 on every initial request — so the mistake surfaced at the next
// restart or cluster scale-up, far from the change that caused it.
//
// Do not "restore" this annotation. v2's copy stays where it is.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type",
		defaultImpl = SipConnector.class)
@JsonSubTypes({
		@JsonSubTypes.Type(value = SipConnector.class, name = "sip"),
		@JsonSubTypes.Type(value = RestConnector.class, name = "rest"),
		@JsonSubTypes.Type(value = JdbcConnector.class, name = "jdbc"),
		@JsonSubTypes.Type(value = LdapConnector.class, name = "ldap"),
		@JsonSubTypes.Type(value = TableConnector.class, name = "table")
})
@JsonPropertyOrder({ "type", "id", "description", "selectors" })
public abstract class Connector implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String id;
	protected String description;
	protected List<Selector> selectors = new LinkedList<>();

	@JsonPropertyDescription("Label for this connector in log and trap messages, e.g."
			+ " RestConnector[screening]. Optional, and not required to be unique.")
	public String getId() { return id; }
	public void setId(String id) { this.id = id; }

	@JsonPropertyDescription("Human-readable description of what this connector does")
	@org.vorpal.blade.framework.v2.config.FormLayout(wide = true)
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }

	@JsonPropertyDescription("Selectors that parse this connector's payload and write to the session")
	public List<Selector> getSelectors() { return selectors; }
	public void setSelectors(List<Selector> selectors) {
		this.selectors = (selectors != null) ? selectors : new LinkedList<>();
	}

	public Connector addSelector(Selector selector) {
		this.selectors.add(selector);
		return this;
	}

	/// Short identifying tag for log/trap messages, e.g. `RestConnector[screening]`.
	protected String tag() {
		return getClass().getSimpleName() + "[" + id + "]";
	}

	/// Fetch the payload for this connector and run each selector
	/// against it. Errors should be logged and swallowed so the
	/// pipeline can continue.
	///
	/// Sync connectors should return
	/// [CompletableFuture#completedFuture] once their work is done.
	/// Async connectors (REST, JDBC, LDAP) should return the future
	/// their underlying driver produced.
	public abstract CompletableFuture<Void> invoke(Context ctx);

	/// Helper for subclasses: run every selector against `payload`,
	/// catching and logging individual failures. Synchronous — CPU
	/// work only, no I/O.
	protected void runSelectors(Context ctx, Object payload) {
		Logger sipLogger = SettingsManager.getSipLogger();
		for (Selector selector : selectors) {
			try {
				selector.extract(ctx, payload);
			} catch (Exception e) {
				if (sipLogger.isLoggable(Level.WARNING)) {
					sipLogger.warning(ctx != null ? ctx.getRequest() : null,
							getClass().getSimpleName() + "[" + id + "] selector "
									+ selector.getId() + " failed: " + e.getMessage());
				}
			}
		}
	}
}
