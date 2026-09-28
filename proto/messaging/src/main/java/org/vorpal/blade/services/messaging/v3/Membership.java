package org.vorpal.blade.services.messaging.v3;

import java.util.List;

import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.events.SubscriptionRegistrar;
import org.vorpal.blade.framework.v3.messaging.MessageArchive;
import org.vorpal.blade.framework.v3.messaging.StoredMessage;

import com.bea.wcp.sip.WlssAction;
import com.bea.wcp.sip.WlssSipApplicationSession;
import com.fasterxml.jackson.databind.JsonNode;

/// Who is in a room, from the application that owns the room: a meeting says whom it let in and
/// who left, as [BladeEventTypes#ROOM_MEMBER] facts on the event bus.
///
/// The room does not decide membership itself. A meeting already decides who is in it (its
/// callers list, its waiting room, a host's removal), and a second list here would be a second
/// answer to the same question. So the room takes the meeting's word, and the meeting holds no
/// chat code beyond saying it.
///
/// A fact may carry `accept`, the formats the member takes, which the room keeps for the address
/// ([Room#learn]). An application that knows its client says so; one that does not leaves it out,
/// and the room learns from the member's first `415` instead.
///
/// ## Every node hears it, one acts
///
/// The subscription is not durable and every engine receives each fact. Each applies it to the
/// room under the room's lock; joining again and leaving again change nothing, so only the first
/// to apply a join sends the newcomer the room's history.
///
/// ## A newcomer's history
///
/// The newest [MessagingSettings#getReplayLimit] stored messages they may see (everything to the
/// room, and private messages they sent or received), sent as ordinary MESSAGEs. The page drops a
/// sequence it already has, so a page that rejoins after a lost connection catches up with no
/// separate history request. The archive is read with no lock held; the sends take it again.
final class Membership implements EventSubscriber.Handler {

	/// This subscriber's name on the broker, and its metric key.
	static final String SUBSCRIPTION = "messaging-room-members";

	private SubscriptionRegistrar registrar;

	void start(javax.servlet.ServletContext context) {
		registrar = SubscriptionRegistrar.named(SUBSCRIPTION).types(BladeEventTypes.ROOM_MEMBER).live()
				.start(context, this);
	}

	void stop() {
		if (registrar != null) {
			registrar.stop();
		}
	}

	@Override
	public void handle(List<CloudEvent> batch) {
		for (CloudEvent event : batch) {
			if (BladeEventTypes.ROOM_MEMBER.equals(event.getType()) && event.getData() != null) {
				apply(event.getData());
			}
		}
	}

	private static void apply(JsonNode data) {
		String room = data.path("room").asText(null);
		String address = data.path("address").asText(null);
		String participant = data.path("participant").asText(null);
		String displayName = data.path("displayName").asText(null);
		boolean joined = data.path("joined").asBoolean(false);
		java.util.List<String> accept = new java.util.ArrayList<>();
		for (JsonNode type : data.path("accept")) {
			if (type.isTextual() && !type.asText().trim().isEmpty()) {
				accept.add(type.asText().trim());
			}
		}
		if (room == null || address == null || participant == null) {
			return;
		}
		String member = address.toLowerCase();
		SipApplicationSession app = Rooms.session(room);
		underLock(app, () -> {
			Room held = Rooms.of(app, room);
			if (joined) {
				if (held.join(participant, member, displayName, accept)) {
					Rooms.save(app, held);
					int upTo = held.last();
					int after = Room.replayAfter(upTo, Rooms.replayLimit());
					if (upTo > after) {
						Rooms.offLock(() -> replay(room, member, after, upTo));
					}
				} else if (!accept.isEmpty()) {
					Rooms.save(app, held); // a rejoin may change what the address takes
				}
			} else if (held.leave(participant)) {
				Rooms.save(app, held);
			}
		});
	}

	/// Send `member` the stored messages numbered after `after` up to `upTo`; anything newer reaches
	/// them live, since they are a member now.
	private static void replay(String room, String member, int after, int upTo) {
		MessageArchive archive = Rooms.archive();
		if (archive == null) {
			return;
		}
		List<StoredMessage> history;
		try {
			history = archive.read(room, after);
		} catch (Exception e) {
			Callflow.getSipLogger().warning("messaging: could not read " + room + "'s history for " + member + ": " + e);
			return;
		}
		SipApplicationSession app = Rooms.session(room);
		underLock(app, () -> {
			Room held = Rooms.of(app, room);
			Delivery delivery = new Delivery();
			for (StoredMessage m : history) {
				if (m.getSequence() <= upTo && Room.visibleTo(member, m.getFrom(), m.getTo())) {
					delivery.send(app, held, m, member);
				}
			}
		});
	}

	private interface Locked {
		void run() throws Exception;
	}

	/// Run `task` holding the room's lock, from a thread that holds none.
	private static void underLock(SipApplicationSession app, Locked task) {
		if (!(app instanceof WlssSipApplicationSession) || !app.isValid()) {
			return;
		}
		try {
			((WlssSipApplicationSession) app).doAction(new WlssAction() {
				@Override
				public Object run() throws Exception {
					task.run();
					return null;
				}
			});
		} catch (Exception e) {
			Callflow.getSipLogger().warning("messaging: room update failed: " + e);
		}
	}
}
