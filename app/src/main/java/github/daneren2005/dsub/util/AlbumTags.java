package github.daneren2005.dsub.util;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import github.daneren2005.dsub.domain.AlbumDetails;
import github.daneren2005.dsub.util.tags.Bastp;

/**
 * The album facts a downloaded file carries in its own tags.
 *
 * Only a stand-in, for albums whose header was never seen online: tags and server can
 * disagree, and what the server said is what the screen showed. Several names are tried
 * for each fact because each container spells them its own way - Vorbis comments say DATE
 * and ORGANIZATION, ID3 comes through Bastp as YEAR, and MP4 atoms as a lower-case year.
 */
public class AlbumTags {
	private static final String[] YEAR_KEYS = { "DATE", "YEAR", "year" };
	private static final String[] ORIGINAL_DATE_KEYS = { "ORIGINALDATE", "ORIGINALYEAR" };
	private static final String[] LABEL_KEYS = { "ORGANIZATION", "LABEL", "PUBLISHER" };
	private static final String[] DISC_KEYS = { "DISCNUMBER", "discnumber" };
	private static final String[] DISC_TOTAL_KEYS = { "DISCTOTAL", "TOTALDISCS" };
	private static final String[] TRACK_KEYS = { "TRACKNUMBER", "tracknumber" };
	private static final String[] TRACK_TOTAL_KEYS = { "TRACKTOTAL", "TOTALTRACKS" };
	private static final String[] ALBUM_ARTIST_KEYS = { "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST" };
	private static final String[] ALBUM_KEYS = { "ALBUM" };

	private final Integer year;
	private final AlbumDetails details;

	private AlbumTags(Integer year, AlbumDetails details) {
		this.year = year;
		this.details = details;
	}

	public static AlbumTags read(File file) {
		return fromTags(new Bastp().getTags(file.getPath()));
	}

	static AlbumTags fromTags(Map<?, ?> tags) {
		Integer year = null;
		String yearText = first(tags, YEAR_KEYS);
		if(leadingYear(yearText) != null) {
			year = Integer.valueOf(leadingYear(yearText));
		}

		AlbumDetails details = new AlbumDetails();
		String originalDate = first(tags, ORIGINAL_DATE_KEYS);
		if(leadingYear(originalDate) != null) {
			details.setOriginalReleaseDate(originalDate);
		}
		details.setRecordLabel(first(tags, LABEL_KEYS));

		boolean saysSomething = details.getOriginalReleaseDate() != null || details.getRecordLabel() != null;
		return new AlbumTags(year, saysSomething ? details : null);
	}

	/**
	 * The album artist and album name the file is tagged with, or null where it lacks either.
	 * Which album a file belongs to is a question of its own where the folder it is in cannot
	 * answer it - a song downloaded under its own performer rather than the album's artist.
	 */
	public static Pair<String, String> albumIdentityOf(File file) {
		Map<?, ?> tags = new Bastp().getTags(file.getPath());
		String albumArtist = first(tags, ALBUM_ARTIST_KEYS);
		String album = first(tags, ALBUM_KEYS);
		if(albumArtist == null || album == null) {
			return null;
		}
		return new Pair<String, String>(albumArtist, album);
	}

	/** How many tracks the album has by these files' own tags, or null where they do not say. */
	public static Integer expectedTrackCountOf(List<File> files) {
		Bastp bastp = new Bastp();
		List<Map<?, ?>> tags = new ArrayList<Map<?, ?>>();
		for(File file: files) {
			tags.add(bastp.getTags(file.getPath()));
		}
		return expectedTrackCount(tags);
	}

	/**
	 * Track totals are per disc, so every disc has to be accounted for. A disc none of whose
	 * tracks are here has no file to say how long it is, and then nothing can be said - which
	 * is the right answer for an album that is plainly not all here.
	 */
	static Integer expectedTrackCount(List<? extends Map<?, ?>> tagsPerFile) {
		int discs = 1;
		Map<Integer, Integer> tracksPerDisc = new HashMap<Integer, Integer>();
		for(Map<?, ?> tags: tagsPerFile) {
			String discText = first(tags, DISC_KEYS);
			Integer disc = leadingNumber(discText);
			if(disc == null || disc < 1) {
				disc = 1;
			}

			Integer discTotal = leadingNumber(first(tags, DISC_TOTAL_KEYS));
			if(discTotal == null) {
				discTotal = afterSlash(discText);
			}
			Integer trackTotal = leadingNumber(first(tags, TRACK_TOTAL_KEYS));
			if(trackTotal == null) {
				trackTotal = afterSlash(first(tags, TRACK_KEYS));
			}

			if(trackTotal != null && trackTotal > 0) {
				Integer known = tracksPerDisc.get(disc);
				tracksPerDisc.put(disc, (known == null) ? trackTotal : Math.max(known, trackTotal));
			}
			discs = Math.max(discs, disc);
			if(discTotal != null) {
				discs = Math.max(discs, discTotal);
			}
		}

		int total = 0;
		for(int disc = 1; disc <= discs; disc++) {
			Integer tracks = tracksPerDisc.get(disc);
			if(tracks == null) {
				return null;
			}
			total += tracks;
		}
		return total;
	}

	/** The year the file was tagged with, or null. */
	public Integer getYear() {
		return year;
	}

	/** The original release date and label, or null where the file has neither. */
	public AlbumDetails getDetails() {
		return details;
	}

	private static String first(Map<?, ?> tags, String[] keys) {
		if(tags == null) {
			return null;
		}

		for(String key: keys) {
			Object value = tags.get(key);
			if(value instanceof List && !((List<?>) value).isEmpty()) {
				value = ((List<?>) value).get(0);
			}
			if(value instanceof String && !((String) value).trim().isEmpty()) {
				return ((String) value).trim();
			}
		}
		return null;
	}

	/** The four digits a date starts with, or null for anything that does not start with a year. */
	private static String leadingYear(String date) {
		if(date == null || date.length() < 4) {
			return null;
		}

		String year = date.substring(0, 4);
		for(int i = 0; i < year.length(); i++) {
			if(!Character.isDigit(year.charAt(i))) {
				return null;
			}
		}
		return "0000".equals(year) ? null : year;
	}

	/** The number a tag starts with: "6" and "6/13" are both 6. */
	private static Integer leadingNumber(String text) {
		if(text == null) {
			return null;
		}

		int end = 0;
		while(end < text.length() && Character.isDigit(text.charAt(end))) {
			end++;
		}
		try {
			return (end == 0) ? null : Integer.valueOf(text.substring(0, end));
		} catch(NumberFormatException e) {
			return null;
		}
	}

	/** The total in a "6/13" style tag, or null where there is no slash. */
	private static Integer afterSlash(String text) {
		if(text == null || text.indexOf('/') == -1) {
			return null;
		}
		return leadingNumber(text.substring(text.indexOf('/') + 1).trim());
	}
}
