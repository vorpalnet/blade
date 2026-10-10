package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Objects;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/// A key a call was filed under (`v_call_keys`): a configured selector's
/// match, or the caller's `account` once an application identified it. One
/// row per call, name and value, which together are its identity. See
/// [CallRow] for why these map views.
@Entity
@Table(name = "v_call_keys")
@IdClass(KeyRow.Key.class)
public class KeyRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "call_id")
	private String callId;

	@Id
	@Column(name = "key_name")
	private String keyName;

	@Id
	@Column(name = "key_value")
	private String keyValue;

	private String tenant;

	/// The composite identity.
	public static class Key implements Serializable {
		private static final long serialVersionUID = 1L;
		public String callId;
		public String keyName;
		public String keyValue;

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Key)) {
				return false;
			}
			Key k = (Key) o;
			return Objects.equals(callId, k.callId) && Objects.equals(keyName, k.keyName)
					&& Objects.equals(keyValue, k.keyValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(callId, keyName, keyValue);
		}
	}
}
