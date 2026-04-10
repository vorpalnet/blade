package org.vorpal.blade.framework.v2.config;

import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import weblogic.security.internal.SerializedSystemIni;
import weblogic.security.internal.encryption.ClearOrEncryptedService;
import weblogic.security.internal.encryption.EncryptionService;

/// Utility for encrypting and decrypting credentials in BLADE
/// configuration files using the WebLogic domain's built-in
/// encryption service.
///
/// ## Convention
///
/// String values in config files use a prefix to indicate their state:
///
/// - **`{CLEARTEXT}secret`** — plaintext that should be encrypted on
///   next save. The Configurator or [SettingsManager] detects this
///   prefix, encrypts the value, and rewrites the config file with
///   the encrypted form.
///
/// - **`{AES}base64...`** — already encrypted by WebLogic's encryption
///   service. Decrypted transparently at runtime by [SettingsManager].
///
/// - **No prefix** — left alone. Not treated as a credential.
///
/// ## How it works
///
/// Uses WebLogic's `ClearOrEncryptedService` backed by the domain's
/// `SerializedSystemIni.dat` key file. This is the same encryption
/// that protects `boot.properties` and other WebLogic config files.
/// No additional keys or keystores are needed.
///
/// ## Usage
///
/// ```java
/// // Check if a value needs encrypting
/// if (CredentialEncryption.isCleartext(value)) {
///     String encrypted = CredentialEncryption.encrypt(value);
///     // encrypted = "{AES}base64..."
/// }
///
/// // Check if a value is encrypted
/// if (CredentialEncryption.isEncrypted(value)) {
///     String plain = CredentialEncryption.decrypt(value);
/// }
/// ```
///
/// ## Outside WebLogic
///
/// When running outside a WebLogic domain (tests, CLI tools), the
/// encryption service is unavailable. All methods fail gracefully:
/// `encrypt()` returns the input unchanged, `decrypt()` returns
/// the input unchanged, and a warning is logged once.
public final class CredentialEncryption {
	private static final Logger logger = Logger.getLogger(CredentialEncryption.class.getName());
	private static final String CLEARTEXT_PREFIX = "{CLEARTEXT}";
	private static final String AES_PREFIX = "{AES}";

	private static ClearOrEncryptedService service;
	private static boolean initialized;
	private static boolean available;

	private CredentialEncryption() {
	}

	/// Returns true if the value has the `{CLEARTEXT}` prefix,
	/// indicating it should be encrypted on next save.
	public static boolean isCleartext(String value) {
		return value != null && value.startsWith(CLEARTEXT_PREFIX);
	}

	/// Returns true if the value is already encrypted (has `{AES}`
	/// or `{3DES}` prefix, as determined by WebLogic's service).
	public static boolean isEncrypted(String value) {
		if (value == null) return false;
		init();
		if (!available) return value.startsWith(AES_PREFIX);
		return service.isEncrypted(value);
	}

	/// Returns true if the value is a credential (either cleartext
	/// waiting to be encrypted, or already encrypted).
	public static boolean isCredential(String value) {
		return isCleartext(value) || isEncrypted(value);
	}

	/// Encrypt a cleartext value. If the value has the `{CLEARTEXT}`
	/// prefix, it is stripped before encryption.
	///
	/// Returns the encrypted string (e.g. `{AES}base64...`).
	/// If the encryption service is unavailable (outside WebLogic),
	/// returns the input unchanged.
	public static String encrypt(String value) {
		if (value == null) return null;
		init();

		// Strip {CLEARTEXT} prefix if present
		String plain = value;
		if (plain.startsWith(CLEARTEXT_PREFIX)) {
			plain = plain.substring(CLEARTEXT_PREFIX.length());
		}

		if (!available) {
			logger.warning("CredentialEncryption: encryption service unavailable, returning plaintext");
			return plain;
		}

		try {
			return service.encrypt(plain);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "CredentialEncryption: encryption failed", e);
			return plain;
		}
	}

	/// Decrypt an encrypted value (e.g. `{AES}base64...`).
	///
	/// Returns the plaintext string. If the value is not encrypted,
	/// returns it unchanged. If the encryption service is unavailable,
	/// returns the input unchanged.
	public static String decrypt(String value) {
		if (value == null) return null;
		init();

		if (!available) {
			logger.warning("CredentialEncryption: encryption service unavailable, returning as-is");
			return value;
		}

		if (!service.isEncrypted(value)) {
			return value;
		}

		try {
			return service.decrypt(value);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "CredentialEncryption: decryption failed", e);
			return value;
		}
	}

	/// Process a config value: if `{CLEARTEXT}`, encrypt it and return
	/// the encrypted form. If `{AES}`, return as-is (already encrypted).
	/// Otherwise return the value unchanged.
	///
	/// This is the method [SettingsManager] calls when saving a config
	/// file — it handles both cases in one call.
	public static String processForSave(String value) {
		if (isCleartext(value)) {
			return encrypt(value);
		}
		return value;
	}

	/// Process a config value for runtime use: if encrypted, decrypt it.
	/// If `{CLEARTEXT}`, strip the prefix and return plaintext.
	/// Otherwise return unchanged.
	///
	/// This is the method [SettingsManager] calls when loading a config
	/// file — it produces the runtime-ready plaintext.
	public static String processForRuntime(String value) {
		if (value == null) return null;
		if (isCleartext(value)) {
			return value.substring(CLEARTEXT_PREFIX.length());
		}
		if (isEncrypted(value)) {
			return decrypt(value);
		}
		return value;
	}

	/// Initialize the WebLogic encryption service (once).
	private static synchronized void init() {
		if (initialized) return;
		initialized = true;

		try {
			EncryptionService es = SerializedSystemIni.getEncryptionService();
			if (es != null) {
				service = new ClearOrEncryptedService(es);
				available = true;
				logger.info("CredentialEncryption: initialized using domain encryption service");
			} else {
				available = false;
				logger.warning("CredentialEncryption: SerializedSystemIni returned null — " +
						"running outside a WebLogic domain?");
			}
		} catch (Exception e) {
			available = false;
			logger.warning("CredentialEncryption: encryption service unavailable — " +
					e.getMessage());
		}
	}

	// ------------------------------------------------------------------
	// JSON tree walkers
	// ------------------------------------------------------------------

	/// Walk a JSON tree and encrypt any `{CLEARTEXT}` string values
	/// to `{AES}`. Modifies the tree in place.
	///
	/// Called by the Configurator before saving a config file to disk.
	///
	/// @param node the JSON tree to process
	/// @return true if any values were encrypted
	public static boolean encryptTree(JsonNode node) {
		boolean changed = false;
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				JsonNode value = field.getValue();
				if (value.isTextual() && isCleartext(value.asText())) {
					obj.put(field.getKey(), processForSave(value.asText()));
					changed = true;
				} else if (value.isObject() || value.isArray()) {
					changed |= encryptTree(value);
				}
			}
		} else if (node.isArray()) {
			ArrayNode arr = (ArrayNode) node;
			for (int i = 0; i < arr.size(); i++) {
				JsonNode element = arr.get(i);
				if (element.isTextual() && isCleartext(element.asText())) {
					arr.set(i, TextNode.valueOf(processForSave(element.asText())));
					changed = true;
				} else if (element.isObject() || element.isArray()) {
					changed |= encryptTree(element);
				}
			}
		}
		return changed;
	}

	/// Walk a JSON tree and decrypt any `{AES}` string values to
	/// plaintext. Modifies the tree in place.
	///
	/// Called by [Settings.reload()] before converting JSON to the
	/// config object for runtime use.
	///
	/// @param node the JSON tree to process
	public static void decryptTree(JsonNode node) {
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				JsonNode value = field.getValue();
				if (value.isTextual() && isEncrypted(value.asText())) {
					obj.put(field.getKey(), decrypt(value.asText()));
				} else if (value.isObject() || value.isArray()) {
					decryptTree(value);
				}
			}
		} else if (node.isArray()) {
			ArrayNode arr = (ArrayNode) node;
			for (int i = 0; i < arr.size(); i++) {
				JsonNode element = arr.get(i);
				if (element.isTextual() && isEncrypted(element.asText())) {
					arr.set(i, TextNode.valueOf(decrypt(element.asText())));
				} else if (element.isObject() || element.isArray()) {
					decryptTree(element);
				}
			}
		}
	}
}
