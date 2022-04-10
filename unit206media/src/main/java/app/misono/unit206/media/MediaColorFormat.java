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

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

/**
 *	Detects video/avc encoding color format of each device model.
 */
public final class MediaColorFormat {
	private static final String	TAG = "MediaColorFormat";
	private static final String	MIME_AVC = "video/avc";				//	MediaFormat.MIMETYPE_VIDEO_AVC

	private MediaColorFormat() {
	}

	public static int getEncoderColorFormat() {
		return getEncoderColorFormat(MIME_AVC);
	}

	public static int getEncoderColorFormat(String mimeVideo) {
		int n = MediaCodecList.getCodecCount();
		for (int i = 0; i < n; i++) {
			MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
			String[] mimes = info.getSupportedTypes();
			if (info.isEncoder()) {
				for (String mime : mimes) {
					if (mime.equals(mimeVideo)) {
						try {
							MediaCodecInfo.CodecCapabilities cap = info.getCapabilitiesForType(mime);
							for (int k = 0; k < cap.colorFormats.length; k++) {
								switch (cap.colorFormats[k]) {
								case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
								case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
									return cap.colorFormats[k];
								}
							}
						} catch (Exception e) {
							// nop
						}
					}
				}
			}
		}
		throw new RuntimeException("NOT FOUND " + mimeVideo + " ENCODER COLOR FORMAT!!!");
	}

	public static int getYuv420ColorFormat(int encoderColorFormat) {
		int	yuv420color;

		yuv420color	= 0;
		switch (encoderColorFormat) {
		case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
Log.w(TAG, "### Planar");
			//	Nexus7-2012
			yuv420color	= Yuv420PlanarAlone.FORMAT_IYUV;
//			yuv420color	= Yuv420Planar.FORMAT_YV12;
			break;
		case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
Log.w(TAG, "### SemiPlanar");
			//	Nexus5
			//	Nexus7-2013
			//	FonePad7
			//	mi-3
			yuv420color	= Yuv420PlanarAlone.FORMAT_NV12;
//			yuv420color	= Yuv420Planar.FORMAT_NV21;
			break;
		}

		return yuv420color;
	}
}
