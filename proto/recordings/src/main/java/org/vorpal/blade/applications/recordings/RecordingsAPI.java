package org.vorpal.blade.applications.recordings;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
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
import javax.ws.rs.core.StreamingOutput;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.AccessEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.media.RecordingArchive;
import org.vorpal.blade.framework.v3.security.AccessDecision;
import org.vorpal.blade.framework.v3.security.AccessEvaluator;
import org.vorpal.blade.framework.v3.security.DataPermission;
import org.vorpal.blade.framework.v3.security.RealmSubjectAttributes;
import org.vorpal.blade.framework.v3.security.SubjectAttributes;

/// The review API, and the one place a recording is handed over.
///
/// Every method does the same four things in the same order: work out who is
/// asking, ask [AccessEvaluator] whether they may, record the answer on the
/// event bus, and only then act. That order is the design. An authorization
/// check in four places is enforced in three, and the fourth is the one an
/// auditor finds.
///
/// ## Refusals are recorded as loudly as grants
///
/// A `403` publishes an `AccessEvent` exactly as a `200` does. A log of
/// successes cannot show attempted overreach, which is most of what an access
/// review is looking for, and a run of denials against one recording is the
/// signal such a review exists to find.
///
/// ## Play streams, export hands over
///
/// `phi:play` streams the recording through this application, so the next
/// request is audited too. `phi:export` is a separate permission for the rung
/// where content leaves and stops being auditable at all. That distinction is
/// the whole reason the permission is a ladder rather than a flag.
@Path("recordings")
public class RecordingsAPI {

	@javax.ws.rs.core.Context
	private HttpServletRequest request;

	/// Recordings for one UTC day, `yyyy/MM/dd`.
	///
	/// The listing is filtered by the policy, not filtered in the browser: a
	/// caller is told about the recordings they may know exist and no others.
	/// Returning everything and hiding rows client-side would make the response
	/// itself the disclosure.
	@GET
	@Path("{year}/{month}/{day}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response list(@PathParam("year") String year, @PathParam("month") String month,
			@PathParam("day") String day) {

		SubjectAttributes caller = caller();
		RecordingArchive archive = RecordingArchive.installed();
		if (archive == null) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE)
					.entity("{\"error\":\"no recording archive is configured\"}").build();
		}

		String date = year + "/" + month + "/" + day;
		AccessEvaluator evaluator = evaluator();
		List<Map<String, Object>> visible = new ArrayList<>();

		try {
			for (RecordingArchive.RecordingSummary recording : archive.list(date)) {
				AccessDecision decision =
						evaluator.evaluate(caller, DataPermission.LIST, recording.attributes());
				if (!decision.isAllowed()) {
					continue;
				}
				Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("id", recording.id());
				row.put("bytes", recording.bytes());
				row.put("complete", recording.complete());
				visible.add(row);
			}
		} catch (IOException e) {
			return Response.serverError().entity("{\"error\":\"the archive could not be listed\"}").build();
		}

		// One event for the listing rather than one per row: the act being
		// audited is "who looked at what day", and a row-per-event log of a
		// hundred-call day buries that under its own volume.
		publish(caller, AccessDecision.permit(DataPermission.LIST, "listing"), "recordingDay", date);

		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("day", date);
		body.put("recordings", visible);
		return Response.ok(body).build();
	}

	/// Stream one recording.
	@GET
	@Path("{id}/media")
	@Produces("audio/mp4")
	public Response play(@PathParam("id") String id) {
		SubjectAttributes caller = caller();
		RecordingArchive archive = RecordingArchive.installed();
		if (archive == null) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
		}

		Map<String, String> attributes = attributesOf(archive, id);
		AccessDecision decision = evaluator().evaluate(caller, DataPermission.PLAY, attributes);
		publish(caller, decision, "recording", id);

		if (!decision.isAllowed()) {
			// The reason goes to the audit log, not to the caller. Telling an
			// unauthorized caller which rule refused them, or that the recording
			// exists at all, is itself a disclosure.
			return Response.status(Response.Status.FORBIDDEN).build();
		}

		StreamingOutput body = new StreamingOutput() {
			@Override
			public void write(OutputStream out) throws IOException {
				archive.writeTo(id, out);
			}
		};
		return Response.ok(body).build();
	}

	/// Who is asking, as the container knows them.
	private SubjectAttributes caller() {
		javax.security.auth.Subject subject = null;
		try {
			subject = javax.security.auth.Subject.getSubject(java.security.AccessController.getContext());
		} catch (RuntimeException ignore) {
			// No subject on this thread; the null caller below denies.
		}
		String name = (request.getUserPrincipal() == null) ? null : request.getUserPrincipal().getName();
		return RealmSubjectAttributes.of(subject, name);
	}

	private AccessEvaluator evaluator() {
		RecordingsSettings settings = RecordingsServlet.settings();
		return new AccessEvaluator(settings == null ? null : settings.getAccess());
	}

	/// The record attributes a rule matches on. A recording the archive does not
	/// know about yields none, so every rule with a `match` fails closed.
	private Map<String, String> attributesOf(RecordingArchive archive, String id) {
		Map<String, String> attributes = new java.util.LinkedHashMap<>();
		attributes.put("recordingId", id);
		int dot = id.indexOf('.');
		if (dot > 0) {
			attributes.put("vorpalId", id.substring(0, dot));
		}
		return attributes;
	}

	private void publish(SubjectAttributes caller, AccessDecision decision, String kind, String id) {
		try {
			AccessEvent event = new AccessEvent(caller, decision, kind, id)
					.from(request == null ? null : request.getRemoteAddr());
			EventBus.publish(event.toCloudEvent("/blade/recordings"));
		} catch (Exception e) {
			// An audit record that cannot be published must be visible somewhere.
			// Losing it silently is the one failure this whole path exists to
			// prevent.
			SettingsManager.getSipLogger().severe(
					"recordings: could not publish the access record for " + kind + ":" + id + " - " + e);
		}
	}
}
