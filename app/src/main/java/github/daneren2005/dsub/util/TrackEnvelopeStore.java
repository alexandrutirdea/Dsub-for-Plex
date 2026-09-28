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

 Copyright 2026 (C) Scott Jackson
*/
package github.daneren2005.dsub.util;

import android.content.Context;
import android.util.Log;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What has been learned about how the tracks on this device begin and end.
 *
 * A shape is measured while a track plays, but it is wanted before one plays - the queue
 * has to decide how to join two records at the moment the second is armed, which is before
 * a note of it has been heard. So nothing here is useful the first time a track comes
 * round, and everything here is useful the second. That is the whole design: it fills in as
 * the library gets listened to, and asks nothing of anybody in the meantime.
 *
 * Held to a fixed number of tracks, oldest use dropped first. A shape is a few hundred
 * bytes, so the cap is about keeping the file small enough to write often rather than about
 * memory.
 */
public class TrackEnvelopeStore {
	private static final String TAG = TrackEnvelopeStore.class.getSimpleName();
	private static final String FILE_NAME = "track_envelopes.ser";

	/** How many tracks are remembered. Beyond this the least recently used one goes. */
	private static final int CAPACITY = 2000;

	private static TrackEnvelopeStore instance;

	private final Context context;
	private final LruEnvelopes envelopes;
	private boolean dirty = false;

	private TrackEnvelopeStore(Context context) {
		this.context = context.getApplicationContext();

		LruEnvelopes loaded = FileUtil.deserializeCompressed(this.context, FILE_NAME, LruEnvelopes.class);
		this.envelopes = loaded == null ? new LruEnvelopes() : loaded;
		Log.i(TAG, "Know how " + envelopes.size() + " tracks begin and end");
	}

	public static synchronized TrackEnvelopeStore getInstance(Context context) {
		if(instance == null) {
			instance = new TrackEnvelopeStore(context);
		}
		return instance;
	}

	/** What was measured of a track, or null for one that has not been heard through yet. */
	public synchronized TrackEnvelope get(int serverInstance, String id) {
		if(id == null) {
			return null;
		}
		return envelopes.get(key(serverInstance, id));
	}

	public synchronized void put(int serverInstance, String id, TrackEnvelope envelope) {
		if(id == null || envelope == null || !envelope.isUsable()) {
			return;
		}
		envelopes.put(key(serverInstance, id), envelope);
		dirty = true;
	}

	/**
	 * Writes the file if anything has changed. Called from a background thread, since a
	 * track ending is not a moment anybody wants spent on a disk write.
	 */
	public synchronized void save() {
		if(!dirty) {
			return;
		}
		dirty = false;
		FileUtil.serializeCompressed(context, envelopes, FILE_NAME);
	}

	/**
	 * Ids are only unique within a server, and the same library added twice is two servers.
	 */
	private static String key(int serverInstance, String id) {
		return serverInstance + ":" + id;
	}

	/** A plain LRU map, which is all {@link LinkedHashMap} needs telling to be. */
	private static class LruEnvelopes extends LinkedHashMap<String, TrackEnvelope> implements Serializable {
		private static final long serialVersionUID = 1L;

		LruEnvelopes() {
			super(16, 0.75f, true);
		}

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, TrackEnvelope> eldest) {
			return size() > CAPACITY;
		}
	}
}
