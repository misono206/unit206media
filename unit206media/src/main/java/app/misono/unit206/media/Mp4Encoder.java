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
import android.media.MediaFormat;
import android.renderscript.RSInvalidStateException;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import app.misono.unit206.debug.Log2;
import app.misono.unit206.misc.ThreadGate;
import app.misono.unit206.task.SingletonTask;
import app.misono.unit206.task.Taskz;

import com.google.android.gms.tasks.Task;

import java.io.Closeable;
import java.io.File;
import java.util.concurrent.Executor;

/**
 *	Creates a mp4 video(video/avc) file.
 */
public final class Mp4Encoder implements Closeable {
	private static final String	TAG = "Mp4Encoder";

	private final SingletonTask singleton;
	private final Executor executor;
	private final int yuv420color;

	private VideoEncoderParams params;
	private Yuv420PlanarAlone yuv420;
	private MediaEncoder encoder;
	private Throwable throwable;
	private int frames;

	public Mp4Encoder(@NonNull Context ctx, @NonNull Executor executor) {
		this.executor = executor;
		singleton = new SingletonTask();
		yuv420 = new Yuv420PlanarAlone(ctx);
		yuv420color = MediaColorFormat.getYuv420ColorFormat(MediaColorFormat.getEncoderColorFormat());
	}

	@AnyThread
	@NonNull
	public Task<Void> startTask(
		int width,
		int height,
		@Nullable VideoEncoderParams params,
		@NonNull File mp4
	) {
		this.params = params != null ? params : new VideoEncoderParams();
		return Taskz.call(executor, () -> {
			ThreadGate done = new ThreadGate();
			encoder = new MediaEncoder(new MediaEncoder.Callback() {
				@Override
				public void done(MediaEncoder enc, int frames) {
					Mp4Encoder.this.frames = frames;
					new Thread(() -> {
						enc.close();
						done.open();
					}).start();
				}

				@Override
				@WorkerThread
				public void fatalError(MediaEncoder encoder, Throwable e) {
					Log2.e(TAG, "fatalError:");
					e.printStackTrace();
				}

				@Override
				@WorkerThread
				public void canceled(MediaEncoder encoder) {
				}

				/**
				 *	It may recycle the byte array of payload.
				 */
				@Override
				@WorkerThread
				public void recyclePayload(MediaEncoder encoder, byte[] payload) {
				}

				@Override
				@WorkerThread
				public void closed(MediaEncoder encoder) {
				}
			});
			MediaFormat fmt = MediaFormat.createVideoFormat(params.mimeType, width, height);
			fmt.setInteger(MediaFormat.KEY_FRAME_RATE, params.fps);
			fmt.setInteger(MediaFormat.KEY_BIT_RATE, params.bitRate);
			fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaColorFormat.getEncoderColorFormat());
			fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, params.intervalKeyFrame);
			encoder.start(mp4.getAbsolutePath(), fmt);
			done.block();
			return null;
		});
	}

	@AnyThread
	@NonNull
	public Task<Void> inputPayloadTask(@NonNull Bitmap bitmap, long usec) {
		return singleton.call(executor, () -> {
			byte[] yuv = yuv420.convert(bitmap, yuv420color);
			encoder.inputPayload(yuv, usec);
			return null;
		});
	}

	@AnyThread
	@NonNull
	public Task<Void> endOfPayloadTask() {
		return singleton.call(executor, () -> {
			encoder.endOfPayload();
			return null;
		});
	}

	@Override
	public void close() {
		if (yuv420 != null) {
			try {
				yuv420.close();
			} catch (RSInvalidStateException e) {
				// nop
			}
			yuv420 = null;
		}
	}

}
