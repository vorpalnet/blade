package org.vorpal.blade.services.crud;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipServletMessage;

/// Parses and reassembles MIME multipart SIP message bodies. Per-part headers
/// (Content-ID, Content-Disposition, Content-Length, custom MIME headers) are
/// preserved through the round trip — only the targeted part's body is
/// rewritten.
public class MimeHelper implements Serializable {
	private static final long serialVersionUID = 1L;

	private MimeHelper() {
	}

	/// One section of a multipart body: every header (case-preserving,
	/// insertion-ordered) plus the body text.
	public static class MimePart implements Serializable {
		private static final long serialVersionUID = 1L;
		public Map<String, String> headers = new LinkedHashMap<>();
		public String body;

		public MimePart() {
		}

		public MimePart(String contentType, String body) {
			if (contentType != null) headers.put("Content-Type", contentType);
			this.body = body;
		}

		public String getContentType() {
			for (Map.Entry<String, String> e : headers.entrySet()) {
				if ("content-type".equalsIgnoreCase(e.getKey())) return e.getValue();
			}
			return null;
		}
	}

	public static List<MimePart> parseParts(SipServletMessage msg) throws IOException {
		List<MimePart> parts = new ArrayList<>();
		if (msg.getContent() == null || msg.getContentType() == null) return parts;

		String boundary = extractBoundary(msg.getContentType());
		if (boundary == null) return parts;

		String body = bodyAsString(msg.getContent());
		if (body == null) return parts;

		String delimiter = "--" + boundary;
		String[] sections = body.split(java.util.regex.Pattern.quote(delimiter));

		for (int i = 1; i < sections.length; i++) {
			String section = sections[i];
			if (section.startsWith("--")) break;

			section = stripLeadingNewlines(section);
			section = stripTrailingNewlines(section);
			if (section.isEmpty()) continue;

			int blank = section.indexOf("\r\n\r\n");
			int blankSep = 4;
			if (blank < 0) {
				blank = section.indexOf("\n\n");
				blankSep = 2;
			}

			MimePart part = new MimePart();
			String headerBlock;
			if (blank >= 0) {
				headerBlock = section.substring(0, blank);
				part.body = section.substring(blank + blankSep);
			} else {
				headerBlock = "";
				part.body = section;
			}
			parseHeaders(headerBlock, part.headers);
			parts.add(part);
		}

		return parts;
	}

	public static String getPartBody(SipServletMessage msg, String contentType) throws IOException {
		for (MimePart part : parseParts(msg)) {
			if (matches(part.getContentType(), contentType)) return part.body;
		}
		return null;
	}

	public static void setPartBody(SipServletMessage msg, String contentType, String newBody) throws Exception {
		List<MimePart> parts = parseParts(msg);
		for (MimePart part : parts) {
			if (matches(part.getContentType(), contentType)) {
				part.body = newBody;
				rewrite(msg, parts);
				return;
			}
		}
	}

	/// Adds a new MIME part to the message. If the body is empty, the new
	/// part becomes the entire body. If the body is currently single-part,
	/// it is wrapped in `multipart/mixed` with a fresh boundary and the new
	/// part is appended. If the body is already multipart, the new part is
	/// appended in place.
	public static void addPart(SipServletMessage msg, String contentType, String body) throws Exception {
		if (msg.getContent() == null) {
			msg.setContent(body, contentType);
			return;
		}

		String existingType = msg.getContentType();
		if (existingType != null && existingType.toLowerCase().startsWith("multipart/")) {
			List<MimePart> parts = parseParts(msg);
			parts.add(new MimePart(contentType, body));
			rewrite(msg, parts);
			return;
		}

		String boundary = newBoundary();
		List<MimePart> parts = new ArrayList<>();
		parts.add(new MimePart(existingType, bodyAsString(msg.getContent())));
		parts.add(new MimePart(contentType, body));
		String wrappedType = "multipart/mixed;boundary=" + boundary;
		StringBuilder sb = new StringBuilder();
		for (MimePart part : parts) {
			sb.append("--").append(boundary).append("\r\n");
			for (Map.Entry<String, String> h : part.headers.entrySet()) {
				sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
			}
			sb.append("\r\n");
			sb.append(part.body != null ? part.body : "").append("\r\n");
		}
		sb.append("--").append(boundary).append("--\r\n");
		msg.setContent(sb.toString(), wrappedType);
	}

	public static void removePart(SipServletMessage msg, String contentType) throws Exception {
		List<MimePart> parts = parseParts(msg);
		boolean removed = parts.removeIf(p -> matches(p.getContentType(), contentType));
		if (!removed) return;

		if (parts.isEmpty()) {
			msg.setContent(null, null);
		} else if (parts.size() == 1) {
			MimePart sole = parts.get(0);
			msg.setContent(sole.body, sole.getContentType());
		} else {
			rewrite(msg, parts);
		}
	}

	private static void rewrite(SipServletMessage msg, List<MimePart> parts) throws Exception {
		String boundary = extractBoundary(msg.getContentType());
		StringBuilder sb = new StringBuilder();
		for (MimePart part : parts) {
			sb.append("--").append(boundary).append("\r\n");
			for (Map.Entry<String, String> h : part.headers.entrySet()) {
				sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
			}
			sb.append("\r\n");
			sb.append(part.body != null ? part.body : "").append("\r\n");
		}
		sb.append("--").append(boundary).append("--\r\n");
		msg.setContent(sb.toString(), msg.getContentType());
	}

	private static void parseHeaders(String block, Map<String, String> out) {
		if (block == null || block.isEmpty()) return;
		String currentName = null;
		StringBuilder currentValue = new StringBuilder();
		for (String line : block.split("\\r?\\n")) {
			if (line.isEmpty()) continue;
			if ((line.startsWith(" ") || line.startsWith("\t")) && currentName != null) {
				currentValue.append(' ').append(line.trim());
				continue;
			}
			if (currentName != null) {
				out.put(currentName, currentValue.toString());
				currentValue.setLength(0);
			}
			int colon = line.indexOf(':');
			if (colon < 0) {
				currentName = null;
				continue;
			}
			currentName = line.substring(0, colon).trim();
			currentValue.append(line.substring(colon + 1).trim());
		}
		if (currentName != null) out.put(currentName, currentValue.toString());
	}

	private static boolean matches(String partContentType, String wanted) {
		if (partContentType == null || wanted == null) return false;
		return partContentType.toLowerCase().startsWith(wanted.toLowerCase());
	}

	private static String bodyAsString(Object content) {
		if (content instanceof String) return (String) content;
		if (content instanceof byte[]) return new String((byte[]) content);
		return null;
	}

	private static String stripLeadingNewlines(String s) {
		int i = 0;
		while (i < s.length() && (s.charAt(i) == '\r' || s.charAt(i) == '\n')) i++;
		return s.substring(i);
	}

	private static String stripTrailingNewlines(String s) {
		int i = s.length();
		while (i > 0 && (s.charAt(i - 1) == '\r' || s.charAt(i - 1) == '\n')) i--;
		return s.substring(0, i);
	}

	private static String newBoundary() {
		return "blade-" + Long.toHexString(System.nanoTime()) + Long.toHexString(Double.doubleToLongBits(Math.random()));
	}

	private static String extractBoundary(String contentType) {
		if (contentType == null) return null;
		String lower = contentType.toLowerCase();
		int idx = lower.indexOf("boundary=");
		if (idx < 0) return null;
		String value = contentType.substring(idx + 9).trim();
		if (value.startsWith("\"")) {
			int end = value.indexOf('"', 1);
			return end > 0 ? value.substring(1, end) : null;
		}
		int semi = value.indexOf(';');
		if (semi >= 0) value = value.substring(0, semi).trim();
		return value;
	}
}
