package org.vorpal.blade.framework.v3.config.maps;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipServletRequest;

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

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")
public class ConfigPrefixMap<T> extends HashMap<String, Translation<T>> implements TranslationsMap<T> {
	private static final long serialVersionUID = 1L;

	private static Logger sipLogger = SettingsManager.getSipLogger();

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

	public Translation<T> createTranslation(String key) {
		Translation<T> t = new Translation<>();
		t.setId(key);
		this.put(key, t);
		return t;
	}

	@Override
	public Translation<T> lookup(SipServletRequest request) {
		Translation<T> value = null;

		if (sipLogger == null)
			sipLogger = SettingsManager.getSipLogger();

		try {
			AttributesKey regexRoute = null;

			for (AttributeSelector selector : selectors) {

				regexRoute = selector.findKey(request);

				if (regexRoute != null) {

					String substring;
					for (int i = regexRoute.key.length(); i > 0; --i) {
						substring = regexRoute.key.substring(0, i);

						value = this.get(substring);
						sipLogger.finer(request, "prefix=" + substring + ", value=" + value);
						if (value != null) {
							break;
						}

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
					} else {
						sipLogger.finer(request,
								this.getClass().getSimpleName() + " " + this.getId() + " found no match.");

					}

				}

			}

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}

		return value;
	}

	@Override
	public AttributeSelector addSelector(AttributeSelector selector) {
		this.selectors.add(selector);
		return selector;
	}

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public TranslationsMap<T> setId(String id) {
		this.id = id;
		return this;
	}

}
