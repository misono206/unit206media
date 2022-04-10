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
import android.os.Messenger;

import androidx.annotation.WorkerThread;

import java.util.concurrent.atomic.AtomicInteger;

/**
 *	Provides a messaging thread with handler.
 */
@Deprecated
public abstract class MessageThread {
	private ConditionVariable signal;
	private AtomicInteger count;
	private Handler handler;

	public MessageThread() {
		count	= new AtomicInteger();
		signal	= new ConditionVariable();
		new Thread() {
			@SuppressLint("HandlerLeak")
			@Override
			public void run() {
				Looper.prepare();
				handler	= new Handler() {
					@Override
					public void handleMessage(Message msg) {
						SignalDesc	s;

						s	= null;
						if (msg.obj instanceof SignalDesc) {
							s		= (SignalDesc)msg.obj;
							msg.obj	= s.obj;
						}
						MessageThread.this.handleMessage(msg);
						if (s != null) {
							s.send();
						}
					}
				};
				signal.open();
				Looper.loop();
				done();
				if (signal != null) {
					signal.open();
				}
			}
		}.start();
		signal.block();
		signal	= null;
	}

	public MessageThread(int what) {
		this();
		sendMessage(what);
	}

	public MessageThread(int what, Object obj) {
		this();
		sendMessage(what, obj);
	}

	/**
	 *	Pushes a message onto the end of the message queue after all pending messages before the current time.
	 */
	public MessageThread(Message msg) {
		this();
		sendMessage(msg);
	}

	public void quitSync(int msgQuit) {
		quitSync(msgQuit, 0L);
	}

	public synchronized void quitSync(int msgQuit, long msecTimeout) {
		signal	= new ConditionVariable();
		sendMessage(msgQuit);
		if (msecTimeout == 0L) {
			signal.block();
		} else {
			signal.block(msecTimeout);
		}
		signal	= null;
	}

	/**
	 *	Creates a new Messenger pointing to the Handler.
	 */
	public Messenger createMessenger() {
		return new Messenger(handler);
	}

	/**
	 *	Sends a Message containing only the what value.
	 */
	public Handler sendMessage(int what) {
		Handler h;

		if ((h = handler) != null) {
			h.sendEmptyMessage(what);
		}

		return h;
	}

	/**
	 *	Sends a Message containing only the what value.
	 */
	public Handler sendMessageSync(int what) {
		SignalDesc	s;
		Handler h;

		if ((h = handler) != null) {
			s	= new SignalDesc(null);
			Message.obtain(h, what, s).sendToTarget();
			s.await();
		}

		return h;
	}


	/**
	 *	Sends a Message containing only the what value, to be delivered after the specified amount of time elapses.
	 */
	public Handler sendMessageDelayed(int what, long msec) {
		Handler h;

		if ((h = handler) != null) {
			h.sendEmptyMessageDelayed(what, msec);
		}

		return h;
	}

	public Handler sendMessageDelayed(int what, int arg1, int arg2, Object obj, long msec) {
		Handler h;

		if ((h = handler) != null) {
			h.sendMessageDelayed(Message.obtain(h, what, arg1, arg2, obj), msec);
		}

		return h;
	}

	public Handler sendMessage(int what, Object obj) {
		Handler h;

		if ((h = handler) != null) {
			Message.obtain(h, what, obj).sendToTarget();
		}

		return h;
	}

	public Handler sendMessageSync(int what, Object obj) {
		SignalDesc	s;
		Handler h;

		if ((h = handler) != null) {
			s	= new SignalDesc(obj);
			Message.obtain(h, what, s).sendToTarget();
			s.await();
		}

		return h;
	}

	public Handler sendMessage(int what, int arg1, int arg2, Object obj) {
		Handler h;

		if ((h = handler) != null) {
			Message.obtain(h, what, arg1, arg2, obj).sendToTarget();
		}

		return h;
	}

	public Handler sendMessageSync(int what, int arg1, int arg2, Object obj) {
		SignalDesc	s;
		Handler h;

		if ((h = handler) != null) {
			s	= new SignalDesc(obj);
			Message.obtain(h, what, arg1, arg2, s).sendToTarget();
			s.await();
		}

		return h;
	}

	/**
	 *	Pushes a message onto the end of the message queue after all pending messages before the current time.
	 */
	public Handler sendMessage(Message msg) {
		Handler h;

		if ((h = handler) != null && msg != null) {
			h.sendMessage(msg);
		}

		return h;
	}

	/**
	 *	Removes any pending posts of messages with code 'what' that are in the message queue.
	 */
	public void removeMessages(int what) {
		Handler h;

		if ((h = handler) != null) {
			h.removeMessages(what);
		}
	}

	/**
	 *	Removes any pending posts of messages with code 'what' and whose obj is 'object' that are in the message queue.
	 *	If object is null, all messages will be removed.
	 */
	public void removeMessages(int what, Object obj) {
		Handler h;

		if ((h = handler) != null) {
			h.removeMessages(what, obj);
		}
	}

	/**
	 *	Causes the Runnable r to be added to the message queue.
	 */
	public void post(Runnable r) {
		Handler h;

		if ((h = handler) != null) {
			h.post(r);
		}
	}

	public void decrement() {
		count.decrementAndGet();
	}

	public void increment() {
		count.incrementAndGet();
	}

	public Handler getHandler() {
		return handler;
	}

	public Looper getLooper() {
		Handler h;
		Looper rc;

		rc	= null;
		if ((h = handler) != null) {
			rc	= h.getLooper();
		}

		return rc;
	}

	public int count() {
		return count.intValue();
	}

	/**
	 *	Quits the looper.
	 */
	protected void quit() {
		Handler h;

		if ((h = handler) != null) {
			handler	= null;
			h.getLooper().quit();
		}
	}

	private static class SignalDesc {
		private ConditionVariable sig;
		private Object obj;

		private SignalDesc(Object obj) {
			sig			= new ConditionVariable();
			this.obj	= obj;
		}

		private void await() {
			sig.block();
		}

		private void send() {
			sig.open();
		}
	}

	@WorkerThread
	public abstract void handleMessage(Message msg);

	@WorkerThread
	public abstract void done();
}
