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

package org.vorpal.blade.framework.logging;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public final class LogFormatter extends Formatter {

	private static final String LINE_SEPARATOR = System.getProperty("line.separator");
	private static final DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

	@Override
	public String format(LogRecord record) {
//		Thread t = Thread.currentThread();
		StringBuilder builder = new StringBuilder(1000);

		builder.append(padRight(record.getLevel(), 7));
//		builder.append(" ").append("T").append(String.format("%03d", t.getId()));
		builder.append(" ").append(df.format(new Date(record.getMillis())));
		builder.append(" - ").append(formatMessage(record));
		builder.append(LINE_SEPARATOR);
		return builder.toString();
	}

//	public String getHead(Handler h) {
//		return super.getHead(h);
//	}
//
//	public String getTail(Handler h) {
//		return super.getTail(h);
//	}

	private static String padLeft(Level level, int length) {
		return String.format("%1$" + length + "s", level.toString());
	}

	private static String padRight(Level level, int length) {
		return String.format("%1$-" + length + "s", level.toString());
	}
}
