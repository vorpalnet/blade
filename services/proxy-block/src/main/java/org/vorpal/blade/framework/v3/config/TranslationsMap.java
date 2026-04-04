package org.vorpal.blade.framework.v3.config;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.config.maps.ConfigHashMap;
import org.vorpal.blade.framework.v3.config.maps.ConfigPrefixMap;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({ //
//	@JsonSubTypes.Type(value = ConfigLinkedHashMap.class, name = "linked"),
//	@JsonSubTypes.Type(value = ConfigTreeMap.class, name = "tree"), 
//	@JsonSubTypes.Type(value = ConfigAddressMap.class, name = "ipaddr"), 
//	@JsonSubTypes.Type(value = ConfigIPv4Map.class, name = "ipv4"),
//	@JsonSubTypes.Type(value = ConfigIPv6Map.class, name = "ipv6"),
		@JsonSubTypes.Type(value = ConfigPrefixMap.class, name = "prefix"),
		@JsonSubTypes.Type(value = ConfigHashMap.class, name = "hash") //
})
@JsonPropertyOrder({ "id", "desc", "normalize", "selectors", "defaultRoute" })
public interface TranslationsMap<T> extends Map<String, Translation<T>> {

//	public String id = null;
	public String getId();

	public TranslationsMap<T> setId(String id);

//	public String desc = null;
//	public Boolean normalize = null;
//	public List<AttributeSelector> selectors = new LinkedList<>();

	public List<AttributeSelector> getSelectors();

	public TranslationsMap<T> setSelectors(List<AttributeSelector> selectors);

	@JsonIgnore
	public Logger sipLogger = Callflow.getSipLogger();

	public AttributeSelector addSelector(AttributeSelector selector);

	public Translation<T> createTranslation(String key);

	public Translation<T> lookup(SipServletRequest request);

	public default  Translation<T> applyTranslations(SipServletRequest request) {
		Logger sipLogger = SettingsManager.getSipLogger();

		Translation<T> translation = null;

		try {
			translation = this.lookup(request);

			if (translation != null) {
				// now check for additional translations
				if (translation.getList() != null) {
					Translation<T> t = null;
					for (TranslationsMap<T> map : translation.getList()) {
						t = map.applyTranslations(request);
						if (t != null) {
							break;
						}
					}
					if (t != null) {
						translation = t;
					}
				}

				if (sipLogger.isLoggable(Level.FINE)) {
					sipLogger.fine(request, this.getClass().getSimpleName() + " " + this.getId() + " found translation "
							+ translation.getId());
				}

				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.logObjectAsJson(request, Level.FINER, translation);
				}

			} else {
				sipLogger.fine(request, this.getClass().getSimpleName() + " " + this.getId()
						+ " found no translation, returning null.");
			}

		} catch (Exception e) {
			sipLogger.severe(request, "Unknown error for " + this.getClass().getName() + ".applyTranslations()");
			sipLogger.severe(request, request.toString());
			sipLogger.logStackTrace(e);
		}

		return translation;

	}

}