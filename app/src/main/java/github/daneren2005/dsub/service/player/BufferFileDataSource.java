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

import android.net.Uri;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;

import github.daneren2005.serverproxy.BufferFile;

/**
 * Reads a file that is still being written.
 *
 * A download in progress is a file whose end moves, and every reader DSub has had until
 * now treats the end it can see as the end of the song: the platform player finishes the
 * track early and the queue has to notice and start it again from where it stopped. The
 * way around that was to serve the file over HTTP from a socket on this device
 * ({@link github.daneren2005.serverproxy.BufferProxy}), which holds the response open and
 * keeps writing as more arrives.
 *
 * An engine that takes its bytes from a {@link DataSource} needs none of that. Running out
 * of bytes here is not the end of anything - it waits for the download to catch up, the
 * same way the proxy's socket did, and the loopback hop goes away.
 *
 * The end really has arrived when the download says it is done and there is nothing left
 * to read. A download that has instead stalled for {@link #STALL_TIMEOUT_MS} gives up, so
 * a dead transfer surfaces as a read error the queue can act on rather than as silence.
 */
@OptIn(markerClass = UnstableApi.class)
public class BufferFileDataSource extends BaseDataSource {
	private static final String TAG = BufferFileDataSource.class.getSimpleName();

	/** How long to sit on an empty read before asking again. */
	private static final long POLL_INTERVAL_MS = 250;
	/** How long a file can go without growing before the read is called failed. */
	private static final long STALL_TIMEOUT_MS = 30000;

	private final BufferFile bufferFile;

	private Uri uri;
	private File file;
	private RandomAccessFile input;
	private long position;
	private long bytesRemaining;
	private boolean opened;

	public BufferFileDataSource(BufferFile bufferFile) {
		super(/* isNetwork= */ false);
		this.bufferFile = bufferFile;
	}

	@Override
	public long open(DataSpec dataSpec) throws IOException {
		uri = dataSpec.uri;
		transferInitializing(dataSpec);

		file = bufferFile.getFile();
		input = new RandomAccessFile(file, "r");
		position = dataSpec.position;
		input.seek(position);

		// Takes the lock that keeps the download from renaming the file out from under
		// this read the moment it finishes.
		bufferFile.onStart();
		opened = true;

		if(dataSpec.length != C.LENGTH_UNSET) {
			bytesRemaining = dataSpec.length;
		} else {
			Long contentLength = bufferFile.getContentLength();
			// The length the server declared, which is the whole song rather than what has
			// arrived of it. Without one the extractor is told nothing, which costs seeking
			// within a song that has not finished downloading.
			bytesRemaining = contentLength == null ? C.LENGTH_UNSET : Math.max(0, contentLength - position);
		}

		transferStarted(dataSpec);
		return bytesRemaining;
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		if(length == 0) {
			return 0;
		}
		if(bytesRemaining == 0) {
			return C.RESULT_END_OF_INPUT;
		}

		int toRead = bytesRemaining == C.LENGTH_UNSET
				? length
				: (int) Math.min(length, bytesRemaining);

		long waitingSince = 0;
		while(true) {
			int read = input.read(buffer, offset, toRead);
			if(read > 0) {
				position += read;
				if(bytesRemaining != C.LENGTH_UNSET) {
					bytesRemaining -= read;
				}
				bytesTransferred(read);
				return read;
			}

			// Nothing there right now. Whether that is the end of the song or a download
			// that has not caught up is the download's answer to give, not the file's.
			if(bufferFile.isWorkDone()) {
				return C.RESULT_END_OF_INPUT;
			}

			if(waitingSince == 0) {
				waitingSince = System.currentTimeMillis();
				Log.d(TAG, "Waiting for more of " + file + " past " + position);
			} else if(System.currentTimeMillis() - waitingSince > STALL_TIMEOUT_MS) {
				throw new IOException("Download stalled at " + position + " bytes of " + file);
			}

			// Nudges a download that has dropped out back into life, which is the same
			// thing the proxy did while it was blocked waiting.
			bufferFile.onResume();

			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException();
			}
		}
	}

	@Override
	public Uri getUri() {
		return uri;
	}

	@Override
	public void close() throws IOException {
		uri = null;
		try {
			if(input != null) {
				input.close();
			}
		} finally {
			input = null;
			file = null;
			if(opened) {
				opened = false;
				bufferFile.onStop();
				transferEnded();
			}
		}
	}

	/** Hands the engine a reader for one particular download. */
	public static class Factory implements DataSource.Factory {
		private final BufferFile bufferFile;

		public Factory(BufferFile bufferFile) {
			this.bufferFile = bufferFile;
		}

		@Override
		public DataSource createDataSource() {
			return new BufferFileDataSource(bufferFile);
		}
	}
}
