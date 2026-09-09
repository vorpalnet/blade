package org.vorpal.blade.framework.v3.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Silences protected spans of a recording as it streams, by replacing the
/// AAC frames inside each span with a silent frame.
///
/// The recordings the media tier stores are fragmented MP4: `ftyp`, `moov`,
/// then `moof`+`mdat` pairs, one AAC-LC frame per sample, each frame's size
/// and duration listed in the fragment's `trun`, and the fragment's start
/// time in its `tfdt` on the track's timescale. That is enough to know, for
/// every frame, the moment it plays, without decoding anything. A frame whose
/// moment falls in a span is replaced by the canonical silent AAC-LC frame
/// for the track's channel count, the `trun` entry is given the new size, and
/// the `mdat` header the new length. Nothing else in the file moves: the
/// fragment's data offset is relative to its own `moof`, whose size is
/// unchanged, so the rewrite is local to each fragment.
///
/// ## Why frames and not samples
///
/// Decoding, zeroing and re-encoding would need an AAC encoder, which the
/// application tier does not have and should not carry. Replacing whole frames
/// mutes to the frame boundary, 21 milliseconds at 48 kHz, which is finer than
/// any word. The spans themselves come from the recognizer's word timing
/// (`Utterance.Redaction`), padded by the caller to cover timing jitter.
///
/// ## What it refuses
///
/// A recording it cannot place in time, or whose fragments do not list a size
/// per sample, cannot be muted in place. It throws rather than pass the audio
/// through: a muted rendition that quietly turned out unmuted is the one
/// failure this class must never have. The stream the media tier writes has
/// both properties; a recording from elsewhere may not.
///
/// The silent frames are the well-known AAC-LC raw data blocks for one and two
/// channels, a block with no spectral data, valid at any sampling rate. A
/// decoder plays them as digital silence.
public final class MutingOutputStream extends OutputStream {

	/// The silent AAC-LC raw data block for a single channel element.
	static final byte[] SILENT_MONO = { 0x01, 0x18, 0x20, 0x07 };
	/// The silent AAC-LC raw data block for a channel pair.
	static final byte[] SILENT_STEREO = { 0x21, 0x10, 0x04, 0x60, (byte) 0x8c, 0x1c };

	private final OutputStream out;
	private final long[][] spansMillis;
	private final long trackOffsetMillis;

	private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
	private byte[] heldMoof;
	private long timescale;
	private int channels;
	private long defaultSampleDuration;
	private long defaultSampleSize;
	private boolean framesMuted;

	/// @param out               where the muted stream goes
	/// @param spansMillis       the spans to silence, each `{startMillis, endMillis}`
	///                          on the conversation clock, exclusive end
	/// @param trackOffsetMillis where the recording's own zero sits on the
	///                          conversation clock (`RecordingTrack.offsetMillis`)
	public MutingOutputStream(OutputStream out, List<long[]> spansMillis, long trackOffsetMillis) {
		this.out = out;
		this.spansMillis = spansMillis.toArray(new long[0][]);
		this.trackOffsetMillis = trackOffsetMillis;
	}

	/// Whether any frame has been silenced so far.
	public boolean framesMuted() {
		return framesMuted;
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] { (byte) b }, 0, 1);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		pending.write(b, off, len);
		drain();
	}

	@Override
	public void flush() throws IOException {
		drain();
		out.flush();
	}

	@Override
	public void close() throws IOException {
		drain();
		if (pending.size() > 0 || heldMoof != null) {
			throw new IOException("recording ended inside a box; " + pending.size() + " bytes unaccounted for");
		}
		out.close();
	}

	/// Hand every complete top-level box on to [#box]; keep the remainder.
	private void drain() throws IOException {
		byte[] buf = pending.toByteArray();
		int at = 0;
		while (buf.length - at >= 8) {
			long size = u32(buf, at);
			int header = 8;
			if (size == 1) {
				if (buf.length - at < 16) {
					break;
				}
				size = ByteBuffer.wrap(buf, at + 8, 8).getLong();
				header = 16;
			} else if (size == 0) {
				throw new IOException("a box extending to end of file cannot be muted as a stream");
			}
			if (size < header || size > Integer.MAX_VALUE) {
				throw new IOException("malformed box size " + size);
			}
			if (buf.length - at < size) {
				break;
			}
			byte[] box = new byte[(int) size];
			System.arraycopy(buf, at, box, 0, (int) size);
			at += (int) size;
			box(box, header);
		}
		pending.reset();
		if (at < buf.length) {
			pending.write(buf, at, buf.length - at);
		}
	}

	private void box(byte[] box, int header) throws IOException {
		String type = type(box, 4);
		switch (type) {
		case "moov":
			readMovie(box, header);
			out.write(box);
			return;
		case "moof":
			if (heldMoof != null) {
				throw new IOException("two moof boxes without an mdat between them");
			}
			heldMoof = box;
			return;
		case "mdat":
			if (heldMoof == null) {
				out.write(box);
				return;
			}
			byte[] moof = heldMoof;
			heldMoof = null;
			rewrite(moof, box, header);
			return;
		default:
			out.write(box);
		}
	}

	// --- the movie header: timescale and channels ------------------------------

	private void readMovie(byte[] moov, int header) throws IOException {
		int[] mdhd = find(moov, header, moov.length, "trak", "mdia", "mdhd");
		if (mdhd == null) {
			throw new IOException("recording has no media header; cannot place its frames in time");
		}
		int version = moov[mdhd[0] + 8] & 0xff;
		timescale = (version == 1) ? u32(moov, mdhd[0] + 28) : u32(moov, mdhd[0] + 20);
		int[] mp4a = find(moov, header, moov.length, "trak", "mdia", "minf", "stbl", "stsd", "mp4a");
		if (mp4a == null) {
			throw new IOException("recording is not AAC; only AAC-LC frames can be silenced in place");
		}
		channels = ((moov[mp4a[0] + 24] & 0xff) << 8) | (moov[mp4a[0] + 25] & 0xff);
		if (channels != 1 && channels != 2) {
			throw new IOException("no silent frame for " + channels + " channels");
		}
		int[] trex = find(moov, header, moov.length, "mvex", "trex");
		if (trex != null) {
			defaultSampleDuration = u32(moov, trex[0] + 20);
			defaultSampleSize = u32(moov, trex[0] + 24);
		}
		if (timescale <= 0) {
			throw new IOException("recording has no timescale");
		}
	}

	// --- one fragment ------------------------------------------------------------

	private void rewrite(byte[] moof, byte[] mdat, int mdatHeader) throws IOException {
		if (timescale <= 0) {
			throw new IOException("fragment before any movie header; cannot place its frames in time");
		}
		int[] traf = find(moof, 8, moof.length, "traf");
		if (traf == null) {
			throw new IOException("fragment has no track");
		}
		int[] tfhd = find(moof, traf[0] + 8, traf[0] + traf[1], "tfhd");
		int[] tfdt = find(moof, traf[0] + 8, traf[0] + traf[1], "tfdt");
		int[] trun = find(moof, traf[0] + 8, traf[0] + traf[1], "trun");
		if (tfdt == null || trun == null) {
			throw new IOException("fragment carries no decode time or no sample table; cannot be muted");
		}
		long sampleDuration = defaultSampleDuration;
		long sampleSize = defaultSampleSize;
		if (tfhd != null) {
			int flags = (int) (u32(moof, tfhd[0] + 8) & 0xffffff);
			int p = tfhd[0] + 16;
			if ((flags & 0x1) != 0) {
				p += 8;
			}
			if ((flags & 0x2) != 0) {
				p += 4;
			}
			if ((flags & 0x8) != 0) {
				sampleDuration = u32(moof, p);
				p += 4;
			}
			if ((flags & 0x10) != 0) {
				sampleSize = u32(moof, p);
			}
		}
		int tfdtVersion = moof[tfdt[0] + 8] & 0xff;
		long decodeTime = (tfdtVersion == 1) ? ByteBuffer.wrap(moof, tfdt[0] + 12, 8).getLong()
				: u32(moof, tfdt[0] + 12);

		int flags = (int) (u32(moof, trun[0] + 8) & 0xffffff);
		int count = (int) u32(moof, trun[0] + 12);
		int p = trun[0] + 16;
		if ((flags & 0x1) != 0) {
			p += 4;
		}
		if ((flags & 0x4) != 0) {
			p += 4;
		}
		boolean perSampleDuration = (flags & 0x100) != 0;
		boolean perSampleSize = (flags & 0x200) != 0;
		if (!perSampleSize) {
			throw new IOException("fragment lists no size per sample; cannot be muted in place");
		}
		if (!perSampleDuration && sampleDuration <= 0) {
			throw new IOException("fragment has no sample duration; cannot place its frames in time");
		}
		int entry = (perSampleDuration ? 4 : 0) + 4 + ((flags & 0x400) != 0 ? 4 : 0) + ((flags & 0x800) != 0 ? 4 : 0);

		byte[] silent = (channels == 2) ? SILENT_STEREO : SILENT_MONO;
		ByteArrayOutputStream data = new ByteArrayOutputStream(mdat.length);
		int src = mdatHeader;
		long time = decodeTime;
		for (int i = 0; i < count; i++) {
			int e = p + i * entry;
			long duration = perSampleDuration ? u32(moof, e) : sampleDuration;
			int sizeAt = e + (perSampleDuration ? 4 : 0);
			int size = (int) u32(moof, sizeAt);
			if (src + size > mdat.length) {
				throw new IOException("sample " + i + " runs past the end of its mdat");
			}
			long startMillis = trackOffsetMillis + time * 1000L / timescale;
			long endMillis = trackOffsetMillis + (time + duration) * 1000L / timescale;
			if (muted(startMillis, endMillis)) {
				data.write(silent, 0, silent.length);
				putU32(moof, sizeAt, silent.length);
				framesMuted = true;
			} else {
				data.write(mdat, src, size);
			}
			src += size;
			time += duration;
		}
		// Anything after the listed samples (there should be nothing) is kept.
		if (src < mdat.length) {
			data.write(mdat, src, mdat.length - src);
		}
		byte[] body = data.toByteArray();
		out.write(moof);
		byte[] head = new byte[mdatHeader];
		System.arraycopy(mdat, 0, head, 0, mdatHeader);
		if (mdatHeader == 16) {
			ByteBuffer.wrap(head, 8, 8).putLong(16L + body.length);
		} else {
			putU32(head, 0, 8L + body.length);
		}
		out.write(head);
		out.write(body);
	}

	private boolean muted(long startMillis, long endMillis) {
		for (long[] span : spansMillis) {
			if (startMillis < span[1] && endMillis > span[0]) {
				return true;
			}
		}
		return false;
	}

	// --- box helpers ---------------------------------------------------------------

	/// Find a nested box by path inside `[from, to)`; returns `{offset, size}`
	/// of the innermost, or null. A `stsd` is entered past its entry count.
	private static int[] find(byte[] b, int from, int to, String... path) {
		int at = from;
		while (to - at >= 8) {
			long size = u32(b, at);
			if (size == 0) {
				size = to - at;
			}
			if (size < 8 || at + size > to) {
				return null;
			}
			if (type(b, at + 4).equals(path[0])) {
				if (path.length == 1) {
					return new int[] { at, (int) size };
				}
				int inner = at + 8 + ("stsd".equals(path[0]) ? 8 : 0);
				String[] rest = new String[path.length - 1];
				System.arraycopy(path, 1, rest, 0, rest.length);
				int[] found = find(b, inner, at + (int) size, rest);
				if (found != null) {
					return found;
				}
			}
			at += (int) size;
		}
		return null;
	}

	private static String type(byte[] b, int at) {
		return new String(b, at, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	private static long u32(byte[] b, int at) {
		return ((long) (b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
	}

	private static void putU32(byte[] b, int at, long v) {
		b[at] = (byte) (v >>> 24);
		b[at + 1] = (byte) (v >>> 16);
		b[at + 2] = (byte) (v >>> 8);
		b[at + 3] = (byte) v;
	}

	/// The spans a list of utterances' redactions cover, padded, as
	/// `{start, end}` pairs. Spans without timing are skipped: nothing can be
	/// muted for them, and the caller decides whether that is acceptable.
	public static List<long[]> spansOf(List<? extends org.vorpal.blade.framework.v3.media.manifest.Utterance> utterances,
			long padMillis) {
		List<long[]> spans = new ArrayList<>();
		if (utterances == null) {
			return spans;
		}
		for (org.vorpal.blade.framework.v3.media.manifest.Utterance u : utterances) {
			if (u.getRedactions() == null) {
				continue;
			}
			for (org.vorpal.blade.framework.v3.media.manifest.Utterance.Redaction r : u.getRedactions()) {
				if (r.getStartMillis() != null && r.getEndMillis() != null) {
					spans.add(new long[] { Math.max(0, r.getStartMillis() - padMillis), r.getEndMillis() + padMillis });
				}
			}
		}
		return spans;
	}
}
