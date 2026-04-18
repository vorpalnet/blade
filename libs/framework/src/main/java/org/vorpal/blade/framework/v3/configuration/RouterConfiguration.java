package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.adapters.Adapter;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Generic top-level configuration for any service built on the BLADE
/// v3 router pipeline.
///
/// ## Pipeline model
///
/// A single ordered list of [Adapter]s. Each adapter enriches the
/// shared [Context] as it runs:
///
/// - **`sip`** ([org.vorpal.blade.framework.v3.configuration.adapters.SipAdapter])
///   parses the inbound SIP request and writes extracted values into
///   the session via its selectors.
/// - **`rest`** ([org.vorpal.blade.framework.v3.configuration.adapters.RestAdapter])
///   calls an external HTTP API using `${var}`-interpolated URL /
///   headers / body; JSON or XML selectors parse the response.
/// - **`jdbc`** / **`ldap`** / **`map`** — analogous data sources.
/// - **`table`** ([org.vorpal.blade.framework.v3.configuration.adapters.TableAdapter])
///   consults a [org.vorpal.blade.framework.v3.configuration.translations.TranslationTable],
///   spreads the matched treatment's fields into the context, and
///   records the match under [Context#LAST_MATCH].
///
/// Because tables are adapters in this model, operators can interleave
/// them freely — e.g. SIP → enrichment table → REST → routing table —
/// so each REST call can use values from an earlier table lookup.
///
/// The **last** `TableAdapter` that matched during the pipeline is the
/// routing decision. If nothing matched, [#defaultRoute] is returned.
///
/// ## Type parameter
///
/// `<T>` is the **final-routing treatment type** — the payload carried
/// by [#defaultRoute] and by the last table in the pipeline whose
/// match supplies the routing decision. Enrichment tables earlier in
/// the pipeline may carry different treatment types (their contribution
/// is to spread attributes into the context, not to drive routing).
///
/// @param <T> the final-routing treatment type
@JsonPropertyOrder({ "logging", "sessionExpiration", "pipeline", "defaultRoute" })
public class RouterConfiguration<T> extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Ordered pipeline of adapters (SIP, REST, JDBC, LDAP, map, table); runs sequentially per request")
	private List<Adapter> pipeline = new LinkedList<>();

	@JsonPropertyDescription("Default translation returned when no table in the pipeline matches")
	private Translation<T> defaultRoute;

	public RouterConfiguration() {
	}

	public List<Adapter> getPipeline() {
		return pipeline;
	}

	public void setPipeline(List<Adapter> pipeline) {
		this.pipeline = (pipeline != null) ? pipeline : new LinkedList<>();
	}

	public Translation<T> getDefaultRoute() {
		return defaultRoute;
	}

	public void setDefaultRoute(Translation<T> defaultRoute) {
		this.defaultRoute = defaultRoute;
	}

	/// Read the last-matched translation from [Context#LAST_MATCH]
	/// (set by any [org.vorpal.blade.framework.v3.configuration.adapters.TableAdapter]
	/// that ran earlier in the pipeline), cast to `Translation<T>`, or
	/// fall back to [#defaultRoute] if nothing matched.
	///
	/// This does **not** run the pipeline itself — callers should drive
	/// the pipeline asynchronously (iRouter uses
	/// [CompletableFuture#thenCompose] to release the SIP container
	/// thread during REST calls) and invoke this method only once the
	/// chain has completed.
	@SuppressWarnings("unchecked")
	public Translation<T> getFinalTranslation(Context ctx) {
		if (ctx == null) return defaultRoute;
		Object last = ctx.getAttachment(Context.LAST_MATCH);
		if (last instanceof Translation) {
			return (Translation<T>) last;
		}
		return defaultRoute;
	}

	/// Synchronously walk the pipeline (joining each adapter's future
	/// in turn), then return the last-matched translation. Intended
	/// for tests and non-SIP callers; the SIP pipeline drives adapters
	/// asynchronously and calls [#getFinalTranslation] instead.
	public Translation<T> findTranslation(Context ctx) {
		if (ctx == null) return defaultRoute;
		for (Adapter adapter : pipeline) {
			try {
				CompletableFuture<Void> f = adapter.invoke(ctx);
				if (f != null) f.join();
			} catch (Exception ignore) {
				// Adapters should log and swallow their own errors.
			}
		}
		return getFinalTranslation(ctx);
	}
}
