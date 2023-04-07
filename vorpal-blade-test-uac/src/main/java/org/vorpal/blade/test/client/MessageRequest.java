package org.vorpal.blade.test.client;

import java.util.List;

public class MessageRequest {
	public String requestURI;
	public String fromAddress;
	public String toAddress;
	public List<Header> headers;
	public String contentType;
	public String content;
}
