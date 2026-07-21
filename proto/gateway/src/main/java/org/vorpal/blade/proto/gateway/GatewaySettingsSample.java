package org.vorpal.blade.proto.gateway;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// The default/sample config written on first deploy: two virtual gateways on distinct
/// Contact IPs — one Flowroute trunk kept up with digest REGISTER, one IP‑authenticated
/// carrier that needs no REGISTER. Credentials are placeholders; set the real ones (and
/// let the Configurator encrypt the password) via the console.
public class GatewaySettingsSample extends GatewaySettings implements Serializable {
	private static final long serialVersionUID = 1L;

	public GatewaySettingsSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.INFO);

		VirtualGateway flowroute = new VirtualGateway();
		flowroute.setName("flowroute-primary");
		flowroute.setContactHost("203.0.113.10"); // example — must be one of the container's SIP channels
		flowroute.setContactPort(5060);
		flowroute.setTransport("tcp");
		flowroute.setRegistrarDomain("us-east-nj.sip.flowroute.com");
		RegisterDigestStyle digest = new RegisterDigestStyle();
		digest.setUserId("15551234567");
		digest.setAuthName("00000000");
		digest.setPassword(""); // set via the Configurator; stored {CLEARTEXT}->{AES}
		flowroute.setStyle(digest);

		VirtualGateway ipauth = new VirtualGateway();
		ipauth.setName("carrier-b-ipauth");
		ipauth.setContactHost("203.0.113.11");
		ipauth.setContactPort(5060);
		ipauth.setTransport("udp");
		ipauth.setRegistrarDomain("sip.example-carrier.net");
		ipauth.setStyle(new IpAuthStyle());

		getGateways().add(flowroute);
		getGateways().add(ipauth);
	}
}
