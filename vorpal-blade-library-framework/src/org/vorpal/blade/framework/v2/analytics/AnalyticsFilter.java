package org.vorpal.blade.framework.v2.analytics;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebFilter(filterName = "AnalyticsFilter", urlPatterns = "/*", asyncSupported = true)
public class AnalyticsFilter implements Filter {

	public static final String ANALYTICS_ATTRIBUTE = "org.vorpal.blade.analytics";

	@Override
	public void init(FilterConfig config) throws ServletException {
		// do nothing;
	}

	public String getRequestBody(HttpServletRequest request) throws IOException {
		StringBuilder stringBuilder = new StringBuilder();
		try (BufferedReader bufferedReader = request.getReader()) {
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				stringBuilder.append(line);
			}
		}
		return stringBuilder.toString();
	}

	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final Pattern pattern = Pattern.compile("([^/]+)/?$");

	private void processAnalytics(Logger sipLogger, Analytics analytics,
			BufferedResponseWrapper wrappedResponse, Event event) throws IOException {

		SipServletRequest sipRequest = Analytics.sipServletRequest.get();
		if (sipRequest != null) {

			sipLogger.severe(sipRequest.getSession(), "AnalyticsFilter.doFilter - sipRequest.id="+sipRequest.getId());

			event.setSessionId(Analytics.getSessionId(sipRequest.getApplicationSession()));
			Analytics.sipServletRequest.remove();
		}else {
			sipLogger.severe("AnalyticsFilter.doFilter - Could not find sipServletRequest.");
		}

		sipLogger.logEvent(sipRequest.getSession(), event);
		analytics.sendEvent(event);

		// Now build and send events with both request and response attributes
		byte[] responseBody = wrappedResponse.getBody();

		if (responseBody != null && responseBody.length > 0) {
			Callflow.getSipLogger().finer("AnalyticsFilter.doFilter - responseBody=" + responseBody);
		}else {
			Callflow.getSipLogger().finer("AnalyticsFilter.doFilter - There is no responseBody!");
		}

		// Copy the buffered response body to the actual response
		wrappedResponse.copyBodyToResponse();
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		Analytics analytics = SettingsManager.getAnalytics();
		Event event = null;

		if (analytics != null && request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
			HttpServletRequest httpRequest = (HttpServletRequest) request;
			HttpServletResponse httpResponse = (HttpServletResponse) response;

			Logger sipLogger = SettingsManager.getSipLogger();

			// Wrap request and response to allow re-reading the body
			BufferedRequestWrapper wrappedRequest = new BufferedRequestWrapper(httpRequest);
			BufferedResponseWrapper wrappedResponse = new BufferedResponseWrapper(httpResponse);

			sipLogger.finer("AnalyticsFilter.doFilter - contentType=" + wrappedRequest.getContentType());
			sipLogger.finer("AnalyticsFilter.doFilter - contextPath=" + wrappedRequest.getContextPath() //
					+ ", pathInfo=" + wrappedRequest.getPathInfo() //
					+ ", pathTranslated=" + wrappedRequest.getPathTranslated() //
					+ ", servletPath=" + wrappedRequest.getServletPath() //
			);

			if (wrappedRequest.getContentType().equals("application/json")) {
				String strJson = getRequestBody(wrappedRequest);
				JsonNode jsonNode = objectMapper.readTree(strJson);

				for (Map.Entry<String, EventSelector> entry : analytics.getEvents().entrySet()) {

					sipLogger.finer("AnalyticsFilter.doFilter - entry.key=" + entry.getKey());

					if (httpRequest.getPathInfo().equals(entry.getKey())) {
						sipLogger.finer("AnalyticsFilter.doFilter - entry.key=" + entry.getKey());
						Matcher matcher = pattern.matcher(httpRequest.getContextPath());
						String name = matcher.replaceAll("$1");
						sipLogger.finer("AnalyticsFilter.doFilter - name=" + name);

						event = SettingsManager.createEvent(entry.getKey(), jsonNode);
					}
				}
			}

			// Let the servlet process the request
			chain.doFilter(wrappedRequest, wrappedResponse);

			if (wrappedRequest.isAsyncStarted()) {
				final Event asyncEvent = event;
				wrappedRequest.getAsyncContext().addListener(new AsyncListener() {
					@Override
					public void onComplete(AsyncEvent e) throws IOException {
						processAnalytics(sipLogger, analytics, wrappedResponse, asyncEvent);
					}
					@Override
					public void onTimeout(AsyncEvent e) throws IOException {}
					@Override
					public void onError(AsyncEvent e) throws IOException {}
					@Override
					public void onStartAsync(AsyncEvent e) throws IOException {}
				});
			} else {
				processAnalytics(sipLogger, analytics, wrappedResponse, event);
			}

		} else {
			chain.doFilter(request, response);
		}
	}

	@Override
	public void destroy() {
		// do nothing;
	}

}
