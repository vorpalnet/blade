package org.vorpal.blade.framework.v3.events;

import java.io.Serializable;

import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.Callflow;

/// Who may see a call: its tenant and its department, as the call carries them
/// on X-Vorpal-ID at the moment an event is published (see
/// [Callflow#setTenant]). Every call-scoped event is stamped with it, so a
/// reader can be shown only the calls, and only the parts of a call, that
/// belong to them.
///
/// Taken when the event is built, not when it is sent: a department that
/// changes mid-call changes the events after it, never the ones before.
public final class CallScope implements Serializable {

	private static final long serialVersionUID = 1L;

	/// No scope: the event carries neither field, and a reader falls back to
	/// the publishing deployment's `blade.tenant`.
	public static final CallScope NONE = new CallScope(null, null);

	private final String tenant;
	private final String department;

	/// @param tenant     the tenant, or null
	/// @param department the department, or null
	/// @throws IllegalArgumentException if either is not a name (see
	///                                  [Callflow#isScopeName])
	public CallScope(String tenant, String department) {
		if ((tenant != null && !Callflow.isScopeName(tenant))
				|| (department != null && !Callflow.isScopeName(department))) {
			throw new IllegalArgumentException("not a tenant or department name: " + tenant + ", " + department);
		}
		this.tenant = tenant;
		this.department = department;
	}

	/// The call's scope as its application session holds it now.
	///
	/// @param app the call's application session; null gives [#NONE]
	public static CallScope of(SipApplicationSession app) {
		if (app == null) {
			return NONE;
		}
		String tenant = Callflow.getTenant(app);
		String department = Callflow.getDepartment(app);
		return (tenant == null && department == null) ? NONE : new CallScope(tenant, department);
	}

	public String getTenant() {
		return tenant;
	}

	public String getDepartment() {
		return department;
	}

	@Override
	public String toString() {
		return "CallScope[" + tenant + "/" + department + "]";
	}
}
