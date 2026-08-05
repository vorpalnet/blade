package org.vorpal.blade.framework.v3.crud;

import org.vorpal.blade.framework.v2.logging.LogParameters;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v3.configuration.MatchStrategy;
import org.vorpal.blade.framework.v3.configuration.connectors.SipConnector;
import org.vorpal.blade.framework.v3.configuration.connectors.TableConnector;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

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
/// The pipeline — a [SipConnector] extracting the dialed number, then a
/// [TableConnector] writing the `ruleSet` variable — maps dialed numbers
/// `8001..8004` onto these rule sets so each can be exercised independently
/// from a SIPp dialer. Any other number falls back to `defaultRuleSet`
/// (example-create), so dialing anything demonstrates the service.
/// Example-create's `trace-${dialedNumber}` header shows a
/// pipeline-extracted variable being referenced inside a rule template.
public class CrudConfigurationSample extends CrudSettings {
	private static final long serialVersionUID = 1L;

	public CrudConfigurationSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LogParameters.LoggingLevel.FINER);

		SipConnector sip = new SipConnector();
		sip.setId("sip");
		sip.setDescription("Extract the dialed number from the To header");
		sip.addSelector(new RegexSelector("dialedNumber", "To",
				".*<?sips?:\\+?(?<did>[^@;>]+)@.*", "${did}"));

		TableConnector ruleSelection = new TableConnector();
		ruleSelection.setId("rule-selection");
		ruleSelection.setDescription("Map the dialed number to a rule set");

		TranslationTable byDialedNumber = new TranslationTable();
		byDialedNumber.setMatch(MatchStrategy.hash);
		byDialedNumber.setKeyExpression("${dialedNumber}");
		ruleSelection.addTable(byDialedNumber);

		this.getPipeline().add(sip);
		this.getPipeline().add(ruleSelection);

		RuleSet create = exampleCreate();
		RuleSet read = exampleRead();
		RuleSet update = exampleUpdate();
		RuleSet delete = exampleDelete();

		this.getRuleSets().put(create.getId(), create);
		this.getRuleSets().put(read.getId(), read);
		this.getRuleSets().put(update.getId(), update);
		this.getRuleSets().put(delete.getId(), delete);

		byDialedNumber.createTranslation("8001").put(RULESET_VARIABLE, create.getId());
		byDialedNumber.createTranslation("8002").put(RULESET_VARIABLE, read.getId());
		byDialedNumber.createTranslation("8003").put(RULESET_VARIABLE, update.getId());
		byDialedNumber.createTranslation("8004").put(RULESET_VARIABLE, delete.getId());

		this.setDefaultRuleSet(create.getId());
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
		r.getOperations().add(new CreateOperation("X-Trace-Id", "trace-${dialedNumber}"));

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
