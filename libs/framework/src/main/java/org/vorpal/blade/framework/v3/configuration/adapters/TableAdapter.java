package org.vorpal.blade.framework.v3.configuration.adapters;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.List;
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

/// Pipeline step that consults a [TranslationTable] as part of the
/// adapter chain.
///
/// On [#invoke]:
///
/// 1. Delegates to [TranslationTable#match] to find a [Translation].
/// 2. If a translation is found, every scalar bean property of its
///    `treatment` is copied into the [Context] session as an attribute
///    (property name → `String.valueOf(value)`). So a `CustomerProfile`
///    treatment with `customerId="acme"` and `apiKey="xxx"` makes
///    `${customerId}` and `${apiKey}` available to every downstream
///    pipeline step.
/// 3. The whole matched [Translation] is stored in `Context` under
///    [Context#LAST_MATCH] so the outer
///    [org.vorpal.blade.framework.v3.configuration.RouterConfiguration]
///    can pick up the final routing decision after the pipeline finishes.
///
/// Chaining a [TableAdapter] early in the pipeline lets later adapters
/// (e.g. a `RestAdapter`) interpolate values the table produced —
/// turning adapters + tables into one ordered pipeline instead of two
/// separate phases.
///
/// The selectors list inherited from [Adapter] is unused. Keep it empty.
///
/// @param <T> the treatment type carried by the wrapped table
@JsonPropertyOrder({ "type", "id", "description", "table" })
public class TableAdapter<T> extends Adapter implements Serializable {
	private static final long serialVersionUID = 1L;

	private TranslationTable<T> table;

	public TableAdapter() {
	}

	public TableAdapter(TranslationTable<T> table) {
		this.table = table;
	}

	@JsonPropertyDescription("Wrapped translation table; hash, prefix, etc.")
	public TranslationTable<T> getTable() {
		return table;
	}

	public void setTable(TranslationTable<T> table) {
		this.table = table;
	}

	/// Selectors are meaningless on a table adapter (it produces its
	/// matched treatment instead). Hide the inherited list from JSON.
	@Override
	@JsonIgnore
	public List<Selector> getSelectors() {
		return super.getSelectors();
	}

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		if (ctx == null || table == null) return CompletableFuture.completedFuture(null);

		Logger sipLogger = SettingsManager.getSipLogger();
		try {
			Translation<T> match = table.match(ctx);
			if (match == null) {
				if (sipLogger != null && sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer("TableAdapter[" + id + "] no match for table "
							+ table.getId());
				}
				return CompletableFuture.completedFuture(null);
			}

			if (sipLogger != null && sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine("TableAdapter[" + id + "] matched translation "
						+ match.getId() + " in table " + table.getId());
			}

			spreadTreatmentIntoContext(ctx, match.getTreatment(), sipLogger);
			ctx.setAttachment(Context.LAST_MATCH, match);
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.warning("TableAdapter[" + id + "] failed: " + e.getMessage());
			}
		}
		return CompletableFuture.completedFuture(null);
	}

	/// Copy every readable scalar bean property of `treatment` into
	/// the [Context] as a session attribute. Null values are skipped;
	/// collections/maps are skipped (they'd need different treatment).
	private static void spreadTreatmentIntoContext(Context ctx, Object treatment, Logger sipLogger) {
		if (treatment == null) return;
		BeanInfo info;
		try {
			info = Introspector.getBeanInfo(treatment.getClass(), Object.class);
		} catch (IntrospectionException e) {
			if (sipLogger != null) {
				sipLogger.warning("TableAdapter: cannot introspect "
						+ treatment.getClass().getSimpleName() + ": " + e.getMessage());
			}
			return;
		}
		for (PropertyDescriptor pd : info.getPropertyDescriptors()) {
			Method reader = pd.getReadMethod();
			if (reader == null) continue;
			Class<?> type = reader.getReturnType();
			if (!isScalar(type)) continue;
			try {
				Object value = reader.invoke(treatment);
				if (value == null) continue;
				ctx.put(pd.getName(), String.valueOf(value));
			} catch (ReflectiveOperationException e) {
				if (sipLogger != null) {
					sipLogger.warning("TableAdapter: failed to read "
							+ pd.getName() + " on "
							+ treatment.getClass().getSimpleName() + ": " + e.getMessage());
				}
			}
		}
	}

	private static boolean isScalar(Class<?> type) {
		return type == String.class
				|| type == Integer.class || type == int.class
				|| type == Long.class || type == long.class
				|| type == Boolean.class || type == boolean.class
				|| type == Double.class || type == double.class
				|| type == Float.class || type == float.class
				|| type.isEnum();
	}
}
