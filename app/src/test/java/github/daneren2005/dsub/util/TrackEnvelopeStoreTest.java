package github.daneren2005.dsub.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shape of a track as a value: what it answers, and what it refuses to answer when
 * nothing was measured.
 */
public class TrackEnvelopeStoreTest {
	@Test
	public void saysNothingWhenNothingWasMeasured() {
		TrackEnvelope empty = new TrackEnvelope(200, new float[0], new float[0], -120f);

		assertFalse(empty.isUsable());
		assertFalse("an unusable shape must not read as a fade", empty.startsQuiet());
		assertFalse(empty.endsQuiet());
		assertFalse("nor as a dead stop, which would invite a crossfade", empty.startsCold());
		assertFalse(empty.endsCold());
		assertEquals(0, empty.arrivalMs());
		assertEquals(0, empty.departureMs());
	}

	@Test
	public void readsLevelAgainstTheTracksOwnMiddleRatherThanFullScale() {
		// Both stop dead at their own level; one is simply a quieter record than the other.
		TrackEnvelope loud = level(-9f, -9f);
		TrackEnvelope quiet = level(-30f, -30f);

		assertTrue(loud.endsCold());
		assertTrue("a quiet record is not a faded one", quiet.endsCold());
		assertFalse(quiet.endsQuiet());
	}

	@Test
	public void countsTheTimeATrackSpendsArrivingAndLeaving() {
		float[] head = new float[] { -60f, -40f, -20f, -12f, -12f, -12f };
		float[] tail = new float[] { -12f, -12f, -12f, -30f, -50f, -70f };
		TrackEnvelope envelope = new TrackEnvelope(200, head, tail, -12f);

		// Underway from the fourth window in, which is 600ms.
		assertEquals(600, envelope.arrivalMs());
		// Last fully underway three windows from the end, which is 600ms of leaving.
		assertEquals(600, envelope.departureMs());
	}

	private static TrackEnvelope level(float db, float reference) {
		float[] windows = new float[10];
		java.util.Arrays.fill(windows, db);
		return new TrackEnvelope(200, windows, windows, reference);
	}
}
