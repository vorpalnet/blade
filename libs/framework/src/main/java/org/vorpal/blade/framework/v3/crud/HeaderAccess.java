package org.vorpal.blade.framework.v3.crud;

import java.lang.reflect.Field;

import javax.servlet.sip.Address;

import com.bea.wcp.sip.engine.AddressAdapter;
import com.bea.wcp.sip.engine.ParameterableAdapter;
import com.bea.wcp.sip.engine.server.AddressImpl;
import com.bea.wcp.sip.engine.server.SipURIImpl;
import com.bea.wcp.sip.engine.server.SipURIProperties;

/// Lets the CRUD rules rewrite `To` and `From` on a request the application
/// received, not only on one it created.
///
/// The container lets an application change the URI and display name of `To`
/// and `From` on a request it sends, but refuses on a request it received,
/// with `IllegalStateException("the uri is read only")`. A proxy forwards the
/// request it received, so without this a passthru call, the iRouter and any
/// other proxy could never have those headers rewritten. The refusal is a
/// property of the address object, not of the message: each address carries a
/// set of mutation rules, and a received request's `To` and `From` get the
/// read-only set. Its SIP URI has its own set, in which only the user part
/// and URI headers can change. [#unlock] gives both the rules the container
/// gives a request the application sends. The `tag` parameter stays
/// protected, as it is there.
///
/// The other way to lift the refusal is the container's own system property,
/// `wlss.enable_modify_to_from=true`, which lifts it for every application on
/// the engine. This class lifts it for the one address a rule is changing.
///
/// Every failure here (another container, a changed one, a message that is
/// not the container's) returns false, and the caller reports the original
/// refusal.
final class HeaderAccess {

	/// The adapter's reference to the address it wraps. Null when the
	/// container's classes are not there or have changed.
	private static final Field IMPL = implField();

	private HeaderAccess() {
	}

	/// Gives `address`, the `To` or `From` of a message, the mutation rules of
	/// a request the application sends.
	///
	/// @param address the address returned by `getTo()` or `getFrom()`
	/// @param header  `To` or `From`
	/// @return true if the address can now be changed
	static boolean unlock(Address address, String header) {
		if (IMPL == null || !(address instanceof ParameterableAdapter)) {
			return false;
		}
		try {
			AddressImpl impl = (AddressImpl) IMPL.get(address);
			impl.setMutationProperties(AddressAdapter.getAddressProperties(true, header.toLowerCase(), true));
			if (impl.getURI() instanceof SipURIImpl) {
				// the rules of a sent request's To and From URI: all of it
				((SipURIImpl) impl.getURI()).setMutationProperties(SipURIProperties.SIPURI_PROPERTIES_ALL_MUTABLE);
			}
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	private static Field implField() {
		try {
			Field field = ParameterableAdapter.class.getDeclaredField("impl");
			field.setAccessible(true);
			return field;
		} catch (Throwable notThisContainer) {
			return null;
		}
	}
}
