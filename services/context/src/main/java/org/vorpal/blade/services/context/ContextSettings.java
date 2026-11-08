package org.vorpal.blade.services.context;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.RouterConfig;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;
import org.vorpal.blade.framework.v3.crud.Crud;
import org.vorpal.blade.framework.v3.crud.CrudCapable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// On the v2 [RouterConfig] base, so it carries the `crud` section through
/// [CrudCapable] rather than the v3 `Configuration`.
@SchemaAbout(
		name = "Context",
		tagline = "Inbound SIP Header Store",
		description = "Captures the raw inbound SIP headers per call and exposes them for REST "
				+ "lookup and mutation, keyed by BLADE Selectors — so other systems can read or "
				+ "rewrite the headers a call arrived with.")
public class ContextSettings extends RouterConfig implements CrudCapable, Serializable {
	private static final long serialVersionUID = 1L;

	protected Crud crud;

	@Override
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("CRUD rules run on every message this application sends: create, read, update or delete headers and body parts, without the CRUD service in the call path")
	public Crud getCrud() {
		return crud;
	}

	public void setCrud(Crud crud) {
		this.crud = crud;
	}

}
