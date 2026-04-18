package org.vorpal.blade.framework.v3.configuration.translations;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/// A single entry in a [TranslationTable]. Carries an application-defined
/// payload (the `treatment`) of type `T`, plus an optional list of nested
/// [TranslationTable]s for hierarchical lookups (e.g. area code → prefix).
///
/// Translations are emitted with `@JsonIdentityInfo` so that any second
/// occurrence in the JSON output is rendered as a reference to the first
/// by `id`, keeping config files compact and readable.
///
/// @param <T> the application-defined treatment type
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonPropertyOrder({ "id", "description", "treatment", "tables" })
public class Translation<T> implements Serializable {
	private static final long serialVersionUID = 1L;

	private String id;
	private String description;
	private T treatment;
	private List<TranslationTable<T>> tables;

	public Translation() {
	}

	public Translation(String id) {
		this.id = id;
	}

	public Translation(String id, T treatment) {
		this.id = id;
		this.treatment = treatment;
	}

	@JsonPropertyDescription("Unique identifier for this translation")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("Human-readable description of this translation")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("Application-defined payload (treatment) for this translation")
	public T getTreatment() {
		return treatment;
	}

	public void setTreatment(T treatment) {
		this.treatment = treatment;
	}

	@JsonPropertyDescription("Nested translation tables searched after this translation matches; deepest match wins")
	public List<TranslationTable<T>> getTables() {
		return tables;
	}

	public void setTables(List<TranslationTable<T>> tables) {
		this.tables = tables;
	}

	/// Convenience: append a nested table, allocating the list if needed.
	public TranslationTable<T> addTable(TranslationTable<T> table) {
		if (tables == null) {
			tables = new LinkedList<>();
		}
		tables.add(table);
		return table;
	}
}
