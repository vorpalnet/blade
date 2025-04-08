package org.vorpal.blade.services.proxy.blocker;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.DummyRequest;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.LogManager;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.config.AttributeSelector;
import org.vorpal.blade.framework.v3.config.Translation;
import org.vorpal.blade.framework.v3.config.TranslationsMap;
import org.vorpal.blade.framework.v3.config.maps.ConfigHashMap;

import com.bea.wcp.sip.engine.server.SipFactoryImpl;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class ProxyBlockConfigSample extends ProxyBlockConfig {
	private static final long serialVersionUID = 1L;

	public final static String NORMALIZE_FROM_PATTERN = "^(?:\"?(?<fromName>.*?)\"?\\s*)[<]*(?<fromProto>sips?):(?:(?<fromUser>.*)@)*(?<fromHost>[^:;>]*)(?:[:](?<fromPort>[0-9]+))*(?:[;](?<fromUriparams>[^>]*))*[>]*[;]*(?<FromAddrparams>.*)$";
	public final static String NORMALIZE_FROM_EXPRESSION = "\\L${fromUser}";

	public final static String NORMALIZE_TO_PATTERN = "^(?:\"?(?<toName>.*?)\"?\\s*)[<]*(?<toProto>sips?):(?:(?<toUser>.*)@)*(?<toHost>[^:;>]*)(?:[:](?<toPort>[0-9]+))*(?:[;](?<toUriparams>[^>]*))*[>]*[;]*(?<toAddrparams>.*)$";
	public final static String NORMALIZE_TO_EXPRESSION = "\\L${toUser}";

//	public final static String FROM_ADDRESS_PATTERN = "^(?:\"?(?<fromName>.*?)\"?\\s*)[<]*(?<fromProto>sips?):(?:(?<fromUser>.*)@)*(?<fromHost>[^:;>]*)(?:[:](?<fromPort>[0-9]+))*(?:[;](?<fromUriparams>[^>]*))*[>]*[;]*(?<FromAddrparams>.*)$";
//	public final static String TO_ADDRESS_PATTERN = "^(?:\"?(?<toName>.*?)\"?\\s*)[<]*(?<toProto>sips?):(?:(?<toUser>.*)@)*(?<toHost>[^:;>]*)(?:[:](?<toPort>[0-9]+))*(?:[;](?<toUriparams>[^>]*))*[>]*[;]*(?<toAddrparams>.*)$";

	public ProxyBlockConfigSample() {
		this.defaultRoute = null;
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINEST);

		AttributeSelector fromSel = new AttributeSelector();
		fromSel.setId("fromSel");
		fromSel.setDescription("Match on ANI, the phone number of the calling party.");
		fromSel.setAttribute("From");
		fromSel.setPattern(NORMALIZE_FROM_PATTERN);
		fromSel.setExpression("${fromUser}");
		Map<String, String> additionalExpressions = new HashMap<>();
		additionalExpressions.put("fromPort", "5060"); // default value
		additionalExpressions.put("toPort", "5060"); // default value
		fromSel.setAdditionalExpressions(additionalExpressions);

		this.selectors.add(fromSel);

		AttributeSelector toSel = new AttributeSelector();
		toSel.setId("toSel");
		toSel.setDescription("Match on DID, the dialed number.");
		toSel.setAttribute("To");
		toSel.setPattern(NORMALIZE_TO_PATTERN);
		toSel.setExpression("${toUser}");
		toSel.setAdditionalExpressions(additionalExpressions);

		this.selectors.add(toSel);

		TranslationsMap<Endpoints> t1 = new ConfigHashMap<Endpoints>();
		t1.setId("t1");
		t1.addSelector(fromSel);
		System.out.println("t1.selectors.size()=" + t1.getSelectors().size());

		Translation<Endpoints> alice = new Translation<Endpoints>();
		alice.setId("alice");
		alice.setRoute(new Endpoints("sip:doug@vorpal.net"));
		t1.put("alice", alice);

//		Translation<Endpoints> alice = t1.createTranslation("alice").setRoute(new Endpoints("sip:doug@vorpal.net"));

		TranslationsMap<Endpoints> aliceMap = new ConfigHashMap<>();
		System.out.println("aliceMap.selectors.size()=" + aliceMap.getSelectors().size());
//		TranslationsMap<Endpoints> aliceMap = new ConfigPrefixMap<>();
		aliceMap.setId("aliceMap");
		aliceMap.addSelector(toSel);
		System.out.println("aliceMap.selectors.size()=" + aliceMap.getSelectors().size());

		alice.addTranslationsMap(aliceMap);

		Translation<Endpoints> tBob = aliceMap.createTranslation("bob").setDesc("this is bob")
				.setRoute(new Endpoints("sip:carol@vorpal.net"));
		System.out.println("tBob.getRoute()=" + tBob.getRoute());
		System.out.println("tBob.getRoute().getRequestURIs()=" + tBob.getRoute().getRequestURIs());
		aliceMap.createTranslation("carol").setDesc("this is bob").setRoute(new Endpoints("sip:bob@vorpal.net"));

		this.maps.add(t1);

		this.plans.add(t1);

	}

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

		ProxyBlockConfig config1 = new ProxyBlockConfigSample();
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config1);
		System.out.println("config1=\n" + output);

		ProxyBlockConfig config2 = mapper.readValue(output, ProxyBlockConfig.class);
		String output2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config2);
		System.out.println("config2=\n" + output2);

		SipFactory sipFactory = new SipFactoryImpl(null, null);
		Callflow.setSipFactory(sipFactory);
		SettingsManager.setSipFactory(sipFactory);

		URI from = sipFactory.createURI("sip:blice@vorpal.net");
		URI to = sipFactory.createURI("sip:bob@vorpal.net");

		DummyRequest rqst1 = new DummyRequest("INVITE", from, to);

		Translation<Endpoints> t = config2.findTranslation(rqst1);

		System.out.println("Results...");

		System.out.println("t=" + t);
		System.out.println("t.getRoute()=" + t.getRoute());
		System.out.println("t.getRoute().getRequestURIs()=" + t.getRoute().getRequestURIs());
		System.out.println("t.getRoute().getRequestURIs().get(0)=" + t.getRoute().getRequestURIs().get(0));

	}

}
