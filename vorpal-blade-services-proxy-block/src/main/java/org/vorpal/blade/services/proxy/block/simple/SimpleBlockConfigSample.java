package org.vorpal.blade.services.proxy.block.simple;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class SimpleBlockConfigSample extends SimpleBlockConfig {

	private static final long serialVersionUID = 1L;
//	public final static String NORMALIZE_FROM_PATTERN = "^(?:\"?(?<fromName>.*?)\"?\\s*)[<]*(?<fromProto>sips?):(?:(?<fromUser>.*)@)*(?<fromHost>[^:;>]*)(?:[:](?<fromPort>[0-9]+))*(?:[;](?<fromUriparams>[^>]*))*[>]*[;]*(?<FromAddrparams>.*)$";
	public final static String NORMALIZE_FROM_PATTERN = "^(?:\"?(?<fromName>.*?)\"?\\s*)[<]*(?<fromProto>sips?):(?:[+1]*(?<fromUser>.*)@)*(?<fromHost>[^:;>]*)(?:[:](?<fromPort>[0-9]+))*(?:[;](?<fromUriparams>[^>]*))*[>]*[;]*(?<FromAddrparams>.*)$";
//	public final static String NORMALIZE_FROM_EXPRESSION = "\\L${fromUser}";
	public final static String NORMALIZE_FROM_EXPRESSION = "${fromUser}";

	public final static String TO_PATTERN = "^(?:\"?(?<toName>.*?)\"?\\s*)[<]*(?<toProto>sips?):(?:(?<toUser>.*)@)*(?<toHost>[^:;>]*)(?:[:](?<toPort>[0-9]+))*(?:[;](?<toUriparams>[^>]*))*[>]*[;]*(?<toAddrparams>.*)$";
	public final static String TO_EXPRESSION = "${toUser}";

	public final static String RURI_PATTERN = "^(?:\"?(?<ruriName>.*?)\"?\\s*)[<]*(?<ruriProto>sips?):(?:(?<ruriUser>.*)@)*(?<ruriHost>[^:;>]*)(?:[:](?<ruriPort>[0-9]+))*(?:[;](?<ruriUriparams>[^>]*))*[>]*[;]*(?<ruriAddrparams>.*)$";
//	public final static String NORMALIZE_RURI_EXPRESSION = "\\L${toUser}";

	public SimpleBlockConfigSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);

		this.fromSelector = new AttributeSelector();
		fromSelector.setId("fromSel");
		fromSelector.setDescription("Match on From header.");
		fromSelector.setAttribute("From");
		fromSelector.setPattern(NORMALIZE_FROM_PATTERN);
		fromSelector.setExpression(NORMALIZE_FROM_EXPRESSION);
		Map<String, String> additionalFromExpressions = new HashMap<>();
		additionalFromExpressions.put("fromPort", "5060"); // default value
		fromSelector.setAdditionalExpressions(additionalFromExpressions);

		this.toSelector = new AttributeSelector();
		toSelector.setId("toSel");
		toSelector.setDescription("Match on To Header");
		toSelector.setAttribute("To");
		toSelector.setPattern(TO_PATTERN);
		toSelector.setExpression(TO_EXPRESSION);

		this.ruriSelector = new AttributeSelector();
		ruriSelector.setId("ruriSel");
		ruriSelector.setDescription("Match on request URI");
		ruriSelector.setAttribute("Request-URI");
		ruriSelector.setPattern(RURI_PATTERN);
//		fromSelector.setExpression(NORMALIZE_FROM_EXPRESSION);

		this.defaultRoute = new SimpleTranslation();
		this.defaultRoute.id = "defaultRoute";
		this.defaultRoute.forwardTo = new LinkedList<>();
		this.defaultRoute.forwardTo.add("${ruriProto}:${ruriUser}@${ruriHost}:${fromPort};${ruriUriparams}");

// ----
		SimpleTranslation n8165551234 = new SimpleTranslation();
		n8165551234.id = "8165551234";
		this.callingNumbers.add(n8165551234);
		n8165551234.forwardTo = new LinkedList<>();
		n8165551234.forwardTo.add("${ruriProto}:${toUser}@10.10.10.10:${fromPort};${ruriUriparams}");
		n8165551234.forwardTo.add("${ruriProto}:${toUser}@10.10.10.11:${fromPort};${ruriUriparams}");
// ----
		SimpleTranslation n9135551234 = new SimpleTranslation();
		n9135551234.id = "9135551234";
		this.callingNumbers.add(n9135551234);
		n9135551234.forwardTo = new LinkedList<>();
		n9135551234.forwardTo.add("${ruriProto}:${toUser}@10.10.10.10:${fromPort};${ruriUriparams}");
		n9135551234.forwardTo.add("${ruriProto}:${toUser}@10.10.10.11:${fromPort};${ruriUriparams}");

		n9135551234.dialedNumbers = new LinkedList<>();
		SimpleDialed sd1 = new SimpleDialed();
		n9135551234.dialedNumbers.add(sd1);
		sd1.id = "8005550001";
		sd1.forwaredTo.add("${ruriProto}:${toUser}@10.10.10.12:${fromPort};${ruriUriparams}");
		sd1.forwaredTo.add("${ruriProto}:${toUser}@10.10.10.13:${fromPort};${ruriUriparams}");
		n9135551234.dialedNumbers.add(sd1);

		SimpleDialed sd2 = new SimpleDialed();
		n9135551234.dialedNumbers.add(sd2);
		sd2.id = "8005550002";
		sd2.forwaredTo.add("${ruriProto}:${toUser}@10.10.10.14:${fromPort};${ruriUriparams}");
		sd2.forwaredTo.add("${ruriProto}:${toUser}@10.10.10.15:${fromPort};${ruriUriparams}");
		n9135551234.dialedNumbers.add(sd2);

// ----
		SimpleTranslation stAlice = new SimpleTranslation();
		stAlice.id = "alice";
		this.callingNumbers.add(stAlice);

		List<String> aliceList = new LinkedList<>();
		aliceList.add("sip:doug@vorpal.net");
		stAlice.forwardTo = aliceList;

		stAlice.dialedNumbers = new LinkedList<>();

		SimpleDialed bobDialed = new SimpleDialed();
		bobDialed.id = "bob";
		bobDialed.forwaredTo = new LinkedList<>();
		bobDialed.forwaredTo.add("sip:carol@vorpal.net:${fromPort}");
		stAlice.dialedNumbers.add(bobDialed);

		SimpleDialed carolDialed = new SimpleDialed();
		carolDialed.id = "carol";
		carolDialed.forwaredTo = new LinkedList<>();
		carolDialed.forwaredTo.add("sip:bob@vorpal.net:${fromPort}");
		stAlice.dialedNumbers.add(carolDialed);

	}

/*	
	public static void main(String[] args) throws JsonProcessingException, ServletParseException {

		Logger sipLogger = LogManager.getLogger("BLADE");
		sipLogger.setUseParentHandlers(false);
		sipLogger.addHandler(new ConsoleHandler() {
			{
				setOutputStream(System.out);
			}
		});
		sipLogger.setLevel(Level.FINEST);
		SettingsManager.setSipLogger(sipLogger);
		Callflow.setLogger(sipLogger);

		SimpleBlockConfig config1 = new SimpleBlockConfigSample();
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config1);

		System.out.println("Printing config1...");
		System.out.println(output);

		SipFactory sipFactory = new SipFactoryImpl(null, null);
		Callflow.setSipFactory(sipFactory);
		SettingsManager.setSipFactory(sipFactory);

//		URI from = sipFactory.createURI("sip:alice@vorpal.net:6060");
//		URI to = sipFactory.createURI("sip:carol@vorpal.net:7070");
//		URI from = sipFactory.createURI("sip:18165551234@att.com"); // works
		URI from = sipFactory.createURI("sip:+18165551234@att.com"); // works
		URI to = sipFactory.createURI("sip:19135550001@att.com");

		DummyRequest rqst1 = new DummyRequest("INVITE", from, to);

//		String forwardTo = forwardTo(config1, rqst1);
//
//		System.out.println("forwardTo: " + forwardTo);

	}
*/
	
	
}
