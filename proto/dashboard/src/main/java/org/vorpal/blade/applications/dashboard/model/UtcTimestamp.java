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
@Converter
public class UtcTimestamp implements AttributeConverter<Date, Timestamp> {

	@Override
	public Timestamp convertToDatabaseColumn(Date instant) {
		return toDb(instant);
	}

	@Override
	public Date convertToEntityAttribute(Timestamp column) {
		return fromDb(column);
	}

	/// The instant as a timestamp whose wall clock reads UTC.
	public static Timestamp toDb(Date instant) {
		return instant == null ? null
				: Timestamp.valueOf(LocalDateTime.ofInstant(instant.toInstant(), ZoneOffset.UTC));
	}

	/// The instant a UTC wall-clock timestamp stands for.
	public static Date fromDb(Timestamp column) {
		return column == null ? null : Date.from(column.toLocalDateTime().toInstant(ZoneOffset.UTC));
	}
}
