package org.vorpal.blade.applications.files;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Per-type well-formedness checks run before a file is written. No JAX-RS
/// dependencies, so it can be exercised outside the container — see
/// `FileValidatorsSmokeTest`. JSON validation uses Jackson (already in the WAR
/// via the framework library); the smoke test puts it on the classpath with:
///
/// ```
/// mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
/// java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" \
///   org.vorpal.blade.applications.files.FileValidatorsSmokeTest
/// ```
///
/// These are *well-formedness* checks, not semantic ones: a well-formed
/// `sipserver.xml` can still be wrong for the SIP container. The guarantee is
/// only that the file parses.
public final class FileValidators {

	/// Re-throws so parse() still fails, but emits nothing to stderr.
	private static final ErrorHandler SILENT = new ErrorHandler() {
		public void warning(SAXParseException e) {
		}

		public void error(SAXParseException e) throws SAXParseException {
			throw e;
		}

		public void fatalError(SAXParseException e) throws SAXParseException {
			throw e;
		}
	};

	/// Tolerates nothing beyond plain RFC 8259 JSON — no comments, and
	/// FAIL_ON_TRAILING_TOKENS rejects content after the root value.
	private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	private FileValidators() {
	}

	/// Returns null if the content is acceptable for the type, else a
	/// human-readable rejection reason. TEXT is always accepted.
	public static String validate(FileType type, String content) {
		String body = content == null ? "" : content;
		switch (type) {
		case XML:
			return validateXml(body);
		case JSON:
			return validateJson(body);
		case PROPERTIES:
			return validateProperties(body);
		case TEXT:
		default:
			return null;
		}
	}

	static String validateXml(String content) {
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(false);
			// Harden against XXE — this is only a well-formedness check.
			dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
			dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			dbf.setExpandEntityReferences(false);
			DocumentBuilder db = dbf.newDocumentBuilder();
			// Swallow the parser's default stderr spam — we re-surface the
			// failure via the returned message, so don't also log it.
			db.setErrorHandler(SILENT);
			db.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
			return null;
		} catch (Exception e) {
			return "Not well-formed XML: " + e.getMessage();
		}
	}

	static String validateJson(String content) {
		try {
			JsonNode root = JSON_MAPPER.readTree(content);
			// readTree("") yields MissingNode rather than throwing.
			if (root == null || root.isMissingNode()) {
				return "Not valid JSON: empty content";
			}
			return null;
		} catch (Exception e) {
			return "Not valid JSON: " + e.getMessage();
		}
	}

	static String validateProperties(String content) {
		try {
			new Properties().load(new StringReader(content));
			return null;
		} catch (IllegalArgumentException | IOException e) {
			return "Not valid .properties: " + e.getMessage();
		}
	}
}
