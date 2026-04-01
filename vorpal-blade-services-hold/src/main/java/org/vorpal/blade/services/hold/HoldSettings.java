package org.vorpal.blade.services.hold;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.RouterConfig;

import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;

@JsonSchemaTitle(value = "Hold")
public class HoldSettings extends RouterConfig implements Serializable {
	private static final long serialVersionUID = 2L;

}
