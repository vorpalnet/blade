package org.vorpal.blade.framework.v3.configuration.translations.tables;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

/// Hash-based [TranslationTable] — exact-match key lookups via an internal
/// [HashMap]. The first concrete table implementation in BLADE 3.0; other
/// strategies (prefix, tree, address) follow.
///
/// This class uses **composition** rather than inheritance: it holds a
/// `HashMap<String, Translation<T>>` field instead of `extends HashMap`.
/// This is the same pattern as v2's `ConfigHashMap`. The reason for the
/// composition choice is documented on [TranslationTable] — extending
/// `Map` confuses every JSON Schema generator we tried.
///
/// @param <T> the treatment type carried by each translation in this table
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonPropertyOrder({ "id", "description", "translations" })
public class HashTranslationTable<T> implements TranslationTable<T> {
	private static final long serialVersionUID = 1L;

	private String id;
	private String description;

	@JsonPropertyDescription("Map of translation key to translation entry")
	public LinkedHashMap<String, Translation<T>> translations = new LinkedHashMap<>();

	public HashTranslationTable() {
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

	/// Convenience accessor for callers that want the underlying storage
	/// map directly. Returns the live map — modifications affect the table.
	public Map<String, Translation<T>> getTranslations() {
		return translations;
	}

	public void setTranslations(LinkedHashMap<String, Translation<T>> translations) {
		this.translations = (translations != null) ? translations : new LinkedHashMap<>();
	}
}
