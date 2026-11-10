package org.vorpal.blade.admin.crud;

import org.vorpal.blade.framework.v2.config.ConfigEditorServlet;
import org.vorpal.blade.framework.v3.crud.Crud;
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

	/// The saved tests did not run because their file cannot be read: the
	/// rules still save, and the operator learns why the tests did not guard
	/// them.
	@Override
	protected java.util.List<String> saveWarnings() {
		return CrudTests.unreadable(appName());
	}

	@Override
	protected java.util.List<String> problems(Object settings, boolean domainFile) {
		CrudSettings crud = (CrudSettings) settings;
		java.util.List<String> problems = Crud.problems(crud.getRuleSets(), crud.getDefaultRuleSet());
		// Saved tests recorded on the domain file guard its Save. A cluster or
		// server file's own tests run on demand.
		if (domainFile && problems.isEmpty()) {
			try {
				problems.addAll(CrudTests.failures(crud, CrudTests.load(appName()), "domain"));
			} catch (java.io.IOException unreadable) {
				// Not a reason to refuse the rules: [#saveWarnings] says so.
			}
		}
		return problems;
	}

	@Override
	protected Object sample() {
		return new CrudConfigurationSample();
	}
}
