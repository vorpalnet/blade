package org.vorpal.blade.applications.analytics;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/// Creates the event-bus JMS stack (connection factory + uniform distributed
/// topic, and the file store / JMS server / module / subdeployment behind them)
/// on startup **if it is not already present** — so a fresh domain does not need
/// an operator to press the audit page's "fix" button or run
/// `services/events/notes/configure-messaging-jms.py` before the bus works. It
/// runs the same code path that button does ([WlsResourceProvisioner#provisionJms]).
///
/// ## Why this lives in analytics-console (an admin-tier app)
///
/// A WebLogic config edit needs the **Edit MBeanServer**, which exists only on
/// the AdminServer (`java:comp/env/jmx/edit`; off-admin the lookup throws). The
/// engine-tier apps that USE the bus (`services/events`, `services/analytics`)
/// run on the cluster and cannot edit config at all. `analytics-console` ships in
/// `blade-admin.ear` and deploys to the AdminServer, so it is the one place that
/// can — and the AdminServer is a single, non-clustered server.
///
/// ## Why there is no race ("multiple apps fighting")
///
/// 1. **Single writer, architecturally** — only the AdminServer can edit config,
///    so no engine node ever provisions, no matter how many boot at once.
/// 2. **Single instance** — one AdminServer, one designated admin app, one
///    `@WebListener` → this runs exactly once per AdminServer lifecycle.
/// 3. **Idempotent under the domain edit lock** — [WlsResourceProvisioner#provisionJms]
///    does create-if-absent inside WebLogic's domain-wide single-holder edit
///    lock, so even a hypothetical concurrent caller finds everything present and
///    no-ops. The read-only [WlsResourceAudit] pre-check below means the edit lock
///    is taken only when something is actually missing — every restart/redeploy
///    of an already-provisioned domain does zero edit work.
///
/// A failure here is logged, never fatal: the app still starts, and publishing /
/// consuming simply no-op until the resources exist — exactly as before this hook.
@WebListener
public class EventBusJmsBootstrap implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(EventBusJmsBootstrap.class.getName());

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			if (alreadyProvisioned()) {
				logger.info("event-bus JMS already provisioned — nothing to do");
				return;
			}
			logger.info("event-bus JMS resources missing — provisioning "
					+ WlsResourceAudit.EXPECTED_CONNECTION_FACTORY_JNDI + " and "
					+ WlsResourceAudit.EXPECTED_TOPIC_JNDI + " ...");
			List<String> steps = WlsResourceProvisioner.provisionJms();
			for (String step : steps) {
				logger.info("  " + step);
			}
			logger.info("event-bus JMS provisioning complete");
		} catch (Exception e) {
			// Never fatal: the admin app still starts. The bus no-ops until the
			// resources exist — provision them with the analytics audit page's
			// "fix" button or services/events/notes/configure-messaging-jms.py.
			logger.log(Level.SEVERE, "event-bus JMS auto-provisioning failed; provision manually "
					+ "(analytics audit 'fix' button or configure-messaging-jms.py)", e);
		}
	}

	/// True only when BOTH the connection factory and the topic are already bound
	/// to their JNDI names — the read-only, no-lock fast path. Any audit failure
	/// (e.g. the Edit/DomainRuntime MBeanServer not reachable off the AdminServer)
	/// returns false so the caller attempts provisioning, which surfaces the real
	/// error rather than silently skipping.
	private static boolean alreadyProvisioned() throws Exception {
		boolean cf = false;
		boolean topic = false;
		for (WlsResourceAudit.Finding f : WlsResourceAudit.run()) {
			switch (f.key) {
				case "connectionFactory": cf = f.present; break;
				case "distributedTopic":  topic = f.present; break;
				default: break;
			}
		}
		return cf && topic;
	}
}
