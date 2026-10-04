package org.vorpal.blade.applications.dashboard;

import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

/// Owns the dashboard's one `EntityManagerFactory` and the [AnalyticsReports]
/// built on it.
///
/// **Created on first use, not at startup.** A domain with no analytics
/// database still runs the dashboard: health and operations need no database,
/// and the analytics cards report the missing data source instead of the whole
/// application failing to deploy.
///
/// **Rebuilt when the data source setting changes.** The JNDI name comes from
/// `DashboardSettings.analyticsDataSource` on every request, so an operator can
/// repoint the dashboard in the Configurator without a redeploy.
///
/// One per application instance, held on the ServletContext; each node has its
/// own, and nothing is shared between them.
public class AnalyticsStore {

	public static final String ATTR = "org.vorpal.blade.dashboard.analytics";
	static final String UNIT = "BladeDashboard";

	private static final Logger logger = Logger.getLogger(AnalyticsStore.class.getName());

	private String dataSource;
	private EntityManagerFactory factory;
	private AnalyticsReports reports;

	/// The reports, bound to `dataSource`.
	public synchronized AnalyticsReports reports(String dataSource) {
		if (factory == null || !dataSource.equals(this.dataSource)) {
			close();
			factory = Persistence.createEntityManagerFactory(UNIT,
					Collections.singletonMap("javax.persistence.nonJtaDataSource", dataSource));
			reports = new AnalyticsReports(factory);
			this.dataSource = dataSource;
			logger.info("dashboard analytics reading " + dataSource);
		}
		return reports;
	}

	public synchronized void close() {
		if (factory != null) {
			try {
				factory.close();
			} catch (RuntimeException e) {
				logger.log(Level.WARNING, "dashboard analytics close error", e);
			}
		}
		factory = null;
		reports = null;
		dataSource = null;
	}
}
