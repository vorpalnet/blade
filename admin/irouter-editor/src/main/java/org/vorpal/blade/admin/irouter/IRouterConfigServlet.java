package org.vorpal.blade.admin.irouter;

import org.vorpal.blade.framework.v2.config.ConfigEditorServlet;
import org.vorpal.blade.framework.v3.irouter.IRouterConfig;
import org.vorpal.blade.framework.v3.irouter.IRouterConfigSample;

/// Load / save / publish / version-history endpoint for the iRouter editor —
/// the whole control plane lives in the framework's [ConfigEditorServlet];
/// this subclass just names the iRouter service. Mapped in web.xml under
/// `/resources/api/config/*`.
public class IRouterConfigServlet extends ConfigEditorServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected String appName() {
		return "irouter";
	}

	@Override
	protected Class<?> settingsClass() {
		return IRouterConfig.class;
	}

	@Override
	protected Object sample() {
		return new IRouterConfigSample();
	}
}
