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
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RSInvalidStateException;
import android.renderscript.RenderScript;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;

/**
 *	Converts Bitmap(ARGB_8888) to YUV420Planar or YUV420SemiPlanar.
 */
public final class Yuv420PlanarAlone implements Closeable {
	public static final int	FORMAT_IYUV	= 0;				//	YYYYYYYY UU VV	YUV420p
	public static final int	FORMAT_YV12	= 1;				//	YYYYYYYY VV UU	YUV420p
	public static final int	FORMAT_NV12	= 2;				//	YYYYYYYY UVUV	YUV420sp
	public static final int	FORMAT_NV21	= 3;				//	YYYYYYYY VUVU	YUV420sp

	private ScriptC_rgb2yuv script;
	private RenderScript rs;
	private Allocation inAlloc, outAlloc;
	private int yuvSize;

	public Yuv420PlanarAlone(@NonNull Context ctx) {
		rs = RenderScript.create(ctx);
		script = new ScriptC_rgb2yuv(rs);
	}

	public byte[] convert(Bitmap bitmap, int format) {
		byte[] rc = createYuv(bitmap);
		convert(rc, bitmap, format);
		return rc;
	}

	/**
	 *	Returns a byte array for YUV420 with the specified bitmap's width and height.
	 */
	public byte[] createYuv(Bitmap bitmap) {
		return createYuv(bitmap.getWidth(), bitmap.getHeight());
	}

	/**
	 *	Returns a byte array for YUV420 with the specified width and height.
	 */
	public byte[] createYuv(int width, int height) {
		return new byte[width * height * 3 / 2];
	}

	public byte[] convert(@Nullable byte[] yuv, Bitmap bitmap, int format) {
		if (script != null) {
			if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
				throw new RuntimeException("bitmap.config is not ARGB_8888...");
			}
			int size = bitmap.getWidth() * bitmap.getHeight() * 3 / 2;
			if (yuv == null) {
				yuv = new byte[size];
			}
			if (size != yuvSize) {
				clearAlloc();
				yuvSize = size;
				inAlloc = Allocation.createFromBitmap(rs, bitmap, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
				outAlloc = Allocation.createSized(rs, Element.U8(rs), size);
			} else {
				inAlloc.copyFrom(bitmap);
			}
			script.bind_out(outAlloc);
			script.set_inAlloc(inAlloc);
			script.invoke_setup(format);
			script.forEach_root(inAlloc, inAlloc);
			outAlloc.copyTo(yuv);
		} else {
			throw new IllegalStateException("Yuv420PlanarAlone is already closed...");
		}
		return yuv;
	}

	private void clearAlloc() {
		if (inAlloc != null) {
			inAlloc.destroy();
			inAlloc	= null;
		}
		if (outAlloc != null) {
			outAlloc.destroy();
			outAlloc = null;
		}
	}

	@Override
	public void close() {
		clearAlloc();
		if (script != null) {
			try {
				script.destroy();
			} catch (RSInvalidStateException e) {
				//	nop
			}
			script = null;
		}
		if (rs != null) {
			rs.destroy();
			rs = null;
		}
	}

}
