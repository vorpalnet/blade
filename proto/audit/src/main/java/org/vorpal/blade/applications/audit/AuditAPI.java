package org.vorpal.blade.applications.audit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.AccessEvent;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.security.AccessDecision;
import org.vorpal.blade.framework.v3.security.AccessEvaluator;
import org.vorpal.blade.framework.v3.security.AuditArchive;
import org.vorpal.blade.framework.v3.security.ContainerSubject;
import org.vorpal.blade.framework.v3.security.DataPermission;
import org.vorpal.blade.framework.v3.security.JwtIdentity;
import org.vorpal.blade.framework.v3.security.RealmSubjectAttributes;
import org.vorpal.blade.framework.v3.security.SubjectAttributes;

/// Reading the access log, behind `phi:audit`.
///
/// Storing access records is the recording half of an audit control. This is the
/// examining half, and without it the trail is a filing cabinet nobody has a key
/// to.
///
/// ## Reading the log is itself an access
///
/// Every read publishes its own access record, permitted or refused. Two reasons,
/// and the second is the one that matters.
///
/// The first is symmetry: `phi:audit` is a permission like any other and the
/// application asks the same evaluator in the same order.
///
/// The second is that the audit log is the most sensitive thing here. It names
/// who reached for what, so a person trying to find out whether their own
/// activity was noticed reads *this*, not the recordings. A trail that records
/// every access except accesses to itself has a hole in exactly the shape of
/// someone covering their tracks.
///
/// That record is written by the same sink, so it is subject to the same
/// retention rule and cannot be removed by whoever read the log.
///
/// ## Who holds the permission
///
/// `phi:audit` belongs to the people who audit and deliberately not to the
/// people being audited. An access log its subjects can read is a map of what
/// they got away with. Nothing here enforces that beyond the policy: it is the
/// operator's rule to write, and it is worth writing carefully.
@Path("audit")
public class AuditAPI {

	@Context
	private HttpServletRequest request;

	/// Access records for one UTC day, oldest first.
	@GET
	@Path("{year}/{month}/{day}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response list(@PathParam("year") String year, @PathParam("month") String month,
			@PathParam("day") String day) {

		SubjectAttributes caller = caller();
		String date = year + "/" + month + "/" + day;

		Map<String, String> record = new LinkedHashMap<>();
		record.put("auditDay", date);

		AccessDecision decision = evaluator().evaluate(caller, DataPermission.AUDIT, record);
		publish(caller, decision, date);

		if (!decision.isAllowed()) {
			// No reason to the caller, as everywhere else. Telling somebody which
			// rule refused them is a hint about how to satisfy it.
			return Response.status(Response.Status.FORBIDDEN).build();
		}

		AuditArchive archive = AuditArchive.installed();
		if (archive == null) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE)
					.entity("{\"error\":\"no audit archive is configured\"}").build();
		}

		try {
			List<Map<String, Object>> rows = new ArrayList<>();
			for (CloudEvent event : archive.list(date)) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("time", event.getTime());
				row.put("type", event.getType());
				row.put("source", event.getSource());
				row.put("subject", event.getSubject());
				row.put("id", event.getId());
				rows.add(row);
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("day", date);
			body.put("records", rows);
			return Response.ok(body).build();
		} catch (IOException unreadable) {
			// Deliberately not a partial listing. See AuditArchive: a short audit
			// listing that looks complete is the failure a reviewer cannot detect.
			return Response.serverError()
					.entity("{\"error\":\"the audit trail for " + date + " could not be read in full\"}").build();
		}
	}

	private SubjectAttributes caller() {
		if (request.getUserPrincipal() instanceof JwtIdentity) {
			return SubjectAttributes.of((JwtIdentity) request.getUserPrincipal()); // signed in by OidcLoginFilter
		}
		String name = (request.getUserPrincipal() == null) ? null : request.getUserPrincipal().getName();
		return RealmSubjectAttributes.of(ContainerSubject.current(), name);
	}

	private AccessEvaluator evaluator() {
		AuditSettings settings = AuditSubscription.settings();
		return new AccessEvaluator(settings == null ? null : settings.getAccess());
	}

	private void publish(SubjectAttributes caller, AccessDecision decision, String date) {
		try {
			AccessEvent event = new AccessEvent(caller, decision, "auditDay", date)
					.from(request == null ? null : request.getRemoteAddr());
			EventBus.publish(event.toCloudEvent("/blade/audit"));
		} catch (Exception e) {
			java.util.logging.Logger.getLogger(AuditAPI.class.getName())
					.severe("audit: could not record that " + date + " was read: " + e);
		}
	}
}
