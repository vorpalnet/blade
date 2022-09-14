/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package org.vorpal.blade.framework.logging;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.logging.Level;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.SipURI;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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

	private static final String NOSESS = "[------:--] ";

	@Override
	public void severe(String msg) {
		super.severe(NOSESS + ConsoleColors.RED_BRIGHT + msg + ConsoleColors.RESET);
	}

	@Override
	public void warning(String msg) {
		super.warning(NOSESS + ConsoleColors.YELLOW_BOLD_BRIGHT + msg + ConsoleColors.RESET);
	}

	@Override
	public void fine(String msg) {
		super.fine(NOSESS + msg);
	}

	@Override
	public void finer(String msg) {
		super.finer(NOSESS + msg);
	}

	@Override
	public void finest(String msg) {
		super.finest(NOSESS + msg);
	}

	@Override
	public void info(String msg) {
		super.info(NOSESS + msg);
	}

	public void logStackTrace(SipApplicationSession appSession, Exception e) {
		StringWriter errors = new StringWriter();
		e.printStackTrace(new PrintWriter(errors));
		severe(appSession, errors.toString());
	}

	public void logStackTrace(SipSession sipSession, Exception e) {
		StringWriter errors = new StringWriter();
		e.printStackTrace(new PrintWriter(errors));
		severe(sipSession, errors.toString());
	}

	public void logStackTrace(SipServletMessage msg, Exception e) {
		StringWriter errors = new StringWriter();
		e.printStackTrace(new PrintWriter(errors));
		severe(msg, errors.toString());
	}

	public void logStackTrace(Exception e) {
		StringWriter errors = new StringWriter();
		e.printStackTrace(new PrintWriter(errors));
		severe(errors.toString());
	}

	public void logConfiguration(Object config) {
		try {
			ObjectMapper mapper = new ObjectMapper();
			mapper.setSerializationInclusion(Include.NON_NULL);
			mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			mapper.writerWithDefaultPrettyPrinter().writeValue(pw, config);
			this.info(config.getClass().getSimpleName() + "=" + sw.toString());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void logObjectAsJson(SipServletMessage message, Level level, Object config) {
		try {

			if (this.isLoggable(level)) {
				ObjectMapper mapper = new ObjectMapper();
				mapper.setSerializationInclusion(Include.NON_NULL);
				mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				mapper.writerWithDefaultPrettyPrinter().writeValue(pw, config);
				log(Level.FINE, message, config.getClass().getSimpleName() + "=" + sw.toString());
			}

		} catch (IOException e) {
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

	public void log(Level level, SipServletMessage message, String comments) {
		log(level, hexHash(message.getSession()) + " " + comments);
	}

	public void fine(SipServletMessage message, String comments) {
		log(Level.FINE, message, comments);
	}

	public void finer(SipServletMessage message, String comments) {
		log(Level.FINER, message, comments);
	}

	public void finest(SipServletMessage message, String comments) {
		log(Level.FINEST, message, comments);
	}

	public void info(SipServletMessage message, String comments) {
		log(Level.INFO, message, comments);
	}

	public void severe(SipServletMessage message, String comments) {
		log(Level.SEVERE, message, ConsoleColors.RED_BRIGHT + comments + ConsoleColors.RESET);
	}

	public void warning(SipServletMessage message, String comments) {
		log(Level.WARNING, message, ConsoleColors.BLUE_BRIGHT + comments + ConsoleColors.RESET);
	}

	public void log(Level level, SipApplicationSession appSession, String comments) {
		log(level, hexHash(appSession) + " " + comments);
	}

	public void log(Level level, SipSession sipSession, String comments) {
		log(level, hexHash(sipSession) + " " + comments);
	}

	public void fine(SipSession sipSession, String comments) {
		log(Level.FINE, sipSession, comments);
	}

	public void fine(SipApplicationSession appSession, String comments) {
		log(Level.FINE, appSession, comments);
	}

	public void finer(SipSession sipSession, String comments) {
		log(Level.FINER, sipSession, comments);
	}

	public void finer(SipApplicationSession appSession, String comments) {
		log(Level.FINER, appSession, comments);
	}

	public void finest(SipSession sipSession, String comments) {
		log(Level.FINEST, sipSession, comments);
	}

	public void finest(SipApplicationSession appSession, String comments) {
		log(Level.FINEST, appSession, comments);
	}

	public void info(SipSession sipSession, String comments) {
		log(Level.INFO, sipSession, comments);
	}

	public void info(SipApplicationSession appSession, String comments) {
		log(Level.INFO, appSession, comments);
	}

	public void severe(SipSession sipSession, String comments) {
		log(Level.SEVERE, sipSession, ConsoleColors.RED_BRIGHT + comments + ConsoleColors.RESET);
	}

	public void severe(SipApplicationSession appSession, String comments) {
		log(Level.SEVERE, appSession, ConsoleColors.RED_BRIGHT + comments + ConsoleColors.RESET);
	}

	public void warning(SipSession sipSession, String comments) {
		log(Level.WARNING, sipSession, ConsoleColors.BLUE_BRIGHT + comments + ConsoleColors.RESET);
	}

	public void warning(SipApplicationSession appSession, String comments) {
		log(Level.WARNING, appSession, ConsoleColors.BLUE_BRIGHT + comments + ConsoleColors.RESET);
	}

	public static String timeout(ServletTimer timer) {
		String str;

		SipApplicationSession appSession = timer.getApplicationSession();
		String id = timer.getId();
		long timeRemaining = timer.getTimeRemaining();

		if (timeRemaining > 0) {
			str = hexHash(timer.getApplicationSession()) + " " + timer.getId() + " timer set for "
					+ timer.getTimeRemaining() + "ms";

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
		int hash1 = Math.abs(sipSession.getApplicationSession().getId().hashCode()) % 0xFFFFFF;
		int hash2 = Math.abs(sipSession.getId().hashCode()) % 0xFF;
		return "[" + String.format("%06X", hash1) + ":" + String.format("%02X", hash2) + "]";
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

	public void superArrow(Direction direction, SipServletRequest request, SipServletResponse response, String name)
			throws ServletParseException {

		try {

			if (isLoggable(Level.FINE)) {

				StringBuilder str = new StringBuilder();

				boolean leftSide = false;

				String requestUri = "";
				if (request != null) {
					if (request.isInitial() || request.getSession().getState().equals(State.EARLY)) {
						requestUri = request.getRequestURI().toString();
					}

//					if (request.getSession().getState().equals(State.INITIAL)) {
//						requestUri = request.getRequestURI().toString();
//					}

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

				String note = "";

				if (leftSide) {
					if (direction.equals(Direction.RECEIVE)) {
						if (request != null) { // #1

							if (request.getMethod().equals("INVITE")) {
								if (request.isInitial()) {
									note = request.getRequestURI().toString();
								} else {
									note = "To: " + request.getTo();
								}
							} else if (request.getMethod().equals("REFER")) {
								note = "Refer-To: " + request.getHeader("Refer-To");
							}

							String alice = String.format("%-17s", shorten(from(request), 17)).replace(' ', '-');
							String arrow = String.format("%17s", method + "-->").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + String.format("%-32s", note);

							str.append(hexHash(request.getSession())).append("{1}");
//							str.append(hexHash(request.getSession())).append(" ");

							str.append(alice).append(arrow).append(middle).append(comment);
						} else { // #2
							String alice = String.format("%-17s", shorten(to(response), 17)).replace(' ', '-');
							String arrow = String.format("%17s", "" + status + "-->").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + response.getReasonPhrase() + " ("
									+ response.getMethod() + ")";

							str.append(hexHash(response.getSession())).append("{2}");
//							str.append(hexHash(response.getSession())).append(" ");
							str.append(alice).append(arrow).append(middle).append(comment);
						}
					} else {
						if (request != null) { // #3

							if (request.getMethod().equals("NOTIFY")) {
								if (request.getContentType().equals("message/sipfrag")) {
									note = new String((byte[]) request.getContent());
								}
							}else if (request.getMethod().equals("INVITE")) {
								note = "From: "+request.getHeader("From");
							}

							String alice = String.format("%-18s", shorten(to(request), 17) + "<").replace(' ', '-');
							String arrow = String.format("%16s", "" + method + "---").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + String.format("%-32s", note);

							str.append(hexHash(request.getSession())).append("{3}");
//							str.append(hexHash(request.getSession())).append(" ");

							str.append(alice).append(arrow).append(middle).append(comment);

						} else { // #4
							String alice = String.format("%-18s", shorten(from(response), 17) + "<").replace(' ', '-');
							String arrow = String.format("%16s", "" + status + "---").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + response.getReasonPhrase() + " ("
									+ response.getMethod() + ")";

							str.append(hexHash(response.getSession())).append("{4}");
//							str.append(hexHash(response.getSession())).append(" ");

							str.append(alice).append(arrow).append(middle).append(comment);
						}
					}

				} else {
					if (direction.equals(Direction.RECEIVE)) {
						if (request != null) { // #5

							if (request.getMethod().equals("INVITE")) {
								if (request.isInitial()) {
									note = request.getRequestURI().toString();
								} else {
									note = "To: " + request.getTo();
								}
							} else if (request.getMethod().equals("REFER")) {
								note = "Refer-To: " + request.getHeader("Refer-To");
							}

							String left = String.format("%34s", "");
							String middle = String.format("%-18s", shorten(name, 17) + "<").replace(' ', '-');
							String arrow = String.format("%16s", "" + method + "---").replace(' ', '-');
							String bob = String.format("%-17s", shorten(from(request), 17));
							String comment = " ; " + String.format("%-32s", note);

							str.append(hexHash(request.getSession())).append("{5}");
//							str.append(hexHash(request.getSession())).append(" ");

							str.append(left).append(middle).append(arrow).append(bob).append(comment);
						} else { // #6
							String left = String.format("%34s", "");
							String middle = String.format("%-18s", shorten(name, 17) + "<").replace(' ', '-');
							String arrow = String.format("%16s", "" + status + "---").replace(' ', '-');
							String bob = String.format("%-17s", shorten(to(response), 17));
							String comment = " ; " + response.getReasonPhrase() + " (" + response.getMethod() + ")";

							str.append(hexHash(response.getSession())).append("{6}");
//							str.append(hexHash(response.getSession())).append(" ");

							str.append(left).append(middle).append(arrow).append(bob).append(comment);
						}

					} else {

						if (request != null) { // #7

							if (request.getMethod().equals("INVITE")) {
								if (request.isInitial()) {
									note = request.getRequestURI().toString();
								} else {
									note = "From: " + request.getFrom();
								}
							} else if (request.getMethod().equals("NOTIFY")) {
								if (request.getContentType().equals("message/sipfrag")) {
									note = new String((byte[]) request.getContent());
								}
							}

							String left = String.format("%34s", "");
							String middle = String.format("%-17s", shorten(name, 17)).replace(' ', '-');
							String arrow = String.format("%17s", method + "-->").replace(' ', '-');
							String bob = String.format("%-17s", shorten(to(request), 17));
							String comment = " ; " + String.format("%-32s", note);

							str.append(hexHash(request.getSession())).append("{7}");
//							str.append(hexHash(request.getSession())).append(" ");

							str.append(left).append(middle).append(arrow).append(bob).append(comment);

						} else { // #8
							String left = String.format("%34s", "");
							String middle = String.format("%-17s", shorten(name, 17)).replace(' ', '-');
							String arrow = String.format("%17s", status + "-->").replace(' ', '-');
							String bob = String.format("%-17s", shorten(from(response), 17));
							String comment = " ; " + response.getReasonPhrase() + " (" + response.getMethod() + ")";

							str.append(hexHash(response.getSession())).append("{8}");
//							str.append(hexHash(response.getSession())).append(" ");

							str.append(left).append(middle).append(arrow).append(bob).append(comment);
						}

					}

				}

				log(Level.FINE, str.toString());

				if (isLoggable(Level.FINEST)) {
					if (request != null) {
						log(Level.FINEST, "\r\n" + request.toString());
					} else {
						log(Level.FINEST, "\r\n" + response.toString());
					}
				}

			}

		} catch (Exception ex) {
			log(Level.WARNING, ex.getMessage());
		}
	}

}
