package org.vorpal.blade.framework.config;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
//
		@JsonSubTypes.Type(value = ConfigAddressMap.class, name = "address"),
		@JsonSubTypes.Type(value = ConfigPrefixMap.class, name = "prefix"),
		@JsonSubTypes.Type(value = ConfigHashMap.class, name = "hash"),
		@JsonSubTypes.Type(value = ConfigLinkedHashMap.class, name = "linked"),
		@JsonSubTypes.Type(value = ConfigTreeMap.class, name = "tree")
//
})
public abstract class TranslationsMap {
	public String id;
	public String description;
	public Selector selector;

	public abstract Translation createTranslation(String key);

	protected abstract Translation lookup(SipServletRequest request);

	public Translation applyTranslations(SipServletRequest request) {
		String strRequestUri;
		URI uri;
		Translation translation = null;
		RegExRoute regexRoute = null;

		try {
			regexRoute = selector.findKey(request);
			if (regexRoute != null) {
				translation = this.lookup(request);

				if (translation != null) {

					if (translation.requestUri != null) {
						strRequestUri = regexRoute.matcher.replaceAll(translation.requestUri);

						uri = SettingsManager.getSipFactory().createURI(strRequestUri);

						// copy all SIP URI parameters (if not present in new request uri)
						for (String name : request.getRequestURI().getParameterNameSet()) {
							if (uri.getParameter(name) == null) {
								uri.setParameter(name, uri.getParameter(name));
							}
						}

						// jwm - use proxy instead
						// request.setRequestURI(uri);

					}

					// now check for additional translations
					if (translation.list != null) {
						Translation t = null;
						for (TranslationsMap map : translation.list) {
							t = applyTranslations(request);
							if (t != null) {
								break;
							}
						}
						if (t != null) {
							translation = t;
						}
					}

				}
			}
		} catch (Exception e) {
			if (SettingsManager.getSipLogger() != null) {
				SettingsManager.getSipLogger().logStackTrace(request, e);
			} else {
				e.printStackTrace();
			}
		}

		return translation;

	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Selector getSelector() {
		return selector;
	}

	public void setSelector(Selector selector) {
		this.selector = selector;
	}

}