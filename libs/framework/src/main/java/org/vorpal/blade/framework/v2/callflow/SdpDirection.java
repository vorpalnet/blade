package org.vorpal.blade.framework.v2.callflow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.activation.DataHandler;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.vorpal.blade.framework.v2.sdp.Sdp;

/// Rewrite every `m=` section's direction attribute to a target value
/// (`inactive`, `sendrecv`, `sendonly`, `recvonly`). Used by [CallflowHold]
/// to answer locally and by [AbstractCallflow3PCC] to rewrite a peer-leg
/// offer mid-flow.
///
/// Handles `application/sdp` and `multipart/*` bodies; the multipart path
/// preserves non-SDP parts (e.g., SIPREC `application/rs-metadata+xml`)
/// untouched and round-trips through JavaMail so the response Content-Type's
/// boundary matches the body bytes.
final class SdpDirection {

	private SdpDirection() {}

	/// Parse an SDP body, force every m-line to the supplied direction, return
	/// the re-serialized SDP.
	static String force(String sdpText, String direction) {
		Sdp sdp = Sdp.parse(sdpText);
		if (sdp.getMedia() != null) {
			for (Sdp.Media m : sdp.getMedia()) {
				List<Sdp.Attribute> attrs = m.getAttributes();
				if (attrs == null) {
					attrs = new ArrayList<>();
					m.setAttributes(attrs);
				} else {
					attrs.removeIf(a -> isDirectionAttribute(a.getName()));
				}
				attrs.add(new Sdp.Attribute(direction, null));
			}
		}
		return sdp.toString();
	}

	/// Parse a multipart body, apply [#force] to every `application/sdp` part,
	/// repackage. The returned content-type pairs with the boundary writeTo
	/// just emitted — use it on the response.
	static MultipartResult forceMultipart(byte[] body, String contentType, String direction)
			throws MessagingException, IOException {
		MimeMultipart mp = new MimeMultipart(new ByteArrayDataSource(body, contentType));
		for (int i = 0; i < mp.getCount(); i++) {
			MimeBodyPart part = (MimeBodyPart) mp.getBodyPart(i);
			String partType = part.getContentType();
			if (partType != null && partType.toLowerCase().startsWith("application/sdp")) {
				String rewritten = force(readPart(part), direction);
				part.setDataHandler(new DataHandler(new ByteArrayDataSource(rewritten.getBytes(), partType)));
			}
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		mp.writeTo(baos);
		return new MultipartResult(baos.toByteArray(), mp.getContentType());
	}

	private static boolean isDirectionAttribute(String name) {
		return "sendrecv".equalsIgnoreCase(name)
				|| "sendonly".equalsIgnoreCase(name)
				|| "recvonly".equalsIgnoreCase(name)
				|| "inactive".equalsIgnoreCase(name);
	}

	private static String readPart(MimeBodyPart part) throws IOException, MessagingException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (InputStream is = part.getInputStream()) {
			byte[] buf = new byte[4096];
			int n;
			while ((n = is.read(buf)) > 0) {
				baos.write(buf, 0, n);
			}
		}
		return baos.toString("UTF-8");
	}

	static final class MultipartResult {
		final byte[] body;
		final String contentType;

		MultipartResult(byte[] body, String contentType) {
			this.body = body;
			this.contentType = contentType;
		}
	}
}
