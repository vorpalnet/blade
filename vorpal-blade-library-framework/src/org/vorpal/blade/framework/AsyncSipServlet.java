package org.vorpal.blade.framework;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.TimerListener;
import javax.servlet.sip.TimerService;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.callflow.Callback;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;
import org.vorpal.blade.framework.logging.Logger.Direction;

/**
 * This abstract SipServlet is designed to implement the features of the BLADE
 * asynchronous APIs (lambda expressions). Extend and implement this class to
 * create your own specialized SipServlet class. See B2buaServlet as an example.
 * 
 * @author Jeff McDonald
 *
 */
public abstract class AsyncSipServlet extends SipServlet
		implements SipServletListener, ServletContextListener, TimerListener {

	private static final long serialVersionUID = 1L;
	private SipServletContextEvent event;
	protected static Logger sipLogger;
	protected static SipFactory sipFactory;
	protected static SipSessionsUtil sipUtil;
	protected static TimerService timerService;

	/**
	 * Called when the SipServlet has been created.
	 * 
	 * @param event
	 * @throws ServletException 
	 * @throws IOException 
	 */
	protected abstract void servletCreated(SipServletContextEvent event) throws ServletException, IOException;

	/**
	 * Called when the SipServlet has been destroyed.
	 * 
	 * @param event
	 * @throws ServletException 
	 * @throws IOException 
	 */
	protected abstract void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException;

	/**
	 * Implement this method to choose various Callflow objects for incoming
	 * requests that do not already have a callflow defined.
	 * 
	 * @param request
	 * @return Callflow the chosen callflow object
	 * @throws ServletException
	 * @throws IOException
	 */
	protected abstract Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException;

	@Override
	final public void servletInitialized(SipServletContextEvent event) {
		this.event = event;
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");
		timerService = (TimerService) event.getServletContext().getAttribute("javax.servlet.sip.TimerService");

		Callflow.setLogger(sipLogger);
		Callflow.setSipFactory(sipFactory);
		Callflow.setSipUtil(sipUtil);
		Callflow.setTimerService(timerService);

		try {
			servletCreated(event);
			sipLogger = LogManager.getLogger(event);
		} catch (Exception e) {

			if (null != sipLogger) {
				sipLogger.logStackTrace(e);
			} else {
				e.printStackTrace();
			}

		}

	}

	/**
	 * Override this method to handle call events that may fall outside the scope of
	 * your defined callflows.
	 * 
	 * @param message a modifiable message object, either a request or response
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callEvent(SipServletMessage message) throws ServletException, IOException {
		// override this method
	}

	@Override
	final public void contextInitialized(ServletContextEvent sce) {
		// do nothing;
	}

	@Override
	final public void contextDestroyed(ServletContextEvent sce) {
		try {
			servletDestroyed(event);
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	@Override
	protected void doRequest(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow;
		Callback<SipServletRequest> requestLambda;

		try {
			requestLambda = Callflow.pullCallback(request);
			if (requestLambda != null) {
				String name = requestLambda.getClass().getSimpleName();
				Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, name);
				requestLambda.accept(request);
			} else {
				callflow = chooseCallflow(request);

				if (callflow == null) {
					if (request.getMethod().equals("ACK")) {
						Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
					} else {
						Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
						SipServletResponse response = request.createResponse(501);
						response.send();
						Callflow.getLogger().superArrow(Direction.SEND, null, response, "null");
						Callflow.getLogger().warning("No registered callflow for request method " + request.getMethod()
								+ ", consider modifying the 'chooseCallflow' method.");
					}
				} else {
					callflow.processWrapper(request);
				}
			}
		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
			throw e;
		}
	}

	@Override
	protected void doResponse(SipServletResponse response) throws ServletException, IOException {
		Callback<SipServletResponse> callback;
		try {

			callback = Callflow.pullCallback(response);
			if (callback != null) {
				Callflow.getLogger().superArrow(Direction.RECEIVE, null, response, callback.getClass().getSimpleName());
				callback.accept(response);
			} else {
				Callflow.getLogger().superArrow(Direction.RECEIVE, null, response, "null");
			}

		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
			throw e;
		}

	}

	@SuppressWarnings("unchecked")
	@Override
	final public void timeout(ServletTimer timer) {
		try {

			Callback<ServletTimer> callback;
			callback = (Callback<ServletTimer>) timer.getInfo();

			if (callback != null) {
				callback.accept(timer);
			}

		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
		}
	}

	/**
	 * @return the sipLogger
	 */
	final public static Logger getSipLogger() {
		return sipLogger;
	}

	final public static void setSipLogger(Logger _sipLogger) {
		sipLogger = _sipLogger;
	}

	/**
	 * @return the sipFactory
	 */
	final public static SipFactory getSipFactory() {
		return sipFactory;
	}

	final public static void setSipFactory(SipFactory _sipFactory) {
		sipFactory = _sipFactory;
	}

	/**
	 * @return the sipUtil
	 */
	final public static SipSessionsUtil getSipUtil() {
		return sipUtil;
	}

	final public static void setSipUtil(SipSessionsUtil _sipUtil) {
		sipUtil = _sipUtil;
	}

	/**
	 * @return the timerService
	 */
	final public static TimerService getTimerService() {
		return timerService;
	}

//	/**
//	 * This is an alternate hashing algorithm that can be used during
//	 * the @SipApplicationKey method.
//	 * 
//	 * @param string
//	 * @return a long number written as a hexadecimal string
//	 */
//	public static String hash(String string) {
//		long h = 1125899906842597L; // prime
//		int len = string.length();
//
//		for (int i = 0; i < len; i++) {
//			h = 31 * h + string.charAt(i);
//		}
//
//		return Long.toHexString(h);
//	}

	private static String byteArray2Text(byte[] bytes) {
		final char[] alphanum = { // 62 characters for randomish pattern distribution
				'0', '1', '2', '3', '4', '5', '6', '7', //
				'8', '9', 'a', 'b', 'c', 'd', 'e', 'f', //
				'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', //
				'o', 'p', 'q', 'r', 's', 't', 'u', 'v', //
				'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', //
				'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', //
				'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', //
				'U', 'V', 'W', 'X', 'Y', 'Z' };
		StringBuffer sb = new StringBuffer();
		for (final byte b : bytes) {
			sb.append(alphanum[Byte.toUnsignedInt(b) % alphanum.length]);
		}
		return sb.toString();
	}

	private static String hexEncode(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(Integer.toHexString(Byte.toUnsignedInt(b)));
		}
		return sb.toString();
	}

	
	public static String hash(String stringToHash) {
		String stringHash = null;

		MessageDigest messageDigest;
		try {
//			messageDigest = MessageDigest.getInstance("SHA-256");
			messageDigest = MessageDigest.getInstance("MD5");
			messageDigest.update(stringToHash.getBytes());
			stringHash = byteArray2Text(messageDigest.digest());
//			stringHash = hexEncode(messageDigest.digest());

		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return stringHash;
	}

	public static String getAppSessionHashKey(String stringToHash) {
		SipApplicationSession appSession = null;
		String hashedString = hash(stringToHash);
		appSession = sipUtil.getApplicationSessionByKey(hashedString, true);

		String existingHashKey = (String) appSession.getAttribute("HASHKEY");

		if (null == existingHashKey) {
			// this is good because the AppSession did not previously exist
			appSession.setAttribute("HASHKEY", hashedString);
		} else {
			if (false == existingHashKey.equals(hashedString)) {
				// this is bad because the hash keys collide;
				sipLogger.severe("@SipApplicationKey hash key collision. SipApplicationSession.id: "
						+ appSession.getId() + " collides with " + existingHashKey + " and " + hashedString);
				appSession.setAttribute("HASHKEY_COLLISION", true);
			}
		}

		return hashedString;
	}

	public static String getAccountName(Address address) {
		return getAccountName(address.getURI());
	}

	public static String getAccountName(URI _uri) {
		SipURI sipUri = (SipURI) _uri;
		return sipUri.getUser().toLowerCase() + "@" + sipUri.getHost().toLowerCase();
	}

	public void sendResponse(SipServletResponse response) throws ServletException, IOException {
		Callflow.getLogger().superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());
		try {
			response.send();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}
	}

}
