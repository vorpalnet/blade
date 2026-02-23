package org.vorpal.blade.framework.v2.analytics;

public class AnalyticsAsyncSipServletSample extends Analytics {

	public AnalyticsAsyncSipServletSample() {

		EventSelector start = createEventSelector("start");
		start.addAttribute("servletName", "servletName", "^.*$", "$0");
		start.addAttribute("server", "weblogic.Name", "^.*$", "$0");

		EventSelector stop = createEventSelector("stop");

	}

}
