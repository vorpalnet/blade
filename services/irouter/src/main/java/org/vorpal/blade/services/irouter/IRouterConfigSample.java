package org.vorpal.blade.services.irouter;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.RestResolver;
import org.vorpal.blade.framework.v3.configuration.Selector;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.tables.HashTranslationTable;
import org.vorpal.blade.framework.v3.configuration.translations.tables.PrefixTranslationTable;

/// Builds a sample configuration for the Intelligent Router.
///
/// This gets written to the `_samples/` directory on first deploy
/// so operators have a working template to copy and customize.
public class IRouterConfigSample extends IRouterConfig {
	private static final long serialVersionUID = 1L;

	public IRouterConfigSample() {

		// Selector: extract the user part of the To header
		Selector toUser = new Selector("to-user", "To",
				"(?:sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*",
				"${user}");
		this.selectors = new LinkedList<>();
		this.selectors.add(toUser);

		// Hash table: route by dialed number
		HashTranslationTable<RoutingTreatment> routes = new HashTranslationTable<>();
		routes.setId("routes");
		routes.setDescription("Route calls by dialed number");

		Translation<RoutingTreatment> sales = routes.createTranslation("sales");
		sales.setDescription("Sales queue");
		sales.setTreatment(new RoutingTreatment("sip:sales@queue.example.com"));

		Translation<RoutingTreatment> support = routes.createTranslation("support");
		support.setDescription("Support queue");
		Map<String, String> supportHeaders = new LinkedHashMap<>();
		supportHeaders.put("X-Priority", "high");
		support.setTreatment(new RoutingTreatment("sip:support@queue.example.com", supportHeaders));

		// Prefix table: route by area code
		PrefixTranslationTable<RoutingTreatment> areaCodes = new PrefixTranslationTable<>();
		areaCodes.setId("area-codes");
		areaCodes.setDescription("Route by area code prefix");

		areaCodes.createTranslation("1800")
				.setTreatment(new RoutingTreatment("sip:tollfree@carrier.example.com"));
		areaCodes.createTranslation("1")
				.setTreatment(new RoutingTreatment("sip:domestic@carrier.example.com"));
		areaCodes.createTranslation("44")
				.setTreatment(new RoutingTreatment("sip:uk@intl-carrier.example.com"));

		this.plan = new LinkedList<>();
		this.plan.add(routes);
		this.plan.add(areaCodes);

		// REST resolver: query an external API when no local match
		// Uses ${user} and ${host} extracted by the "to-user" selector.
		// POST body comes from _templates/customer-lookup.txt
		RestResolver<RoutingTreatment> restResolver = new RestResolver<>();
		restResolver.setId("customer-api");
		restResolver.setDescription("Customer routing API lookup");
		restResolver.setUrl("https://api.example.com/v1/route");
		restResolver.setMethod("POST");
		restResolver.setBearerToken("your-api-token-here");
		restResolver.setTimeoutSeconds(3);
		restResolver.setBodyTemplate("customer-lookup.txt");

		// Response selector: extract destination URI from JSON response
		// e.g. response: {"route": {"destination": "sip:agent@queue.example.com"}}
		Selector responseSelector = new Selector("route-dest", "$.route.destination",
				"(?<uri>.*)", "${uri}");
		restResolver.setResponseSelector(responseSelector);

		this.resolvers = new LinkedList<>();
		this.resolvers.add(restResolver);

		// Default route: if nothing matches
		this.defaultRoute = new Translation<>("default");
		this.defaultRoute.setDescription("Default route when no match is found");
		this.defaultRoute.setTreatment(new RoutingTreatment("sip:operator@pbx.example.com"));
	}
}
