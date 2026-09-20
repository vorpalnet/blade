package org.vorpal.blade.framework.v3.configuration;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;

/// Inline, sequential pipeline execution — the synchronous counterpart to
/// the iRouter callflow's `thenCompose` chain, shared by every caller that
/// must have the enriched [Context] before continuing on the same thread:
/// the CRUD service's rule-set selection and the editors' dry-run previews.
///
/// Each connector's future is joined before the next runs; sync connectors
/// (`sip`, `table`) are already complete when `invoke` returns, so
/// joining costs nothing. An I/O connector (`rest`, `jdbc`, `ldap`) blocks
/// for its round-trip, at most [#WAIT_SECONDS] — callers on the SIP container thread should prefer
/// message-derived enrichment. A connector failure is logged and skipped so
/// the rest of the pipeline still runs, same policy as the async chain.
public final class Pipelines {

	/// Longest this thread waits for one connector, set with
	/// `-Dvorpal.blade.pipeline.waitSeconds` (default 5). The caller may be a SIP
	/// container thread, so an unbounded wait on a hung database or REST service
	/// would hold that thread, and each new call takes another, until the engine
	/// stops answering. A connector that runs over is skipped like one that failed.
	static final long WAIT_SECONDS = Long.getLong("vorpal.blade.pipeline.waitSeconds", 5L);

	private Pipelines() {
	}

	/// Runs `pipeline` against `request`, returning the enriched [Context].
	public static Context enrich(List<Connector> pipeline, SipServletRequest request) {
		Context ctx = new Context(request);
		Logger sipLogger = SettingsManager.getSipLogger();
		if (pipeline != null) {
			for (Connector connector : pipeline) {
				try {
					CompletableFuture<Void> f = connector.invoke(ctx);
					if (f != null) f.get(WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
				} catch (Exception e) {
					if (sipLogger != null) {
						sipLogger.warning(request, "pipeline connector " + connector.getId()
								+ " failed: " + e.getMessage());
					}
				}
			}
		}
		return ctx;
	}
}
