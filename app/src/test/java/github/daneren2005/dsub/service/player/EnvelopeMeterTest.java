package github.daneren2005.dsub.service.player;

import org.junit.Test;

import github.daneren2005.dsub.util.TrackEnvelope;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The shapes a record actually comes in, fed through the meter as a player would feed it.
 *
 * Each track below is built as a level in decibels over time and handed over in buffers the
 * size a decoder hands out, so what is being tested is the same path the samples take.
 */
public class EnvelopeMeterTest {
	/** What a decoder hands the sink at a time, near enough. */
	private static final int BUFFER_MS = 20;

	@Test
	public void hearsATrackThatStartsAndStopsDead() {
		TrackEnvelope envelope = measure(flat(180000, -14f));

		assertTrue(envelope.startsCold());
		assertTrue(envelope.endsCold());
		assertFalse(envelope.startsQuiet());
		assertFalse(envelope.endsQuiet());
		assertEquals(0, envelope.arrivalMs());
		assertEquals(0, envelope.departureMs());
	}

	@Test
	public void hearsATrackThatFadesItselfOut() {
		// Steady, then eight seconds of fade to silence - the ending this exists to notice.
		float[] track = flat(180000, -14f);
		rampTo(track, 172000, 180000, -14f, -70f);

		TrackEnvelope envelope = measure(track);

		assertTrue("a faded ending should read as already gone", envelope.endsQuiet());
		assertFalse(envelope.endsCold());
		assertTrue("the leaving should measure seconds, was " + envelope.departureMs(),
				envelope.departureMs() > 4000);
	}

	@Test
	public void hearsATrackThatFadesItselfIn() {
		// The Queen Is Dead problem: the record arrives already playing, brought up by hand.
		float[] track = flat(180000, -12f);
		rampTo(track, 0, 6000, -70f, -12f);

		TrackEnvelope envelope = measure(track);

		assertTrue("a faded start should read as arriving quiet", envelope.startsQuiet());
		assertFalse(envelope.startsCold());
		assertTrue("the arrival should measure seconds, was " + envelope.arrivalMs(),
				envelope.arrivalMs() > 3000);
	}

	@Test
	public void doesNotMistakeAQuietRecordingForAFade() {
		// Twelve decibels below the loud one above, and still starting and stopping dead.
		TrackEnvelope envelope = measure(flat(180000, -26f));

		assertTrue("level is read against the track's own middle", envelope.startsCold());
		assertTrue(envelope.endsCold());
		assertFalse(envelope.startsQuiet());
		assertFalse(envelope.endsQuiet());
	}

	@Test
	public void hearsAShortDecayAsNeitherColdNorGone() {
		// A chord let ring for a second and a half: not a fade out, but not a dead stop.
		float[] track = flat(180000, -14f);
		rampTo(track, 178500, 180000, -14f, -34f);

		TrackEnvelope envelope = measure(track);

		assertFalse("a ring-out is not a dead stop", envelope.endsCold());
		assertFalse("nor has the track taken itself away", envelope.endsQuiet());
	}

	@Test
	public void saysNothingAboutATrackTooShortToHaveAMiddle() {
		assertNull(measure(flat(2000, -14f)));
	}

	@Test
	public void keepsTheLastTwentySecondsOfALongTrack() {
		float[] track = flat(600000, -14f);
		rampTo(track, 596000, 600000, -14f, -70f);

		TrackEnvelope envelope = measure(track);

		assertTrue("the end of a ten minute track is still the end", envelope.endsQuiet());
	}

	// ------------------------------------------------------------------ fixtures

	/** Runs a track's level through the meter in decoder sized buffers. */
	private static TrackEnvelope measure(float[] levelPerMs) {
		EnvelopeMeter meter = new EnvelopeMeter();
		int frameRate = 44100;
		for(int at = 0; at + BUFFER_MS <= levelPerMs.length; at += BUFFER_MS) {
			double total = 0;
			for(int i = at; i < at + BUFFER_MS; i++) {
				double amplitude = Math.pow(10, levelPerMs[i] / 20.0);
				total += amplitude * amplitude;
			}
			meter.add(total / BUFFER_MS, frameRate * BUFFER_MS / 1000, at);
		}
		return meter.finish();
	}

	private static float[] flat(int durationMs, float db) {
		float[] out = new float[durationMs];
		java.util.Arrays.fill(out, db);
		return out;
	}

	/** Bends the level linearly in decibels between two points in the track. */
	private static void rampTo(float[] track, int fromMs, int toMs, float fromDb, float toDb) {
		for(int i = Math.max(0, fromMs); i < Math.min(track.length, toMs); i++) {
			float through = (float) (i - fromMs) / (float) (toMs - fromMs);
			track[i] = fromDb + (toDb - fromDb) * through;
		}
	}
}
