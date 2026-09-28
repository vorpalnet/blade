/// Storage for SIP MESSAGE conversations: the contract between `proto/messaging`, which assigns
/// every message its place in a room, and whatever archive a deployment installs.
///
/// A room's messages are stored the way a live transcript is, one immutable object per message
/// named by its sequence, so an archive that refuses to rewrite an object still takes a
/// conversation that grows. [MessageArchive] is found through `ServiceLoader`; with none installed
/// the messaging service relays and stores nothing.
package org.vorpal.blade.framework.v3.messaging;
