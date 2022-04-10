/*
 * Copyright 2020-2022 Atelier Misono, Inc. @ https://misono.app/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.misono.unit206.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import app.misono.unit206.task.Taskz;

import com.google.android.gms.tasks.Task;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 *
 *	TODO:	repeatMode: to be total duration count when repeat mode.
 */
@RequiresApi(17)
public final class MediaDecoder {
	private static final String	TAG	= "MediaDecoder";

	public Object tag;

	private final MediaExtractor extractor;
	private final Callback callback;
	private final Executor executor;
	private final Params params;

	private MovieSurface movieSurface;
	private Throwable throwable;
	private Surface surface;
	private boolean fDone;

	private MediaDecoder(Params params, Callback callback) {
		this.params = params != null ? params : new Params();
		this.callback = callback;
		extractor = new MediaExtractor();
		executor = Executors.newCachedThreadPool();
	}

	/**
	 *	Sets the MediaExtractor instance source to use.
	 *
	 *	@param extractor MediaExtractor
	 *	@param params Params
	 *	@param callback Callback
	 */
	public MediaDecoder(MediaExtractor extractor, Params params, Callback callback) {
		this.params = params != null ? params : new Params();
		this.callback = callback;
		this.extractor = extractor;
		executor = Executors.newCachedThreadPool();
	}

	/**
	 *	Sets the data source (file-path or http URL) to use.
	 *
	 * @param path			the path of the file, or the http URL of the stream
	 * @param callback Callback
	 */
	public MediaDecoder(String path, Params params, Callback callback) throws IOException {
		this(params, callback);
		extractor.setDataSource(path);
	}

	/**
	 *	Sets the data source (file-path or http URL) to use.
	 *
	 * @param path			the path of the file, or the http URL of the stream
	 * @param headers		the headers associated with the http request for the stream you want to play
	 * @param callback Callback
	 */
	public MediaDecoder(
		String path,
		Map<String, String> headers,
		Params params,
		Callback callback
	) throws IOException {
		this(params, callback);
		extractor.setDataSource(path, headers);
	}

	/**
	 *	Sets the data source (FileDescriptor) to use.
	 *	It is the caller's responsibility to close the file descriptor.
	 *	It is safe to do so as soon as this call returns.
	 *
	 * @param fd			the FileDescriptor for the file you want to extract from.
	 * @param callback Callback
	 */
	public MediaDecoder(FileDescriptor fd, Params params, Callback callback) throws IOException {
		this(params, callback);
		extractor.setDataSource(fd);
	}

	/**
	 *	Sets the data source (FileDescriptor) to use.
	 *	The FileDescriptor must be seekable (N.B. a LocalSocket is not seekable).
	 *	It is the caller's responsibility to close the file descriptor.
	 *	It is safe to do so as soon as this call returns.
	 *
	 * @param fd			the FileDescriptor for the file you want to extract from.
	 * @param offset		the offset into the file where the data to be extracted starts, in bytes
	 * @param length		the length in bytes of the data to be extracted
	 * @param callback Callback
	 */
	public MediaDecoder(
		FileDescriptor fd,
		long offset,
		long length,
		Params params,
		Callback callback
	) throws IOException {
		this(params, callback);
		extractor.setDataSource(fd, offset, length);
	}

	/**
	 *	Sets the data source as a content Uri.
	 *
	 * @param context		the Context to use when resolving the Uri
	 * @param uri			the Content URI of the data you want to extract from.
	 * @param headers		the headers to be sent together with the request for the data
	 * @param callback Callback
	 */
	public MediaDecoder(
		Context context,
		Uri uri,
		Map<String, String> headers,
		Params params,
		Callback callback
	) throws IOException {
		this(params, callback);
		extractor.setDataSource(context, uri, headers);
	}

	/**
	 *	Starts the decoder thread.
	 *	This method will return immediately.
	 */
	@NonNull
	public Task<Void> startTask() {
		return Taskz.call(executor, () -> {
			movieSurface = null;
			MediaCodec decoder = null;
			MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
			int n = extractor.getTrackCount();
			JasaTimer timer = JasaTimer.getInstance();
			JasaTimer.Callback timerCallback = obj -> {
Log.e(TAG, "TIMEOUT...");
				fDone	= true;
			};
			try {
				for (int i = 0; i < n; i++) {
					MediaFormat fmt = extractor.getTrackFormat(i);
					String mime = fmt.getString(MediaFormat.KEY_MIME);
					if (mime.startsWith("video/")) {
Log.e(TAG, "mime:" + mime);
						int mp4w = fmt.getInteger(MediaFormat.KEY_WIDTH);
						int mp4h = fmt.getInteger(MediaFormat.KEY_HEIGHT);
						if (surface == null) {
							int w = params.outWidth  == 0 ? mp4w : params.outWidth;
							int h = params.outHeight == 0 ? mp4h : params.outHeight;
							movieSurface = new MovieSurface(w, h);
						}
						extractor.selectTrack(i);
						extractor.seekTo(params.usecStart, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
						try {
							decoder	= MediaCodec.createDecoderByType(mime);
						} catch (IOException e) {
							continue;
						}
						try {
							if (surface == null) {
								if (movieSurface != null) {
									decoder.configure(fmt, movieSurface.getSurface(), null, 0);
								} else {
									decoder.configure(fmt, null, null, 0);
								}
							} else {
								decoder.configure(fmt, surface, null, 0);
							}
						} catch (IllegalStateException e) {
							Log.e(TAG, "This device does not support " + mime + " or the video size is bigger ...");
							decoder.stop();
							decoder.release();
							decoder	= null;
							if (movieSurface != null) {
								movieSurface.release();
								movieSurface	= null;
							}
							continue;
						}
						decoder.start();
						callback.codecinfo(MediaDecoder.this, fmt);
						ByteBuffer[] inbuf = decoder.getInputBuffers();
						int frameNo	= 0;
						boolean fEos = false;
						timer.start(1000, timerCallback, null);
						for ( ; ; ) {
							if (fDone) break;

							if (!fEos) {
								int index = decoder.dequeueInputBuffer(0);
								if (0 <= index) {
									ByteBuffer buf = inbuf[index];
									int size = extractor.readSampleData(buf, 0);
									if (params.repeatMode && size < 0 ) {
										extractor.seekTo(params.usecStart, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
										size = extractor.readSampleData(buf, 0);
									}
									if (size < 0) {
										fEos = true;
										decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
									} else {
										decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
										extractor.advance();
									}
								}
							}
							int index = decoder.dequeueOutputBuffer(info, 1000);
							if (index < 0) {
								switch (index) {
								case MediaCodec.INFO_TRY_AGAIN_LATER:
								case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
									break;
								case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
									fmt	= decoder.getOutputFormat();
Log.w(TAG, "INFO_OUTPUT_FORMAT_CHANGED:" + fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT));
									break;
								default:
									Log.w(TAG, "unknown dequeueOutputBuffer() " + index);
									break;
								}
							} else {
								int msec = (int)(info.presentationTimeUs / 1000);
								if (params.duration != 0 && params.duration * 1000 <= msec) {
Log.w(TAG, "BREAK: duration:" + (info.presentationTimeUs / 1000000));
									fDone = true;
									break;
								}
								if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
Log.w(TAG, "duration:" + msec + "msec");
									fDone = true;
									break;
								}
								timer.stop(timerCallback, null);
								timer.start(1000, timerCallback, null);
								decoder.releaseOutputBuffer(index, true);
								if (params.fps == 0 || frameNo * 1000 / params.fps <= msec) {
									try {
										if (movieSurface != null) {
											movieSurface.awaitNewImage();
											movieSurface.drawImage(true);
										}
										callback.decoded(MediaDecoder.this, frameNo, info);
										frameNo++;
									} catch (InterruptedException e) {
										setThrowable(e);
										break;
									}
								}
							}
						}
						timer.stop(timerCallback, null);
Log.w(TAG, "EXIT:" + frameNo);
						break;
					}
				}
			} finally {
				timer.stop(timerCallback, null);
				if (movieSurface != null) {
					movieSurface.release();
				}
				if (decoder != null) {
					decoder.stop();
					decoder.release();
				}
				extractor.release();
			}
			return null;
		});
	}

	private void setThrowable(Throwable e) {
if (e != null) e.printStackTrace();
		if (throwable == null) {
			throwable = e;
		}
	}

	public Throwable getThrowable() {
		return throwable;
	}

	public void setSurface(Surface surface) {
		this.surface = surface;
	}

	public byte[] getJpeg(int quality) {
		byte[] jpg = null;
		if (movieSurface != null) {
			jpg = movieSurface.getJpeg(quality);
		}
		return jpg;
	}

	public Bitmap getBitmap(Bitmap bitmap) {
		Bitmap rc = null;
		if (movieSurface != null) {
			rc = movieSurface.getBitmap(bitmap);
		}
		return rc;
	}

	public void finish() {
		fDone = true;
	}

	public void setTag(Object tag) {
		this.tag = tag;
	}

	public Object getTag() {
		return tag;
	}

	public static class Params {
		public boolean repeatMode;
		public long usecStart;
		public int outWidth, outHeight, duration, fps;

		public Params() {
		}
	}

	public interface Callback {
		void decoded(@NonNull MediaDecoder decoder, int frameNo, MediaCodec.BufferInfo info);
		void codecinfo(@NonNull MediaDecoder decoder, MediaFormat fmt);
	}
}