package org.vorpal.blade.test.config;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;

import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.DummyRequest;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.ConfigHashMap;
import org.vorpal.blade.framework.v2.config.KeepAliveParametersDefault;
import org.vorpal.blade.framework.v2.config.RouterConfig;
import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.config.TranslationsMap;
import org.vorpal.blade.framework.v2.logging.LogManager;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.bea.wcp.sip.engine.server.SipFactoryImpl;

public class ProxyRouterConfigVoxai extends RouterConfig implements Serializable {

	private static final long serialVersionUID = 1L;

	public final static String SIP_ADDRESS_PATTERN = "^(?:\"?(?<name>.*?)\"?\\s*)[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)$";

	public ProxyRouterConfigVoxai() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINEST);
		this.session = new SessionParameters();

		this.session.expiration = 60;
		this.session.keepAlive = new KeepAliveParametersDefault();
		this.session.sessionSelectors = new LinkedList<>();

		AttributeSelector requestUriSelector = new AttributeSelector();
		requestUriSelector.setAttribute("Request-URI");
		requestUriSelector.setPattern(SIP_ADDRESS_PATTERN);
		requestUriSelector.setId("requestUriSelector");
		requestUriSelector.setDescription("parse the SIP requestURI");
		this.session.sessionSelectors.add(requestUriSelector);
		
		
		
		
		
		

		Selector sbcLocation = new Selector("sbcLocation", "User-to-User",
				"^.*SBCLocation=[ ]*(?<SBCLocation>[^;]*);.*$", "${SBCLocation}");
		sbcLocation.setDescription("the SBC Location");
		this.selectors.add(sbcLocation);

		TranslationsMap callers = new ConfigHashMap();
		callers.setId("callers");
		callers.addSelector(sbcLocation);
		callers.setDescription("map of sbc locations");

		callers.createTranslation("cvsxm-stg-tx")//
				.setId("cvsxm-stg-tx") //
				.setRequestUri("${proto}:${user}@10.119.127.147:${port};${uriparams}");

		callers.createTranslation("cvsxm_stg_nj")//
				.setId("cvsxm-stg-nj") //
				.setRequestUri("${proto}:${user}@10.113.63.35:${port};${uriparams}");

		this.maps.add(callers);
		this.plan.add(callers);

	}

	public static void main(String[] args) throws ServletParseException {

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

		RouterConfig config = new ProxyRouterConfigVoxai();
		logger.logConfiguration(config);

		URI sipUri;
		Address from, to;

		DummyRequest request;

		sipUri = sipFactory.createURI("sip:18009254733@gensip.occas.cv300-telematics.net:5061;transport=tls");
		from = sipFactory.createAddress("\"SiriusXM Connect\" <sip:19727536200@52.203.12.137>;tag=HDWp7BI");
		to = sipFactory.createAddress("\"Toll Free\" <sip:18009254733@gensip.occas.cv300-telematics.net>");
		request = new DummyRequest("INVITE", from, to);
		request.setRequestURI(sipUri);

		request.setHeader("User-to-User",
				"00ATX-GeoLocation= 43.980570~-92.462230 ;ATX-Reference-ID= -92.462230 ;Lang= en ;TRID= 911 ;VIN= JTJDZKCA1J2001376 ;SPIRefID= 11223344 ;SBCLocation= cvsxm-stg-tx;encoding=ascii;purpose=isdn-uui;content=isdn-uui");

		System.out.println("invoking config.findTranslation()...");

//		Translation t = config.findTranslation(request);
//		System.out.println("t=" + t);

		URI uri = config.findRoute(request);
		System.out.println("uri=" + uri);

//		System.out.println("requestUri=" + t.getRequestUri());
		System.out.println(14);

//		from = sipFactory.createURI("sip:18165551234@vorpal.net");
//		to = sipFactory.createURI("sip:19995559876@vorpal.net");
//		request = new DummyRequest("INVITE", from, to);
//		t = config.findTranslation(request);
//		System.out.println("from=" + from + ", to=" + to + ", requestUri=" + t.getRequestUri());
//
//		from = sipFactory.createURI("sip:12795555678@vorpal.net");
//		to = sipFactory.createURI("sip:19135559876@vorpal.net");
//		request = new DummyRequest("INVITE", from, to);
//		t = config.findTranslation(request);
//		System.out.println("from=" + from + ", to=" + to + ", requestUri=" + t.getRequestUri());
//
//		URI ruri = request.getRequestURI();
//		System.out.println("ruri=" + ruri);
//
//		URI uri = config.findRoute(request);
//		System.out.println("uri = " + uri);

	}

}
