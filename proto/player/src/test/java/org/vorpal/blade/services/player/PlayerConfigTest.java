package org.vorpal.blade.services.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Unit tests for the player app's config model. The media path itself (offer/play/record) needs a
/// live OCCAS + 309 driver + Kurento and is verified at deploy time; these cover the serializable
/// configuration the Configurator drives.
public class PlayerConfigTest {

	private static final ObjectMapper M = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	@Test
	public void sampleNamesADriverAndMedia() {
		PlayerSettings s = new PlayerSettingsSample();
		assertEquals("org.vorpal.gryphon.kurento", s.getDriverName());
		assertEquals("ws://localhost:8888/kurento", s.getDriverProperties().get("kurento.ws.url"));
		assertNotNull("sample plays something", s.getMediaUri());
		assertFalse(s.isLoop());
		assertFalse(s.isRecord());
	}

	@Test
	public void settingsRoundTrip() throws Exception {
		PlayerSettings s = new PlayerSettings();
		s.setDriverName("some.driver");
		s.getDriverProperties().put("kurento.ws.url", "ws://media:8888/kurento");
		s.setMediaUri("http://media.example.com/greeting.wav");
		s.setLoop(true);
		s.setRecord(true);
		s.setRecordUri("file:///tmp/rec.webm");

		PlayerSettings back = M.readValue(M.writeValueAsString(s), PlayerSettings.class);

		assertEquals("some.driver", back.getDriverName());
		assertEquals("ws://media:8888/kurento", back.getDriverProperties().get("kurento.ws.url"));
		assertEquals("http://media.example.com/greeting.wav", back.getMediaUri());
		assertTrue(back.isLoop());
		assertTrue(back.isRecord());
		assertEquals("file:///tmp/rec.webm", back.getRecordUri());
	}
}
