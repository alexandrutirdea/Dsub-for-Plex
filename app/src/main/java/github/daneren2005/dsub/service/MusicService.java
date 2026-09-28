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
package github.daneren2005.dsub.service;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;


import android.content.Context;
import android.graphics.Bitmap;

import github.daneren2005.dsub.domain.AlbumDetails;
import github.daneren2005.dsub.domain.ArtistInfo;
import github.daneren2005.dsub.domain.ChatMessage;
import github.daneren2005.dsub.domain.Genre;
import github.daneren2005.dsub.domain.Indexes;
import github.daneren2005.dsub.domain.InternetRadioStation;
import github.daneren2005.dsub.domain.PlayerQueue;
import github.daneren2005.dsub.domain.RemoteStatus;
import github.daneren2005.dsub.domain.Lyrics;
import github.daneren2005.dsub.domain.SyncedLyrics;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.MusicFolder;
import github.daneren2005.dsub.domain.Playlist;
import github.daneren2005.dsub.domain.PodcastChannel;
import github.daneren2005.dsub.domain.SearchCritera;
import github.daneren2005.dsub.domain.SearchResult;
import github.daneren2005.dsub.domain.Share;
import github.daneren2005.dsub.domain.User;
import github.daneren2005.dsub.domain.Version;
import github.daneren2005.dsub.util.SilentBackgroundTask;
import github.daneren2005.dsub.util.ProgressListener;

/**
 * @author Sindre Mehus
 */
public interface MusicService {

    void ping(Context context, ProgressListener progressListener) throws Exception;

    boolean isLicenseValid(Context context, ProgressListener progressListener) throws Exception;

    List<MusicFolder> getMusicFolders(boolean refresh, Context context, ProgressListener progressListener) throws Exception;

	void startRescan(Context context, ProgressListener listener) throws Exception;

    Indexes getIndexes(String musicFolderId, boolean refresh, Context context, ProgressListener progressListener) throws Exception;

    MusicDirectory getMusicDirectory(String id, String name, boolean refresh, Context context, ProgressListener progressListener) throws Exception;

	MusicDirectory getArtist(String id, String name, boolean refresh, Context context, ProgressListener progressListener) throws Exception;

	MusicDirectory getAlbum(String id, String name, boolean refresh, Context context, ProgressListener progressListener) throws Exception;

    SearchResult search(SearchCritera criteria, Context context, ProgressListener progressListener) throws Exception;

    MusicDirectory getStarredList(Context context, ProgressListener progressListener) throws Exception;

    MusicDirectory getPlaylist(boolean refresh, String id, String name, Context context, ProgressListener progressListener) throws Exception;

    List<Playlist> getPlaylists(boolean refresh, Context context, ProgressListener progressListener) throws Exception;

    void createPlaylist(String id, String name, List<MusicDirectory.Entry> entries, Context context, ProgressListener progressListener) throws Exception;
	
	void deletePlaylist(String id, Context context, ProgressListener progressListener) throws Exception;
	
	void addToPlaylist(String id, List<MusicDirectory.Entry> toAdd, Context context, ProgressListener progressListener) throws Exception;
	
	void removeFromPlaylist(String id, List<Integer> toRemove, Context context, ProgressListener progressListener) throws Exception;
	
	void overwritePlaylist(String id, String name, int toRemove, List<MusicDirectory.Entry> toAdd, Context context, ProgressListener progressListener) throws Exception;
	
	void updatePlaylist(String id, String name, String comment, boolean pub, Context context, ProgressListener progressListener) throws Exception;

    Lyrics getLyrics(String artist, String title, Context context, ProgressListener progressListener) throws Exception;

	/**
	 * Time-synced lyrics for one track, or null when the server has none. Separate
	 * from getLyrics because that is keyed on artist and title, while a synced file
	 * belongs to a specific track and is fetched by its id.
	 */
	SyncedLyrics getSyncedLyrics(MusicDirectory.Entry song, Context context) throws Exception;

	/**
	 * Full detail for one track, or null when the server has nothing more to add.
	 *
	 * Some listing endpoints - Plex's Hub-based ones especially, which back things
	 * like the home screen and search - answer with a trimmed copy that leaves out
	 * bit depth and sample rate, even though the server has them. This is the same
	 * per-track lookup a full album listing uses, called only when Now Playing finds
	 * those fields missing on the song it is about to show.
	 */
	MusicDirectory.Entry getSongDetails(MusicDirectory.Entry song, Context context) throws Exception;

	/**
	 * Track count, running time and style tags for a batch of albums, keyed by album id.
	 *
	 * Batched because these are the fields listings leave out: answering per album would
	 * mean one request each, which is what makes duration filtering over a collection
	 * viable at all. Ids that the server knows nothing about are simply absent from the
	 * result rather than mapping to an empty entry.
	 */
	Map<String, AlbumDetails> getAlbumDetails(List<String> albumIds, Context context, ProgressListener progressListener) throws Exception;

	/**
	 * Albums worth going back to: well rated, but not listened to for a long time.
	 * Null when the server has no way to answer, so the caller can hide the row.
	 */
	MusicDirectory getRecommendedAlbums(int count, Context context, ProgressListener progressListener) throws Exception;

	/**
	 * Short, highly rated albums that have not been played for a season. Null when the
	 * server cannot answer, so the caller can hide the row.
	 *
	 * Every album that qualifies, not a picked few: working this out is expensive enough
	 * to be worth caching, and a cached pool the caller draws from anew each time still
	 * gives a row that differs from one look to the next. Picking here instead would
	 * freeze one draw for as long as the cache lived.
	 */
	MusicDirectory getShortAlbums(Context context, ProgressListener progressListener) throws Exception;

    void scrobble(String id, boolean submission, Context context, ProgressListener progressListener) throws Exception;

    MusicDirectory getAlbumList(String type, int size, int offset, boolean refresh, Context context, ProgressListener progressListener) throws Exception;

	MusicDirectory getAlbumList(String type, String extra, int size, int offset, boolean refresh, Context context, ProgressListener progressListener) throws Exception;

	MusicDirectory getSongList(String type, int size, int offset, Context context, ProgressListener progressListener) throws Exception;

	MusicDirectory getRandomSongs(int size, String artistId, Context context, ProgressListener progressListener) throws Exception;
    MusicDirectory getRandomSongs(int size, String folder, String genre, String startYear, String endYear, Context context, ProgressListener progressListener) throws Exception;

	String getCoverArtUrl(Context context, MusicDirectory.Entry entry) throws Exception;

    Bitmap getCoverArt(Context context, MusicDirectory.Entry entry, int size, ProgressListener progressListener, SilentBackgroundTask task) throws Exception;

    HttpURLConnection getDownloadInputStream(Context context, MusicDirectory.Entry song, long offset, int maxBitrate, SilentBackgroundTask task) throws Exception;

	String getMusicUrl(Context context, MusicDirectory.Entry song, int maxBitrate) throws Exception;

	String getVideoUrl(int maxBitrate, Context context, String id);
	
	String getVideoStreamUrl(String format, int Bitrate, Context context, String id) throws Exception;
	
	String getHlsUrl(String id, int bitRate, Context context) throws Exception;

    RemoteStatus updateJukeboxPlaylist(List<String> ids, Context context, ProgressListener progressListener) throws Exception;

    RemoteStatus skipJukebox(int index, int offsetSeconds, Context context, ProgressListener progressListener) throws Exception;

    RemoteStatus stopJukebox(Context context, ProgressListener progressListener) throws Exception;

    RemoteStatus startJukebox(Context context, ProgressListener progressListener) throws Exception;

    RemoteStatus getJukeboxStatus(Context context, ProgressListener progressListener) throws Exception;

    RemoteStatus setJukeboxGain(float gain, Context context, ProgressListener progressListener) throws Exception;
    
    void setStarred(List<MusicDirectory.Entry> entries, List<MusicDirectory.Entry> artists, List<MusicDirectory.Entry> albums, boolean starred, ProgressListener progressListener, Context context) throws Exception;
	
	List<Share> getShares(Context context, ProgressListener progressListener) throws Exception;

	List<Share> createShare(List<String> ids, String description, Long expires, Context context, ProgressListener progressListener) throws Exception;

	void deleteShare(String id, Context context, ProgressListener progressListener) throws Exception;

	void updateShare(String id, String description, Long expires, Context context, ProgressListener progressListener) throws Exception;
    
    List<ChatMessage> getChatMessages(Long since, Context context, ProgressListener progressListener) throws Exception;
    
    void addChatMessage(String message, Context context, ProgressListener progressListener) throws Exception;
	
	List<Genre> getGenres(boolean refresh, Context context, ProgressListener progressListener) throws Exception;
	
	MusicDirectory getSongsByGenre(String genre, int count, int offset, Context context, ProgressListener progressListener) throws Exception;

	MusicDirectory getTopTrackSongs(String artist, int size, Context context, ProgressListener progressListener) throws Exception;

	/**
	 * Songs that sound like the one given, as opposed to ones filed near it.
	 *
	 * What "sound like" means is the server's to decide - Plex analyses the audio itself,
	 * Subsonic asks last.fm - so the only promise made here is that the seed is a track and
	 * what comes back is a list of tracks, the seed not among them.
	 */
	MusicDirectory getSonicallySimilarSongs(MusicDirectory.Entry seed, int size, Context context, ProgressListener progressListener) throws Exception;

	/**
	 * Fills in the per-track details a listing leaves out - replay gain, bit depth, sampling
	 * rate - on the entries given, in place.
	 *
	 * A listing is deliberately lighter than the metadata behind it, so these arrive only
	 * when something asks. Doing it for a batch rather than a track at a time is the point:
	 * a queue's worth of single requests is not worth the levels it would buy. A server with
	 * nothing more to give simply leaves them as they were.
	 */
	default void fillSongDetails(List<MusicDirectory.Entry> songs, Context context) throws Exception {}
	
	List<PodcastChannel> getPodcastChannels(boolean refresh, Context context, ProgressListener progressListener) throws Exception;
	
	MusicDirectory getPodcastEpisodes(boolean refresh, String id, Context context, ProgressListener progressListener) throws Exception;

	MusicDirectory getNewestPodcastEpisodes(boolean refresh, Context context, ProgressListener progressListener, int count) throws Exception;
	
	void refreshPodcasts(Context context, ProgressListener progressListener) throws Exception;
	
	void createPodcastChannel(String url, Context context, ProgressListener progressListener) throws Exception;
	
	void deletePodcastChannel(String id, Context context, ProgressListener progressListener) throws Exception;
	
	void downloadPodcastEpisode(String id, Context context, ProgressListener progressListener) throws Exception;
	
	void deletePodcastEpisode(String id, String parent, ProgressListener progressListener, Context context) throws Exception;

	void setRating(MusicDirectory.Entry entry, int rating, Context context, ProgressListener progressListener) throws Exception;

	MusicDirectory getBookmarks(boolean refresh, Context context, ProgressListener progressListener) throws Exception;

	void createBookmark(MusicDirectory.Entry entry, int position, String comment, Context context, ProgressListener progressListener) throws Exception;

	void deleteBookmark(MusicDirectory.Entry entry, Context context, ProgressListener progressListener) throws Exception;

	User getUser(boolean refresh, String username, Context context, ProgressListener progressListener) throws Exception;

	List<User> getUsers(boolean refresh, Context context, ProgressListener progressListener) throws Exception;

	void createUser(User user, Context context, ProgressListener progressListener) throws Exception;

	void updateUser(User user, Context context, ProgressListener progressListener) throws Exception;

	void deleteUser(String username, Context context, ProgressListener progressListener) throws Exception;

	void changeEmail(String username, String email, Context context, ProgressListener progressListener) throws Exception;

	void changePassword(String username, String password, Context context, ProgressListener progressListener) throws Exception;

	Bitmap getAvatar(String username, int size, Context context, ProgressListener progressListener, SilentBackgroundTask task) throws Exception;

	ArtistInfo getArtistInfo(String id, boolean refresh, boolean allowNetwork, Context context, ProgressListener progressListener) throws Exception;

	Bitmap getBitmap(String url, int size, Context context, ProgressListener progressListener, SilentBackgroundTask task) throws Exception;

	MusicDirectory getVideos(boolean refresh, Context context, ProgressListener progressListener) throws Exception;

	void savePlayQueue(List<MusicDirectory.Entry> songs, MusicDirectory.Entry currentPlaying, int position, Context context, ProgressListener progressListener) throws Exception;

	PlayerQueue getPlayQueue(Context context, ProgressListener progressListener) throws Exception;

	List<InternetRadioStation> getInternetRadioStations(boolean refresh, Context context, ProgressListener progressListener) throws Exception;
	
	int processOfflineSyncs(final Context context, final ProgressListener progressListener) throws Exception;
	
	void setInstance(Integer instance) throws Exception;

	/**
	 * Reports live playback state to the server. Plex uses this to populate its
	 * dashboard; backends without a session concept ignore it.
	 *
	 * @param state one of "playing", "paused" or "stopped"
	 */
	void updatePlaybackState(MusicDirectory.Entry song, String state, int positionMs, Context context) throws Exception;

	/** Which configured server (1-based) this service is bound to. */
	int getInstance(Context context);

	/**
	 * Opaque identity for the currently configured server, used to key caches
	 * and detect when the user has switched servers.
	 */
	String getServerKey(Context context);
}
