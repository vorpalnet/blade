package org.vorpal.blade.test.b2bua;

import java.io.Serializable;
import java.util.HashMap;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.KeepAliveParameters;
import org.vorpal.blade.framework.config.SessionParameters;
import org.vorpal.blade.framework.config.SessionParametersDefault;
import org.vorpal.blade.framework.logging.LogParametersDefault;

public class ConfigSample extends SampleB2buaConfig implements Serializable {

	public ConfigSample() {

		try {
			this.logging = new LogParametersDefault();
			this.session = new SessionParametersDefault();

			this.address = Callflow.getSipFactory().createAddress("Alice <sip:alice@vorpal.net>");
//			this.ipv4Address = new IPAddressString("192.168.1.1").getAddress().toIPv4();
//			this.ipv6Address = new IPAddressString("2605:a601:aeba:6500:468:ceac:53f1:5854").getAddress().toIPv6();
			this.uri = Callflow.getSipFactory().createURI("sip:bob@vorpal.net");
			this.value1 = "value #1";
			this.value2 = "value #2";

			map = new HashMap<>();
			map.put("one", "this is value one");
			map.put("two", "this is value two");

			this.setLogging(new LogParametersDefault());

			SessionParameters sp = new SessionParameters();
			sp.setExpiration(900);

			KeepAliveParameters kap = new KeepAliveParameters();
			kap.setMinSE(90);
			kap.setSessionExpires(180);
			kap.setStyle(KeepAliveParameters.KeepAlive.UPDATE);

			sp.setKeepAlive(kap);
			this.setSession(sp);

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
