package org.vorpal.blade.services.proxy.block;

import java.util.LinkedList;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.analytics.EventSelector;
import org.vorpal.blade.framework.v3.configuration.MatchStrategy;
import org.vorpal.blade.framework.v3.configuration.connectors.RateConnector;
import org.vorpal.blade.framework.v3.configuration.connectors.SipConnector;
import org.vorpal.blade.framework.v3.configuration.connectors.TableConnector;
import org.vorpal.blade.framework.v3.configuration.routing.ConditionalRouting;
import org.vorpal.blade.framework.v3.configuration.routing.Route;
import org.vorpal.blade.framework.v3.configuration.selectors.IdentitySelector;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;
import org.vorpal.blade.framework.v3.irouter.IRouterInvite;

/// Sample call-blocking configuration, written to `_samples/` on first deploy.
///
/// ## Pipeline
///
/// 1. **sip** sets `${stirShaken}`, the STIR/SHAKEN switch, to `false`. It
///    reads the caller's ten-digit number (`${ani}`) from From, then
///    from P-Asserted-Identity when the carrier sends one, since the network
///    asserts PAI and the caller writes From. It also reads the dialed number,
///    the carrier's `verstat`, an anonymous From, and the STIR/SHAKEN PASSporT
///    (`${stir}`, `${stir.age}`, ...). A number outside the North American
///    Numbering Plan leaves `${ani}` unset, so no rule below fires on it.
/// 2. **rate** counts this caller's calls over the last minute on this node
///    into `${callRate}`.
/// 3. **lists** looks `${ani}` up in the allow list, then the block list, then
///    blocked prefixes, and sets `${listed}` to `allow` or `block`. First match
///    wins, so a number on both lists is allowed. A block entry also sets
///    `${treatment}`, what happens to that caller (see below).
/// 4. **ownNumbers** sets `${ownNumber}` when the caller ID is one of the
///    customer's own numbers. Those numbers never call in, so this rule has
///    almost no false positives. The table is prefix-matched so a DID block is
///    one row.
///
/// ## Routing, first true clause wins
///
/// | When | Outcome |
/// |---|---|
/// | allow list | pass, `X-Call-Screen: allow` |
/// | block list, treatment `review` | forward to the review mailbox |
/// | block list, treatment `tarpit` | forward to the tarpit |
/// | block list, any other treatment | `603 Decline` |
/// | caller ID is our own number | forward to the tarpit |
/// | caller ID is not a valid NANP number | `603 Decline` |
/// | carrier verification failed (STIR/SHAKEN on) | challenge IVR |
/// | more than 10 calls a minute from this number, on this node | challenge IVR |
/// | caller ID equals the dialed number | challenge IVR |
/// | otherwise | pass, `X-Call-Screen: clear`, or `watch` when anonymous; with STIR/SHAKEN on, also attestation C or a PASSporT older than 60 s |
///
/// Every outcome carries `X-Call-Screen: <verdict>;reason=<rule>` so the IVR,
/// the agent desktop, and call records see why. The challenge route carries
/// the original request URI so the IVR can complete the call once the caller
/// passes.
///
/// ## Block-list treatments
///
/// A blocked caller is not always a machine. Some are people harassing someone
/// at the company, and rejecting them says they have been found out, which can
/// push them to other numbers or other channels. Each block entry therefore
/// names its treatment:
///
/// - `review` forwards to a voicemail box a review team listens to. The caller
///   hears an ordinary greeting, and the message is evidence. The forward
///   carries `Diversion` and `X-Original-Request-URI` naming the number that
///   was dialed, so the voicemail system can record who was targeted.
/// - `tarpit` forwards to an endpoint that rings and never answers. A dialer
///   learns nothing and holds a port until it gives up, and an unanswered call
///   bills no minutes. The endpoint must not answer, or every robocall costs
///   the customer a toll-free minute.
/// - `decline`, or no treatment, answers `603 Decline`: final, no reason given,
///   and a code a dialer should not retry.
///
/// ## STIR/SHAKEN is off
///
/// Every rule that reads `verstat` or the PASSporT also requires
/// `${stirShaken} == true`, so with the switch at `false` those values are
/// parsed but never change an outcome or reach a header. Setting the
/// `stirShaken` selector's expression to `true` turns them on.
///
/// ## Reporting
///
/// `analytics` defines the router's decision events: `callRouted` for a call
/// passed or forwarded to the challenge, review mailbox or tarpit, `callDeclined`
/// for a 603. Each
/// carries the caller, the dialed number and the `X-Call-Screen` verdict.
/// Analytics ships disabled; enabling it puts one event per call on the BLADE
/// event bus.
///
/// Numbers use the fictional 555-01xx range; hosts use `example.com`.
public class CallBlockingConfigSample extends CallBlockingConfig {
	private static final long serialVersionUID = 1L;

	/// Ten-digit NANP number from a sip:, sips: or tel: URI, with or without +1.
	public static final String NANP_USER = ".*?(?:sips?|tel):\\+?1?(?<tn>\\d{10})(?:[@;>].*)?";

	/// The `verstat` URI parameter a verifying carrier adds (ATIS-1000074).
	public static final String VERSTAT = ".*;verstat=(?<verstat>[A-Za-z-]+).*";

	/// A NANP number that can be assigned: area code and exchange start 2-9,
	/// area code's middle digit is not 9, and the area code is not N11.
	public static final String VALID_NANP = "(?![2-9]11)[2-9][0-8]\\d[2-9]\\d{6}";

	public static final String CHALLENGE_URI = "sip:challenge@ivr.example.com";

	/// The voicemail box a review team listens to.
	public static final String REVIEW_URI = "sip:review@voicemail.example.com";

	/// An endpoint that rings and never answers.
	public static final String TARPIT_URI = "sip:tarpit@media.example.com";

	/// Guard prepended to every STIR/SHAKEN condition.
	private static final String STIR_ON = "${stirShaken} == true && ";

	public CallBlockingConfigSample() {

		// ----- 1. What the INVITE says -----
		SipConnector sip = new SipConnector();
		sip.setId("sip");
		sip.setDescription("Caller, dialed number, carrier verification, STIR/SHAKEN claims");
		// The STIR/SHAKEN switch: a constant, since every INVITE has a request URI.
		sip.addSelector(new RegexSelector("stirShaken", "requestURI", ".*", "false"));
		sip.addSelector(new RegexSelector("ani", "From", NANP_USER, "${tn}"));
		sip.addSelector(new RegexSelector("ani", "P-Asserted-Identity", NANP_USER, "${tn}"));
		sip.addSelector(new RegexSelector("dnis", "requestURI", NANP_USER, "${tn}"));
		sip.addSelector(new RegexSelector("requestUri", "requestURI", ".*", "${0}"));
		sip.addSelector(new RegexSelector("verstat", "From", VERSTAT, "${verstat}"));
		sip.addSelector(new RegexSelector("verstat", "P-Asserted-Identity", VERSTAT, "${verstat}"));
		sip.addSelector(new RegexSelector("anonymous", "From", "(?i).*anonymous.*", "true"));
		sip.addSelector(new IdentitySelector("stir"));

		// ----- 2. How often this number is calling -----
		RateConnector rate = new RateConnector();
		rate.setId("rate");
		rate.setDescription("Calls from this number in the last minute, on this node");
		rate.setKeyExpression("${ani}");
		rate.setWindowSeconds(60);

		// ----- 3. Allow and block lists -----
		TableConnector lists = new TableConnector();
		lists.setId("lists");
		lists.setDescription("Allow list, then block list, then blocked prefixes; first match wins."
				+ " A block entry's treatment is review, tarpit or decline");

		TranslationTable allow = new TranslationTable();
		allow.setMatch(MatchStrategy.hash);
		allow.setKeyExpression("${ani}");
		allow.createTranslation("8165550100").put("listed", "allow");
		allow.createTranslation("9135550101").put("listed", "allow");
		lists.addTable(allow);

		TranslationTable block = new TranslationTable();
		block.setMatch(MatchStrategy.hash);
		block.setKeyExpression("${ani}");
		block.createTranslation("2025550150").put("listed", "block").put("treatment", "review");
		block.createTranslation("2025550160").put("listed", "block").put("treatment", "tarpit");
		block.createTranslation("2025550170").put("listed", "block").put("treatment", "decline");
		lists.addTable(block);

		TranslationTable blockPrefix = new TranslationTable();
		blockPrefix.setMatch(MatchStrategy.prefix);
		blockPrefix.setKeyExpression("${ani}");
		blockPrefix.createTranslation("30355501").put("listed", "block").put("treatment", "tarpit");
		lists.addTable(blockPrefix);

		// ----- 4. The customer's own numbers -----
		TableConnector own = new TableConnector();
		own.setId("ownNumbers");
		own.setDescription("Our own numbers never place inbound calls; a match is spoofed caller ID");

		TranslationTable ownNumbers = new TranslationTable();
		ownNumbers.setMatch(MatchStrategy.prefix);
		ownNumbers.setKeyExpression("${ani}");
		ownNumbers.createTranslation("8005550100").put("ownNumber", "true");
		ownNumbers.createTranslation("8885550100").put("ownNumber", "true");
		ownNumbers.createTranslation("41555501").put("ownNumber", "true");
		own.addTable(ownNumbers);

		this.setPipeline(new LinkedList<>());
		this.getPipeline().add(sip);
		this.getPipeline().add(rate);
		this.getPipeline().add(lists);
		this.getPipeline().add(own);

		// ----- Decision -----
		ConditionalRouting routing = new ConditionalRouting();

		routing.addClause("${listed} == allow",
				new Route().addHeader("X-Call-Screen", "allow;reason=allow-list"));

		routing.addClause("${listed} == block && ${treatment} == review",
				new Route(REVIEW_URI)
						.addHeader("X-Call-Screen", "review;reason=block-list")
						.addHeader("X-Original-Request-URI", "${requestUri}")
						.addHeader("Diversion", "<${requestUri}>;reason=unconditional;counter=1"));

		routing.addClause("${listed} == block && ${treatment} == tarpit",
				tarpit("block-list"));

		routing.addClause("${listed} == block",
				new Route(603, "Decline").addHeader("X-Call-Screen", "block;reason=block-list"));

		routing.addClause("${ownNumber} == true",
				tarpit("own-number"));

		routing.addClause("${ani} != '' && !(${ani} matches '" + VALID_NANP + "')",
				new Route(603, "Decline").addHeader("X-Call-Screen", "block;reason=invalid-number"));

		routing.addClause(STIR_ON + "${verstat} == TN-Validation-Failed",
				challenge("verstat-failed"));

		routing.addClause("${callRate} > 10",
				challenge("call-rate"));

		routing.addClause("${ani} != '' && ${ani} == ${dnis}",
				challenge("caller-is-callee"));

		Route clear = new Route()
				.addHeader("X-Call-Screen", "clear")
				.addConditionalHeader("X-Call-Screen", "watch;reason=anonymous", "${anonymous} == true")
				.addConditionalHeader("X-Call-Screen", "watch;reason=attestation-c", STIR_ON + "${stir} == C")
				.addConditionalHeader("X-Call-Screen", "watch;reason=stale-passport",
						STIR_ON + "${stir} != '' && ${stir.age} > 60")
				.addConditionalHeader("X-Call-Screen-Attest", "${stir}", STIR_ON + "${stir} != ''")
				.addConditionalHeader("X-Call-Screen-Verstat", "${verstat}", STIR_ON + "${verstat} != ''")
				// Carry this number's per-node call rate downstream so a media-tier
				// risk score can weigh it as behaviour. A call below the challenge
				// threshold above still has a rate worth fusing with a borderline
				// acoustic score.
				.addConditionalHeader("X-Call-Rate", "${callRate}", "${callRate} != ''");
		routing.setDefaultRoute(clear);

		this.setRouting(routing);

		Analytics analytics = new Analytics();
		analytics.setEnabled(false);
		for (String name : new String[] { IRouterInvite.EVENT_ROUTED, IRouterInvite.EVENT_DECLINED }) {
			EventSelector event = analytics.createEventSelector(name);
			event.addAttribute("caller", "From", "^.*?(?:sips?|tel):([^@;>]*).*$", "$1");
			event.addAttribute("dialed", "To", "^.*?(?:sips?|tel):([^@;>]*).*$", "$1");
			event.addAttribute("screen", "X-Call-Screen", "^.*$", "$0");
		}
		this.setAnalytics(analytics);

		this.setNotes("STIR/SHAKEN rules are off. To turn them on, set the expression of the"
				+ " stirShaken selector in the sip connector to true.");
	}

	private static Route tarpit(String reason) {
		return new Route(TARPIT_URI).addHeader("X-Call-Screen", "tarpit;reason=" + reason);
	}

	private static Route challenge(String reason) {
		return new Route(CHALLENGE_URI)
				.addHeader("X-Call-Screen", "challenge;reason=" + reason)
				.addHeader("X-Original-Request-URI", "${requestUri}");
	}
}
