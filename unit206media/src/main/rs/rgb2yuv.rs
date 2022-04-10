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

/**
 *	Converts Bitmap(ARGB_8888) to YUV420Planar or YUV420SemiPlanar.
 *
 *	@version	2014.11.07
 *	@author		ara
 */

#pragma version(1)
#pragma rs java_package_name(app.misono.unit206.media)

rs_allocation	inAlloc;
uint32_t		width, height, uvStep;
uint8_t			*out, *yy, *uu, *vv;

void root(const uchar4 *v_in, uchar4 *v_out, uint32_t x, uint32_t y) {
	float4		argb;
	uint32_t	xy;

	argb	= rsUnpackColor8888(*v_in);
	xy		= y * width + x;
	yy[xy]	= (0.256788 * argb.r + 0.504129 * argb.g + 0.097906 * argb.b) * 256 + 16;
	if ((x & 1) == 0 && (y & 1) == 0) {
		xy		= (((y * width) >> 2) + (x >> 1)) * uvStep;
		uu[xy]	= (-0.148223 * argb.r - 0.290993 * argb.g + 0.439216 * argb.b) * 256 + 128;
		vv[xy]	= ( 0.439216 * argb.r - 0.367788 * argb.g - 0.071427 * argb.b) * 256 + 128;
	}
}

void setup(int format) {
	uint32_t	wh;
	uint8_t		*uv;

	width	= rsAllocationGetDimX(inAlloc);
	height	= rsAllocationGetDimY(inAlloc);
	wh		= width * height;
	yy		= out;
	uv		= &out[wh];
	switch (format) {
	case 0:								//	FORMAT_IYUV: YYYYYYYY UU VV
		uu		= uv;
		vv		= &uv[wh / 4];
		uvStep	= 1;
		break;
	case 1:								//	FORMAT_YV12: YYYYYYYY VV UU
		vv		= uv;
		uu		= &uv[wh / 4];
		uvStep	= 1;
		break;
	case 2:								//	FORMAT_NV12: YYYYYYYY UVUV
		uu		= uv;
		vv		= &uv[1];
		uvStep	= 2;
		break;
	case 3:								//	FORMAT_NV21: YYYYYYYY VUVU
		vv		= uv;
		uu		= &uv[1];
		uvStep	= 2;
		break;
	}
}

/*
 *	end of file
 */
