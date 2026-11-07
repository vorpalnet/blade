package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.sip.DetachedAddress;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedResponse;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.configuration.Configuration;

/// The `To` header of an outgoing INVITE, rewritten by an application's own
/// `crud` section: the container's path, the text path, and the shapes a
/// carrier's Request-URI and `To` arrive in.
class ToHeaderRewriteTest {

	private static final DetachedSipFactory FACTORY = new DetachedSipFactory();

	@BeforeEach
	void wire() {
		SettingsManager.setSipFactory(FACTORY);
		SettingsManager.setSipLogger(new CapturingLogger());
		Callflow.setSipFactory(FACTORY);
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

	/// The transfer sample's two rules, with `replacement` as the new `To`.
	private static void publish(String replacement) {
		Rule capture = rule("capture", new ReadOperation("requestURI", "^sips?:(?<target>[^@;>]+)"));
		Rule rewrite = rule("rewrite", new UpdateOperation("To", ".*", replacement));
		rewrite.setWhen("${target} matches '1916.*'");
		RuleSet ruleSet = new RuleSet();
		ruleSet.setId("rules");
		ruleSet.getRules().add(capture);
		ruleSet.getRules().add(rewrite);
		Crud crud = new Crud();
		crud.getRuleSets().put("rules", ruleSet);
		crud.setDefaultRuleSet("rules");
		Configuration config = new Configuration();
		config.setCrud(crud);
		CrudHook.publish(config);
	}

	private static DetachedRequest invite(String requestUri, String to) throws Exception {
		DetachedRequest invite = new DetachedRequest(new DetachedApplicationSession("app"), "INVITE",
				"<sip:alice@example.com>;tag=a1", to);
		invite.setRequestURI(FACTORY.createURI(requestUri));
		return invite;
	}

	/// A request the way the container hands one over: `getTo()` returns the
	/// message's own address object, so a change to it is a change to the
	/// message. Only what the rules touch is answered.
	private static SipServletRequest containerInvite(String requestUri, Address to, Address from) throws Exception {
		DetachedApplicationSession appSession = new DetachedApplicationSession("app");
		javax.servlet.sip.URI ruri = FACTORY.createURI(requestUri);
		Map<String, Object> attributes = new HashMap<>();
		return (SipServletRequest) Proxy.newProxyInstance(ToHeaderRewriteTest.class.getClassLoader(),
				new Class<?>[] { SipServletRequest.class }, (proxy, method, args) -> {
					switch (method.getName()) {
					case "getMethod":
						return "INVITE";
					case "isInitial":
						return true;
					case "getRequestURI":
						return ruri;
					case "getTo":
						return to;
					case "getFrom":
						return from;
					case "getHeader":
						switch (((String) args[0]).toLowerCase()) {
						case "to":
							return to.toString();
						case "from":
							return from.toString();
						default:
							return null;
						}
					case "getApplicationSession":
						return appSession;
					case "getAttribute":
						return attributes.get(args[0]);
					case "setAttribute":
						attributes.put((String) args[0], args[1]);
						return null;
					case "removeAttribute":
						attributes.remove(args[0]);
						return null;
					case "toString":
						return "INVITE " + ruri;
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						Class<?> type = method.getReturnType();
						if (type == boolean.class) {
							return false;
						}
						if (type == int.class) {
							return 0;
						}
						return null;
					}
				});
	}

	@Test
	void theContainersOwnAddressIsChangedInPlace() throws Exception {
		publish("<sip:${target}@transfer.example.com>");
		Address to = new DetachedAddress("<sip:19165550100@carrier.example.com>");
		SipServletRequest invite = containerInvite("sip:19165550100@carrier.example.com", to,
				new DetachedAddress("<sip:alice@example.com>;tag=a1"));
		CrudHook.apply(invite);
		assertSame(to, invite.getTo(), "the message keeps its own address object");
		assertEquals("sip:19165550100@transfer.example.com", to.getURI().toString());
	}

	@Test
	void theContainerPathSetsTheDisplayName() throws Exception {
		publish("\"Transfer Desk\" <sip:${target}@transfer.example.com>");
		Address to = new DetachedAddress("<sip:19165550100@carrier.example.com>");
		SipServletRequest invite = containerInvite("sip:19165550100@carrier.example.com", to,
				new DetachedAddress("<sip:alice@example.com>;tag=a1"));
		CrudHook.apply(invite);
		assertEquals("Transfer Desk", to.getDisplayName());
		assertEquals("sip:19165550100@transfer.example.com", to.getURI().toString());
	}

	@Test
	void theContainerPathLeavesOtherTargetsAlone() throws Exception {
		publish("<sip:${target}@transfer.example.com>");
		Address to = new DetachedAddress("<sip:18005550100@carrier.example.com>");
		SipServletRequest invite = containerInvite("sip:18005550100@carrier.example.com", to,
				new DetachedAddress("<sip:alice@example.com>;tag=a1"));
		CrudHook.apply(invite);
		assertEquals("sip:18005550100@carrier.example.com", to.getURI().toString());
	}

	@Test
	void theContainerPathKeepsHeaderParameters() throws Exception {
		publish("<sip:${target}@transfer.example.com>");
		Address to = new DetachedAddress("<sip:19165550100@carrier.example.com>;x-leg=transfer");
		SipServletRequest invite = containerInvite("sip:19165550100@carrier.example.com", to,
				new DetachedAddress("<sip:alice@example.com>;tag=a1"));
		CrudHook.apply(invite);
		assertEquals("transfer", to.getParameter("x-leg"));
		assertEquals("sip:19165550100@transfer.example.com", to.getURI().toString());
	}

	@Test
	void theTextPathKeepsHeaderParameters() throws Exception {
		publish("<sip:${target}@transfer.example.com>");
		DetachedRequest invite = invite("sip:19165550100@carrier.example.com",
				"<sip:19165550100@carrier.example.com>;x-leg=transfer");
		CrudHook.apply(invite);
		assertEquals("sip:19165550100@transfer.example.com", invite.getTo().getURI().toString());
		assertEquals("transfer", invite.getTo().getParameter("x-leg"));
	}

	@Test
	void aSipsTargetMatches() throws Exception {
		publish("<sip:${target}@transfer.example.com>");
		DetachedRequest invite = invite("sips:19165550100@carrier.example.com",
				"<sips:19165550100@carrier.example.com>");
		CrudHook.apply(invite);
		assertEquals("sip:19165550100@transfer.example.com", invite.getTo().getURI().toString());
	}

	@Test
	void aUserPhoneTargetMatches() throws Exception {
		publish("<sip:${target}@transfer.example.com;user=phone>");
		DetachedRequest invite = invite("sip:19165550100@carrier.example.com;user=phone",
				"<sip:19165550100@carrier.example.com;user=phone>");
		CrudHook.apply(invite);
		assertEquals("sip:19165550100@transfer.example.com;user=phone", invite.getTo().getURI().toString());
	}

	/// `1916.*` is a whole-value match, so an E.164 target with its `+` does not
	/// match it. A carrier that sends `+1916…` needs `\+?1916.*`.
	@Test
	void aPlusPrefixedTargetDoesNotMatchTheBarePattern() throws Exception {
		publish("<sip:${target}@transfer.example.com>");
		DetachedRequest invite = invite("sip:+19165550100@carrier.example.com",
				"<sip:+19165550100@carrier.example.com>");
		CrudHook.apply(invite);
		assertEquals("sip:+19165550100@carrier.example.com", invite.getTo().getURI().toString());
	}

	@Test
	void theResponseToTheInviteIsUntouched() throws Exception {
		publish("<sip:${target}@transfer.example.com>");
		DetachedRequest invite = invite("sip:19165550100@carrier.example.com",
				"<sip:19165550100@carrier.example.com>");
		DetachedResponse ok = new DetachedResponse(invite, 200);
		CrudHook.apply(ok);
		assertEquals("sip:19165550100@carrier.example.com", ok.getTo().getURI().toString());
	}

	/// Through the real send chokepoint, not the hook alone.
	@Test
	void callflowSendRequestRewritesTo() throws Exception {
		publish("<sip:${target}@transfer.example.com>");
		DetachedRequest invite = invite("sip:19165550100@carrier.example.com",
				"<sip:19165550100@carrier.example.com>");
		org.vorpal.blade.framework.v3.Callflow callflow = new org.vorpal.blade.framework.v3.Callflow() {
			private static final long serialVersionUID = 1L;

			@Override
			public void process(SipServletRequest request) {
			}
		};
		callflow.sendRequest(invite, response -> {
		});
		assertEquals("sip:19165550100@transfer.example.com", invite.getTo().getURI().toString());
	}
}
