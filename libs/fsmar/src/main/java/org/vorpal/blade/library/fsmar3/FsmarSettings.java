package org.vorpal.blade.library.fsmar3;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.vorpal.blade.framework.v2.config.Settings;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.fsmar.AppRouterConfiguration;
import org.vorpal.blade.framework.v3.fsmar.Fsmar2Converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// FSMAR-specific [Settings] that makes the un-versioned `fsmar.json` config
/// backward-compatible:
///
/// 1. **Legacy filename fallback** — reads `fsmar.json` if present, else the
///    legacy `fsmar2.json` (so an existing FSMAR 2 domain keeps routing after the
///    jar swap, no file rename needed).
/// 2. **Auto-upgrade on load** — a pre-3 / FSMAR 2-shaped config tree is upgraded
///    to FSMAR 3 in memory via [Fsmar2Converter] BEFORE the framework deserializes
///    it (a v2 tree can't deserialize into [AppRouterConfiguration] directly).
///    A current (v3) tree passes through untouched.
///
/// This is the first concrete client of the planned config-versioning
/// upgrade-on-load seam: the version lives INSIDE the config (the framework
/// `version` field), the filename is generic. The conversion is the same
/// `Fsmar2Converter` the Flow editor uses; because it **fails closed**
/// (unconvertible conditions become `when:"false"`), a forgotten migration
/// under-routes and logs loudly rather than mis-routing — but the intended path
/// is still: convert + review + re-save in the Flow editor.
public class FsmarSettings extends Settings<AppRouterConfiguration> {

	/// The legacy FSMAR 2 config filename, in the same domain directory.
	private final Path legacyDomain;

	public FsmarSettings(Class<AppRouterConfiguration> clazz,
			SettingsManager<AppRouterConfiguration> settingsManager, String configName,
			ObjectMapper objectMapper, AppRouterConfiguration sampleConfig) {
		super(clazz, settingsManager, configName, objectMapper, sampleConfig);
		this.legacyDomain = Paths.get(settingsManager.getDomainPath() + "/fsmar2.json");
	}

	/// Read `fsmar.json` if it exists; otherwise fall back to the legacy
	/// `fsmar2.json`. (Returns the primary path when neither exists, so the
	/// "use sample config" branch in [#reload] behaves as usual.)
	@Override
	protected File domainFile() {
		File primary = super.domainFile();
		if (primary.exists()) {
			return primary;
		}
		File legacy = legacyDomain.toFile();
		return legacy.exists() ? legacy : primary;
	}

	/// Upgrade a pre-3 / FSMAR 2-shaped config tree to FSMAR 3 before the
	/// framework deserializes it. Applies to every overlay (domain/cluster/
	/// server). A current tree is returned unchanged.
	@Override
	protected JsonNode readConfigTree(File file, String configType) throws IOException {
		JsonNode tree = super.readConfigTree(file, configType);
		if (!isLegacy(tree)) {
			return tree;
		}

		sipLogger.severe("FSMAR config '" + file.getName() + "' (" + configType
				+ ") is a pre-3 (FSMAR 2) configuration — auto-upgrading to FSMAR 3 in memory. "
				+ "This is a safety net: open it in the Flow editor, review the conversion, and "
				+ "re-save as fsmar.json.");
		try {
			Fsmar2Converter.Result result = Fsmar2Converter.convert(tree);
			String json = Fsmar2Converter.toValidatedJson(result);
			for (String w : result.warnings) {
				sipLogger.warning("FSMAR auto-upgrade: " + w);
			}
			return objectMapper.readTree(json);
		} catch (Exception e) {
			sipLogger.severe("FSMAR auto-upgrade FAILED for " + file.getName() + ": " + e
					+ " — leaving the config as-is (it will fail to load).");
			return tree;
		}
	}

	/// A tree needs upgrading when it explicitly declares a version below 3, or
	/// is FSMAR 2-shaped (a `previous` map and no v3 `states`). A v3 tree (which
	/// has `states`, and whose `version` defaults to 3) returns false.
	static boolean isLegacy(JsonNode tree) {
		if (tree == null || !tree.isObject()) {
			return false;
		}
		JsonNode version = tree.get("version");
		if (version != null && version.canConvertToInt() && version.asInt() < 3) {
			return true;
		}
		return tree.has("previous") && !tree.has("states");
	}
}
