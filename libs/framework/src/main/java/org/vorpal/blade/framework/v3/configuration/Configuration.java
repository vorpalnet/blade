package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;

import org.vorpal.blade.framework.v3.crud.Crud;
import org.vorpal.blade.framework.v3.crud.CrudCapable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// The v3 base configuration: the v2 base plus an optional `crud` section, the
/// CRUD service's rules run inside this application ([Crud]).
///
/// An application gets the section by extending this class instead of the v2
/// one, and by loading its config through the v3
/// [SettingsManager], which hands every load to
/// [org.vorpal.blade.framework.v3.crud.CrudHook]. The section is absent unless
/// configured, and an absent section costs one null check per message sent.
@JsonPropertyOrder({ "version", "notes", "logging", "session", "analytics", "events", "crud" })
public class Configuration extends org.vorpal.blade.framework.v2.config.Configuration
		implements CrudCapable, Serializable {
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
