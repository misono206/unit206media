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

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Message;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.Runnable;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.util.Vector;

/**
 *	Creates a mp4 video file from YUV data.
 */
@RequiresApi(18)
public final class MediaEncoder implements Closeable {
	private static final String	TAG = "MediaEncoder";

	private EncoderThread encThread;
	private MediaMuxer muxer;
	private Callback callback;
	private boolean fGivenMuxer, fCancel;

	public MediaEncoder(Callback callback) {
		this.callback = callback;
		encThread = new EncoderThread();
	}

	/**
	 *	Starts encoding.
	 */
	public void start(String outPath, MediaFormat fmt) {
		fGivenMuxer	= false;
		checkClosed().start(outPath, fmt);
	}

	/**
	 *	Starts encoding with given MediaMuxer.
	 */
	public void start(MediaMuxer muxer, MediaFormat fmt) {
		fGivenMuxer = true;
		this.muxer = muxer;
		checkClosed().start(null, fmt);
	}

	private class EncoderThread extends MessageThread {
		private static final int MSG_QUIT = 0;
		private static final int MSG_START = 1;
		private static final int MSG_CANCEL = 2;
		private static final int MSG_ERROR = 3;

		private static final int STATE_IDLE = 0;
		private static final int STATE_STARTED = 1;

		private final Vector<SoftReference<InputPayload>> poolPayload;

		private MediaCodec.BufferInfo info;
		private MediaCodec encoder;
		private String outPath;
		private int state, frames, videoTrack;

		private EncoderThread() {
			super();
			state = STATE_IDLE;
			poolPayload = new Vector<>();
		}

		private void start(String outPath, MediaFormat fmt) {
			this.outPath = outPath;
			sendMessage(MSG_START, fmt);
		}

		@Override
		public void handleMessage(Message msg) {
			switch (state) {
			case STATE_IDLE:
				stateIdle(msg);
				break;
			case STATE_STARTED:
				stateStarted(msg);
				break;
			}
		}

		private void notifyFatalError(Throwable e) {
			if (callback != null) {
				callback.fatalError(MediaEncoder.this, e);
			}
			clean(false);
		}

		private void notifyCancel() {
			if (callback != null) {
				callback.canceled(MediaEncoder.this);
			}
			clean(false);
		}

		@RequiresApi(21)
		private void setCallback21() {
			encoder.setCallback(new MediaCodec.Callback() {
				@Override
				public void onInputBufferAvailable(MediaCodec codec, int index) {
				}

				@Override
				public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
				}

				@Override
				public void onError(MediaCodec codec, MediaCodec.CodecException e) {
					sendMessage(MSG_ERROR, e);
				}

				@Override
				public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
				}
			});
		}

		private void stateIdle(Message msg) {
			switch (msg.what) {
			case MSG_START:
				try {
					fCancel = false;
					if (!fGivenMuxer) {
						deleteOutputFile();				//	Delete by myself because MediaMuxer does not truncate the file.
						muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
					}
					info = new MediaCodec.BufferInfo();
					MediaFormat fmt = (MediaFormat)msg.obj;
					encoder = MediaCodec.createEncoderByType(fmt.getString(MediaFormat.KEY_MIME));
					if (21 <= Build.VERSION.SDK_INT) {
//	TODO						setCallback21();
					}
					encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
					encoder.start();
					state = STATE_STARTED;
				} catch (IOException e) {
					notifyFatalError(e);
				}
				break;
			}
		}

		private void stateStarted(Message msg) {
			switch (msg.what) {
			case MSG_START:
				notifyFatalError(new IllegalStateException("MediaEncoder is already started..."));
				break;
			case MSG_CANCEL:
				notifyCancel();
				break;
			case MSG_QUIT:
				quit();
				break;
			case MSG_ERROR:
				notifyFatalError((Throwable)msg.obj);
				break;
			}
		}

		private void clean(boolean success) {
			if (encoder != null) {
				encoder.stop();
				encoder.release();
				encoder = null;
			}
			if (!fGivenMuxer && muxer != null) {
				try {
					muxer.stop();
				} catch (IllegalStateException e) {
					//	0-duration samples found
				}
				try {
					muxer.release();
				} catch (IllegalStateException e) {
					//	0-duration samples found
				}
			}
			muxer = null;
			if (!success) {
				deleteOutputFile();
				outPath = null;
			}
			state = STATE_IDLE;
		}

		@Override
		public void done() {
			clean(false);
			poolPayload.clear();
			if (callback != null) {
				callback.closed(MediaEncoder.this);
			}
		}

		private void deleteOutputFile() {
			if (outPath != null) {
				new File(outPath).delete();
			}
		}

		private void checkOutput(boolean drain) {
			boolean fTryAgainLater = false;
			for ( ; ; ) {
				int index = encoder.dequeueOutputBuffer(info, 0);
				if (index < 0) {
					switch (index) {
					case MediaCodec.INFO_TRY_AGAIN_LATER:
						fTryAgainLater = true;
						break;
					case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
						break;
					case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
						MediaFormat fmt = encoder.getOutputFormat();
						videoTrack = muxer.addTrack(fmt);
						muxer.start();
						break;
					default:
						Log.w(TAG, "unknown dequeueOutputBuffer() " + index);
						break;
					}
				} else {
					ByteBuffer[] outbuf = encoder.getOutputBuffers();
					ByteBuffer buf = outbuf[index];
					muxer.writeSampleData(videoTrack, buf, info);
					frames++;
					encoder.releaseOutputBuffer(index, false);
					if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						clean(true);
						if (callback != null) {
							callback.done(MediaEncoder.this, frames);
						}
						break;
					}
				}
				if (!drain && fTryAgainLater) break;
			}
		}

		private InputPayload obtainInputPayload() {
			try {
				for ( ; ; ) {
					if (poolPayload.size() == 0) break;

					SoftReference<InputPayload> ref = poolPayload.remove(0);
					InputPayload rc = ref.get();
					ref.clear();
					if (rc != null) return rc;
				}
			} catch (IndexOutOfBoundsException e) {
				// nop
			}

			return new InputPayload();
		}

		private void recycleInputPayload(InputPayload payload) {
			payload.clear();
			poolPayload.add(new SoftReference<>(payload));
		}

		private class InputPayload implements Runnable {
			private byte[]	buf;
			private long	usecSampleTime;

			private InputPayload() {
			}

			private void init(byte[] buf, long usecSampleTime) {
				this.buf = buf;
				this.usecSampleTime = usecSampleTime;
			}

			private void clear() {
				buf	= null;
			}

			@Override
			public void run() {
				if (state == STATE_STARTED) {
					ByteBuffer[] inbuf = encoder.getInputBuffers();
					for ( ; ; ) {
						if (fCancel) {
							notifyCancel();
							break;
						}
						checkOutput(false);
						int index = encoder.dequeueInputBuffer(1000);
						if (0 <= index) {
							if (buf != null) {
								ByteBuffer bb = inbuf[index];
								bb.put(buf, 0, buf.length);
								encoder.queueInputBuffer(index, 0, buf.length, usecSampleTime, 0);
							} else {
								encoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
								checkOutput(true);
							}
							break;
						}
					}
				}
				if (callback != null && buf != null) {
					callback.recyclePayload(MediaEncoder.this, buf);
				}
				recycleInputPayload(this);
			}
		}

		private void inputPayload(byte[] buf, long usecSampleTime) {
			InputPayload payload = obtainInputPayload();
			payload.init(buf, usecSampleTime);
			post(payload);
		}
	}

	private EncoderThread checkClosed() {
		EncoderThread th = encThread;
		if (th == null) {
			throw new IllegalStateException("MediaEncoder is already closed...");
		}
		return th;
	}

	public void inputPayload(byte[] buf, long usecSampleTime) {
		checkClosed().inputPayload(buf, usecSampleTime);
	}

	public void cancel() {
		fCancel = true;
		checkClosed().sendMessage(EncoderThread.MSG_CANCEL);
	}

	public void endOfPayload() {
		checkClosed().inputPayload(null, 0);
	}

	@Override
	public void close() {
		callback = null;
		EncoderThread th = encThread;
		if (th != null) {
			encThread = null;
			th.sendMessage(EncoderThread.MSG_QUIT);
		}
	}

	public static MediaFormat createVideoFormat(String mime, int width, int height, int fps) {
		int wh = Math.min(width, height);
		//
		//	http://www.lighterra.com/papers/videoencodingh264/
		//
		int bitRate;
		if (wh <= 240) {
			bitRate	= 576 * 1000;
		} else if (wh <= 360) {
			bitRate	= 896 * 1000;
		} else if (wh <= 432) {
			bitRate	= 1088 * 1000;
		} else if (wh <= 480) {
			bitRate	= 1536 * 1000;
		} else if (wh <= 576) {
			bitRate	= 2176 * 1000;
		} else if (wh <= 720) {
			bitRate	= 3072 * 1000;
		} else if (wh <= 1080) {
			bitRate	= 7552 * 1000;
		} else {
			bitRate	= 20000 * 1000;
		}
		MediaFormat	fmt = MediaFormat.createVideoFormat(mime, width, height);
		fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
		fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
		fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaColorFormat.getEncoderColorFormat());
		fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
		return fmt;
	}

	public interface Callback {
		@WorkerThread
		void done(MediaEncoder encoder, int frames);
		@WorkerThread
		void fatalError(MediaEncoder encoder, Throwable e);
		@WorkerThread
		void canceled(MediaEncoder encoder);

		/**
		 *	It may recycle the byte array of payload.
		 */
		@WorkerThread
		void recyclePayload(MediaEncoder encoder, byte[] payload);
		@WorkerThread
		void closed(MediaEncoder encoder);
	}
}
