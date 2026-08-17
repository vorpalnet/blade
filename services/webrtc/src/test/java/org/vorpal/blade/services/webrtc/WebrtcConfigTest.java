package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The serializable configuration the Configurator drives. The media path itself needs a live OCCAS
/// and a JSR-309 driver and is verified at deploy time; what is checkable here is that an operator's
/// edits survive the round trip and that the defaults are the safe ones.
public class WebrtcConfigTest {

	private static final ObjectMapper M = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	@Test
	public void driverSettingsRoundTrip() throws Exception {
		// These moved out of servlet context init-parameters, where an operator could not reach
		// them without repacking the WAR. Losing them in serialization would put the gateway back
		// to having no media plane, silently.
		WebrtcSettings s = new WebrtcSettings();
		s.setDriverName("org.vorpal.gryphon.kurento");
		s.getDriverProperties().put("kurento.ws.url", "ws://media:8888/kurento");
		s.getDriverProperties().put("external.ipv4", "203.0.113.10");

		WebrtcSettings back = M.readValue(M.writeValueAsString(s), WebrtcSettings.class);

		assertEquals("org.vorpal.gryphon.kurento", back.getDriverName());
		assertEquals("ws://media:8888/kurento", back.getDriverProperties().get("kurento.ws.url"));
		assertEquals("203.0.113.10", back.getDriverProperties().get("external.ipv4"));
	}

	@Test
	public void driverPropertiesAreNeverNull() {
		// The servlet reads these at startup without a null check of its own; a config file that
		// omits the block, or names it null, must not take the media plane down with it.
		WebrtcSettings s = new WebrtcSettings();
		s.setDriverProperties(null);

		assertNotNull(s.getDriverProperties());
		assertTrue(s.getDriverProperties().isEmpty());
	}

	@Test
	public void anEmptyDriverNameMeansWhicheverIsInstalled() throws Exception {
		// Blank is the usual case — one driver, no need to name it. It must survive as blank rather
		// than becoming a driver name nothing matches.
		WebrtcSettings back = M.readValue(M.writeValueAsString(new WebrtcSettings()), WebrtcSettings.class);

		assertEquals(null, back.getDriverName());
		assertNotNull(back.getDriverProperties());
	}

	@Test
	public void theSampleShipsAuthenticationOnAndAnalyticsOff() {
		// Both defaults are deliberate and opposite: authentication on, because a gateway that
		// places calls for strangers should not be a quiet default; analytics off, because call
		// detail records should not start accumulating because nobody said not to.
		WebrtcSettings s = new WebrtcSettingsSample();

		assertTrue(s.getJwt().isEnabled());
		assertEquals("urn:blade:phone", s.getJwt().getIssuer());
		assertNotNull("selectors ship configured so turning analytics on says something", s.getAnalytics());
		assertEquals(Boolean.FALSE, s.getAnalytics().isEnabled());
	}

	@Test
	public void registerExpiresRefusesNonsense() {
		// The refresh timer derives its period from this; a zero or negative value would produce a
		// timer that fires continuously.
		WebrtcSettings s = new WebrtcSettings();

		s.setRegisterExpiresSeconds(0);
		assertEquals(Integer.valueOf(3600), s.getRegisterExpiresSeconds());

		s.setRegisterExpiresSeconds(null);
		assertEquals(Integer.valueOf(3600), s.getRegisterExpiresSeconds());

		s.setRegisterExpiresSeconds(120);
		assertEquals(Integer.valueOf(120), s.getRegisterExpiresSeconds());
	}
}
