package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;
import org.vorpal.blade.framework.v3.configuration.selectors.XmlSelector;

/// A caller-written XML body must not pull files or URLs into the document.
class XmlHelperXxeTest {

	@TempDir
	Path dir;

	private String externalEntityBody() throws Exception {
		Path secret = dir.resolve("secret.txt");
		Files.writeString(secret, "TOP-SECRET");
		return "<!DOCTYPE r [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]><r>&x;</r>";
	}

	@Test
	void parseRefusesADoctype() throws Exception {
		String body = externalEntityBody();
		assertThrows(Exception.class, () -> XmlHelper.parse(body));
	}

	@Test
	void parseStillReadsOrdinaryXml() throws Exception {
		assertEquals("hello", XmlHelper.parse("<r><m>hello</m></r>").getDocumentElement().getTextContent());
	}

	@Test
	void selectorStoresNothingFromAnEntityBody() throws Exception {
		XmlSelector selector = new XmlSelector();
		selector.setId("leak");
		selector.setAttribute("/r");

		MemoryContext control = new MemoryContext();
		selector.extract(control, "<r>plain</r>");
		assertEquals("plain", control.get("leak"), "the selector must extract from an ordinary body");

		MemoryContext ctx = new MemoryContext();
		selector.extract(ctx, externalEntityBody());
		assertNull(ctx.get("leak"));
	}
}
