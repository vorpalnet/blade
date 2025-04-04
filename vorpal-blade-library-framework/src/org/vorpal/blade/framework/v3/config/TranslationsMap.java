package org.vorpal.blade.framework.v3.config;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

//@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
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
public interface TranslationsMap<T> {

	public final List<String> selectors = new LinkedList<>();

	public abstract Translation<T> createTranslation(String key);

	public abstract Translation<T> lookup(SipServletRequest request);

	public abstract void addSelector(String id, AttributeSelector sel);

//	public void setSelectors(List<AttributeSelector> selectors);
//
//	public List<AttributeSelector> getSelectors();

// public String id;
//	public String description;

//	private List<AttributeSelector> selectorList = new LinkedList<>();
//	public List<String> selectors = new LinkedList<>();
//	
//	
//	public TranslationsMap<T> addSelector(String selectorId, List<AttributeSelector> selectors) {		
//		return this;
//	}
//	
//	public abstract Translation<T> createTranslation(String key);
//
//	protected abstract Translation<T> lookup(SipServletRequest request);
//
//
	public default Translation<T> applyTranslations(SipServletRequest request) {
		Logger sipLogger = SettingsManager.getSipLogger();

//		String strRequestUri;
//		URI uri;
		Translation<T> translation = null;
//		RegExRoute regexRoute = null;

		try {
			translation = this.lookup(request);

			if (translation != null) {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(request, this.getClass().getSimpleName() + //
					// " " + this.getId() + //
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

//				if (translation.getRequestUri() != null) {
//					strRequestUri = regexRoute.matcher.replaceAll(translation.getRequestUri());
//					uri = SettingsManager.getSipFactory().createURI(strRequestUri);
//
//					// copy all SIP URI parameters (if not present in new request uri)
//					for (String name : request.getRequestURI().getParameterNameSet()) {
//						if (uri.getParameter(name) == null) {
//							uri.setParameter(name, uri.getParameter(name));
//						}
//					}
//				}

				// now check for additional translations
				if (translation.getList() != null) {
					Translation<T> t = null;
					for (TranslationsMap<T> map : translation.getList()) {
//						sipLogger.finer(request, "Checking further TranslationMaps id: " + map.getId());
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

}