package org.vorpal.blade.admin.crud;

import org.vorpal.blade.framework.v2.config.ConfigEditorServlet;
import org.vorpal.blade.framework.v3.crud.CrudConfigurationSample;
import org.vorpal.blade.framework.v3.crud.CrudSettings;

/// Load / save / publish / version-history endpoint for the rules editor —
/// the whole control plane lives in the framework's [ConfigEditorServlet];
/// this subclass just names the CRUD service. Mapped in web.xml under
/// `/resources/api/config/*`.
public class CrudConfigServlet extends ConfigEditorServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected String appName() {
		return "crud";
	}

	@Override
	protected Class<?> settingsClass() {
		return CrudSettings.class;
	}

	@Override
	protected Object sample() {
		return new CrudConfigurationSample();
	}
}
