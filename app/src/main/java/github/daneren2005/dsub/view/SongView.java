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
package github.daneren2005.dsub.view;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import github.daneren2005.dsub.R;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.PodcastEpisode;
import github.daneren2005.dsub.service.DownloadService;
import github.daneren2005.dsub.service.DownloadFile;
import github.daneren2005.dsub.util.DrawableTint;
import github.daneren2005.dsub.util.SongDBHandler;
import github.daneren2005.dsub.util.ThemeUtil;
import github.daneren2005.dsub.util.Util;

import java.io.File;

/**
 * Used to display songs in a {@code ListView}.
 *
 * @author Sindre Mehus
 */
public class SongView extends UpdateView2<MusicDirectory.Entry, Boolean> {
	private static final String TAG = SongView.class.getSimpleName();

	private TextView trackTextView;
	private TextView titleTextView;
	private TextView playingTextView;
	private TextView artistTextView;
	private TextView durationTextView;
	private TextView statusTextView;
	private ImageView statusImageView;
	private ImageView bookmarkButton;
	private ImageView playedButton;
	private ImageView cachedButton;
	private ImageView lovedView;
	private View bottomRowView;
	private TextView suffixTextView;

	private DownloadService downloadService;
	private long revision = -1;
	private DownloadFile downloadFile;
	private boolean dontChangeDownloadFile = false;

	private boolean playing = false;
	private boolean rightImage = false;
	// -1 rather than 0: 0 is now a real state - the row is not on the device and the
	// cloud draws nothing - so it cannot double as "not yet decided".
	private int cloudImage = -1;
	private boolean isWorkDone = false;
	private boolean isSaved = false;
	private File partialFile;
	private boolean partialFileExists = false;
	private boolean loaded = false;
	private boolean isBookmarked = false;
	private boolean isBookmarkedShown = false;
	private boolean showPodcast = false;
	private boolean isPlayed = false;
	private boolean isPlayedShown = false;
	private boolean showAlbum = false;
	private String albumArtist = null;
	private boolean loved = false;

	public SongView(Context context) {
		super(context);
		LayoutInflater.from(context).inflate(R.layout.song_list_item, this, true);

		trackTextView = (TextView) findViewById(R.id.song_track);
		titleTextView = (TextView) findViewById(R.id.song_title);
		artistTextView = (TextView) findViewById(R.id.song_artist);
		durationTextView = (TextView) findViewById(R.id.song_duration);
		statusTextView = (TextView) findViewById(R.id.song_status);
		statusImageView = (ImageView) findViewById(R.id.song_status_icon);
		ratingBar = (RatingBar) findViewById(R.id.song_rating);
		starButton = (ImageButton) findViewById(R.id.song_star);
		starButton.setFocusable(false);
		bookmarkButton = (ImageButton) findViewById(R.id.song_bookmark);
		bookmarkButton.setFocusable(false);
		playedButton = (ImageButton) findViewById(R.id.song_played);
		moreButton = (ImageView) findViewById(R.id.item_more);
		cachedButton = (ImageView) findViewById(R.id.item_cached);
		lovedView = (ImageView) findViewById(R.id.song_loved);
		bottomRowView = findViewById(R.id.song_bottom);
		suffixTextView = (TextView) findViewById(R.id.song_suffix);
	}

	public void setObjectImpl(MusicDirectory.Entry song, Boolean checkable) {
		this.checkable = checkable;

		StringBuilder artist = new StringBuilder(40);

		boolean isPodcast = song instanceof PodcastEpisode;
		if(!song.isVideo() || isPodcast) {
			if(isPodcast) {
				PodcastEpisode episode = (PodcastEpisode) song;
				if(showPodcast && episode.getArtist() != null) {
					artist.append(episode.getArtist());
				}

				String date = episode.getDate();
				if(date != null) {
					if(artist.length() != 0) {
						artist.append(" - ");
					}
					artist.append(Util.formatDate(context, date, false));
				}
			}
			else if(song.getArtist() != null) {
				if(showAlbum) {
					artist.append(song.getAlbum());
				} else if(!isAlbumArtist(song.getArtist())) {
					artist.append(song.getArtist());
				}
			}

			if(isPodcast) {
				String status = ((PodcastEpisode) song).getStatus();
				int statusRes = -1;

				if("error".equals(status)) {
					statusRes = R.string.song_details_error;
				} else if("skipped".equals(status)) {
					statusRes = R.string.song_details_skipped;
				} else if("downloading".equals(status)) {
					statusRes = R.string.song_details_downloading;
				}

				if(statusRes != -1) {
					artist.append(" (");
					artist.append(getContext().getString(statusRes));
					artist.append(")");
				}
			}

			durationTextView.setText(Util.formatDuration(song.getDuration()));
			durationTextView.setVisibility(View.VISIBLE);

			// The second line is only worth its height when it carries something. On an
			// album's own listing the artist is the album's, which the header states
			// already, so nearly every row here comes out as one line.
			bottomRowView.setVisibility(artist.length() > 0 ? View.VISIBLE : View.GONE);
		} else {
			// A video keeps its duration where it always was, beside the status text.
			bottomRowView.setVisibility(View.GONE);
			durationTextView.setVisibility(View.GONE);
			statusTextView.setText(Util.formatDuration(song.getDuration()));
		}

		String title = song.getTitle();
		Integer track = song.getTrack();
		if(song.getCustomOrder() != null) {
			track = song.getCustomOrder();
		}
		TextView newPlayingTextView;
		if(track != null && Util.getDisplayTrack(context)) {
			trackTextView.setText(String.format("%02d", track));
			trackTextView.setVisibility(View.VISIBLE);
			newPlayingTextView = trackTextView;
		} else {
			trackTextView.setVisibility(View.GONE);
			newPlayingTextView = titleTextView;
		}

		if(newPlayingTextView != playingTextView || playingTextView == null) {
			if(playing) {
				playingTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
				playing = false;
			}

			playingTextView = newPlayingTextView;
		}

		titleTextView.setText(title);
		artistTextView.setText(artist);
		if(Util.getDisplayFileSuffix(context)) {
			suffixTextView.setText(song.getSuffix());
		}

		// Set on every bind rather than only when loved: these views are recycled, and a
		// row that inherited a heart from the track that used to occupy it would keep it.
		applyLoved();

		this.setBackgroundColor(0x00000000);
		ratingBar.setVisibility(View.GONE);
		rating = 0;

		revision = -1;
		loaded = false;
		dontChangeDownloadFile = false;
	}

	public void setDownloadFile(DownloadFile downloadFile) {
		this.downloadFile = downloadFile;
		dontChangeDownloadFile = true;
	}

	public DownloadFile getDownloadFile() {
		return downloadFile;
	}

	@Override
	protected void updateBackground() {
		if (downloadService == null) {
			downloadService = DownloadService.getInstance();
			if(downloadService == null) {
				return;
			}
		}

		long newRevision = downloadService.getDownloadListUpdateRevision();
		if((revision != newRevision && dontChangeDownloadFile == false) || downloadFile == null) {
			downloadFile = downloadService.forSong(item);
			revision = newRevision;
		}

		isWorkDone = downloadFile.isWorkDone();
		isSaved = downloadFile.isSaved();
		partialFile = downloadFile.getPartialFile();
		partialFileExists = partialFile.exists();
		isStarred = item.isStarred();
		isBookmarked = item.getBookmark() != null;
		isRated = item.getRating();

		// Check if needs to load metadata: check against all fields that we know are null in offline mode
		if(item.getBitRate() == null && item.getDuration() == null && item.getDiscNumber() == null && isWorkDone) {
			item.loadMetadata(downloadFile.getCompleteFile());
			loaded = true;
		}

		if(item instanceof PodcastEpisode || item.isAudioBook() || item.isPodcast()) {
			isPlayed = SongDBHandler.getHandler(context).hasBeenCompleted(item);
		}
	}

	@Override
	protected void update() {
		if(loaded) {
			setObjectImpl(item, item2);
		}
		if (downloadService == null || downloadFile == null) {
			return;
		}

		if(item.isStarred()) {
			if(!starred) {
				if(starButton.getDrawable() == null) {
					starButton.setImageDrawable(DrawableTint.getTintedDrawable(context, R.drawable.ic_toggle_star));
				}
				starButton.setVisibility(View.VISIBLE);
				starred = true;
			}
		} else {
			if(starred) {
				starButton.setVisibility(View.GONE);
				starred = false;
			}
		}

		// The cloud carries this now, so item_more is left alone and stays the overflow
		// dots it is in the layout. Pinned outranks cached: a pinned track is also work
		// done, and the arrow is the more specific of the two things to say about it.
		int cloudImage;
		int cloudDescription;
		if(isWorkDone && isSaved) {
			cloudImage = R.drawable.ic_cloud_pinned;
			cloudDescription = R.string.song_pinned;
		} else if(isWorkDone) {
			cloudImage = R.drawable.ic_cloud_cached;
			cloudDescription = R.string.song_cached;
		} else {
			// Drawn as nothing: see UpdateView, which does the same for album rows.
			cloudImage = 0;
			cloudDescription = R.string.song_not_cached;
		}

		if(cloudImage != this.cloudImage) {
			if(cloudImage == 0) {
				cachedButton.setVisibility(View.INVISIBLE);
			} else {
				cachedButton.setImageResource(cloudImage);
				cachedButton.setVisibility(View.VISIBLE);
			}
			cachedButton.setContentDescription(context.getResources().getString(cloudDescription));
			this.cloudImage = cloudImage;
		}

		if (downloadFile.isDownloading() && !downloadFile.isDownloadCancelled() && partialFileExists) {
			double percentage = (partialFile.length() * 100.0) / downloadFile.getEstimatedSize();
			percentage = Math.min(percentage, 100);
			statusTextView.setText((int)percentage + " %");
			if(!rightImage) {
				statusImageView.setVisibility(View.VISIBLE);
				rightImage = true;
			}
		} else if(rightImage) {
			statusTextView.setText(null);
			statusImageView.setVisibility(View.GONE);
			rightImage = false;
		}

		boolean playing = Util.equals(downloadService.getCurrentPlaying(), downloadFile);
		if (playing) {
			if(!this.playing) {
				this.playing = playing;
				playingTextView.setCompoundDrawablesWithIntrinsicBounds(DrawableTint.getDrawableRes(context, R.attr.playing), 0, 0, 0);
			}
		} else {
			if(this.playing) {
				this.playing = playing;
				playingTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
			}
		}

		if(isBookmarked) {
			if(!isBookmarkedShown) {
				if(bookmarkButton.getDrawable() == null) {
					bookmarkButton.setImageDrawable(DrawableTint.getTintedDrawable(context, R.drawable.ic_menu_bookmark_selected));
				}

				bookmarkButton.setVisibility(View.VISIBLE);
				isBookmarkedShown = true;
			}
		} else {
			if(isBookmarkedShown) {
				bookmarkButton.setVisibility(View.GONE);
				isBookmarkedShown = false;
			}
		}

		if(isPlayed) {
			if(!isPlayedShown) {
				if(playedButton.getDrawable() == null) {
					playedButton.setImageDrawable(DrawableTint.getTintedDrawable(context, R.drawable.ic_toggle_played));
				}

				playedButton.setVisibility(View.VISIBLE);
				isPlayedShown = true;
			}
		} else {
			if(isPlayedShown) {
				playedButton.setVisibility(View.GONE);
				isPlayedShown = false;
			}
		}

		if(isRated != rating) {
			if(isRated > 1) {
				if(rating <= 1) {
					ratingBar.setVisibility(View.VISIBLE);
					// The stars live on the second line, which a row with nothing to say
					// under its title has folded away.
					bottomRowView.setVisibility(View.VISIBLE);
				}

				ratingBar.setNumStars(isRated);
				ratingBar.setRating(isRated);
			} else if(isRated <= 1) {
				if(rating > 1) {
					ratingBar.setVisibility(View.GONE);
				}
			}

			// Still highlight red if a 1-star
			if(isRated == 1) {
				this.setBackgroundColor(Color.RED);

				String theme = ThemeUtil.getTheme(context);
				if("black".equals(theme)) {
					this.getBackground().setAlpha(80);
				} else if("dark".equals(theme) || "holo".equals(theme)) {
					this.getBackground().setAlpha(60);
				} else {
					this.getBackground().setAlpha(20);
				}
			} else if(rating == 1) {
				this.setBackgroundColor(0x00000000);
			}

			rating = isRated;
		}
	}

	public MusicDirectory.Entry getEntry() {
		return item;
	}

	public void setShowPodcast(boolean showPodcast) {
		this.showPodcast = showPodcast;
	}

	public void setShowAlbum(boolean showAlbum) {
		this.showAlbum = showAlbum;
	}

	/**
	 * Who the listing this row sits in is by. Null everywhere the question has no answer -
	 * a playlist, a search result, the play queue - and those rows keep naming the artist.
	 */
	public void setAlbumArtist(String albumArtist) {
		this.albumArtist = albumArtist;
	}

	/**
	 * Whether this track is loved on Last.fm.
	 *
	 * Draws itself rather than waiting for setObject to do it: the hearts arrive from the
	 * dashboard after the listing is already on screen, and rebinding a row that is
	 * already showing the same Entry returns early without reaching setObjectImpl. Left
	 * to setObject, a heart would only ever appear on rows scrolled into view afterwards.
	 */
	public void setLoved(boolean loved) {
		this.loved = loved;
		applyLoved();
	}

	private void applyLoved() {
		if(loved) {
			if(lovedView.getDrawable() == null) {
				lovedView.setImageResource(R.drawable.ic_love_filled);
			}
			lovedView.setVisibility(View.VISIBLE);
		} else {
			lovedView.setVisibility(View.GONE);
		}
	}

	/**
	 * Whether naming this artist under the title would only repeat the album's own artist,
	 * which the header above the list already says. A guest on one track of an album does
	 * differ, and that is the row worth spending the line on.
	 */
	private boolean isAlbumArtist(String artist) {
		return albumArtist != null && albumArtist.trim().equalsIgnoreCase(artist.trim());
	}
}
