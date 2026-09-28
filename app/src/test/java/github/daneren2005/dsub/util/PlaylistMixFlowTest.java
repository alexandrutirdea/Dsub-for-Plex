package github.daneren2005.dsub.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import github.daneren2005.dsub.domain.MusicDirectory.Entry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What the Flow ordering does with the two things it cannot measure its way out of: a
 * track the server has not analysed, and a yes-or-no measurement sitting beside two
 * scales.
 *
 * Both used to come out as a block - every unanalysed track the same distance from every
 * other and so all adjacent, and the voice flag weighted heavily enough to sort the set
 * into sung and unsung before the levels got a say. These pin down that they do not.
 */
public class PlaylistMixFlowTest {
	private static final int GAP = 5;

	/**
	 * Flow draws on a shared Random that nothing here can seed, so every figure below is a
	 * mean over this many orderings rather than one reading. Enough runs that the means
	 * settle well clear of the thresholds, which are set against what the old behaviour
	 * measured rather than against what the new behaviour happens to score.
	 */
	private static final int RUNS = 60;

	@Test
	public void spreadsUnanalysedTracksThroughTheSetRatherThanClumpingThem() {
		List<Entry> tracks = playlist(120, 0.25f, 0.1f, 4);

		double clumped = 0;
		double meanPosition = 0;
		for(int run = 0; run < RUNS; run++) {
			List<Entry> ordered = PlaylistMix.flowOrder(tracks, GAP);
			assertEquals("every track is kept", tracks.size(), ordered.size());

			List<Integer> at = positionsOfUnanalysed(ordered);
			clumped += adjacentShare(at);
			meanPosition += meanOf(at) / ordered.size();
		}
		clumped /= RUNS;
		meanPosition /= RUNS;

		// Placed at the middle of the scale, as they were, these came out around 90%
		// adjacent. Spread through the walk they measure 0%.
		assertTrue("unanalysed tracks should not arrive in a block, was " + clumped,
				clumped < 0.25);
		assertTrue("unanalysed tracks should sit across the set, centred at " + meanPosition,
				meanPosition > 0.35 && meanPosition < 0.65);
	}

	@Test
	public void doesNotSortTheSetIntoSungAndUnsungBeforeTheLevelsGetASay() {
		List<Entry> tracks = playlist(120, 0f, 0.2f, 7);

		double clumped = 0;
		for(int run = 0; run < RUNS; run++) {
			clumped += adjacentShare(positionsOfInstrumental(PlaylistMix.flowOrder(tracks, GAP)));
		}
		clumped /= RUNS;

		// Weighted level with loudness, one flag decided the whole order and this measured
		// 0.875. Some grouping is wanted and expected - instrumentals really are alike in
		// that respect - so this guards against the flag dominating, not against it counting.
		assertTrue("instrumentals should not all run together, was " + clumped, clumped < 0.75);
	}

	@Test
	public void keepsEveryTrackWhenNothingHasBeenAnalysedAtAll() {
		List<Entry> tracks = playlist(40, 1f, 0f, 3);
		List<Entry> ordered = PlaylistMix.flowOrder(tracks, GAP);
		assertEquals(tracks.size(), ordered.size());
		assertTrue("nothing measured is a shuffle, and it should say so",
				!PlaylistMix.canOrderByFlow(tracks));
	}

	// ------------------------------------------------------------------ fixtures

	/** A set shaped like a rock playlist: a loud bulk, a mid group and a few quiet ones. */
	private static List<Entry> playlist(int size, float unanalysed, float instrumental, int artists) {
		Random random = new Random(11);
		List<Entry> tracks = new ArrayList<Entry>();
		for(int i = 0; i < size; i++) {
			Entry track = new Entry();
			track.setId("track" + i);
			track.setTitle("Track " + i);
			track.setAlbumArtist("artist" + random.nextInt(artists));

			if(random.nextFloat() >= unanalysed) {
				float family = random.nextFloat();
				if(family < 0.72f) {
					track.setLoudness(-8.5f + (float) random.nextGaussian() * 1.6f);
					track.setLoudnessRange(5.5f + (float) random.nextGaussian() * 1.5f);
				} else if(family < 0.92f) {
					track.setLoudness(-12f + (float) random.nextGaussian() * 1.5f);
					track.setLoudnessRange(8f + (float) random.nextGaussian() * 2f);
				} else {
					track.setLoudness(-16.5f + (float) random.nextGaussian() * 1.8f);
					track.setLoudnessRange(11f + (float) random.nextGaussian() * 2.5f);
				}
				track.setVoiceActivity(random.nextFloat() >= instrumental);
			}
			tracks.add(track);
		}
		return tracks;
	}

	private static List<Integer> positionsOfUnanalysed(List<Entry> ordered) {
		List<Integer> at = new ArrayList<Integer>();
		for(int i = 0; i < ordered.size(); i++) {
			if(ordered.get(i).getLoudness() == null) {
				at.add(i);
			}
		}
		return at;
	}

	private static List<Integer> positionsOfInstrumental(List<Entry> ordered) {
		List<Integer> at = new ArrayList<Integer>();
		for(int i = 0; i < ordered.size(); i++) {
			Boolean voice = ordered.get(i).getVoiceActivity();
			if(voice != null && !voice) {
				at.add(i);
			}
		}
		return at;
	}

	/** How much of a group plays back to back, 0 for never and 1 for one solid block. */
	private static double adjacentShare(List<Integer> at) {
		if(at.size() < 2) {
			return 0;
		}
		int adjacent = 0;
		for(int i = 0; i + 1 < at.size(); i++) {
			if(at.get(i + 1) - at.get(i) == 1) {
				adjacent++;
			}
		}
		return (double) adjacent / (at.size() - 1);
	}

	private static double meanOf(List<Integer> at) {
		if(at.isEmpty()) {
			return 0;
		}
		double total = 0;
		for(int value : at) {
			total += value;
		}
		return total / at.size();
	}
}
