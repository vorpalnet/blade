/// The BLADE Test Console admin app — a cluster-wide control surface for the
/// BLADE test apps (test-uac, test-uas).
///
/// Runs on the AdminServer (in `blade-admin.ear`, context-root
/// `blade/test-console`). [ConsoleAPI] discovers every tester node's
/// `vorpal.blade:Type=TesterControl` MBean over the federated DomainRuntime
/// MBeanServer, fans start/stop/reset commands out, and aggregates each
/// node's live [LoadStatus][org.vorpal.blade.framework.v3.tester.LoadStatus]
/// and [ScenarioReport][org.vorpal.blade.framework.v3.tester.ScenarioReport]
/// metrics for the dashboard.
///
/// JAX-RS is rooted at `/api` ([RestApplication]); the dashboard itself is
/// static (`console.html` + `console.js`), chromed by the portal brand and
/// protected by the shared `BLADEADMINSESSION` FORM auth. [TestConsoleStartup]
/// registers the [TestConsoleSettings] SettingsManager so the app appears on
/// the Admin Portal launcher deck.
package org.vorpal.blade.applications.testconsole;
