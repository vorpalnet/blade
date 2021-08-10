package vorpal.alice.logging;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

@Deprecated
public final class LogFormatter extends Formatter {

	private static final String LINE_SEPARATOR = System.getProperty("line.separator");
	private static final DateFormat df = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS");

	@Override
	public String format(LogRecord record) {
		Thread t = Thread.currentThread();
		StringBuilder builder = new StringBuilder(1000);

		builder.append(padRight(record.getLevel(), 7));
		builder.append(" ").append("T").append(String.format("%03d", t.getId()));
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
