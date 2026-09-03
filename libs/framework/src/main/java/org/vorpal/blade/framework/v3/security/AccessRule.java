package org.vorpal.blade.framework.v3.security;

import java.io.Serializable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// One rule: who it is about, which records it is about, and what it grants.
///
/// The shape is `proto/acl`'s `AclRule` generalized. There, a rule is an
/// address and a permission and the first match wins. Here the match has two
/// halves — the caller ([#getGroups]) and the record ([#getMatch]) — because
/// "a supervisor may hear their own team's calls" is a relationship, and no
/// amount of role naming expresses a relationship.
///
/// ## Both halves must match, and empty means "any"
///
/// A rule with no groups is about every caller; a rule with no match is about
/// every record. A rule with neither is about everything, which is a rule worth
/// writing deliberately and worth noticing in review.
///
/// ## Matching the caller against the record
///
/// A [#getMatch] value of the form `${subject.name}` compares the record
/// attribute against the caller's own name, and `${subject.<attribute>}`
/// against one of the caller's [SubjectAttributes#attributes]. So:
///
/// ```
/// - name:   "agents hear their own calls"
///   match:  { agent: "${subject.name}" }
///   permit: [ phi:list, phi:transcript ]
/// ```
///
/// grants each agent exactly their own calls, with one rule and no per-user
/// configuration. An unresolvable reference — the deployment supplies no such
/// attribute — does not match, so a rule that depends on a fact nobody provides
/// grants nothing rather than everything.
public class AccessRule implements Serializable {
	private static final long serialVersionUID = 1L;

	private String name;
	private LinkedList<String> groups = new LinkedList<>();
	private LinkedHashMap<String, String> match = new LinkedHashMap<>();
	private LinkedList<String> permit = new LinkedList<>();

	/// Resolved from [#permit] by [#initialize], the way `AclConfig` builds its
	/// trie: parse the operator's strings once at load rather than on every
	/// request. Rebuilt on each load, so it is not part of the JSON.
	private transient Set<DataPermission> resolved;

	public AccessRule() {
	}

	public AccessRule(String name, List<String> groups, Map<String, String> match, List<String> permit) {
		this.name = name;
		if (groups != null) {
			this.groups.addAll(groups);
		}
		if (match != null) {
			this.match.putAll(match);
		}
		if (permit != null) {
			this.permit.addAll(permit);
		}
	}

	/// Parse [#getPermit] into the enum set this rule actually grants, dropping
	/// names that are not permissions.
	///
	/// A typo therefore grants less, never more. It is silent here because this
	/// object has no logger; [AccessPolicy#unknownPermissions] reports what was
	/// dropped so a deployment can be told about it once, at load, instead of
	/// discovering it as a missing grant later.
	public void initialize() {
		Set<DataPermission> set = EnumSet.noneOf(DataPermission.class);
		for (String value : permit) {
			DataPermission permission = DataPermission.fromName(value);
			if (permission != null) {
				set.add(permission);
			}
		}
		resolved = Collections.unmodifiableSet(set);
	}

	/// The permissions this rule grants. Never null; empty until [#initialize].
	@JsonIgnore
	public Set<DataPermission> permissions() {
		return (resolved == null) ? Collections.emptySet() : resolved;
	}

	/// The permit entries that named nothing. Never null.
	@JsonIgnore
	public Set<String> unknownPermissions() {
		Set<String> unknown = new LinkedHashSet<>();
		for (String value : permit) {
			if (DataPermission.fromName(value) == null) {
				unknown.add(value);
			}
		}
		return unknown;
	}

	/// True if this rule is about `subject` — that is, the caller is in one of
	/// [#getGroups], or the rule names no groups at all.
	public boolean appliesTo(SubjectAttributes subject) {
		if (groups.isEmpty()) {
			return true;
		}
		if (subject == null) {
			return false;
		}
		for (String group : groups) {
			if (subject.inGroup(group)) {
				return true;
			}
		}
		return false;
	}

	/// True if this rule is about `record` — every [#getMatch] entry equals the
	/// record's attribute of that name, after `${subject.…}` resolution.
	///
	/// A record missing an attribute the rule names does not match. So does a
	/// rule referencing a subject attribute the deployment does not supply. Both
	/// fail closed, which is the only safe direction for a missing fact.
	public boolean matches(SubjectAttributes subject, Map<String, String> record) {
		if (match.isEmpty()) {
			return true;
		}
		for (Map.Entry<String, String> entry : match.entrySet()) {
			String expected = resolve(entry.getValue(), subject);
			if (expected == null) {
				return false;
			}
			String actual = (record == null) ? null : record.get(entry.getKey());
			if (!expected.equals(actual)) {
				return false;
			}
		}
		return true;
	}

	/// `${subject.name}` and `${subject.<attribute>}` against the caller;
	/// anything else is a literal. Null when the reference cannot be resolved.
	private static String resolve(String value, SubjectAttributes subject) {
		if (value == null || !value.startsWith("${subject.") || !value.endsWith("}")) {
			return value;
		}
		if (subject == null) {
			return null;
		}
		String key = value.substring("${subject.".length(), value.length() - 1);
		return "name".equals(key) ? subject.name() : subject.attribute(key);
	}

	@JsonPropertyDescription("Name of this rule, shown in the audit record as the reason access was granted. Write it as the sentence you would want to read in an access review, e.g. 'QA reviewers hear their own queue'.")
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@JsonPropertyDescription("Identity-provider groups this rule is about. The caller must be in at least one. Leave empty to mean every caller.")
	public LinkedList<String> getGroups() {
		return groups;
	}

	public void setGroups(LinkedList<String> groups) {
		this.groups = (groups == null) ? new LinkedList<>() : groups;
	}

	@JsonPropertyDescription("Record attributes that must match for this rule to apply, e.g. {\"queue\": \"cardiology\"}. A value of ${subject.name} matches the caller's own name, and ${subject.<attribute>} one of the caller's attributes. Leave empty to mean every record.")
	public LinkedHashMap<String, String> getMatch() {
		return match;
	}

	public void setMatch(LinkedHashMap<String, String> match) {
		this.match = (match == null) ? new LinkedHashMap<>() : match;
	}

	@JsonPropertyDescription("What this rule grants: phi:list, phi:transcript, phi:play, phi:export, phi:unredact, phi:audit, phi:breakglass. Grant the least that does the job; a name that is not one of these grants nothing.")
	public LinkedList<String> getPermit() {
		return permit;
	}

	public void setPermit(LinkedList<String> permit) {
		this.permit = (permit == null) ? new LinkedList<>() : permit;
	}

	@Override
	public String toString() {
		return (name == null) ? ("rule" + permit) : name;
	}
}
