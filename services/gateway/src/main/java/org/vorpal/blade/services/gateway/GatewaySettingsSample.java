package org.vorpal.blade.services.gateway;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// The default/sample config written on first deploy: two outbound trunks, one credentialed
/// trunk kept up with digest REGISTER, one IP-authenticated carrier that needs no REGISTER.
/// Credentials are placeholders; set the real ones (and let the Configurator encrypt the
/// password) via the console.
public class GatewaySettingsSample extends GatewaySettings implements Serializable {
	private static final long serialVersionUID = 1L;

	public GatewaySettingsSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.INFO);

		VirtualGateway carrier = new VirtualGateway();
		carrier.setName("carrier-primary");
		carrier.setTransport("tcp");
		carrier.setRegistrarDomain("sip.carrier.example.com");
		// outboundInterface is left unset: single-interface engines originate on the
		// container's own SIP channel. Set it only on a multi-homed engine, to the
		// advertised host of the channel this trunk should send from.
		RegisterDigestStyle digest = new RegisterDigestStyle();
		digest.setUserId("15551234567");
		digest.setAuthName("00000000");
		digest.setPassword(""); // set via the Configurator; stored {CLEARTEXT}->{AES}
		carrier.setStyle(digest);

		VirtualGateway ipauth = new VirtualGateway();
		ipauth.setName("carrier-b-ipauth");
		ipauth.setTransport("udp");
		ipauth.setRegistrarDomain("sip.example-carrier.net");
		ipauth.setStyle(new IpAuthStyle());

		getGateways().add(carrier);
		getGateways().add(ipauth);
	}
}
