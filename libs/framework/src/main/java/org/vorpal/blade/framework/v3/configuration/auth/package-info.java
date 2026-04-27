/// Authentication schemes applied to
/// [org.vorpal.blade.framework.v3.configuration.connectors.RestConnector]
/// requests just before they're dispatched.
///
/// The abstract base
/// [org.vorpal.blade.framework.v3.configuration.auth.Authentication] is
/// polymorphic via a `type` discriminator; ten concrete subtypes cover
/// static credentials, the common OAuth 2.0 grants, and the two
/// request-signing schemes most customers need.
///
/// **Static credentials (native — no library):**
///
/// - [org.vorpal.blade.framework.v3.configuration.auth.BasicAuthentication]
///   (`type: basic`) — HTTP Basic Auth.
/// - [org.vorpal.blade.framework.v3.configuration.auth.BearerAuthentication]
///   (`type: bearer`) — static Bearer token.
/// - [org.vorpal.blade.framework.v3.configuration.auth.ApiKeyAuthentication]
///   (`type: apikey`) — API key carried in an arbitrary header.
///
/// **OAuth 2.0 grants (Nimbus-backed, token cached + refreshed):**
///
/// - [org.vorpal.blade.framework.v3.configuration.auth.OAuth2PasswordAuthentication]
///   (`type: oauth2-password`) — RFC 6749 §4.3 Resource Owner Password.
/// - [org.vorpal.blade.framework.v3.configuration.auth.OAuth2ClientCredentialsAuthentication]
///   (`type: oauth2-client`) — RFC 6749 §4.4 Client Credentials.
/// - [org.vorpal.blade.framework.v3.configuration.auth.OAuth2RefreshTokenAuthentication]
///   (`type: oauth2-refresh-token`) — RFC 6749 §6 refresh-token grant.
/// - [org.vorpal.blade.framework.v3.configuration.auth.OAuth2JwtBearerAuthentication]
///   (`type: oauth2-jwt-bearer`) — RFC 7523 signed JWT assertion.
/// - [org.vorpal.blade.framework.v3.configuration.auth.OAuth2SamlBearerAuthentication]
///   (`type: oauth2-saml-bearer`) — RFC 7522 SAML 2.0 assertion.
///
/// **Request-signing (body-aware):**
///
/// - [org.vorpal.blade.framework.v3.configuration.auth.HmacAuthentication]
///   (`type: hmac`) — generic HMAC over a template of the request.
///   Covers GitHub / Shopify / Twilio / Stripe-style webhook signing
///   and most custom HMAC schemes.
/// - [org.vorpal.blade.framework.v3.configuration.auth.AwsSigV4Authentication]
///   (`type: aws-sigv4`) — AWS Signature Version 4 for API Gateway,
///   S3, Lambda function URLs, SNS, DynamoDB, and other AWS services.
///   Hand-rolled, no AWS SDK dep.
///
/// Both request-signing schemes override the three-arg
/// [org.vorpal.blade.framework.v3.configuration.auth.Authentication#applyTo(java.net.http.HttpRequest.Builder, org.vorpal.blade.framework.v3.configuration.Context, org.vorpal.blade.framework.v3.configuration.auth.Authentication.RequestSignature)]
/// overload so they can see the HTTP method, resolved URL, and resolved
/// body at stamp time.
///
/// All OAuth subtypes extend
/// [org.vorpal.blade.framework.v3.configuration.auth.AbstractOAuth2Authentication]
/// which handles the token cache + `synchronized` refresh and delegates
/// the grant-specific `TokenRequest` construction to the subclass.
/// Tokens are treated opaquely — no JWT parsing.
///
/// Every field on every subtype is `${var}`-resolvable, so credentials
/// can come from environment variables, system properties, or values
/// an upstream pipeline stage wrote into the
/// [org.vorpal.blade.framework.v3.configuration.Context].
package org.vorpal.blade.framework.v3.configuration.auth;
