package org.vorpal.blade.framework.v3.configuration.selectors;

import java.util.Map;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Looks up previously-extracted context values in an embedded
/// [TranslationTable] and spreads the matched [Translation]'s extras back
/// into the context — **classification as data**.
///
/// The canonical use is tiering: a selector earlier in the list extracts a
/// customer key (`${From.user}`, `${callerIp}`, an X-header), this selector's
/// table maps key → `{tier: gold}`, and a routing condition tests
/// `${tier} == 'gold'`. One config; the tier assignments are table rows an
/// operator edits, not routing logic.
///
/// - The lookup key comes from the table's own `keyExpression` (a `${}`
///   template resolved against the context), so this selector chains off any
///   selector that ran before it — selectors run in list order.
/// - The table supports the standard match strategies: `hash` (exact),
///   `prefix` (longest-prefix — e.g. number plans), `range` (integer
///   intervals).
/// - On a match, every entry of the Translation's `extras` map is stored
///   both bare (`${tier}`) and namespaced by this selector's id
///   (`${customerTier.tier}`), mirroring [RegexSelector]'s named-group
///   convention. No match → nothing stored; route the fallthrough with an
///   unconditional transition (or test `${tier} == ''`).
///
/// The inherited `attribute` field is meaningless here (the key expression
/// lives on the table) and is hidden from JSON.
@JsonPropertyOrder({ "type", "id", "table" })
public class TableSelector extends Selector {
	private static final long serialVersionUID = 1L;

	private TranslationTable table;

	public TableSelector() {
	}

	public TableSelector(String id, TranslationTable table) {
		this.id = id;
		this.table = table;
	}

	@JsonPropertyDescription("Translation table: keyExpression (a ${} template over extracted values) plus key→Translation rows; the matched Translation's extras spread into the context")
	public TranslationTable getTable() {
		return table;
	}

	public TableSelector setTable(TranslationTable table) {
		this.table = table;
		return this;
	}

	/// The key expression lives on the table — hide the inherited source
	/// attribute from JSON.
	@Override
	@JsonIgnore
	public String getAttribute() {
		return super.getAttribute();
	}

	@Override
	public void extract(Context ctx, Object payload) {
		if (ctx == null || table == null) {
			return;
		}

		Translation match = table.lookup(ctx);
		if (match == null) {
			Logger sipLogger = SettingsManager.getSipLogger();
			if (sipLogger != null && sipLogger.isLoggable(java.util.logging.Level.FINER)) {
				sipLogger.finer("TableSelector[" + id + "] no match for key "
						+ table.getKeyExpression() + "=" + ctx.resolve(table.getKeyExpression()));
			}
			return;
		}

		for (Map.Entry<String, String> e : match.getExtras().entrySet()) {
			store(ctx, e.getKey(), e.getValue());
			if (id != null) {
				store(ctx, id + "." + e.getKey(), e.getValue());
			}
		}
	}
}
