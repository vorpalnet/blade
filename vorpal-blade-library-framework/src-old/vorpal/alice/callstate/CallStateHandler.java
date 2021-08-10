package vorpal.alice.callstate;

import java.io.Serializable;
import java.util.logging.Level;

import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

//import com.bea.wcp.sip.engine.server.header.HeaderUtils;

import vorpal.alice.logging.Logger;
import vorpal.alice.logging.Logger.Direction;

@Deprecated
@javax.servlet.sip.annotation.SipListener
public class CallStateHandler implements Serializable, SipServletListener {
	protected static final long serialVersionUID = 1L;
	protected static Logger logger = null;
	public final static String CALL_STATE_HANDLER = "CALL_STATE_HANDLER";

	public enum SipMethod {
		INVITE, ACK, BYE, CANCEL, OPTIONS, REGISTER, PRACK, SUBSCRIBE, NOTIFY, PUBLISH, INFO, REFER, MESSAGE, UPDATE
	}

	// public final static String CALL_STATE_HANDLER = "CALL_STATE_HANDLER";
	// public final static String ACTIVE_VSRP_SESSION_ID =
	// "ACTIVE_VSRP_SESSION_ID";
	// public final static String INACTIVE_VSRP_SESSION_ID =
	// "INACTIVE_VSRP_SESSION_ID";
	// public final static String SBC_SESSION_ID = "SBC_SESSION_ID";

	public int state = 1;

	public CallStateHandler() {
	}

	public CallStateHandler(CallStateHandler that) {
		this.state = that.state;
	}

	public void processEvent(SipServletRequest request, SipServletResponse response, ServletTimer timer) throws Exception {
	};

	@Override
	public void servletInitialized(SipServletContextEvent event) {
		if (logger == null) {
			logger = Logger.getLogger(event.getServletContext());
		}
	};

	protected String getPrintableName() {
		String name = this.getClass().getSimpleName();
		name = name.concat(" ");
		name = name.concat(Integer.toString(this.state));

		int spaces = 20 - name.length();
		for (int i = 0; i < spaces; i++) {
			name = name.concat(" ");
		}
		return name;
	}

	// public static String hexHash(SipApplicationSession appSession) {
	// return Logger.hexHash(appSession);
	// }

	// public static String hexHash(SipApplicationSession appSession) {
	// int hash = Math.abs(appSession.getId().hashCode()) % 0xFFFF;
	// return "[" + String.format("%04X", hash) + ":----]";
	// }

	// public static String hexHash(SipSession sipSession) {
	// return Logger.hexHash(sipSession);
	// }

	// public static String hexHash(SipSession sipSession) {
	// int hash1 =
	// Math.abs(sipSession.getApplicationSession().getId().hashCode()) % 0xFFFF;
	// int hash2 = Math.abs(sipSession.getId().hashCode()) % 0xFFFF;
	// return "[" + String.format("%04X", hash1) + ":" + String.format("%04X",
	// hash2) + "]";
	// }

	// public static String hexHash(SipApplicationSession appSession) {
	// return "[" +
	// Integer.toHexString(Math.abs(appSession.getId().hashCode())).toUpperCase()
	// + "]";
	// }

	// public static String hexHash(SipSession sipSession) {
	// return "[" +
	// Integer.toHexString(Math.abs(sipSession.getId().hashCode())).toUpperCase()
	// + "]";
	// }

	// public static String hexHash(SipServletMessage message) {
	// String output = "[";
	// output +=
	// Integer.toHexString(Math.abs(message.getApplicationSession().getId().hashCode())).toUpperCase();
	// output += ":";
	// output +=
	// Integer.toHexString(Math.abs(message.getSession().getId().hashCode())).toUpperCase();
	// output += "]";
	// return output;
	// }

	// public void printOutboundMessage(SipServletMessage message) throws
	// UnsupportedEncodingException, IOException {
	//
	// logger.fine(message.getSession().getState().toString());
	//
	// if (logger.isLoggable(Level.FINE)) {
	//
	// try {
	//
	// if (message != null) {
	//
	// String event = message.getHeader("Event");
	// if (event != null && event.equals("refer")) {
	// event += " " + new String((byte[]) message.getContent()).trim();
	// }
	// if (event == null) {
	// event = (message.getContent() != null) ? "w/ SDP" : "w/o SDP";
	// }
	//
	// if (message instanceof SipServletRequest) {
	// SipServletRequest rqst = (SipServletRequest) message;
	//
	// if (rqst.getMethod().equals("MESSAGE")) {
	// ObjectMapper objectMapper = new ObjectMapper();
	// JsonNode rootNode = objectMapper.readTree(rqst.getContent().toString());
	// event = rootNode.path("event").asText();
	// event += " " + rootNode.path("status").asInt();
	// event += " " + rootNode.path("reason").asText();
	// }
	//
	// String output = getPrintableName() + " " + ((SipURI)
	// rqst.getTo().getURI()).getHost() + " <-- "
	// + rqst.getMethod() + " " + event + ", " + hexHash(message) + " "
	// + rqst.getSession().getState().toString();
	//
	// logger.fine(output);
	//
	// } else {
	// SipServletResponse rspn = (SipServletResponse) message;
	// String output = getPrintableName() + " " + ((SipURI)
	// rspn.getFrom().getURI()).getHost()
	// + " <-- " + rspn.getStatus() + " " + rspn.getReasonPhrase() + " ("
	// + rspn.getMethod().toLowerCase() + ") " + event + ", " + hexHash(message)
	// + " "
	// + rspn.getSession().getState().toString();
	//
	// logger.fine(output);
	//
	// }
	// }
	//
	// } catch (Exception e) {
	// logger.fine("logging error: " + e.getMessage());
	// System.out.println("logging error: " + e.getMessage());
	// }
	//
	// }
	//
	// }

	// public void printInboundMessage(SipServletMessage message) throws
	// UnsupportedEncodingException, IOException {
	// if (logger.isLoggable(Level.FINE)) {
	//
	// try {
	//
	// String event = message.getHeader("Event");
	// if (event != null && event.equals("refer")) {
	// event += " " + new String((byte[]) message.getContent()).trim();
	// }
	// if (event == null) {
	// event = (message.getContent() != null) ? "w/ SDP" : "w/o SDP";
	// }
	//
	// if (message instanceof SipServletRequest) {
	// SipServletRequest rqst = (SipServletRequest) message;
	// String output = null;
	//
	// output = getPrintableName() + " " + ((SipURI)
	// rqst.getFrom().getURI()).getHost() + " --> "
	// + rqst.getMethod() + " " + event + ", " + hexHash(message) + " "
	// + rqst.getSession().getState().toString();
	//
	// logger.fine(output);
	// } else {
	// SipServletResponse rspn = (SipServletResponse) message;
	// String output = getPrintableName() + " " + ((SipURI)
	// rspn.getTo().getURI()).getHost() + " --> "
	// + rspn.getStatus() + " " + rspn.getReasonPhrase() + " (" +
	// rspn.getMethod().toLowerCase()
	// + ") " + event + ", " + hexHash(message) + " " +
	// rspn.getSession().getState().toString();
	//
	// logger.fine(output);
	//
	// }
	//
	// } catch (Exception e) {
	// logger.fine("logging error: " + e.getMessage());
	// System.out.println("logging error: " + e.getMessage());
	// }
	//
	// }
	// }

	// public void printTimer(ServletTimer timer) {
	// if (logger.isLoggable(Level.FINE)) {
	//
	// // try {
	//
	// String output = getPrintableName() + " " + " timer id: " + timer.getId()
	// + ", time remaining: "
	// + (int) timer.getTimeRemaining() / 1000 + ", " +
	// hexHash(timer.getApplicationSession());
	//
	// logger.fine(output);
	//
	// // } catch (Exception e) {
	// // logger.fine("logging error: " + e.getMessage());
	// // // System.out.println("logging error: " + e.getMessage());
	// // }
	//
	// }
	// }

	public void logOutboundMessage(SipServletMessage msg) {
		logger.log(Level.FINE, Direction.SEND, msg, getPrintableName());
	}

	public void logInboundMessage(SipServletMessage msg) {
		logger.log(Level.FINE, Direction.RECEIVE, msg, getPrintableName());
	}

}
