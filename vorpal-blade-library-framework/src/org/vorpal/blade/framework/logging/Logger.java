package org.vorpal.blade.framework.logging;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;

import javax.servlet.ServletContext;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipURI;

import org.codehaus.jackson.map.ObjectMapper;
import org.vorpal.blade.framework.callflow.Callflow;

import weblogic.kernel.KernelLogManager;

public class Logger extends java.util.logging.Logger implements Serializable {
//	private static Logger logger = null;
//	private static String name;

	public static enum Direction {
		SEND, RECEIVE
	};
	// Level{OFF, SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST, ALL};

	protected Logger(String name, String resourceBundleName) {
		super(name, resourceBundleName);
	}

//	public static Logger getLogger() {
//		return logger;
//	}

	@Override
	public void severe(String msg) {
		super.severe(ConsoleColors.RED_BRIGHT + msg + ConsoleColors.RESET);
	}

	@Override
	public void warning(String msg) {
		super.severe(ConsoleColors.BLUE_BRIGHT + msg + ConsoleColors.RESET);
	}

	public void logStackTrace(Exception ex) {
		StringWriter errors = new StringWriter();
		ex.printStackTrace(new PrintWriter(errors));
		severe(errors.toString());
	}

	public void logConfiguration(Object config) {
		try {
			ObjectMapper mapper = new ObjectMapper();
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			mapper.writerWithDefaultPrettyPrinter().writeValue(pw, config);
			this.info(config.getClass().getSimpleName() + "=" + sw.toString());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void severe(Exception e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		log(Level.SEVERE, sw.toString());
	}

	public void log(Level level, ServletTimer timer) {
		log(level, timer, null);
	}

	public void log(Level level, ServletTimer timer, String comments) {
		if (this.isLoggable(level)) {
			String msg;
			if (comments != null) {
				msg = " (" + comments + ")";
			} else {
				msg = "";
			}

			log(level, timeout(timer) + msg);

		}

	}

	public static String timeout(ServletTimer timer) {
		String str;

		SipApplicationSession appSession = timer.getApplicationSession();
		String id = timer.getId();
		long timeRemaining = timer.getTimeRemaining();

		if (timeRemaining > 0) {
			str = hexHash(timer.getApplicationSession()) + " " + timer.getId() + " timer set for " + timer.getTimeRemaining() + "ms";

		} else {
			str = hexHash(timer.getApplicationSession()) + " " + timer.getId() + " timer expired";

		}
		return str;

	}

	private String from(SipServletMessage msg) throws ServletParseException {
		String name = null;
		SipURI uri = (SipURI) msg.getFrom().getURI();
		name = uri.getUser();

		if (name == null) {
			name = uri.getHost();
		}

		return name;
	}

	private String to(SipServletMessage msg) throws ServletParseException {
		String name = null;
		SipURI uri = (SipURI) msg.getTo().getURI();
		name = uri.getUser();

		if (name == null) {
			name = uri.getHost();
		}

		return name;
	}

	public static String hexHash(SipApplicationSession appSession) {
		int hash = Math.abs(appSession.getId().hashCode()) % 0xFFFF;
		return "[" + String.format("%04X", hash) + ":----]";
	}

	public static String hexHash(SipSession sipSession) {
		int hash1 = Math.abs(sipSession.getApplicationSession().getId().hashCode()) % 0xFFFF;
		int hash2 = Math.abs(sipSession.getId().hashCode()) % 0xFFFF;
		return "[" + String.format("%04X", hash1) + ":" + String.format("%04X", hash2) + "]";
	}

	public String shorten(String value, int length) {
		StringBuilder sb = new StringBuilder();

		if (length >= 2) {
			length = length - 2;
		}

		if (value.length() <= length) {
			sb.append("[").append(value).append("]");
		} else {
			String left = value.substring(0, (length / 2)) + "*";
			String right = value.substring(value.length() - (length - left.length()), value.length());

			sb.append("[");
			sb.append(left);
			sb.append(right);
			sb.append("]");
		}

		return sb.toString();
	}

//    |-------17------||-------17------||-------17------||-------17------||-------17------||-------17------|
//    0                   10                  20                  30                  40                  50		
//    012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890  
//#1  [255.255.255.255]--------INVITE-->[BigLongN*edCall]                                     ; SDP 
//#2  [255.255.255.255]-----------200-->[BigLongN*edCall]                                     ; OK (INVITE) 
//#3  [255.255.255.255]<-------INVITE---[BigLon*allflow2]                                     ; SDP
//#4  [255.255.255.255]<----------200---[BigLon*allflow2]                                     ; OK (INVITE) w/SDP
//#5                                    [BigLonedCall]<----------INVITE---[255.255.255.255]   ; SDP
//#6                                    [BigLon*amedCall]<----------200---[255.255.255.255]   ; OK (INVITE)
//#7                                    [BigLonedCall]-----------INVITE-->[255.255.255.255]   ; SDP
//#8                                    [BigLon*amedCall]-----------200-->[255.255.255.255]   ; OK (INVITE)

	public void superArrow(Direction direction, SipServletRequest request, SipServletResponse response, String name) throws ServletParseException {

		try {

			if (isLoggable(Level.FINE)) {

				StringBuilder str = new StringBuilder();

				boolean leftSide = false;

				String requestUri = "";
				if (request != null && request.isInitial()) {
					requestUri = request.getRequestURI().toString();
				}

				if (request != null && request.isInitial() && direction.equals(Direction.RECEIVE)) {
					request.getSession().setAttribute("DIAGRAM_SIDE", "LEFT");
					String line = String.format("%87s", "").replace(' ', '=');
					log(Level.FINE, hexHash(request.getSession()) + (" ") + line);
				}

				if (request != null) {
					leftSide = (null != request.getSession().getAttribute("DIAGRAM_SIDE")) ? true : false;
				} else {
					leftSide = (null != response.getSession().getAttribute("DIAGRAM_SIDE")) ? true : false;
				}

				String method = "";
				if (request != null) {
					method += request.getMethod();
					if (request.getContentLength() > 0) {
						method += " (sdp)";
					}
				}

				String status = "";
				if (response != null) {
					status += response.getStatus();
					if (response.getContentLength() > 0) {
						status += " (sdp)";
					}
				}

//			String name;
//			if (callflow != null) {
//				name = callflow.getClass().getSimpleName();
//			} else {
//				name = "null";
//			}

				if (leftSide) {
					if (direction.equals(Direction.RECEIVE)) {
						if (request != null) { // #1
							String alice = String.format("%-17s", shorten(from(request), 17)).replace(' ', '-');
							String arrow = String.format("%17s", method + "-->").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + String.format("%-32s", requestUri);

							// str.append(hexHash(request.getSession())).append("1");
							str.append(hexHash(request.getSession())).append(" ");

							str.append(alice).append(arrow).append(middle).append(comment);
						} else { // #2
							String alice = String.format("%-17s", shorten(to(response), 17)).replace(' ', '-');
							String arrow = String.format("%17s", "" + status + "-->").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + response.getReasonPhrase() + " (" + response.getMethod() + ")";

//						str.append(hexHash(response.getSession())).append("2");
							str.append(hexHash(response.getSession())).append(" ");
							str.append(alice).append(arrow).append(middle).append(comment);
						}
					} else {
						if (request != null) { // #3
							String alice = String.format("%-18s", shorten(to(request), 17) + "<").replace(' ', '-');
							String arrow = String.format("%16s", "" + method + "---").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + String.format("%-32s", requestUri);
//						str.append(hexHash(request.getSession())).append("3");
							str.append(hexHash(request.getSession())).append(" ");
							str.append(alice).append(arrow).append(middle).append(comment);

						} else { // #4
							String alice = String.format("%-18s", shorten(from(response), 17) + "<").replace(' ', '-');
							String arrow = String.format("%16s", "" + status + "---").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + response.getReasonPhrase() + " (" + response.getMethod() + ")";

//						str.append(hexHash(response.getSession())).append("4");
							str.append(hexHash(response.getSession())).append(" ");
							str.append(alice).append(arrow).append(middle).append(comment);
						}
					}

				} else {
					if (direction.equals(Direction.RECEIVE)) {
						if (request != null) { // #5
							String left = String.format("%34s", "");
							String middle = String.format("%-18s", shorten(name, 17) + "<").replace(' ', '-');
							String arrow = String.format("%16s", "" + method + "---").replace(' ', '-');
							String bob = String.format("%-17s", shorten(from(request), 17));
							String comment = " ; " + String.format("%-32s", requestUri);
//						str.append(hexHash(request.getSession())).append("5");
							str.append(hexHash(request.getSession())).append(" ");
							str.append(left).append(middle).append(arrow).append(bob).append(comment);
						} else { // #6
							String left = String.format("%34s", "");
							String middle = String.format("%-18s", shorten(name, 17) + "<").replace(' ', '-');
							String arrow = String.format("%16s", "" + status + "---").replace(' ', '-');
							String bob = String.format("%-17s", shorten(to(response), 17));
							String comment = " ; " + response.getReasonPhrase() + " (" + response.getMethod() + ")";
//						str.append(hexHash(response.getSession())).append("6");
							str.append(hexHash(response.getSession())).append(" ");
							str.append(left).append(middle).append(arrow).append(bob).append(comment);
						}

					} else {

						if (request != null) { // #7
							String left = String.format("%34s", "");
							String middle = String.format("%-17s", shorten(name, 17)).replace(' ', '-');
							String arrow = String.format("%17s", method + "-->").replace(' ', '-');
							String bob = String.format("%-17s", shorten(to(request), 17));
							String comment = " ; " + String.format("%-32s", requestUri);
//						str.append(hexHash(request.getSession())).append("7");
							str.append(hexHash(request.getSession())).append(" ");
							str.append(left).append(middle).append(arrow).append(bob).append(comment);

						} else { // #8
							String left = String.format("%34s", "");
							String middle = String.format("%-17s", shorten(name, 17)).replace(' ', '-');
							String arrow = String.format("%17s", status + "-->").replace(' ', '-');
							String bob = String.format("%-17s", shorten(from(response), 17));
							String comment = " ; " + response.getReasonPhrase() + " (" + response.getMethod() + ")";
//						str.append(hexHash(response.getSession())).append("8");
							str.append(hexHash(response.getSession())).append(" ");
							str.append(left).append(middle).append(arrow).append(bob).append(comment);
						}

					}

				}

				log(Level.FINE, str.toString());

				if (isLoggable(Level.FINER)) {
					if (request != null) {
						log(Level.FINER, "\r\n" + request.toString());
					} else {
						log(Level.FINER, "\r\n" + response.toString());
					}
				}

			}

		} catch (Exception ex) {
			log(Level.WARNING, ex.getMessage());
		}
	}

}
