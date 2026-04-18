package org.vorpal.blade.framework.v3.configuration.translations.tables;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.vorpal.blade.framework.v2.config.trie.Trie;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Prefix-search [TranslationTable] backed by a [Trie]. Stores translations
/// by exact-match key like any other table, but [#lookup] does
/// **longest-prefix match** — the standard telco "dial plan" operation
/// where the most specific stored key that is a prefix of the input wins.
///
/// Example: with keys `"1"`, `"1816"`, and `"18165551234"`, calling
/// `lookup("18165559876")` returns the translation for `"1816"` (the
/// longest matching prefix).
///
/// ## Storage
///
/// The `translations` field is the source of truth: a plain
/// `LinkedHashMap<String, Translation<T>>`, identical in shape to
/// [HashTranslationTable]. Jackson reads & writes it directly; schema
/// generators see a clean public field.
///
/// The [Trie] is a **lazy secondary index** built on first prefix-lookup
/// call (or after a mutation). It's not serialized, not part of the
/// schema, and not part of the in-memory state Jackson sees. Mutations
/// to `translations` invalidate the index; the next prefix lookup
/// rebuilds it from the map.
///
/// @param <T> the treatment type carried by each translation in this table
public class PrefixTranslationTable<T> extends TranslationTable<T> {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Map of translation key to translation entry")
	private LinkedHashMap<String, Translation<T>> translations = new LinkedHashMap<>();

	@JsonIgnore
	private transient Trie<Translation<T>> prefixIndex;

	public PrefixTranslationTable() {
	}

	/// Longest-prefix lookup: returns the most specific [Translation] whose
	/// key is a prefix of `input`, or `null` if no stored key matches.
	@Override
	public Translation<T> lookup(String key) {
		return getPrefixIndex().longestPrefixOf(key);
	}

	@Override
	public Translation<T> createTranslation(String key) {
		Translation<T> t = new Translation<>(key);
		translations.put(key, t);
		invalidatePrefixIndex();
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

	/// Returns the longest stored key that is a prefix of `input`, or
	/// `null` if no stored key matches.
	public String longestPrefixKeyOf(String input) {
		return getPrefixIndex().longestPrefixKeyOf(input);
	}

	/// Drop the cached secondary index. Call after bulk modifications to
	/// `translations` made outside this class's helper methods.
	public void invalidatePrefixIndex() {
		prefixIndex = null;
	}

	/// Live underlying storage map. Modifications affect the table; call
	/// [#invalidatePrefixIndex] afterward so the next prefix lookup rebuilds.
	public Map<String, Translation<T>> getTranslations() {
		return translations;
	}

	public void setTranslations(LinkedHashMap<String, Translation<T>> translations) {
		this.translations = (translations != null) ? translations : new LinkedHashMap<>();
		invalidatePrefixIndex();
	}

	@JsonIgnore
	private Trie<Translation<T>> getPrefixIndex() {
		Trie<Translation<T>> idx = prefixIndex;
		if (idx == null) {
			idx = new Trie<>();
			for (Map.Entry<String, Translation<T>> entry : translations.entrySet()) {
				idx.put(entry.getKey(), entry.getValue());
			}
			prefixIndex = idx;
		}
		return idx;
	}
}
