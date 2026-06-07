/// Admin console for the BLADE analytics pipeline at `/blade/analytics` —
/// audits and provisions the WebLogic resources the analytics service
/// depends on, and can generate sample call data for testing.
///
/// [WlsResourceAudit] inspects the domain over JMX and reports whether the
/// JMS resources (connection factory, distributed queue) and the JDBC
/// datasource the analytics service requires are present and correctly
/// configured; [WlsResourceProvisioner] can create what's missing.
/// [AuditAPI] and [SampleDataAPI] are the JAX-RS surfaces behind the
/// console UI; [SampleDataGenerator] fabricates realistic call records so
/// dashboards and queries can be exercised without live SIP traffic.
package org.vorpal.blade.applications.analytics;
