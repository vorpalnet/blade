package org.vorpal.blade.services.crud.config;

import org.vorpal.blade.framework.config.ConfigAddressMap;
import org.vorpal.blade.framework.config.ConfigHashMap;
import org.vorpal.blade.framework.config.ConfigLinkedHashMap;
import org.vorpal.blade.framework.config.ConfigPrefixMap;
import org.vorpal.blade.framework.config.ConfigTreeMap;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({ //
		@JsonSubTypes.Type(value = BladeCreate.class, name = "create"), 
		@JsonSubTypes.Type(value = BladeUpdate.class, name = "update"), 
		@JsonSubTypes.Type(value = BladeRead.class, name = "read"), 
		@JsonSubTypes.Type(value = BladeDelete.class, name = "delete"), 
		@JsonSubTypes.Type(value = ConfigTreeMap.class, name = "tree") })
public class BladeRule {
	public String component;

}
