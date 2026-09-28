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
 */
package github.daneren2005.dsub.service.plex;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.domain.AlbumDetails;
import github.daneren2005.dsub.domain.ArtistInfo;
import github.daneren2005.dsub.domain.Artist;
import github.daneren2005.dsub.domain.ChatMessage;
import github.daneren2005.dsub.domain.Genre;
import github.daneren2005.dsub.domain.Indexes;
import github.daneren2005.dsub.domain.InternetRadioStation;
import github.daneren2005.dsub.domain.Lyrics;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.MusicFolder;
import github.daneren2005.dsub.domain.PlayerQueue;
import github.daneren2005.dsub.domain.Playlist;
import github.daneren2005.dsub.domain.PodcastChannel;
import github.daneren2005.dsub.domain.RemoteStatus;
import github.daneren2005.dsub.domain.SearchCritera;
import github.daneren2005.dsub.domain.SearchResult;
import github.daneren2005.dsub.domain.Share;
import github.daneren2005.dsub.domain.SyncedLyrics;
import github.daneren2005.dsub.domain.User;
import github.daneren2005.dsub.service.MusicService;
import github.daneren2005.dsub.util.FileUtil;
import github.daneren2005.dsub.util.ProgressListener;
import github.daneren2005.dsub.util.SilentBackgroundTask;
import github.daneren2005.dsub.util.Util;

/**
 * Talks to a Plex Media Server.
 *
 * Plex models a music library as a section containing three item types --
 * artist (8), album (9) and track (10) -- each addressed by an opaque
 * "ratingKey". That maps cleanly onto DSub's tag browsing mode, which is why
 * {@link Util#isTagBrowsing} is forced on for Plex servers.
 *
 * A number of MusicService features have no Plex equivalent at all (podcasts,
 * jukebox, chat, shares, internet radio, user administration). Those throw
 * UnsupportedOperationException and the corresponding UI is hidden.
 */
public class PlexMusicService implements MusicService {
	private static final String TAG = PlexMusicService.class.getSimpleName();

	private static final String LIBRARY_IDENTIFIER = "com.plexapp.plugins.library";
	/** Synthetic id for the list of collections. */
	public static final String COLLECTIONS_ID = "plex-collections";
	/** Prefix marking an id as a collection rather than a library item. */
	public static final String COLLECTION_PREFIX = "plex-collection:";
	/**
	 * What a Part's streaming key starts with. Kept here next to the code that reads it,
	 * but public because the cache has to recognise one to know it is not a file path.
	 */
	public static final String PART_KEY_PREFIX = "/library/parts/";

	/** Albums per detail round trip. See getAlbumDetails for why this is not larger. */
	private static final int DETAIL_BATCH_SIZE = 50;
	/** Comfortably above the tracks a batch of albums can hold, so nothing is paged off. */
	private static final int TRACK_PAGE_SIZE = 5000;

	/** Collections the Home recommendations are drawn from, if the server has them. */
	private static final String[] RECOMMENDED_COLLECTIONS = {"3 stars", "4 stars and over"};
	/** Albums read per collection - about 145 KB each. Over half typically qualify, which
	 *  still leaves several times what a row needs, and keeps the cost sane on mobile data. */
	private static final int RECOMMENDATION_WINDOW = 60;
	/** Six months, in seconds: how long an album must have been left alone. */
	private static final long RECOMMENDATION_MIN_AGE = 182L * 24L * 60L * 60L;

	/**
	 * The dashboard maintains this collection: at least three stars, at least three
	 * tracks, under thirty minutes. Each of those is at least as loose as what this row
	 * asks for, which makes it a superset - so the row can start here instead of
	 * measuring the whole library. Checked: filtering these 196 albums finds exactly the
	 * same records as scanning all 1241 rated ones, for a tenth of the traffic.
	 *
	 * The duration bound now matches the dashboard's own exactly, so tightening
	 * EP_MAX_MINUTES there would start hiding albums from this row.
	 */
	private static final String SHORT_ALBUM_COLLECTION = "EPs and short albums";
	/**
	 * Collections that count as well rated, three stars and up. Plex exposes no star
	 * rating on albums at all, so the tiers can only be read as collection membership,
	 * and there is no "3 stars and over" - "4 stars and over" already covers four and
	 * five, leaving only "3 stars" to add.
	 */
	private static final String[] HIGHLY_RATED_COLLECTIONS = {"3 stars", "4 stars and over"};
	/**
	 * Elements dropped from a listing that is only read for its ids. Plex answers an
	 * unknown name by ignoring it, so naming one it does not have costs nothing.
	 */
	private static final String ID_ONLY_EXCLUDES =
			"Media,Genre,Style,Mood,Country,Collection,Director,Writer,Producer,Role,Similar,Guid,Image,UltraBlurColors";
	/** Page size for a collection that did not say how many children it has. */
	private static final int COLLECTION_PAGE_SIZE = 5000;
	private static final int SHORT_ALBUM_MAX_MINUTES = 30;
	private static final int SHORT_ALBUM_MIN_TRACKS = 4;
	/** Ninety days, in seconds. */
	private static final long SHORT_ALBUM_MIN_AGE = 90L * 24L * 60L * 60L;
	/** Plex stream types; 2 is audio (1 is video, 3 subtitles, 4 lyrics). */
	private static final int STREAM_TYPE_AUDIO = 2;
	private static final int STREAM_TYPE_LYRICS = 4;
	/** Plex stores ratings out of 10; DSub uses 5 stars. */
	private static final int RATING_SCALE = 2;
	private static final int STARRED_RATING = 10;

	private final PlexClient client = new PlexClient();
	private String machineIdentifier;

	@Override
	public void setInstance(Integer instance) {
		client.setInstance(instance);
		machineIdentifier = null;
	}

	@Override
	public int getInstance(Context context) {
		return client.getInstance(context);
	}

	@Override
	public String getServerKey(Context context) {
		return client.getServerUrl(context);
	}

	// ------------------------------------------------------------ connection

	@Override
	public void ping(Context context, ProgressListener progressListener) throws Exception {
		// Stage 1: /identity needs no auth, so a failure here means the address
		// is wrong rather than the credentials.
		JSONObject identity;
		try {
			identity = client.getAnonymous(context, "/identity");
		} catch(Exception e) {
			throw new Exception("Could not reach a Plex server at this address. "
					+ "Check the server address (including https:// and port). Cause: " + e.getMessage());
		}

		if(identity.optString("machineIdentifier", null) == null) {
			throw new Exception("That address answered, but does not look like a Plex server. "
					+ "Check the server address.");
		}

		// Stage 2: the root endpoint requires a valid token, so this proves auth.
		client.get(context, "/", null);
	}

	@Override
	public boolean isLicenseValid(Context context, ProgressListener progressListener) throws Exception {
		// Plex Pass is not required to stream your own music library.
		return true;
	}

	@Override
	public void startRescan(Context context, ProgressListener listener) throws Exception {
		client.command(context, "/library/sections/" + client.getLibraryKey(context) + "/refresh", null);
	}

	/** Identifies this server when building playlist URIs. */
	private String getMachineIdentifier(Context context) throws Exception {
		if (machineIdentifier == null) {
			machineIdentifier = client.get(context, "/", null).optString("machineIdentifier", null);
		}
		if (machineIdentifier == null) {
			throw new Exception("Could not determine Plex server identifier");
		}
		return machineIdentifier;
	}

	// -------------------------------------------------------------- browsing

	@Override
	public List<MusicFolder> getMusicFolders(boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		JSONObject container = client.get(context, "/library/sections", null);
		JSONArray directories = container.optJSONArray("Directory");

		List<MusicFolder> folders = new ArrayList<MusicFolder>();
		for (int i = 0; directories != null && i < directories.length(); i++) {
			JSONObject dir = directories.getJSONObject(i);
			if ("artist".equals(dir.optString("type"))) {
				folders.add(new MusicFolder(dir.optString("key"), dir.optString("title")));
			}
		}
		return folders;
	}

	@Override
	public Indexes getIndexes(String musicFolderId, boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		String section = musicFolderId != null ? musicFolderId : client.getLibraryKey(context);
		JSONArray metadata = getMetadata(client.get(context, "/library/sections/" + section + "/all",
				PlexClient.params("type", String.valueOf(PlexClient.TYPE_ARTIST))));

		List<Artist> artists = new ArrayList<Artist>();
		for (int i = 0; i < metadata.length(); i++) {
			artists.add(parseArtist(metadata.getJSONObject(i)));
		}

		return new Indexes(0, new ArrayList<Artist>(), artists);
	}

	@Override
	public MusicDirectory getMusicDirectory(String id, String name, boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		return getChildren(context, id, name);
	}

	@Override
	public MusicDirectory getArtist(String id, String name, boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		// The caller already knows this is an artist, so go straight for the whole
		// discography; the child listing is only there for an id that turns out not
		// to be one.
		MusicDirectory albums = getArtistAlbums(context, id, name);
		return albums != null ? albums : getChildren(context, id, name);
	}

	@Override
	public MusicDirectory getAlbum(String id, String name, boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		return getChildren(context, id, name);
	}

	/**
	 * Albums yield their tracks. An artist would yield its albums, but only some of
	 * them, so that case is handed to {@link #getArtistAlbums} as soon as it shows.
	 */
	private MusicDirectory getChildren(Context context, String id, String name) throws Exception {
		// Collections are not part of the artist/album/track hierarchy, so they
		// are addressed with synthetic ids. Handling them here means every entry
		// point (getArtist/getAlbum/getMusicDirectory) resolves them.
		if (COLLECTIONS_ID.equals(id)) {
			return getCollectionList(context, name);
		}
		if (id != null && id.startsWith(COLLECTION_PREFIX)) {
			return getCollectionAlbums(context, id, name);
		}

		// Without includeElements the Part carries no Stream, and bit depth and sample
		// rate live only on the stream - the listing would otherwise report just the
		// container and bitrate. Asking for it here avoids a request per track.
		JSONObject container = client.get(context, "/library/metadata/" + id + "/children",
				PlexClient.params("includeElements", "Stream"));

		// viewGroup is how the response says what it holds, and albums mean the id was
		// an artist's after all - a listing we cannot take at face value, so ask for the
		// discography instead. Reached when the caller did not know the id was an artist
		// (a search result played from its context menu, say); getArtist skips ahead.
		if ("album".equals(container.optString("viewGroup"))) {
			MusicDirectory albums = getArtistAlbums(context, id, name);
			if (albums != null) {
				return albums;
			}
		}

		JSONArray metadata = getMetadata(container);
		MusicDirectory dir = new MusicDirectory();
		dir.setId(id);
		dir.setName(name);
		for (int i = 0; i < metadata.length(); i++) {
			dir.addChild(parseEntry(metadata.getJSONObject(i)));
		}
		return dir;
	}

	/**
	 * Every release credited to one artist, oldest first.
	 *
	 * Asking for an artist's children - the obvious call, and the one the Plex artist
	 * page itself makes - answers with only what the library files as albums. Once a
	 * music library carries album formats, EPs, singles, live records and compilations
	 * are split off into hubs of their own and never reach that listing, so an artist
	 * looks like they only ever released albums. Filtering the library by artist asks
	 * for the albums themselves and knows nothing about formats, so the whole
	 * discography comes back in one list.
	 *
	 * Returns null when nothing comes back, which is how an id that is not an artist
	 * (or a server that will not filter on artist.id) reads - the caller falls back to
	 * the child listing rather than showing an empty screen.
	 */
	private MusicDirectory getArtistAlbums(Context context, String id, String name) throws Exception {
		MusicDirectory dir = listSection(context, PlexClient.params(
				"type", String.valueOf(PlexClient.TYPE_ALBUM),
				"artist.id", id,
				"sort", "year"));
		if (dir.getChildrenSize() == 0) {
			return null;
		}

		dir.setId(id);
		dir.setName(name);
		return dir;
	}

	/** The album collections defined on the music library. */
	private MusicDirectory getCollectionList(Context context, String name) throws Exception {
		JSONObject container = client.get(context,
				"/library/sections/" + client.getLibraryKey(context) + "/collections", null);

		// Plex returns collections under "Metadata" in JSON but "Directory" in
		// XML, and which one appears varies by server version. Read both rather
		// than depend on the shape.
		List<JSONObject> items = new ArrayList<JSONObject>();
		collectInto(items, container.optJSONArray("Metadata"));
		collectInto(items, container.optJSONArray("Directory"));

		MusicDirectory dir = new MusicDirectory();
		dir.setId(COLLECTIONS_ID);
		dir.setName(name);

		for (JSONObject item : items) {
			// A music library can also hold artist and track collections; only
			// album ones make sense as a grid of albums. Older servers omit
			// subtype entirely, so fall back to accepting any collection.
			String subtype = item.optString("subtype", "");
			if (!subtype.isEmpty() && !"album".equalsIgnoreCase(subtype)) {
				continue;
			}

			String ratingKey = item.optString("ratingKey", null);
			String title = item.optString("title", null);
			if (ratingKey == null || ratingKey.isEmpty() || title == null || title.isEmpty()) {
				continue;
			}

			MusicDirectory.Entry entry = new MusicDirectory.Entry();
			entry.setId(COLLECTION_PREFIX + ratingKey);
			entry.setTitle(title);
			entry.setDirectory(true);

			String thumb = item.optString("thumb", null);
			if (thumb != null && !thumb.isEmpty()) {
				entry.setCoverArt(thumb);
			}
			dir.addChild(entry);
		}
		return dir;
	}

	/** The albums belonging to one collection. */
	private MusicDirectory getCollectionAlbums(Context context, String id, String name) throws Exception {
		String ratingKey = id.substring(COLLECTION_PREFIX.length());
		JSONObject container = client.get(context, "/library/collections/" + ratingKey + "/children", null);

		List<JSONObject> items = new ArrayList<JSONObject>();
		collectInto(items, container.optJSONArray("Metadata"));
		collectInto(items, container.optJSONArray("Directory"));

		MusicDirectory dir = new MusicDirectory();
		dir.setId(id);
		dir.setName(name);
		for (JSONObject item : items) {
			dir.addChild(parseEntry(item));
		}
		return dir;
	}

	/**
	 * Drops one album out of a collection, the same call the Plex Dashboard makes through
	 * plexapi's Collection.removeItems. Only works on ordinary collections - Plex rejects
	 * edits to smart ones, which is why this stays best-effort at the caller.
	 */
	public void removeAlbumFromCollection(Context context, String collectionId, String albumId) throws Exception {
		String ratingKey = collectionId.startsWith(COLLECTION_PREFIX)
				? collectionId.substring(COLLECTION_PREFIX.length()) : collectionId;
		client.delete(context, "/library/collections/" + ratingKey + "/items/" + albumId, null);
	}

	@Override
	public SearchResult search(SearchCritera criteria, Context context, ProgressListener progressListener) throws Exception {
		JSONObject container = client.get(context, "/hubs/search",
				PlexClient.params("query", criteria.getQuery(), "limit", String.valueOf(criteria.getSongCount())));

		List<Artist> artists = new ArrayList<Artist>();
		List<MusicDirectory.Entry> albums = new ArrayList<MusicDirectory.Entry>();
		List<MusicDirectory.Entry> songs = new ArrayList<MusicDirectory.Entry>();

		JSONArray hubs = container.optJSONArray("Hub");
		for (int i = 0; hubs != null && i < hubs.length(); i++) {
			JSONObject hub = hubs.getJSONObject(i);
			JSONArray metadata = hub.optJSONArray("Metadata");
			if (metadata == null) {
				continue;
			}

			String type = hub.optString("type");
			for (int j = 0; j < metadata.length(); j++) {
				JSONObject item = metadata.getJSONObject(j);
				if ("artist".equals(type)) {
					artists.add(parseArtist(item));
				} else if ("album".equals(type)) {
					albums.add(parseEntry(item));
				} else if ("track".equals(type)) {
					songs.add(parseEntry(item));
				}
			}
		}

		return new SearchResult(artists, albums, songs);
	}

	@Override
	public MusicDirectory getAlbumList(String type, int size, int offset, boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		return getAlbumList(type, null, size, offset, refresh, context, progressListener);
	}

	@Override
	public MusicDirectory getAlbumList(String type, String extra, int size, int offset, boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		List<String> params = PlexClient.params(
				"type", String.valueOf(PlexClient.TYPE_ALBUM),
				"X-Plex-Container-Start", String.valueOf(offset),
				"X-Plex-Container-Size", String.valueOf(size));

		if ("newest".equals(type)) {
			addParam(params, "sort", "addedAt:desc");
		} else if ("recent".equals(type)) {
			addParam(params, "sort", "lastViewedAt:desc");
		} else if ("frequent".equals(type)) {
			addParam(params, "sort", "viewCount:desc");
		} else if ("highest".equals(type)) {
			addParam(params, "sort", "userRating:desc");
		} else if ("random".equals(type)) {
			addParam(params, "sort", "random");
		} else if ("starred".equals(type)) {
			addParam(params, "userRating>>", String.valueOf(STARRED_RATING - 1));
		} else if ("alphabeticalByArtist".equals(type)) {
			addParam(params, "sort", "artist.titleSort");
		} else if ("years".equals(type) && extra != null) {
			addParam(params, "year", extra);
		} else if ("genres".equals(type) && extra != null) {
			addParam(params, "genre", extra);
		} else {
			addParam(params, "sort", "titleSort");
		}

		return listSection(context, params);
	}

	@Override
	public MusicDirectory getSongList(String type, int size, int offset, Context context, ProgressListener progressListener) throws Exception {
		List<String> params = PlexClient.params(
				"type", String.valueOf(PlexClient.TYPE_TRACK),
				"X-Plex-Container-Start", String.valueOf(offset),
				"X-Plex-Container-Size", String.valueOf(size));

		if ("newest".equals(type)) {
			addParam(params, "sort", "addedAt:desc");
		} else if ("frequent".equals(type)) {
			addParam(params, "sort", "viewCount:desc");
		} else if ("recent".equals(type)) {
			addParam(params, "sort", "lastViewedAt:desc");
		} else if ("highest".equals(type)) {
			addParam(params, "sort", "userRating:desc");
		} else {
			addParam(params, "sort", "titleSort");
		}

		return listSection(context, params);
	}

	@Override
	public MusicDirectory getStarredList(Context context, ProgressListener progressListener) throws Exception {
		return listSection(context, PlexClient.params(
				"type", String.valueOf(PlexClient.TYPE_TRACK),
				"userRating>>", String.valueOf(STARRED_RATING - 1)));
	}

	@Override
	public MusicDirectory getRandomSongs(int size, String artistId, Context context, ProgressListener progressListener) throws Exception {
		List<String> params = PlexClient.params(
				"type", String.valueOf(PlexClient.TYPE_TRACK),
				"sort", "random",
				"X-Plex-Container-Size", String.valueOf(size));
		if (artistId != null) {
			addParam(params, "artist.id", artistId);
		}
		return listSection(context, params);
	}

	@Override
	public MusicDirectory getRandomSongs(int size, String folder, String genre, String startYear, String endYear, Context context, ProgressListener progressListener) throws Exception {
		List<String> params = PlexClient.params(
				"type", String.valueOf(PlexClient.TYPE_TRACK),
				"sort", "random",
				"X-Plex-Container-Size", String.valueOf(size));
		if (genre != null) {
			addParam(params, "genre", genre);
		}
		if (startYear != null && endYear != null) {
			addParam(params, "year>>", startYear);
			addParam(params, "year<<", endYear);
		}
		return listSection(context, params, folder);
	}

	@Override
	public List<Genre> getGenres(boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		JSONObject container = client.get(context, "/library/sections/" + client.getLibraryKey(context) + "/genre", null);
		JSONArray directories = container.optJSONArray("Directory");

		List<Genre> genres = new ArrayList<Genre>();
		for (int i = 0; directories != null && i < directories.length(); i++) {
			JSONObject dir = directories.getJSONObject(i);
			Genre genre = new Genre();
			genre.setName(dir.optString("title"));
			genre.setIndex(dir.optString("key"));
			genres.add(genre);
		}
		return genres;
	}

	@Override
	public MusicDirectory getSongsByGenre(String genre, int count, int offset, Context context, ProgressListener progressListener) throws Exception {
		return listSection(context, PlexClient.params(
				"type", String.valueOf(PlexClient.TYPE_TRACK),
				"genre", genre,
				"X-Plex-Container-Start", String.valueOf(offset),
				"X-Plex-Container-Size", String.valueOf(count)));
	}

	/**
	 * Tracks Plex's own analysis puts nearest this one in the way they actually sound,
	 * which is what it builds its own sonic radio out of.
	 *
	 * Only a library that has been through sonic analysis has anything to answer with. A
	 * server that has not run it, or an older one with no such endpoint at all, gives back
	 * nothing or refuses outright - so rather than leave the radio dead, that falls back on
	 * other tracks by the same artist. Related by a far blunter measure, but it plays.
	 */
	@Override
	public MusicDirectory getSonicallySimilarSongs(MusicDirectory.Entry seed, int size, Context context, ProgressListener progressListener) throws Exception {
		try {
			JSONArray metadata = getMetadata(client.get(context,
					"/library/metadata/" + seed.getId() + "/nearest",
					PlexClient.params(
							"type", String.valueOf(PlexClient.TYPE_TRACK),
							"limit", String.valueOf(size),
							"X-Plex-Container-Size", String.valueOf(size))));

			MusicDirectory dir = new MusicDirectory();
			for (int i = 0; i < metadata.length(); i++) {
				MusicDirectory.Entry entry = parseEntry(metadata.getJSONObject(i));
				// The seed itself comes back at the top of its own neighbourhood, being as
				// near to itself as anything can be.
				if (entry != null && !Util.equals(entry.getId(), seed.getId())) {
					dir.addChild(entry);
				}
			}

			if (dir.getChildrenSize() > 0) {
				return dir;
			}
			Log.i(TAG, "Plex knows of nothing sonically near " + seed.getTitle()
					+ "; falling back on its artist");
		} catch (Exception x) {
			Log.w(TAG, "Sonic neighbours are not available on this server, falling back on the artist", x);
		}

		String artistId = seed.getArtistId();
		if (artistId == null || artistId.isEmpty()) {
			return new MusicDirectory();
		}
		return getRandomSongs(size, artistId, context, progressListener);
	}

	@Override
	public MusicDirectory getTopTrackSongs(String artist, int size, Context context, ProgressListener progressListener) throws Exception {
		return listSection(context, PlexClient.params(
				"type", String.valueOf(PlexClient.TYPE_TRACK),
				"artist.title", artist,
				"sort", "viewCount:desc",
				"X-Plex-Container-Size", String.valueOf(size)));
	}

	@Override
	public ArtistInfo getArtistInfo(String id, boolean refresh, boolean allowNetwork, Context context, ProgressListener progressListener) throws Exception {
		JSONArray metadata = getMetadata(client.get(context, "/library/metadata/" + id, null));
		ArtistInfo info = new ArtistInfo();
		if (metadata.length() > 0) {
			JSONObject item = metadata.getJSONObject(0);
			info.setBiography(item.optString("summary", null));
			String art = item.optString("art", null);
			if (art != null && !art.isEmpty()) {
				info.setImageUrl(client.buildTokenUrl(context, art, null));
			}
		}
		info.setSimilarArtists(new ArrayList<Artist>());
		info.setMissingArtists(new ArrayList<String>());
		return info;
	}

	private MusicDirectory listSection(Context context, List<String> params) throws Exception {
		return listSection(context, params, null);
	}

	private MusicDirectory listSection(Context context, List<String> params, String section) throws Exception {
		String key = section != null ? section : client.getLibraryKey(context);
		JSONArray metadata = getMetadata(client.get(context, "/library/sections/" + key + "/all", params));

		MusicDirectory dir = new MusicDirectory();
		for (int i = 0; i < metadata.length(); i++) {
			dir.addChild(parseEntry(metadata.getJSONObject(i)));
		}
		return dir;
	}

	// ------------------------------------------------------------- playlists

	@Override
	public List<Playlist> getPlaylists(boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		JSONArray metadata = getMetadata(client.get(context, "/playlists", PlexClient.params("playlistType", "audio")));

		List<Playlist> playlists = new ArrayList<Playlist>();
		for (int i = 0; i < metadata.length(); i++) {
			JSONObject item = metadata.getJSONObject(i);
			Playlist playlist = new Playlist(item.optString("ratingKey"), item.optString("title"));
			playlist.setSongCount(String.valueOf(item.optInt("leafCount")));
			playlist.setDuration(item.optInt("duration") / 1000);

			// Plex builds a mosaic of the playlist's album art and exposes it here.
			String composite = item.optString("composite", null);
			if (composite != null && !composite.isEmpty()) {
				playlist.setCoverArt(composite);
			}
			playlists.add(playlist);
		}
		return playlists;
	}

	@Override
	public MusicDirectory getPlaylist(boolean refresh, String id, String name, Context context, ProgressListener progressListener) throws Exception {
		JSONArray metadata = getMetadata(client.get(context, "/playlists/" + id + "/items", null));

		MusicDirectory dir = new MusicDirectory();
		dir.setId(id);
		dir.setName(name);
		for (int i = 0; i < metadata.length(); i++) {
			dir.addChild(parseEntry(metadata.getJSONObject(i)));
		}
		return dir;
	}

	@Override
	public void createPlaylist(String id, String name, List<MusicDirectory.Entry> entries, Context context, ProgressListener progressListener) throws Exception {
		client.post(context, "/playlists", PlexClient.params(
				"type", "audio",
				"title", name,
				"smart", "0",
				"uri", buildItemUri(context, entries)));
	}

	@Override
	public void deletePlaylist(String id, Context context, ProgressListener progressListener) throws Exception {
		client.delete(context, "/playlists/" + id, null);
	}

	@Override
	public void addToPlaylist(String id, List<MusicDirectory.Entry> toAdd, Context context, ProgressListener progressListener) throws Exception {
		client.put(context, "/playlists/" + id + "/items", PlexClient.params("uri", buildItemUri(context, toAdd)));
	}

	@Override
	public void removeFromPlaylist(String id, List<Integer> toRemove, Context context, ProgressListener progressListener) throws Exception {
		// Plex deletes by playlistItemID rather than index, so resolve the
		// current contents first. Remove highest index first so that the
		// remaining positions stay valid.
		JSONArray metadata = getMetadata(client.get(context, "/playlists/" + id + "/items", null));

		List<Integer> sorted = new ArrayList<Integer>(toRemove);
		Collections.sort(sorted, Collections.reverseOrder());
		for (Integer index : sorted) {
			if (index == null || index < 0 || index >= metadata.length()) {
				continue;
			}
			String itemId = metadata.getJSONObject(index).optString("playlistItemID", null);
			if (itemId != null) {
				client.delete(context, "/playlists/" + id + "/items/" + itemId, null);
			}
		}
	}

	@Override
	public void overwritePlaylist(String id, String name, int toRemove, List<MusicDirectory.Entry> toAdd, Context context, ProgressListener progressListener) throws Exception {
		client.delete(context, "/playlists/" + id + "/items", null);
		addToPlaylist(id, toAdd, context, progressListener);
	}

	@Override
	public void updatePlaylist(String id, String name, String comment, boolean pub, Context context, ProgressListener progressListener) throws Exception {
		client.put(context, "/playlists/" + id, PlexClient.params("title", name, "summary", comment));
	}

	/** Plex adds items to playlists by server-scoped library URI. */
	private String buildItemUri(Context context, List<MusicDirectory.Entry> entries) throws Exception {
		StringBuilder keys = new StringBuilder();
		for (MusicDirectory.Entry entry : entries) {
			if (keys.length() > 0) {
				keys.append(',');
			}
			keys.append(entry.getId());
		}
		return "server://" + getMachineIdentifier(context) + "/" + LIBRARY_IDENTIFIER
				+ "/library/metadata/" + keys;
	}

	// --------------------------------------------------------- rating/status

	@Override
	public void setStarred(List<MusicDirectory.Entry> entries, List<MusicDirectory.Entry> artists, List<MusicDirectory.Entry> albums, boolean starred, ProgressListener progressListener, Context context) throws Exception {
		// Plex has no separate "favourite" flag for music, so a starred item is
		// modelled as a full 5 star rating.
		List<MusicDirectory.Entry> all = new ArrayList<MusicDirectory.Entry>();
		if (entries != null) {
			all.addAll(entries);
		}
		if (artists != null) {
			all.addAll(artists);
		}
		if (albums != null) {
			all.addAll(albums);
		}

		for (MusicDirectory.Entry entry : all) {
			rate(context, entry.getId(), starred ? STARRED_RATING : 0);
			entry.setStarred(starred);
		}
	}

	@Override
	public void setRating(MusicDirectory.Entry entry, int rating, Context context, ProgressListener progressListener) throws Exception {
		rate(context, entry.getId(), rating * RATING_SCALE);
		entry.setRating(rating);
	}

	private void rate(Context context, String id, int rating) throws Exception {
		client.command(context, "/:/rate", PlexClient.params(
				"key", id,
				"identifier", LIBRARY_IDENTIFIER,
				"rating", String.valueOf(rating)));
	}

	@Override
	public void updatePlaybackState(MusicDirectory.Entry song, String state, int positionMs, Context context) throws Exception {
		if(song == null || song.getId() == null) {
			return;
		}

		// Plex builds its "now playing" dashboard from timeline pings. They must
		// keep arriving while playing or the session is dropped.
		Integer duration = song.getDuration();
		long durationMs = (duration != null) ? duration * 1000L : 0L;
		long timeMs = Math.max(0, positionMs);

		// Plex answers 400 to the whole ping when the position is past the end of the
		// track, which drops the session rather than merely losing one update. Verified
		// against the server: time == duration is accepted and time == duration + 1 is
		// not, and a duration of zero turns the check off altogether.
		//
		// Running past the end is normal rather than exceptional. A local player reports
		// a little more than the tagged length at the end of a song, and a cast reports a
		// position carried forward from the last poll of the speaker, which overshoots
		// whenever a poll is late.
		if(durationMs > 0 && timeMs > durationMs) {
			timeMs = durationMs;
		}

		client.command(context, "/:/timeline", PlexClient.params(
				"ratingKey", song.getId(),
				"key", "/library/metadata/" + song.getId(),
				"identifier", LIBRARY_IDENTIFIER,
				"state", state,
				"time", String.valueOf(timeMs),
				"duration", String.valueOf(durationMs)));
	}

	@Override
	public void scrobble(String id, boolean submission, Context context, ProgressListener progressListener) throws Exception {
		// Plex only records a play once it is considered complete; there is no
		// "now playing" equivalent that maps onto a partial scrobble.
		if (!submission) {
			return;
		}
		client.command(context, "/:/scrobble", PlexClient.params("key", id, "identifier", LIBRARY_IDENTIFIER));
	}

	// ------------------------------------------------------------ media urls

	@Override
	public String getCoverArtUrl(Context context, MusicDirectory.Entry entry) throws Exception {
		String thumb = entry.getCoverArt();
		if (thumb == null) {
			return null;
		}
		return client.buildTokenUrl(context, thumb, null);
	}

	@Override
	public Bitmap getCoverArt(Context context, MusicDirectory.Entry entry, int size, ProgressListener progressListener, SilentBackgroundTask task) throws Exception {
		String thumb = entry.getCoverArt();
		if (thumb == null) {
			return null;
		}

		// Synchronize on the entry so we don't download twice for the same song.
		synchronized (entry) {
			// Always fetch at the size of the largest view there is, whatever size the
			// caller asked to draw. There is one cached file per album and every request
			// writes it, so asking the server for exactly the size being drawn meant an
			// album fetched for a grid thumbnail first left the now playing screen
			// stretching a 225px image over the whole width. Plex will not upscale past
			// the original, so this is a ceiling rather than a fixed cost.
			String cacheSize = String.valueOf(getArtCacheSize(context));
			String url = client.buildTokenUrl(context, "/photo/:/transcode", PlexClient.params(
					"width", cacheSize,
					"height", cacheSize,
					"minSize", "1",
					"url", thumb));
			return downloadBitmap(context, url, size, FileUtil.getAlbumArtFile(context, entry));
		}
	}

	/**
	 * How big to keep album art on disk: the narrow side of the screen, which is what the
	 * now playing screen fills. Matches ImageLoader's own idea of a large image.
	 */
	private int getArtCacheSize(Context context) {
		DisplayMetrics metrics = context.getResources().getDisplayMetrics();
		return Math.min(metrics.widthPixels, metrics.heightPixels);
	}

	@Override
	public Bitmap getBitmap(String url, int size, Context context, ProgressListener progressListener, SilentBackgroundTask task) throws Exception {
		return downloadBitmap(context, url, size, null);
	}

	@Override
	public String getMusicUrl(Context context, MusicDirectory.Entry song, int maxBitrate) throws Exception {
		return buildStreamUrl(context, song, maxBitrate);
	}

	@Override
	public HttpURLConnection getDownloadInputStream(Context context, MusicDirectory.Entry song, long offset, int maxBitrate, SilentBackgroundTask task) throws Exception {
		HttpURLConnection connection = client.openConnection(context, buildStreamUrl(context, song, maxBitrate));
		if (offset > 0) {
			connection.setRequestProperty("Range", "bytes=" + offset + "-");
		}
		connection.setReadTimeout(30 * 1000);
		return connection;
	}

	/**
	 * Streams the original file when no bitrate cap applies, otherwise asks
	 * Plex to transcode. The path stored on the entry is the Part key returned
	 * with the track metadata.
	 */
	private String buildStreamUrl(Context context, MusicDirectory.Entry song, int maxBitrate) throws Exception {
		// Lossless is never re-encoded, whatever bitrate cap is configured --
		// transcoding it would defeat the point of storing it losslessly.
		//
		// Otherwise transcode only when the bitrate genuinely has to come down.
		// DownloadFile.getActualBitrate() passes the song's *own* bitrate when
		// no limit is set -- that is its guard against upsampling, not a request
		// to transcode.
		Integer songBitrate = song.getBitRate();
		boolean needsTranscode = !isLossless(song)
				&& maxBitrate > 0 && songBitrate != null && songBitrate > maxBitrate;

		if (!needsTranscode && song.getPath() != null) {
			// Part key straight off the server: the original file, untouched.
			return client.buildTokenUrl(context, song.getPath(), null);
		}

		List<String> params = PlexClient.params(
				"path", "/library/metadata/" + song.getId(),
				"mediaIndex", "0",
				"partIndex", "0",
				"protocol", "http",
				"directPlay", "0",
				"directStream", "1",
				"X-Plex-Client-Identifier", PlexClient.getClientIdentifier(context));
		if (maxBitrate > 0) {
			addParam(params, "maxAudioBitrate", String.valueOf(maxBitrate));
		}
		return client.buildTokenUrl(context, "/music/:/transcode/universal/start.mp3", params);
	}

	/**
	 * Formats that must always be streamed untouched. Checked against both the
	 * container (Plex "container") and the codec (Plex "audioCodec"), since a
	 * lossless codec can sit inside a general purpose container.
	 */
	private static boolean isLossless(MusicDirectory.Entry song) {
		return isLosslessName(song.getSuffix()) || isLosslessName(song.getContentType());
	}

	private static boolean isLosslessName(String name) {
		if (name == null || name.isEmpty()) {
			return false;
		}

		String value = name.toLowerCase();
		// contentType arrives as "audio/flac"; keep just the subtype.
		int slash = value.indexOf('/');
		if (slash != -1) {
			value = value.substring(slash + 1);
		}

		return "flac".equals(value) || "alac".equals(value) || "wav".equals(value)
				|| "wave".equals(value) || "ape".equals(value) || "wv".equals(value)
				|| "aiff".equals(value) || "aif".equals(value) || "dsf".equals(value)
				|| "dff".equals(value) || "tta".equals(value) || "shn".equals(value)
				|| "pcm".equals(value) || "x-flac".equals(value);
	}

	private Bitmap downloadBitmap(Context context, String url, int size, File saveTo) throws Exception {
		HttpURLConnection connection = null;
		InputStream in = null;
		try {
			connection = client.openConnection(context, url);
			if (connection.getResponseCode() >= 400) {
				throw new java.io.IOException("Failed to fetch image, code " + connection.getResponseCode());
			}
			in = connection.getInputStream();

			java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int read;
			while ((read = in.read(chunk)) != -1) {
				buffer.write(chunk, 0, read);
			}
			byte[] bytes = buffer.toByteArray();

			if (saveTo != null) {
				OutputStream out = null;
				try {
					out = new FileOutputStream(saveTo);
					out.write(bytes);
				} catch (Exception e) {
					Log.w(TAG, "Failed to cache album art", e);
				} finally {
					Util.close(out);
				}
			}

			// Size 0 asks for the art to be cached and nothing more - DownloadFile does
			// this when caching a song for offline play.
			return size == 0 ? null : FileUtil.getSampledBitmap(bytes, size, true);
		} finally {
			Util.close(in);
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	// --------------------------------------------------------------- parsing

	/** Appends every object in {@code array} to {@code target}, ignoring nulls. */
	private static void collectInto(List<JSONObject> target, JSONArray array) {
		for (int i = 0; array != null && i < array.length(); i++) {
			JSONObject item = array.optJSONObject(i);
			if (item != null) {
				target.add(item);
			}
		}
	}

	private static JSONArray getMetadata(JSONObject container) {
		JSONArray metadata = container.optJSONArray("Metadata");
		return metadata != null ? metadata : new JSONArray();
	}

	private static void addParam(List<String> params, String name, String value) {
		params.add(name);
		params.add(value);
	}

	private Artist parseArtist(JSONObject item) {
		Artist artist = new Artist();
		artist.setId(item.optString("ratingKey"));
		artist.setName(item.optString("title"));

		String title = item.optString("titleSort", item.optString("title", ""));
		artist.setIndex(title.isEmpty() ? "#" : title.substring(0, 1).toUpperCase());

		// Comes with the listing, so the Library rows cost no extra request to illustrate.
		String thumb = item.optString("thumb", null);
		if (thumb != null && !thumb.isEmpty()) {
			artist.setCoverArt(thumb);
		}

		int rating = item.optInt("userRating", 0);
		if (rating > 0) {
			artist.setRating(rating / RATING_SCALE);
			artist.setStarred(rating >= STARRED_RATING);
		}
		return artist;
	}

	/** Converts a Plex artist/album/track metadata object into an Entry. */
	private MusicDirectory.Entry parseEntry(JSONObject item) {
		MusicDirectory.Entry entry = new MusicDirectory.Entry();
		String type = item.optString("type");

		entry.setId(item.optString("ratingKey"));
		entry.setTitle(item.optString("title"));
		entry.setDirectory(!"track".equals(type));

		// Albums carry "year"; tracks carry the album's year as "parentYear" and have
		// no "year" of their own, so a track shows nothing unless we fall back.
		if (item.has("year")) {
			entry.setYear(item.optInt("year"));
		} else if (item.has("parentYear")) {
			entry.setYear(item.optInt("parentYear"));
		}

		// Epoch seconds. lastViewedAt is simply absent until something is played,
		// which is what "never played" is derived from.
		if (item.has("addedAt")) {
			entry.setAdded(item.optLong("addedAt"));
		}
		if (item.has("lastViewedAt")) {
			entry.setLastPlayed(item.optLong("lastViewedAt"));
		}
		if (item.has("viewCount")) {
			entry.setPlayCount(item.optInt("viewCount"));
		}

		int rating = item.optInt("userRating", 0);
		if (rating > 0) {
			entry.setRating(rating / RATING_SCALE);
			entry.setStarred(rating >= STARRED_RATING);
		}

		// Plex reports durations in milliseconds.
		if (item.has("duration")) {
			entry.setDuration(item.optInt("duration") / 1000);
		}

		String thumb = item.optString("thumb", null);
		if (thumb == null || thumb.isEmpty()) {
			thumb = item.optString("parentThumb", null);
		}
		if (thumb != null && !thumb.isEmpty()) {
			entry.setCoverArt(thumb);
		}

		if ("album".equals(type)) {
			entry.setAlbum(item.optString("title"));
			entry.setArtist(item.optString("parentTitle"));
			entry.setAlbumArtist(item.optString("parentTitle"));
			entry.setParent(item.optString("parentRatingKey"));
			entry.setArtistId(item.optString("parentRatingKey"));
			entry.setAlbumId(item.optString("ratingKey"));
		} else if ("track".equals(type)) {
			entry.setAlbum(item.optString("parentTitle"));

			// grandparentTitle is the album's artist. On compilations the track's
			// own performer lives in originalTitle, and that is the one that
			// should be scrobbled as the artist.
			String albumArtist = item.optString("grandparentTitle", null);
			String trackArtist = item.optString("originalTitle", null);
			entry.setArtist((trackArtist != null && !trackArtist.isEmpty()) ? trackArtist : albumArtist);
			entry.setAlbumArtist(albumArtist);
			entry.setParent(item.optString("parentRatingKey"));
			entry.setAlbumId(item.optString("parentRatingKey"));
			entry.setArtistId(item.optString("grandparentRatingKey"));
			entry.setGrandParent(item.optString("grandparentRatingKey"));

			if (item.has("index")) {
				entry.setTrack(item.optInt("index"));
			}
			if (item.has("parentIndex")) {
				entry.setDiscNumber(item.optInt("parentIndex"));
			}

			parseMedia(item, entry);
		} else {
			entry.setArtist(item.optString("title"));
			entry.setArtistId(item.optString("ratingKey"));
		}

		return entry;
	}

	/** Pulls codec, bitrate, size and the streamable part path off a track. */
	private void parseMedia(JSONObject item, MusicDirectory.Entry entry) {
		JSONArray media = item.optJSONArray("Media");
		if (media == null || media.length() == 0) {
			return;
		}

		JSONObject first = media.optJSONObject(0);
		if (first == null) {
			return;
		}

		if (first.has("bitrate")) {
			entry.setBitRate(first.optInt("bitrate"));
		}
		String container = first.optString("container", null);
		if (container != null && !container.isEmpty()) {
			entry.setSuffix(container);
		}
		String codec = first.optString("audioCodec", null);
		if (codec != null && !codec.isEmpty()) {
			entry.setContentType("audio/" + codec);
		}

		// Whether the analysis heard any singing, which sits on the media rather than on
		// the stream with the rest of what it measured.
		if (first.has("hasVoiceActivity")) {
			entry.setVoiceActivity(first.optBoolean("hasVoiceActivity"));
		}

		JSONArray parts = first.optJSONArray("Part");
		if (parts == null || parts.length() == 0) {
			return;
		}
		JSONObject part = parts.optJSONObject(0);
		if (part == null) {
			return;
		}

		if (part.has("size")) {
			entry.setSize(part.optLong("size"));
		}
		// Used to stream the original file without transcoding. Note this is a streaming
		// key and not a path on the server, whatever the field it is kept in suggests -
		// see FileUtil, which has to tell the two apart to lay the cache out.
		String key = part.optString("key", null);
		if (key != null && !key.isEmpty()) {
			entry.setPath(key);
		}

		parseAudioStream(part, entry);
	}

	/**
	 * Bit depth and sample rate live on the audio stream rather than the media, and a
	 * part can hold several streams - lyrics show up here too - so the audio one has
	 * to be picked out by type rather than by position.
	 */
	/** A decibel figure as Plex writes it, or null when it did not report one. */
	private static Float parseGain(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			return Float.valueOf(value);
		} catch (NumberFormatException x) {
			return null;
		}
	}

	private void parseAudioStream(JSONObject part, MusicDirectory.Entry entry) {
		JSONArray streams = part.optJSONArray("Stream");
		if (streams == null) {
			return;
		}

		for (int i = 0; i < streams.length(); i++) {
			JSONObject stream = streams.optJSONObject(i);
			if (stream == null || stream.optInt("streamType", -1) != STREAM_TYPE_AUDIO) {
				continue;
			}

			if (stream.has("bitDepth")) {
				entry.setBitDepth(stream.optInt("bitDepth"));
			}
			if (stream.has("samplingRate")) {
				entry.setSamplingRate(stream.optInt("samplingRate"));
			}

			// Some files have no bitrate on the media object - Plex does not always
			// fill it in - but do carry one on the stream. Only used as a fallback so
			// the media value still wins where both are present.
			Integer existing = entry.getBitRate();
			if ((existing == null || existing <= 0) && stream.has("bitrate")) {
				entry.setBitRate(stream.optInt("bitrate"));
			}

			// Plex measures loudness as it scans and reports both figures here, which is
			// the only way a track that is being streamed has any replay gain at all -
			// there is no file on this device to read tags out of until it is downloaded.
			entry.setReplayGainTrack(parseGain(stream.optString("gain", null)));
			entry.setReplayGainAlbum(parseGain(stream.optString("albumGain", null)));

			// The same analysis, kept for telling recordings apart rather than for levelling
			// them: how loud it is overall, and how far it travels between its quietest and
			// its loudest.
			entry.setLoudness(parseGain(stream.optString("loudness", null)));
			entry.setLoudnessRange(parseGain(stream.optString("lra", null)));
			return;
		}
	}

	// ------------------------------------------------- unsupported by Plex

	private static UnsupportedOperationException unsupported(String feature) {
		return new UnsupportedOperationException(feature + " is not supported by Plex servers");
	}

	@Override
	public Lyrics getLyrics(String artist, String title, Context context, ProgressListener progressListener) throws Exception {
		// Plex keys lyrics to a track rather than an artist/title pair, so this
		// signature cannot serve them. See getSyncedLyrics.
		throw unsupported("Lyrics");
	}

	/**
	 * How many ids go into one request. Plex takes a comma separated list on the metadata
	 * endpoint; this is kept well short of anything that would make the URL awkward.
	 */
	private static final int DETAIL_BATCH = 40;

	/**
	 * Fills replay gain and the format details onto tracks that came from a listing.
	 *
	 * A listing carries no Media at all, so none of this is in hand until it is asked for -
	 * which is why replay gain used to exist only for downloaded tracks, read out of the
	 * file once there was one. Plex measures every track as it scans, and the figures come
	 * back with the metadata.
	 *
	 * Anything the server does not answer for is left exactly as it was, so a track it has
	 * not analysed falls back on whatever its file says, the same as before.
	 */
	@Override
	public void fillSongDetails(List<MusicDirectory.Entry> songs, Context context) throws Exception {
		if (songs == null || songs.isEmpty()) {
			return;
		}

		Map<String, MusicDirectory.Entry> wanted = new LinkedHashMap<String, MusicDirectory.Entry>();
		for (MusicDirectory.Entry song : songs) {
			if (song != null && song.getId() != null && !song.isDirectory()) {
				wanted.put(song.getId(), song);
			}
		}
		if (wanted.isEmpty()) {
			return;
		}

		List<String> ids = new ArrayList<String>(wanted.keySet());
		for (int from = 0; from < ids.size(); from += DETAIL_BATCH) {
			List<String> batch = ids.subList(from, Math.min(ids.size(), from + DETAIL_BATCH));
			StringBuilder joined = new StringBuilder();
			for (String id : batch) {
				if (joined.length() > 0) {
					joined.append(',');
				}
				joined.append(id);
			}

			JSONArray metadata = getMetadata(client.get(context, "/library/metadata/" + joined, null));
			for (int i = 0; i < metadata.length(); i++) {
				JSONObject item = metadata.getJSONObject(i);
				MusicDirectory.Entry target = wanted.get(item.optString("ratingKey", null));
				if (target == null) {
					continue;
				}

				MusicDirectory.Entry detailed = parseEntry(item);
				if (detailed == null) {
					continue;
				}
				if (detailed.getReplayGainTrack() != null) {
					target.setReplayGainTrack(detailed.getReplayGainTrack());
				}
				if (detailed.getReplayGainAlbum() != null) {
					target.setReplayGainAlbum(detailed.getReplayGainAlbum());
				}
				if (detailed.getLoudness() != null) {
					target.setLoudness(detailed.getLoudness());
				}
				if (detailed.getLoudnessRange() != null) {
					target.setLoudnessRange(detailed.getLoudnessRange());
				}
				if (detailed.getVoiceActivity() != null) {
					target.setVoiceActivity(detailed.getVoiceActivity());
				}
				if (detailed.getBitDepth() != null && detailed.getBitDepth() > 0) {
					target.setBitDepth(detailed.getBitDepth());
				}
				if (detailed.getSamplingRate() != null && detailed.getSamplingRate() > 0) {
					target.setSamplingRate(detailed.getSamplingRate());
				}
			}
		}
	}

	@Override
	public MusicDirectory.Entry getSongDetails(MusicDirectory.Entry song, Context context) throws Exception {
		if (song == null || song.getId() == null || song.isDirectory()) {
			return null;
		}

		JSONArray metadata = getMetadata(client.get(context, "/library/metadata/" + song.getId(), null));
		if (metadata.length() == 0) {
			return null;
		}

		return parseEntry(metadata.getJSONObject(0));
	}

	@Override
	public SyncedLyrics getSyncedLyrics(MusicDirectory.Entry song, Context context) throws Exception {
		if (song == null || song.getId() == null || song.isDirectory()) {
			return null;
		}

		JSONArray metadata = getMetadata(client.get(context, "/library/metadata/" + song.getId(), null));
		if (metadata.length() == 0) {
			return null;
		}

		String key = findLyricStreamKey(metadata.getJSONObject(0));
		if (key == null) {
			return null;
		}

		// A file whose lyric fetcher wrote an error message instead of lyrics parses
		// to nothing, which is the same as having none.
		return SyncedLyrics.parse(client.getText(context, key));
	}

	/** The key of the track's lyric stream, or null when it has none. */
	private String findLyricStreamKey(JSONObject item) {
		JSONArray media = item.optJSONArray("Media");
		for (int i = 0; media != null && i < media.length(); i++) {
			JSONArray parts = media.optJSONObject(i) == null ? null : media.optJSONObject(i).optJSONArray("Part");
			for (int j = 0; parts != null && j < parts.length(); j++) {
				JSONObject part = parts.optJSONObject(j);
				JSONArray streams = part == null ? null : part.optJSONArray("Stream");

				for (int k = 0; streams != null && k < streams.length(); k++) {
					JSONObject stream = streams.optJSONObject(k);
					if (stream == null || stream.optInt("streamType", -1) != STREAM_TYPE_LYRICS) {
						continue;
					}

					String key = stream.optString("key", null);
					if (key != null && !key.isEmpty()) {
						return key;
					}
				}
			}
		}
		return null;
	}

	@Override
	public String getVideoUrl(int maxBitrate, Context context, String id) {
		throw unsupported("Video");
	}

	@Override
	public String getVideoStreamUrl(String format, int bitrate, Context context, String id) throws Exception {
		throw unsupported("Video");
	}

	@Override
	public String getHlsUrl(String id, int bitRate, Context context) throws Exception {
		throw unsupported("Video");
	}

	@Override
	public MusicDirectory getVideos(boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Video");
	}

	@Override
	public RemoteStatus updateJukeboxPlaylist(List<String> ids, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Jukebox");
	}

	@Override
	public RemoteStatus skipJukebox(int index, int offsetSeconds, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Jukebox");
	}

	@Override
	public RemoteStatus stopJukebox(Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Jukebox");
	}

	@Override
	public RemoteStatus startJukebox(Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Jukebox");
	}

	@Override
	public RemoteStatus getJukeboxStatus(Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Jukebox");
	}

	@Override
	public RemoteStatus setJukeboxGain(float gain, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Jukebox");
	}

	@Override
	public List<Share> getShares(Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Sharing");
	}

	@Override
	public List<Share> createShare(List<String> ids, String description, Long expires, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Sharing");
	}

	@Override
	public void deleteShare(String id, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Sharing");
	}

	@Override
	public void updateShare(String id, String description, Long expires, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Sharing");
	}

	@Override
	public List<ChatMessage> getChatMessages(Long since, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Chat");
	}

	@Override
	public void addChatMessage(String message, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Chat");
	}

	@Override
	public List<PodcastChannel> getPodcastChannels(boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Podcasts");
	}

	@Override
	public MusicDirectory getPodcastEpisodes(boolean refresh, String id, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Podcasts");
	}

	@Override
	public MusicDirectory getNewestPodcastEpisodes(boolean refresh, Context context, ProgressListener progressListener, int count) throws Exception {
		throw unsupported("Podcasts");
	}

	@Override
	public void refreshPodcasts(Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Podcasts");
	}

	@Override
	public void createPodcastChannel(String url, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Podcasts");
	}

	@Override
	public void deletePodcastChannel(String id, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Podcasts");
	}

	@Override
	public void downloadPodcastEpisode(String id, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Podcasts");
	}

	@Override
	public void deletePodcastEpisode(String id, String parent, ProgressListener progressListener, Context context) throws Exception {
		throw unsupported("Podcasts");
	}

	@Override
	public MusicDirectory getBookmarks(boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Bookmarks");
	}

	@Override
	public void createBookmark(MusicDirectory.Entry entry, int position, String comment, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Bookmarks");
	}

	@Override
	public void deleteBookmark(MusicDirectory.Entry entry, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Bookmarks");
	}

	@Override
	public User getUser(boolean refresh, String username, Context context, ProgressListener progressListener) throws Exception {
		// Called on startup to resolve role flags. Plex has no equivalent, and
		// UserUtil treats a null user as "no special roles", which is what we
		// want -- so degrade rather than throw on this background path.
		return null;
	}

	@Override
	public List<User> getUsers(boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("User administration");
	}

	@Override
	public void createUser(User user, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("User administration");
	}

	@Override
	public void updateUser(User user, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("User administration");
	}

	@Override
	public void deleteUser(String username, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("User administration");
	}

	@Override
	public void changeEmail(String username, String email, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("User administration");
	}

	@Override
	public void changePassword(String username, String password, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("User administration");
	}

	@Override
	public Bitmap getAvatar(String username, int size, Context context, ProgressListener progressListener, SilentBackgroundTask task) throws Exception {
		throw unsupported("Avatars");
	}

	@Override
	public List<InternetRadioStation> getInternetRadioStations(boolean refresh, Context context, ProgressListener progressListener) throws Exception {
		throw unsupported("Internet radio");
	}

	@Override
	public void savePlayQueue(List<MusicDirectory.Entry> songs, MusicDirectory.Entry currentPlaying, int position, Context context, ProgressListener progressListener) throws Exception {
		// Plex play queues are transient session objects rather than a durable
		// "resume where you left off" store. This fires on every queue change,
		// so no-op instead of logging an error each time.
	}

	@Override
	public PlayerQueue getPlayQueue(Context context, ProgressListener progressListener) throws Exception {
		// Callers treat null as "nothing saved remotely".
		return null;
	}

	@Override
	public int processOfflineSyncs(Context context, ProgressListener progressListener) throws Exception {
		// Offline scrobbles and star changes are replayed by the caller against
		// whichever service is active; Plex has nothing extra to flush.
		return 0;
	}

	/**
	 * Two requests per batch, because no single Plex endpoint carries all three fields:
	 *
	 * - {@code /library/metadata/<id>,<id>,...} accepts a comma separated list and answers
	 *   with one row per album carrying {@code leafCount} and the Style tags. Neither
	 *   appears on a collection's children listing.
	 * - {@code /library/sections/<n>/all?type=10&album.id=<id>,<id>,...} returns the tracks
	 *   of all those albums at once, which is the only way to total an album's running
	 *   time -- Plex reports no duration on albums anywhere.
	 *
	 * Fifty albums a batch keeps the track response to roughly a megabyte while still
	 * covering an ordinary collection in one or two rounds.
	 */
	@Override
	public Map<String, AlbumDetails> getAlbumDetails(List<String> albumIds, Context context, ProgressListener progressListener) throws Exception {
		Map<String, AlbumDetails> result = new HashMap<String, AlbumDetails>();
		if (albumIds == null || albumIds.isEmpty()) {
			return result;
		}

		String section = client.getLibraryKey(context);
		for (int start = 0; start < albumIds.size(); start += DETAIL_BATCH_SIZE) {
			int end = Math.min(start + DETAIL_BATCH_SIZE, albumIds.size());
			String joined = join(albumIds.subList(start, end));

			if (progressListener != null) {
				progressListener.updateProgress(context.getResources().getString(
						R.string.menu_filter_details_progress, end, albumIds.size()));
			}

			readAlbumTags(context, joined, result);
			readAlbumDurations(context, section, joined, result);
		}
		return result;
	}

	/**
	 * When the album itself was last played, off its own metadata row.
	 *
	 * This is the lastViewedAt that a collection's children listing carries for every
	 * album on it - what the browse filter rests and sorts on - asked for one album at a
	 * time, because an album screen opened by id never saw that listing. Read through
	 * {@link #parseEntry} so it is the same field read the same way in both places.
	 *
	 * Deliberately not part of {@link AlbumDetails}: those are cached for weeks on the
	 * grounds that nothing in them moves, and this moves every time the album is played.
	 *
	 * Returns null where the album has never been played, which is how Plex says it - the
	 * attribute is simply absent - and null for an album the server does not know.
	 */
	public Long getAlbumLastPlayed(Context context, String albumId) throws Exception {
		if (albumId == null || albumId.isEmpty()) {
			return null;
		}

		JSONArray metadata = getMetadata(client.get(context, "/library/metadata/" + albumId, null));
		if (metadata.length() == 0) {
			return null;
		}
		return parseEntry(metadata.getJSONObject(0)).getLastPlayed();
	}

	/**
	 * Well-rated albums that have not been played for a long time.
	 *
	 * Drawn from the rating collections rather than from a library-wide query, because
	 * Plex offers no server-side filter on last-played for albums and no sort either -
	 * both were tried. Fetching those collections whole would be several megabytes, so a
	 * random window is taken from each and filtered here. The window moves every time,
	 * which is what makes the row worth looking at twice.
	 */
	@Override
	public MusicDirectory getRecommendedAlbums(int count, Context context, ProgressListener progressListener) throws Exception {
		JSONObject container = client.get(context,
				"/library/sections/" + client.getLibraryKey(context) + "/collections", null);

		List<JSONObject> collections = new ArrayList<JSONObject>();
		collectInto(collections, container.optJSONArray("Metadata"));
		collectInto(collections, container.optJSONArray("Directory"));

		long cutoff = (System.currentTimeMillis() / 1000L) - RECOMMENDATION_MIN_AGE;
		List<MusicDirectory.Entry> candidates = new ArrayList<MusicDirectory.Entry>();

		for (String title : RECOMMENDED_COLLECTIONS) {
			JSONObject collection = findCollection(collections, title);
			if (collection == null) {
				Log.w(TAG, "No collection named \"" + title + "\" to recommend from.");
				continue;
			}

			collectStaleAlbums(context, collection, cutoff, candidates, RECOMMENDATION_WINDOW);
		}

		if (candidates.isEmpty()) {
			return null;
		}

		Collections.shuffle(candidates);
		MusicDirectory dir = new MusicDirectory();
		for (int i = 0; i < Math.min(count, candidates.size()); i++) {
			dir.addChild(candidates.get(i));
		}
		return dir;
	}

	/**
	 * Short, well-rated albums left alone for a season - all of them, for the caller to
	 * draw a row from.
	 *
	 * Narrowed in three passes, cheapest first, because each one is more expensive per
	 * album than the last: the collection listing carries the play dates, the star
	 * collections say which albums are rated highly enough, and only the tracks carry
	 * duration. By the time durations are needed there are a few dozen albums left, not
	 * a few hundred.
	 *
	 * Rating used to be read from each album's own detail, which cost a request per fifty
	 * albums that survived pass one. Reading the star collections instead costs one
	 * request per tier however many survived, and the track count then comes free out of
	 * pass three, from the number of tracks it was already fetching.
	 */
	@Override
	public MusicDirectory getShortAlbums(Context context, ProgressListener progressListener) throws Exception {
		JSONObject container = client.get(context,
				"/library/sections/" + client.getLibraryKey(context) + "/collections", null);

		List<JSONObject> collections = new ArrayList<JSONObject>();
		collectInto(collections, container.optJSONArray("Metadata"));
		collectInto(collections, container.optJSONArray("Directory"));

		JSONObject collection = findCollection(collections, SHORT_ALBUM_COLLECTION);
		if (collection == null) {
			Log.w(TAG, "No \"" + SHORT_ALBUM_COLLECTION + "\" collection; skipping short albums.");
			return null;
		}

		// Pass one: everything in the collection, keeping what has not been played lately.
		// Insertion ordered so the pool comes back in the collection's own order rather
		// than a hash order that would change under us between runs.
		Map<String, MusicDirectory.Entry> stale = new LinkedHashMap<String, MusicDirectory.Entry>();
		List<MusicDirectory.Entry> candidates = new ArrayList<MusicDirectory.Entry>();
		collectStaleAlbums(context, collection, (System.currentTimeMillis() / 1000L) - SHORT_ALBUM_MIN_AGE,
				candidates, 0);
		for (MusicDirectory.Entry album : candidates) {
			stale.put(album.getId(), album);
		}
		if (stale.isEmpty()) {
			return null;
		}

		// Pass two: rated highly enough, by membership of the star collections.
		Set<String> rated = new HashSet<String>();
		for (String title : HIGHLY_RATED_COLLECTIONS) {
			JSONObject starred = findCollection(collections, title);
			if (starred == null) {
				Log.w(TAG, "No collection named \"" + title + "\" to rate against.");
				continue;
			}

			readCollectionAlbumIds(context, starred, rated);
		}

		List<String> shortlist = new ArrayList<String>();
		for (String id : stale.keySet()) {
			if (rated.contains(id)) {
				shortlist.add(id);
			}
		}
		if (shortlist.isEmpty()) {
			return null;
		}

		// Pass three: the only one that needs every track of every album, and the one that
		// answers both remaining questions - how long the album runs, and how many tracks
		// came back for it.
		//
		// These stay local on purpose. They carry no style tags, no label and no release
		// date, because nothing here asks the endpoint that has them, so feeding them to
		// the details cache would leave a filter later reading them back as an album with
		// no genres rather than as an album nobody has read the genres of.
		Map<String, AlbumDetails> details = new HashMap<String, AlbumDetails>();
		String section = client.getLibraryKey(context);
		for (int start = 0; start < shortlist.size(); start += DETAIL_BATCH_SIZE) {
			int end = Math.min(start + DETAIL_BATCH_SIZE, shortlist.size());
			readAlbumDurations(context, section, join(shortlist.subList(start, end)), details);
		}

		List<MusicDirectory.Entry> result = new ArrayList<MusicDirectory.Entry>();
		for (String id : shortlist) {
			AlbumDetails album = details.get(id);
			// A batch that filled its page leaves the track count at zero rather than a
			// short one, so an album from it is dropped here instead of being let through
			// as the single this test exists to keep out.
			if (album != null && album.getDurationMs() > 0
					&& album.getTrackCount() >= SHORT_ALBUM_MIN_TRACKS
					&& album.getDurationMinutes() < SHORT_ALBUM_MAX_MINUTES) {
				result.add(stale.get(id));
			}
		}
		if (result.isEmpty()) {
			return null;
		}

		MusicDirectory dir = new MusicDirectory();
		dir.addChildren(result);
		return dir;
	}

	/**
	 * The ids of every album in a collection, and nothing else about them.
	 *
	 * Membership is the only question being asked, so the response is stripped down to
	 * what carries the ratingKey - the tag elements and the summary are most of the weight
	 * of an album row and none of them are read here.
	 */
	private void readCollectionAlbumIds(Context context, JSONObject collection, Set<String> out) throws Exception {
		String ratingKey = collection.optString("ratingKey", null);
		if (ratingKey == null || ratingKey.isEmpty()) {
			return;
		}

		// Asking for the collection's own size, or for a page big enough to hold any of
		// them when it did not say. Not a default of one: a short read here does not fail,
		// it quietly drops albums that should have been rated highly enough.
		int childCount = collection.optInt("childCount", 0);
		JSONObject container = client.get(context, "/library/collections/" + ratingKey + "/children",
				PlexClient.params("X-Plex-Container-Start", "0",
						"X-Plex-Container-Size", String.valueOf(childCount > 0 ? childCount : COLLECTION_PAGE_SIZE),
						"excludeElements", ID_ONLY_EXCLUDES,
						"excludeFields", "summary"));

		List<JSONObject> items = new ArrayList<JSONObject>();
		collectInto(items, container.optJSONArray("Metadata"));
		collectInto(items, container.optJSONArray("Directory"));

		for (JSONObject item : items) {
			String id = item.optString("ratingKey", null);
			if (id != null && !id.isEmpty()) {
				out.add(id);
			}
		}
	}

	/** Reads one random window of a collection, keeping albums left alone since the cutoff. */
	private void collectStaleAlbums(Context context, JSONObject collection, long cutoff,
			List<MusicDirectory.Entry> out, int window) throws Exception {
		String ratingKey = collection.optString("ratingKey", null);
		if (ratingKey == null || ratingKey.isEmpty()) {
			return;
		}

		// A window of zero means read the lot; a collection small enough to filter whole
		// does not want a random slice of itself.
		int childCount = collection.optInt("childCount", 0);
		int size = (window > 0) ? window : Math.max(childCount, 1);
		int start = (window > 0 && childCount > window)
				? new Random().nextInt(childCount - window + 1) : 0;

		JSONObject container = client.get(context, "/library/collections/" + ratingKey + "/children",
				PlexClient.params("X-Plex-Container-Start", String.valueOf(start),
						"X-Plex-Container-Size", String.valueOf(size)));

		List<JSONObject> items = new ArrayList<JSONObject>();
		collectInto(items, container.optJSONArray("Metadata"));
		collectInto(items, container.optJSONArray("Directory"));

		for (JSONObject item : items) {
			MusicDirectory.Entry album = parseEntry(item);
			Long lastPlayed = album.getLastPlayed();
			// Never played counts as stale: it has been waiting longest of all.
			if (lastPlayed == null || lastPlayed < cutoff) {
				out.add(album);
			}
		}
	}

	private JSONObject findCollection(List<JSONObject> collections, String title) {
		for (JSONObject collection : collections) {
			if (title.equalsIgnoreCase(collection.optString("title", ""))) {
				return collection;
			}
		}
		return null;
	}

	/**
	 * Track count, style tags, label and original release date, one row per album.
	 *
	 * Plex calls the label "studio" here, the same attribute it uses for a film's studio.
	 * originallyAvailableAt is the first release rather than this pressing's year, which
	 * is why the header prefers it over the year carried on the tracks.
	 */
	private void readAlbumTags(Context context, String joinedIds, Map<String, AlbumDetails> out) throws Exception {
		JSONArray metadata = getMetadata(client.get(context, "/library/metadata/" + joinedIds, null));
		for (int i = 0; i < metadata.length(); i++) {
			JSONObject item = metadata.getJSONObject(i);
			String id = item.optString("ratingKey", null);
			if (id == null || id.isEmpty()) {
				continue;
			}

			AlbumDetails details = detailsFor(out, id);
			details.setTrackCount(item.optInt("leafCount", 0));
			details.setStyles(readTags(item, "Style"));
			details.setRecordLabel(item.optString("studio", null));
			details.setOriginalReleaseDate(item.optString("originallyAvailableAt", null));
		}
	}

	/** Totals the tracks of every album in the batch by their parent. */
	private void readAlbumDurations(Context context, String section, String joinedIds, Map<String, AlbumDetails> out) throws Exception {
		JSONArray metadata = getMetadata(client.get(context, "/library/sections/" + section + "/all",
				PlexClient.params("type", "10", "album.id", joinedIds,
						"X-Plex-Container-Size", String.valueOf(TRACK_PAGE_SIZE),
						// Only the duration and the parent id are wanted; dropping the media
						// blocks takes about a quarter off the response.
						"excludeElements", "Media")));

		// A response that filled the page has left tracks behind, which would make every
		// tally below an undercount - and the short albums row now decides on those counts.
		// Fifty albums would have to average a hundred tracks to get here.
		boolean complete = metadata.length() < TRACK_PAGE_SIZE;
		if (!complete) {
			Log.w(TAG, "Track page filled at " + TRACK_PAGE_SIZE + "; leaving track counts unset for this batch.");
		}

		Map<String, Integer> tally = new HashMap<String, Integer>();
		for (int i = 0; i < metadata.length(); i++) {
			JSONObject track = metadata.getJSONObject(i);
			String albumId = track.optString("parentRatingKey", null);
			if (albumId == null || albumId.isEmpty()) {
				continue;
			}

			AlbumDetails details = detailsFor(out, albumId);
			details.setDurationMs(details.getDurationMs() + track.optLong("duration", 0));

			Integer counted = tally.get(albumId);
			counted = (counted == null) ? 1 : counted + 1;
			tally.put(albumId, counted);
		}

		// Only where the album row carried no leafCount, so a batch that answers with
		// fewer tracks than the album really has cannot quietly overwrite a good count.
		if (complete) {
			for (Map.Entry<String, Integer> counted : tally.entrySet()) {
				AlbumDetails details = out.get(counted.getKey());
				if (details != null && details.getTrackCount() == 0) {
					details.setTrackCount(counted.getValue());
				}
			}
		}
	}

	private AlbumDetails detailsFor(Map<String, AlbumDetails> out, String id) {
		AlbumDetails details = out.get(id);
		if (details == null) {
			details = new AlbumDetails();
			out.put(id, details);
		}
		return details;
	}

	private List<String> readTags(JSONObject item, String element) {
		List<String> tags = new ArrayList<String>();
		JSONArray array = item.optJSONArray(element);
		for (int i = 0; array != null && i < array.length(); i++) {
			JSONObject tag = array.optJSONObject(i);
			String value = (tag != null) ? tag.optString("tag", null) : null;
			if (value != null && !value.isEmpty()) {
				tags.add(value);
			}
		}
		return tags;
	}

	private String join(List<String> values) {
		StringBuilder builder = new StringBuilder();
		for (String value : values) {
			if (builder.length() > 0) {
				builder.append(',');
			}
			builder.append(value);
		}
		return builder.toString();
	}
}
