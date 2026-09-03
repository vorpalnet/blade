package org.vorpal.blade.framework.v3.security;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Who may do what with call content, as an operator writes it down.
///
/// A section of the `security` admin app's settings, edited in the Configurator
/// like [JwtAuthConfig] and pushed to the engines by the machinery that already
/// distributes every other BLADE config. There is no new editor and no new
/// distribution path, and there is deliberately no policy language: the shape is
/// `proto/acl`'s `AclConfig` — an ordered list, first match wins — because that
/// is the shape operators here already read.
///
/// ## Deny by default, with no way to say otherwise
///
/// `AclConfig` has a `defaultPermission` that an operator may set to `allow`.
/// This has no such field, and the omission is the point. An empty or
/// unconfigured policy grants nothing, a rule list that matches nothing grants
/// nothing, and a deployment that has not thought about access yet is closed
/// rather than open. The failure mode of a configuration mistake should be a
/// support call, not a disclosure.
///
/// ## First match wins, so order is meaning
///
/// [AccessEvaluator] walks the list in order and stops at the first rule that
/// is about this caller, about this record, and grants the permission asked
/// for. Ordering rules from most specific to most general reads the way an
/// access review asks the question.
public class AccessPolicy implements Serializable {
	private static final long serialVersionUID = 1L;

	private LinkedList<AccessRule> rules = new LinkedList<>();

	public AccessPolicy() {
	}

	/// Parse every rule's permit list once, at load. Mirrors
	/// `AclConfig.initialize`, which builds its address trie the same way and
	/// for the same reason: an operator's strings become the runtime form once,
	/// not on every request.
	///
	/// Call this after deserializing a policy. [AccessEvaluator] calls it if it
	/// has not been called, so a hand-built policy in a test behaves like a
	/// loaded one.
	public void initialize() {
		for (AccessRule rule : rules) {
			rule.initialize();
		}
	}

	/// Every permit entry across every rule that named no permission — a typo,
	/// or a name from a newer version of BLADE.
	///
	/// These are dropped rather than rejected, because refusing to load a policy
	/// over one bad word would take a whole deployment's access down for a typo.
	/// Dropping is the safe direction — it grants less — but it is silent, so an
	/// app that loads a policy should log this once and say which entries went
	/// nowhere.
	@JsonIgnore
	public Set<String> unknownPermissions() {
		Set<String> unknown = new LinkedHashSet<>();
		for (AccessRule rule : rules) {
			unknown.addAll(rule.unknownPermissions());
		}
		return unknown;
	}

	@JsonPropertyDescription("Access rules, in order. The first rule that is about this caller, about this record, and grants the permission being asked for decides; if none does, access is refused. There is no way to make the default 'allow'.")
	public LinkedList<AccessRule> getRules() {
		return rules;
	}

	public void setRules(LinkedList<AccessRule> rules) {
		this.rules = (rules == null) ? new LinkedList<>() : rules;
	}
}
