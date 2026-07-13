package org.vorpal.blade.framework.v3.configuration;

/// Smoke-test driver for [UriTidy].
public final class UriTidySmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		// Null / blank / already-clean pass-through
		check("null", null, null);
		check("blank", "   ", "   ");
		check("clean.uri", "sip:carol@host", "sip:carol@host");
		check("clean.bracketed", "<sip:carol@host>", "<sip:carol@host>");
		check("clean.display", "\"Carol\" <sip:carol@host>", "\"Carol\" <sip:carol@host>");
		check("clean.tel", "tel:+15551234567", "tel:+15551234567");
		check("clean.params", "<sip:host;lr>", "<sip:host;lr>");

		// Original template-damage rules
		check("trailing.semi", "sip:carol@host:5060;", "sip:carol@host");
		check("trailing.semi.bracketed", "<sip:carol@host:5060;>", "<sip:carol@host>");
		check("double.semi", "sip:carol@host;;transport=tcp", "sip:carol@host;transport=tcp");
		check("empty.port", "sip:carol@host:;transport=tcp", "sip:carol@host;transport=tcp");
		check("empty.user", "sip:@host", "sip:host");
		check("default.port", "sip:carol@host:5060", "sip:carol@host");
		check("default.port.tls", "sips:carol@host:5061", "sips:carol@host");
		check("nondefault.port", "sip:carol@host:5070", "sip:carol@host:5070");
		check("port.5060.on.sips", "sips:carol@host:5060", "sips:carol@host:5060");
		check("tls.param.port", "sip:carol@host:5061;transport=tls", "sip:carol@host;transport=tls");

		// Missing scheme
		check("scheme.missing", "carol@host", "sip:carol@host");
		check("scheme.missing.port", "carol@host:5060", "sip:carol@host");
		check("scheme.empty.proto", ":carol@host", "sip:carol@host");
		check("scheme.missing.bracketed", "<carol@host>", "<sip:carol@host>");
		check("scheme.missing.display", "\"Carol\" <carol@host>", "\"Carol\" <sip:carol@host>");
		check("scheme.tel.plus", "+15551234567", "tel:+15551234567");
		check("scheme.host.only", "host.example.com", "sip:host.example.com");
		check("scheme.uppercase", "SIP:CAROL@HOST", "SIP:CAROL@HOST");
		check("scheme.ipv6", "carol@[2001:db8::1]:5070", "sip:carol@[2001:db8::1]:5070");

		// Brackets: missing, unbalanced, display name without brackets
		check("bracket.missing.close", "<sip:carol@host", "<sip:carol@host>");
		check("bracket.missing.open", "sip:carol@host>", "<sip:carol@host>");
		check("bracket.display.unbracketed", "\"Carol\" sip:carol@host", "\"Carol\" <sip:carol@host>");
		check("bracket.display.unquoted", "Carol <sip:carol@host>", "\"Carol\" <sip:carol@host>");
		check("bracket.display.multiword", "Carol Smith sip:carol@host", "\"Carol Smith\" <sip:carol@host>");
		check("bracket.display.inside", "<Carol sip:carol@host>", "\"Carol\" <sip:carol@host>");

		// Quotes: unbalanced, single, smart, around the URI itself
		check("quote.unbalanced", "\"Carol <sip:carol@host>", "\"Carol\" <sip:carol@host>");
		check("quote.single", "'Carol' <sip:carol@host>", "\"Carol\" <sip:carol@host>");
		check("quote.smart", "“Carol” <sip:carol@host>", "\"Carol\" <sip:carol@host>");
		check("quote.around.uri", "\"sip:carol@host\"", "sip:carol@host");
		check("quote.escaped", "\"Carol \\\"CC\\\" Smith\" <sip:carol@host>",
				"\"Carol \\\"CC\\\" Smith\" <sip:carol@host>");
		check("quote.apostrophe.user", "sip:o'brien@host", "sip:o'brien@host");

		// Empty ${displayname} template damage
		check("display.empty.quoted", "\"\" <sip:carol@host>", "<sip:carol@host>");
		check("display.empty.space", " <sip:carol@host>", "<sip:carol@host>");

		// Whitespace inside the URI
		check("ws.after.scheme", "sip: carol@host", "sip:carol@host");
		check("ws.around.params", "<sip:carol@host ;transport=tcp>", "<sip:carol@host;transport=tcp>");
		check("ws.everywhere", "  \"Carol\"   <  sip:carol@host  >  ", "\"Carol\" <sip:carol@host>");

		// Everything at once: no scheme, smart quotes, no brackets, default port, trailing ';'
		check("kitchen.sink", "‘Carol Smith’ carol@host:5060;", "\"Carol Smith\" <sip:carol@host>");

		// Unrecoverable garbage returns the input for createURI() to reject
		check("garbage.empty.brackets", "<>", "<>");

		summary();
	}

	private static void check(String name, String input, String expected) {
		String actual = UriTidy.tidy(input);
		boolean ok = (expected == null) ? actual == null : expected.equals(actual);
		if (ok) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name + "  in=[" + input + "]  want=[" + expected + "]  got=[" + actual + "]");
		}
	}

	private static void summary() {
		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}
}
