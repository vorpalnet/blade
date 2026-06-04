package org.vorpal.blade.applications.files;

/// Smoke-test driver for [FileValidators] — the per-type well-formedness checks
/// run before a save. Same `main()` convention as the framework smoke tests;
/// exits non-zero on the first failed expectation. JSON validation pulls in
/// Jackson, so build the dependency classpath first:
///
/// ```
/// mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
/// java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" \
///   org.vorpal.blade.applications.files.FileValidatorsSmokeTest
/// ```
public class FileValidatorsSmokeTest {

	private static int failures = 0;

	public static void main(String[] args) {
		// XML — well-formed accepted, malformed rejected.
		accept(FileType.XML, "<a><b>x</b></a>", "well-formed xml");
		accept(FileType.XML, "<?xml version=\"1.0\"?><root attr=\"v\"/>", "xml with decl + attr");
		reject(FileType.XML, "<a><b>x</a>", "mismatched tags");
		reject(FileType.XML, "not xml at all <", "garbage");
		reject(FileType.XML, "", "empty is not a document");
		// DOCTYPE is disallowed (XXE hardening) — treated as not acceptable.
		reject(FileType.XML, "<!DOCTYPE a><a/>", "doctype rejected");

		// JSON — plain RFC 8259; a scalar root is valid JSON.
		accept(FileType.JSON, "{\"a\": 1, \"b\": [true, null, \"x\"]}", "json object");
		accept(FileType.JSON, "[1, 2, 3]", "json array root");
		accept(FileType.JSON, "{\"outer\": {\"inner\": {\"deep\": []}}}", "nested json");
		accept(FileType.JSON, "42", "scalar root is valid json");
		reject(FileType.JSON, "{\"a\": 1} junk", "trailing content");
		reject(FileType.JSON, "{\"a\": 1", "unclosed brace");
		reject(FileType.JSON, "{'a': 1}", "single quotes");
		reject(FileType.JSON, "not json", "bare word");
		reject(FileType.JSON, "", "empty is not json");

		// Properties — Properties.load is lenient; a lone bad unicode escape fails.
		accept(FileType.PROPERTIES, "a=1\nb=2\n# comment\n", "normal properties");
		accept(FileType.PROPERTIES, "", "empty properties is valid");
		accept(FileType.PROPERTIES, "key.with.dots = value with spaces", "dotted key");
		reject(FileType.PROPERTIES, "bad=\\uZZZZ", "malformed unicode escape");

		// Text — anything goes.
		accept(FileType.TEXT, "<not> xml \\uZZZZ", "text accepts anything");
		accept(FileType.TEXT, "", "empty text");
		accept(FileType.TEXT, null, "null text treated as empty");

		if (failures > 0) {
			System.err.println(failures + " expectation(s) failed");
			System.exit(1);
		}
		System.out.println("FileValidatorsSmokeTest: all expectations passed");
	}

	private static void accept(FileType type, String content, String desc) {
		String result = FileValidators.validate(type, content);
		if (result != null) {
			failures++;
			System.err.println("FAIL [" + desc + "] expected accept, got reject: " + result);
		}
	}

	private static void reject(FileType type, String content, String desc) {
		String result = FileValidators.validate(type, content);
		if (result == null) {
			failures++;
			System.err.println("FAIL [" + desc + "] expected reject, got accept");
		}
	}
}
