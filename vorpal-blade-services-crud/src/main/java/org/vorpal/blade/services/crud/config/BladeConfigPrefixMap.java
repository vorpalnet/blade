package org.vorpal.blade.services.crud.config;

import org.apache.commons.collections4.trie.PatriciaTrie;

public class BladeConfigPrefixMap<T> extends BladeTranslationsMap<T>{
	public PatriciaTrie< BladeTranslation<T> > map = new PatriciaTrie<>();

	public int size() {
		return map.size();
	}

}
