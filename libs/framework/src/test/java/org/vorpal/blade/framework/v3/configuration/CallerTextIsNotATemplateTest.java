package org.vorpal.blade.framework.v3.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.connectors.TableConnector;
import org.vorpal.blade.framework.v3.configuration.selectors.AttributeSelector;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;
import org.vorpal.blade.framework.v3.configuration.selectors.TableSelector;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

/// Text taken from a call must never be expanded as a template: a `${...}` in a
/// caller's From header would otherwise reach the environment-variable and
/// system-property fallback. `user.home` stands in for a secret; it is set in
/// every JVM.
class CallerTextIsNotATemplateTest {

	private static final String HOME = System.getProperty("user.home");
	private static final String FROM = "\"${user.home}\" <sip:+12025550150@carrier.example.com>;tag=1";

	@Test
	void storedHeaderStaysLiteral() {
		MemoryContext ctx = new MemoryContext();
		new AttributeSelector("from", "From").extract(ctx, Collections.singletonMap("From", FROM));
		assertEquals(FROM, ctx.get("from"));
	}

	@Test
	void regexGroupStaysLiteral() {
		MemoryContext ctx = new MemoryContext();
		new RegexSelector("callerName", "From", "\"(?<name>[^\"]*)\".*", "${name}")
				.extract(ctx, Collections.singletonMap("From", FROM));
		assertEquals("${user.home}", ctx.get("callerName"));
		assertEquals("${user.home}", ctx.get("callerName.name"));
	}

	@Test
	void templateDoesNotRescanWhatItInserted() {
		MemoryContext ctx = new MemoryContext();
		ctx.put("callerName", "${user.home}");
		assertEquals("X-Caller: ${user.home}", ctx.resolve("X-Caller: ${callerName}"));

		Map<String, String> vars = new HashMap<>();
		vars.put("callerName", "${user.home}");
		assertEquals("${user.home}", Context.substitute("${callerName}", vars));
	}

	@Test
	void configurationTemplatesStillResolve() {
		MemoryContext ctx = new MemoryContext();
		ctx.put("user", "alice");
		assertEquals("alice@" + HOME, ctx.resolve("${user}@${user.home}"));
	}

	@Test
	void tableExtrasResolveInOrder() {
		TranslationTable table = new TranslationTable();
		table.setKeyExpression("${ani}");
		table.createTranslation("2025550150")
				.put("base", "https://screen.example.com")
				.put("url", "${base}/check/${ani}");
		TableConnector connector = new TableConnector();
		connector.addTable(table);

		MemoryContext ctx = new MemoryContext();
		ctx.put("ani", "2025550150");
		connector.invoke(ctx).join();

		assertEquals("https://screen.example.com/check/2025550150", ctx.get("url"));
	}

	@Test
	void tableSelectorExtrasResolve() {
		TranslationTable table = new TranslationTable();
		table.setKeyExpression("${ani}");
		table.createTranslation("2025550150").put("mailbox", "sip:review-${ani}@voicemail.example.com");

		MemoryContext ctx = new MemoryContext();
		ctx.put("ani", "2025550150");
		new TableSelector("route", table).extract(ctx, null);

		assertEquals("sip:review-2025550150@voicemail.example.com", ctx.get("mailbox"));
		assertEquals("sip:review-2025550150@voicemail.example.com", ctx.get("route.mailbox"));
	}

	@Test
	void nulInABodyValueCannotFakeMultipleInstances() throws Exception {
		org.vorpal.blade.framework.v3.configuration.selectors.JsonSelector tier =
				new org.vorpal.blade.framework.v3.configuration.selectors.JsonSelector();
		tier.setId("tier");
		tier.setAttribute("$.tier");
		MemoryContext ctx = new MemoryContext();
		tier.extract(ctx, "{\"tier\":\"basic\\u0000gold\"}");

		assertEquals("basicgold", ctx.get("tier"));
		org.junit.jupiter.api.Assertions.assertFalse(
				new org.vorpal.blade.framework.v3.configuration.expressions.Expression("${tier} matches gold").evaluate(ctx));
	}
}
