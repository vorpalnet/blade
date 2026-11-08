package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Translation map using HashMap for key-based lookups.
 *
 * @deprecated Use an exact-match table: a v3 {@code TranslationTable} with {@code match: hash}
 *             ({@code org.vorpal.blade.framework.v3.configuration.translations}),
 *             read by the v3 {@code RouterConfiguration} pipeline.
 */
@Deprecated
public class ConfigHashMap extends TranslationsMap implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Hash map of translation entries keyed by lookup value")
	public HashMap<String, Translation> map = new HashMap<>();

	public int size() {
		return map.size();
	}

	@Override
	public Translation lookup(SipServletRequest request) {
		Translation value = null;

		try {
			for (Selector selector : this.selectors) {

				RegExRoute regexRoute = selector.findKey(request);

				if (regexRoute != null) {
					value = map.get(regexRoute.key);
				}

				if (value != null) {
					value = new Translation(value);
					if (value.getAttributes() == null) {
						value.setAttributes(new HashMap<>());
					}
					if (regexRoute.attributes != null) {
						value.getAttributes().putAll(regexRoute.attributes);
					}
					break;
				}
			}

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(e);
		}

		return value;
	}

	@Override
	public Translation createTranslation(String key) {
		Translation value = new Translation();
		map.put(key, value);
		return value;
	}

}
