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
import org.vorpal.blade.framework.v3.media.MutingOutputStream;
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
import org.vorpal.blade.framework.v3.security.JwtIdentity;
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
	/// Hear one recording.
	///
	/// A recording whose transcript the recorder redacted is heard muted: the
	/// frames inside each protected span are silenced as the file streams,
	/// from the span times the recognizer's word timing gave. `?verbatim=true`
	/// asks for the audio as stored, which is `phi:unredact`, a second decision
	/// audited on its own, the same as the transcript. A span the recognizer
	/// could not time cannot be muted, and the recording is then refused
	/// rather than played with the value audible.
	@GET
	@Path("{id}/media")
	@Produces("audio/mp4")
	public Response play(@PathParam("id") String id,
			@javax.ws.rs.QueryParam("verbatim") @javax.ws.rs.DefaultValue("false") boolean verbatim) {
		return audio(id, verbatim, DataPermission.PLAY, "recording", null);
	}

	/// Serve the audio behind `permission`, muted unless the caller asked for
	/// and holds `phi:unredact`. `disposition`, when given, makes it a download.
	private Response audio(String id, boolean verbatim, DataPermission permission, String kind, String disposition) {
		SubjectAttributes caller = caller();
		RecordingArchive archive = RecordingArchive.installed();
		if (archive == null) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
		}

		Map<String, String> attributes = attributesOf(archive, id);
		AccessDecision decision = evaluator().evaluate(caller, permission, attributes);
		publish(caller, decision, kind, id);

		if (!decision.isAllowed()) {
			// The reason goes to the audit log, not to the caller. Telling an
			// unauthorized caller which rule refused them, or that the recording
			// exists at all, is itself a disclosure.
			return Response.status(Response.Status.FORBIDDEN).build();
		}
		if (verbatim) {
			AccessDecision reveal = evaluator().evaluate(caller, DataPermission.UNREDACT, attributes);
			publish(caller, reveal, kind + "-verbatim", id);
			if (!reveal.isAllowed()) {
				return Response.status(Response.Status.FORBIDDEN).build();
			}
		}

		final Muting muting;
		try {
			muting = verbatim ? Muting.NONE : mutingFor(id);
		} catch (IOException e) {
			return Response.serverError().entity("{\"error\":\"the transcript could not be read\"}").build();
		}
		if (muting.untimed) {
			return Response.status(Response.Status.CONFLICT)
					.entity("{\"error\":\"a protected span in this recording cannot be muted; verbatim requires phi:unredact\"}")
					.type(MediaType.APPLICATION_JSON).build();
		}

		StreamingOutput body = new StreamingOutput() {
			@Override
			public void write(OutputStream out) throws IOException {
				if (muting.spans.isEmpty()) {
					archive.writeTo(id, out);
					return;
				}
				MutingOutputStream muted = new MutingOutputStream(out, muting.spans, muting.trackOffsetMillis);
				archive.writeTo(id, muted);
				muted.flush();
			}
		};
		Response.ResponseBuilder response = Response.ok(body);
		if (disposition != null) {
			response.header("Content-Disposition", disposition);
		}
		return response.build();
	}

	/// What to silence in a recording: the timed spans of every redacted
	/// transcript's utterances, on the conversation clock, and where the
	/// recording's own zero sits on it.
	private static final class Muting {
		static final Muting NONE = new Muting(new ArrayList<>(), 0L, false);
		final List<long[]> spans;
		final long trackOffsetMillis;
		final boolean untimed;

		Muting(List<long[]> spans, long trackOffsetMillis, boolean untimed) {
			this.spans = spans;
			this.trackOffsetMillis = trackOffsetMillis;
			this.untimed = untimed;
		}
	}

	/// Word timing places a span to the recognizer's token boundary; the pad
	/// covers the boundary itself and the onset the recognizer trims.
	private static final long MUTE_PAD_MILLIS = 200;

	private Muting mutingFor(String id) throws IOException {
		ManifestArchive manifests = ManifestArchive.installed();
		TranscriptArchive transcripts = TranscriptArchive.installed();
		if (manifests == null || transcripts == null) {
			return Muting.NONE;
		}
		ConversationManifest manifest = manifests.get(TranscriptView.conversationOf(id));
		if (manifest == null) {
			return Muting.NONE;
		}
		List<long[]> spans = new ArrayList<>();
		boolean untimed = false;
		for (TranscriptRef ref : manifest.getTranscripts()) {
			if (ref.getRedaction() != TranscriptRef.Redaction.REDACTED || ref.getObject() == null
					|| !ref.getObject().endsWith("/")) {
				continue;
			}
			List<Utterance> utterances = transcripts.read(manifest.getConversation(), ref.getId());
			spans.addAll(MutingOutputStream.spansOf(utterances, MUTE_PAD_MILLIS));
			for (Utterance u : utterances) {
				if (u.getRedactions() == null) {
					continue;
				}
				for (Utterance.Redaction r : u.getRedactions()) {
					if (r.getStartMillis() == null || r.getEndMillis() == null) {
						untimed = true;
					}
				}
			}
		}
		long offset = 0L;
		for (org.vorpal.blade.framework.v3.media.manifest.RecordingTrack track : manifest.getTracks()) {
			if (track.getOffsetMillis() != null) {
				offset = track.getOffsetMillis();
				break;
			}
		}
		return new Muting(spans, offset, untimed);
	}

	/// Read what was said on one recording.
	///
	/// Behind `phi:transcript`, which is not `phi:play`: an analyst can be
	/// given the words without the audio, and a policy that grants one says
	/// nothing about the other. The body is [TranscriptView]: every line with
	/// its bounds and its words on the conversation clock, the model that
	/// produced it, and any correction toward an expected name beside what was
	/// heard. Nothing is composed here; the record is handed over as stored.
	/// Read a transcript.
	///
	/// A transcript the recorder redacted is handed over redacted: protected
	/// spans as their kind in brackets, the words behind them masked, the
	/// recognizer's uncorrected text withheld. `?verbatim=true` asks for the
	/// stored text instead, which is a second permission, `phi:unredact`,
	/// evaluated and audited as its own decision. The two are separate on
	/// purpose: a reviewer holds the words without holding the numbers, and a
	/// request for the numbers is a distinct, countable act.
	@GET
	@Path("{id}/transcript")
	@Produces(MediaType.APPLICATION_JSON)
	public Response transcript(@PathParam("id") String id,
			@javax.ws.rs.QueryParam("verbatim") @javax.ws.rs.DefaultValue("false") boolean verbatim) {
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
		if (verbatim) {
			AccessDecision reveal = evaluator().evaluate(caller, DataPermission.UNREDACT, attributes);
			publish(caller, reveal, "transcript-verbatim", id);
			if (!reveal.isAllowed()) {
				return Response.status(Response.Status.FORBIDDEN).build();
			}
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
			return Response.ok(TranscriptView.of(manifest, utterances, verbatim)).build();
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
	public Response export(@PathParam("id") String id,
			@javax.ws.rs.QueryParam("verbatim") @javax.ws.rs.DefaultValue("false") boolean verbatim) {
		// The copy that leaves is muted the same way the stream is; the
		// verbatim copy is the same second permission.
		return audio(id, verbatim, DataPermission.EXPORT, "recording", "attachment; filename=\"" + id + ".m4a\"");
	}

	/// Find conversations by what a supervisor knows about them and by what
	/// was said.
	///
	/// Every filter is optional and they combine: `from` and `to` bound the
	/// days (UTC), `number` matches a calling or called number by prefix,
	/// `call` names a call, `attr.<name>=<value>` matches an attribute the
	/// recording carried, `kind` names a redaction kind that was found (a card
	/// was taken), `q` is words in the redacted transcript, and `value` is a
	/// protected value spoken on the call, matched by keyed hash, which is a
	/// `phi:unredact` question and is refused without it.
	///
	/// The catalog is a cache of the archive with no policy of its own, so
	/// every candidate is evaluated for `phi:list` against the attributes the
	/// catalog holds for it, the same evaluation the day listing makes, and
	/// only the permitted ones come back. The search is audited once, as a
	/// listing, the way a day listing is, with the query text passed through
	/// the redactor first: an audit record carries no content, and a search
	/// for a phone number is content.
	@GET
	@Path("search")
	@Produces(MediaType.APPLICATION_JSON)
	public Response search(@javax.ws.rs.QueryParam("q") String words, @javax.ws.rs.QueryParam("from") String from,
			@javax.ws.rs.QueryParam("to") String to, @javax.ws.rs.QueryParam("number") String number,
			@javax.ws.rs.QueryParam("call") String call, @javax.ws.rs.QueryParam("kind") List<String> kinds,
			@javax.ws.rs.QueryParam("value") String value, @javax.ws.rs.QueryParam("limit") @javax.ws.rs.DefaultValue("50") int limit,
			@Context javax.ws.rs.core.UriInfo uri) {
		SubjectAttributes caller = caller();
		RecordingsSettings settings = RecordingsServlet.settings();
		String dataSource = (settings == null || settings.getCatalogDataSource() == null) ? "jdbc/BladeAnalytics"
				: settings.getCatalogDataSource();

		CatalogSearch.Query query = new CatalogSearch.Query();
		query.words = words;
		query.number = number;
		query.call = call;
		query.limit = limit;
		try {
			query.from = (from == null || from.isEmpty()) ? null : java.time.LocalDate.parse(from);
			query.to = (to == null || to.isEmpty()) ? null : java.time.LocalDate.parse(to);
		} catch (RuntimeException badDate) {
			return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"from and to are yyyy-MM-dd\"}").build();
		}
		if (kinds != null) {
			for (String k : kinds) {
				if (k != null && !k.isEmpty()) {
					query.kinds.add(k);
				}
			}
		}
		for (Map.Entry<String, List<String>> e : uri.getQueryParameters().entrySet()) {
			if (e.getKey().startsWith("attr.") && !e.getValue().isEmpty()) {
				query.attributes.put(e.getKey().substring(5), e.getValue().get(0));
			}
		}

		// What is audited: the query with any protected value in it withheld.
		// One record for the search, as the day listing does for a day: the
		// act is "who searched for what", and the policy decides per hit below.
		org.vorpal.blade.framework.v3.media.manifest.Redactor redactor = org.vorpal.blade.framework.v3.media.manifest.Redactor.defaults();
		String described = describe(query, redactor);
		publish(caller, AccessDecision.permit(DataPermission.LIST, "listing"), "search", described);
		Map<String, String> searchAttributes = new java.util.LinkedHashMap<>();
		searchAttributes.put("search", described);

		if (value != null && !value.isEmpty()) {
			String key = (settings == null) ? "" : settings.getProtectedValueKey();
			if (key == null || key.isEmpty()) {
				return Response.status(Response.Status.CONFLICT)
						.entity("{\"error\":\"protected-value search is not configured (protectedValueKey)\"}").build();
			}
			AccessDecision reveal = evaluator().evaluate(caller, DataPermission.UNREDACT, searchAttributes);
			publish(caller, reveal, "search-value", "[" + redactor.find(value).stream().findFirst()
					.map(org.vorpal.blade.framework.v3.media.manifest.Utterance.Redaction::getKind).orElse("value") + "]");
			if (!reveal.isAllowed()) {
				return Response.status(Response.Status.FORBIDDEN).build();
			}
			query.protectedDigest = org.vorpal.blade.framework.v3.media.manifest.Redactor.digest(key, value);
		}

		List<CatalogSearch.Hit> hits;
		try {
			hits = CatalogSearch.run(dataSource, query, CatalogSearch.fullText(dataSource));
		} catch (Exception e) {
			Logger log = SettingsManager.getSipLogger();
			if (log != null) {
				log.severe("recordings: the catalog could not be searched: " + e);
			}
			return Response.status(Response.Status.SERVICE_UNAVAILABLE)
					.entity("{\"error\":\"the call catalog is not available\"}").build();
		}

		List<Map<String, Object>> visible = new ArrayList<>();
		for (CatalogSearch.Hit hit : hits) {
			Map<String, String> attributes = new java.util.LinkedHashMap<>(hit.attributes);
			String recordingId = CatalogSearch.recordingIdOf(hit.conversation);
			attributes.put("recordingId", recordingId);
			int dot = recordingId.indexOf('.');
			if (dot > 0) {
				attributes.put("vorpalId", recordingId.substring(0, dot));
			}
			if (!evaluator().evaluate(caller, DataPermission.LIST, attributes).isAllowed()) {
				continue;
			}
			Map<String, Object> row = new java.util.LinkedHashMap<>();
			row.put("id", recordingId);
			row.put("conversation", hit.conversation);
			row.put("call", hit.call);
			row.put("epochUtc", hit.epochUtc);
			row.put("durationMillis", hit.durationMillis);
			row.put("complete", hit.complete);
			row.put("from", hit.from);
			row.put("to", hit.to);
			row.put("utterances", hit.utterances);
			row.put("holds", hit.holds);
			row.put("moves", hit.moves);
			row.put("kinds", hit.kinds);
			row.put("attributes", hit.attributes);
			if (hit.snippet != null) {
				row.put("snippet", hit.snippet);
				row.put("snippetSequence", hit.snippetSequence);
				row.put("snippetMillis", hit.snippetMillis);
			}
			visible.add(row);
		}
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("query", described);
		body.put("candidates", hits.size());
		body.put("hits", visible);
		return Response.ok(body).build();
	}

	/// The query as the audit log may carry it: filters named, words with any
	/// protected value withheld, the protected value itself never present.
	static String describe(CatalogSearch.Query q, org.vorpal.blade.framework.v3.media.manifest.Redactor redactor) {
		StringBuilder d = new StringBuilder();
		if (q.from != null || q.to != null) {
			d.append("days=").append(q.from).append("..").append(q.to).append(' ');
		}
		if (q.number != null && !q.number.isEmpty()) {
			d.append("number=[phone] ");
		}
		if (q.call != null && !q.call.isEmpty()) {
			d.append("call=").append(q.call).append(' ');
		}
		for (Map.Entry<String, String> e : q.attributes.entrySet()) {
			d.append(e.getKey()).append('=').append(e.getValue()).append(' ');
		}
		if (!q.kinds.isEmpty()) {
			d.append("kinds=").append(String.join(",", q.kinds)).append(' ');
		}
		if (q.words != null && !q.words.trim().isEmpty()) {
			String w = q.words.trim();
			d.append("words=\"").append(org.vorpal.blade.framework.v3.media.manifest.Redactor.mask(w, redactor.find(w)))
					.append("\" ");
		}
		return d.toString().trim();
	}

	/// Who is asking: as the token named them when OidcLoginFilter signed them
	/// in, otherwise as the container knows them.
	private SubjectAttributes caller() {
		if (request.getUserPrincipal() instanceof JwtIdentity) {
			return SubjectAttributes.of((JwtIdentity) request.getUserPrincipal());
		}
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
