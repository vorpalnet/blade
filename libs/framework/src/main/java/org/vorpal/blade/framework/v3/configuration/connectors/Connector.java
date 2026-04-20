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

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/// A protocol connector handles "how to talk to" a data source — SIP
/// passively, REST over HTTP, JDBC over SQL, LDAP over LDAP, or an
/// in-memory hash map. Each connector fetches its raw payload, then
/// runs its [Selector]s against the payload to extract values into
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
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type",
		defaultImpl = SipConnector.class)
@JsonSubTypes({
		@JsonSubTypes.Type(value = SipConnector.class, name = "sip"),
		@JsonSubTypes.Type(value = MapConnector.class, name = "map"),
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

	@JsonPropertyDescription("Unique identifier for this connector")
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
					sipLogger.warning(getClass().getSimpleName() + "[" + id + "] selector "
							+ selector.getId() + " failed: " + e.getMessage());
				}
			}
		}
	}
}
