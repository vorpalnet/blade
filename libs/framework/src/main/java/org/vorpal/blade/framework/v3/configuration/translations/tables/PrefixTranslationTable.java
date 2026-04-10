package org.vorpal.blade.framework.v3.configuration.translations.tables;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;
import org.vorpal.blade.framework.v2.config.trie.Trie;

/// Prefix-search [TranslationTable] backed by a [Trie]. Stores translations
/// by exact-match key like any other table, but additionally supports
/// **longest-prefix lookup** via [#longestPrefixOf(String)] — the standard
/// telco "dial plan" operation where the most specific stored key that is
/// a prefix of the input wins.
///
/// ## Storage
///
/// The `translations` field is the source of truth: a plain
/// `LinkedHashMap<String, Translation<T>>`, identical in shape to
/// [HashTranslationTable]. Jackson reads & writes it directly, schema
/// generators see a clean public field.
///
/// The [Trie] is a **lazy secondary index** built on first prefix-lookup
/// call (or after a mutation). It's not serialized, not part of the
/// schema, and not part of the in-memory state Jackson sees. Mutations
/// to `translations` invalidate the index; the next prefix lookup
/// rebuilds it from the map. For typical config sizes (hundreds to
/// thousands of entries) this is a one-time millisecond cost.
///
/// @param <T> the treatment type carried by each translation in this table
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonPropertyOrder({ "id", "description", "translations" })
public class PrefixTranslationTable<T> implements TranslationTable<T> {
	private static final long serialVersionUID = 1L;

	private String id;
	private String description;

	@JsonPropertyDescription("Map of translation key to translation entry")
	public LinkedHashMap<String, Translation<T>> translations = new LinkedHashMap<>();

	/// Lazy secondary index. Built on first prefix-lookup call;
	/// invalidated by any mutation through [#createTranslation(String)].
	/// Not serialized.
	@JsonIgnore
	private transient Trie<Translation<T>> prefixIndex;

	public PrefixTranslationTable() {
	}

	@Override
	@JsonPropertyDescription("Unique identifier for this translation table")
	public String getId() {
		return id;
	}

	@Override
	public void setId(String id) {
		this.id = id;
	}

	@Override
	@JsonPropertyDescription("Human-readable description of this translation table")
	public String getDescription() {
		return description;
	}

	@Override
	public void setDescription(String description) {
		this.description = description;
	}

	@Override
	public Translation<T> createTranslation(String key) {
		Translation<T> t = new Translation<>(key);
		translations.put(key, t);
		invalidatePrefixIndex();
		return t;
	}

	@Override
	public Translation<T> get(String key) {
		return translations.get(key);
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

	/// Telco-style longest-prefix lookup. Returns the most specific
	/// [Translation] whose key is a prefix of `input`, or `null` if no
	/// stored key matches.
	///
	/// Example: with keys `"1"`, `"1816"`, and `"18165551234"`, calling
	/// `longestPrefixOf("18165559876")` returns the translation for
	/// `"1816"` (the longest matching prefix).
	public Translation<T> longestPrefixOf(String input) {
		return getPrefixIndex().longestPrefixOf(input);
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
