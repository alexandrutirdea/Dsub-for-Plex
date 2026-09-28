package github.daneren2005.dsub.domain;

import java.io.Serializable;

/**
 * What the album header last knew about one album, kept so it can say the same offline.
 *
 * Offline an album is a folder of files, with no id to ask about and nobody to ask, so the
 * year, the release line and when it was last played would all go blank. Each part is
 * filled in as an online screen learns it. Last played carries its own "known" flag
 * because a record never played is an answer worth repeating, where one never asked
 * about is not.
 *
 * It also remembers which server album the folder holds, so the Downloaded albums row can
 * open the album online without having to work that out again.
 */
public class AlbumSnapshot implements Serializable {
	private Integer year;
	private AlbumDetails details;
	private boolean lastPlayedKnown;
	private Long lastPlayed;
	private MusicDirectory.Entry album;
	private int albumServerKey;

	public AlbumSnapshot() {
	}

	public AlbumSnapshot(AlbumSnapshot other) {
		if(other != null) {
			year = other.year;
			details = other.details;
			lastPlayedKnown = other.lastPlayedKnown;
			lastPlayed = other.lastPlayed;
			album = other.album;
			albumServerKey = other.albumServerKey;
		}
	}

	/** The year every track agreed on, or null where they did not or nothing said. */
	public Integer getYear() {
		return year;
	}

	public void setYear(Integer year) {
		this.year = year;
	}

	/** The release date, label and track count, or null where the server was never asked. */
	public AlbumDetails getDetails() {
		return details;
	}

	public void setDetails(AlbumDetails details) {
		this.details = details;
	}

	public boolean isLastPlayedKnown() {
		return lastPlayedKnown;
	}

	/** Epoch seconds, or null for a record never played. Only meaningful once known. */
	public Long getLastPlayed() {
		return lastPlayed;
	}

	public void setLastPlayed(Long lastPlayed) {
		this.lastPlayed = (lastPlayed != null && lastPlayed > 0) ? lastPlayed : null;
		this.lastPlayedKnown = true;
	}

	/** The server's album this folder holds, or null where it has not been matched. */
	public MusicDirectory.Entry getAlbum() {
		return album;
	}

	/** Which server the album belongs to, as {@code Util.getRestUrlHash} gives it. */
	public int getAlbumServerKey() {
		return albumServerKey;
	}

	public void setAlbum(MusicDirectory.Entry album, int serverKey) {
		this.album = album;
		this.albumServerKey = serverKey;
	}
}
