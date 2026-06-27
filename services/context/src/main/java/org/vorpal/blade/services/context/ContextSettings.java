package org.vorpal.blade.services.context;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.RouterConfig;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

@SchemaAbout(
		name = "Context",
		tagline = "Inbound SIP Header Store",
		description = "Captures the raw inbound SIP headers per call and exposes them for REST "
				+ "lookup and mutation, keyed by BLADE Selectors — so other systems can read or "
				+ "rewrite the headers a call arrived with.")
public class ContextSettings extends RouterConfig implements Serializable {
	private static final long serialVersionUID = 1L;

}
