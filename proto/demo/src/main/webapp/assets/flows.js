/* Guided demo flows - DATA, not code. Each flow is an ordered list of steps
   over a service's REST operations. The generic player (guided.js) renders and
   runs ANY flow here, so a new guided demo = add an entry, not write a page.
   (Loaded as a script so it works straight off the filesystem, where fetch()
   of a local .json is blocked.) Pure ASCII on purpose. */
window.FLOWS = window.FLOWS || {};

window.FLOWS['3pcc'] = {
  id: '3pcc',
  title: 'Click-to-Call (3PCC)',
  blurb: 'A REST request conjures a live call: ring two phones, then bridge them. Each step is one atomic 3PCC command - the microservice on display.',
  service: 'tpcc',
  explorerApp: 'tpcc',
  basePath: '/tpcc/resources/api/v1',
  canvas: 'twoLegBridge',
  inputs: [
    { id: 'from', label: 'From - agent', placeholder: '+1 913 555 0142' },
    { id: 'to', label: 'To - customer', placeholder: '+1 415 555 0199' }
  ],
  steps: [
    {
      op: 'createSession', title: 'Start a session',
      narration: 'Create the 3PCC session that will own both legs. It comes back with a sessionId (and, now, a Vorpal tracking id).',
      method: 'POST', path: '/session', body: {},
      capture: { sessionId: 'sessionId' }, canvas: {}
    },
    {
      op: 'createDialog', title: 'Call phone A (agent)',
      narration: 'An OFFERLESS INVITE rings the agent; media is parked (RFC 3725) until we bridge. Returns the dialogId for this leg.',
      method: 'POST', path: '/dialog/${sessionId}', body: { remoteParty: '${from}' },
      capture: { dialogA: 'dialogId' }, canvas: { a: 'connected' }
    },
    {
      op: 'createDialog', title: 'Call phone B (customer)',
      narration: 'Same for the customer leg - a second parked call.',
      method: 'POST', path: '/dialog/${sessionId}', body: { remoteParty: '${to}' },
      capture: { dialogB: 'dialogId' }, canvas: { b: 'connected' }
    },
    {
      op: 'connectDialogs', title: 'Bridge A & B',
      narration: 'Re-INVITE Alice, carry her SDP offer to Bob, ACK both. The parked media activates and the two parties are talking.',
      method: 'PUT', path: '/dialog/${sessionId}/${dialogA}/connect/${dialogB}',
      canvas: { bridge: 'on' }
    },
    {
      op: 'deleteDialog', title: 'Hang up', optional: true,
      narration: 'BYE each leg to tear the whole call down.',
      method: 'DELETE', path: '/dialog/${sessionId}/${dialogA}',
      canvas: { a: 'ended', b: 'ended', bridge: 'off' }
    }
  ]
};
