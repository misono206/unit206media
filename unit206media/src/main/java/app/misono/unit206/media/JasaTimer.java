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

import android.annotation.SuppressLint;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/**
 * The <tt>JasaTimer</tt> class provides the <a href='http://developer.android.com/reference/android/os/Handler.html'><tt>Handler</tt></a> class based timers.
 * This is "tì’eylan aEywa".
 */
public final class JasaTimer implements Closeable {
	private static final int MSG_ADD_TIMER = 0;
	private static final int MSG_REMOVE_TIMER = 1;
	private static final int MSG_TIMEOUT = 2;
	private static final int MSG_REMOVE_HANDLER = 3;
	private static final int MSG_FINISH = 4;

	private static JasaTimer me;

	private ConditionVariable signal;
	private List<TimerTuple> aList;
	private TimerTuple active;
	private Handler handler;

	private JasaTimer() {
		aList = new ArrayList<>();
		signal = new ConditionVariable();
		new Thread("JasaTimer") {
			@SuppressLint("HandlerLeak")
			@Override
			public void run() {
				Looper.prepare();
				handler = new Handler(Looper.myLooper()) {
					@Override
					public void handleMessage(Message msg) {
						TimerTuple tt, t;
						switch (msg.what) {
						case MSG_ADD_TIMER:
							tt = (TimerTuple)msg.obj;
							aList.add(tt);
							if (active == null || tt.timeout < active.timeout) {
								handler.removeMessages(MSG_TIMEOUT);
								active = tt;
								handler.sendEmptyMessageAtTime(MSG_TIMEOUT, tt.timeout);
							}
							break;
						case MSG_REMOVE_TIMER:
							tt = (TimerTuple)msg.obj;
							if (tt == active) {
								handler.removeMessages(MSG_TIMEOUT);
								active = null;
							}
							for (int i = 0; i < aList.size(); ) {
								t = aList.get(i);
								if (t.handler == tt.handler && t.what == tt.what && t.obj == tt.obj) {
									aList.remove(i);
									continue;
								}
								i++;
							}
							if (active == null) {
								nextTimerStart();
							}
							break;
						case MSG_REMOVE_HANDLER:
							Handler h = (Handler)msg.obj;
							if (h == active.handler) {
								handler.removeMessages(MSG_TIMEOUT);
								active = null;
							}
							for (int i = 0; i < aList.size(); ) {
								t = aList.get(i);
								if (t.handler == h) {
									aList.remove(i);
									continue;
								}
								i++;
							}
							if (active == null) {
								nextTimerStart();
							}
							break;
						case MSG_TIMEOUT:
							long now = SystemClock.uptimeMillis();
							for (int i = 0; i < aList.size(); ) {
								t = aList.get(i);
								if (t.timeout <= now) {
									if (t.handler != null) {
										Message.obtain(t.handler, t.what, t.obj).sendToTarget();
									} else if (t.callback != null) {
										t.callback.timeout(t.obj);
									}
									aList.remove(i);
									continue;
								}
								i++;
							}
							nextTimerStart();
							break;
						case MSG_FINISH:
							getLooper().quit();
							break;
						}
					}
				};
				signal.open();
				Looper.loop();
				signal = null;
				aList = null;
			}

			private void nextTimerStart() {
				active = null;
				int n = aList.size();
				for (int i = 0; i < n; i++) {
					TimerTuple t = aList.get(i);
					if (active == null || t.timeout < active.timeout) {
						active = t;
					}
				}
				if (active != null) {
					handler.sendEmptyMessageAtTime(MSG_TIMEOUT, active.timeout);
				}
			}
		}.start();
		signal.block();
	}

	/**
	 * Returns the instance of <tt>JasaTimer</tt>.
	 */
	public static JasaTimer getInstance() {
		if (me == null) me = new JasaTimer();

		return me;
	}

	public static JasaTimer getMe() {
		return me;
	}

	/**
	 * Enqueue a message into the message queue after all pending messages before (current time + <tt>msec</tt>).
	 * You will receive it in <a href='http://developer.android.com/reference/android/os/Handler.html#handleMessage(android.os.Message)'><tt>handleMessage(Message)</tt></a>, in the thread attached to the <tt>aHandler</tt> handler.
	 */
	public void start(int msec, Handler aHandler, int what) {
		Handler h = handler;
		if (h != null) {
			TimerTuple t = new TimerTuple(aHandler, SystemClock.uptimeMillis() + msec, what, null);
			Message.obtain(h, MSG_ADD_TIMER, t).sendToTarget();
		}
	}

	/**
	 * Enqueue a message into the message queue after all pending messages before (current time + <tt>msec</tt>).
	 * You will receive it in <a href='http://developer.android.com/reference/android/os/Handler.html#handleMessage(android.os.Message)'><tt>handleMessage(Message)</tt></a>, in the thread attached to the <tt>aHandler</tt> handler.
	 */
	public void start(int msec, Handler aHandler, int what, Object obj) {
		Handler h = handler;
		if (h != null) {
			TimerTuple t = new TimerTuple(aHandler, SystemClock.uptimeMillis() + msec, what, obj);
			Message.obtain(h, MSG_ADD_TIMER, t).sendToTarget();
		}
	}

	/**
	 * Enqueue a message into the message queue after all pending messages before (current time + <tt>msec</tt>).
	 * You will receive it in <a href='http://developer.android.com/reference/android/os/Handler.html#handleMessage(android.os.Message)'><tt>handleMessage(Message)</tt></a>, in the thread attached to the <tt>aHandler</tt> handler.
	 */
	public void start(int msec, Callback callback, Object obj) {
		Handler h = handler;
		if (h != null) {
			TimerTuple t = new TimerTuple(callback, SystemClock.uptimeMillis() + msec, obj);
			Message.obtain(h, MSG_ADD_TIMER, t).sendToTarget();
		}
	}

	/**
	 * Remove any pending timers with code 'what'. 
	 */
	public void stop(Handler aHandler, int what) {
		Handler h = handler;
		if (h != null) {
			TimerTuple t = new TimerTuple(aHandler, 0, what, null);
			Message.obtain(h, MSG_REMOVE_TIMER, t).sendToTarget();
		}
	}

	/**
	 * Remove any pending timers with code 'what' and 'obj'. 
	 */
	public void stop(Handler aHandler, int what, Object obj) {
		Handler h = handler;
		if (h != null) {
			TimerTuple t = new TimerTuple(aHandler, 0, what, obj);
			Message.obtain(h, MSG_REMOVE_TIMER, t).sendToTarget();
		}
	}

	/**
	 * Remove any pending timers. 
	 */
	public void stop(Handler aHandler) {
		Handler h = handler;
		if (h != null) {
			Message.obtain(h, MSG_REMOVE_HANDLER, aHandler).sendToTarget();
		}
	}

	/**
	 * Remove any pending timers with code 'callback' and 'obj'. 
	 */
	public void stop(Callback callback, Object obj) {
		Handler h = handler;
		if (h != null) {
			TimerTuple t = new TimerTuple(callback, 0, obj);
			Message.obtain(h, MSG_REMOVE_TIMER, t).sendToTarget();
		}
	}

	//	TODO
	//	RunnableTimer extends Runnable
	private static class TimerTuple {
		private final Object obj;
		private final long timeout;

		private Callback callback;
		private Handler handler;
		private int what;

		private TimerTuple(Handler aHandler, long timeout, int what, Object obj) {
			this.handler = aHandler;
			this.timeout = timeout;
			this.what = what;
			this.obj = obj;
		}

		private TimerTuple(Callback callback, long timeout, Object obj) {
			this.callback = callback;
			this.timeout = timeout;
			this.obj = obj;
		}
	}

	public interface Callback {
//		@WorkerThread
		void timeout(Object obj);
	}

	@Override
	public synchronized void close() {
		Handler h = handler;
		if (h != null) {
			handler = null;
			me = null;
			h.sendEmptyMessage(MSG_FINISH);
		}
	}
}

