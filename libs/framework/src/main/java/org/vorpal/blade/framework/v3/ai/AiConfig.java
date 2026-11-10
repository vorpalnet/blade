package org.vorpal.blade.framework.v3.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.vorpal.blade.framework.v2.config.CredentialEncryption;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.ConfigFiles;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Reads the domain's [AiSettings] from `blade-ai.json` on the AdminServer,
/// with its key decrypted, and publishes the file's schema and sample so the
/// Configurator lists `blade-ai` and edits it like any configuration (its
/// Save encrypts the key).
public final class AiConfig {

	/// The configuration's name: `blade-ai.json`, `blade-ai.jschema`.
	public static final String NAME = "blade-ai";

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	private AiConfig() {
	}

	/// The domain file.
	public static Path file() {
		return ConfigFiles.BASE.resolve(NAME + ".json");
	}

	/// True when `blade-ai.json` exists.
	public static boolean exists() {
		return Files.isRegularFile(file());
	}

	/// The settings, key decrypted; the defaults (off) when the file does not
	/// exist or does not read.
	public static AiSettings load() {
		try {
			if (!exists()) return new AiSettings();
			JsonNode tree = MAPPER.readTree(file().toFile());
			CredentialEncryption.decryptTree(tree);
			AiSettings s = MAPPER.treeToValue(tree, AiSettings.class);
			return (s != null) ? s : new AiSettings();
		} catch (Exception e) {
			return new AiSettings();
		}
	}

	/// Writes `_schemas/blade-ai.jschema` and `_samples/blade-ai.json.SAMPLE`
	/// when they are missing, so the Configurator can edit the file. Safe to
	/// call from every editor's start-up.
	public static void publishSchema() {
		try {
			Path schema = ConfigFiles.BASE.resolve("_schemas").resolve(NAME + ".jschema");
			if (!Files.exists(schema)) {
				Files.createDirectories(schema.getParent());
				Files.write(schema, MAPPER.writerWithDefaultPrettyPrinter()
						.writeValueAsBytes(SettingsManager.generateSchemaNode(AiSettings.class, MAPPER)));
			}
			Path sample = ConfigFiles.BASE.resolve("_samples").resolve(NAME + ".json.SAMPLE");
			if (!Files.exists(sample)) {
				Files.createDirectories(sample.getParent());
				Files.write(sample, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(new AiSettings()));
			}
		} catch (IOException | RuntimeException e) {
			// Not fatal: the Configurator just does not list blade-ai yet.
		}
	}
}
