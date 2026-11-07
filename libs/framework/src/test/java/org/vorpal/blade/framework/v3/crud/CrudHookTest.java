package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import javax.servlet.sip.ServletParseException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.configuration.Configuration;

/// An application's own `crud` section, run by [CrudHook] on what it sends.
class CrudHookTest {

	private static DetachedRequest invite(String requestUri, String to) throws ServletParseException {
		DetachedRequest invite = new DetachedRequest(new DetachedApplicationSession("app"), "INVITE",
				"<sip:alice@example.com>;tag=a1", to);
		invite.setRequestURI(new DetachedSipFactory().createURI(requestUri));
		return invite;
	}

	@BeforeEach
	void wire() {
		DetachedSipFactory factory = new DetachedSipFactory();
		SettingsManager.setSipFactory(factory);
		SettingsManager.setSipLogger(new CapturingLogger());
		Callflow.setSipFactory(factory);
		Callflow.setSipLogger(new CapturingLogger());
	}

	@AfterEach
	void unpublish() {
		CrudHook.publish(new Configuration());
	}

	private static Rule rule(String id, Operation op) {
		Rule rule = new Rule();
		rule.setId(id);
		rule.setMethod("INVITE");
		rule.setMessageType("request");
		rule.getOperations().add(op);
		return rule;
	}

	private static void publish(Rule... rules) {
		RuleSet ruleSet = new RuleSet();
		ruleSet.setId("rules");
		for (Rule rule : rules) {
			ruleSet.getRules().add(rule);
		}
		Crud crud = new Crud();
		crud.getRuleSets().put("rules", ruleSet);
		crud.setDefaultRuleSet("rules");
		Configuration config = new Configuration();
		config.setCrud(crud);
		CrudHook.publish(config);
	}

	private static void publishTargetRewrite() {
		Rule capture = rule("capture", new ReadOperation("requestURI", "^sips?:(?<target>[^@;>]+)"));
		Rule rewrite = rule("rewrite", new UpdateOperation("To", ".*", "<sip:${target}@transfer.example.com>"));
		rewrite.setWhen("${target} matches '1916.*'");
		publish(capture, rewrite);
	}

	@Test
	void rewritesToWhenTheTargetMatches() throws Exception {
		publishTargetRewrite();
		DetachedRequest invite = invite("sip:19165550100@carrier.example.com", "<sip:19165550100@carrier.example.com>");
		CrudHook.apply(invite);
		assertEquals("sip:19165550100@transfer.example.com", invite.getTo().getURI().toString());
	}

	@Test
	void leavesOtherTargetsAlone() throws Exception {
		publishTargetRewrite();
		DetachedRequest invite = invite("sip:18005550100@carrier.example.com", "<sip:18005550100@carrier.example.com>");
		CrudHook.apply(invite);
		assertEquals("sip:18005550100@carrier.example.com", invite.getTo().getURI().toString());
	}

	@Test
	void neverChangesToInsideADialog() throws Exception {
		publishTargetRewrite();
		DetachedRequest reinvite = invite("sip:19165550100@carrier.example.com", "<sip:19165550100@carrier.example.com>;tag=b1");
		reinvite.setInitial(false);
		CrudHook.apply(reinvite);
		assertEquals("sip:19165550100@carrier.example.com", reinvite.getTo().getURI().toString());
	}

	@Test
	void runsOncePerMessage() throws Exception {
		publish(rule("append", new UpdateOperation("X-Count", "^(?<v>.*)$", "${v}x")));
		DetachedRequest invite = invite("sip:bob@example.com", "<sip:bob@example.com>");
		invite.setHeader("X-Count", "1");
		CrudHook.apply(invite);
		CrudHook.apply(invite); // a parked request is sent a second time
		assertEquals("1x", invite.getHeader("X-Count"));
	}

	@Test
	void skipsRulesThatNameAB2buaEvent() throws Exception {
		Rule rule = rule("stamp", new CreateOperation("X-Stamp", "one"));
		rule.setEvent("callStarted");
		publish(rule);
		DetachedRequest invite = invite("sip:bob@example.com", "<sip:bob@example.com>");
		CrudHook.apply(invite);
		assertNull(invite.getHeader("X-Stamp"));
	}

	@Test
	void variablesNeverOverwriteTheApplicationsAttributes() throws Exception {
		publish(rule("capture", new ReadOperation("requestURI", "^sip:(?<target>[^@]+)")));
		DetachedRequest invite = invite("sip:bob@example.com", "<sip:bob@example.com>");
		invite.getApplicationSession().setAttribute("target", "the application's own");
		CrudHook.apply(invite);
		assertEquals("the application's own", invite.getApplicationSession().getAttribute("target"));
		assertEquals("bob", MessageHelper.getVariable(invite.getApplicationSession(), "target"));
	}

	@Test
	void doesNothingWithoutASection() throws Exception {
		CrudHook.publish(new Configuration());
		DetachedRequest invite = invite("sip:19165550100@carrier.example.com", "<sip:19165550100@carrier.example.com>");
		CrudHook.apply(invite);
		assertEquals("sip:19165550100@carrier.example.com", invite.getTo().getURI().toString());
	}
}
