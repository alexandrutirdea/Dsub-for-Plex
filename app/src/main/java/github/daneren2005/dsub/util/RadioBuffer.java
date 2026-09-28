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

import java.util.List;

import github.daneren2005.dsub.domain.MusicDirectory;

/**
 * Somewhere a queue that never ends gets its next songs from.
 *
 * Each kind of radio decides for itself what to fetch and when to fetch more; all the
 * queue wants is to be handed however many songs it is short of, without waiting on a
 * server to produce them.
 */
public interface RadioBuffer {
	/**
	 * Up to {@code size} songs, taken out of the buffer. Fewer when it has not filled yet,
	 * and none at all is a normal answer rather than an error - the queue asks again.
	 */
	List<MusicDirectory.Entry> get(int size);

	/** Stops fetching for good. */
	void shutdown();
}
