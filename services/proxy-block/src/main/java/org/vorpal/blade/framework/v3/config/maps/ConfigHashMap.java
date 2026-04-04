package org.vorpal.blade.framework.v3.config.maps;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.config.AttributeSelector;
import org.vorpal.blade.framework.v3.config.Translation;
import org.vorpal.blade.framework.v3.config.TranslationsMap;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

//@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="@type")
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")

public class ConfigHashMap<T> extends HashMap<String, Translation<T>> implements TranslationsMap<T> {
	private static final long serialVersionUID = 1L;

	public List<AttributeSelector> selectors = new LinkedList<>();

	public List<AttributeSelector> getSelectors() {
		return selectors;
	}

	public TranslationsMap<T> setSelectors(List<AttributeSelector> selectors) {
		this.selectors = selectors;
		return this;
	}

	@JsonPropertyDescription("Required identifier, typically the same as the map key")
	public String id;

	@JsonPropertyDescription("Optional description field for human readability purposes")
	public String desc;

	@JsonPropertyDescription("Optional default route")
	public Translation<T> defaultRoute;

	public String getId() {
		return id;
	}

	public TranslationsMap<T> setId(String id) {
		this.id = id;
		return this;
	}

	public String getDesc() {
		return desc;
	}

	public TranslationsMap<T> setDesc(String desc) {
		this.desc = desc;
		return this;
	}

	static Logger sipLogger = SettingsManager.getSipLogger();

	@Override
	public Translation<T> lookup(SipServletRequest request) {
		Translation<T> value = null;

		if (sipLogger == null)
			sipLogger = SettingsManager.getSipLogger();

		try {
			for (AttributeSelector selector : selectors) {

				sipLogger.finer("selector.id=" + selector.getId());

				AttributesKey regexRoute = selector.findKey(request);

				if (regexRoute != null) {
					value = this.get(regexRoute.key);
				}

				if (value != null) {
					value = new Translation<>(value);
					if (value.getAttributes() == null) {
						value.setAttributes(new HashMap<>());
					}
					if (regexRoute.attributes != null) {
						value.getAttributes().putAll(regexRoute.attributes);
					}
					break;
				}
			}

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(e);
		}

		return value;
	}

	@Override
	public Translation<T> createTranslation(String key) {
		Translation<T> value = new Translation<>();
		value.setId(key);

		Callflow.getSipLogger().finer("ConfigHashMap setting value: " + value);
		this.put(key, value);
		Callflow.getSipLogger().finer("ConfigHashMap getting value: " + this.get(key));

		return value;
	}

	@Override
	public AttributeSelector addSelector(AttributeSelector selector) {
		selectors.add(selector);
		return selector;
	}

}
