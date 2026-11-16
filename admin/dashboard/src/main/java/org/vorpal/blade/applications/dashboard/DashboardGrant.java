package org.vorpal.blade.applications.dashboard;

/// What one group may see on the dashboard: the framework's
/// [org.vorpal.blade.framework.v3.access.Grant], under the dashboard's
/// `grants` setting. A viewer's access is the union of the grants of every
/// group they belong to ([Access#of]); a viewer in no granted group sees
/// nothing.
public class DashboardGrant extends org.vorpal.blade.framework.v3.access.Grant {

	private static final long serialVersionUID = 1L;
}
