package org.vorpal.blade.services.proxy.balancer;

import java.io.IOException;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.services.proxy.balancer.ProxyBalancerConfig.EndpointStatus;

public class OptionsPingCallflow extends Callflow {
	private static final long serialVersionUID = 1L;
	private String timerId;
	private ProxyBalancerConfig config;

	public OptionsPingCallflow(ProxyBalancerConfig config) {
		this.config = config;
	}

	public String getTimerId() {
		return timerId;
	}

	public void setTimerId(String timerId) {
		this.timerId = timerId;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		SipApplicationSession appSession = request.getApplicationSession();
		int seconds = ProxyBalancerServlet.settingsManager.getCurrent().getPingInterval();

		timerId = this.schedulePeriodicTimer(appSession, seconds, (timer) -> {

			for (Entry<String, EndpointStatus> endpointEntry : config.endpointStatus.entrySet()) {

				SipServletRequest options = sipFactory.createRequest(sipFactory.createApplicationSession(), "OPTIONS",
						ProxyBalancerServlet.servletContextName, endpointEntry.getKey());
				sendRequest(options, (response) -> {
					if (response.getStatus() == 200) {
						endpointEntry.setValue(EndpointStatus.up);
					} else {
						endpointEntry.setValue(EndpointStatus.down);
					}

				});

			}

		});

	}

}
