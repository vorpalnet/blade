package org.vorpal.blade.framework.v3.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


/// The message formats BLADE reads and writes, and the media-type matching that decides which one a
/// receiver gets.
///
/// ## Here, not in the messaging service
///
/// Two kinds of reader need it. A room member is sent a message in a format it takes, which the
/// messaging service decides at delivery. A reader of the archive (a recording review, the catalog)
/// is sent nothing: it reads [StoredMessage]s and writes them in the format it wants, with the same
/// writers, so a stored message reads the same whichever path it took.
///
/// ## One form in the middle
///
/// Every format is read into a message's text ([StoredMessage#getText]) and written back out from
/// it, with the envelope (sender, time, recipient) from the stored message's own fields. So each format costs
/// a reader and a writer, never a converter per pair. A member who takes the sender's own format is
/// sent the body as posted, untouched; only a member who does not is sent a rewrite.
///
/// ## What a rewrite vouches for
///
/// A CPIM body written here names the sender the network asserted, not whatever the posting page
/// put in its own `From`. For a receiver that reads only CPIM, the body becomes as trustworthy as
/// the room's headers ([RoomHeaders]).
///
/// A format not listed here is stored and relayed as posted, only to receivers that take it.
public final class MessageFormats {

	/// A format the room reads and writes.
	public interface Format {
		/// The text of `body`, or null when this body cannot be read as text.
		String read(String body);

		/// `message` written in this format for `recipient`, or for no one in particular (an archive
		/// reader) when `recipient` is null.
		String write(StoredMessage message, String recipient);

		/// The Content-Type of what [#write] produces.
		String contentType();
	}

	public static final String PLAIN = "text/plain";
	public static final String CPIM = "message/cpim";

	private static final Map<String, Format> KNOWN = new LinkedHashMap<>();

	static {
		KNOWN.put(CPIM, new Cpim());
		KNOWN.put(PLAIN, new PlainText());
	}

	private MessageFormats() {
	}

	/// The types the room can write, in the order it prefers them for a wildcard.
	public static Set<String> writable() {
		return Collections.unmodifiableSet(KNOWN.keySet());
	}

	/// The text of a posted body, or null when the room cannot read its format.
	public static String read(String contentType, String body) {
		Format format = KNOWN.get(base(contentType));
		return (format == null || body == null) ? null : format.read(body);
	}

	/// The format for `type`, which must be one of [#writable].
	public static Format of(String type) {
		return KNOWN.get(base(type));
	}

	/// A media type without its parameters, lower case: `text/plain;charset=utf-8` is `text/plain`.
	/// A missing type is `text/plain`, the RFC 3428 default for a MESSAGE.
	public static String base(String type) {
		if (type == null || type.trim().isEmpty()) {
			return PLAIN;
		}
		int semi = type.indexOf(';');
		return ((semi < 0) ? type : type.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
	}

	/// Whether `type` is one of `accepted`, wildcards (`*/*`, `text/*`) included.
	public static boolean accepts(List<String> accepted, String type) {
		String wanted = base(type);
		for (String entry : accepted) {
			String a = base(entry);
			if (a.equals("*/*") || a.equals(wanted)
					|| (a.endsWith("/*") && wanted.startsWith(a.substring(0, a.length() - 1)))) {
				return true;
			}
		}
		return false;
	}

	/// The media types of `Accept` header values, in the order given. Each value may itself be a
	/// comma-separated list; `q` and other parameters are dropped.
	public static List<String> parseAccept(Iterator<String> values) {
		List<String> out = new ArrayList<>();
		while (values != null && values.hasNext()) {
			String value = values.next();
			if (value == null) {
				continue;
			}
			for (String part : value.split(",")) {
				String type = base(part);
				if (type.contains("/") && !out.contains(type)) {
					out.add(type);
				}
			}
		}
		return out;
	}

	/// What to send a member whose accepted types are `accepted`, for a message posted as
	/// `original`: `original` itself when they take it (or have not said), else the first writable
	/// type they take, else null, when there is nothing they can be sent.
	///
	/// @param readable whether the room read the message's text, without which it can write nothing
	public static String choose(List<String> accepted, String original, boolean readable) {
		if (accepted == null || accepted.isEmpty() || accepts(accepted, original)) {
			return original;
		}
		if (!readable) {
			return null;
		}
		for (String entry : accepted) {
			for (String type : KNOWN.keySet()) {
				if (accepts(Collections.singletonList(entry), type)) {
					return type;
				}
			}
		}
		return null;
	}

	/// Plain text: the body is the text.
	public static final class PlainText implements Format {
		@Override
		public String read(String body) {
			return body;
		}

		@Override
		public String write(StoredMessage message, String recipient) {
			return message.getText();
		}

		@Override
		public String contentType() {
			return "text/plain;charset=utf-8";
		}
	}

	/// `message/cpim` (RFC 3862): the message headers, a blank line, the encapsulated part's own
	/// headers, a blank line, its content. Read only when that part is plain text.
	public static final class Cpim implements Format {
		private static final String CRLF = "\r\n";

		@Override
		public String read(String body) {
			String[] message = splitHeaders(body);
			if (message == null) {
				return null;
			}
			String[] part = splitHeaders(message[1]);
			if (part == null) {
				return null;
			}
			String partType = null;
			for (String line : part[0].split("\r?\n")) {
				int colon = line.indexOf(':');
				if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Type")) {
					partType = line.substring(colon + 1).trim();
				}
			}
			return PLAIN.equals(base(partType)) ? part[1] : null;
		}

		@Override
		public String write(StoredMessage message, String recipient) {
			String name = (message.getDisplayName() == null) ? ""
					: message.getDisplayName().replaceAll("[\"\\r\\n]", "").trim();
			String from = "<sip:" + message.getFrom() + ">";
			// The room in the recipient's domain; an archive reader has none, so the sender's.
			String domainOf = (recipient != null) ? recipient : message.getFrom();
			int at = (domainOf == null) ? -1 : domainOf.indexOf('@');
			String to = (message.getTo() != null) ? message.getTo()
					: message.getRoom() + ((at < 0) ? "" : domainOf.substring(at));
			return "From: " + (name.isEmpty() ? from : "\"" + name + "\" " + from) + CRLF
					+ "To: <sip:" + to + ">" + CRLF
					+ "DateTime: " + Instant.ofEpochMilli(message.getAtMillis()) + CRLF
					+ CRLF
					+ "Content-Type: text/plain;charset=utf-8" + CRLF
					+ CRLF
					+ message.getText();
		}

		@Override
		public String contentType() {
			return CPIM;
		}

		/// `text` split at its first blank line into headers and the rest; null when it has none.
		private static String[] splitHeaders(String text) {
			int crlf = text.indexOf("\r\n\r\n");
			int lf = text.indexOf("\n\n");
			if (crlf < 0 && lf < 0) {
				return null;
			}
			boolean useCrlf = crlf >= 0 && (lf < 0 || crlf <= lf);
			int at = useCrlf ? crlf : lf;
			return new String[] { text.substring(0, at), text.substring(at + (useCrlf ? 4 : 2)) };
		}
	}
}
