package org.vorpal.blade.services.crud;

import org.vorpal.blade.framework.v2.config.ConfigHashMap;
import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.framework.v2.config.TranslationsMap;
import org.vorpal.blade.framework.v2.logging.LogParameters;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// Canonical sample that demonstrates every addressing mode (regex / XPath /
/// JsonPath / SDP) inside each of the four CRUD verbs:
///
/// 1. **example-create** — stamp two SIP headers and attach an XML metadata
///    part to the body
/// 2. **example-read**   — capture values from a header, an XML part, a JSON
///    part, and an SDP part into session variables
/// 3. **example-update** — anonymize a header and rewrite values inside XML,
///    JSON, and SDP parts
/// 4. **example-delete** — strip a private header plus matching values
///    inside XML, JSON, and SDP parts
///
/// Selectors / maps / plan map dialed numbers `8001..8004` onto these rule
/// sets so each can be exercised independently from a SIPp dialer.
public class CrudConfigurationSample extends CrudConfiguration {
	private static final long serialVersionUID = 1L;

	public CrudConfigurationSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LogParameters.LoggingLevel.FINER);

		Selector dialedNumber = new Selector("dialed-number", "To", SIP_ADDRESS_PATTERN, "${user}");
		dialedNumber.setDescription("Extract dialed number from To header");
		this.selectors.add(dialedNumber);

		RuleSet create = exampleCreate();
		RuleSet read = exampleRead();
		RuleSet update = exampleUpdate();
		RuleSet delete = exampleDelete();

		this.getRuleSets().put(create.getId(), create);
		this.getRuleSets().put(read.getId(), read);
		this.getRuleSets().put(update.getId(), update);
		this.getRuleSets().put(delete.getId(), delete);

		TranslationsMap dialedMap = new ConfigHashMap();
		dialedMap.id = "dialed-number-map";
		dialedMap.description = "Map dialed numbers to rule sets";
		dialedMap.addSelector(dialedNumber);

		dialedMap.createTranslation("8001").addAttribute("ruleSet", create.getId());
		dialedMap.createTranslation("8002").addAttribute("ruleSet", read.getId());
		dialedMap.createTranslation("8003").addAttribute("ruleSet", update.getId());
		dialedMap.createTranslation("8004").addAttribute("ruleSet", delete.getId());

		this.maps.add(dialedMap);
		this.plan.add(dialedMap);

		this.defaultRoute = new Translation();
		this.defaultRoute.setId("default");
		this.defaultRoute.addAttribute("ruleSet", create.getId());
	}

	private static RuleSet exampleCreate() {
		RuleSet rs = new RuleSet();
		rs.setId("example-create");
		rs.setDescription("Stamp routing headers and attach recording metadata XML");

		Rule r = new Rule();
		r.setId("stamp-and-attach");
		r.setMethod("INVITE");
		r.setEvent("callStarted");
		r.getOperations().add(new CreateOperation("X-Caller-Region", "us-west-2"));
		r.getOperations().add(new CreateOperation("X-Trace-Id", "trace-${userPart}"));

		CreateOperation attach = new CreateOperation();
		attach.setAttribute("body");
		attach.setContentType("application/recording-metadata+xml");
		attach.setValue("<recording><session id=\"abc-123\" tenant=\"acme\"/></recording>");
		r.getOperations().add(attach);

		rs.getRules().add(r);
		return rs;
	}

	private static RuleSet exampleRead() {
		RuleSet rs = new RuleSet();
		rs.setId("example-read");
		rs.setDescription("Capture values from headers, XML, JSON, and SDP into session variables");

		Rule r = new Rule();
		r.setId("harvest-everything");
		r.setMethod("INVITE");
		r.setEvent("callStarted");

		r.getOperations().add(new ReadOperation("From",
				"sip:(?<callerUser>[^@]+)@(?<callerHost>[^;>]+)"));

		XPathReadOperation xml = new XPathReadOperation();
		xml.setContentType("application/recording-metadata+xml");
		xml.getExpressions().put("tenantId", "//session/@tenant");
		xml.getExpressions().put("sessionId", "//session/@id");
		r.getOperations().add(xml);

		JsonPathReadOperation json = new JsonPathReadOperation();
		json.setContentType("application/json");
		json.getExpressions().put("agentId", "$.agent.id");
		r.getOperations().add(json);

		SdpReadOperation sdp = new SdpReadOperation();
		sdp.setContentType("application/sdp");
		sdp.getExpressions().put("mediaAddr", "$.connection.address");
		sdp.getExpressions().put("mediaPort", "$.media[0].port");
		r.getOperations().add(sdp);

		rs.getRules().add(r);
		return rs;
	}

	private static RuleSet exampleUpdate() {
		RuleSet rs = new RuleSet();
		rs.setId("example-update");
		rs.setDescription("Anonymize the From header and rewrite values across XML, JSON, and SDP");

		Rule r = new Rule();
		r.setId("rewrite-everything");
		r.setMethod("INVITE");
		r.setEvent("callStarted");

		r.getOperations().add(new UpdateOperation("From",
				"sip:(?<u>[^@]+)@(?<h>[^;>]+)",
				"sip:anonymous@${h}"));

		XPathUpdateOperation xml = new XPathUpdateOperation();
		xml.setContentType("application/recording-metadata+xml");
		xml.setXpath("//session/@tenant");
		xml.setValue("redacted");
		r.getOperations().add(xml);

		JsonPathUpdateOperation json = new JsonPathUpdateOperation();
		json.setContentType("application/json");
		json.setJsonPath("$.agent.id");
		json.setValue("MASKED");
		r.getOperations().add(json);

		SdpUpdateOperation sdp = new SdpUpdateOperation();
		sdp.setContentType("application/sdp");
		sdp.setJsonPath("$.connection.address");
		sdp.setValue("10.99.0.1");
		r.getOperations().add(sdp);

		rs.getRules().add(r);
		return rs;
	}

	private static RuleSet exampleDelete() {
		RuleSet rs = new RuleSet();
		rs.setId("example-delete");
		rs.setDescription("Strip private values across headers, XML, JSON, and SDP");

		Rule r = new Rule();
		r.setId("scrub-everything");
		r.setMethod("INVITE");
		r.setEvent("callStarted");

		r.getOperations().add(new DeleteOperation("P-Asserted-Identity"));

		XPathDeleteOperation xml = new XPathDeleteOperation();
		xml.setContentType("application/recording-metadata+xml");
		xml.setXpath("//session/@tenant");
		r.getOperations().add(xml);

		JsonPathDeleteOperation json = new JsonPathDeleteOperation();
		json.setContentType("application/json");
		json.setJsonPath("$.agent.id");
		r.getOperations().add(json);

		SdpDeleteOperation sdp = new SdpDeleteOperation();
		sdp.setContentType("application/sdp");
		sdp.setJsonPath("$.media[0].attributes[?(@.name=='sendrecv')]");
		r.getOperations().add(sdp);

		rs.getRules().add(r);
		return rs;
	}
}
