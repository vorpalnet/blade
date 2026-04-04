package org.vorpal.blade.services.crud;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.sip.SipServletMessage;

/**
 * Utility for parsing and reassembling MIME multipart SIP message bodies.
 * Uses simple boundary-based parsing (no javax.mail dependency).
 */
public class MimeHelper implements Serializable {
	private static final long serialVersionUID = 1L;

	private MimeHelper() {
	}

	/**
	 * A single part of a MIME multipart message.
	 */
	public static class MimePart implements Serializable {
		private static final long serialVersionUID = 1L;
		public String contentType;
		public String body;

		public MimePart(String contentType, String body) {
			this.contentType = contentType;
			this.body = body;
		}
	}

	/**
	 * Parses a multipart message body into individual parts.
	 */
	public static List<MimePart> parseParts(SipServletMessage msg) throws IOException {
		List<MimePart> parts = new ArrayList<>();
		if (msg.getContent() == null || msg.getContentType() == null) {
			return parts;
		}

		String msgContentType = msg.getContentType();
		String boundary = extractBoundary(msgContentType);
		if (boundary == null) {
			return parts;
		}

		String body;
		Object content = msg.getContent();
		if (content instanceof String) {
			body = (String) content;
		} else if (content instanceof byte[]) {
			body = new String((byte[]) content);
		} else {
			return parts;
		}

		String delimiter = "--" + boundary;
		String[] sections = body.split(delimiter);

		for (int i = 1; i < sections.length; i++) {
			String section = sections[i];
			if (section.startsWith("--")) {
				break; // closing delimiter
			}

			section = section.trim();
			if (section.isEmpty()) {
				continue;
			}

			// Split headers from body at first blank line
			int blankLine = section.indexOf("\r\n\r\n");
			if (blankLine < 0) {
				blankLine = section.indexOf("\n\n");
			}

			String partHeaders;
			String partBody;
			if (blankLine >= 0) {
				partHeaders = section.substring(0, blankLine);
				partBody = section.substring(blankLine).trim();
			} else {
				partHeaders = "";
				partBody = section;
			}

			String partContentType = extractPartContentType(partHeaders);
			parts.add(new MimePart(partContentType, partBody));
		}

		return parts;
	}

	/**
	 * Gets the body text of the first MIME part matching the given content type.
	 */
	public static String getPartBody(SipServletMessage msg, String contentType) throws IOException {
		List<MimePart> parts = parseParts(msg);
		for (MimePart part : parts) {
			if (part.contentType != null && part.contentType.startsWith(contentType)) {
				return part.body;
			}
		}
		return null;
	}

	/**
	 * Replaces the body of the first MIME part matching the given content type
	 * and writes the reassembled multipart back to the message.
	 */
	public static void setPartBody(SipServletMessage msg, String contentType, String newBody) throws Exception {
		List<MimePart> parts = parseParts(msg);
		boolean found = false;
		for (MimePart part : parts) {
			if (part.contentType != null && part.contentType.startsWith(contentType)) {
				part.body = newBody;
				found = true;
				break;
			}
		}

		if (found) {
			String boundary = extractBoundary(msg.getContentType());
			String reassembled = reassemble(parts, boundary);
			msg.setContent(reassembled, msg.getContentType());
		}
	}

	/**
	 * Removes the first MIME part matching the given content type
	 * and writes the reassembled multipart back to the message.
	 */
	public static void removePart(SipServletMessage msg, String contentType) throws Exception {
		List<MimePart> parts = parseParts(msg);
		boolean removed = parts.removeIf(
				part -> part.contentType != null && part.contentType.startsWith(contentType));

		if (removed) {
			String boundary = extractBoundary(msg.getContentType());
			if (parts.size() == 1) {
				// Only one part left — unwrap from multipart
				msg.setContent(parts.get(0).body, parts.get(0).contentType);
			} else if (parts.isEmpty()) {
				msg.setContent(null, null);
			} else {
				String reassembled = reassemble(parts, boundary);
				msg.setContent(reassembled, msg.getContentType());
			}
		}
	}

	/**
	 * Reassembles MIME parts into a multipart body string.
	 */
	public static String reassemble(List<MimePart> parts, String boundary) {
		StringBuilder sb = new StringBuilder();
		for (MimePart part : parts) {
			sb.append("--").append(boundary).append("\r\n");
			if (part.contentType != null) {
				sb.append("Content-Type: ").append(part.contentType).append("\r\n");
			}
			sb.append("\r\n");
			sb.append(part.body).append("\r\n");
		}
		sb.append("--").append(boundary).append("--\r\n");
		return sb.toString();
	}

	private static String extractBoundary(String contentType) {
		if (contentType == null) {
			return null;
		}
		String lower = contentType.toLowerCase();
		int idx = lower.indexOf("boundary=");
		if (idx < 0) {
			return null;
		}
		String boundary = contentType.substring(idx + 9).trim();
		// Remove quotes if present
		if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
			boundary = boundary.substring(1, boundary.length() - 1);
		}
		// Remove any trailing parameters
		int semi = boundary.indexOf(';');
		if (semi >= 0) {
			boundary = boundary.substring(0, semi).trim();
		}
		return boundary;
	}

	private static String extractPartContentType(String partHeaders) {
		if (partHeaders == null || partHeaders.isEmpty()) {
			return null;
		}
		for (String line : partHeaders.split("\\r?\\n")) {
			if (line.toLowerCase().startsWith("content-type:")) {
				return line.substring("content-type:".length()).trim();
			}
		}
		return null;
	}
}
