package github.daneren2005.dsub.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import github.daneren2005.dsub.domain.AlbumDetails;
import github.daneren2005.dsub.domain.AlbumSnapshot;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.MusicDirectory.Entry;
import github.daneren2005.dsub.service.MusicService;
import github.daneren2005.dsub.service.MusicServiceFactory;

/**
 * The albums that are on the phone in full, newest download first, for the Home row.
 *
 * Built from the music folder rather than from any listing: the folder is the truth about
 * what is downloaded, and the one thing there both online and offline. An album counts
 * once its folder holds as many finished tracks as the album has - the server's count
 * where it has been heard, the files' own track totals otherwise. A folder with nothing to
 * measure against is left out rather than guessed at.
 *
 * Online the row has to open the server's album, not the folder, so each folder is matched
 * to one: through the song database and a lookup of one of its tracks the first time, and
 * from what was kept every time after.
 */
public final class DownloadedAlbums {
	private static final String TAG = DownloadedAlbums.class.getSimpleName();
	private static final String PARTIAL_MARKER = ".partial.";

	/** One album folder and the finished tracks in it. */
	private static class Folder {
		final File dir;
		final File artistDir;
		final List<File> tracks;
		final long newest;
		private Integer countFromTags;
		private boolean tagsRead;

		Folder(File dir, File artistDir, List<File> tracks, long newest) {
			this.dir = dir;
			this.artistDir = artistDir;
			this.tracks = tracks;
			this.newest = newest;
		}

		/** Read once and only if asked: it opens every track in the folder. */
		Integer countFromTags() {
			if(!tagsRead) {
				countFromTags = AlbumTags.expectedTrackCountOf(tracks);
				tagsRead = true;
			}
			return countFromTags;
		}
	}

	private DownloadedAlbums() {
	}

	public static List<Entry> find(Context context) {
		List<Folder> folders = foldersOnDisk(context);
		Collections.sort(folders, new Comparator<Folder>() {
			@Override
			public int compare(Folder lhs, Folder rhs) {
				return Long.compare(rhs.newest, lhs.newest);
			}
		});

		return Util.isOffline(context) ? offlineAlbums(context, folders) : onlineAlbums(context, folders);
	}

	/** The album a track belongs to, as an entry the album screen can be opened with. */
	public static Entry albumEntryOf(Entry song) {
		String id = (song.getAlbumId() != null) ? song.getAlbumId() : song.getParent();
		if(id == null || id.isEmpty()) {
			return null;
		}

		Entry album = new Entry();
		album.setDirectory(true);
		album.setId(id);
		album.setTitle(song.getAlbum());
		album.setAlbum(song.getAlbum());
		album.setArtist((song.getAlbumArtist() != null) ? song.getAlbumArtist() : song.getArtist());
		album.setCoverArt(song.getCoverArt());
		album.setYear(song.getYear());
		return album;
	}

	private static List<Folder> foldersOnDisk(Context context) {
		List<Folder> folders = new ArrayList<Folder>();
		for(File artistDir: FileUtil.listFiles(FileUtil.getMusicDirectory(context))) {
			if(!artistDir.isDirectory()) {
				continue;
			}

			for(File albumDir: FileUtil.listFiles(artistDir)) {
				if(!albumDir.isDirectory()) {
					continue;
				}

				// By the name the song is saved under: a song pinned after it was cached can
				// briefly be there twice, and it is still one track.
				Map<String, File> tracks = new LinkedHashMap<String, File>();
				long newest = 0;
				for(File file: FileUtil.listFiles(albumDir)) {
					if(file.isFile() && !file.getName().contains(PARTIAL_MARKER) && FileUtil.isMusicFile(file)) {
						tracks.put(CacheLayoutMigration.saveNameOf(file.getName()), file);
						newest = Math.max(newest, file.lastModified());
					}
				}

				if(!tracks.isEmpty()) {
					folders.add(new Folder(albumDir, artistDir, new ArrayList<File>(tracks.values()), newest));
				}
			}
		}
		return folders;
	}

	private static List<Entry> offlineAlbums(Context context, List<Folder> folders) {
		MusicService service = MusicServiceFactory.getMusicService(context);
		Map<File, MusicDirectory> artists = new HashMap<File, MusicDirectory>();

		List<Entry> albums = new ArrayList<Entry>();
		for(Folder folder: folders) {
			Integer expected = serverTrackCount(AlbumSnapshots.get(context, folder.dir));
			if(expected == null) {
				expected = folder.countFromTags();
			}
			if(expected == null || folder.tracks.size() < expected) {
				continue;
			}

			// The same entries the offline Library shows, so the row opens and draws its art
			// exactly as browsing there would.
			MusicDirectory artist = artists.get(folder.artistDir);
			if(artist == null) {
				try {
					artist = service.getMusicDirectory(folder.artistDir.getPath(), folder.artistDir.getName(), false, context, null);
				} catch(Exception e) {
					Log.w(TAG, "Could not list " + folder.artistDir, e);
					continue;
				}
				artists.put(folder.artistDir, artist);
			}

			for(Entry child: artist.getChildren(true, false)) {
				if(folder.dir.getPath().equals(child.getId())) {
					albums.add(child);
					break;
				}
			}
		}
		return albums;
	}

	private static List<Entry> onlineAlbums(Context context, List<Folder> folders) {
		MusicService service = MusicServiceFactory.getMusicService(context);
		int serverKey = Util.getRestUrlHash(context);

		List<Folder> matchedFolders = new ArrayList<Folder>();
		List<Entry> matchedAlbums = new ArrayList<Entry>();
		boolean canLookUp = true;
		for(Folder folder: folders) {
			AlbumSnapshot kept = AlbumSnapshots.get(context, folder.dir);

			// Not worth a request to match an album that is already known to be short.
			Integer expected = serverTrackCount(kept);
			if(expected == null) {
				expected = folder.countFromTags();
			}
			if(expected != null && folder.tracks.size() < expected) {
				continue;
			}

			Entry album = (kept != null && kept.getAlbumServerKey() == serverKey) ? kept.getAlbum() : null;
			if(album == null && canLookUp) {
				try {
					album = lookUpAlbum(context, service, serverKey, folder);
				} catch(Exception e) {
					// One failure says the server is not answering; the rest would too.
					Log.w(TAG, "Could not match downloaded albums to the server", e);
					canLookUp = false;
				}
				AlbumSnapshots.rememberAlbum(context, folder.dir, serverKey, album, null);
			}

			if(album != null) {
				matchedFolders.add(folder);
				matchedAlbums.add(album);
			}
		}

		Map<String, AlbumDetails> details = Collections.emptyMap();
		if(!matchedAlbums.isEmpty()) {
			List<String> ids = new ArrayList<String>();
			for(Entry album: matchedAlbums) {
				ids.add(album.getId());
			}
			try {
				Map<String, AlbumDetails> fetched = service.getAlbumDetails(ids, context, null);
				if(fetched != null) {
					details = fetched;
				}
			} catch(Exception e) {
				Log.w(TAG, "Could not get track counts for downloaded albums", e);
			}
		}

		List<Entry> albums = new ArrayList<Entry>();
		for(int i = 0; i < matchedFolders.size(); i++) {
			Folder folder = matchedFolders.get(i);
			Entry album = matchedAlbums.get(i);

			Integer expected;
			AlbumDetails server = details.get(album.getId());
			if(server != null && server.getTrackCount() > 0) {
				AlbumSnapshots.rememberAlbum(context, folder.dir, serverKey, album, server);
				expected = server.getTrackCount();
			} else {
				expected = serverTrackCount(AlbumSnapshots.get(context, folder.dir));
				if(expected == null) {
					expected = folder.countFromTags();
				}
			}

			if(expected != null && folder.tracks.size() >= expected) {
				albums.add(album);
			}
		}
		return albums;
	}

	/**
	 * The server album a folder holds, found through the first of its tracks the song
	 * database can put an id to. One request, and only ever once per folder.
	 */
	private static Entry lookUpAlbum(Context context, MusicService service, int serverKey, Folder folder) throws Exception {
		SongDBHandler songs = SongDBHandler.getHandler(context);
		for(File track: folder.tracks) {
			Pair<Integer, String> id = songs.getIdFromPath(serverKey, CacheLayoutMigration.saveNameOf(track.getAbsolutePath()));
			if(id == null) {
				continue;
			}

			Entry song = new Entry();
			song.setId(id.getSecond());
			Entry details = service.getSongDetails(song, context);
			return (details == null) ? null : albumEntryOf(details);
		}
		return null;
	}

	private static Integer serverTrackCount(AlbumSnapshot snapshot) {
		if(snapshot == null || snapshot.getDetails() == null || snapshot.getDetails().getTrackCount() <= 0) {
			return null;
		}
		return snapshot.getDetails().getTrackCount();
	}
}
