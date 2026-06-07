/// Admin app for browsing and querying BLADE log files across every server
/// in the cluster, from one page at `/blade/logs`.
///
/// [ClusterDiscovery] walks the domain over JMX to find the managed servers
/// and their log locations; [LogReaderClient] and [LogReaderListener] fetch
/// log content from each server; [LogQueryAPI] is the JAX-RS surface the
/// browser viewer calls to list, tail, filter and download logs.
/// [LogsSettings] carries the app's configuration (registered via the
/// standard SettingsManager so it appears in the Configurator).
package org.vorpal.blade.applications.logs;
