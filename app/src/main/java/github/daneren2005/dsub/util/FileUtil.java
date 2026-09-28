/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package github.daneren2005.dsub.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Iterator;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import androidx.core.content.ContextCompat;
import android.util.Log;
import github.daneren2005.dsub.domain.AlbumDetails;
import github.daneren2005.dsub.domain.AlbumSnapshot;
import github.daneren2005.dsub.domain.Artist;
import github.daneren2005.dsub.domain.Genre;
import github.daneren2005.dsub.domain.Indexes;
import github.daneren2005.dsub.domain.Playlist;
import github.daneren2005.dsub.domain.PodcastChannel;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.MusicFolder;
import github.daneren2005.dsub.domain.PodcastEpisode;
import github.daneren2005.dsub.service.MediaStoreService;
import github.daneren2005.dsub.service.plex.PlexMusicService;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

/**
 * @author Sindre Mehus
 */
public class FileUtil {

    private static final String TAG = FileUtil.class.getSimpleName();
    private static final String[] FILE_SYSTEM_UNSAFE = {"/", "\\", "..", ":", "\"", "?", "*", "<", ">", "|"};
    private static final String[] FILE_SYSTEM_UNSAFE_DIR = {"\\", "..", ":", "\"", "?", "*", "<", ">", "|"};
    private static final List<String> MUSIC_FILE_EXTENSIONS = Arrays.asList("mp3", "ogg", "aac", "flac", "m4a", "wav", "wma", "opus", "oga");
	private static final List<String> VIDEO_FILE_EXTENSIONS = Arrays.asList("flv", "mp4", "m4v", "wmv", "avi", "mov", "mpg", "mkv", "3gp", "webm");
	private static final List<String> PLAYLIST_FILE_EXTENSIONS = Arrays.asList("m3u");
	private static final int MAX_FILENAME_LENGTH = 254 - ".complete.mp3".length();
    private static File DEFAULT_MUSIC_DIR;
	private static final Kryo kryo = new Kryo();
	private static HashMap<String, MusicDirectory.Entry> entryLookup;

	static {
		kryo.register(MusicDirectory.Entry.class);
		kryo.register(Indexes.class);
		kryo.register(Artist.class);
		kryo.register(MusicFolder.class);
		kryo.register(PodcastChannel.class);
		kryo.register(Playlist.class);
		kryo.register(Genre.class);
		kryo.register(AlbumDetails.class);
		kryo.register(AlbumSnapshot.class);
	}
	
	public static File getAnySong(Context context) {
		File dir = getMusicDirectory(context);
		return getAnySong(context, dir);
	}
	private static File getAnySong(Context context, File dir) {
		for(File file: dir.listFiles()) {
			if(file.isDirectory()) {
				return getAnySong(context, file);
			}
			
			String extension = getExtension(file.getName());
			if(MUSIC_FILE_EXTENSIONS.contains(extension)) {
				return file;
			}
		}
		
		return null;
	}
	
	public static File getEntryFile(Context context, MusicDirectory.Entry entry) {
		if(entry.isDirectory()) {
			return getAlbumDirectory(context, entry);
		} else {
			return getSongFile(context, entry);
		}
	}

    public static File getSongFile(Context context, MusicDirectory.Entry song) {
        File dir = getAlbumDirectory(context, song);

        StringBuilder fileName = new StringBuilder();
        Integer track = song.getTrack();
        if (track != null) {
            if (track < 10) {
                fileName.append("0");
            }
            fileName.append(track).append("-");
        }

        fileName.append(fileSystemSafe(song.getTitle()));
		if(fileName.length() >= MAX_FILENAME_LENGTH) {
			fileName.setLength(MAX_FILENAME_LENGTH);
		}

		fileName.append(".");
		if(song.isVideo()) {
			String videoPlayerType = Util.getVideoPlayerType(context);
			if("hls".equals(videoPlayerType)) {
				// HLS should be able to transcode to mp4 automatically
				fileName.append("mp4");
			} else if("raw".equals(videoPlayerType)) {
				// Download the original video without any transcoding
				fileName.append(song.getSuffix());
			}
		} else {
			if (song.getTranscodedSuffix() != null) {
				fileName.append(song.getTranscodedSuffix());
			} else {
				fileName.append(song.getSuffix());
			}
		}

        return new File(dir, fileName.toString());
    }

	public static File getPlaylistFile(Context context, String server, String name) {
		File playlistDir = getPlaylistDirectory(context, server);
		return new File(playlistDir, fileSystemSafe(name) + ".m3u");
	}
	public static void writePlaylistFile(Context context, File file, MusicDirectory playlist) throws IOException {
		FileWriter fw = new FileWriter(file);
		BufferedWriter bw = new BufferedWriter(fw);
		try {
			fw.write("#EXTM3U\n");
			for (MusicDirectory.Entry e : playlist.getChildren()) {
				String filePath = FileUtil.getSongFile(context, e).getAbsolutePath();
				if(! new File(filePath).exists()){
					String ext = FileUtil.getExtension(filePath);
					String base = FileUtil.getBaseName(filePath);
					filePath = base + ".complete." + ext;
				}
				fw.write(filePath + "\n");
			}
		} catch(Exception e) {
			Log.w(TAG, "Failed to save playlist: " + playlist.getName());
		} finally {
			bw.close();
			fw.close();
		}
	}
	public static File getPlaylistDirectory(Context context) {
		File playlistDir = new File(getSubsonicDirectory(context), "playlists");
		ensureDirectoryExistsAndIsReadWritable(playlistDir);
		return playlistDir;
	}
	public static File getPlaylistDirectory(Context context, String server) {
		File playlistDir = new File(getPlaylistDirectory(context), server);
		ensureDirectoryExistsAndIsReadWritable(playlistDir);
		return playlistDir;
	}

	public static File getAlbumArtFile(Context context, PodcastChannel channel) {
		MusicDirectory.Entry entry = new MusicDirectory.Entry();
		entry.setId(channel.getId());
		entry.setTitle(channel.getName());
		return getAlbumArtFile(context, entry);
	}
    public static File getAlbumArtFile(Context context, MusicDirectory.Entry entry) {
		if(entry.getId().indexOf(ImageLoader.PLAYLIST_PREFIX) != -1) {
			File dir = getAlbumArtDirectory(context);
			return  new File(dir, Util.md5Hex(ImageLoader.PLAYLIST_PREFIX + entry.getTitle()) + ".jpeg");
		} else if(entry.getId().indexOf(ImageLoader.PODCAST_PREFIX) != -1) {
			File dir = getAlbumArtDirectory(context);
			return  new File(dir, Util.md5Hex(ImageLoader.PODCAST_PREFIX + entry.getTitle()) + ".jpeg");
		} else if(entry.getId().startsWith(ImageLoader.ARTIST_PREFIX)) {
			// An artist's picture is filed by the picture itself, rather than by the folder
			// an album of theirs would live in. That folder is "<artist>/<album>", which for
			// a self-titled album is the same folder the artist would be given: whichever of
			// the two was drawn first then answered for both, and the White Album showed a
			// photograph of the band.
			File dir = getAlbumArtDirectory(context);
			String art = entry.getCoverArt() != null ? entry.getCoverArt() : entry.getTitle();
			return new File(dir, Util.md5Hex(ImageLoader.ARTIST_PREFIX + art) + ".jpeg");
		} else {
			File albumDir = getAlbumDirectory(context, entry);
			File artFile;
			File albumFile = getAlbumArtFile(albumDir);
			File hexFile = getHexAlbumArtFile(context, albumDir);
			if (albumDir.exists()) {
				if (hexFile.exists()) {
					hexFile.renameTo(albumFile);
				}
				artFile = albumFile;
			} else {
				artFile = hexFile;
			}
			return artFile;
		}
    }

    public static File getAlbumArtFile(File albumDir) {
        return new File(albumDir, Constants.ALBUM_ART_FILE);
    }
	public static File getHexAlbumArtFile(Context context, File albumDir) {
		return new File(getAlbumArtDirectory(context), Util.md5Hex(albumDir.getPath()) + ".jpeg");
	}

    public static Bitmap getAlbumArtBitmap(Context context, MusicDirectory.Entry entry, int size) {
        File albumArtFile = getAlbumArtFile(context, entry);
        if (albumArtFile.exists()) {
			final BitmapFactory.Options opt = new BitmapFactory.Options();
			opt.inJustDecodeBounds = true;
			BitmapFactory.decodeFile(albumArtFile.getPath(), opt);
			opt.inPurgeable = true;
			opt.inSampleSize = Util.calculateInSampleSize(opt, size, Util.getScaledHeight(opt.outHeight, opt.outWidth, size));
			opt.inJustDecodeBounds = false;

			Bitmap bitmap = BitmapFactory.decodeFile(albumArtFile.getPath(), opt);
			return bitmap == null ? null : getScaledBitmap(bitmap, size);
        }
        return null;
    }

	public static File getAvatarDirectory(Context context) {
		File avatarDir = new File(getSubsonicDirectory(context), "avatars");
		ensureDirectoryExistsAndIsReadWritable(avatarDir);
		ensureDirectoryExistsAndIsReadWritable(new File(avatarDir, ".nomedia"));
		return avatarDir;
	}

	public static File getAvatarFile(Context context, String username) {
		return new File(getAvatarDirectory(context), Util.md5Hex(username) + ".jpeg");
	}

	public static Bitmap getAvatarBitmap(Context context, String username, int size) {
		File avatarFile = getAvatarFile(context, username);
		if (avatarFile.exists()) {
			final BitmapFactory.Options opt = new BitmapFactory.Options();
			opt.inJustDecodeBounds = true;
			BitmapFactory.decodeFile(avatarFile.getPath(), opt);
			opt.inPurgeable = true;
			opt.inSampleSize = Util.calculateInSampleSize(opt, size, Util.getScaledHeight(opt.outHeight, opt.outWidth, size));
			opt.inJustDecodeBounds = false;

			Bitmap bitmap = BitmapFactory.decodeFile(avatarFile.getPath(), opt);
			return bitmap == null ? null : getScaledBitmap(bitmap, size, false);
		}
		return null;
	}

	public static File getMiscDirectory(Context context) {
		File dir = new File(getSubsonicDirectory(context), "misc");
		ensureDirectoryExistsAndIsReadWritable(dir);
		ensureDirectoryExistsAndIsReadWritable(new File(dir, ".nomedia"));
		return dir;
	}

	public static File getMiscFile(Context context, String url) {
		return new File(getMiscDirectory(context), Util.md5Hex(url) + ".jpeg");
	}

	public static Bitmap getMiscBitmap(Context context, String url, int size) {
		File avatarFile = getMiscFile(context, url);
		if (avatarFile.exists()) {
			final BitmapFactory.Options opt = new BitmapFactory.Options();
			opt.inJustDecodeBounds = true;
			BitmapFactory.decodeFile(avatarFile.getPath(), opt);
			opt.inPurgeable = true;
			opt.inSampleSize = Util.calculateInSampleSize(opt, size, Util.getScaledHeight(opt.outHeight, opt.outWidth, size));
			opt.inJustDecodeBounds = false;

			Bitmap bitmap = BitmapFactory.decodeFile(avatarFile.getPath(), opt);
			return bitmap == null ? null : getScaledBitmap(bitmap, size, false);
		}
		return null;
	}

	public static Bitmap getSampledBitmap(byte[] bytes, int size) {
		return getSampledBitmap(bytes, size, true);
	}
	public static Bitmap getSampledBitmap(byte[] bytes, int size, boolean allowUnscaled) {
		final BitmapFactory.Options opt = new BitmapFactory.Options();
		opt.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);
		opt.inPurgeable = true;
		opt.inSampleSize = Util.calculateInSampleSize(opt, size, Util.getScaledHeight(opt.outHeight, opt.outWidth, size));
		opt.inJustDecodeBounds = false;
		Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);
		if(bitmap == null) {
			return null;
		} else {
			return getScaledBitmap(bitmap, size, allowUnscaled);
		}
	}
	public static Bitmap getScaledBitmap(Bitmap bitmap, int size) {
		return getScaledBitmap(bitmap, size, true);
	}
	public static Bitmap getScaledBitmap(Bitmap bitmap, int size, boolean allowUnscaled) {
		// Don't waste time scaling if the difference is minor
		// Large album arts still need to be scaled since displayed as is on now playing!
		if(allowUnscaled && size < 400 && bitmap.getWidth() < (size * 1.1)) {
			return bitmap;
		} else {
			return Bitmap.createScaledBitmap(bitmap, size, Util.getScaledHeight(bitmap, size), true);
		}
	}

	public static File getAlbumArtDirectory(Context context) {
		File albumArtDir = new File(getSubsonicDirectory(context), "artwork");
		ensureDirectoryExistsAndIsReadWritable(albumArtDir);
		ensureDirectoryExistsAndIsReadWritable(new File(albumArtDir, ".nomedia"));
		return albumArtDir;
	}

	public static File getArtistDirectory(Context context, Artist artist) {
		File dir = new File(getMusicDirectory(context).getPath() + "/" + fileSystemSafe(artist.getName()));
		return dir;
	}
	public static File getArtistDirectory(Context context, MusicDirectory.Entry artist) {
		File dir = new File(getMusicDirectory(context).getPath() + "/" + fileSystemSafe(artist.getTitle()));
		return dir;
	}

    /**
     * Whether a path says where the file sits on the server, which is what the cache is
     * laid out along.
     *
     * Subsonic hands back exactly that - "Artist/Album/01 Song.mp3" - and mirroring it is
     * how the cache has always been arranged. Plex has nothing of the sort to give: what
     * it puts in the same field is the key its streaming endpoint is addressed by, and
     * arranging the cache along those files every track under a number of its own, with
     * no artist or album anywhere. Offline browsing then shows the only thing that is
     * there, which is a tree of numbers.
     */
    private static boolean isServerFilePath(String path) {
        return path != null && !path.startsWith(PlexMusicService.PART_KEY_PREFIX);
    }

    public static File getAlbumDirectory(Context context, MusicDirectory.Entry entry) {
        File dir = null;
        if (isServerFilePath(entry.getPath())) {
            File f = new File(fileSystemSafeDir(entry.getPath()));
			String folder = getMusicDirectory(context).getPath();
			if(entry.isDirectory()) {
				folder += "/" + f.getPath();
			} else if(f.getParent() != null) {
				folder += "/" + f.getParent();
			}
            dir = new File(folder);
        } else {
			MusicDirectory.Entry firstSong;
			if(!Util.isOffline(context)) {
				firstSong = lookupChild(context, entry, false);
				if(firstSong != null) {
					File songFile = FileUtil.getSongFile(context, firstSong);
					dir = songFile.getParentFile();
				}
			}

			if(dir == null) {
				// The album's own artist, not the track's: a compilation belongs in one folder
				// rather than scattered under each of its singers, where nothing - the offline
				// Library, the downloaded albums row, a track count - can see it whole again.
				String artist = fileSystemSafe(entry.getAlbumArtist());
				String album = fileSystemSafe(entry.getAlbum());
				if("unnamed".equals(album)) {
					album = fileSystemSafe(entry.getTitle());
				}
				dir = new File(getMusicDirectory(context).getPath() + "/" + artist + "/" + album);
			}
        }
        return dir;
    }

	public static MusicDirectory.Entry lookupChild(Context context, MusicDirectory.Entry entry, boolean allowDir) {
		// Initialize lookupMap if first time called
		String lookupName = Util.getCacheName(context, "entryLookup");
		if(entryLookup == null) {
			entryLookup = deserialize(context, lookupName, HashMap.class);
			
			// Create it if 
			if(entryLookup == null) {
				entryLookup = new HashMap<String, MusicDirectory.Entry>();
			}
		}
		
		// Check if this lookup has already been done before
		MusicDirectory.Entry child = entryLookup.get(entry.getId());
		if(child != null) {
			return child;
		}
		
		// Do a special lookup since 4.7+ doesn't match artist/album to entry.getPath
		String s = Util.getRestUrl(context, null, false) + entry.getId();
		String cacheName = (Util.isTagBrowsing(context) ? "album-" : "directory-") + s.hashCode() + ".ser";
		MusicDirectory entryDir = FileUtil.deserialize(context, cacheName, MusicDirectory.class);

		if(entryDir != null) {
			List<MusicDirectory.Entry> songs = entryDir.getChildren(allowDir, true);
			if(songs.size() > 0) {
				child = songs.get(0);
				entryLookup.put(entry.getId(), child);
				serialize(context, entryLookup, lookupName);
				return child;
			}
		}

		return null;
	}
	
	public static String getPodcastPath(Context context, PodcastEpisode episode) {
		return fileSystemSafe(episode.getArtist()) + "/" + fileSystemSafe(episode.getTitle());
	}
	public static File getPodcastFile(Context context, String server) {
		File dir = getPodcastDirectory(context);
		return new File(dir.getPath() + "/" +  fileSystemSafe(server));
	}
	public static File getPodcastDirectory(Context context) {
		File dir = new File(context.getCacheDir(), "podcasts");
		ensureDirectoryExistsAndIsReadWritable(dir);
		return dir;
	}
	public static File getPodcastDirectory(Context context, PodcastChannel channel) {
		File dir = new File(getMusicDirectory(context).getPath() + "/" + fileSystemSafe(channel.getName()));
		return dir;
	}
	public static File getPodcastDirectory(Context context, String channel) {
		File dir = new File(getMusicDirectory(context).getPath() + "/" + fileSystemSafe(channel));
		return dir;
	}

    public static void createDirectoryForParent(File file) {
        File dir = file.getParentFile();
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e(TAG, "Failed to create directory " + dir);
            }
        }
    }

    private static File createDirectory(Context context, String name) {
        File dir = new File(getSubsonicDirectory(context), name);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Failed to create " + name);
        }
        return dir;
    }

    public static File getSubsonicDirectory(Context context) {
        return context.getExternalFilesDir(null);
    }

    public static File getDefaultMusicDirectory(Context context) {
		if(DEFAULT_MUSIC_DIR == null) {
			File[] dirs;
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
				dirs = context.getExternalMediaDirs();
			} else {
				dirs = ContextCompat.getExternalFilesDirs(context, null);
			}

			DEFAULT_MUSIC_DIR = new File(getBestDir(dirs), "music");

			if (!DEFAULT_MUSIC_DIR.exists() && !DEFAULT_MUSIC_DIR.mkdirs()) {
				Log.e(TAG, "Failed to create default dir " + DEFAULT_MUSIC_DIR);

				// Some devices seem to have screwed up the new media directory API.  Go figure.  Try again with standard locations
				dirs = ContextCompat.getExternalFilesDirs(context, null);

				DEFAULT_MUSIC_DIR = new File(getBestDir(dirs), "music");
				if (!DEFAULT_MUSIC_DIR.exists() && !DEFAULT_MUSIC_DIR.mkdirs()) {
					Log.e(TAG, "Failed to create default dir " + DEFAULT_MUSIC_DIR);
				} else {
					Log.w(TAG, "Stupid OEM's messed up media dir API added in 5.0");
				}
			}
		}

        return DEFAULT_MUSIC_DIR;
    }
	private static File getBestDir(File[] dirs) {
		// Ask directly for the SD Card
		for(int i = 0; i < dirs.length; i++) {
			try {
				if (dirs[i] != null && Environment.isExternalStorageRemovable(dirs[i])) {
					return dirs[i];
				}
			} catch (Exception e) {
				Log.e(TAG, "Failed to check if is external", e);
			}
		}

		// Nothing said it was removable, so guess.  Most of the time the SD card is last
		for(int i = dirs.length - 1; i >= 0; i--) {
			if(dirs[i] != null) {
				return dirs[i];
			}
		}

		// Should be impossible to be reached
		return dirs[0];
	}

    public static File getMusicDirectory(Context context) {
        String path = Util.getPreferences(context).getString(Constants.PREFERENCES_KEY_CACHE_LOCATION, getDefaultMusicDirectory(context).getPath());
        File dir = new File(path);
        return ensureDirectoryExistsAndIsReadWritable(dir) ? dir : getDefaultMusicDirectory(context);
    }
	public static boolean deleteMusicDirectory(Context context) {
		File musicDirectory = FileUtil.getMusicDirectory(context);
		MediaStoreService mediaStore = new MediaStoreService(context);
		return recursiveDelete(musicDirectory, mediaStore);
	}

	/**
	 * Deletes every song the cache is holding, and leaves the ones downloaded permanently.
	 *
	 * Clearing the cache used to take the whole music folder, pinned albums with it, which
	 * made it no use as a routine chore: the downloads chosen on purpose went out with the
	 * ones that were only ever passing through. The automatic cleanup already told them
	 * apart by the markers on the file name, and this goes by the same rule.
	 */
	public static void deleteCachedSongs(Context context) {
		deleteSongs(context, false);
	}

	/** Deletes every song downloaded permanently, and leaves the cache alone. */
	public static void deletePermanentSongs(Context context) {
		deleteSongs(context, true);
	}

	/** What deleting the permanent downloads would free, in bytes. */
	public static long getPermanentCacheSize(Context context) {
		return songBytes(getMusicDirectory(context), true);
	}

	/** A song only held by the cache: finished, or still downloading. */
	private static boolean isCachedSong(File file) {
		String name = file.getName();
		return name.endsWith(".partial") || name.contains(".partial.") || name.endsWith(".complete") || name.contains(".complete.");
	}

	/** A song saved for good: music or video carrying neither of the cache's markers. */
	private static boolean isPermanentSong(File file) {
		return !isCachedSong(file) && (isMusicFile(file) || isVideoFile(file));
	}

	private static void deleteSongs(Context context, boolean permanent) {
		File root = getMusicDirectory(context);
		deleteSongsUnder(root, root, permanent, new MediaStoreService(context));
	}

	private static void deleteSongsUnder(File dir, File root, boolean permanent, MediaStoreService mediaStore) {
		File[] children = dir.listFiles();
		if(children == null) {
			return;
		}

		for(File child: children) {
			if(child.isDirectory()) {
				deleteSongsUnder(child, root, permanent, mediaStore);
			} else if(permanent ? isPermanentSong(child) : isCachedSong(child)) {
				if(child.delete()) {
					mediaStore.deleteFromMediaStore(child);
				}
			}
		}

		// A folder keeps its art only while it still has a song to show it for.
		if(!dir.equals(root)) {
			deleteEmptyDir(dir);
		}
	}

	private static long songBytes(File dir, boolean permanent) {
		File[] children = dir.listFiles();
		if(children == null) {
			return 0L;
		}

		long size = 0L;
		for(File child: children) {
			if(child.isDirectory()) {
				size += songBytes(child, permanent);
			} else if(permanent ? isPermanentSong(child) : isCachedSong(child)) {
				size += child.length();
			}
		}
		return size;
	}
	/** Drops one serialized cache file, so whatever it held is fetched again next time. */
	public static boolean deleteSerialized(Context context, String fileName) {
		File file = new File(context.getCacheDir(), fileName);
		return file.exists() && file.delete();
	}
	/**
	 * How long ago something was serialized, in milliseconds, or -1 when it never was.
	 *
	 * The file's own timestamp is already the record of when it was last written, so
	 * nothing extra has to be kept to know how old a cached copy has got.
	 */
	public static long serializedAge(Context context, String fileName) {
		File file = new File(context.getCacheDir(), fileName);
		if(!file.exists()) {
			return -1;
		}
		return System.currentTimeMillis() - file.lastModified();
	}
	public static void deleteSerializedCache(Context context) {
		deleteSerializedCache(context, null);
	}
	/** As above, sparing one file by name. */
	public static void deleteSerializedCache(Context context, String keep) {
		for(File file: context.getCacheDir().listFiles()) {
			if(file.getName().indexOf(".ser") != -1 && !file.getName().equals(keep)) {
				file.delete();
			}
		}
	}
	public static boolean deleteArtworkCache(Context context) {
		File artDirectory = FileUtil.getAlbumArtDirectory(context);
		return recursiveDelete(artDirectory);
	}
	public static boolean deleteAvatarCache(Context context) {
		File artDirectory = FileUtil.getAvatarDirectory(context);
		return recursiveDelete(artDirectory);
	}

	public static boolean recursiveDelete(File dir) {
		return recursiveDelete(dir, null);
	}
	public static boolean recursiveDelete(File dir, MediaStoreService mediaStore) {
		if (dir != null && dir.exists()) {
			File[] list = dir.listFiles();
			if(list != null) {
				for(File file: list) {
					if(file.isDirectory()) {
						if(!recursiveDelete(file, mediaStore)) {
							return false;
						}
					} else if(file.exists()) {
						if(!file.delete()) {
							return false;
						} else if(mediaStore != null) {
							mediaStore.deleteFromMediaStore(file);
						}
					}
				}
			}
			return dir.delete();
		}
		return false;
	}

	public static void deleteEmptyDir(File dir) {
		try {
			File[] children = dir.listFiles();
			if(children == null) {
				return;
			}

			// No songs left in the folder
			if (children.length == 1 && children[0].getPath().equals(FileUtil.getAlbumArtFile(dir).getPath())) {
				Util.delete(children[0]);
				children = dir.listFiles();
			}

			// Delete empty directory
			if (children.length == 0) {
				Util.delete(dir);
			}
		} catch(Exception e) {
			Log.w(TAG, "Error while trying to delete empty dir", e);
		}
	}

	public static void unpinSong(Context context, File saveFile) {
		// Don't try to unpin a song which isn't actually pinned
		if(saveFile.getName().contains(".complete")) {
			return;
		}

		// Unpin file, rename to .complete
		File completeFile = new File(saveFile.getParent(), FileUtil.getBaseName(saveFile.getName()) +
				".complete." + FileUtil.getExtension(saveFile.getName()));

		if(!saveFile.renameTo(completeFile)) {
			Log.w(TAG, "Failed to upin " + saveFile + " to " + completeFile);
		} else {
			try {
				new MediaStoreService(context).renameInMediaStore(completeFile, saveFile);
			} catch(Exception e) {
				Log.w(TAG, "Failed to write to media store");
			}
		}
	}

    public static boolean ensureDirectoryExistsAndIsReadWritable(File dir) {
        if (dir == null) {
            return false;
        }

        if (dir.exists()) {
            if (!dir.isDirectory()) {
                Log.w(TAG, dir + " exists but is not a directory.");
                return false;
            }
        } else {
            if (dir.mkdirs()) {
                Log.i(TAG, "Created directory " + dir);
            } else {
                Log.w(TAG, "Failed to create directory " + dir);
                return false;
            }
        }

        if (!dir.canRead()) {
            Log.w(TAG, "No read permission for directory " + dir);
            return false;
        }

        if (!dir.canWrite()) {
            Log.w(TAG, "No write permission for directory " + dir);
            return false;
        }
        return true;
    }
	public static boolean verifyCanWrite(File dir) {
		if(ensureDirectoryExistsAndIsReadWritable(dir)) {
			try {
				File tmp = new File(dir, "checkWrite");
				tmp.createNewFile();
				if(tmp.exists()) {
					if(tmp.delete()) {
						return true;
					} else {
						Log.w(TAG, "Failed to delete temp file, retrying");
						
						// This should never be reached since this is a file DSub created!
						Thread.sleep(100L);
						tmp = new File(dir, "checkWrite");
						if(tmp.delete()) {
							return true;
						} else {
							Log.w(TAG, "Failed retry to delete temp file");
							return false;
						}
					}
				} else {
					Log.w(TAG, "Temp file does not actually exist");
					return false;
				}
			} catch(Exception e) {
				Log.w(TAG, "Failed to create tmp file", e);
				return false;
			}
		} else {
			return false;
		}
	}

    /**
    * Makes a given filename safe by replacing special characters like slashes ("/" and "\")
    * with dashes ("-").
    *
    * @param filename The filename in question.
    * @return The filename with special characters replaced by hyphens.
    */
    static String fileSystemSafe(String filename) {
        if (filename == null || filename.trim().length() == 0) {
            return "unnamed";
        }

        for (String s : FILE_SYSTEM_UNSAFE) {
            filename = filename.replace(s, "-");
        }
        return filename;
    }

    /**
     * Makes a given filename safe by replacing special characters like colons (":")
     * with dashes ("-").
     *
     * @param path The path of the directory in question.
     * @return The the directory name with special characters replaced by hyphens.
     */
    private static String fileSystemSafeDir(String path) {
        if (path == null || path.trim().length() == 0) {
            return "";
        }

		// Airsonic 11 appears to be returning backslashes instead of always forcing path with forward slashes
		if(!path.contains("/")) {
			path = path.replace("\\", "/");
		}
        for (String s : FILE_SYSTEM_UNSAFE_DIR) {
            path = path.replace(s, "-");
        }
        return path;
    }

    /**
     * Similar to {@link File#listFiles()}, but returns a sorted set.
     * Never returns {@code null}, instead a warning is logged, and an empty set is returned.
     */
    public static SortedSet<File> listFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            Log.w(TAG, "Failed to list children for " + dir.getPath());
            return new TreeSet<File>();
        }

        return new TreeSet<File>(Arrays.asList(files));
    }

    public static SortedSet<File> listMediaFiles(File dir) {
        SortedSet<File> files = listFiles(dir);
        Iterator<File> iterator = files.iterator();
        while (iterator.hasNext()) {
            File file = iterator.next();
            if (!file.isDirectory() && !isMediaFile(file)) {
                iterator.remove();
            }
        }
        return files;
    }

    private static boolean isMediaFile(File file) {
        String extension = getExtension(file.getName());
        return MUSIC_FILE_EXTENSIONS.contains(extension) || VIDEO_FILE_EXTENSIONS.contains(extension);
    }
	
	public static boolean isMusicFile(File file) {
		String extension = getExtension(file.getName());
        return MUSIC_FILE_EXTENSIONS.contains(extension);
	}
	public static boolean isVideoFile(File file) {
		String extension = getExtension(file.getName());
        return VIDEO_FILE_EXTENSIONS.contains(extension);
	}
	
	public static boolean isPlaylistFile(File file) {
		String extension = getExtension(file.getName());
		return PLAYLIST_FILE_EXTENSIONS.contains(extension);
	}

    /**
     * Returns the extension (the substring after the last dot) of the given file. The dot
     * is not included in the returned extension.
     *
     * @param name The filename in question.
     * @return The extension, or an empty string if no extension is found.
     */
    public static String getExtension(String name) {
        int index = name.lastIndexOf('.');
        return index == -1 ? "" : name.substring(index + 1).toLowerCase();
    }

    /**
     * Returns the base name (the substring before the last dot) of the given file. The dot
     * is not included in the returned basename.
     *
     * @param name The filename in question.
     * @return The base name, or an empty string if no basename is found.
     */
    public static String getBaseName(String name) {
        int index = name.lastIndexOf('.');
        return index == -1 ? name : name.substring(0, index);
    }
	
	public static Long[] getUsedSize(Context context, File file) {
		long number = 0L;
		long permanent = 0L;
		long size = 0L;
		
		if(file.isFile()) {
			if(isMediaFile(file)) {
				if(file.getAbsolutePath().indexOf(".complete") == -1) {
					permanent++;
				}
				return new Long[] {1L, permanent, file.length()};
			} else {
				return new Long[] {0L, 0L, 0L};
			}
		} else {
			for (File child : FileUtil.listFiles(file)) {
				Long[] pair = getUsedSize(context, child);
				number += pair[0];
				permanent += pair[1];
				size += pair[2];
			}
			return new Long[] {number, permanent, size};
		}
	}

	/**
	 * Everything clearing the cache would delete, in bytes.
	 *
	 * Deliberately not getUsedSize(): that one only counts media files, so it answers how
	 * much music is held rather than how much space giving the cache up would return.
	 * Artwork in particular is thousands of small files and is worth seeing.
	 */
	public static long getCacheSize(Context context) {
		long size = songBytes(getMusicDirectory(context), false)
				+ directorySize(getAlbumArtDirectory(context))
				+ directorySize(getAvatarDirectory(context));

		File[] serialized = context.getCacheDir().listFiles();
		if(serialized != null) {
			for(File file: serialized) {
				if(file.getName().indexOf(".ser") != -1) {
					size += file.length();
				}
			}
		}

		return size;
	}

	private static long directorySize(File dir) {
		if(dir == null || !dir.exists()) {
			return 0L;
		}
		if(dir.isFile()) {
			return dir.length();
		}

		long size = 0L;
		File[] children = dir.listFiles();
		if(children != null) {
			for(File child: children) {
				size += directorySize(child);
			}
		}
		return size;
	}

    public static <T extends Serializable> boolean serialize(Context context, T obj, String fileName) {
		Output out = null;
		try {
			RandomAccessFile file = new RandomAccessFile(context.getCacheDir() + "/" + fileName, "rw");
			out = new Output(new FileOutputStream(file.getFD()));
			synchronized (kryo) {
				kryo.writeObject(out, obj);
			}
			return true;
		} catch (Throwable x) {
			Log.w(TAG, "Failed to serialize object to " + fileName);
			return false;
		} finally {
			Util.close(out);
		}
    }

	public static <T extends Serializable> T deserialize(Context context, String fileName, Class<T> tClass) {
		return deserialize(context, fileName, tClass, 0);
	}

    public static <T extends Serializable> T deserialize(Context context, String fileName, Class<T> tClass, int hoursOld) {
		Input in = null;
		try {
			File file = new File(context.getCacheDir(), fileName);
			if(!file.exists()) {
				return null;
			}

			if(hoursOld != 0) {
				Date fileDate = new Date(file.lastModified());
				// Convert into hours
				long age = (new Date().getTime() - fileDate.getTime()) / 1000 / 3600;
				if(age > hoursOld) {
					return null;
				}
			}

			RandomAccessFile randomFile = new RandomAccessFile(file, "r");

			in = new Input(new FileInputStream(randomFile.getFD()));
			synchronized (kryo) {
				T result = kryo.readObject(in, tClass);
				return result;
			}
		} catch(FileNotFoundException e) {
			// Different error message
			Log.w(TAG, "No serialization for object from " + fileName);
			return null;
		} catch (Throwable x) {
			Log.w(TAG, "Failed to deserialize object from " + fileName);
			return null;
		} finally {
			Util.close(in);
		}
    }

	public static <T extends Serializable> boolean serializeCompressed(Context context, T obj, String fileName) {
		Output out = null;
		try {
			RandomAccessFile file = new RandomAccessFile(context.getCacheDir() + "/" + fileName, "rw");
			out = new Output(new DeflaterOutputStream(new FileOutputStream(file.getFD())));
			synchronized (kryo) {
				kryo.writeObject(out, obj);
			}
			return true;
		} catch (Throwable x) {
			Log.w(TAG, "Failed to serialize compressed object to " + fileName);
			return false;
		} finally {
			Util.close(out);
		}
	}

	public static <T extends Serializable> T deserializeCompressed(Context context, String fileName, Class<T> tClass) {
		Input in = null;
		try {
			RandomAccessFile file = new RandomAccessFile(context.getCacheDir() + "/" + fileName, "r");

			in = new Input(new InflaterInputStream(new FileInputStream(file.getFD())));
			synchronized (kryo) {
				T result = kryo.readObject(in, tClass);
				return result;
			}
		} catch(FileNotFoundException e) {
			// Different error message
			Log.w(TAG, "No serialization compressed for object from " + fileName);
			return null;
		} catch (Throwable x) {
			Log.w(TAG, "Failed to deserialize compressed object from " + fileName);
			return null;
		} finally {
			Util.close(in);
		}
	}
}
