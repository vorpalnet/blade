package vorpal.alice.logging;

import java.io.File;
import java.io.PrintWriter;
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

import weblogic.kernel.KernelLogManager;

@Deprecated
public class Logger extends java.util.logging.Logger {
	private static Logger logger = null;

	public static enum Direction {
		SEND, RECEIVE
	};
	// public enum Level{OFF, SEVERE, WARNING, INFO, CONFIG, FINE, FINER,
	// FINEST, ALL};

	protected Logger(String name, String resourceBundleName) {
		super(name, resourceBundleName);
	}

	public static Logger getLogger() {
		return logger;
	}

	public static Logger getLogger(ServletContext context) {
		if (logger == null) {

			try {

				String name = context.getServletContextName();

				String directory = "./servers/" + System.getProperty("weblogic.Name") + "/logs/" + name;
				File file = new File(directory);
				file.mkdirs();
				String filepath = directory + "/" + name + ".log";

				Formatter formatter = new LogFormatter();
				Handler handler = new FileHandler(filepath, 10 * 1024 * 1024, 10, true);
				// Handler handler = new FileHandler(filepath);

				handler.setFormatter(formatter);

				logger = new Logger(context.getServletContextName(), null);

				// logger.setParent(KernelLogManager.getLogger());

				logger.addHandler(handler);
				logger.setParent(KernelLogManager.getLogger());
				logger.setUseParentHandlers(false);

				// logger.setLevel(KernelLogManager.getLogger().getLevel());

			} catch (Exception e) {
				e.printStackTrace();
			}

		}

		return logger;
	}

	public void severe(Exception e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		log(Level.SEVERE, sw.toString());
	}

	public void log(Level level, Direction direction, SipServletMessage message) {
		log(level, direction, message, null);
	}

	public void log(Level level, Direction direction, SipServletMessage message, String comments) {
		if (this.isLoggable(level)) {
			String msg;
			if (comments != null) {
				msg = " (" + comments + ")";
			} else {
				msg = "";
			}

			if (message instanceof SipServletRequest) {
				SipServletRequest request = (SipServletRequest) message;

				if (direction == Direction.RECEIVE) {
					log(level, recv(request) + msg);
				} else {
					log(level, send(request) + msg);
				}

			} else {
				SipServletResponse response = (SipServletResponse) message;

				if (direction == Direction.RECEIVE) {
					log(level, recv(response) + msg);
				} else {
					log(level, send(response) + msg);
				}
			}
		}
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

	private String from(SipServletRequest rqst) throws ServletParseException {
		String name = null;
		SipURI fromUri = (SipURI) rqst.getFrom().getURI();
		SipURI contactUri = (SipURI) rqst.getAddressHeader("Contact").getURI();

		name = fromUri.getUser();
		if (name == null) {
			name = fromUri.getUser() + "@" + contactUri.getHost() + ":" + contactUri.getPort();
		} else {
			name = contactUri.getHost() + ":" + contactUri.getPort();
		}

		return name;
	}

	private String to(SipServletRequest rqst) {
		String name = null;
		SipURI toUri = (SipURI) rqst.getTo().getURI();
		SipURI requestUri = (SipURI) rqst.getRequestURI();

		name = toUri.getUser();
		if (name != null) {
			name = toUri.getUser() + "@" + requestUri.getHost() + ":" + requestUri.getPort();
		} else {
			name = requestUri.getHost() + ":" + requestUri.getPort();
		}

		return name;
	}

	private String from(SipServletResponse rspn) throws ServletParseException {
		String name = null;
		SipURI fromUri = (SipURI) rspn.getTo().getURI();
		SipURI contactUri = (SipURI) rspn.getAddressHeader("Contact").getURI();

		return name;
	}

	private String to(SipServletResponse rspn) {
		String name = null;

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

	public static String recv(SipServletRequest req) {
		return hexHash(req.getSession()) + " " + ((SipURI) (req.getFrom().getURI())).getUser() + " --" + req.getMethod() + "--> "
				+ ((SipURI) (req.getTo().getURI())).getUser();
	}

	public static String send(SipServletResponse resp) {
		return hexHash(resp.getSession()) + " " + ((SipURI) (resp.getFrom().getURI())).getUser() + " <--" + resp.getStatus() + " " + resp.getReasonPhrase() + " ("
				+ resp.getMethod().toLowerCase() + ") -- " + ((SipURI) (resp.getTo().getURI())).getUser();
	}

	public static String send(SipServletRequest req) {
		return hexHash(req.getSession()) + " " + ((SipURI) (req.getTo().getURI())).getUser() + " <--" + req.getMethod() + "-- "
				+ ((SipURI) (req.getFrom().getURI())).getUser();
	}

	public static String recv(SipServletResponse resp) {
		return hexHash(resp.getSession()) + " " + ((SipURI) (resp.getTo().getURI())).getUser() + " --" + resp.getStatus() + " " + resp.getReasonPhrase() + " ("
				+ resp.getMethod().toLowerCase() + ") --> " + ((SipURI) (resp.getFrom().getURI())).getUser();
	}

}
