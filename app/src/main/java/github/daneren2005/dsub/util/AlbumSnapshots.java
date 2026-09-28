package github.daneren2005.dsub.util;

import android.content.Context;

import java.io.File;
import java.util.HashMap;
import java.util.Objects;

import github.daneren2005.dsub.domain.AlbumDetails;
import github.daneren2005.dsub.domain.AlbumSnapshot;
import github.daneren2005.dsub.domain.MusicDirectory.Entry;

/**
 * Keeps what online album screens learned, against the folder the album downloads into.
 *
 * The folder is the key because it is the one thing both sides share: offline an album's
 * id is its folder's path, and online the first track says where it would be saved. Only
 * albums with that folder on disk are kept, since nothing else can be opened offline, and
 * that bounds the file by what is downloaded rather than by everything ever browsed.
 *
 * Unkeyed by server on purpose: offline there is no active server to key by, and the
 * music folder it describes is shared between them.
 */
public final class AlbumSnapshots {
	/** Spared by Clear Cache: it describes the permanent downloads that clearing leaves behind. */
	public static final String CACHE_NAME = "albumSnapshots.ser";

	private static HashMap<String, AlbumSnapshot> snapshots;

	private AlbumSnapshots() {
	}

	/** A copy of what is kept for the folder, or null where nothing is. */
	public static synchronized AlbumSnapshot get(Context context, File folder) {
		String key = keyFor(context, folder);
		AlbumSnapshot kept = (key == null) ? null : load(context).get(key);
		return (kept == null) ? null : new AlbumSnapshot(kept);
	}

	/** Records the album's year and release details. Null details leave any kept ones alone. */
	public static synchronized void rememberDetails(Context context, File folder, Integer year, AlbumDetails details) {
		String key = keyFor(context, folder);
		if(key == null) {
			return;
		}

		AlbumSnapshot snapshot = load(context).get(key);
		boolean detailsChanged = details != null && (snapshot == null || !sameDetails(snapshot.getDetails(), details));
		if(snapshot != null && Objects.equals(snapshot.getYear(), year) && !detailsChanged) {
			return;
		}

		snapshot = createIfMissing(key, snapshot);
		snapshot.setYear(year);
		if(details != null) {
			snapshot.setDetails(details);
		}
		save(context);
	}

	/**
	 * Records which server album the folder holds. Details, where given, are kept alongside -
	 * they are the same answer the album screen gets, just asked for in a batch.
	 */
	public static synchronized void rememberAlbum(Context context, File folder, int serverKey, Entry album, AlbumDetails details) {
		String key = keyFor(context, folder);
		if(key == null || album == null) {
			return;
		}

		AlbumSnapshot snapshot = load(context).get(key);
		boolean albumChanged = snapshot == null || snapshot.getAlbumServerKey() != serverKey
				|| !sameAlbum(snapshot.getAlbum(), album);
		boolean detailsChanged = details != null && (snapshot == null || !sameDetails(snapshot.getDetails(), details));
		if(!albumChanged && !detailsChanged) {
			return;
		}

		snapshot = createIfMissing(key, snapshot);
		snapshot.setAlbum(album, serverKey);
		if(details != null) {
			snapshot.setDetails(details);
		}
		save(context);
	}

	/** Records when the album was last played, in epoch seconds; null for never. */
	public static synchronized void rememberLastPlayed(Context context, File folder, Long lastPlayed) {
		String key = keyFor(context, folder);
		if(key == null) {
			return;
		}

		AlbumSnapshot snapshot = load(context).get(key);
		Long normalised = (lastPlayed != null && lastPlayed > 0) ? lastPlayed : null;
		if(snapshot != null && snapshot.isLastPlayedKnown() && Objects.equals(snapshot.getLastPlayed(), normalised)) {
			return;
		}

		snapshot = createIfMissing(key, snapshot);
		snapshot.setLastPlayed(normalised);
		save(context);
	}

	/**
	 * Everything there is to say about a downloaded album offline, or null if the folder is
	 * not there. What was kept wins; the first track's tags fill in what never was.
	 */
	public static AlbumSnapshot forOfflineAlbum(Context context, File folder) {
		if(folder == null || !folder.isDirectory()) {
			return null;
		}

		AlbumSnapshot result = new AlbumSnapshot(get(context, folder));
		if(result.getYear() == null || result.getDetails() == null) {
			File track = firstTrack(folder);
			if(track != null) {
				AlbumTags tags = AlbumTags.read(track);
				if(result.getYear() == null) {
					result.setYear(tags.getYear());
				}
				if(result.getDetails() == null) {
					result.setDetails(tags.getDetails());
				}
			}
		}
		return result;
	}

	/**
	 * Moves what is kept for a folder to where the folder went. Offline this is everything
	 * the app knows about an album beyond its files - its year, its release details, when it
	 * was last played - and it is keyed by where those files are, so a move it is not told
	 * about loses all of it.
	 */
	public static synchronized void moveFolder(Context context, File from, File to) {
		String oldKey = relativeKey(context, from);
		String newKey = relativeKey(context, to);
		if(oldKey == null || newKey == null || oldKey.equals(newKey)) {
			return;
		}

		AlbumSnapshot snapshot = load(context).remove(oldKey);
		if(snapshot == null) {
			return;
		}

		// Several folders can move into one, and what is already there was learned about the
		// album that now holds all of them; the first to arrive answers for the rest.
		if(!snapshots.containsKey(newKey)) {
			snapshots.put(newKey, snapshot);
		}
		save(context);
	}

	private static AlbumSnapshot createIfMissing(String key, AlbumSnapshot snapshot) {
		if(snapshot == null) {
			snapshot = new AlbumSnapshot();
			snapshots.put(key, snapshot);
		}
		return snapshot;
	}

	private static boolean sameDetails(AlbumDetails kept, AlbumDetails details) {
		return kept != null && kept.getTrackCount() == details.getTrackCount()
				&& Objects.equals(kept.getOriginalReleaseDate(), details.getOriginalReleaseDate())
				&& Objects.equals(kept.getRecordLabel(), details.getRecordLabel());
	}

	private static boolean sameAlbum(Entry kept, Entry album) {
		return kept != null && Objects.equals(kept.getId(), album.getId())
				&& Objects.equals(kept.getTitle(), album.getTitle())
				&& Objects.equals(kept.getArtist(), album.getArtist())
				&& Objects.equals(kept.getCoverArt(), album.getCoverArt());
	}

	private static File firstTrack(File folder) {
		for(File file: FileUtil.listMediaFiles(folder)) {
			if(file.isFile() && FileUtil.isMusicFile(file)) {
				return file;
			}
		}
		return null;
	}

	/** The folder relative to the music directory, so moving the cache does not orphan it. */
	private static String keyFor(Context context, File folder) {
		return (folder == null || !folder.isDirectory()) ? null : relativeKey(context, folder);
	}

	/** The same key by path alone, for a folder that is on its way somewhere else. */
	private static String relativeKey(Context context, File folder) {
		if(folder == null) {
			return null;
		}

		String path = folder.getAbsolutePath();
		String root = FileUtil.getMusicDirectory(context).getAbsolutePath() + "/";
		return path.startsWith(root) ? path.substring(root.length()) : path;
	}

	@SuppressWarnings("unchecked")
	private static HashMap<String, AlbumSnapshot> load(Context context) {
		if(snapshots == null) {
			snapshots = FileUtil.deserialize(context, CACHE_NAME, HashMap.class);
			if(snapshots == null) {
				snapshots = new HashMap<String, AlbumSnapshot>();
			}
		}
		return snapshots;
	}

	private static void save(Context context) {
		FileUtil.serialize(context, snapshots, CACHE_NAME);
	}
}
