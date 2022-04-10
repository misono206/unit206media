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

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import app.misono.unit206.task.Taskz;

import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 *	Decode a movie file.
 */
@RequiresApi(18)
public final class MovieDecoder implements Closeable {
	private static final String	TAG = "MovieDecoder";

	private final OnVideoInfo onVideo;
	private final OnDecoded onDecoded;
	private final Executor executor;
	private final Params params;

	private MediaExtractor extractor;
	private MediaDecoder decoder;
	private Throwable throwable;
	private Bitmap bitmap;
	private boolean realtime;
	private long tickStart;

	private MovieDecoder(
		@Nullable Params params,
		@Nullable OnVideoInfo onVideo,
		@NonNull OnDecoded onDecoded
	) {
		this.params = params != null ? params : new Params();
		this.onVideo = onVideo;
		this.onDecoded = onDecoded;
		executor = Executors.newCachedThreadPool();
		extractor = new MediaExtractor();
	}

	/**
	 *	Sets the data source (file-path or http URL) to use.
	 */
	public MovieDecoder(
		@NonNull String inPath,
		@Nullable Params params,
		@Nullable OnVideoInfo onVideo,
		@NonNull OnDecoded onDecoded
	) throws IOException {
		this(params, onVideo, onDecoded);
		extractor.setDataSource(inPath);
	}

	/**
	 *	Sets the data source (file-path or http URL) to use.
	 *
	 * @param headers	the headers associated with the http request for the stream you want to play
	 */
	public MovieDecoder(
		@NonNull String inPath,
		@Nullable Map<String, String> headers,
		@Nullable Params params,
		@Nullable OnVideoInfo onVideo,
		@NonNull OnDecoded onDecoded
	) throws IOException {
		this(params, onVideo, onDecoded);
		extractor.setDataSource(inPath, headers);
	}

	/**
	 *	Sets the data source (FileDescriptor) to use.
	 *	It is the caller's responsibility to close the file descriptor.
	 *	It is safe to do so as soon as this call returns.
	 *
	 * @param fd	the FileDescriptor for the file you want to extract from.
	 */
	public MovieDecoder(
		@NonNull FileDescriptor fd,
		@Nullable Params params,
		@Nullable OnVideoInfo onVideo,
		@NonNull OnDecoded onDecoded
	) throws IOException {
		this(params, onVideo, onDecoded);
		extractor.setDataSource(fd);
	}

	/**
	 *	Sets the data source (FileDescriptor) to use.
	 *	The FileDescriptor must be seekable (N.B. a LocalSocket is not seekable).
	 *	It is the caller's responsibility to close the file descriptor.
	 *	It is safe to do so as soon as this call returns.
	 *
	 * @param fd	the FileDescriptor for the file you want to extract from.
	 * @param offset	the offset into the file where the data to be extracted starts, in bytes
	 * @param length	the length in bytes of the data to be extracted
	 */
	public MovieDecoder(
		@NonNull FileDescriptor fd,
		long offset,
		long length,
		@Nullable Params params,
		@Nullable OnVideoInfo onVideo,
		@NonNull OnDecoded onDecoded
	) throws IOException {
		this(params, onVideo, onDecoded);
		extractor.setDataSource(fd, offset, length);
	}

	/**
	 *	Sets the data source as a content Uri.
	 *
	 * @param context	the Context to use when resolving the Uri
	 * @param uri	the Content URI of the data you want to extract from.
	 * @param headers	the headers to be sent together with the request for the data
	 */
	public MovieDecoder(
		@NonNull Context context,
		@NonNull Uri uri,
		@Nullable Map<String, String> headers,
		@Nullable Params params,
		@Nullable OnVideoInfo onVideo,
		@NonNull OnDecoded onDecoded
	) throws IOException {
		this(params, onVideo, onDecoded);
		extractor.setDataSource(context, uri, headers);
	}

	public void setRealtimeMode(boolean realtime) {
		this.realtime = realtime;
	}

	@AnyThread
	@NonNull
	public Task<Void> startTask(@Nullable CancellationToken cancel) {
		return Taskz.call(executor, cancel, () -> {
			try {
				int n = extractor.getTrackCount();
				for (int i = 0; i < n; i++) {
					MediaFormat fmt = extractor.getTrackFormat(i);
					String mime = fmt.getString(MediaFormat.KEY_MIME);
					if (mime.startsWith("video/")) {
						extractor.selectTrack(i);
						extractor.seekTo(params.dec.usecStart, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
						decodeVideo();
						break;
					}
				}
			} finally {
				if (extractor != null) {
					extractor.release();
					extractor = null;
				}
			}
			return null;
		});
	}

	@Override
	public void close() {
		if (extractor != null) {
			extractor.release();
			extractor = null;
		}
		if (decoder != null) {
			decoder.finish();
			decoder = null;
		}
	}

	@WorkerThread
	private void decodeVideo() throws Exception {
		tickStart = 0;
		decoder = new MediaDecoder(extractor, params.dec, new MediaDecoder.Callback() {
			@Override
			public void decoded(@NonNull MediaDecoder dec, int frameNo, MediaCodec.BufferInfo info) {
				int msec = (int)(info.presentationTimeUs / 1000);
				if (params.dec.duration != 0 && params.dec.duration <= msec / 1000) {
					dec.finish();
				} else {
					if (realtime) {
						long now = System.currentTimeMillis();
						if (tickStart == 0) {
							tickStart = now;
						} else {
							long sleep = msec - (now - tickStart);
							if (0 < sleep) {
								try {
									Thread.sleep(sleep);
								} catch (InterruptedException e) {
									// nop
								}
							}
						}
					}
					bitmap = dec.getBitmap(bitmap);
					onDecoded.decoded(bitmap, info.presentationTimeUs);
				}
			}

			@Override
			public void codecinfo(@NonNull MediaDecoder decoder, MediaFormat src) {
				OnVideoInfo info = onVideo;
				if (info != null) {
					info.codecinfo(src);
				}
			}
		});
		Tasks.await(decoder.startTask());
	}

	private void setThrowable(Throwable e) {
if (e != null) e.printStackTrace();
		if (throwable == null) {
			throwable	= e;
		}
	}

	public Throwable getThrowable() {
		return throwable;
	}

	public interface OnVideoInfo {
		void codecinfo(@NonNull MediaFormat info);
	}

	public interface OnDecoded {
		void decoded(@NonNull Bitmap bitmap, long usec);
	}

	public static final class Params {
		public MediaDecoder.Params dec;

		public Params() {
			dec = new MediaDecoder.Params();
		}

		public Params(MediaDecoder.Params decParams) {
			dec = decParams;
		}
	}

}
