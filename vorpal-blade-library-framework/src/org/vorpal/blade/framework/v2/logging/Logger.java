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

package org.vorpal.blade.framework.v2.logging;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.logging.Level;

import javax.servlet.sip.Proxy;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.v2.callflow.Callflow;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class Logger extends java.util.logging.Logger implements Serializable {
	private static final long serialVersionUID = 1L;
	private Level sequenceDiagramLoggingLevel = Level.FINE;
	private Level configurationLoggingLevel = Level.FINE;
	private static ObjectMapper mapper = null;

	@Override
	public void log(Level level, String msg) {
		try {
			super.log(level, msg);
		} catch (Exception ex) {
			StringWriter errors = new StringWriter();
			ex.printStackTrace(new PrintWriter(errors));
			try {
				this.getParent().warning("Logging problem... " + errors.toString());
			} catch (Exception ex2) {
				System.out.println("WARNING: Logging problem... " + errors.toString());
			}
		}
	}

	@Override
	public Level getLevel() {
		Level level = super.getLevel();
		if (level == null) {
			level = this.getParent().getLevel();
		}
		return level;
	}

	public Level getSequenceDiagramLoggingLevel() {
		return sequenceDiagramLoggingLevel;
	}

	public void setSequenceDiagramLoggingLevel(Level sequenceDiagramLoggingLevel) {
		this.sequenceDiagramLoggingLevel = sequenceDiagramLoggingLevel;
	}

	public Level getConfigurationLoggingLevel() {
		return configurationLoggingLevel;
	}

	public void setConfigurationLoggingLevel(Level configurationLoggingLevel) {
		this.configurationLoggingLevel = configurationLoggingLevel;
	}

	public static enum Direction {
		SEND, RECEIVE
	};

	protected Logger(String name, String resourceBundleName) {
		super(name, resourceBundleName);
	}

	private static final String NOSESS = "[--------:----]";

	@Override
	public void severe(String msg) {
		super.severe(NOSESS + " " + ConsoleColors.RED_BRIGHT + msg + ConsoleColors.RESET);
	}

	@Override
	public void warning(String msg) {
		super.warning(NOSESS + " " + ConsoleColors.YELLOW_BOLD_BRIGHT + msg + ConsoleColors.RESET);
	}

	@Override
	public void fine(String msg) {
		super.fine(NOSESS + " " + msg);
	}

	@Override
	public void finer(String msg) {
		super.finer(NOSESS + " " + msg);
	}

	@Override
	public void finest(String msg) {
		super.finest(NOSESS + " " + msg);
	}

	@Override
	public void info(String msg) {
		super.info(NOSESS + " " + msg);
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
		warning("Logging error...  " + e.getMessage() + "\n" + errors.toString());
	}

	public void logSevereStackTrace(Exception e) {
		StringWriter errors = new StringWriter();
		e.printStackTrace(new PrintWriter(errors));
		severe("Logging error...  " + e.getMessage() + "\n" + errors.toString());
	}

	public void logWarningStackTrace(Exception e) {
		StringWriter errors = new StringWriter();
		e.printStackTrace(new PrintWriter(errors));
		warning("Logging warning...  " + e.getMessage() + "\n" + errors.toString());
	}

	/**
	 * Serializes an object as JSON and logs it at the 'configurationLoggingLevel'
	 * if it exists. If not, it logs it as FINE.
	 * 
	 * @param config
	 */
	public void logConfiguration(Object config) {
		if (this.configurationLoggingLevel != null) {
			this.log(this.configurationLoggingLevel, config.getClass().getSimpleName() + "=" + serializeObject(config));
		} else {
			this.log(Level.FINE, config.getClass().getSimpleName() + "=" + serializeObject(config));
		}
	}

	/**
	 * Serializes an object as JSON and logs it at the specified level.
	 * 
	 * @param message
	 * @param level
	 * @param obj
	 */
	public void logObjectAsJson(SipServletMessage message, Level level, Object obj) {
		if (isLoggable(level)) {
			log(level, message, obj.getClass().getName() + "=" + serializeObject(obj));
		}
	}

	/**
	 * Serializes an object as JSON and logs it at the specified level.
	 * 
	 * @param sipSession
	 * @param level
	 * @param obj
	 */
	public void logObjectAsJson(SipSession sipSession, Level level, Object obj) {
		if (isLoggable(level)) {
			log(level, sipSession, obj.getClass().getName() + "=" + serializeObject(obj));
		}
	}

	/**
	 * Serializes an object as JSON and logs it at the specified level.
	 * 
	 * @param appSession
	 * @param level
	 * @param obj
	 */
	public void logObjectAsJson(SipApplicationSession appSession, Level level, Object obj) {
		if (isLoggable(level)) {
			log(level, appSession, obj.getClass().getName() + "=" + serializeObject(obj));
		}
	}

	/**
	 * Serializes an object as JSON and logs it at the specified level.
	 * 
	 * @param level
	 * @param obj
	 */
	public void logObjectAsJson(Level level, Object obj) {
		if (isLoggable(level)) {
			log(level, obj.getClass().getName() + "=" + serializeObject(obj));
		}
	}

	/**
	 * Serializes an object as JSON and returns a string.
	 * 
	 * @param obj
	 * @return
	 */
	public static String serializeObject(Object obj) {
		String value = null;

		if (obj != null) {
			if (mapper == null) {
				mapper = new ObjectMapper();
				mapper.setSerializationInclusion(Include.NON_NULL);
				mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
			}

			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			try {
				mapper.writerWithDefaultPrettyPrinter().writeValue(pw, obj);
				value = sw.toString();
			} catch (Exception ex) {
				// cannot serialize, just give the pointer location
				value = obj.toString();
			}
		}

		return value;
	}

	public void severe(Exception e) {
		severe(null, e);
	}

	public void severe(SipServletMessage message, Exception e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		if (message != null) {
			log(Level.SEVERE, message, sw.toString());
		} else {
			log(Level.SEVERE, sw.toString());
		}
	}

	public void log(Level level, ServletTimer timer) {
		if (this.isLoggable(level)) {
			log(level, timer, null);
		}
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

		if (this.isLoggable(level)) {
			try {

				log(level, hexHash(message) + " " + comments);

			} catch (Exception e) {
				this.logWarningStackTrace(e);
			}
		}

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
		log(Level.WARNING, message, ConsoleColors.YELLOW_BOLD_BRIGHT + comments + ConsoleColors.RESET);
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
		log(Level.WARNING, sipSession, ConsoleColors.YELLOW_BOLD_BRIGHT + comments + ConsoleColors.RESET);
	}

	public void warning(SipApplicationSession appSession, String comments) {
		log(Level.WARNING, appSession, ConsoleColors.YELLOW_BOLD_BRIGHT + comments + ConsoleColors.RESET);
	}

	public static String timeout(ServletTimer timer) {
		String str;

		long timeRemaining = timer.getTimeRemaining();

		if (timeRemaining > 0) {
			str = hexHash(timer.getApplicationSession()) + " " + timer.getId() + " timer set for "
					+ timer.getTimeRemaining() + "ms";

		} else {
			str = hexHash(timer.getApplicationSession()) + " " + timer.getId() + " timer expired";

		}
		return str;

	}

	public static String from(SipServletMessage msg) {
		String name = null;
		SipURI uri = (SipURI) msg.getFrom().getURI();
		name = uri.getUser();

		if (name == null) {
			name = uri.getHost();
		}

		return name;
	}

	public static String to(SipServletMessage msg) {
		String name = null;

		SipURI uri = (SipURI) msg.getTo().getURI();
		name = uri.getUser();

		if (name == null) {
			name = uri.getHost();
		}

		return name;
	}

	public static String hexHash(SipApplicationSession appSession) {
		String hashValue = NOSESS;

		if (appSession != null) {
			String hash1 = Callflow.getVorpalSessionId(appSession);
			if (hash1 == null) {
				hash1 = "--------";
			}
			hashValue = "[" + hash1 + ":----]";
		}

		return hashValue;
	}

	public static String hexHash(SipServletMessage message) {
		SipSession sipSession = null;

		if (message != null) {
			sipSession = message.getSession();
		}

		return hexHash(sipSession);
	}

	public static String hexHash(SipSession sipSession) {
		String hashValue = NOSESS;

		if (sipSession != null && sipSession.isValid()) {

			String hash1 = Callflow.getVorpalSessionId(sipSession.getApplicationSession());
			if (hash1 == null) {
				hash1 = "--------";
			}

			String hash2 = Callflow.getVorpalDialogId(sipSession);
			if (hash2 == null) {
				hash2 = "----";
			}

			hashValue = "[" + hash1 + ":" + hash2 + "]";
		}
		return hashValue;
	}

	public String shorten(String _value, int length) {

		String value = null;
		int dollarIndex = _value.indexOf('$');

		if (dollarIndex >= 0) {
			value = _value.substring(0, dollarIndex);
		} else {
			value = _value;
		}

		StringBuilder sb = new StringBuilder();

		if (length >= 2) {
			length = length - 2;
		}

		if (value.length() <= length) {
			sb.append("[").append(value).append("]");
		} else {

			String name = value.substring(0, length);
			sb.append("[");
			sb.append(name);
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

	public void superArrow(Direction direction, SipServletRequest request, SipServletResponse response, String name) {
		try {
			boolean leftSide = false;

			if (isLoggable(Level.FINE)) { // TODO: This seems like a problem with the Configuration file settings.

				if (request != null //
						&& request.isInitial() //
						&& direction.equals(Direction.RECEIVE)) {

//					request.getSession().setAttribute("DIAGRAM_SIDE", "LEFT");
					String line = String.format("%87s", "").replace(' ', '=');

					// This is the new session =========== line
					log(Level.FINE, hexHash(request) + " " + line);
				}

				if (request != null) {
					leftSide = (null != request.getSession().getAttribute("_diagramLeft")
							&& request.getSession().getAttribute("_diagramLeft").equals(Boolean.TRUE)) ? true : false;
				} else {
					leftSide = (null != response.getSession().getAttribute("_diagramLeft")
							&& response.getSession().getAttribute("_diagramLeft").equals(Boolean.TRUE)) ? true : false;
				}

				if (response != null) {
					Proxy proxy = response.getRequest().getProxy(false);
					if (proxy != null) {
						leftSide = false;
					}
				}

			}

			superArrow(direction, leftSide, request, response, name, null);

		} catch (Exception ex) {

			if (Callflow.getSipLogger() != null) {
				Callflow.getSipLogger().warning("Logging error... " + ex.getMessage());
			}

		}
	}

	public void superArrow(Direction direction, boolean leftSide, SipServletRequest request,
			SipServletResponse response, String name, String user) {
//		log(Level.WARNING, "...superArrow direction=" + direction //
//				+ ", leftSide=" + leftSide //
//				+ ", request=" + ((request != null) ? request.getMethod() : null) //
//				+ ", response=" + ((response != null) ? response.getMethod() : null) //
//				+ ", name=" + name //
//				+ ", user=" + user);

		try {

			if (isLoggable(this.getSequenceDiagramLoggingLevel())) {
				StringBuilder str = new StringBuilder();

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
							}

							else if (request.getMethod().equals("NOTIFY")) {
								note += "Event: " + request.getHeader("Event");
								note += ", Subscription-State: " + request.getHeader("Subscription-State");
								if (request.getContentType().equals("message/sipfrag")) {
									if (note.length() > 0) {
										note += ", ";
									}
									note += new String((byte[]) request.getContent());
								}
							}

							else if (request.getMethod().equals("REFER")) {

//								note = "Refer-To: " + request.getAddressHeader("Refer-To");
								note = "Refer-To: " + request.getHeader("Refer-To");

							} else if (request.getMethod().equals("REGISTER")) {
								String expires = request.getHeader("Expires");
								if (expires == null) {
									expires = request.getParameterableHeader("Contact").getParameter("expires");
								}
								note = "Expires: " + expires;
							}

							note = note.trim();

							String alice;
							if (user == null) {
								alice = String.format("%-17s", shorten(from(request), 17)).replace(' ', '-');
							} else {
								alice = String.format("%-17s", shorten(user, 17)).replace(' ', '-');
							}

							String arrow = String.format("%17s", method + "-->").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));

							String comment = String.format("%36s", ";") + " " + String.format("%-32s", note);

//							str.append(hexHash(request.getSession())).append("{1}");
							str.append(hexHash(request)).append(" ");

//							comment = addState(request, comment);
							str.append(alice).append(arrow).append(middle).append(comment);
						} else { // #2

							String alice;
							if (user == null) {
								alice = String.format("%-17s", shorten(to(response), 17)).replace(' ', '-');
							} else {
								alice = String.format("%-17s", shorten(user, 17)).replace(' ', '-');
							}

							String arrow = String.format("%17s", "" + status + "-->").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + response.getReasonPhrase() + " ("
									+ response.getMethod() + ")";

//							str.append(hexHash(response.getSession())).append("{2}");
							str.append(hexHash(response)).append(" ");

//							comment = addState(response, comment);

							str.append(alice).append(arrow).append(middle).append(comment);
						}
					} else {
						if (request != null) { // #3

							if (request.getMethod().equals("NOTIFY")) {
								note += "Event: " + request.getHeader("Event");
								note += ", Subscription-State: " + request.getHeader("Subscription-State");
								if (request.getContentType().equals("message/sipfrag")) {
									if (note.length() > 0) {
										note += ", ";
									}
									note += new String((byte[]) request.getContent());
								}
							} else if (request.getMethod().equals("REFER")) {
//								note = "Refer-To: " + request.getAddressHeader("Refer-To");
								note = "Refer-To: " + request.getHeader("Refer-To");
							} else if (request.getMethod().equals("INVITE")) {
								note = "From: " + request.getFrom();
							}

							note = note.trim();

							String alice;
							if (user == null) {
								alice = String.format("%-18s", shorten(to(request), 17) + "<").replace(' ', '-');
							} else {
								alice = String.format("%-18s", shorten(user, 17) + "<").replace(' ', '-');
							}
							String arrow = String.format("%16s", "" + method + "---").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + String.format("%-32s", note);

//							str.append(hexHash(request.getSession())).append("{3}");
							str.append(hexHash(request)).append(" ");

//							comment = addState(request, comment);

							str.append(alice).append(arrow).append(middle).append(comment);

						} else { // #4

							String alice;
							if (user == null) {
								alice = String.format("%-18s", shorten(from(response), 17) + "<").replace(' ', '-');
							} else {
								alice = String.format("%-18s", shorten(user, 17) + "<").replace(' ', '-');
							}

							String arrow = String.format("%16s", "" + status + "---").replace(' ', '-');
							String middle = String.format("%-17s", shorten(name, 17));
							String comment = String.format("%36s", ";") + " " + response.getReasonPhrase() + " ("
									+ response.getMethod() + ")";

//							str.append(hexHash(response.getSession())).append("{4}");
							str.append(hexHash(response)).append(" ");

//							comment = addState(response, comment);

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
							}

							else if (request.getMethod().equals("NOTIFY")) {
								note += "Event: " + request.getHeader("Event");
								note += ", Subscription-State: " + request.getHeader("Subscription-State");
								if (request.getContentType().equals("message/sipfrag")) {
									if (note.length() > 0) {
										note += ", ";
									}
									note += new String((byte[]) request.getContent()).trim();
								}
							}

							else if (request.getMethod().equals("REFER")) {
//								note = "Refer-To: " + request.getAddressHeader("Refer-To");
								note = "Refer-To: " + request.getHeader("Refer-To");
							}

							note = note.trim();

							String left = String.format("%34s", "");
							String middle = String.format("%-18s", shorten(name, 17) + "<").replace(' ', '-');
							String arrow = String.format("%16s", "" + method + "---").replace(' ', '-');

							String bob;
							if (user == null) {
								bob = String.format("%-17s", shorten(from(request), 17));
							} else {
								bob = String.format("%-17s", shorten(user, 17));
							}

							String comment = " ; " + String.format("%-32s", note);

//							str.append(hexHash(request.getSession())).append("{5}");
							str.append(hexHash(request)).append(" ");

//							comment = addState(request, comment);

							str.append(left).append(middle).append(arrow).append(bob).append(comment);
						} else { // #6
							String left = String.format("%34s", "");
							String middle = String.format("%-18s", shorten(name, 17) + "<").replace(' ', '-');
							String arrow = String.format("%16s", "" + status + "---").replace(' ', '-');

							String bob;
							if (user == null) {
								bob = String.format("%-17s", shorten(to(response), 17));
							} else {
								bob = String.format("%-17s", shorten(user, 17));
							}

							String comment = " ; " + response.getReasonPhrase() + " (" + response.getMethod() + ")";

//							str.append(hexHash(response.getSession())).append("{6}");
							str.append(hexHash(response)).append(" ");

//							comment = addState(response, comment);

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
								note += "Event: " + request.getHeader("Event");
								note += ", Subscription-State: " + request.getHeader("Subscription-State");
								if (request.getContentType().equals("message/sipfrag")) {
									if (note.length() > 0) {
										note += ", ";
									}
									note += new String((byte[]) request.getContent());
								}
							}

							note = note.trim();

							String left = String.format("%34s", "");
							String middle = String.format("%-17s", shorten(name, 17)).replace(' ', '-');
							String arrow = String.format("%17s", method + "-->").replace(' ', '-');

							String bob;
							if (user == null) {
								bob = String.format("%-17s", shorten(to(request), 17));
							} else {
								bob = String.format("%-17s", shorten(user, 17));
							}

							String comment = " ; " + String.format("%-32s", note);

//							str.append(hexHash(request.getSession())).append("{7}");
							str.append(hexHash(request)).append(" ");

//							comment = addState(request, comment);

							str.append(left).append(middle).append(arrow).append(bob).append(comment);

						} else { // #8
							String left = String.format("%34s", "");
							String middle = String.format("%-17s", shorten(name, 17)).replace(' ', '-');
							String arrow = String.format("%17s", status + "-->").replace(' ', '-');
							String bob = String.format("%-17s", shorten(from(response), 17));
							String comment = " ; " + response.getReasonPhrase() + " (" + response.getMethod() + ")";

//							str.append(hexHash(response.getSession())).append("{8}");
							str.append(hexHash(response)).append(" ");

//							comment = addState(response, comment);

							str.append(left).append(middle).append(arrow).append(bob).append(comment);
						}

					}

				}

				log(this.getSequenceDiagramLoggingLevel(), str.toString());

				if (isLoggable(Level.FINEST)) {
					if (request != null) {
						finest(request, direction + " Request:\n" + request.toString());
					} else {
						finest(request, direction + " Response:\n" + response.toString());
					}
				}

			}

		} catch (Exception ex) {
			this.logWarningStackTrace(ex);
		}
	}

}
