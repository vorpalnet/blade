package org.vorpal.blade.framework.v2.analytics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

public class BufferedResponseWrapper extends HttpServletResponseWrapper {

	private ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	private ServletOutputStream outputStream;
	private PrintWriter writer;

	public BufferedResponseWrapper(HttpServletResponse response) {
		super(response);
	}

	@Override
	public ServletOutputStream getOutputStream() throws IOException {
		if (outputStream == null) {
			outputStream = new BufferedServletOutputStream(buffer);
		}
		return outputStream;
	}

	@Override
	public PrintWriter getWriter() throws IOException {
		if (writer == null) {
			String encoding = getCharacterEncoding();
			if (encoding == null) {
				encoding = "UTF-8";
			}
			writer = new PrintWriter(new OutputStreamWriter(buffer, encoding));
		}
		return writer;
	}

	@Override
	public void flushBuffer() throws IOException {
		if (writer != null) {
			writer.flush();
		}
		if (outputStream != null) {
			outputStream.flush();
		}
	}

	public byte[] getBody() throws IOException {
		flushBuffer();
		return buffer.toByteArray();
	}

	public void copyBodyToResponse() throws IOException {
		byte[] body = getBody();
		if (body.length > 0) {
			HttpServletResponse response = (HttpServletResponse) getResponse();
			response.setContentLength(body.length);
			response.getOutputStream().write(body);
			response.getOutputStream().flush();
		}
	}

	private static class BufferedServletOutputStream extends ServletOutputStream {
		private final ByteArrayOutputStream buffer;

		public BufferedServletOutputStream(ByteArrayOutputStream buffer) {
			this.buffer = buffer;
		}

		@Override
		public void write(int b) throws IOException {
			buffer.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			buffer.write(b, off, len);
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener listener) {
			// not used
		}
	}

}
