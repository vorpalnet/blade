/// Chat rooms over SIP MESSAGE (RFC 3428).
///
/// - [MessagingServlet] keys every MESSAGE by its room and hands it to [PostMessage].
/// - [PostMessage] numbers a member's post, answers it, stores it and sends it on.
/// - [Delivery] sends one message to one member, the room's envelope in `X-Chat-*` headers.
/// - [Membership] applies who joined and left, from the event bus, and sends a newcomer the history.
/// - [Room] is the membership and numbering, plain Java; [Rooms] is where a room lives and how its
///   messages are kept.
package org.vorpal.blade.services.messaging.v3;
