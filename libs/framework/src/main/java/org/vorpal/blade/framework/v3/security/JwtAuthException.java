package org.vorpal.blade.framework.v3.security;

/// Thrown when a bearer JWT cannot be validated — bad signature, wrong
/// issuer/audience, expired, malformed, or the signing key can't be resolved
/// from the configured JWKS. Callers translate this into an HTTP `401`.
public class JwtAuthException extends Exception {
	private static final long serialVersionUID = 1L;

	public JwtAuthException(String message) {
		super(message);
	}

	public JwtAuthException(String message, Throwable cause) {
		super(message, cause);
	}
}
