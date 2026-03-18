package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;

public class AnalyticsAsyncSipServletSample extends Analytics implements Serializable{

	private static final long serialVersionUID = 1L;

	public AnalyticsAsyncSipServletSample() {

		EventSelector start = createEventSelector("start");
		start.addAttribute("servletName", "servletName", "^.*$", "$0");
		start.addAttribute("server", "weblogic.Name", "^.*$", "$0");

		EventSelector stop = createEventSelector("stop");

	}

}
