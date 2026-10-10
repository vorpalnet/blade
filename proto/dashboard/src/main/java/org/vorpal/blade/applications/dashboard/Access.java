package org.vorpal.blade.applications.dashboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.persistence.Parameter;
import javax.persistence.Query;

/// What one viewer may see: the union of the [DashboardGrant]s of every group
/// they belong to. Applied on the server, inside every query, so the browser
/// is only ever sent what this allows; a scope picker can narrow it, never
/// widen it.
///
/// **Two kinds of seeing.** The platform (servers, heap, operations, data
/// health, the per-minute samples) is per domain, and only a grant naming the
/// domain under `platform` shows it: a tenant must not see the load of the
/// tenants beside it. Calls are per tenant and department, and a rule shows a
/// call row only if its domain, tenant and department all match.
///
/// **Department is per part of a call.** An event carries the department that
/// handled it, so a department viewer sees only their own events of a call
/// that passed through several. A call row has no department of its own; it
/// is shown if any of its events carry one of the viewer's.
public final class Access {

	/// In a grant's list, every value, including none at all.
	public static final String ALL = "*";

	/// One grant's call rule. A null set is every value; an empty one, none.
	static final class Rule {
		final Set<String> domains;
		final Set<String> tenants;
		final Set<String> departments;

		Rule(Set<String> domains, Set<String> tenants, Set<String> departments) {
			this.domains = domains;
			this.tenants = tenants;
			this.departments = departments;
		}

		boolean showsNoCalls() {
			return isEmpty(domains) || isEmpty(tenants) || isEmpty(departments);
		}

		@Override
		public String toString() {
			return show(domains) + "/" + show(tenants) + "/" + show(departments);
		}
	}

	final List<Rule> rules;
	/// Domains whose platform this viewer sees; null for every one.
	final Set<String> platform;
	final boolean content;

	Access(List<Rule> rules, Set<String> platform, boolean content) {
		this.rules = Collections.unmodifiableList(rules);
		this.platform = platform;
		this.content = content;
	}

	/// The union of the grants a viewer holds. A viewer who holds none sees
	/// nothing: no calls, no platform, no content.
	public static Access of(Collection<DashboardGrant> held) {
		List<Rule> rules = new ArrayList<>();
		Set<String> platform = new TreeSet<>();
		boolean allPlatform = false;
		boolean content = false;
		for (DashboardGrant g : held) {
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
			content |= g.isContent();
		}
		return new Access(rules, allPlatform ? null : platform, content);
	}

	/// The WebLogic roles that see everything while no grants are configured:
	/// the roles the dashboard admitted before it had grants.
	static final List<String> ADMIN_ROLES = java.util.Arrays.asList("Admin", "Operator", "Deployer", "Monitor");

	/// What the person making `req` may see, by the groups they are in.
	///
	/// With no grants configured, a viewer in a WebLogic admin role sees
	/// everything (null: no limit), as before grants existed, and anyone else
	/// signed in, an identity-domain user for one, sees nothing. Signing in
	/// is never on its own a reason to see calls.
	public static Access forRequest(javax.servlet.http.HttpServletRequest req, DashboardSettings settings) {
		List<DashboardGrant> grants = (settings == null) ? null : settings.getGrants();
		if (grants == null || grants.isEmpty()) {
			for (String role : ADMIN_ROLES) {
				if (req.isUserInRole(role)) {
					return null;
				}
			}
			return of(Collections.<DashboardGrant>emptyList());
		}
		List<DashboardGrant> held = new ArrayList<>();
		for (DashboardGrant g : grants) {
			if (g != null && g.getGroup() != null && req.isUserInRole(g.getGroup())) {
				held.add(g);
			}
		}
		return of(held);
	}

	/// Answer for a live endpoint (servers, operations) the viewer may not see:
	/// true, with a JSON `error` written, when this domain's platform is not
	/// theirs. Status 200, not 403: the board reloads the page on a 403,
	/// taking it for an expired sign-in.
	static boolean refusePlatform(javax.servlet.http.HttpServletRequest req,
			javax.servlet.http.HttpServletResponse resp, javax.servlet.ServletContext context)
			throws java.io.IOException {
		Access access = forRequest(req, DashboardSettingsStartup.settings(context));
		if (access == null || access.seesPlatform(org.vorpal.blade.framework.v2.config.SettingsManager.getDomainName())) {
			return false;
		}
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.getWriter().write("{\"error\":\"no grant shows this domain's servers\",\"denied\":true}");
		return true;
	}

	/// A grant's list as a set: null for [#ALL], empty for none.
	static Set<String> set(List<String> values) {
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

	/// True if the viewer sees `domain`'s platform.
	public boolean seesPlatform(String domain) {
		return platform == null || (domain != null && platform.contains(domain));
	}

	/// True if the viewer sees any domain's platform.
	public boolean seesAnyPlatform() {
		return platform == null || !platform.isEmpty();
	}

	public boolean seesContent() {
		return content;
	}

	/// The JPQL condition, starting " AND", that keeps only the rows of
	/// `alias` this viewer may see. Bind it with [#bind].
	///
	/// @param departmentColumn whether the row has a `department` of its own
	///                         (an event-based row) or is a whole call, shown
	///                         when any of its events matches
	String predicate(String alias, boolean departmentColumn) {
		if (rules.isEmpty()) {
			return " AND 1 = 0";
		}
		StringBuilder any = new StringBuilder();
		for (int i = 0; i < rules.size(); i++) {
			Rule r = rules.get(i);
			StringBuilder all = new StringBuilder("1 = 1");
			if (r.domains != null) {
				all.append(" AND ").append(alias).append(".clusterName IN :accessDomains").append(i);
			}
			if (r.tenants != null) {
				all.append(" AND ").append(alias).append(".tenant IN :accessTenants").append(i);
			}
			if (r.departments != null) {
				if (departmentColumn) {
					all.append(" AND ").append(alias).append(".department IN :accessDepartments").append(i);
				} else {
					all.append(" AND ").append(alias).append(".callId IN (SELECT q").append(i)
							.append(".callId FROM EventRow q").append(i).append(" WHERE q").append(i)
							.append(".department IN :accessDepartments").append(i).append(")");
				}
			}
			any.append(i == 0 ? "" : " OR ").append('(').append(all).append(')');
		}
		return " AND (" + any + ")";
	}

	/// The JPQL condition, starting " AND", that keeps only the per-minute
	/// samples of domains whose platform this viewer sees.
	String samplePredicate(String alias) {
		if (platform == null) {
			return "";
		}
		return platform.isEmpty() ? " AND 1 = 0" : " AND " + alias + ".domain IN :accessPlatform";
	}

	/// Bind every access parameter the query declares.
	void bind(Query q) {
		Set<String> declared = new LinkedHashSet<>();
		for (Parameter<?> p : q.getParameters()) {
			if (p.getName() != null) {
				declared.add(p.getName());
			}
		}
		for (int i = 0; i < rules.size(); i++) {
			Rule r = rules.get(i);
			bindIf(q, declared, "accessDomains" + i, r.domains);
			bindIf(q, declared, "accessTenants" + i, r.tenants);
			bindIf(q, declared, "accessDepartments" + i, r.departments);
		}
		bindIf(q, declared, "accessPlatform", platform);
	}

	private static void bindIf(Query q, Set<String> declared, String name, Set<String> values) {
		if (values != null && declared.contains(name)) {
			q.setParameter(name, new ArrayList<>(values));
		}
	}

	/// A stable description, for the report cache's key and the audit record.
	public String key() {
		return rules + "|" + show(platform) + "|" + content;
	}

	@Override
	public String toString() {
		return "Access[" + key() + "]";
	}

	private static boolean isEmpty(Set<String> s) {
		return s != null && s.isEmpty();
	}

	private static String show(Set<String> s) {
		return s == null ? ALL : String.join(",", s);
	}
}
