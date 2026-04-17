package org.vorpal.blade.framework.v3.configuration.routing;

import com.bea.wcp.sip.engine.Container;
import com.bea.wcp.sip.engine.server.Filter;
import com.bea.wcp.sip.engine.server.FilterContext;
import com.bea.wcp.sip.engine.server.SipServletMessageImpl;
import com.bea.wcp.sip.engine.server.SipServletRequestImpl;
import com.bea.wcp.sip.engine.server.SipServletResponseImpl;
import com.bea.wcp.sip.engine.server.SipSessionImpl;
import com.bea.wcp.sip.engine.server.header.HeaderField;
import com.bea.wcp.sip.engine.server.header.HeaderType;
import com.bea.wcp.sip.engine.server.proxy.ProxyImpl;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipURI;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OCCAS engine-level filter that implements SIP loose routing drop-out.
 *
 * When active, OCCAS proxies the initial request but removes itself
 * from the dialog's route set so that subsequent in-dialog messages
 * (re-INVITE, BYE, etc.) flow directly between the UAs.
 *
 * ===================================================================
 * DEPLOYMENT SCOPE
 * ===================================================================
 *
 * This filter is SERVER-LEVEL (configured in sipserver.xml), not
 * per-WAR. It sees ALL SIP messages for ALL deployed applications.
 *
 * Two modes are supported:
 *
 *   1. GLOBAL MODE (default) -- strips OCCAS Record-Route entries
 *      from all outgoing messages, removing the entire OCCAS instance
 *      from every dialog's route set.
 *
 *   2. APP-AWARE MODE -- only strips Record-Route for messages
 *      belonging to specific named applications. Other apps on the
 *      same OCCAS instance remain in the route set normally.
 *
 * ===================================================================
 * CONFIGURATION (sipserver.xml FilterBean)
 * ===================================================================
 *
 *   className: com.bea.wcp.sip.engine.server.LooseRoutingFilter
 *   parameters:
 *     enabled  - "true" or "false" (default: "true")
 *     methods  - comma-separated SIP methods to apply to (default: all)
 *     apps     - comma-separated deployed app names to apply to
 *                (default: empty = global mode, all apps affected)
 *
 * ===================================================================
 * HOW IT WORKS
 * ===================================================================
 *
 * The filter intercepts OUTGOING messages only (after the Proxy API
 * has already added Record-Route entries during ProxyBranchImpl.start).
 *
 * For each outgoing request or response:
 *   1. Checks whether the message's application (obtained from
 *      FilterContext.getServletContextName, which reads from the
 *      message's SipApplicationSession -> CanaryContext) is targeted.
 *   2. If targeted, strips all Record-Route header values tagged with
 *      the "wlssrrd" parameter (OCCAS's internal Record-Route marker).
 *   3. Clears the transient recordRouteIn/recordRouteOut fields on
 *      requests so updateTransportHeaders() won't re-insert them.
 *   4. Also clears the session-level isRecordRoutingProxy flag so
 *      OCCAS won't expect to stay in the dialog path.
 *
 * Contact headers require no special handling -- the engine's existing
 * saveOriginalContactHeaderField/restoreOriginalContacts lifecycle
 * already preserves the original UA Contact for proxy-mode sessions.
 *
 * ===================================================================
 * ALTERNATIVE: PER-APP PROXY API APPROACH
 * ===================================================================
 *
 * If you prefer per-app control without a server-level filter, see
 * LooseRoutingHelper -- a utility class that can be called from
 * within a SIP Servlet's doInvite() to disable Record-Route on
 * the ProxyImpl directly. That approach uses the fact that
 * SipServletMessageImpl.proxy is a protected field of type ProxyImpl,
 * accessible from the same package or via reflection.
 */
public class LooseRoutingFilter implements Filter {

    private static final Logger logger = Logger.getLogger(LooseRoutingFilter.class.getName());
    private static final String WLSS_RR_PARAM = "wlssrrd";

    private Properties params;
    private String[] targetMethods;
    private Set targetApps;
    private boolean enabled;

    public void init(Container container, Properties props) {
        this.params = props;
        this.enabled = !"false".equalsIgnoreCase((String) props.get("enabled"));

        // Optional: restrict to specific SIP methods
        String methods = (String) props.get("methods");
        if (methods != null && !methods.trim().isEmpty()) {
            this.targetMethods = methods.split("\\s*,\\s*");
        }

        // Optional: restrict to specific deployed app names (app-aware mode)
        String apps = (String) props.get("apps");
        if (apps != null && !apps.trim().isEmpty()) {
            this.targetApps = new HashSet();
            for (String app : apps.split("\\s*,\\s*")) {
                this.targetApps.add(app.trim());
            }
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(
                    "LooseRoutingFilter: app-aware mode, target apps: " + this.targetApps);
            }
        }
    }

    public Properties getParams() {
        return this.params;
    }

    // ---- Request filtering ----

    public SipServletRequestImpl filter(SipServletRequestImpl req, FilterContext ctx)
            throws Exception {

        if (!this.enabled || ctx.isIncoming()) {
            return ctx.filterNext(req);
        }

        if (!this.appliesTo(req.getMethod())) {
            return ctx.filterNext(req);
        }

        if (!this.appIsTargeted(ctx)) {
            return ctx.filterNext(req);
        }

        if (logger.isLoggable(Level.FINE)) {
            logger.fine(
                "LooseRoutingFilter: stripping OCCAS Record-Route from outgoing "
                + req.getMethod() + " [app=" + ctx.getServletContextName() + "]");
        }

        this.stripOccasRecordRoute(req);

        // Clear the transient RR references so updateTransportHeaders()
        // won't re-insert them when the message hits the wire.
        req.clearRRTransients();

        // Clear the session-level flag so OCCAS knows this session
        // is no longer a record-routing proxy.
        SipSessionImpl session = req.getSessionImpl();
        if (session != null) {
            session.setRecordingRoutingProxy(false);
        }

        return ctx.filterNext(req);
    }

    // ---- Response filtering ----

    public SipServletResponseImpl filter(SipServletResponseImpl res, FilterContext ctx)
            throws Exception {

        if (!this.enabled || ctx.isIncoming()) {
            return ctx.filterNext(res);
        }

        SipServletRequestImpl req = (SipServletRequestImpl) res.getRequest();
        if (req == null || !this.appliesTo(req.getMethod())) {
            return ctx.filterNext(res);
        }

        if (!this.appIsTargeted(ctx)) {
            return ctx.filterNext(res);
        }

        if (logger.isLoggable(Level.FINE)) {
            logger.fine(
                "LooseRoutingFilter: stripping OCCAS Record-Route from outgoing "
                + res.getStatus() + " response [app=" + ctx.getServletContextName() + "]");
        }

        this.stripOccasRecordRoute(res);

        return ctx.filterNext(res);
    }

    // ---- Helpers ----

    /**
     * Remove Record-Route header values that contain the "wlssrrd"
     * parameter -- these are the entries OCCAS added. Leaves
     * Record-Route entries from other proxies intact.
     */
    private void stripOccasRecordRoute(SipServletMessageImpl msg) {
        HeaderField rrField = msg.getRecordRoute();
        if (rrField == null || rrField.countValues() == 0) {
            return;
        }

        // Walk backwards so index removal doesn't shift unvisited entries
        for (int i = rrField.countValues() - 1; i >= 0; i--) {
            Object val = rrField.getValue(i);
            if (this.isOccasRecordRoute(val)) {
                rrField.removeValue(i);
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine(
                        "LooseRoutingFilter: removed RR entry: " + val);
                }
            }
        }

        // If all Record-Route entries were ours, remove the header entirely
        if (rrField.countValues() == 0) {
            msg.removeHeader(HeaderType.RECORD_ROUTE);
        }
    }

    /**
     * Check whether a Record-Route value was added by OCCAS.
     * OCCAS tags its RR entries with wlssrrd=in or wlssrrd=out.
     */
    private boolean isOccasRecordRoute(Object value) {
        if (value instanceof Address) {
            Address addr = (Address) value;
            if (addr.getURI() != null && addr.getURI().isSipURI()) {
                SipURI uri = (SipURI) addr.getURI();
                return uri.getParameter(WLSS_RR_PARAM) != null;
            }
        }
        return false;
    }

    /**
     * Check if the current message's application is one we should act on.
     * If no targetApps configured (global mode), always returns true.
     */
    private boolean appIsTargeted(FilterContext ctx) {
        if (this.targetApps == null) {
            return true;
        }
        String appName = ctx.getServletContextName();
        return appName != null && this.targetApps.contains(appName);
    }

    /**
     * Check if this filter should apply to the given SIP method.
     * If no methods were configured, applies to all methods.
     */
    private boolean appliesTo(String method) {
        if (this.targetMethods == null) {
            return true;
        }
        for (String m : this.targetMethods) {
            if (m.equalsIgnoreCase(method)) {
                return true;
            }
        }
        return false;
    }
}
