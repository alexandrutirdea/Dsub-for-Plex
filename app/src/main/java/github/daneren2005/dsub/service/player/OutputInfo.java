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
package github.daneren2005.dsub.service.player;

/**
 * What an engine can say about the path the track it is playing takes to the speaker.
 *
 * Worth having because the interesting question - whether what leaves the file is what
 * reaches the DAC - has no answer anywhere in the platform player. Every field here can be
 * unknown, and an engine that knows nothing returns no OutputInfo at all.
 */
public class OutputInfo {
	/** Something like "audio/flac", or null when unknown. */
	public final String codec;
	/** Sample rate of the recording in Hz, or 0 when unknown. */
	public final int sampleRate;
	/** Channels in the recording, or 0 when unknown. */
	public final int channelCount;
	/** Bits per sample of the recording, or 0 when the engine cannot say. */
	public final int bitDepth;
	/**
	 * Whether the encoded stream is being handed to the DSP rather than decoded here and
	 * mixed. The one path on Android where the platform mixer is out of the way.
	 */
	public final boolean offloaded;
	/**
	 * What the decoder actually handed the sink - "16-bit", "24-bit", "float" - or null
	 * when nothing says.
	 *
	 * Kept apart from {@link #bitDepth}, which is the recording's own, because the whole
	 * question of whether high resolution output is doing anything is whether these two
	 * agree. A twenty four bit file decoded into a sixteen bit buffer has already lost the
	 * difference, and nothing further down can tell.
	 */
	public final String sinkFormat;

	public OutputInfo(String codec, int sampleRate, int channelCount, int bitDepth,
			boolean offloaded, String sinkFormat) {
		this.codec = codec;
		this.sampleRate = sampleRate;
		this.channelCount = channelCount;
		this.bitDepth = bitDepth;
		this.offloaded = offloaded;
		this.sinkFormat = sinkFormat;
	}
}
