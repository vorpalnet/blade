package org.vorpal.blade.services.presence;

/*
 * 
 * PUBLISH
 *   account_id (From)
 *     EventMap<String, Event> -- String is the name of the event, Event contains
 *        Content, ContentType, From
 *        SubscriberList
 *        - Subscriber
 *          - From (becomes the To)
 *          - Contact
 *          - Expires
 * 
 *  For SUBSCRIBE
 *  sessionId = From + Event
 *  set the appSession expiration to 'Expires'.
 *  add subscriber
 *  when session expires
 * 
 * 
 * 
 */

public class Event {

}
