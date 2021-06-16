package org.vorpal.blade.framework.callflow;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.function.Consumer;

import javax.servlet.sip.SipServletMessage;

@FunctionalInterface
public interface Callback<T> extends Consumer<T>, Serializable {

	@Override
	default void accept(final T elem) {
		try {
			acceptThrows(elem);
		} catch (final Exception ex) {
//			StringWriter sw = new StringWriter();
//			PrintWriter pw = new PrintWriter(sw);
//			ex.printStackTrace(pw);
//			Callflow.logger.warning(sw.toString());
			
			Callflow.sipLogger.logStackTrace(ex);
			throw new RuntimeException(ex);
		}
	}

	void acceptThrows(T t) throws Exception;

}
