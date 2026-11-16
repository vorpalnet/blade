package org.vorpal.blade.framework.v3.access;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Which calls one group sees in an admin application, and which domains'
/// servers. A viewer's scope is the union of the grants of every group they
/// belong to ([Access#of]); a viewer in no granted group sees nothing.
///
/// A grant decides which calls are shown, never what may be read in them:
/// transcripts, caller numbers and message text are a `phi:transcript`
/// permission in the application's access policy
/// ([org.vorpal.blade.framework.v3.security.AccessPolicy]), checked per call.
/// Scope is a filter the database can apply to thousands of rows; the policy
/// is a rule about one person and one record. Each does the job the other
/// cannot.
///
/// In every list `*` means all. `domains` and `departments` default to all,
/// so a grant narrows by tenant without repeating them. `tenants` and
/// `platform` default to none: seeing calls, and seeing a domain's servers,
/// are each granted on purpose.
public class Grant implements Serializable {

	private static final long serialVersionUID = 1L;

	private String group;
	private List<String> platform = new ArrayList<>();
	private List<String> domains = new ArrayList<>(Arrays.asList(Access.ALL));
	private List<String> tenants = new ArrayList<>();
	private List<String> departments = new ArrayList<>(Arrays.asList(Access.ALL));

	@JsonPropertyDescription("The group this grant is for, as the sign-in reports it: an identity-domain group, a WebLogic group such as Administrators, or a WebLogic role such as Admin.")
	public String getGroup() {
		return group;
	}

	public void setGroup(String group) {
		this.group = group;
	}

	@JsonPropertyDescription("Domains whose servers, operations and data health this group sees. * for all. Empty for none: a tenant's people should not see the load of the tenants beside it.")
	public List<String> getPlatform() {
		return platform;
	}

	public void setPlatform(List<String> platform) {
		this.platform = platform;
	}

	@JsonPropertyDescription("Domains whose calls this group sees, within its tenants. * for all.")
	public List<String> getDomains() {
		return domains;
	}

	public void setDomains(List<String> domains) {
		this.domains = domains;
	}

	@JsonPropertyDescription("Tenants whose calls this group sees. * for all, including calls with no tenant. Empty for none: the group sees no calls.")
	public List<String> getTenants() {
		return tenants;
	}

	public void setTenants(List<String> tenants) {
		this.tenants = tenants;
	}

	@JsonPropertyDescription("Departments within those tenants. * for all. A department sees only its own parts of a call, and a caller's account only on calls it took part in.")
	public List<String> getDepartments() {
		return departments;
	}

	public void setDepartments(List<String> departments) {
		this.departments = departments;
	}
}
