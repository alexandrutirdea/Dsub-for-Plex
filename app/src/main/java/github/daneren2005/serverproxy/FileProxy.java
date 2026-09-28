/*
	This file is part of ServerProxy.
	SocketProxy is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	Subsonic is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU General Public License for more details.
	You should have received a copy of the GNU General Public License
	along with Subsonic. If not, see <http://www.gnu.org/licenses/>.
	Copyright 2014 (C) Scott Jackson
*/

package github.daneren2005.serverproxy;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;

import android.content.Context;
import android.util.Log;

public class FileProxy extends ServerProxy {
	private static final String TAG = FileProxy.class.getSimpleName();

	public FileProxy(Context context) {
		super(context);
	}

	protected ProxyTask getTask(Socket client) {
		return new StreamFileTask(client);
	}

	protected class StreamFileTask extends ProxyTask {
		File file;

		public StreamFileTask(Socket client) {
			super(client);
		}

		@Override
		public boolean processRequest() {
			if(!super.processRequest()) {
				return false;
			}

			Log.i(TAG, "Processing request for file " + path);
			file = getFile(path);
			if (!file.exists()) {
				Log.e(TAG, "File " + path + " does not exist");
				return false;
			}

			// Make sure to not try to read past where the file is downloaded
			if(cbSkip != 0 && cbSkip >= file.length()) {
				return false;
			}

			return true;
		}

		File getFile(String path) {
			return new File(path);
		}

		Long getContentLength() {
			return file.length();
		}
		long getFileSize() {
			return file.length();
		}

		@Override
		public void run() {
			Long contentLength = getContentLength();

			// Create HTTP header
			String headers;
			if(cbSkip == 0) {
				headers = "HTTP/1.0 200 OK\r\n";
			} else {
				headers = "HTTP/1.0 206 OK\r\n";
				headers += "Content-Range: bytes " + cbSkip + "-" + (file.length() - 1) + "/";
				if(contentLength == null) {
					headers += "*";
				} else {
					headers += contentLength;
				}
				headers += "\r\n";

				Log.i(TAG, "Streaming starts from: " + cbSkip);
			}

			headers += "Content-Type: " + getContentType(file) + "\r\n";

			long fileSize;
			if(contentLength == null) {
				fileSize = getFileSize();
			} else {
				fileSize = contentLength;
				if(cbSkip > 0) {
					headers += "Content-Length: " + (fileSize - cbSkip) + "\r\n";
				} else {
					headers += "Content-Length: " + fileSize + "\r\n";
				}
				headers += "Accept-Ranges: bytes \r\n";
			}
			Log.i(TAG, "Streaming fileSize: " + fileSize);

			headers += "Connection: close\r\n";
			headers += "\r\n";

			long cbToSend = fileSize - cbSkip;
			long cbSent = 0;
			// Told apart from the total so a renderer that stops reading and drops the
			// connection a minute later can be told from one that is cut off mid-flow.
			long lastSend = System.currentTimeMillis();
			OutputStream output = null;
			byte[] buff = new byte[64 * 1024];
			try {
				output = new BufferedOutputStream(client.getOutputStream(), 32*1024);
				output.write(headers.getBytes());

				// Make sure to have file lock
				onStart();

				// Loop as long as there's stuff to send
				while (isRunning && !client.isClosed()) {
					onResume();

					// See if there's more to send
					int cbSentThisBatch = 0;
					if (file.exists()) {
						FileInputStream input = new FileInputStream(file);
						input.skip(cbSkip);
						int cbToSendThisBatch = input.available();
						while (cbToSendThisBatch > 0) {
							int cbToRead = Math.min(cbToSendThisBatch, buff.length);
							int cbRead = input.read(buff, 0, cbToRead);
							if (cbRead == -1) {
								break;
							}
							cbToSendThisBatch -= cbRead;
							cbToSend -= cbRead;
							output.write(buff, 0, cbRead);
							output.flush();
							cbSkip += cbRead;
							cbSentThisBatch += cbRead;
							cbSent += cbRead;
							lastSend = System.currentTimeMillis();
						}
						input.close();
					}

					// Done regardless of whether or not it thinks it is
					if(isWorkDone()) {
						break;
					}

					// If we did nothing this batch, block for a second
					if (cbSentThisBatch == 0) {
						Log.d(TAG, "Blocking until more data appears (" + cbToSend + ")");
						Thread.sleep(1000);
					}
				}

				Log.i(TAG, "Sent " + cbSent + " of " + (fileSize - (cbSkip - cbSent)) + " bytes");

				// Release file lock, use of stream proxy means nothing else is using it
				onStop();
			}
			catch (SocketException socketException) {
				Log.e(TAG, "Client closed after " + cbSent + " of " + (fileSize - (cbSkip - cbSent))
						+ " bytes, " + (System.currentTimeMillis() - lastSend) + "ms after the last one went out");

				// Release file lock, use of stream proxy means nothing else is using it
				onStop();
			}
			catch (Exception e) {
				Log.e(TAG, "Exception thrown from streaming task:");
				Log.e(TAG, e.getClass().getName() + " : " + e.getLocalizedMessage());
			}

			// Cleanup
			try {
				if (output != null) {
					output.close();
				}
				client.close();
			}
			catch (IOException e) {
				Log.e(TAG, "IOException while cleaning up streaming task:");
				Log.e(TAG, e.getClass().getName() + " : " + e.getLocalizedMessage());
			}
		}

		/**
		 * Worked out from the name, there being nothing else to go on.
		 *
		 * Renderers do act on this - a Sonos speaker will skip a track whose type it does
		 * not believe it can play - so the common types are named rather than everything
		 * but mp3 being left to a renderer's guess.
		 */
		String getContentType(File file) {
			String name = file.getPath();
			int index = name.lastIndexOf('.');
			String ext = index == -1 ? "" : name.substring(index + 1).toLowerCase();

			if("mp3".equals(ext)) {
				return "audio/mpeg";
			} else if("m4a".equals(ext) || "mp4".equals(ext) || "aac".equals(ext)) {
				return "audio/mp4";
			} else if("flac".equals(ext)) {
				return "audio/flac";
			} else if("ogg".equals(ext) || "opus".equals(ext)) {
				return "application/ogg";
			} else if("wav".equals(ext)) {
				return "audio/wav";
			}
			return "application/octet-stream";
		}

		public void onStart() {

		}
		public void onStop() {

		}
		public void onResume() {

		}
		public boolean isWorkDone() {
			return cbSkip >= file.length();
		}
	}
}
