package org.vorpal.blade.framework.v3.configuration.connectors;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Pipeline step that consults an ordered list of [TranslationTable]s —
/// each with its own key expression and match strategy — and spreads
/// the first matched [Translation]'s extras into the session [Context].
///
/// First-match-wins: on [#invoke] the connector walks [#getTables]
/// in order, calling [TranslationTable#lookup] against the current
/// Context. The first table that returns a non-null Translation is
/// the hit; every extras entry on that Translation is written to the
/// Context as a session attribute, and iteration stops. If no table
/// matches, the connector contributes nothing.
///
/// This shape lets operators express fallback-chain lookups — "find
/// the customer by IP, else by source number, else by domain" — as
/// one connector rather than a sequence of conditional TableConnectors
/// that would all cascade into the context.
///
/// Table connectors are pure enrichment — they never make the routing
/// decision. Routing happens at the top-level
/// [org.vorpal.blade.framework.v3.configuration.routing.Routing] after
/// the pipeline completes.
///
/// The selectors list inherited from [Connector] is unused. Hidden from
/// JSON.
@JsonPropertyOrder({ "type", "id", "description", "tables" })
public class TableConnector extends Connector implements Serializable {
	private static final long serialVersionUID = 1L;

	private List<TranslationTable> tables = new LinkedList<>();

	public TableConnector() {
	}

	@JsonPropertyDescription("Ordered list of translation tables; first lookup to match wins and its extras spread into the Context")
	public List<TranslationTable> getTables() {
		return tables;
	}

	public void setTables(List<TranslationTable> tables) {
		this.tables = (tables != null) ? tables : new LinkedList<>();
	}

	/// Convenience for programmatic construction — appends a new
	/// [TranslationTable] and returns it for chaining.
	public TranslationTable addTable(TranslationTable table) {
		if (table != null) tables.add(table);
		return table;
	}

	/// Selectors are meaningless on a table connector — hide the inherited
	/// list from JSON.
	@Override
	@JsonIgnore
	public List<Selector> getSelectors() {
		return super.getSelectors();
	}

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		if (ctx == null || tables == null || tables.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}

		Logger sipLogger = SettingsManager.getSipLogger();
		try {
			for (TranslationTable table : tables) {
				Translation match = table.lookup(ctx);
				if (match == null) continue;

				if (sipLogger != null && sipLogger.isLoggable(Level.FINE)) {
					sipLogger.fine("TableConnector[" + id + "] matched key via "
							+ table.getKeyExpression());
				}
				for (Map.Entry<String, String> e : match.getExtras().entrySet()) {
					ctx.put(e.getKey(), e.getValue());
				}
				return CompletableFuture.completedFuture(null);
			}

			if (sipLogger != null && sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer("TableConnector[" + id + "] no match across " + tables.size()
						+ " table(s)");
			}
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.warning("TableConnector[" + id + "] failed: " + e.getMessage());
			}
		}
		return CompletableFuture.completedFuture(null);
	}
}
