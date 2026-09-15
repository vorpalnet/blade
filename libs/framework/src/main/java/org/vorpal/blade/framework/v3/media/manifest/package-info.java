/// The stored form of a recording.
///
/// A call is not one recording, and a conversation is not one stream. Four
/// levels, each earning its place:
///
/// ```
/// call            the whole customer experience; survives a transfer
///  |- conversation the unit of access control and retention
///     |- track     one audio stream, with a role and a party
///        |- segment what the recorder actually wrote
/// ```
///
/// [org.vorpal.blade.framework.v3.media.manifest.ConversationManifest] is the
/// contract. Everything else here either describes part of it, places it on a
/// timeline, or moves it from the scratchpad to the archive.
///
/// ## The customer chooses tracks, not code paths
///
/// Mixed, per participant, both, or two-party stereo as a channel map: all four
/// are a track list in the same format. Which one a deployment produces is
/// configuration. Nothing downstream branches on it, and adding a shape later
/// does not change the schema.
///
/// ## Preserve what you measured
///
/// The rule the format is built on: store the representation others can be
/// derived from. A compressed timeline cannot be turned back into a faithful
/// one, and a `silent` flag cannot be turned back into a level. So the timeline
/// is preserved, the level is a number, and a gap carries its reason.
///
/// It has exactly one exception, and it is deliberate. Content that must not be
/// retained is destroyed: card entry audio is never stored, the interval and its
/// bounds are kept, and there is nothing behind that gap for `phi:unredact` to
/// reveal. Preserve structure and time; destroy prohibited content; record that
/// you destroyed it.
///
/// The other boundary is claimed precision.
/// [org.vorpal.blade.framework.v3.media.manifest.ConversationManifest#getSyncAccuracyMillis]
/// should say what is defensible rather than what is flattering. Manufactured
/// detail is not preserved detail.
///
/// ## Nothing here depends on a storage retention rule
///
/// A conversation becomes a record when its manifest is committed, and
/// [org.vorpal.blade.framework.v3.media.manifest.ManifestArchive] refuses a
/// second commit for the same conversation. That invariant is enforced by the
/// implementation, not by the bucket.
///
/// This matters in both directions. A production deployment can add an object
/// retention rule to harden the archive against a tenancy administrator, and it
/// changes nothing functionally. A test or demo environment must run without
/// one, because a retention rule makes every object undeletable for its whole
/// duration, and a seven year rule means seven years of failed experiments
/// nobody can clear. Provisioning for a test or demo environment should therefore
/// create no retention rule unless one is explicitly asked for.
///
/// ## Two decisions worth knowing about
///
/// **The scratchpad is a separate bucket, not a prefix.** A mutable manifest
/// cannot live inside a bucket carrying a retention rule, because the rule
/// forbids exactly the rewriting the scratchpad exists to allow. It also needs
/// its own lifecycle rule, which is bucket-scoped. Same tenancy, same region,
/// same access controls, same protected content: the only difference is
/// mutability. See
/// [org.vorpal.blade.framework.v3.media.manifest.ManifestStore].
///
/// **Legal hold lives outside the manifest.** A hold is a decision taken after a
/// conversation is closed, and it can be lifted. Recording it inside a committed
/// manifest would mean rewriting an immutable object, which is the problem the
/// commit was designed to avoid. It belongs to the storage layer as an object or
/// bucket hold, keyed by conversation, where it can change without the record of
/// the conversation changing.
package org.vorpal.blade.framework.v3.media.manifest;
