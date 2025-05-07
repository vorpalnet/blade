package org.vorpal.blade.services.proxy.router;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.DummyRequest;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.ConfigHashMap;
import org.vorpal.blade.framework.v2.config.ConfigPrefixMap;
import org.vorpal.blade.framework.v2.config.RouterConfig;
import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.framework.v2.config.TranslationsMap;
import org.vorpal.blade.framework.v2.logging.LogManager;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.bea.wcp.sip.engine.server.SipFactoryImpl;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class ProxyRouterConfigSample extends RouterConfig implements Serializable {

	private static final long serialVersionUID = 1L;

	public ProxyRouterConfigSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);
		this.session = null;

//		this.defaultRoute = new Translation();
//		this.defaultRoute.setId("default");
//		this.defaultRoute.setRequestUri("sips:+110006@cvsxm-stg-occas.byoc.mypurecloud.com:5061;transport=tls");

		Selector caller = new Selector("caller", "From", SIP_ADDRESS_PATTERN, "${user}");
		caller.setDescription("caller's number");
		this.selectors.add(caller);

		Selector dialed = new Selector("dialed", "To", SIP_ADDRESS_PATTERN, "${user}");
		dialed.setDescription("dialed number");
		this.selectors.add(dialed);

		TranslationsMap callers = new ConfigHashMap();
		callers.setId("callers");
		callers.addSelector(caller);
		callers.setDescription("map of blocked callers");

		Translation t_alice = callers.createTranslation("alice").setId("alice").setRequestUri("sip:carol@vorpal.net");

		// if alice calls bob, shes get carol
		// if alice calls carol, she gets bob
		// if alice calls doug, it goes through
		Translation a1 = callers.createTranslation("alice");
		a1.setId("alice"); // don't think this is needed.
//		c1.setDescription("generally abusive; send all calls to voicemail unless the below numbers are dialed");
		List<TranslationsMap> a1Maps = new LinkedList<>();
		TranslationsMap a1PrefixMap = new ConfigPrefixMap();
		a1PrefixMap.addSelector(dialed);
		a1PrefixMap.createTranslation("bob").setId("bob").setRequestUri("sip:carol@vorpal.net");
		a1PrefixMap.createTranslation("carol").setId("carol").setRequestUri("sip:bob@vorpal.net");
		a1Maps.add(a1PrefixMap);
		a1.setList(a1Maps);

		// a real jerk from kansas city
		Translation c1 = callers.createTranslation("18165551234");
		c1.setId("18165551234"); // don't think this is needed.
		c1.setRequestUri("sip:voicemail");
		c1.setDescription("generally abusive; send all calls to voicemail unless the below numbers are dialed");
		List<TranslationsMap> c1Maps = new LinkedList<>();
		TranslationsMap c1PrefixMap = new ConfigPrefixMap();
		c1PrefixMap.addSelector(dialed);
		c1PrefixMap.createTranslation("1913").setId("1913").setRequestUri("sip:voicemail913")
				.setDescription("for (913) area code");
		c1PrefixMap.createTranslation("1913555").setId("1913555").setRequestUri("sip:voicemail913555")
				.setDescription("for (913) 555 NPA");
		c1PrefixMap.createTranslation("19135550001").setId("19135550001").setRequestUri("sip:voicemail9130001")
				.setDescription("for (913)555-001 NXX");
		c1Maps.add(c1PrefixMap);
		c1.setList(c1Maps);

		// a jilted lover from from sacramento
		Translation c2 = callers.createTranslation("12795555678");
		c2.setId("12795555678");
		c2.setDescription("harrases a specific employee");
		List<TranslationsMap> c2Maps = new LinkedList<>();
		TranslationsMap c2HashMap = new ConfigHashMap();
		c2HashMap.addSelector(dialed);
		c2HashMap.createTranslation("15305559876").setId("15305559876");
		c2Maps.add(c2HashMap);
		c2.setList(c2Maps);

		this.maps.add(callers);
		this.plan.add(callers);

	}

	public static void main(String[] args) throws ServletParseException, JsonProcessingException {

		RouterConfig configuration = new ProxyRouterConfigSample();
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);
		System.out.println(output);

	}

	public static void main2(String[] args) throws ServletParseException {

		SipFactory sipFactory = new SipFactoryImpl(null, null);
		Callflow.setSipFactory(sipFactory);
		SettingsManager.setSipFactory(sipFactory);

		Logger logger = LogManager.getLogger("BLADE");
		logger.setUseParentHandlers(false);
		logger.addHandler(new ConsoleHandler() {
			{
				setOutputStream(System.out);
			}
		});
		logger.setLevel(Level.FINEST);
		SettingsManager.setSipLogger(logger);
		Callflow.setLogger(logger);

		RouterConfig config = new ProxyRouterConfigSample();
		logger.logConfiguration(config);

		URI from;
		URI to;
		DummyRequest request;
		Translation t;

		from = sipFactory.createURI("sip:18165551234@vorpal.net");
		to = sipFactory.createURI("sip:19135559876@vorpal.net");
		request = new DummyRequest("INVITE", from, to);
		t = config.findTranslation(request);
		System.out.println("from=" + from + ", to=" + to + ", requestUri=" + t.getRequestUri());

		from = sipFactory.createURI("sip:18165551234@vorpal.net");
		to = sipFactory.createURI("sip:19995559876@vorpal.net");
		request = new DummyRequest("INVITE", from, to);
		t = config.findTranslation(request);
		System.out.println("from=" + from + ", to=" + to + ", requestUri=" + t.getRequestUri());

		from = sipFactory.createURI("sip:12795555678@vorpal.net");
		to = sipFactory.createURI("sip:19135559876@vorpal.net");
		request = new DummyRequest("INVITE", from, to);
		t = config.findTranslation(request);
		System.out.println("from=" + from + ", to=" + to + ", requestUri=" + t.getRequestUri());

		URI ruri = request.getRequestURI();
		System.out.println("ruri=" + ruri);

		String strUri = t.getRequestUri();
//		URI uri = SettingsManager.sipFactory.createURI(t.getRequestUri());

		URI uri = config.findRoute(request);
		System.out.println("uri = " + uri);

	}

}
