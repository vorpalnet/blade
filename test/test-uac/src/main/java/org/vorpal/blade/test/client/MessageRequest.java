package org.vorpal.blade.test.client;

import java.util.List;

/// Request model for the single-call `POST /api/v1/connect` API.
///
/// The body comes from, in priority order: the named `scenario`'s template,
/// the `template` file, or the inline `content`/`contentType` pair. A
/// `scenario` additionally applies its transformation rules to the INVITE
/// and its responses.
public class MessageRequest {
	public String requestURI;
	public String fromAddress;
	public String toAddress;
	public List<Header> headers;
	public String contentType;
	public String content;
	public String template;
	public String scenario;
}
