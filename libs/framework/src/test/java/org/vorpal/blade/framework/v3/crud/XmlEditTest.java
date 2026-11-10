package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/// An XPath edit changes only what it selects: the declaration and the
/// layout of everything else survive the parse and re-serialization.
class XmlEditTest {

	private static String delete(String xml, String xpath) throws Exception {
		org.w3c.dom.Document doc = XmlHelper.parse(xml);
		XmlHelper.removeNodes(doc, xpath);
		return XmlHelper.serialize(xml, doc);
	}

	@Test
	void theDeclarationIsKeptAsItArrived() throws Exception {
		String xml = "<?xml version='1.0' encoding='UTF-8'?>\r\n<a><b/><c/></a>";
		assertEquals("<?xml version='1.0' encoding='UTF-8'?>\r\n<a><c/></a>", delete(xml, "//b"));
	}

	@Test
	void aBodyWithoutADeclarationGetsNone() throws Exception {
		assertEquals("<a><c/></a>", delete("<a><b/><c/></a>", "//b"));
	}

	@Test
	void crlfLineBreaksStayCrlf() throws Exception {
		String xml = "<a>\r\n\t<b/>\r\n\t<c/>\r\n</a>";
		assertEquals("<a>\r\n\t<c/>\r\n</a>", delete(xml, "//b"));
	}

	@Test
	void aRemovedElementTakesItsIndentation() throws Exception {
		String xml = "<a>\n\t<b/>\n\t<c/>\n\t<d/>\n</a>";
		assertEquals("<a>\n\t<b/>\n\t<d/>\n</a>", delete(xml, "//c"));
	}
}
