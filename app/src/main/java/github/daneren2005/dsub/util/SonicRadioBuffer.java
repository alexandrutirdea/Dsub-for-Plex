/*
  This file is part of Subsonic.
	Subsonic is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	Subsonic is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU General Public License for more details.
	You should have received a copy of the GNU General Public License
	along with Subsonic. If not, see <http://www.gnu.org/licenses/>.
	Copyright 2026 (C) Scott Jackson
*/

package github.daneren2005.dsub.util;

import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.service.DownloadService;
import github.daneren2005.dsub.service.MusicService;
import github.daneren2005.dsub.service.MusicServiceFactory;

/**
 * A queue that keeps going in whatever direction the song it started from was pointing.
 *
 * Asking the server what sounds like one track gives the same answer every time, so a
 * radio that kept asking about the track it started from would run out after one refill
 * and then repeat itself. Instead the seed moves: each refill asks about the last song the
 * previous one brought back, and the radio walks away from where it started rather than
 * circling it. Which is what makes it worth listening to for longer than a few songs, and
 * also why it drifts - an hour in, it is nowhere near the track that began it.
 *
 * The track it was started from plays first, and the walk begins behind it. Everything the
 * radio has handed over is remembered, so it does not double back onto songs that have just
 * played - the seed included, which the server always returns as the nearest thing to
 * itself. That memory is capped: far enough back that a session
 * does not repeat itself, not so far that a radio left running all day eventually has
 * nothing left it is allowed to play.
 */
public class SonicRadioBuffer implements RadioBuffer {
	private static final String TAG = SonicRadioBuffer.class.getSimpleName();

	/** How many songs back the radio refuses to repeat itself. */
	private static final int MEMORY = 300;

	private ScheduledExecutorService executorService;
	private final Runnable runnable;
	private final ArrayList<MusicDirectory.Entry> buffer = new ArrayList<MusicDirectory.Entry>();
	private final Set<String> played = new LinkedHashSet<String>();
	private final DownloadService context;
	private final int capacity;
	private final int refillThreshold;

	private int lastCount = -1;

	/** The track the next refill asks about, which moves along as the radio plays. */
	private MusicDirectory.Entry seed;

	public SonicRadioBuffer(DownloadService context) {
		this.context = context;
		this.runnable = new Runnable() {
			@Override
			public void run() {
				refill();
			}
		};

		int shuffleListSize = Math.max(1, Integer.parseInt(
				Util.getPreferences(context).getString(Constants.PREFERENCES_KEY_RANDOM_SIZE, "20")));
		capacity = Math.min(500, shuffleListSize * 5 / 2);
		refillThreshold = capacity * 4 / 5;
	}

	/** Starts the radio somewhere new, throwing away wherever the last one had wandered to. */
	public void setSeed(MusicDirectory.Entry seed) {
		synchronized(buffer) {
			buffer.clear();
			played.clear();
			lastCount = -1;
			// The song asked for is the first thing heard. Everything the server sends back
			// is what sounds like it, which is only worth hearing once the thing it sounds
			// like has played.
			buffer.add(seed);
		}
		this.seed = seed;
		remember(seed);
		refill();
	}

	/**
	 * Picks a radio back up after a restart, from the one thing kept about it - the id of
	 * the track it was last seeded from. Nothing of where it had walked to survives, so it
	 * carries on from there rather than from where it began.
	 */
	public void restoreSeed(String seedId) {
		if(seedId == null) {
			return;
		}

		MusicDirectory.Entry restored = new MusicDirectory.Entry();
		restored.setId(seedId);
		this.seed = restored;
		restart();
	}

	/** The track the radio would ask about next, which is what gets persisted. */
	public String getSeedId() {
		MusicDirectory.Entry current = seed;
		return current == null ? null : current.getId();
	}

	@Override
	public List<MusicDirectory.Entry> get(int size) {
		restart();

		List<MusicDirectory.Entry> result = new ArrayList<MusicDirectory.Entry>(size);
		synchronized(buffer) {
			while(!buffer.isEmpty() && result.size() < size) {
				result.add(buffer.remove(0));
			}
		}
		Log.i(TAG, "Taking " + result.size() + " songs from the sonic radio buffer. "
				+ buffer.size() + " remaining.");
		return result;
	}

	@Override
	public void shutdown() {
		if(executorService != null) {
			executorService.shutdown();
		}
	}

	private void restart() {
		synchronized(buffer) {
			if(buffer.size() <= refillThreshold && lastCount != 0
					&& (executorService == null || executorService.isShutdown())) {
				executorService = Executors.newSingleThreadScheduledExecutor();
				executorService.scheduleWithFixedDelay(runnable, 0, 10, TimeUnit.SECONDS);
			}
		}
	}

	private void refill() {
		// Seeding the radio calls straight in here before anything is scheduled, so an
		// absent executor is not a reason to stop - only a reason not to shut one down.
		if(buffer.size() > refillThreshold || lastCount == 0
				|| (!Util.isNetworkConnected(context) && !Util.isOffline(context))) {
			if(executorService != null) {
				executorService.shutdown();
			}
			return;
		}

		MusicDirectory.Entry from = seed;
		if(from == null) {
			return;
		}

		try {
			MusicService service = MusicServiceFactory.getMusicService(context);
			MusicDirectory songs = service.getSonicallySimilarSongs(
					from, capacity - buffer.size(), context, null);

			synchronized(buffer) {
				lastCount = 0;
				MusicDirectory.Entry furthest = null;
				for(MusicDirectory.Entry entry : songs.getChildren()) {
					if(entry.getId() == null || played.contains(entry.getId())) {
						continue;
					}
					remember(entry);
					buffer.add(entry);
					furthest = entry;
					lastCount++;
				}

				if(furthest != null) {
					// The next refill carries on from the far end of this one, which is
					// what keeps the radio moving rather than asking the same question
					// again.
					seed = furthest;
				}
				Log.i(TAG, "Refilled the sonic radio buffer with " + lastCount + " songs.");
			}
		} catch(Exception x) {
			// One more try before giving up on it, the way the other radios do: a single
			// failed request is far more often the network than the end of the road.
			if(lastCount != -2) {
				lastCount = -2;
			} else {
				lastCount = 0;
			}
			Log.w(TAG, "Failed to refill the sonic radio buffer.", x);
		}
	}

	private void remember(MusicDirectory.Entry entry) {
		if(entry == null || entry.getId() == null) {
			return;
		}

		synchronized(buffer) {
			played.add(entry.getId());
			// Oldest first, so what drops off is what the radio played longest ago.
			java.util.Iterator<String> oldest = played.iterator();
			while(played.size() > MEMORY && oldest.hasNext()) {
				oldest.next();
				oldest.remove();
			}
		}
	}
}
