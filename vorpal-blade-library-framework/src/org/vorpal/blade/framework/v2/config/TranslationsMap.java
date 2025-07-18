package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.Logger;

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

public abstract class TranslationsMap implements Serializable {
	public String id;
	public String description;
	public List<Selector> selectors = new LinkedList<>();

	public abstract Translation createTranslation(String key);

	protected abstract Translation lookup(SipServletRequest request);

	public abstract int size();

	public Translation applyTranslations(SipServletRequest request) {
		Logger sipLogger = SettingsManager.getSipLogger();

		sipLogger.finer(request, "Translation.applyTranslations - begin...");

		String strRequestUri;
		URI uri;
		Translation translation = null;
		RegExRoute regexRoute = null;

		try {
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
					sipLogger.finer(request, this.getClass().getSimpleName() + " found no translation.");
				}
			}

			if (translation != null) {

				if (SettingsManager.getSipFactory() != null && translation.getRequestUri() != null) {
					URI fromUri = request.getRequestURI();

					String ruri = translation.getRequestUri();
					HashMap<String, String> attrMap = new HashMap<>();
					Object objValue;

					for (String name : request.getApplicationSession().getAttributeNameSet()) {
						objValue = request.getApplicationSession().getAttribute(name);
						if (objValue instanceof String) {
							Callflow.getSipLogger().finest(request,
									"TranslationsMap.applyTranslations - SipApplicationSession attrMp name=" + name
											+ ", value=" + objValue);
							attrMap.put(name, (String) objValue);
						}
					}

					for (String name : request.getSession().getAttributeNameSet()) {
						objValue = request.getApplicationSession().getAttribute(name);
						if (objValue instanceof String) {
							Callflow.getSipLogger().finest(request,
									"TranslationsMap.applyTranslations - SipSession attrMap name=" + name + ", value="
											+ objValue);
							attrMap.put(name, (String) objValue);
						}
					}

					Callflow.getSipLogger().finest(request,
							"TranslationsMap.applyTranslations - attempting to resolveVariables, attrMap=" + attrMap);

					ruri = Configuration.resolveVariables(attrMap, ruri);

					URI toUri = SettingsManager.getSipFactory().createURI(ruri);
					Callflow.copyParameters(fromUri, toUri);
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

		sipLogger.finer(request, "Translation.applyTranslations - end. translation=" + translation);

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