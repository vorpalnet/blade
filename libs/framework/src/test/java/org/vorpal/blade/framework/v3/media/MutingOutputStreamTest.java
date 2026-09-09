package org.vorpal.blade.framework.v3.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// A fragmented MP4 built by hand, the shape the media tier writes, muted and
/// read back. The frames are opaque bytes: the muter never looks inside one,
/// only at where it sits in time.
class MutingOutputStreamTest {

	private static final long TIMESCALE = 48_000;
	private static final long FRAME = 1024; // samples per AAC frame, 21.33 ms

	// --- a tiny fMP4 writer ------------------------------------------------------

	private static byte[] box(String type, byte[]... payloads) {
		int len = 8;
		for (byte[] p : payloads) {
			len += p.length;
		}
		ByteBuffer b = ByteBuffer.allocate(len);
		b.putInt(len).put(type.getBytes(StandardCharsets.ISO_8859_1));
		for (byte[] p : payloads) {
			b.put(p);
		}
		return b.array();
	}

	private static byte[] full(String type, int version, int flags, byte[] payload) {
		ByteBuffer b = ByteBuffer.allocate(4 + payload.length);
		b.putInt((version << 24) | flags).put(payload);
		return box(type, b.array());
	}

	private static byte[] moov(int channels) {
		ByteBuffer mdhd = ByteBuffer.allocate(20);
		mdhd.putInt(0).putInt(0).putInt((int) TIMESCALE).putInt(0).putShort((short) 0).putShort((short) 0);
		// mp4a sample entry: 6 reserved, data-reference index, 8 reserved,
		// channels, sample size, 4 reserved, sample rate (16.16)
		ByteBuffer mp4a = ByteBuffer.allocate(28);
		mp4a.put(new byte[6]).putShort((short) 1).put(new byte[8]).putShort((short) channels).putShort((short) 16)
				.putInt(0).putInt((int) TIMESCALE << 16);
		byte[] stsd = full("stsd", 0, 0, concat(ByteBuffer.allocate(4).putInt(1).array(), box("mp4a", mp4a.array())));
		byte[] stbl = box("stbl", stsd);
		byte[] minf = box("minf", stbl);
		byte[] mdia = box("mdia", full("mdhd", 0, 0, mdhd.array()), minf);
		byte[] trak = box("trak", mdia);
		ByteBuffer trex = ByteBuffer.allocate(20);
		trex.putInt(1).putInt(1).putInt(0).putInt(0).putInt(0);
		byte[] mvex = box("mvex", full("trex", 0, 0, trex.array()));
		return box("moov", trak, mvex);
	}

	/// One fragment: frames of the given sizes, each lasting one AAC frame,
	/// starting at `decodeTime` samples. The frame bytes are the frame's index
	/// repeated, so a replaced frame is unmistakable.
	private static byte[][] fragment(long decodeTime, int... sizes) {
		ByteBuffer tfdt = ByteBuffer.allocate(8).putLong(decodeTime);
		ByteBuffer trun = ByteBuffer.allocate(8 + sizes.length * 8);
		trun.putInt(sizes.length);
		trun.putInt(0); // data offset, patched below
		ByteArrayOutputStream mdat = new ByteArrayOutputStream();
		for (int i = 0; i < sizes.length; i++) {
			trun.putInt((int) FRAME).putInt(sizes[i]);
			byte[] frame = new byte[sizes[i]];
			Arrays.fill(frame, (byte) (i + 1));
			mdat.write(frame, 0, frame.length);
		}
		byte[] traf = box("traf", full("tfhd", 0, 0, ByteBuffer.allocate(4).putInt(1).array()),
				full("tfdt", 1, 0, tfdt.array()), full("trun", 0, 0x301, trun.array()));
		byte[] moof = box("moof", full("mfhd", 0, 0, ByteBuffer.allocate(4).putInt(1).array()), traf);
		return new byte[][] { moof, box("mdat", mdat.toByteArray()) };
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) {
			out.write(p, 0, p.length);
		}
		return out.toByteArray();
	}

	private static byte[] recording(int channels, byte[][]... fragments) {
		List<byte[]> parts = new ArrayList<>();
		parts.add(box("ftyp", "iso5".getBytes(StandardCharsets.ISO_8859_1), new byte[4]));
		parts.add(moov(channels));
		for (byte[][] f : fragments) {
			parts.add(f[0]);
			parts.add(f[1]);
		}
		return concat(parts.toArray(new byte[0][]));
	}

	// --- reading the result -------------------------------------------------------

	/// The frames of every fragment in the stream, as byte arrays.
	private static List<byte[]> frames(byte[] file) {
		List<byte[]> out = new ArrayList<>();
		ByteBuffer b = ByteBuffer.wrap(file);
		List<int[]> sizes = new ArrayList<>();
		while (b.remaining() >= 8) {
			int size = b.getInt();
			String type = new String(file, b.position(), 4, StandardCharsets.ISO_8859_1);
			int start = b.position() - 4;
			if (type.equals("moof")) {
				sizes = trunSizes(file, start, size);
			} else if (type.equals("mdat")) {
				int at = start + 8;
				for (int[] s : sizes) {
					out.add(Arrays.copyOfRange(file, at, at + s[0]));
					at += s[0];
				}
				assertEquals(start + size, at, "mdat length matches the sum of its frames");
			}
			b.position(start + size);
		}
		return out;
	}

	private static List<int[]> trunSizes(byte[] file, int moofStart, int moofSize) {
		// Walk moof > traf > trun by scanning box headers.
		int at = moofStart + 8;
		int end = moofStart + moofSize;
		while (at < end) {
			int size = ByteBuffer.wrap(file, at, 4).getInt();
			String type = new String(file, at + 4, 4, StandardCharsets.ISO_8859_1);
			if (type.equals("traf")) {
				int t = at + 8;
				while (t < at + size) {
					int s2 = ByteBuffer.wrap(file, t, 4).getInt();
					String t2 = new String(file, t + 4, 4, StandardCharsets.ISO_8859_1);
					if (t2.equals("trun")) {
						int count = ByteBuffer.wrap(file, t + 12, 4).getInt();
						List<int[]> sizes = new ArrayList<>();
						int p = t + 20; // version/flags, count, data offset
						for (int i = 0; i < count; i++) {
							sizes.add(new int[] { ByteBuffer.wrap(file, p + 4, 4).getInt() });
							p += 8;
						}
						return sizes;
					}
					t += s2;
				}
			}
			at += size;
		}
		throw new AssertionError("no trun");
	}

	private static byte[] mute(byte[] file, List<long[]> spans, long offset, int chunk) throws IOException {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		try (MutingOutputStream m = new MutingOutputStream(sink, spans, offset)) {
			for (int at = 0; at < file.length; at += chunk) {
				m.write(file, at, Math.min(chunk, file.length - at));
			}
		}
		return sink.toByteArray();
	}

	// --- the tests ---------------------------------------------------------------

	@Test
	@DisplayName("frames inside a span become silent frames; the rest are untouched, in every chunking")
	void mutesTheFramesInsideASpan() throws IOException {
		// Fragment 1 at t=0: 4 frames (0-85 ms). Fragment 2 at 4096 samples
		// (85.3 ms): 4 frames (85-171 ms).
		byte[] file = recording(2, fragment(0, 300, 301, 302, 303), fragment(4 * FRAME, 310, 311, 312, 313));
		List<long[]> spans = Collections.singletonList(new long[] { 60, 100 }); // frames 3,4 (43-85) and 5 (85-106)
		for (int chunk : new int[] { 1, 7, 64, 100_000 }) {
			byte[] muted = mute(file, spans, 0, chunk);
			List<byte[]> frames = frames(muted);
			assertEquals(8, frames.size());
			assertEquals(300, frames.get(0).length, "before the span: untouched");
			assertEquals(301, frames.get(1).length);
			assertArrayEquals(MutingOutputStream.SILENT_STEREO, frames.get(2), "43-64 ms overlaps the span");
			assertArrayEquals(MutingOutputStream.SILENT_STEREO, frames.get(3));
			assertArrayEquals(MutingOutputStream.SILENT_STEREO, frames.get(4), "85-106 ms overlaps the span");
			assertEquals(311, frames.get(5).length, "after the span: untouched");
			assertEquals((byte) 2, frames.get(5)[0], "and it is the original frame");
			assertEquals(313, frames.get(7).length);
		}
	}

	@Test
	@DisplayName("the track offset moves the recording on the conversation clock")
	void honoursTheTrackOffset() throws IOException {
		byte[] file = recording(1, fragment(0, 100, 100, 100, 100));
		// The recording started 10 s into the conversation; the span at 10.0-10.03 s
		// is the first frame or two.
		byte[] muted = mute(file, Collections.singletonList(new long[] { 10_000, 10_030 }), 10_000, 4096);
		List<byte[]> frames = frames(muted);
		assertArrayEquals(MutingOutputStream.SILENT_MONO, frames.get(0));
		assertArrayEquals(MutingOutputStream.SILENT_MONO, frames.get(1), "21-43 ms overlaps 0-30 ms");
		assertEquals(100, frames.get(2).length);
		assertEquals(100, frames.get(3).length);
		assertTrue(muted.length < file.length);
	}

	@Test
	@DisplayName("nothing in a span leaves the stream byte-for-byte as it was")
	void passesAnUnmutedRecordingThrough() throws IOException {
		byte[] file = recording(2, fragment(0, 300, 301), fragment(2 * FRAME, 302, 303));
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		MutingOutputStream m = new MutingOutputStream(sink, Collections.singletonList(new long[] { 5_000, 6_000 }), 0);
		m.write(file);
		m.close();
		assertArrayEquals(file, sink.toByteArray());
		assertFalse(m.framesMuted());
	}

	@Test
	@DisplayName("a recording it cannot place in time is refused, not passed through")
	void refusesWhatItCannotMute() {
		byte[] noMoov = concat(box("ftyp", new byte[8]), fragment(0, 100)[0], fragment(0, 100)[1]);
		assertThrows(IOException.class, () -> mute(noMoov, Collections.singletonList(new long[] { 0, 10 }), 0, 4096));
	}

	@Test
	@DisplayName("spans come from the utterances' timed redactions, padded")
	void spansFromUtterances() {
		Utterance u = new Utterance("caller", 1000, 4000, "x");
		Utterance.Redaction timed = new Utterance.Redaction("card", 0, 4);
		timed.setStartMillis(1500L);
		timed.setEndMillis(2500L);
		Utterance.Redaction untimed = new Utterance.Redaction("ssn", 5, 9);
		u.setRedactions(Arrays.asList(timed, untimed));
		List<long[]> spans = MutingOutputStream.spansOf(Collections.singletonList(u), 150);
		assertEquals(1, spans.size(), "an untimed span cannot be muted and is not pretended to be");
		assertEquals(1350L, spans.get(0)[0]);
		assertEquals(2650L, spans.get(0)[1]);
	}
}
