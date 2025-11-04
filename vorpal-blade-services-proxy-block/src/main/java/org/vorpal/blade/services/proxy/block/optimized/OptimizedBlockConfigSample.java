package org.vorpal.blade.services.proxy.block.optimized;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.DummyRequest;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.LogManager;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.bea.wcp.sip.engine.server.SipFactoryImpl;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class OptimizedBlockConfigSample extends OptimizedBlockConfig {

	private static final long serialVersionUID = 1L;
//	public final static String NORMALIZE_FROM_PATTERN = "^(?:\"?(?<fromName>.*?)\"?\\s*)[<]*(?<fromProto>sips?):(?:(?<fromUser>.*)@)*(?<fromHost>[^:;>]*)(?:[:](?<fromPort>[0-9]+))*(?:[;](?<fromUriparams>[^>]*))*[>]*[;]*(?<FromAddrparams>.*)$";
	public final static String NORMALIZE_FROM_PATTERN = "^(?:\"?(?<fromName>.*?)\"?\\s*)[<]*(?<fromProto>sips?):(?:[+1]*(?<fromUser>.*)@)*(?<fromHost>[^:;>]*)(?:[:](?<fromPort>[0-9]+))*(?:[;](?<fromUriparams>[^>]*))*[>]*[;]*(?<FromAddrparams>.*)$";
	public final static String NORMALIZE_FROM_EXPRESSION = "\\L${fromUser}";

	public final static String NORMALIZE_TO_PATTERN = "^(?:\"?(?<toName>.*?)\"?\\s*)[<]*(?<toProto>sips?):(?:(?<toUser>.*)@)*(?<toHost>[^:;>]*)(?:[:](?<toPort>[0-9]+))*(?:[;](?<toUriparams>[^>]*))*[>]*[;]*(?<toAddrparams>.*)$";
	public final static String NORMALIZE_TO_EXPRESSION = "\\L${toUser}";

	public OptimizedBlockConfigSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINE);

		this.fromSelector = new AttributeSelector();
		fromSelector.setId("fromSel");
		fromSelector.setDescription("Match on ANI, the phone number of the calling party.");
		fromSelector.setAttribute("From");
		fromSelector.setPattern(NORMALIZE_FROM_PATTERN);
		fromSelector.setExpression("${fromUser}");
		Map<String, String> additionalFromExpressions = new HashMap<>();
		additionalFromExpressions.put("fromPort", "5060"); // default value
		fromSelector.setAdditionalExpressions(additionalFromExpressions);

		this.toSelector = new AttributeSelector();
		toSelector.setId("toSel");
		toSelector.setDescription("Match on DID, the dialed number.");
		toSelector.setAttribute("To");
		toSelector.setPattern(NORMALIZE_TO_PATTERN);
		toSelector.setExpression("${toUser}");
//		Map<String, String> additionalToExpressions = new HashMap<>();
//		additionalToExpressions.put("toPort", "5060"); // default value
//		toSelector.setAdditionalExpressions(additionalToExpressions);

//	public final static String NORMALIZE_TO_PATTERN = "^(?:\"?(?<toName>.*?)\"?\\s*)[<]*(?<toProto>sips?):(?:(?<toUser>.*)@)*(?<toHost>[^:;>]*)(?:[:](?<toPort>[0-9]+))*(?:[;](?<toUriparams>[^>]*))*[>]*[;]*(?<toAddrparams>.*)$";

		this.defaultRoute = new OptimizedTranslation();
		this.defaultRoute.forwardTo = new LinkedList<>();
		this.defaultRoute.forwardTo.add("${toProto}:${toUser}@${toHost}:${fromPort};${toUriparams}");

		OptimizedTranslation n8165551234 = new OptimizedTranslation();
		this.callingNumbers.put("8165551234", n8165551234);
		n8165551234.forwardTo = new LinkedList<>();
		n8165551234.forwardTo.add("sip:voicemail@vorpal.net");

//		Dialed dialed8165551234 = new Dialed();
//		dialed8165551234.forwaredTo = new LinkedList<>();
//		dialed8165551234.forwaredTo.add("sip:voicemail@vorpal.net");

		OptimizedTranslation stAlice = new OptimizedTranslation();
		this.callingNumbers.put("alice", stAlice);

		List<String> aliceList = new LinkedList<>();
		aliceList.add("sip:doug@vorpal.net");
		stAlice.forwardTo = aliceList;

		stAlice.dialedNumbers = new HashMap<>();

		OptimizedDialed bobDialed = new OptimizedDialed();
		bobDialed.forwardTo = new LinkedList<>();
		bobDialed.forwardTo.add("sip:carol@vorpal.net:${fromPort}");

		stAlice.dialedNumbers.put("bob", bobDialed);

		OptimizedDialed carolDialed = new OptimizedDialed();
		carolDialed.forwardTo = new LinkedList<>();
		carolDialed.forwardTo.add("sip:bob@vorpal.net:${fromPort}");

		stAlice.dialedNumbers.put("carol", carolDialed);

	}
	/*
	 * public static void main(String[] args) throws JsonProcessingException,
	 * ServletParseException {
	 * 
	 * Logger sipLogger = LogManager.getLogger("BLADE");
	 * sipLogger.setUseParentHandlers(false); sipLogger.addHandler(new
	 * ConsoleHandler() { { setOutputStream(System.out); } });
	 * sipLogger.setLevel(Level.FINEST); SettingsManager.setSipLogger(sipLogger);
	 * Callflow.setLogger(sipLogger);
	 * 
	 * OptimizedBlockConfig config1 = new OptimizedBlockConfigSample(); ObjectMapper
	 * mapper = new ObjectMapper();
	 * mapper.setSerializationInclusion(Include.NON_NULL);
	 * mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS); String output =
	 * mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config1);
	 * 
	 * System.out.println("Printing config1..."); System.out.println(output);
	 * 
	 * SipFactory sipFactory = new SipFactoryImpl(null, null);
	 * Callflow.setSipFactory(sipFactory);
	 * SettingsManager.setSipFactory(sipFactory);
	 * 
	 * // URI from = sipFactory.createURI("sip:alice@vorpal.net:6060"); // URI to =
	 * sipFactory.createURI("sip:carol@vorpal.net:7070"); // URI from =
	 * sipFactory.createURI("sip:18165551234@att.com"); // works URI from =
	 * sipFactory.createURI("sip:+18165551234@att.com"); // works URI to =
	 * sipFactory.createURI("sip:19135550001@att.com");
	 * 
	 * DummyRequest rqst1 = new DummyRequest("INVITE", from, to);
	 * 
	 * String forwardTo = forwardTo(config1, rqst1);
	 * 
	 * System.out.println("forwardTo: " + forwardTo);
	 * 
	 * }
	 */

}
