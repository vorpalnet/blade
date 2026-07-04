package org.vorpal.blade.framework.v3.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.activation.DataHandler;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.vorpal.blade.framework.v2.sdp.Sdp;

/// Pure SDP direction/answer helpers for the v3 media callflows — everything
/// here is static, container-free, and unit-tested ([SdpMediaSmokeTest]).
///
/// This is the RFC 3264 replacement for the v2 blackhole style: directions are
/// expressed with `a=sendrecv/sendonly/recvonly/inactive` on real addresses.
/// `c=` lines are never zeroed — `0.0.0.0` is the RFC 2543 hold convention
/// that RFC 3264 §8.4 keeps only for backward-compat *receiving*, and it
/// breaks RTCP and ICE middleboxes when sent.
public final class SdpMedia {

	private SdpMedia() {
	}

	// ============================================================ directions

	/// Force every m-line to `direction`. Session-level direction attributes
	/// are REMOVED (not just overridden — a stale session-level `a=sendonly`
	/// contradicting the media lines is exactly the sloppiness this replaces);
	/// each m-line's direction attribute is replaced.
	public static void forceDirection(Sdp sdp, MediaDirection direction) {
		if (sdp.getAttributes() != null) {
			sdp.getAttributes().removeIf(a -> MediaDirection.parse(a.getName()) != null);
		}
		if (sdp.getMedia() != null) {
			for (Sdp.Media m : sdp.getMedia()) {
				setMediaDirection(m, direction);
			}
		}
	}

	/// The EFFECTIVE direction of each m-line, in order: the media-level
	/// attribute if present, else the session-level one, else the RFC 3264
	/// default `sendrecv`.
	public static List<MediaDirection> captureDirections(Sdp sdp) {
		MediaDirection sessionLevel = directionOf(sdp.getAttributes(), MediaDirection.SENDRECV);
		List<MediaDirection> out = new ArrayList<>();
		if (sdp.getMedia() != null) {
			for (Sdp.Media m : sdp.getMedia()) {
				out.add(directionOf(m.getAttributes(), sessionLevel));
			}
		}
		return out;
	}

	/// Set each m-line's direction from `directions` (same order). Session-level
	/// direction attributes are removed. Returns false without touching the SDP
	/// when the m-line count doesn't match — the caller falls back to a blanket
	/// [#forceDirection].
	public static boolean applyDirections(Sdp sdp, List<MediaDirection> directions) {
		List<Sdp.Media> media = sdp.getMedia();
		if (media == null || directions == null || media.size() != directions.size()) {
			return false;
		}
		if (sdp.getAttributes() != null) {
			sdp.getAttributes().removeIf(a -> MediaDirection.parse(a.getName()) != null);
		}
		for (int i = 0; i < media.size(); i++) {
			setMediaDirection(media.get(i), directions.get(i));
		}
		return true;
	}

	private static void setMediaDirection(Sdp.Media m, MediaDirection direction) {
		List<Sdp.Attribute> attrs = m.getAttributes();
		if (attrs == null) {
			attrs = new ArrayList<>();
			m.setAttributes(attrs);
		} else {
			attrs.removeIf(a -> MediaDirection.parse(a.getName()) != null);
		}
		attrs.add(new Sdp.Attribute(direction.sdp(), null));
	}

	private static MediaDirection directionOf(List<Sdp.Attribute> attrs, MediaDirection dflt) {
		if (attrs != null) {
			for (Sdp.Attribute a : attrs) {
				MediaDirection d = MediaDirection.parse(a.getName());
				if (d != null) {
					return d;
				}
			}
		}
		return dflt;
	}

	// ================================================================ answer

	/// The discard port (RFC 863) — a real, non-zero port for streams we
	/// answer but never read. Port 0 would REJECT the stream (RFC 3264 §6),
	/// and a rejected stream can't be revived without new m-lines; hold needs
	/// "paused, recoverable".
	public static final int DISCARD_PORT = 9;

	/// Build OUR OWN inactive answer to an offer — the RFC 3264 replacement
	/// for echoing the caller's SDP back with a zeroed `c=`. Per offered
	/// m-line: same type/protocol/formats (with the offer's `rtpmap`/`fmtp`
	/// lines, so the format list stays self-describing), `a=inactive` (a legal
	/// answer to ANY offered direction), and OUR address with the discard
	/// port. An m-line the offer itself disabled (port 0) stays port 0, as §6
	/// requires. The `o=` line is ours — username `-`, the caller's session id
	/// NEVER leaks into it — with `sessionId`/`version` supplied by the caller
	/// so repeated answers on one dialog keep the id stable and bump the
	/// version (RFC 3264 §8).
	public static Sdp buildInactiveAnswer(Sdp offer, String localAddr, String sessionId, long version) {
		String addr = (localAddr == null || localAddr.isEmpty()) ? "127.0.0.1" : localAddr;
		String addrType = addr.indexOf(':') >= 0 ? "IP6" : "IP4";

		Sdp answer = new Sdp();
		answer.setVersion("0");
		Sdp.Origin origin = new Sdp.Origin();
		origin.setUsername("-");
		origin.setSessionId(sessionId);
		origin.setSessionVersion(String.valueOf(version));
		origin.setNetType("IN");
		origin.setAddressType(addrType);
		origin.setAddress(addr);
		answer.setOrigin(origin);
		answer.setSessionName("-");
		answer.setConnection(new Sdp.Connection("IN", addrType, addr));

		List<Sdp.Media> media = new ArrayList<>();
		if (offer.getMedia() != null) {
			for (Sdp.Media offered : offer.getMedia()) {
				Sdp.Media m = new Sdp.Media();
				m.setType(offered.getType());
				m.setPort(offered.getPort() == 0 ? 0 : DISCARD_PORT);
				m.setProtocol(offered.getProtocol());
				m.setFormats(new ArrayList<>(offered.getFormats() != null ? offered.getFormats() : List.of()));
				List<Sdp.Attribute> attrs = new ArrayList<>();
				if (offered.getAttributes() != null) {
					for (Sdp.Attribute a : offered.getAttributes()) {
						// keep only the format descriptions; everything else in
						// the offer (candidates, rtcp, ssrc, ...) describes the
						// OFFERER's transport and must not appear in our answer
						if ("rtpmap".equalsIgnoreCase(a.getName()) || "fmtp".equalsIgnoreCase(a.getName())) {
							attrs.add(new Sdp.Attribute(a.getName(), a.getValue()));
						}
					}
				}
				attrs.add(new Sdp.Attribute(MediaDirection.INACTIVE.sdp(), null));
				m.setAttributes(attrs);
				media.add(m);
			}
		}
		answer.setMedia(media);
		return answer;
	}

	// ============================================================== bodies

	/// Extract the SDP text from a message body: `application/sdp` directly,
	/// or the first `application/sdp` part of a `multipart/*` (e.g. SIPREC
	/// SDP + `rs-metadata`). Returns null when there is none.
	public static String extractSdp(byte[] body, String contentType) throws IOException, MessagingException {
		if (body == null || body.length == 0 || contentType == null) {
			return null;
		}
		String ct = contentType.toLowerCase();
		if (ct.startsWith("application/sdp")) {
			return new String(body, StandardCharsets.UTF_8);
		}
		if (ct.startsWith("multipart/")) {
			MimeMultipart mp = new MimeMultipart(new ByteArrayDataSource(body, contentType));
			for (int i = 0; i < mp.getCount(); i++) {
				MimeBodyPart part = (MimeBodyPart) mp.getBodyPart(i);
				String partType = part.getContentType();
				if (partType != null && partType.toLowerCase().startsWith("application/sdp")) {
					return readPart(part);
				}
			}
		}
		return null;
	}

	/// Rewrite every `application/sdp` part of a multipart body with
	/// `rewriter`, preserving the other parts (SIPREC metadata etc.). The
	/// returned content type carries the boundary the rewrite just emitted —
	/// use it on the outgoing message.
	public static MultipartResult rewriteMultipart(byte[] body, String contentType, SdpRewriter rewriter)
			throws MessagingException, IOException {
		MimeMultipart mp = new MimeMultipart(new ByteArrayDataSource(body, contentType));
		for (int i = 0; i < mp.getCount(); i++) {
			MimeBodyPart part = (MimeBodyPart) mp.getBodyPart(i);
			String partType = part.getContentType();
			if (partType != null && partType.toLowerCase().startsWith("application/sdp")) {
				Sdp sdp = Sdp.parse(readPart(part));
				rewriter.rewrite(sdp);
				part.setDataHandler(new DataHandler(
						new ByteArrayDataSource(sdp.toString().getBytes(StandardCharsets.UTF_8), partType)));
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		mp.writeTo(out);
		return new MultipartResult(out.toByteArray(), mp.getContentType());
	}

	@FunctionalInterface
	public interface SdpRewriter {
		void rewrite(Sdp sdp);
	}

	public static final class MultipartResult {
		public final byte[] body;
		public final String contentType;

		MultipartResult(byte[] body, String contentType) {
			this.body = body;
			this.contentType = contentType;
		}
	}

	private static String readPart(MimeBodyPart part) throws IOException, MessagingException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (InputStream in = part.getInputStream()) {
			byte[] buf = new byte[4096];
			for (int n; (n = in.read(buf)) > 0;) {
				out.write(buf, 0, n);
			}
		}
		return out.toString("UTF-8");
	}
}
