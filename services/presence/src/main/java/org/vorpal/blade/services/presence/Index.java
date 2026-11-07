package org.vorpal.blade.services.presence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.vorpal.blade.framework.v3.Callflow;

/// A skill's index keys, each split into [#SHARDS] buckets.
///
/// The container stores an index key as one set of every session on it, and rewrites the whole set
/// whenever any one of those sessions adds, removes or renews the key (renewing happens on every
/// [javax.servlet.sip.SipApplicationSession#setExpires]). One key per skill therefore costs each
/// save time in proportion to the skill's size, and saves to one key wait on each other: on the
/// test rig, a PUBLISH took 3 ms for an agent joining an empty skill and 800 ms for one joining
/// 1,500 agents. Split by session, a save rewrites a set 1/[#SHARDS] the size; a reader asks for
/// the buckets it needs. The OCCAS release notes list the cost as a known issue (bugs 20318523 /
/// 20550145) and advise this split.
final class Index {

	/// Buckets per key.
	static final int SHARDS = 32;

	private Index() {
	}

	/// The key `prefix` + `skill` in the bucket that `member` (an agent's address, a call's session
	/// id) always falls into.
	static String key(String prefix, String skill, String member) {
		return prefix + skill + "/" + Math.floorMod(member.hashCode(), SHARDS);
	}

	/// Every session on the key, all buckets.
	static List<String> all(String prefix, String skill) {
		List<String> ids = new ArrayList<>();
		for (int shard = 0; shard < SHARDS; shard++) {
			ids.addAll(bucket(prefix, skill, shard));
		}
		return ids;
	}

	/// Whether any session is on the key, reading buckets only until one is found.
	static boolean any(String prefix, String skill) {
		for (int shard = 0; shard < SHARDS; shard++) {
			if (!bucket(prefix, skill, shard).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/// The buckets in random order, read one at a time as the caller asks.
	static List<Integer> shuffled() {
		List<Integer> order = new ArrayList<>(SHARDS);
		for (int shard = 0; shard < SHARDS; shard++) {
			order.add(shard);
		}
		Collections.shuffle(order);
		return order;
	}

	static Set<String> bucket(String prefix, String skill, int shard) {
		return Callflow.getSipUtil().getSipApplicationSessionIds(prefix + skill + "/" + shard);
	}
}
