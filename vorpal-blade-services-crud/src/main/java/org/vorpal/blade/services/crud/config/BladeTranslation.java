package org.vorpal.blade.services.crud.config;

import java.util.LinkedList;
import java.util.Map;

import org.vorpal.blade.framework.config.TranslationsMap;

public class BladeTranslation<T> {
	public String id;
	public String description;
	public LinkedList<BladeTranslationsMap<T>> list;
//	public String requestUri;
//	public String[] route;
//	public String[] routeBack;
//	public String[] routeFinal;

	public T route; // can be populated with named group expressions

}
