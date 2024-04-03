package org.vorpal.blade.framework.config;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({ //
		@JsonSubTypes.Type(value = ConfigAddressMap.class, name = "address"),
		@JsonSubTypes.Type(value = ConfigPrefixMap.class, name = "prefix"),
		@JsonSubTypes.Type(value = ConfigHashMap.class, name = "hash"),
		@JsonSubTypes.Type(value = ConfigLinkedHashMap.class, name = "linked"),
		@JsonSubTypes.Type(value = ConfigTreeMap.class, name = "tree") })
// ConfigAddressMap supports both types -- Delete ConfigIPv4Map in the future
//		@JsonSubTypes.Type(value = ConfigIPv4Map.class, name = "ipv4"),
//		@JsonSubTypes.Type(value = ConfigIPv6Map.class, name = "ipv6")

public abstract class TranslationsMap {
	public String id;
	public String description;
	public List<Selector> selectors = new LinkedList<>();

	public abstract Translation createTranslation(String key);

	protected abstract Translation lookup(SipServletRequest request);

	public abstract int size();

	public Translation applyTranslations(SipServletRequest request) {
		Logger sipLogger = SettingsManager.getSipLogger();

		String strRequestUri;
		URI uri;
		Translation translation = null;
		RegExRoute regexRoute = null;

		try {

			sipLogger.finer(request, "TranslationsMap.applyTranslations() ...");

			translation = this.lookup(request);

			if (translation != null) {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(request, this.getClass().getSimpleName() + //
							" " + this.getId() + //
							" found translation id=" + translation.getId() + //
							", description=" + translation.getDescription() + //
							", attributes=" + Arrays.asList(translation.getAttributes()));
				}

			} else {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(request, this.getClass().getName() + " found no translation.");
				}
			}

			if (translation != null) {

				if (translation.getRequestUri() != null) {
					strRequestUri = regexRoute.matcher.replaceAll(translation.getRequestUri());
					uri = SettingsManager.getSipFactory().createURI(strRequestUri);

					// copy all SIP URI parameters (if not present in new request uri)
					for (String name : request.getRequestURI().getParameterNameSet()) {
						if (uri.getParameter(name) == null) {
							uri.setParameter(name, uri.getParameter(name));
						}
					}
				}

				// now check for additional translations
				if (translation.getList() != null) {
					Translation t = null;
					for (TranslationsMap map : translation.getList()) {
						sipLogger.finer(request, "Checking further TranslationMaps id: " + map.getId());
						t = map.applyTranslations(request);
						if (t != null) {
							break;
						}
					}
					if (t != null) {
						translation = t;
					}
				}

			}

		} catch (Exception e) {
			sipLogger.severe(request, "Unknown error for " + this.getClass().getSimpleName() + ".applyTranslations()");
			sipLogger.severe(request, request.toString());
			sipLogger.logStackTrace(e);
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

	public List<Selector> getSelectors() {
		return selectors;
	}

	public void setSelectors(List<Selector> selectors) {
		this.selectors = selectors;
	}

	public Selector addSelector(Selector selector) {
		this.selectors.add(selector);
		return selector;
	}

}