package org.vorpal.blade.framework.v3.configuration.translations.tables;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Hash-based [TranslationTable] — exact-match key lookups via an internal
/// [LinkedHashMap] (insertion order preserved for stable JSON output).
///
/// Uses **composition** rather than inheritance: holds a
/// `LinkedHashMap<String, Translation<T>>` field instead of extending
/// `Map`. Extending Map confuses every JSON Schema generator we tried
/// (documented on [TranslationTable]).
///
/// @param <T> the treatment type carried by each translation in this table
public class HashTranslationTable<T> extends TranslationTable<T> {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Map of translation key to translation entry")
	private LinkedHashMap<String, Translation<T>> translations = new LinkedHashMap<>();

	public HashTranslationTable() {
	}

	@Override
	public Translation<T> lookup(String key) {
		return translations.get(key);
	}

	@Override
	public Translation<T> createTranslation(String key) {
		Translation<T> t = new Translation<>(key);
		translations.put(key, t);
		return t;
	}

	@Override
	public boolean containsKey(String key) {
		return translations.containsKey(key);
	}

	@Override
	public int size() {
		return translations.size();
	}

	@Override
	public Set<String> keySet() {
		return translations.keySet();
	}

	/// Live underlying storage map. Modifications affect the table.
	public Map<String, Translation<T>> getTranslations() {
		return translations;
	}

	public void setTranslations(LinkedHashMap<String, Translation<T>> translations) {
		this.translations = (translations != null) ? translations : new LinkedHashMap<>();
	}
}
