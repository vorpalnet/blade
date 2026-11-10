package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import javax.servlet.sip.SipServletMessage;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/// Base type for every transformation a [Rule] can apply. Each operation
/// declares its kind via the `type` discriminator (`read`, `create`,
/// `xmlUpdate`, `sdpDelete`, …) so a single ordered `operations` list in
/// JSON describes the full sequence — no separate read/create/update/delete
/// arrays.
///
/// Ordering matters: operations run top-to-bottom, so a read that produces
/// `${variable}` must be listed above any create or update that uses it.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = ReadOperation.class, name = "read"),
		@JsonSubTypes.Type(value = CreateOperation.class, name = "create"),
		@JsonSubTypes.Type(value = UpdateOperation.class, name = "update"),
		@JsonSubTypes.Type(value = DeleteOperation.class, name = "delete"),
		@JsonSubTypes.Type(value = XPathReadOperation.class, name = "xmlRead"),
		@JsonSubTypes.Type(value = XPathCreateOperation.class, name = "xmlCreate"),
		@JsonSubTypes.Type(value = XPathUpdateOperation.class, name = "xmlUpdate"),
		@JsonSubTypes.Type(value = XPathDeleteOperation.class, name = "xmlDelete"),
		@JsonSubTypes.Type(value = JsonPathReadOperation.class, name = "jsonRead"),
		@JsonSubTypes.Type(value = JsonPathCreateOperation.class, name = "jsonCreate"),
		@JsonSubTypes.Type(value = JsonPathUpdateOperation.class, name = "jsonUpdate"),
		@JsonSubTypes.Type(value = JsonPathDeleteOperation.class, name = "jsonDelete"),
		@JsonSubTypes.Type(value = KeepOnlyPartOperation.class, name = "keepPart"),
		@JsonSubTypes.Type(value = SdpReadOperation.class, name = "sdpRead"),
		@JsonSubTypes.Type(value = SdpCreateOperation.class, name = "sdpCreate"),
		@JsonSubTypes.Type(value = SdpUpdateOperation.class, name = "sdpUpdate"),
		@JsonSubTypes.Type(value = SdpDeleteOperation.class, name = "sdpDelete"),
		@JsonSubTypes.Type(value = RejectOperation.class, name = "reject")
})
public interface Operation extends Serializable {

	/// Applies this operation to `msg`. Throws when it fails, so the [Rule]
	/// can put the message back as it was; returns quietly when there is
	/// nothing to do (no such header, no matching part, an unset variable).
	void process(SipServletMessage msg) throws Exception;

	/// Names of session-attribute variables this operation populates when it
	/// runs. Used by [Rule] to clear stale values before a read-heavy rule
	/// re-fires. Non-read operations return an empty list.
	default List<String> variableNames() {
		return Collections.emptyList();
	}

	/// Throws if a regex, XPath or JsonPath in this operation would fail to
	/// compile when a call reaches it. Placeholders stand in for values
	/// ([Placeholders#standIn]). Save runs it ([Crud#problems]), so a typo is
	/// refused in the editor rather than undoing its rule on live calls.
	default void check() throws Exception {
	}
}
