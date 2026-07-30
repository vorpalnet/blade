/*
 * BLADE WebRTC client.
 *
 * One class, no dependencies, no polyfills. Modern browser APIs only: unified-plan
 * RTCPeerConnection, srcObject, async/await. The prior art in this space shipped a client that
 * feature-detected webkitGetUserMedia and used URL.createObjectURL(stream) — both removed from
 * browsers years ago — which is how a working product became a non-working one without anybody
 * touching it. Nothing here has a compatibility shim, on purpose: if it breaks, it breaks loudly.
 *
 * Wire format is CloudEvents 1.0 over a WebSocket negotiated as "blade.webrtc.v1".
 */

const SUBPROTOCOL = 'blade.webrtc.v1';

const EVENT = {
  // browser -> gateway
  SESSION_CONNECT: 'session.connect',
  CALL_OFFER: 'call.offer',
  CALL_ANSWER: 'call.answer',
  CALL_HANGUP: 'call.hangup',
  CALL_DTMF: 'call.dtmf',
  CALL_RECORD: 'call.record',
  // gateway -> browser
  SESSION_READY: 'session.ready',
  CALL_INCOMING: 'call.incoming',
  CALL_PROGRESS: 'call.progress',
  CALL_ESTABLISHED: 'call.established',
  CALL_ENDED: 'call.ended',
  CALL_UPDATE: 'call.update',
  ERROR: 'error',
  // both
  ICE_CANDIDATE: 'ice.candidate',
};

/**
 * A phone. Construct it, connect(), then call() or wait for onIncoming.
 *
 * Events you can assign: onRegistered, onIncoming, onProgress, onEstablished, onEnded, onError,
 * onRemoteStream, onStateChange.
 */
export class BladePhone {
  /**
   * @param {object} options
   * @param {string} options.url        wss:// URL of the gateway's /signal endpoint
   * @param {string} options.aor        the address to claim, e.g. "alice@example.com"
   * @param {RTCIceServer[]} [options.iceServers] STUN/TURN for the browser's own gathering
   */
  constructor({ url, aor, iceServers = [] }) {
    this.url = url;
    this.aor = aor;
    this.iceServers = iceServers;
    this.ws = null;
    this.pc = null;
    this.callId = null;
    this.localStream = null;
    this.state = 'idle';
    /** Candidates that arrived before the peer connection had a remote description. */
    this.pendingCandidates = [];
    /**
     * Whether to trickle our own candidates rather than holding the SDP until gathering ends.
     *
     * Set per call by the gateway. A relayed browser-to-browser call trickles both ways — both ends
     * are browsers and there is no server in the middle to wait on. An anchored call does not: the
     * media server does not advertise inbound trickle, so we send one complete offer or answer.
     */
    this.trickle = false;

    this.onRegistered = () => {};
    this.onIncoming = () => {};
    this.onProgress = () => {};
    this.onEstablished = () => {};
    this.onEnded = () => {};
    this.onError = () => {};
    this.onRemoteStream = () => {};
    this.onStateChange = () => {};
    this.onRenegotiated = () => {};
  }

  // ---- session ----------------------------------------------------------------------------

  /** Open the socket and claim the address. Resolves once the gateway says session.ready. */
  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url, SUBPROTOCOL);

      this.ws.onopen = () => {
        this.#send(EVENT.SESSION_CONNECT, null, { aor: this.aor });
      };

      this.ws.onmessage = (frame) => {
        let event;
        try {
          event = JSON.parse(frame.data);
        } catch {
          return;
        }
        if (event.type === EVENT.SESSION_READY) {
          if (event.data && typeof event.data.trickle === 'boolean') this.trickle = event.data.trickle;
          this.#setState('registered');
          this.onRegistered(event.data);
          resolve();
          return;
        }
        this.#handle(event).catch((e) => this.onError(String(e)));
      };

      this.ws.onerror = () => reject(new Error('could not reach the gateway'));
      this.ws.onclose = () => {
        this.#teardown();
        this.callId = null;
        this.#setState('idle');
      };
    });
  }

  /** Close the socket, dropping any call in progress. */
  disconnect() {
    this.hangup();
    if (this.ws) this.ws.close();
  }

  // ---- placing and answering --------------------------------------------------------------

  /**
   * Dial a target: a phone number, a user@host, or a full SIP URI. The gateway supplies the
   * domain for a bare number.
   */
  async call(target) {
    if (this.state !== 'registered') throw new Error('not registered');
    this.#setState('calling');

    await this.#newPeerConnection();
    const offer = await this.pc.createOffer({ offerToReceiveAudio: true });
    await this.pc.setLocalDescription(offer);

    // Unless the gateway said it can take trickled candidates, send one complete offer. Holding it
    // until gathering finishes is what the spec calls half-trickle, and it removes an entire class
    // of ordering bug from this client. The gateway still trickles ITS candidates to us, which is
    // where the latency actually mattered.
    if (!this.trickle) await this.#iceGatheringComplete();

    this.#send(EVENT.CALL_OFFER, null, {
      target,
      sdp: this.pc.localDescription.sdp,
    });
  }

  /** Accept the call currently ringing. */
  async answer() {
    if (this.state !== 'ringing') throw new Error('nothing is ringing');
    const answer = await this.pc.createAnswer();
    await this.pc.setLocalDescription(answer);
    if (!this.trickle) await this.#iceGatheringComplete();

    this.#send(EVENT.CALL_ANSWER, this.callId, { sdp: this.pc.localDescription.sdp });
    this.#setState('answering');
  }

  /** Hang up, or decline what is ringing. Safe to call when there is no call. */
  hangup() {
    if (this.callId) {
      this.#send(EVENT.CALL_HANGUP, this.callId, {});
    }
    this.#teardown();
    this.callId = null;
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.#setState('registered');
  }

  /** Send a DTMF digit on an established call. */
  dtmf(digit) {
    if (!this.callId) return;
    this.#send(EVENT.CALL_DTMF, this.callId, { digit: String(digit) });
  }

  /**
   * Start or stop recording this call.
   *
   * On a relayed (peer-to-peer) call, turning this on makes the gateway pull a media server in,
   * which arrives as a `call.update` and re-keys the connection. The media between two browsers is
   * encrypted end to end, so there is no way to record it without doing that.
   */
  record(on = true) {
    if (!this.callId) return;
    this.#send(EVENT.CALL_RECORD, this.callId, { on });
  }

  /** Mute or unmute the microphone locally. */
  setMuted(muted) {
    if (!this.localStream) return;
    for (const track of this.localStream.getAudioTracks()) {
      track.enabled = !muted;
    }
  }

  // ---- inbound events ---------------------------------------------------------------------

  async #handle(event) {
    switch (event.type) {
      case EVENT.CALL_INCOMING: {
        this.callId = event.subject;
        // Per call, not per session: a relayed call trickles, an anchored one does not.
        if (typeof event.data.trickle === 'boolean') this.trickle = event.data.trickle;
        await this.#newPeerConnection();
        await this.pc.setRemoteDescription({ type: 'offer', sdp: event.data.sdp });
        await this.#drainCandidates();
        this.#setState('ringing');
        this.onIncoming({ callId: this.callId, from: event.data.from });
        break;
      }

      case EVENT.CALL_PROGRESS: {
        this.callId = event.subject;
        if (event.data && typeof event.data.trickle === 'boolean') this.trickle = event.data.trickle;
        // The gateway answers our offer during alerting rather than at pickup, so the browser has
        // a media path in time to hear ringback and any early media from the carrier.
        if (event.data && event.data.sdp && this.pc && !this.pc.currentRemoteDescription) {
          await this.pc.setRemoteDescription({ type: 'answer', sdp: event.data.sdp });
          await this.#drainCandidates();
        }
        this.#setState(event.data?.status === 'ringing' ? 'ringing-remote' : 'progress');
        this.onProgress(event.data || {});
        break;
      }

      case EVENT.CALL_UPDATE: {
        // A fresh offer for a call already in progress — this is how the gateway drops a media
        // server into a peer-to-peer call so it can be recorded. It is a full re-key: new ICE
        // credentials and a new DTLS handshake, so expect a short gap in the audio.
        this.callId = event.subject;
        // Escalation puts a media server in the path, and it does not take trickled candidates —
        // so from here the complete SDP carries them.
        this.trickle = false;
        await this.pc.setRemoteDescription({ type: 'offer', sdp: event.data.sdp });
        await this.#drainCandidates();
        const updated = await this.pc.createAnswer();
        await this.pc.setLocalDescription(updated);
        if (!this.trickle) await this.#iceGatheringComplete();
        this.#send(EVENT.CALL_ANSWER, this.callId, { sdp: this.pc.localDescription.sdp });
        this.onRenegotiated({ callId: this.callId });
        break;
      }

      case EVENT.CALL_ESTABLISHED:
        this.callId = event.subject;
        // In a relayed call the far browser's answer arrives here, since there was no media server
        // to answer us earlier.
        if (event.data && event.data.sdp && this.pc && !this.pc.currentRemoteDescription) {
          await this.pc.setRemoteDescription({ type: 'answer', sdp: event.data.sdp });
          await this.#drainCandidates();
        }
        this.#setState('established');
        this.onEstablished({ callId: this.callId });
        break;

      case EVENT.CALL_ENDED:
        this.#teardown();
        this.callId = null;
        this.#setState('registered');
        this.onEnded(event.data || {});
        break;

      case EVENT.ICE_CANDIDATE:
        await this.#addCandidate(event.data);
        break;

      case EVENT.ERROR:
        this.onError(event.data?.reason || 'gateway error');
        break;

      default:
        break;
    }
  }

  async #addCandidate(data) {
    if (!data || !data.candidate) return;
    const candidate = {
      candidate: data.candidate,
      sdpMid: data.sdpMid,
      sdpMLineIndex: data.sdpMLineIndex,
    };
    // Candidates can arrive before the description they belong to; queue rather than drop.
    if (!this.pc || !this.pc.remoteDescription) {
      this.pendingCandidates.push(candidate);
      return;
    }
    try {
      await this.pc.addIceCandidate(candidate);
    } catch (e) {
      this.onError(`bad ICE candidate: ${e}`);
    }
  }

  async #drainCandidates() {
    const queued = this.pendingCandidates;
    this.pendingCandidates = [];
    for (const candidate of queued) {
      try {
        await this.pc.addIceCandidate(candidate);
      } catch {
        // A candidate for a media section the answer removed is not an error worth surfacing.
      }
    }
  }

  // ---- peer connection --------------------------------------------------------------------

  async #newPeerConnection() {
    this.#teardown();
    this.pc = new RTCPeerConnection({ iceServers: this.iceServers });

    this.pc.ontrack = (e) => this.onRemoteStream(e.streams[0]);
    this.pc.onicecandidate = (e) => {
      // Only meaningful while trickling; otherwise the candidates are already inside the SDP.
      if (!this.trickle || !e.candidate || !this.callId) return;
      this.#send(EVENT.ICE_CANDIDATE, this.callId, {
        candidate: e.candidate.candidate,
        sdpMid: e.candidate.sdpMid,
        sdpMLineIndex: e.candidate.sdpMLineIndex,
      });
    };
    this.pc.onconnectionstatechange = () => {
      if (this.pc && (this.pc.connectionState === 'failed')) {
        // Almost always a NAT-traversal problem rather than a signaling one.
        this.onError('media connection failed — check STUN/TURN reachability');
      }
    };

    this.localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    for (const track of this.localStream.getTracks()) {
      this.pc.addTrack(track, this.localStream);
    }
  }

  /** Resolve once ICE gathering finishes, so the SDP we send carries every candidate. */
  #iceGatheringComplete() {
    if (!this.pc || this.pc.iceGatheringState === 'complete') return Promise.resolve();
    return new Promise((resolve) => {
      const check = () => {
        if (this.pc.iceGatheringState === 'complete') {
          this.pc.removeEventListener('icegatheringstatechange', check);
          resolve();
        }
      };
      this.pc.addEventListener('icegatheringstatechange', check);
      // Do not let a single unreachable STUN server hold a call up indefinitely; whatever has
      // been gathered by now is better than nothing.
      setTimeout(() => {
        this.pc?.removeEventListener('icegatheringstatechange', check);
        resolve();
      }, 3000);
    });
  }

  #teardown() {
    if (this.pc) {
      this.pc.close();
      this.pc = null;
    }
    if (this.localStream) {
      for (const track of this.localStream.getTracks()) track.stop();
      this.localStream = null;
    }
    this.pendingCandidates = [];
    // Deliberately does NOT clear callId. Tearing down the media is not the same as ending the
    // call — #newPeerConnection() calls this to rebuild the peer connection while the call is very
    // much alive. Clearing it here wiped the id an inbound call had just been given, so the answer
    // went out with no CloudEvents subject and the gateway rejected it. The call-ending paths
    // (hangup, call.ended, socket close) clear it themselves.
  }

  // ---- plumbing ---------------------------------------------------------------------------

  #send(type, subject, data) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    this.ws.send(JSON.stringify({
      specversion: '1.0',
      type,
      source: '/blade/webrtc/client',
      id: crypto.randomUUID(),
      time: new Date().toISOString(),
      datacontenttype: 'application/json',
      ...(subject ? { subject } : {}),
      data,
    }));
  }

  #setState(state) {
    if (this.state === state) return;
    this.state = state;
    this.onStateChange(state);
  }
}

export { EVENT, SUBPROTOCOL };
