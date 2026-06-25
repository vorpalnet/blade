// Gumball — BLADE<->Kurento control client. MIT License, (c) 2026 Vorpal Networks, LLC.
//
// Thin client over the Kurento Protocol (JSON-RPC 2.0 over a JSR-356 WebSocket). Verified against
// the Kurento Protocol docs (create/invoke/release/subscribe/onEvent). Uses only javax.websocket
// (inherited javaee-api, provided by WebLogic at runtime) and Jackson (compile-time; runtime from
// the blade-shared library) — no new WEB-INF/lib JAR, so the skinny-WAR rule holds.
//
// Wire shapes (from the docs):
//   create    -> result {value:<objectId>, sessionId:<uuid>}
//   invoke    -> params {object, operation, operationParams, sessionId}; result {value, sessionId}
//   release   -> params {object, sessionId}
//   subscribe -> params {type, object, sessionId}; result {value:<subId>, sessionId}
//   onEvent   -> (server->client, no id) params.value {data{source,type,...}, object, type}

package org.vorpal.blade.proto.gumball.media;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One instance == one control channel to one Kurento Media Server node.
 *
 * <p>Failover contract: this object holds non-serializable live state (WebSocket, futures). A BLADE
 * callflow MUST persist only {@link #url()}, {@link #sessionId()}, and the string object ids, and
 * on failover call {@link #reattach(String, String)} to reclaim the still-running pipeline.
 */
public class KurentoClient {

	private static final ObjectMapper M = new ObjectMapper();
	/** Kurento drops idle sessions; keepalive well under the default timeout. */
	private static final long PING_SECONDS = 30;

	private final URI uri;
	private final Session ws;
	private final AtomicLong nextId = new AtomicLong(1);
	private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	/** dispatch key (sourceObjectId + "|" + eventType) -> listener. */
	private final ConcurrentHashMap<String, Consumer<JsonNode>> eventListeners = new ConcurrentHashMap<>();
	private final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "gumball-kurento-ping");
		t.setDaemon(true);
		return t;
	});

	private volatile String sessionId; // assigned by KMS on first response; echoed thereafter

	private KurentoClient(URI uri, Session ws) {
		this.uri = uri;
		this.ws = ws;
		this.keepalive.scheduleAtFixedRate(this::pingQuietly, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
	}

	// ---- connection lifecycle ----------------------------------------------------------------

	/** Open a fresh control channel (e.g. ws://media-node:8888/kurento). */
	public static KurentoClient connect(String wsUrl) throws IOException {
		try {
			URI uri = URI.create(wsUrl);
			WebSocketContainer c = ContainerProvider.getWebSocketContainer();
			Holder holder = new Holder();
			Session s = c.connectToServer(holder, uri);
			KurentoClient client = new KurentoClient(uri, s);
			holder.bind(client);
			return client;
		} catch (Exception e) {
			throw new IOException("Kurento connect failed: " + wsUrl, e);
		}
	}

	/**
	 * Re-establish control after a BLADE failover, reclaiming objects created under the old session.
	 * NOTE: the kurento-jsonrpc reconnection handshake (presenting the prior sessionId so KMS does
	 * not GC the pipeline) needs confirming against the kurento-jsonrpc source. We persist sessionId
	 * now so it slots in without changing callers.
	 */
	public static KurentoClient reattach(String wsUrl, String priorSessionId) throws IOException {
		KurentoClient client = connect(wsUrl);
		client.sessionId = priorSessionId; // echoed on subsequent invoke/release to address prior objects
		return client;
	}

	public String url() {
		return uri.toString();
	}

	public String sessionId() {
		return sessionId;
	}

	public void close() {
		keepalive.shutdownNow();
		try {
			ws.close();
		} catch (IOException ignore) {
			// best effort
		}
	}

	// ---- core JSON-RPC -----------------------------------------------------------------------

	private JsonNode rpc(String method, ObjectNode params) throws IOException {
		long id = nextId.getAndIncrement();
		ObjectNode req = M.createObjectNode();
		req.put("jsonrpc", "2.0");
		req.put("id", id);
		req.put("method", method);
		if (sessionId != null) {
			params.put("sessionId", sessionId);
		}
		req.set("params", params);

		CompletableFuture<JsonNode> fut = new CompletableFuture<>();
		pending.put(id, fut);
		sendText(req.toString());
		try {
			JsonNode result = fut.get(10, TimeUnit.SECONDS); // brief block; OCCAS servlet thread
			JsonNode sid = result.get("sessionId");
			if (sid != null) {
				sessionId = sid.asText();
			}
			return result;
		} catch (Exception e) {
			pending.remove(id);
			throw new IOException("Kurento RPC '" + method + "' failed", e);
		}
	}

	/** create a media object; returns its server-side object id. */
	String create(String type, ObjectNode constructorParams) throws IOException {
		ObjectNode params = M.createObjectNode();
		params.put("type", type);
		params.set("constructorParams", constructorParams != null ? constructorParams : M.createObjectNode());
		params.set("properties", M.createObjectNode());
		return rpc("create", params).get("value").asText();
	}

	/** invoke an operation on an object; returns result.value (may be null). */
	JsonNode invoke(String objectId, String operation, ObjectNode operationParams) throws IOException {
		ObjectNode params = M.createObjectNode();
		params.put("object", objectId);
		params.put("operation", operation);
		params.set("operationParams", operationParams != null ? operationParams : M.createObjectNode());
		return rpc("invoke", params).get("value");
	}

	void release(String objectId) throws IOException {
		ObjectNode params = M.createObjectNode();
		params.put("object", objectId);
		rpc("release", params);
	}

	/** subscribe to an event type on an object; routes onEvent notifications to listener. */
	String subscribe(String objectId, String eventType, Consumer<JsonNode> listener) throws IOException {
		ObjectNode params = M.createObjectNode();
		params.put("type", eventType);
		params.put("object", objectId);
		String subId = rpc("subscribe", params).get("value").asText();
		eventListeners.put(objectId + "|" + eventType, listener);
		return subId;
	}

	private void pingQuietly() {
		try {
			rpc("ping", M.createObjectNode());
		} catch (IOException ignore) {
			// keepalive failure surfaces on the next real RPC
		}
	}

	private void sendText(String json) throws IOException {
		synchronized (ws) {
			ws.getBasicRemote().sendText(json); // serialize concurrent sends
		}
	}

	// ---- inbound dispatch (called by the holder endpoint) ------------------------------------

	void onText(String json) {
		try {
			JsonNode msg = M.readTree(json);
			JsonNode idNode = msg.get("id");
			if (idNode != null && (msg.has("result") || msg.has("error"))) {
				CompletableFuture<JsonNode> fut = pending.remove(idNode.asLong());
				if (fut == null) {
					return;
				}
				if (msg.has("error")) {
					fut.completeExceptionally(new IOException("KMS error: " + msg.get("error")));
				} else {
					fut.complete(msg.get("result"));
				}
			} else if ("onEvent".equals(text(msg, "method"))) {
				JsonNode value = msg.path("params").path("value");
				String source = value.path("object").asText(value.path("data").path("source").asText());
				String type = value.path("type").asText();
				Consumer<JsonNode> l = eventListeners.get(source + "|" + type);
				if (l != null) {
					l.accept(value);
				}
			}
		} catch (Exception ignore) {
			// malformed frame: drop
		}
	}

	private static String text(JsonNode n, String f) {
		JsonNode v = n.get(f);
		return v == null ? null : v.asText();
	}

	/** JSR-356 annotated endpoint; forwards frames to the owning client. */
	@ClientEndpoint
	public static class Holder {
		private volatile KurentoClient client;

		void bind(KurentoClient c) {
			this.client = c;
		}

		@OnMessage
		public void onMessage(String json) {
			if (client != null) {
				client.onText(json);
			}
		}

		@OnClose
		public void onClose(Session s, CloseReason r) {
			// failover/reconnect is handled by the caller via reattach()
		}
	}

	// ---- typed facades -----------------------------------------------------------------------

	public Pipeline createPipeline() throws IOException {
		return new Pipeline(this, create("MediaPipeline", null));
	}

	/** Address an existing pipeline by id after failover (no new server object created). */
	public Pipeline pipeline(String pipelineId) {
		return new Pipeline(this, pipelineId);
	}

	/** A Kurento MediaPipeline. Holds only the string id — safe to reconstruct after failover. */
	public static class Pipeline {
		private final KurentoClient k;
		private final String id;

		Pipeline(KurentoClient k, String id) {
			this.k = k;
			this.id = id;
		}

		public String id() {
			return id;
		}

		public RtpEndpoint createRtpEndpoint() throws IOException {
			ObjectNode cp = M.createObjectNode();
			cp.put("mediaPipeline", id);
			// Codec forcing (pin PCMU/PCMA for telco) happens at SDP/KMS-config level, NOT a
			// constructor param — Kurento negotiates from its configured codec set.
			return new RtpEndpoint(k, k.create("RtpEndpoint", cp));
		}

		/**
		 * Create the Gumball audio-bridge element and wire it bidirectionally to the RTP leg, so
		 * caller audio reaches the int8 inference (ASR->LLM->TTS) and synthesized speech returns.
		 * "GumballAgentBridge" is the type name of our custom server-side Kurento/GStreamer module
		 * (net-new C++/GStreamer appsink&lt;-&gt;socket) — PROVISIONAL until that module is registered.
		 */
		public AgentSession connectAgent(RtpEndpoint rtp, ObjectNode agentConfig) throws IOException {
			String bridgeId = k.create("GumballAgentBridge", parentPipeline(agentConfig));
			ObjectNode toBridge = M.createObjectNode();
			toBridge.put("sink", bridgeId);
			k.invoke(rtp.id(), "connect", toBridge); // caller -> inference
			ObjectNode toRtp = M.createObjectNode();
			toRtp.put("sink", rtp.id());
			k.invoke(bridgeId, "connect", toRtp); // inference (TTS) -> caller
			return new AgentSession(k, bridgeId);
		}

		private ObjectNode parentPipeline(ObjectNode extra) {
			ObjectNode cp = extra != null ? extra.deepCopy() : M.createObjectNode();
			cp.put("mediaPipeline", id);
			return cp;
		}

		/** Releasing the pipeline frees its child elements. */
		public void release() throws IOException {
			k.release(id);
		}
	}

	/** A plain (non-WebRTC) RtpEndpoint — no ICE/DTLS; this is the SIP-media leg. */
	public static class RtpEndpoint {
		private final KurentoClient k;
		private final String id;

		RtpEndpoint(KurentoClient k, String id) {
			this.k = k;
			this.id = id;
		}

		public String id() {
			return id;
		}

		/** Feed the caller's SDP offer; returns the SDP answer KMS generated. */
		public String processOffer(String callerOffer) throws IOException {
			ObjectNode p = M.createObjectNode();
			p.put("offer", callerOffer);
			return k.invoke(id, "processOffer", p).asText();
		}

		public void connect(String sinkObjectId) throws IOException {
			ObjectNode p = M.createObjectNode();
			p.put("sink", sinkObjectId);
			k.invoke(id, "connect", p);
		}
	}

	/** Handle to the running AI agent bridge; surfaces the conversation outcome. */
	public static class AgentSession {
		private final KurentoClient k;
		private final String bridgeId;

		AgentSession(KurentoClient k, String bridgeId) {
			this.k = k;
			this.bridgeId = bridgeId;
		}

		public String id() {
			return bridgeId;
		}

		/** Subscribe to the bridge's outcome event (custom module emits "AgentOutcome"). */
		public void onOutcome(Consumer<JsonNode> listener) throws IOException {
			k.subscribe(bridgeId, "AgentOutcome", listener);
		}
	}
}
