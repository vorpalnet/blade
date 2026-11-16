package org.vorpal.blade.framework.v3.access;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.http.HttpServletRequest;

import org.vorpal.blade.framework.v3.security.AccessEvaluator;
import org.vorpal.blade.framework.v3.security.AccessPolicy;
import org.vorpal.blade.framework.v3.security.AccessRule;
import org.vorpal.blade.framework.v3.security.DataPermission;
import org.vorpal.blade.framework.v3.security.SubjectAttributes;

/// What one viewer of an admin application may see: which calls, by the
/// union of the [Grant]s of every group they belong to, and what may be read
/// in them, by the application's [AccessPolicy]. The application applies it
/// on the server, so the browser is only ever sent what this allows; a filter
/// on the page can narrow it, never widen it.
///
/// **Two kinds of seeing.** The platform (servers, heap, operations, data
/// health) is per domain, and only a grant naming the domain under `platform`
/// shows it: a tenant must not see the load of the tenants beside it. Calls
/// are per tenant and department, and a rule shows a call, or a part of one,
/// only if its domain, tenant and department all match.
///
/// **Department is per part of a call.** What a call records (an event, a
/// traced message) carries the department that handled it, so a department
/// viewer sees only their own parts of a call that passed through several.
///
/// **Content** is what was said and who said it: transcripts, caller numbers,
/// accounts, SIP message text. Grants never give it. It is
/// [DataPermission#TRANSCRIPT] in the policy, asked of the record in hand
/// ([#seesContent(String, String, String)]) where the application has one,
/// and of every record ([#seesContent()]) where it gates a whole query, which
/// only a policy rule with no `match` grants. Without it a viewer sees the
/// shape of a call (its timing, its parties' roles, how it ended) and nothing
/// that identifies the people on it.
///
/// The dashboard extends this with its JPA conditions; the Trace app applies
/// it to traced and recorded calls.
public class Access {

	/// In a grant's list, every value, including none at all.
	public static final String ALL = "*";

	/// The WebLogic roles that see every call while no grants are configured:
	/// the roles the admin applications admitted before they had grants.
	public static final List<String> ADMIN_ROLES = Collections
			.unmodifiableList(Arrays.asList("Admin", "Operator", "Deployer", "Monitor"));

	/// The policy an admin application starts with: the WebLogic groups behind
	/// [#ADMIN_ROLES] read content, as they did before content was a policy.
	/// It is written into the application's settings, where an operator sees
	/// it and narrows it; a platform role on its own grants nothing.
	///
	/// The rule is for WebLogic sign-ins only. An identity domain has an
	/// `Administrators` group of its own, the cloud account's administrators,
	/// who are not thereby readers of what callers said.
	public static AccessPolicy defaultPolicy() {
		AccessPolicy policy = new AccessPolicy();
		AccessRule rule = new AccessRule("WebLogic administrators read what was said",
				Arrays.asList("Administrators", "Operators", "Deployers", "Monitors"), null,
				Arrays.asList(DataPermission.TRANSCRIPT.permissionName()));
		rule.setSignIn(SubjectAttributes.WEBLOGIC);
		policy.getRules().add(rule);
		return policy;
	}

	/// One grant's call rule. A null set is every value; an empty one, none.
	public static final class Rule {
		public final Set<String> domains;
		public final Set<String> tenants;
		public final Set<String> departments;

		public Rule(Set<String> domains, Set<String> tenants, Set<String> departments) {
			this.domains = domains;
			this.tenants = tenants;
			this.departments = departments;
		}

		public boolean showsNoCalls() {
			return isEmpty(domains) || isEmpty(tenants) || isEmpty(departments);
		}

		/// True if this rule shows every call: no list limits it.
		public boolean showsAll() {
			return domains == null && tenants == null && departments == null;
		}

		/// True if this rule shows a part of a call with these values. A
		/// limited list never matches a missing value: `tenants: [a]` does not
		/// show a call with no tenant.
		public boolean shows(String domain, String tenant, String department) {
			return in(domains, domain) && in(tenants, tenant) && in(departments, department);
		}

		@Override
		public String toString() {
			return show(domains) + "/" + show(tenants) + "/" + show(departments);
		}

		private static boolean in(Set<String> allowed, String value) {
			return allowed == null || (value != null && allowed.contains(value));
		}
	}

	protected final List<Rule> rules;
	/// Domains whose platform this viewer sees; null for every one.
	protected final Set<String> platform;
	/// Who is looking, for the policy's rules; null with no policy.
	protected final SubjectAttributes subject;
	/// The application's policy; null grants no content.
	protected final AccessEvaluator policy;

	protected Access(List<Rule> rules, Set<String> platform, SubjectAttributes subject, AccessEvaluator policy) {
		this.rules = Collections.unmodifiableList(rules);
		this.platform = platform;
		this.subject = subject;
		this.policy = policy;
	}

	/// A copy, for a subclass that adds its own ways of applying it.
	protected Access(Access other) {
		this(other.rules, other.platform, other.subject, other.policy);
	}

	/// The union of the grants a viewer holds, with no content. A viewer who
	/// holds none sees nothing: no calls, no platform.
	public static Access of(Collection<? extends Grant> held) {
		List<Rule> rules = new ArrayList<>();
		Set<String> platform = new TreeSet<>();
		boolean allPlatform = false;
		for (Grant g : held) {
			Rule r = new Rule(set(g.getDomains()), set(g.getTenants()), set(g.getDepartments()));
			if (!r.showsNoCalls()) {
				rules.add(r);
			}
			Set<String> p = set(g.getPlatform());
			if (p == null) {
				allPlatform = true;
			} else {
				platform.addAll(p);
			}
		}
		return new Access(rules, allPlatform ? null : platform, null, null);
	}

	/// Every call and every domain's platform, with no content.
	public static Access all() {
		return new Access(Collections.singletonList(new Rule(null, null, null)), null, null, null);
	}

	/// This scope, with content as `policy` grants it to `subject`.
	public Access withContent(SubjectAttributes subject, AccessEvaluator policy) {
		return new Access(rules, platform, subject, policy);
	}

	/// What the person making `req` may see: the calls their groups'
	/// `grants` show, and the content `policy` grants them.
	///
	/// With no grants configured, a viewer in a WebLogic admin role sees every
	/// call, as before grants existed, and anyone else signed in, an
	/// identity-domain user for one, sees none. Signing in is never on its own
	/// a reason to see calls. Content comes from the policy either way, and an
	/// empty policy grants none.
	///
	/// A grant's group is held when the request is in that role, or the
	/// signed-in subject is in that group, so a grant and a policy rule can
	/// name the same group the same way.
	public static Access forRequest(HttpServletRequest req, List<? extends Grant> grants, AccessPolicy policy) {
		SubjectAttributes subject = SubjectAttributes.of(req);
		Access scope;
		if (grants == null || grants.isEmpty()) {
			scope = Access.of(Collections.<Grant>emptyList());
			for (String role : ADMIN_ROLES) {
				if (req.isUserInRole(role)) {
					scope = all();
					break;
				}
			}
		} else {
			List<Grant> held = new ArrayList<>();
			for (Grant g : grants) {
				if (g != null && g.getGroup() != null
						&& (req.isUserInRole(g.getGroup()) || (subject != null && subject.inGroup(g.getGroup())))) {
					held.add(g);
				}
			}
			scope = of(held);
		}
		return scope.withContent(subject, new AccessEvaluator(policy));
	}

	/// A grant's list as a set: null for [#ALL], empty for none.
	public static Set<String> set(List<String> values) {
		if (values == null) {
			return Collections.emptySet();
		}
		Set<String> s = new TreeSet<>();
		for (String v : values) {
			if (ALL.equals(v)) {
				return null;
			}
			if (v != null && !v.isEmpty()) {
				s.add(v);
			}
		}
		return s;
	}

	/// The call rules, one per grant that shows any calls. Empty: no calls.
	public List<Rule> rules() {
		return rules;
	}

	/// True if this viewer's grants show every call.
	public boolean seesAllCalls() {
		for (Rule r : rules) {
			if (r.showsAll()) {
				return true;
			}
		}
		return false;
	}

	/// True if some rule shows a part of a call with these values.
	public boolean seesCall(String domain, String tenant, String department) {
		for (Rule r : rules) {
			if (r.shows(domain, tenant, department)) {
				return true;
			}
		}
		return false;
	}

	/// True if the viewer sees `domain`'s platform.
	public boolean seesPlatform(String domain) {
		return platform == null || (domain != null && platform.contains(domain));
	}

	/// True if the viewer sees any domain's platform.
	public boolean seesAnyPlatform() {
		return platform == null || !platform.isEmpty();
	}

	/// True if the policy lets this viewer read the content of every call they
	/// see: a rule about them, with no `match`, permits `phi:transcript`. What
	/// an application asks before a query whose rows it does not check one by
	/// one, such as finding calls by a caller's number.
	public boolean seesContent() {
		return policy != null && policy.evaluate(subject, DataPermission.TRANSCRIPT, null).isAllowed();
	}

	/// True if the policy lets this viewer read the content of a part of a
	/// call with these values. A rule may match `domain`, `tenant` and
	/// `department`.
	public boolean seesContent(String domain, String tenant, String department) {
		if (policy == null) {
			return false;
		}
		Map<String, String> record = new HashMap<>();
		put(record, "domain", domain);
		put(record, "tenant", tenant);
		put(record, "department", department);
		return policy.evaluate(subject, DataPermission.TRANSCRIPT, record).isAllowed();
	}

	/// What this viewer sees, in words, one line per kind of seeing, for an
	/// account menu.
	public static List<String> describe(Access access) {
		List<String> lines = new ArrayList<>();
		if (access.rules.isEmpty()) {
			lines.add("Calls: none");
		} else if (access.seesAllCalls()) {
			lines.add("Calls: all");
		} else {
			for (Rule r : access.rules) {
				List<String> parts = new ArrayList<>();
				parts.add(r.tenants == null ? "every tenant" : "tenant " + String.join(", ", r.tenants));
				parts.add(r.departments == null ? "every department"
						: "department " + String.join(", ", r.departments));
				if (r.domains != null) {
					parts.add("in " + String.join(", ", r.domains));
				}
				lines.add("Calls: " + String.join(" · ", parts));
			}
		}
		lines.add("Servers: " + (access.platform == null ? "all domains"
				: access.platform.isEmpty() ? "none" : String.join(", ", access.platform)));
		lines.add("Transcripts and callers: " + (access.seesContent() ? "yes" : "no"));
		return lines;
	}

	/// A stable description, for a report cache's key and the audit record.
	public String key() {
		return rules + "|" + show(platform) + "|" + seesContent();
	}

	@Override
	public String toString() {
		return "Access[" + key() + "]";
	}

	private static void put(Map<String, String> record, String key, String value) {
		if (value != null) {
			record.put(key, value);
		}
	}

	private static boolean isEmpty(Set<String> s) {
		return s != null && s.isEmpty();
	}

	private static String show(Set<String> s) {
		return s == null ? ALL : String.join(",", s);
	}
}
