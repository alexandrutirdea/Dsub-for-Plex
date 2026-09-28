package github.daneren2005.dsub.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Old-layout rows paired with the files that moved, where the name leaves no doubt.
 */
public class SongPathRepairTest {
	private static final String OLD = "/music/library/parts/";

	@Test
	public void pairsARowWithTheFileThatCarriesItsName() {
		Map<String, String> moves = SongPathRepair.match(
				Arrays.asList(OLD + "487065/1751490580/06-Wuthering Heights.flac"),
				Arrays.asList("/music/Kate Bush/The Kick Inside/06-Wuthering Heights.flac"));

		assertEquals("/music/Kate Bush/The Kick Inside/06-Wuthering Heights.flac",
				moves.get(OLD + "487065/1751490580/06-Wuthering Heights.flac"));
	}

	@Test
	public void leavesANameTwoRowsShare() {
		Map<String, String> moves = SongPathRepair.match(
				Arrays.asList(OLD + "1/1/01-Intro.flac", OLD + "2/2/01-Intro.flac"),
				Arrays.asList("/music/A/B/01-Intro.flac"));

		assertTrue(moves.isEmpty());
	}

	@Test
	public void leavesANameTwoFilesShare() {
		Map<String, String> moves = SongPathRepair.match(
				Arrays.asList(OLD + "1/1/01-Intro.flac"),
				Arrays.asList("/music/A/B/01-Intro.flac", "/music/C/D/01-Intro.flac"));

		assertTrue(moves.isEmpty());
	}

	@Test
	public void leavesARowWithNoFile() {
		Map<String, String> moves = SongPathRepair.match(
				Arrays.asList(OLD + "1/1/03-Gone.flac"), Collections.<String>emptyList());

		assertTrue(moves.isEmpty());
	}
}
