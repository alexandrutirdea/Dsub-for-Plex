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

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import github.daneren2005.dsub.util.TrackEnvelope;

/**
 * Gives each track its own level, on the samples themselves.
 *
 * A player carries one volume, and a playlist played gaplessly moves between tracks
 * without giving anyone a moment to change it: by the time the handover is reported, the
 * first samples of the new track have already been written at the old track's level. On
 * two players that never came up, because each carried its own volume and the one waiting
 * could be set long before it was heard.
 *
 * The place where a track's identity and its samples are both in hand is here, at the
 * point buffers enter the sink. What arrives is read only - a decoder does not hand out
 * anything else - so a turned down buffer is written into one of ours and passed on in its
 * place. {@link #setOutputStreamOffsetUs} is the boundary: the
 * renderer only calls it once it has finished processing the previous stream's output, so
 * everything arriving afterwards belongs to the next track. The gain waiting for that
 * track is taken up there, and applied to every buffer from then on.
 *
 * What it cannot scale, it carries the other way. Offload hands the sink an encoded stream
 * rather than PCM, and scaling those bytes would be destroying the file rather than turning
 * it down; a PCM layout this does not know is the same story. There the track's level is
 * folded into the volume instead, still at the boundary but no longer exactly on it: a
 * volume applies to everything already written and waiting to be heard, so the last
 * fraction of a second of the previous track goes out at the new track's level. Better than
 * the reverse, and the best available when the samples are not ours to touch.
 *
 * Which of the two applies is decided here rather than above, because it is only known once
 * the sink has been configured and playback is by then already underway. Deciding it
 * anywhere else means a window where the volume still carries a level the samples have
 * started carrying too, and every track would begin with a dip.
 *
 * Everything that acts on a buffer or touches the volume runs on the playback thread. The
 * gain setters are the exception - they are called from the thread driving the queue - and
 * they only write a field and leave a flag for the playback thread to act on.
 */
@OptIn(markerClass = UnstableApi.class)
public class ReplayGainSink extends ForwardingAudioSink {
	private static final String TAG = ReplayGainSink.class.getSimpleName();

	/** The level the samples going out right now are being given. */
	private volatile float currentGain = 1f;
	/** The level waiting for the next track to begin, or null when none is waiting. */
	private volatile Float pendingGain;
	/** Whether what is arriving is something this can scale. */
	private volatile boolean applyingGain = false;
	/** What the volume is turned down for, as handed down by the player. */
	private float playerVolume = 1f;
	/** Set when a gain changed off-thread, for the playback thread to act on. */
	private volatile boolean volumeStale = false;

	private volatile int pcmEncoding = Format.NO_VALUE;
	/** Frames per second and bytes per frame, for turning a buffer into a length of time. */
	private int sampleRate = 0;
	private int bytesPerFrame = 0;

	/**
	 * Measures how the track begins and ends as it goes past. Reads the buffer the renderer
	 * handed over, before anything of ours is applied to it, so what it measures is the
	 * recording rather than what this happens to be doing to it.
	 */
	private final EnvelopeMeter envelopeMeter = new EnvelopeMeter();
	/**
	 * The offset the last stream arrived with. A stream that starts where the last one did
	 * is the same one over again - a seek - rather than the next track, and must not take
	 * up the level waiting for the next track.
	 */
	private long lastStreamOffsetUs = Long.MIN_VALUE;

	/**
	 * The last buffer the renderer handed over, and the time it came with. A sink that
	 * cannot take a whole buffer is handed the same one again, and each of those is the
	 * same audio rather than more of it.
	 */
	private ByteBuffer lastSource;
	private long lastSourceTimeUs;
	/** What went down to the sink for {@link #lastSource} - either it, or a scaled copy. */
	private ByteBuffer lastHandedDown;
	/**
	 * Where scaled audio is written. A decoder hands out read only buffers, so turning a
	 * track down means writing somewhere else; one buffer is kept and grown rather than
	 * allocated per buffer, which is what every processor in the pipeline below does too.
	 */
	private ByteBuffer scaledCopy;

	public ReplayGainSink(AudioSink sink) {
		super(sink);
	}

	/** The level for the track being heard, taken up at once. */
	public void setCurrentGain(float gain) {
		currentGain = gain;
		pendingGain = null;
		volumeStale = true;
	}

	/** The level for the track queued behind this one, taken up when it begins. */
	public void setPendingGain(float gain) {
		pendingGain = gain;
	}

	public void clearPendingGain() {
		pendingGain = null;
	}

	public boolean isApplyingGain() {
		return applyingGain;
	}

	/**
	 * The layout the sink was actually handed, which is the decoder's output rather than
	 * the recording's own. The two part company exactly where it matters: a twenty four
	 * bit file decoded into a sixteen bit buffer has already lost the difference by the
	 * time anything downstream sees it. {@link Format#NO_VALUE} when nothing is loaded, or
	 * when what arrives is not PCM at all.
	 */
	public int getSinkPcmEncoding() {
		return pcmEncoding;
	}

	@Override
	public void setVolume(float volume) {
		playerVolume = volume;
		pushVolume();
	}

	/**
	 * Hands the sink below whatever this one is not already carrying on the samples.
	 *
	 * Always from the playback thread, which is where every caller of it runs: the volume
	 * coming down from the player, the sink being configured, a track boundary, and a
	 * buffer arriving after a gain changed elsewhere.
	 */
	private void pushVolume() {
		volumeStale = false;
		super.setVolume(applyingGain ? playerVolume : playerVolume * currentGain);
	}

	@Override
	public void configure(Format inputFormat, int specifiedBufferSize, int[] outputChannels)
			throws ConfigurationException {
		pcmEncoding = MimeTypes.AUDIO_RAW.equals(inputFormat.sampleMimeType)
				? inputFormat.pcmEncoding
				: Format.NO_VALUE;
		sampleRate = inputFormat.sampleRate == Format.NO_VALUE ? 0 : inputFormat.sampleRate;
		int channels = inputFormat.channelCount == Format.NO_VALUE ? 0 : inputFormat.channelCount;
		bytesPerFrame = channels * bytesPerSample(pcmEncoding);
		setApplying(canScale(pcmEncoding));
		Log.i(TAG, "Sink configured for " + inputFormat.sampleMimeType
				+ " pcmEncoding=" + inputFormat.pcmEncoding
				+ " " + inputFormat.sampleRate + "Hz"
				+ " " + inputFormat.channelCount + "ch");

		super.configure(inputFormat, specifiedBufferSize, outputChannels);
		pushVolume();
	}

	@Override
	public void setOutputStreamOffsetUs(long outputStreamOffsetUs) {
		if(outputStreamOffsetUs != lastStreamOffsetUs) {
			lastStreamOffsetUs = outputStreamOffsetUs;

			Float pending = pendingGain;
			if(pending != null) {
				pendingGain = null;
				currentGain = pending;
				pushVolume();
				Log.i(TAG, "Next track starts here, at a gain of " + pending);
			}
		}

		super.setOutputStreamOffsetUs(outputStreamOffsetUs);
	}

	@Override
	public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
			throws InitializationException, WriteException {
		if(volumeStale) {
			// A gain was set from the thread driving the queue. This is the first moment
			// the playback thread has to act on it.
			pushVolume();
		}

		if(buffer != lastSource || presentationTimeUs != lastSourceTimeUs) {
			// Audio this has not seen before. Anything else is the sink asking for the
			// rest of what it could not take, and has to be given the very same buffer
			// back - the one below tracks it by identity.
			lastSource = buffer;
			lastSourceTimeUs = presentationTimeUs;

			measure(buffer, presentationTimeUs);

			float gain = currentGain;
			lastHandedDown = (applyingGain && gain < 1f) ? scaleInto(buffer, gain) : buffer;
		}

		return super.handleBuffer(lastHandedDown, presentationTimeUs, encodedAccessUnitCount);
	}

	@Override
	public void flush() {
		// Whatever was in hand is gone, but the stream it belonged to has not changed.
		lastSource = null;
		lastHandedDown = null;
		super.flush();
	}

	@Override
	public void reset() {
		lastSource = null;
		lastHandedDown = null;
		lastStreamOffsetUs = Long.MIN_VALUE;
		super.reset();
	}

	/** The shape of the track that has been playing, or null when too little was heard. */
	public TrackEnvelope takeEnvelope() {
		if(!envelopeMeter.hasAudio()) {
			return null;
		}
		TrackEnvelope envelope = envelopeMeter.finish();
		envelopeMeter.reset();
		return envelope;
	}

	/** Throws away what has been measured, for an engine being handed a different track. */
	public void forgetEnvelope() {
		envelopeMeter.reset();
	}

	/**
	 * Adds one buffer to the shape being measured.
	 *
	 * Only what can be read as samples, which is the same set this can scale - an encoded
	 * stream on its way to the DSP is bytes of a file rather than audio, and there is
	 * nothing in it to measure without decoding it again.
	 */
	private void measure(ByteBuffer source, long presentationTimeUs) {
		if(!applyingGain || bytesPerFrame <= 0 || sampleRate <= 0) {
			return;
		}

		int from = source.position();
		int to = source.limit();
		int frames = (to - from) / bytesPerFrame;
		if(frames <= 0) {
			return;
		}

		ByteOrder sourceOrder = source.order();
		source.order(ByteOrder.LITTLE_ENDIAN);
		double total = 0;
		int counted = 0;
		try {
			switch(pcmEncoding) {
				case C.ENCODING_PCM_16BIT:
					for(int i = from; i + 2 <= to; i += 2) {
						double sample = source.getShort(i) / 32768.0;
						total += sample * sample;
						counted++;
					}
					break;

				case C.ENCODING_PCM_24BIT:
					for(int i = from; i + 3 <= to; i += 3) {
						int raw = (source.get(i + 2) << 16)
								| ((source.get(i + 1) & 0xFF) << 8)
								| (source.get(i) & 0xFF);
						double sample = raw / 8388608.0;
						total += sample * sample;
						counted++;
					}
					break;

				case C.ENCODING_PCM_32BIT:
					for(int i = from; i + 4 <= to; i += 4) {
						double sample = source.getInt(i) / 2147483648.0;
						total += sample * sample;
						counted++;
					}
					break;

				case C.ENCODING_PCM_FLOAT:
					for(int i = from; i + 4 <= to; i += 4) {
						double sample = source.getFloat(i);
						total += sample * sample;
						counted++;
					}
					break;

				default:
					return;
			}
		} finally {
			source.order(sourceOrder);
		}

		if(counted > 0) {
			envelopeMeter.add(total / counted, frames, presentationTimeUs / 1000L);
		}
	}

	/** How wide one sample of a layout is, or zero for one this cannot read. */
	private static int bytesPerSample(int encoding) {
		switch(encoding) {
			case C.ENCODING_PCM_16BIT: return 2;
			case C.ENCODING_PCM_24BIT: return 3;
			case C.ENCODING_PCM_32BIT:
			case C.ENCODING_PCM_FLOAT: return 4;
			default: return 0;
		}
	}

	private void setApplying(boolean applying) {
		if(applying == applyingGain) {
			return;
		}

		applyingGain = applying;
		Log.i(TAG, applying
				? "Track gain goes on the samples"
				: "Track gain cannot go on the samples here, so it has to ride on the volume");
		pushVolume();
	}

	/** The layouts whose samples this knows how to turn down in place. */
	private static boolean canScale(int pcmEncoding) {
		switch(pcmEncoding) {
			case C.ENCODING_PCM_16BIT:
			case C.ENCODING_PCM_24BIT:
			case C.ENCODING_PCM_32BIT:
			case C.ENCODING_PCM_FLOAT:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Writes every sample from the source's position to its limit, turned down by gain,
	 * into a buffer of our own and hands that back.
	 *
	 * Somewhere else rather than in place because a decoder's output buffer is read only:
	 * MediaCodec hands them out that way, and writing to one is not on offer whatever the
	 * layout.
	 *
	 * Only ever down: DownloadService keeps the gain at or below one and asks the session's
	 * loudness enhancer for anything above, so nothing here can clip.
	 */
	private ByteBuffer scaleInto(ByteBuffer source, float gain) {
		int from = source.position();
		int to = source.limit();
		int length = to - from;

		if(scaledCopy == null || scaledCopy.capacity() < length) {
			scaledCopy = ByteBuffer.allocateDirect(length);
		}
		scaledCopy.clear();
		scaledCopy.order(ByteOrder.LITTLE_ENDIAN);

		ByteOrder sourceOrder = source.order();
		source.order(ByteOrder.LITTLE_ENDIAN);

		try {
			switch(pcmEncoding) {
				case C.ENCODING_PCM_16BIT:
					for(int i = from; i + 2 <= to; i += 2) {
						scaledCopy.putShort((short) Math.rint(source.getShort(i) * (double) gain));
					}
					break;

				case C.ENCODING_PCM_24BIT:
					for(int i = from; i + 3 <= to; i += 3) {
						// Three bytes, little endian, the top one carrying the sign.
						int sample = (source.get(i + 2) << 16)
								| ((source.get(i + 1) & 0xFF) << 8)
								| (source.get(i) & 0xFF);
						int scaled = (int) Math.rint(sample * (double) gain);
						scaledCopy.put((byte) scaled);
						scaledCopy.put((byte) (scaled >> 8));
						scaledCopy.put((byte) (scaled >> 16));
					}
					break;

				case C.ENCODING_PCM_32BIT:
					for(int i = from; i + 4 <= to; i += 4) {
						scaledCopy.putInt((int) Math.rint(source.getInt(i) * (double) gain));
					}
					break;

				case C.ENCODING_PCM_FLOAT:
					for(int i = from; i + 4 <= to; i += 4) {
						scaledCopy.putFloat(source.getFloat(i) * gain);
					}
					break;

				default:
					// Not something we said we could scale, so nothing is changed.
					scaledCopy.put(source.duplicate());
					break;
			}
		} finally {
			source.order(sourceOrder);
		}

		scaledCopy.flip();
		return scaledCopy;
	}
}
