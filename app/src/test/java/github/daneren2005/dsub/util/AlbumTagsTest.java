package github.daneren2005.dsub.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The album facts read back out of a file's tags, in the shapes Bastp hands them over.
 *
 * The Vorbis case is copied from a real download, which is the one that matters: every
 * album on the phone is FLAC.
 */
public class AlbumTagsTest {
	@Test
	public void readsYearReleaseAndLabelFromVorbisComments() {
		HashMap<String, Object> tags = new HashMap<String, Object>();
		tags.put("DATE", list("1978"));
		tags.put("ORIGINALDATE", list("1978-01-12"));
		tags.put("ORIGINALYEAR", list("1978"));
		tags.put("ORGANIZATION", list("EMI"));

		AlbumTags album = AlbumTags.fromTags(tags);

		assertEquals(Integer.valueOf(1978), album.getYear());
		assertEquals("1978-01-12", album.getDetails().getOriginalReleaseDate());
		assertEquals("EMI", album.getDetails().getRecordLabel());
	}

	@Test
	public void takesTheYearOffAFullDate() {
		HashMap<String, Object> tags = new HashMap<String, Object>();
		tags.put("DATE", list("1985-09-16"));

		assertEquals(Integer.valueOf(1985), AlbumTags.fromTags(tags).getYear());
	}

	@Test
	public void readsMp4sLowerCaseYear() {
		HashMap<String, Object> tags = new HashMap<String, Object>();
		tags.put("year", list("2001"));

		assertEquals(Integer.valueOf(2001), AlbumTags.fromTags(tags).getYear());
	}

	@Test
	public void leavesOutWhatIsNotADate() {
		HashMap<String, Object> tags = new HashMap<String, Object>();
		tags.put("DATE", list("unknown"));
		tags.put("ORIGINALDATE", list("0000"));

		AlbumTags album = AlbumTags.fromTags(tags);

		assertNull(album.getYear());
		assertNull(album.getDetails());
	}

	@Test
	public void saysNothingForAnUntaggedFile() {
		AlbumTags album = AlbumTags.fromTags(new HashMap<String, Object>());

		assertNull(album.getYear());
		assertNull(album.getDetails());
	}

	@Test
	public void countsASingleDiscFromItsTrackTotal() {
		assertEquals(Integer.valueOf(13), AlbumTags.expectedTrackCount(Arrays.asList(
				track("6", "13", "1", "1"))));
	}

	@Test
	public void readsTheTotalOutOfATrackNumberWithASlash() {
		HashMap<String, Object> tags = new HashMap<String, Object>();
		tags.put("TRACKNUMBER", list("6/13"));

		assertEquals(Integer.valueOf(13), AlbumTags.expectedTrackCount(Arrays.asList(tags)));
	}

	@Test
	public void addsUpEveryDisc() {
		assertEquals(Integer.valueOf(18), AlbumTags.expectedTrackCount(Arrays.asList(
				track("1", "10", "1", "2"), track("1", "8", "2", "2"))));
	}

	@Test
	public void cannotCountADiscWithNothingOnThePhone() {
		assertNull(AlbumTags.expectedTrackCount(Arrays.asList(
				track("1", "10", "1", "2"), track("2", "10", "1", "2"))));
	}

	@Test
	public void cannotCountWithoutTotals() {
		HashMap<String, Object> tags = new HashMap<String, Object>();
		tags.put("TRACKNUMBER", list("6"));

		assertNull(AlbumTags.expectedTrackCount(Arrays.asList(tags)));
	}

	private static HashMap<String, Object> track(String number, String total, String disc, String discTotal) {
		HashMap<String, Object> tags = new HashMap<String, Object>();
		tags.put("TRACKNUMBER", list(number));
		tags.put("TRACKTOTAL", list(total));
		tags.put("DISCNUMBER", list(disc));
		tags.put("DISCTOTAL", list(discTotal));
		return tags;
	}

	private static List<String> list(String... values) {
		return new ArrayList<String>(Arrays.asList(values));
	}
}
