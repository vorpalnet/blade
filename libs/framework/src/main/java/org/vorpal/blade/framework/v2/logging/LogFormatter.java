/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package org.vorpal.blade.framework.v2.logging;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/**
 * Custom log formatter that outputs log records in a compact, readable format.
 * Format: LEVEL YYYY-MM-DD HH:mm:ss.SSS - message
 */
public final class LogFormatter extends Formatter {

	private static final String LINE_SEPARATOR = System.getProperty("line.separator");
	private static final String DATE_FORMAT_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";
	private static final int LEVEL_PADDING = 7;
	private static final int INITIAL_BUFFER_SIZE = 1000;

	// ThreadLocal for thread-safe date formatting
	private static final ThreadLocal<SimpleDateFormat> dateFormatter = ThreadLocal
			.withInitial(() -> new SimpleDateFormat(DATE_FORMAT_PATTERN));

	/**
	 * Formats a log record into a readable string.
	 *
	 * @param record the log record to format
	 * @return the formatted log string
	 */
	@Override
	public String format(LogRecord record) {
		if (record == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder(INITIAL_BUFFER_SIZE);
		builder.append(padRight(record.getLevel().toString(), LEVEL_PADDING));
		builder.append(" ").append(dateFormatter.get().format(new Date(record.getMillis())));
		builder.append(" - ");
		builder.append(formatMessage(record));
		builder.append(LINE_SEPARATOR);
		return builder.toString();
	}

	@SuppressWarnings("unused")
	private static String padLeft(String str, int length) {
		return String.format("%1$" + length + "s", str);
	}

	private static String padRight(String str, int length) {
		return String.format("%1$-" + length + "s", str);
	}
}
