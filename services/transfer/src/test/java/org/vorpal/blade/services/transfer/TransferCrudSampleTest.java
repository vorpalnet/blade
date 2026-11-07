package org.vorpal.blade.services.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.crud.CrudHook;
import org.vorpal.blade.framework.v3.transfer.TransferSettings;

import com.fasterxml.jackson.databind.ObjectMapper;

/// The sample's `crud` section: a transfer to a 1916 number goes out with a
/// different `To`, and any other transfer is untouched.
class TransferCrudSampleTest {

	@BeforeEach
	void wire() {
		DetachedSipFactory factory = new DetachedSipFactory();
		SettingsManager.setSipFactory(factory);
		SettingsManager.setSipLogger(new CapturingLogger());
		Callflow.setSipFactory(factory);
		Callflow.setSipLogger(new CapturingLogger());
	}

	@AfterEach
	void unpublish() {
		CrudHook.publish(new TransferSettings());
	}

	/// The sample as it would load from its config file.
	private static TransferSettings loaded() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String json = mapper.writeValueAsString(new TransferSettingsSample());
		return mapper.readValue(json, TransferSettings.class);
	}

	private static DetachedRequest transferInvite(String target) throws Exception {
		DetachedRequest invite = new DetachedRequest(new DetachedApplicationSession("transfer"), "INVITE",
				"<sip:alice@example.com>;tag=a1", "<sip:" + target + "@carrier.example.com>");
		invite.setRequestURI(new DetachedSipFactory().createURI("sip:" + target + "@carrier.example.com"));
		return invite;
	}

	@Test
	void theSectionSurvivesTheConfigFile() throws Exception {
		assertNotNull(loaded().getCrud().getRuleSets().get("transfer-target"));
	}

	@Test
	void aTransferTo1916GetsTheNewTo() throws Exception {
		CrudHook.publish(loaded());
		DetachedRequest invite = transferInvite("19165550100");
		CrudHook.apply(invite);
		assertEquals("sip:19165550100@transfer.example.com", invite.getTo().getURI().toString());
	}

	@Test
	void anyOtherTransferIsUntouched() throws Exception {
		CrudHook.publish(loaded());
		DetachedRequest invite = transferInvite("18005550100");
		CrudHook.apply(invite);
		assertEquals("sip:18005550100@carrier.example.com", invite.getTo().getURI().toString());
	}
}
