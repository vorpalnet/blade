package org.vorpal.blade.framework.v2.analytics;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

public class BufferedRequestWrapper extends HttpServletRequestWrapper {

	private final byte[] body;

	public BufferedRequestWrapper(HttpServletRequest request) throws IOException {
		super(request);
		body = request.getInputStream().readAllBytes();
	}

	@Override
	public ServletInputStream getInputStream() throws IOException {
		ByteArrayInputStream byteStream = new ByteArrayInputStream(body);
		return new ServletInputStream() {
			@Override
			public int read() throws IOException {
				return byteStream.read();
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				return byteStream.read(b, off, len);
			}

			@Override
			public boolean isFinished() {
				return byteStream.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
				// not used
			}
		};
	}

	@Override
	public BufferedReader getReader() throws IOException {
		String encoding = getCharacterEncoding();
		if (encoding == null) {
			encoding = "UTF-8";
		}
		return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), encoding));
	}

	public byte[] getBody() {
		return body;
	}

}
