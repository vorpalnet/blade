package org.vorpal.blade.framework.v3.configuration.routing;

import com.bea.wcp.sip.engine.server.SipServletRequestImpl;
import com.bea.wcp.sip.engine.server.SipSessionImpl;
import com.bea.wcp.sip.engine.server.proxy.ProxyImpl;
import java.lang.reflect.Field;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipServletRequest;

/**
 * Per-application utility for disabling Record-Route (loose routing
 * drop-out) from within a SIP Servlet, without the server-level
 * LooseRoutingFilter.
 *
 * ===================================================================
 * THE PROBLEM
 * ===================================================================
 *
 * The standard JSR 289 Proxy.setRecordRoute(false) works, but only
 * if called BEFORE proxy.proxyTo(). Once the proxy has started, the
 * setter throws IllegalStateException. Additionally, the JSR 289
 * Proxy interface is actually a ProxyAdapter that wraps ProxyImpl,
 * and ProxyAdapter.getImpl() is package-private -- so servlets in
 * user packages can't reach ProxyImpl methods directly.
 *
 * ===================================================================
 * THREE APPROACHES (choose one)
 * ===================================================================
 *
 * APPROACH 1: Standard API (preferred when possible)
 * ---------------------------------------------------
 *   Call Proxy.setRecordRoute(false) before proxyTo(). This is the
 *   documented JSR 289 way and works fine if you control the proxy
 *   creation flow:
 *
 *     Proxy proxy = request.getProxy();
 *     proxy.setRecordRoute(false);
 *     proxy.proxyTo(uri);
 *
 *   Limitation: must be called before start. Cannot be used to
 *   selectively disable RR on some branches after the fact, or in
 *   application composition where a prior app already started the
 *   proxy with Record-Route enabled.
 *
 * APPROACH 2: Package-level access (if your class is in the right package)
 * -------------------------------------------------------------------------
 *   If your servlet or helper class is in the
 *   com.bea.wcp.sip.engine.server package (or a subpackage that can
 *   access protected members), you can reach ProxyImpl directly:
 *
 *     SipServletMessageImpl msgImpl = (SipServletMessageImpl) request;
 *     ProxyImpl proxyImpl = msgImpl.proxy;  // protected field
 *     // proxyImpl is the unwrapped implementation
 *
 *   The SipServletMessageImpl.proxy field is declared as:
 *     protected ProxyImpl proxy;  (SipServletMessageImpl.java line 95)
 *
 *   And SipSessionImpl exposes:
 *     public void setRecordingRoutingProxy(boolean)  (line 1207)
 *     public boolean isRecordRoutingProxy()           (line 1187)
 *
 * APPROACH 3: Reflection (works from any package)
 * -------------------------------------------------
 *   Use the static methods in this class. They use reflection to
 *   reach the ProxyImpl through the adapter chain and toggle the
 *   record-route flag, even after the proxy has started.
 *
 * ===================================================================
 * USAGE FROM A SIP SERVLET
 * ===================================================================
 *
 *   protected void doInvite(SipServletRequest request) {
 *       // Option A: disable before proxy starts (standard API)
 *       Proxy proxy = request.getProxy();
 *       proxy.setRecordRoute(false);
 *       proxy.proxyTo(request.getRequestURI());
 *
 *       // Option B: force disable via reflection (works anytime)
 *       LooseRoutingHelper.disableRecordRoute(request);
 *   }
 *
 * ===================================================================
 * HOW IT WORKS (Approach 3 -- reflection path)
 * ===================================================================
 *
 *   ProxyAdapter (what the servlet sees as "Proxy")
 *       |
 *       +-- field "impl" (private) --> ProxyImpl
 *               |
 *               +-- field "recordRoute" (private) --> boolean
 *               +-- field "started" (private) --> boolean
 *
 *   SipServletRequestAdapter (what the servlet sees as "SipServletRequest")
 *       |
 *       +-- field "impl" (package-private) --> SipServletRequestImpl
 *               |
 *               +-- inherited field "proxy" (protected) --> ProxyImpl
 *
 *   SipSessionImpl:
 *       +-- method setRecordingRoutingProxy(boolean) (public)
 *       +-- flag bit 512 in "flags" field
 *
 *   The reflection approach goes through the adapter to reach ProxyImpl,
 *   then directly sets recordRoute = false and clears the started flag
 *   if needed to allow the change.
 */
public class LooseRoutingHelper {

    private static Field proxyAdapterImplField;
    private static Field proxyImplRecordRouteField;
    private static Field proxyImplStartedField;
    private static Field requestAdapterImplField;
    private static Field messageImplProxyField;
    private static boolean reflectionInitialized;
    private static boolean reflectionFailed;

    /**
     * Disable Record-Route on the proxy associated with the given
     * request. Uses reflection to bypass the ProxyAdapter wrapper and
     * the "already started" check. Safe to call at any point.
     *
     * Also clears the session-level isRecordRoutingProxy flag.
     *
     * @return true if successful, false if reflection failed
     */
    public static boolean disableRecordRoute(SipServletRequest request) {
        initReflection();
        if (reflectionFailed) {
            return false;
        }

        try {
            // Unwrap to get ProxyImpl
            ProxyImpl proxyImpl = getProxyImpl(request);
            if (proxyImpl == null) {
                return false;
            }

            // Set recordRoute = false directly, bypassing the started check
            proxyImplRecordRouteField.set(proxyImpl, false);

            // Also clear the session-level flag
            SipServletRequestImpl reqImpl = getRequestImpl(request);
            if (reqImpl != null) {
                SipSessionImpl session = reqImpl.getSessionImpl();
                if (session != null) {
                    session.setRecordingRoutingProxy(false);
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check whether Record-Route is enabled on the proxy for this request.
     *
     * @return true if record-routing, false if not or if no proxy exists
     */
    public static boolean isRecordRouting(SipServletRequest request) {
        initReflection();
        if (reflectionFailed) {
            return false;
        }

        try {
            ProxyImpl proxyImpl = getProxyImpl(request);
            if (proxyImpl == null) {
                return false;
            }
            return (Boolean) proxyImplRecordRouteField.get(proxyImpl);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Internal reflection helpers ----

    private static ProxyImpl getProxyImpl(SipServletRequest request) throws Exception {
        // Path 1: go through the request to get ProxyImpl directly
        SipServletRequestImpl reqImpl = getRequestImpl(request);
        if (reqImpl != null && messageImplProxyField != null) {
            ProxyImpl proxy = (ProxyImpl) messageImplProxyField.get(reqImpl);
            if (proxy != null) {
                return proxy;
            }
        }

        // Path 2: go through the Proxy API -> ProxyAdapter -> ProxyImpl
        try {
            Proxy proxy = request.getProxy(false);
            if (proxy == null) {
                return null;
            }
            if (proxy instanceof ProxyImpl) {
                return (ProxyImpl) proxy;
            }
            if (proxyAdapterImplField != null && proxyAdapterImplField.getDeclaringClass().isInstance(proxy)) {
                return (ProxyImpl) proxyAdapterImplField.get(proxy);
            }
        } catch (Exception e) {
            // getProxy(false) can throw TooManyHopsException
        }

        return null;
    }

    private static SipServletRequestImpl getRequestImpl(SipServletRequest request) {
        if (request instanceof SipServletRequestImpl) {
            return (SipServletRequestImpl) request;
        }

        // Unwrap SipServletRequestAdapter
        try {
            if (requestAdapterImplField != null && requestAdapterImplField.getDeclaringClass().isInstance(request)) {
                return (SipServletRequestImpl) requestAdapterImplField.get(request);
            }
        } catch (Exception e) {
            // fall through
        }

        return null;
    }

    private static synchronized void initReflection() {
        if (reflectionInitialized) {
            return;
        }
        reflectionInitialized = true;

        try {
            // ProxyAdapter.impl -> ProxyImpl
            Class proxyAdapterClass = Class.forName("com.bea.wcp.sip.engine.ProxyAdapter");
            proxyAdapterImplField = proxyAdapterClass.getDeclaredField("impl");
            proxyAdapterImplField.setAccessible(true);

            // ProxyImpl.recordRoute -> boolean
            Class proxyImplClass = Class.forName("com.bea.wcp.sip.engine.server.proxy.ProxyImpl");
            proxyImplRecordRouteField = proxyImplClass.getDeclaredField("recordRoute");
            proxyImplRecordRouteField.setAccessible(true);

            // ProxyImpl.started -> boolean
            proxyImplStartedField = proxyImplClass.getDeclaredField("started");
            proxyImplStartedField.setAccessible(true);

            // SipServletRequestAdapter.impl -> SipServletRequestImpl
            Class requestAdapterClass = Class.forName("com.bea.wcp.sip.engine.SipServletRequestAdapter");
            requestAdapterImplField = requestAdapterClass.getDeclaredField("impl");
            requestAdapterImplField.setAccessible(true);

            // SipServletMessageImpl.proxy -> ProxyImpl
            Class messageImplClass = Class.forName("com.bea.wcp.sip.engine.server.SipServletMessageImpl");
            messageImplProxyField = messageImplClass.getDeclaredField("proxy");
            messageImplProxyField.setAccessible(true);

        } catch (Exception e) {
            reflectionFailed = true;
        }
    }
}
