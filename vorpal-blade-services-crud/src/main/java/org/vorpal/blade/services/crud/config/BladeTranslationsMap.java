package org.vorpal.blade.services.crud.config;

import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.Translation;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({ //
//		@JsonSubTypes.Type(value = ConfigAddressMap.class, name = "address"),
		@JsonSubTypes.Type(value = BladeConfigPrefixMap.class, name = "prefix")
//		@JsonSubTypes.Type(value = ConfigHashMap.class, name = "hash"),
//		@JsonSubTypes.Type(value = ConfigLinkedHashMap.class, name = "linked"),
//		@JsonSubTypes.Type(value = ConfigTreeMap.class, name = "tree") 
})
public abstract class BladeTranslationsMap<T> {

	public String id;
	public String description;
	public List<Selector> selectors = new LinkedList<>();

}
