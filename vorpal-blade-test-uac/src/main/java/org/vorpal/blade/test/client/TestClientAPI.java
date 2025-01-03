package org.vorpal.blade.test.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.activation.DataSource;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.mail.util.MimeMessageParser;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;

@OpenAPIDefinition(info = @Info(title = "TestClient", version = "1", description = "a crude test client"))
@Path("api/v1")

public class TestClientAPI extends Callflow {
	private static final long serialVersionUID = 1L;
	private static Map<String, AsyncResponse> asyncResponses = new HashMap<>();

	@POST
	@Path("/connect")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Open a connection.")
	public void connect( //
			@Suspended AsyncResponse asyncResponse, //
			@Context UriInfo uriInfo, //
			@RequestBody(content = @Content(schema = @Schema(implementation = org.vorpal.blade.test.client.MessageRequest.class)), //
					description = "Message content", //
					required = true) org.vorpal.blade.test.client.MessageRequest message)
			throws ServletException, IOException //
	{

//		TestClientAPI.asyncResponse = asyncResponse;

		URI location = URI.create(uriInfo.getPath());

		// Create the SIP request
		SipFactory sipFactory = SettingsManager.getSipFactory();
		SipApplicationSession appSession = sipFactory.createApplicationSession();

		SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, message.fromAddress,
				message.toAddress);
		if (message.requestURI != null && message.requestURI.length() > 0) {
			bobRequest.setRequestURI(sipFactory.createURI(message.requestURI));
		}

		for (Header header : message.headers) {
			bobRequest.setHeader(header.name, header.value);
		}

//		bobRequest.setHeader("MIME-Version", "1.0");
//		bobRequest.setHeader("X-Acme-Call-ID", "");
//		bobRequest.setHeader("Require", "siprec");
//		bobRequest.setHeader("User-to-User",
//				"04FA08003918F5615DEFC4C8143030303537303633383931363333353436313830;encoding=hex");
//		bobRequest.setHeader("Cisco-Gucid", "00057063891633546180");
//		bobRequest.setContent(content, contentType);

		try {

//			Session session = Session.getDefaultInstance(new Properties());
//			MimeMessage msg = new MimeMessage(session, new ByteArrayInputStream(
//					Files.readAllBytes(Paths.get(this.getClass().getResource("request.txt").toURI()))));
//
//			MimeMessageParser parser = new MimeMessageParser(msg);
//			parser.parse();
//
//			sipLogger.info("msg.getContentType(): " + msg.getContentType());
//			sipLogger.info("msg.getContent():\n" + msg.getContent().toString());

//			bobRequest.setContent(parser.getMimeMessage().getContent().toString(),
//					parser.getMimeMessage().getContentType());

			Session session = Session.getDefaultInstance(new Properties());
			MimeMessage msg = new MimeMessage(session, new ByteArrayInputStream(
					Files.readAllBytes(Paths.get(this.getClass().getResource("siprecInvite.txt").toURI()))));

			MimeMessageParser parser = new MimeMessageParser(msg);
			parser.parse();
			List<DataSource> list = parser.getAttachmentList();
			sipLogger.finer("list.size(): " + list.size());

			MimeMultipart mm2 = (MimeMultipart) msg.getContent();
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			mm2.writeTo(bout);
			bobRequest.setContent(bout.toString(), mm2.getContentType());

			sipLogger.finer("bobRequest.getContentType(): " + bobRequest.getContentType());
			sipLogger.finer("bobRequest.getContent(): \n" + bobRequest.getContent());

		} catch (Exception e) {
			sipLogger.severe(e);
		}

		MessageResponse msgResponse = new MessageResponse();

		// Save the 'transient' AsyncResponse for later HTTP Response
		asyncResponses.put(appSession.getId(), asyncResponse);

		sipLogger.finer(bobRequest, "Sending this...\n" + bobRequest.toString());

		// Send the SIP request
		sendRequest(bobRequest, (bobResponse) -> {

			sipLogger.finer(bobResponse, "Received this...\n" + bobResponse.toString());

			msgResponse.responses.add(bobResponse.toString());

			if (successful(bobResponse)) {
				sendRequest(bobResponse.createAck());
			}

			if (!provisional(bobResponse)) {
				System.out.println("Final response: " + bobResponse.getStatus());
				sipLogger.finer(bobResponse, "Final response: " + bobResponse.getStatus());
				msgResponse.finalStatus = bobResponse.getStatus();
				Response httpResponse = Response.created(location).entity(msgResponse).build();
				asyncResponses.remove(appSession.getId()).resume(httpResponse);
			}

		});

	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	private static final String contentType = "multipart/mixed;boundary=unique-boundary-1";
	private static final String content = "--unique-boundary-1\n" + //
			"Content-Type: application/sdp\n" + //
			"\n" + //
			"v=0\n" + //
			"o=- 1274751 131744 IN IP4 10.173.164.254\n" + //
			"s=-\n" + //
			"c=IN IP4 10.173.164.220\n" + //
			"t=0 0\n" + //
			"m=audio 29028 RTP/AVP 18 101\n" + //
			"a=rtpmap:18 G729/8000\n" + //
			"a=fmtp:18 annexb=no\n" + //
			"a=rtpmap:101 telephone-event/8000\n" + //
			"a=fmtp:101 0-15\n" + //
			"a=maxptime:20\n" + //
			"a=label:369607687\n" + //
			"a=sendonly\n" + //
			"m=audio 42196 RTP/AVP 18 101\n" + //
			"a=fmtp:18 annexb=no\n" + //
			"a=rtpmap:101 telephone-event/8000\n" + //
			"a=ptime:20\n" + //
			"a=label:369607688\n" + //
			"a=sendonly\n" + //
			"\n" + //
			"--unique-boundary-1\n" + //
			"Content-Type: application/rs-metadata+xml\n" + //
			"Content-Disposition: recording-session\n" + //
			"\n" + //
			"<?xml version='1.0' encoding='UTF-8'?>\n" + //
			"<recording xmlns='urn:ietf:params:xml:ns:recording'>\n" + //
			"	<datamode>complete</datamode>\n" + //
			"	<session id=\"OegxHzZJT/5/85BANX/cAQ==\">\n" + //
			"		<associate-time>2021-10-06T13:48:53</associate-time>\n" + //
			"		<extensiondata xmlns:apkt=\"http:/acmepacket.com/siprec/extensiondata\">\n" + //
			"			<apkt:ucid>00391770615DEF95</apkt:ucid>\n" + //
			"			<apkt:callerOrig>false</apkt:callerOrig>\n" + //
			"		</extensiondata>\n" + //
			"	</session>\n" + //
			"	<participant id=\"WPoWZiYFREpVXNr/fkiliQ==\" session=\"OegxHzZJT/5/85BANX/cAQ==\">\n" + //
			"		<nameID aor=\"sip:5047022756@att.int\">\n" + //
			"			<name>5047022756</name>\n" + //
			"		</nameID>\n" + //
			"		<send>yf0uCNZyTGxuYHX16kS6ug==</send>\n" + //
			"		<associate-time>2021-10-06T13:48:53</associate-time>\n" + //
			"		<extensiondata xmlns:apkt=\"http://acmepacket.com/siprec/extensiondata\">\n" + //
			"			<apkt:callingParty>true</apkt:callingParty>\n" + //
			"		</extensiondata>\n" + //
			"	</participant>\n" + //
			"	<participant id=\"DP3FOVQ/SDRrBfZmMg6bSQ==\" session=\"OegxHzZJT/5/85BANX/cAQ==\">\n" + //
			"		<nameID aor=\"sip:1993620429@10.23.60.13\">\n" + //
			"			<name>1993620429</name>\n" + //
			"		</nameID>\n" + //
			"		<send>8K6cfVekRxdptkO9DWiBvQ==</send>\n" + //
			"		<associate-time>2021-10-06T13:49:39</associate-time>\n" + //
			"		<extensiondata xmlns:apkt=\"http://acmepacket.com/siprec/extensiondata\">\n" + //
			"			<apkt:callingParty>false</apkt:callingParty>\n" + //
			"		</extensiondata>\n" + //
			"	</participant>\n" + //
			"	<stream id=\"yf0uCNZyTGxuYHX16kS6ug==\" session=\"OegxHzZJT/5/85BANX/cAQ==\">\n" + //
			"		<label>369607687</label>\n" + //
			"		<mode>separate</mode>\n" + //
			"		<associate-time>2021-10-06T13:49:39</associate-time>\n" + //
			"	</stream>\n" + //
			"	<stream id=\"8K6cfVekRxdptkO9DWiBvQ==\" session=\"OegxHzZJT/5/85BANX/cAQ==\">\n" + //
			"		<label>369607688</label>\n" + //
			"		<mode>separate</mode>\n" + //
			"		<associate-time>2021-10-06T13:49:39</associate-time>\n" + //
			"	</stream>\n" + //
			"</recording>\n" + //
			"--unique-boundary-1--\n";

}
