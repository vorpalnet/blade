package org.vorpal.blade.services.router.junk;

import java.util.Iterator;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.apache.commons.collections4.trie.PatriciaTrie;
import org.vorpal.blade.framework.config.SettingsManager;

public class PrefixRoute extends PatriciaTrie<RouteTransitions> {

	public URI lookup(String user, SipServletRequest request) {
		String strRoute = null;
		URI route = null;

		RouteTransitions value = null;

		Iterator<Entry<String, RouteTransitions>> itr = this.entrySet().iterator();
		Entry<String, RouteTransitions> entry = null;
		Entry<String, RouteTransitions> previous = null;

		try {

			while (itr.hasNext()) {
				previous = entry;
				entry = itr.next();

				if (user.startsWith(entry.getKey())) {
					value = entry.getValue();
				}
			}

			for (RouteTransition t : value) {
				if (true == t.condition.checkAll(request)) {
					strRoute = t.next;
				}
				break;
			}

			if (strRoute != null) {

				if (strRoute.matches("^(sips?):([^@]+)(?:@(.+))?$")) {
					route = SettingsManager.getSipFactory().createURI(strRoute);
				} else {
					route = SettingsManager.getSipFactory().createURI("sip:" + strRoute);
				}

			}

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(e);
		}

		return route;
	}

}
