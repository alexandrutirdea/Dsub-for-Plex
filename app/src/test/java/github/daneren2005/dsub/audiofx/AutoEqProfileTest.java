package github.daneren2005.dsub.audiofx;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The curve read out of an AutoEq profile, checked against what the filters in it mean.
 *
 * A wrong response here would be inaudible as a bug and audible as a bad correction, which
 * is the worst way round, so the shapes are pinned down at the frequencies where they are
 * known by definition: a peaking filter is its own gain at its centre, and a shelf is its
 * gain far to one side and nothing far to the other.
 */
public class AutoEqProfileTest {
	private static final double TOLERANCE = 0.15;

	private static final String HD_800 =
			"Preamp: -6.1 dB\n" +
			"Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70\n" +
			"Filter 2: ON PK Fc 1928 Hz Gain 3.5 dB Q 1.28\n" +
			"Filter 3: ON PK Fc 176 Hz Gain -2.0 dB Q 0.58\n" +
			"Filter 4: ON PK Fc 5764 Hz Gain -7.8 dB Q 3.59\n" +
			"Filter 5: ON PK Fc 22 Hz Gain -0.4 dB Q 4.35\n" +
			"Filter 6: ON HSC Fc 10000 Hz Gain -4.2 dB Q 0.70\n" +
			"Filter 7: ON PK Fc 3534 Hz Gain 1.4 dB Q 5.71\n" +
			"Filter 8: ON PK Fc 3643 Hz Gain -0.3 dB Q 4.55\n" +
			"Filter 9: ON PK Fc 6708 Hz Gain 1.2 dB Q 6.00\n" +
			"Filter 10: ON PK Fc 1026 Hz Gain -0.2 dB Q 2.62\n";

	@Test
	public void readsPreampAndFilters() {
		AutoEqProfile profile = AutoEqProfile.parse(HD_800);

		assertEquals(-6.1f, profile.getPreampDb(), 0.001);
		assertEquals(10, profile.getFilterCount());
	}

	@Test
	public void peakingFilterIsItsGainAtItsCentre() {
		AutoEqProfile profile = AutoEqProfile.parse("Filter 1: ON PK Fc 1000 Hz Gain 6.0 dB Q 1.00\n");

		assertEquals(6.0, profile.gainAt(1000), TOLERANCE);
		// And has left the far ends of the band alone.
		assertEquals(0.0, profile.gainAt(30), TOLERANCE);
		assertEquals(0.0, profile.gainAt(16000), TOLERANCE);
	}

	@Test
	public void lowShelfLiftsBelowAndLetsGoAbove() {
		AutoEqProfile profile = AutoEqProfile.parse("Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70\n");

		assertEquals(6.4, profile.gainAt(20), TOLERANCE);
		assertEquals(3.2, profile.gainAt(105), TOLERANCE);
		assertEquals(0.0, profile.gainAt(4000), TOLERANCE);
	}

	@Test
	public void highShelfCutsAboveAndLetsGoBelow() {
		AutoEqProfile profile = AutoEqProfile.parse("Filter 1: ON HSC Fc 10000 Hz Gain -4.2 dB Q 0.70\n");

		assertEquals(-4.2, profile.gainAt(20000), 0.4);
		assertEquals(-2.1, profile.gainAt(10000), TOLERANCE);
		assertEquals(0.0, profile.gainAt(500), TOLERANCE);
	}

	@Test
	public void filtersAddUp() {
		AutoEqProfile profile = AutoEqProfile.parse(
				"Filter 1: ON PK Fc 1000 Hz Gain 3.0 dB Q 2.00\n" +
				"Filter 2: ON PK Fc 1000 Hz Gain 2.0 dB Q 2.00\n");

		assertEquals(5.0, profile.gainAt(1000), TOLERANCE);
	}

	@Test
	public void skipsFiltersThatAreSwitchedOff() {
		AutoEqProfile profile = AutoEqProfile.parse(
				"Filter 1: ON PK Fc 1000 Hz Gain 3.0 dB Q 2.00\n" +
				"Filter 2: OFF PK Fc 1000 Hz Gain 9.0 dB Q 2.00\n");

		assertEquals(1, profile.getFilterCount());
		assertEquals(3.0, profile.gainAt(1000), TOLERANCE);
	}

	@Test
	public void tellsTheUserWhenGivenTheGraphicEqFileInstead() {
		try {
			AutoEqProfile.parse("GraphicEQ: 21 -1.2; 23 -1.3; 25 -1.4");
			fail("A graphic EQ file is not a parametric one");
		} catch(IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("GraphicEQ"));
		}
	}

	@Test
	public void refusesTextWithNoFiltersInIt() {
		try {
			AutoEqProfile.parse("Preamp: -6.1 dB\n");
			fail("A preamp on its own is not a correction");
		} catch(IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("No filters"));
		}
	}

	/** The whole HD 800 correction, at the frequencies its own filters are pinned to. */
	@Test
	public void followsAWholeProfile() {
		AutoEqProfile profile = AutoEqProfile.parse(HD_800);

		// The bass shelf, less the 176 Hz dip which barely reaches down there.
		assertTrue(profile.gainAt(30) > 5.0);
		// The 5764 Hz notch is the deepest part of the curve.
		assertTrue(profile.gainAt(5764) < -6.0);
		// And the treble shelf pulls the top down.
		assertTrue(profile.gainAt(16000) < -2.0);
	}
}
