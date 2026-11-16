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

/// What one dashboard viewer may see: the framework's
/// [org.vorpal.blade.framework.v3.access.Access], the union of their groups'
/// [DashboardGrant]s, plus the JPQL conditions that apply it inside every
/// report query, so the browser is only ever sent what it allows; a scope
/// picker can narrow it, never widen it.
///
/// **Department is per part of a call.** An event carries the department that
/// handled it, so a department viewer sees only their own events of a call
/// that passed through several. A call row has no department of its own; it
/// is shown if any of its events carry one of the viewer's.
public final class Access extends org.vorpal.blade.framework.v3.access.Access {

	private Access(org.vorpal.blade.framework.v3.access.Access core) {
		super(core);
	}

	/// The union of the grants a viewer holds, with no content. A viewer who
	/// holds none sees nothing: no calls, no platform.
	public static Access of(Collection<? extends org.vorpal.blade.framework.v3.access.Grant> held) {
		return new Access(org.vorpal.blade.framework.v3.access.Access.of(held));
	}

	/// This scope, with content as `policy` grants it to `subject`.
	@Override
	public Access withContent(org.vorpal.blade.framework.v3.security.SubjectAttributes subject,
			org.vorpal.blade.framework.v3.security.AccessEvaluator policy) {
		return new Access(super.withContent(subject, policy));
	}

	/// What the person making `req` may see, by the groups they are in and the
	/// dashboard's policy. See the framework's `forRequest`.
	public static Access forRequest(javax.servlet.http.HttpServletRequest req, DashboardSettings settings) {
		return new Access(org.vorpal.blade.framework.v3.access.Access.forRequest(req,
				(settings == null) ? null : settings.getGrants(), (settings == null) ? null : settings.getAccess()));
	}

	/// Answer for a live endpoint (servers, operations) the viewer may not see:
	/// true, with a JSON `error` written, when this domain's platform is not
	/// theirs. Status 200, not 403: the board reloads the page on a 403,
	/// taking it for an expired sign-in.
	static boolean refusePlatform(javax.servlet.http.HttpServletRequest req,
			javax.servlet.http.HttpServletResponse resp, javax.servlet.ServletContext context)
			throws java.io.IOException {
		Access access = forRequest(req, DashboardSettingsStartup.settings(context));
		if (access.seesPlatform(org.vorpal.blade.framework.v2.config.SettingsManager.getDomainName())) {
			return false;
		}
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.getWriter().write("{\"error\":\"no grant shows this domain's servers\",\"denied\":true}");
		return true;
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
		if (seesAllCalls()) {
			return "";
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
}
