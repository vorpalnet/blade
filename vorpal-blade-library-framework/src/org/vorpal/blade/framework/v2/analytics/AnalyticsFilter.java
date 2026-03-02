package org.vorpal.blade.framework.v2.analytics;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

@WebFilter(filterName = "AnalyticsFilter", urlPatterns = "/*")
public class AnalyticsFilter implements Filter {

	public static final String ANALYTICS_ATTRIBUTE = "org.vorpal.blade.analytics";

	@Override
	public void init(FilterConfig config) throws ServletException {
		// do nothing;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		Analytics analytics = SettingsManager.getAnalytics();

		if (analytics != null && request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
			HttpServletRequest httpRequest = (HttpServletRequest) request;
			HttpServletResponse httpResponse = (HttpServletResponse) response;

			Logger sipLogger = SettingsManager.getSipLogger();

			// Wrap the response to capture the body
			BufferedResponseWrapper wrappedResponse = new BufferedResponseWrapper(httpResponse);

			
			// jwm - test
//			SettingsManager.createEvent("callStarted", bobRequest);
//			if (b2buaListener != null) {
//				b2buaListener.callStarted(bobRequest);
//			}
//			SettingsManager.sendEvent(bobRequest);

			
			
			// Let the servlet process the request
			chain.doFilter(request, wrappedResponse);

			// Now build and send events with both request and response attributes
			byte[] responseBody = wrappedResponse.getBody();

			for (Map.Entry<String, EventSelector> entry : analytics.getEvents().entrySet()) {
				try {
					// Create event with origin (request) attributes
					Event event = analytics.createEvent(entry.getKey(), httpRequest);

					// Add destination (response) attributes
					analytics.addDestinationAttributes(event, wrappedResponse, responseBody);

					if (event.getAttributes() != null && !event.getAttributes().isEmpty()) {
						analytics.sendEvent(event);
					}
				} catch (Exception e) {
					if (sipLogger != null) {
						sipLogger.log(Level.WARNING, "AnalyticsFilter error processing event: " + entry.getKey(), e);
					}
				}
			}

			// Copy the buffered response body to the actual response
			wrappedResponse.copyBodyToResponse();

		} else {
			chain.doFilter(request, response);
		}
	}

	@Override
	public void destroy() {
		// do nothing;
	}

}
