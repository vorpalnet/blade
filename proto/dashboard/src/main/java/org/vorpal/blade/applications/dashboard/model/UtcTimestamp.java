package org.vorpal.blade.applications.dashboard.model;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/// Reads the analytics database's timestamps as UTC, whatever zone this JVM
/// runs in.
///
/// **The columns hold UTC wall-clock time.** They are plain `TIMESTAMP`s with
/// no zone, and JDBC reads one in the JVM's default zone. A dashboard on a
/// server set to local time would otherwise shift every stored instant by its
/// offset, and the analytics service writing in one zone while the dashboard
/// reads in another would shift them twice. The analytics service writes
/// through the same conversion (its own `UtcTimestamp`), so the database holds
/// one clock however the servers are set.
///
/// **Query parameters go through it too.** EclipseLink applies an attribute's
/// converter to a parameter compared with that attribute, so a query binds the
/// plain instant (`c.startedAt >= :since` with a `Date`). Binding the result of
/// [#toDb] instead converts it twice and shifts the window by the JVM's offset.
///
/// **The database side is declared `Object`, not `Timestamp`,** because the
/// driver decides what a column comes back as. MySQL's Connector/J returns a
/// `DATETIME` as a `LocalDateTime`, and in an aggregate (`MIN(c.startedAt)`)
/// EclipseLink passes the driver's value straight through: a converter typed
/// `Timestamp` fails there with a cast it cannot see. [#fromDb] takes any of
/// the three shapes.
@Converter
public class UtcTimestamp implements AttributeConverter<Date, Object> {

	@Override
	public Object convertToDatabaseColumn(Date instant) {
		return toDb(instant);
	}

	@Override
	public Date convertToEntityAttribute(Object column) {
		return fromDb(column);
	}

	/// The instant as a timestamp whose wall clock reads UTC.
	public static Timestamp toDb(Date instant) {
		return instant == null ? null
				: Timestamp.valueOf(LocalDateTime.ofInstant(instant.toInstant(), ZoneOffset.UTC));
	}

	/// The instant a UTC wall-clock time stands for, as whichever type the
	/// driver read it as: a `Timestamp`, a `LocalDateTime`, or a `Date`
	/// already converted.
	public static Date fromDb(Object column) {
		if (column instanceof Timestamp) {
			return Date.from(((Timestamp) column).toLocalDateTime().toInstant(ZoneOffset.UTC));
		}
		if (column instanceof LocalDateTime) {
			return Date.from(((LocalDateTime) column).toInstant(ZoneOffset.UTC));
		}
		return (Date) column;
	}
}
