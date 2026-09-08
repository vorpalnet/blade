package org.vorpal.blade.applications.recordings;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import org.vorpal.blade.framework.v3.media.manifest.ConversationManifest;
import org.vorpal.blade.framework.v3.media.manifest.ManifestArchive;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptArchive;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptRef;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;
import org.vorpal.blade.framework.v3.security.AccessDecision;
import org.vorpal.blade.framework.v3.security.AccessEvaluator;
import org.vorpal.blade.framework.v3.security.ContainerSubject;
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

	/// Read what was said on one recording.
	///
	/// Behind `phi:transcript`, which is not `phi:play`: an analyst can be
	/// given the words without the audio, and a policy that grants one says
	/// nothing about the other. The body is [TranscriptView]: every line with
	/// its bounds and its words on the conversation clock, the model that
	/// produced it, and any correction toward an expected name beside what was
	/// heard. Nothing is composed here; the record is handed over as stored.
	@GET
	@Path("{id}/transcript")
	@Produces(MediaType.APPLICATION_JSON)
	public Response transcript(@PathParam("id") String id) {
		SubjectAttributes caller = caller();
		RecordingArchive archive = RecordingArchive.installed();
		ManifestArchive manifests = ManifestArchive.installed();
		TranscriptArchive transcripts = TranscriptArchive.installed();
		if (archive == null || manifests == null || transcripts == null) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE)
					.entity("{\"error\":\"no transcript archive is configured\"}").build();
		}

		Map<String, String> attributes = attributesOf(archive, id);
		AccessDecision decision = evaluator().evaluate(caller, DataPermission.TRANSCRIPT, attributes);
		publish(caller, decision, "transcript", id);

		if (!decision.isAllowed()) {
			return Response.status(Response.Status.FORBIDDEN).build();
		}
		try {
			String conversation = TranscriptView.conversationOf(id);
			ConversationManifest manifest = manifests.get(conversation);
			if (manifest == null) {
				// Recorded, perhaps, but not yet a record: the conversation is
				// still in flight or was never described. Not found is the
				// honest answer, and it discloses nothing the listing did not.
				return Response.status(Response.Status.NOT_FOUND).build();
			}
			Map<String, List<Utterance>> utterances = new java.util.LinkedHashMap<>();
			for (TranscriptRef ref : manifest.getTranscripts()) {
				if (ref.getObject() != null && ref.getObject().endsWith("/")) {
					utterances.put(ref.getId(), transcripts.read(conversation, ref.getId()));
				}
			}
			return Response.ok(TranscriptView.of(manifest, utterances)).build();
		} catch (IOException e) {
			return Response.serverError().entity("{\"error\":\"the transcript could not be read\"}").build();
		}
	}

	/// Hand over a copy of one recording.
	///
	/// Byte-for-byte the same audio [#play] streams, behind a different
	/// permission on purpose. Play is a listen inside this application, where the
	/// next request is audited too. Export is the rung where the file leaves and
	/// every control here stops applying to it, so a policy can grant the first
	/// without granting the second, and most should.
	@GET
	@Path("{id}/export")
	@Produces("audio/mp4")
	public Response export(@PathParam("id") String id) {
		SubjectAttributes caller = caller();
		RecordingArchive archive = RecordingArchive.installed();
		if (archive == null) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
		}

		Map<String, String> attributes = attributesOf(archive, id);
		AccessDecision decision = evaluator().evaluate(caller, DataPermission.EXPORT, attributes);
		publish(caller, decision, "recording", id);

		if (!decision.isAllowed()) {
			return Response.status(Response.Status.FORBIDDEN).build();
		}

		StreamingOutput body = new StreamingOutput() {
			@Override
			public void write(OutputStream out) throws IOException {
				archive.writeTo(id, out);
			}
		};
		return Response.ok(body)
				.header("Content-Disposition", "attachment; filename=\"" + id + ".m4a\"")
				.build();
	}

	/// Who is asking, as the container knows them.
	private SubjectAttributes caller() {
		// ContainerSubject, not Subject.getSubject(AccessController.getContext()).
		// The JAAS lookup returns null on a servlet thread, which reads as "no
		// groups" and silently stops every group rule from matching. See that
		// class for why.
		javax.security.auth.Subject subject = ContainerSubject.current();
		String name = (request.getUserPrincipal() == null) ? null : request.getUserPrincipal().getName();
		SubjectAttributes attributes = RealmSubjectAttributes.of(subject, name);
		Logger logger = SettingsManager.getSipLogger();
		if (logger != null && logger.isLoggable(Level.FINE)) {
			logger.fine("recordings: caller " + attributes + " via " + ContainerSubject.source());
		}
		return attributes;
	}

	private AccessEvaluator evaluator() {
		RecordingsSettings settings = RecordingsServlet.settings();
		return new AccessEvaluator(settings == null ? null : settings.getAccess());
	}

	/// The record attributes a rule matches on. A recording the archive does not
	/// know about yields none, so every rule with a `match` fails closed.
	///
	/// The archive is asked rather than the identifier parsed. A rule turns on
	/// facts like `department` or `queue`, which are stored beside the recording
	/// when it starts; the identifier carries a call correlator and a timestamp
	/// and nothing a policy would name. Deriving attributes from the id was
	/// exactly enough to make `${subject.name}` rules work and to make every rule
	/// naming a business attribute silently match nothing.
	private Map<String, String> attributesOf(RecordingArchive archive, String id) {
		Map<String, String> attributes = new java.util.LinkedHashMap<>();
		attributes.put("recordingId", id);
		int dot = id.indexOf('.');
		if (dot > 0) {
			attributes.put("vorpalId", id.substring(0, dot));
		}
		try {
			attributes.putAll(archive.attributes(id));
		} catch (IOException unreadable) {
			// Drop the id-derived attributes too, rather than decide on half the
			// facts. Keeping them would let a rule match on a subset of the
			// record, which is how a caller reaches a recording whose department
			// they were never granted. What remains reachable is a rule naming no
			// attribute at all, which is the compliance path and is meant to be.
			SettingsManager.getSipLogger().warning("recordings: the attributes of " + id
					+ " could not be read, so only a rule with an empty match can reach it: " + unreadable);
			return java.util.Collections.emptyMap();
		}
		return attributes;
	}

	private void publish(SubjectAttributes caller, AccessDecision decision, String kind, String id) {
		try {
			AccessEvent event = new AccessEvent(caller, decision, kind, id)
					.from(request == null ? null : request.getRemoteAddr());
			org.vorpal.blade.framework.v3.events.CloudEvent envelope = event.toCloudEvent("/blade/recordings");

			// Diagnostic, kept deliberately. An access record that is published
			// into nothing is the failure this whole path exists to prevent, and
			// it is invisible: EventBus.publish returns normally when no publisher
			// is installed, so silence here looks exactly like success. This says
			// which it was.
			java.util.logging.Logger diag = java.util.logging.Logger.getLogger(RecordingsAPI.class.getName());
			diag.info("recordings: publishing " + envelope.getType() + " ready=" + EventBus.isReady()
					+ " destinations=" + EventBus.registeredDestinations());

			EventBus.publish(envelope);
		} catch (Exception e) {
			// An audit record that cannot be published must be visible somewhere.
			// Losing it silently is the one failure this whole path exists to
			// prevent.
			SettingsManager.getSipLogger().severe(
					"recordings: could not publish the access record for " + kind + ":" + id + " - " + e);
		}
	}
}
