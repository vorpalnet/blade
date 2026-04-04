package org.vorpal.blade.services.crud;

import java.util.LinkedList;

import org.vorpal.blade.framework.v2.config.ConfigHashMap;
import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.framework.v2.config.TranslationsMap;
import org.vorpal.blade.framework.v2.logging.LogParameters;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/**
 * Generates a sample CrudConfiguration demonstrating all CRUD operations.
 */
public class CrudConfigurationSample extends CrudConfiguration {
	private static final long serialVersionUID = 1L;

	public CrudConfigurationSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LogParameters.LoggingLevel.FINER);

		// Selector: extract dialed number from To header
		Selector dialedNumber = new Selector("dialed-number", "To", SIP_ADDRESS_PATTERN, "${user}");
		dialedNumber.setDescription("Extract dialed number from To header");
		this.selectors.add(dialedNumber);

		// Rule Set: demonstrate all four operations
		RuleSet ruleSet = new RuleSet();
		ruleSet.setId("sample-rules");
		ruleSet.setDescription("Sample rules demonstrating CRUD operations");

		// Rule 1: On initial INVITE, read caller info
		Rule readCallerRule = new Rule();
		readCallerRule.setId("read-caller");
		readCallerRule.setDescription("Extract caller user and host from From header on initial INVITE");
		readCallerRule.setMethod("INVITE");
		readCallerRule.setEvent("callStarted");
		
		ReadOperation readFrom = new ReadOperation("From", SIP_ADDRESS_PATTERN);
		readCallerRule.getRead().add(readFrom);

		ruleSet.getRules().add(readCallerRule);

		// Rule 2: On initial INVITE, add a custom header using saved variables
		Rule addHeaderRule = new Rule();
		addHeaderRule.setId("add-header");
		addHeaderRule.setDescription("Add X-Caller-Info header using values extracted by read-caller");
		addHeaderRule.setMethod("INVITE");
		addHeaderRule.setEvent("callStarted");

		CreateOperation createHeader = new CreateOperation("X-Caller-Info", "${user}@${host}");
		addHeaderRule.getCreate().add(createHeader);

		ruleSet.getRules().add(addHeaderRule);

		// Rule 3: On any request, remove a private header
		Rule deleteRule = new Rule();
		deleteRule.setId("strip-private");
		deleteRule.setDescription("Remove X-Private-Data header from all outbound requests");
		deleteRule.setMessageType("request");

		DeleteOperation deleteHeader = new DeleteOperation("X-Private-Data");
		deleteRule.getDelete().add(deleteHeader);

		ruleSet.getRules().add(deleteRule);

		this.getRuleSets().put(ruleSet.getId(), ruleSet);

		// Translation map: match all calls to the sample rule set
		TranslationsMap hashMap = new ConfigHashMap();
		hashMap.id = "dialed-number-map";
		hashMap.description = "Map dialed numbers to rule sets";
		hashMap.addSelector(dialedNumber);

		Translation translation = hashMap.createTranslation("alice");
		translation.setId("route-alice");
		translation.addAttribute("ruleSet", ruleSet.getId());

		this.maps.add(hashMap);
		this.plan.add(hashMap);

		// Default route applies rule set to all unmatched calls
		this.defaultRoute = new Translation();
		this.defaultRoute.setId("default");
		this.defaultRoute.addAttribute("ruleSet", ruleSet.getId());
	}
}
