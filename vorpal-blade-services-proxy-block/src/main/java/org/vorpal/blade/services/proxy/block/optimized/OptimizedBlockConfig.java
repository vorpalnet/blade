package org.vorpal.blade.services.proxy.block.optimized;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.Configuration;

public class OptimizedBlockConfig extends Configuration {
	private static final long serialVersionUID = 1L;

	public AttributeSelector fromSelector;
	public AttributeSelector toSelector;
	public AttributeSelector ruriSelector;
	public Map<String, OptimizedTranslation> callingNumbers = new HashMap<>();
	public OptimizedTranslation defaultRoute;

	public OptimizedBlockConfig() {
		// default constructor
	}

	public AttributeSelector getFromSelector() {
		return fromSelector;
	}

	public void setFromSelector(AttributeSelector fromSelector) {
		this.fromSelector = fromSelector;
	}

	public AttributeSelector getToSelector() {
		return toSelector;
	}

	public void setToSelector(AttributeSelector toSelector) {
		this.toSelector = toSelector;
	}

	public AttributeSelector getRuriSelector() {
		return ruriSelector;
	}

	public void setRuriSelector(AttributeSelector ruriSelector) {
		this.ruriSelector = ruriSelector;
	}

	public Map<String, OptimizedTranslation> getCallingNumbers() {
		return callingNumbers;
	}

	public void setCallingNumbers(Map<String, OptimizedTranslation> callingNumbers) {
		this.callingNumbers = callingNumbers;
	}

	public OptimizedTranslation getDefaultRoute() {
		return defaultRoute;
	}

	public void setDefaultRoute(OptimizedTranslation defaultRoute) {
		this.defaultRoute = defaultRoute;
	}

	public static String forwardTo(OptimizedBlockConfig config1, SipServletRequest request) {
		String forwardTo = null;

		AttributesKey fromKey = config1.fromSelector.findKey(request);
		// // System.out.println("fromKey.key=" + fromKey.key);

		AttributesKey toKey = config1.toSelector.findKey(request);

		Callflow.getSipLogger().finer(request, "toKey+" + toKey);

		if (toKey != null) {
			Callflow.getSipLogger().finer(request, "toKey.key+" + toKey.key);
		}

		AttributesKey ruriKey = null;
		if (config1.ruriSelector != null) {
			ruriKey = config1.ruriSelector.findKey(request);
		}

		OptimizedTranslation t1 = config1.callingNumbers.get(fromKey.key);
		// // System.out.println("t1=" + t1);

		if (t1 == null) {
			t1 = config1.defaultRoute;
		}

		if (t1 != null) {
			List<String> forwardNumbers;

			// // System.out.println("t1.dialedNumbers=" + t1.dialedNumbers);
			if (t1.dialedNumbers != null) {

//				toKey = config1.toSelector.findKey(request);
//
				OptimizedDialed dialed;
				dialed = t1.dialedNumbers.get(toKey.key);

				if (dialed != null) {
					forwardNumbers = new LinkedList<String>(dialed.forwardTo);

					if (forwardNumbers.size() > 0) {
						Collections.shuffle(forwardNumbers);
					}
					forwardTo = forwardNumbers.get(0);
				} else {
					if (t1.forwardTo != null) {
						forwardNumbers = new LinkedList<String>(t1.forwardTo);
						if (forwardNumbers.size() > 0) {
							Collections.shuffle(forwardNumbers);
						}
						forwardTo = forwardNumbers.get(0);
					}

				}

			} else {

				if (t1.forwardTo != null) {
					forwardNumbers = new LinkedList<String>(t1.forwardTo);
					if (forwardNumbers.size() > 0) {
						Collections.shuffle(forwardNumbers);
					}
					forwardTo = forwardNumbers.get(0);
				}

			}

		} else {
			forwardTo = request.getRequestURI().toString();
		}

		if (forwardTo == null) {
			forwardTo = request.getRequestURI().toString();

		}

		// // System.out.println("resolving variables on: " + forwardTo);

		Map<String, String> attrs = new HashMap<>();

		if (toKey != null && toKey.attributes != null) {
			attrs.putAll(toKey.attributes);
		}

		if (fromKey != null && fromKey.attributes != null) {
			attrs.putAll(fromKey.attributes);
		}

		if (ruriKey != null && ruriKey.attributes != null) {
			attrs.putAll(ruriKey.attributes);
		}

		System.out.println("attrs=" + attrs);

		forwardTo = resolveVariables(attrs, forwardTo);

		try {
			forwardTo = Callflow.getSipFactory().createURI(forwardTo).toString();
		} catch (ServletParseException e) {
			Callflow.getSipLogger().severe(request, "Invalid SIP URI: " + forwardTo);
		}

		return forwardTo;

	}

}
