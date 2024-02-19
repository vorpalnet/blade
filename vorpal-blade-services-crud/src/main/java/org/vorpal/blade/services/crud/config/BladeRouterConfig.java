package org.vorpal.blade.services.crud.config;

import java.util.LinkedList;

import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.Translation;
import org.vorpal.blade.framework.config.TranslationsMap;

public class BladeRouterConfig<T> {

	public LinkedList<BladeSelector> selectors = new LinkedList<>();
	public LinkedList<BladeTranslationsMap<T>> maps = new LinkedList<>();
	public LinkedList<BladeTranslationsMap<T>> plan = new LinkedList<>();
	public BladeTranslation<T> defaultRoute = new BladeTranslation<>();

}
