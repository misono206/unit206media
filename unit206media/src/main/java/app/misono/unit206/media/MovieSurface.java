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

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES10;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 *	Provides the surface of SurfaceTexture
 */
@RequiresApi(17)
public final class MovieSurface {
	private STextureRender textureRender;
	private SurfaceTexture surfaceTexture;
	private ReentrantLock lock;
	private EGLDisplay eglDisplay;
	private EGLContext eglContext;
	private EGLSurface eglSurface;
	private ByteBuffer pixelBuf;
	private Condition available, notAvailable;
	private Surface surface;
	private boolean frameAvailable;
	private int width, height;

	private MovieSurface() {
	}

	public MovieSurface(int width, int height, boolean noWait) {
		this.width = width;
		this.height = height;
		eglDisplay = EGL14.EGL_NO_DISPLAY;
		eglContext = EGL14.EGL_NO_CONTEXT;
		eglSurface = EGL14.EGL_NO_SURFACE;
		if (!noWait) {
			lock = new ReentrantLock();
			available = lock.newCondition();
			notAvailable = lock.newCondition();
		}
		eglSetup();
		makeCurrent();
		setup();
	}

	public MovieSurface(int width, int height) {
		this(width, height, false);
	}

	private void setup() {
		textureRender = new STextureRender();
		textureRender.surfaceCreated();
		surfaceTexture = new SurfaceTexture(textureRender.getTextureId());
		surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
			@Override
			public void onFrameAvailable(SurfaceTexture surfaceTexture) {
				if (lock != null) {
					lock.lock();
					if (frameAvailable) {
						try {
							notAvailable.await();
						} catch (InterruptedException e) {
							// nop
						}
					}
					available.signal();
					frameAvailable = true;
					lock.unlock();
				}
			}
		});
		surface = new Surface(surfaceTexture);
		pixelBuf = ByteBuffer.allocateDirect(width * height * 4);
		pixelBuf.order(ByteOrder.LITTLE_ENDIAN);
	}

	public SurfaceTexture getSurfaceTexture() {
		return surfaceTexture;
	}

	private void eglSetup() {
		final int[]	attribList1 = {
			EGL14.EGL_RED_SIZE, 8,
			EGL14.EGL_GREEN_SIZE, 8,
			EGL14.EGL_BLUE_SIZE, 8,
			EGL14.EGL_ALPHA_SIZE, 8,
			EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
			EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
			EGL14.EGL_NONE
		};
	    final int[]	attribList2 = {
			EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
			EGL14.EGL_NONE
	    };
		final int[]	surfaceAttribs = {
			EGL14.EGL_WIDTH, width,
			EGL14.EGL_HEIGHT, height,
			EGL14.EGL_NONE
		};

		eglDisplay	= EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
		if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
			throw new RuntimeException("unable to get EGL14 display");
		}
		int [] version = new int[2];
		if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
			eglDisplay	= null;
			throw new RuntimeException("unable to initialize EGL14");
		}

		EGLConfig[] configs = new EGLConfig[1];
		int [] numConfigs = new int[1];
		if (!EGL14.eglChooseConfig(eglDisplay, attribList1, 0, configs, 0, configs.length, numConfigs, 0)) {
			throw new RuntimeException("unable to find RGB888+recordable ES2 EGL config");
		}

		eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, attribList2, 0);
		checkEglError("eglCreateContext");
		if (eglContext == null) {
			throw new RuntimeException("null context");
		}

		eglSurface	= EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs, 0);
		checkEglError("eglCreatePbufferSurface");
		if (eglSurface == null) {
			throw new RuntimeException("surface was null");
		}
	}

	public void release() {
		if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
			EGL14.eglDestroySurface(eglDisplay, eglSurface);
			EGL14.eglDestroyContext(eglDisplay, eglContext);
			EGL14.eglReleaseThread();
			EGL14.eglTerminate(eglDisplay);
		}
		surface.release();
		surface = null;
		textureRender = null;
		surfaceTexture = null;
		eglDisplay = EGL14.EGL_NO_DISPLAY;
		eglContext = EGL14.EGL_NO_CONTEXT;
		eglSurface = EGL14.EGL_NO_SURFACE;
	}

	private void makeCurrent() {
		if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
			throw new RuntimeException("eglMakeCurrent failed");
		}
	}

	public Surface getSurface() {
		return surface;
	}

	public void awaitNewImage() throws InterruptedException {
		if (lock != null) {
			lock.lock();
			if (!frameAvailable) {
				available.await();
			}
			notAvailable.signal();
			frameAvailable = false;
			lock.unlock();
		}
		textureRender.checkGlError("before updateTexImage");
		surfaceTexture.updateTexImage();
	}

	public void drawImage(boolean invert) {
		textureRender.drawFrame(surfaceTexture, invert);
	}

	public byte[] getJpeg(int quality) {
		Bitmap bitmap = getBitmap(null);
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os);
		return os.toByteArray();
	}

	public Bitmap getBitmap(Bitmap bitmap) {
		pixelBuf.rewind();
		if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
			bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		}
		if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
			throw new RuntimeException("Bitmap.Config is not ARGB_8888...");
		}
		GLES10.glReadPixels(0, 0, width, height, GLES10.GL_RGBA, GLES10.GL_UNSIGNED_BYTE, pixelBuf);
		pixelBuf.rewind();
		bitmap.copyPixelsFromBuffer(pixelBuf);
		return bitmap;
	}

	private void checkEglError(String msg) {
		int error = EGL14.eglGetError();
		if (error != EGL14.EGL_SUCCESS) {
			throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
		}
	}

	private static final class STextureRender {
		private static final int FLOAT_SIZE_BYTES = 4;
		private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
		private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
		private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
		private static final String VERTEX_SHADER =
			"uniform mat4 uMVPMatrix;\n"							+
			"uniform mat4 uSTMatrix;\n"								+
			"attribute vec4 aPosition;\n"							+
			"attribute vec4 aTextureCoord;\n"						+
			"varying vec2 vTextureCoord;\n"							+
			"void main() {\n"										+
			" gl_Position = uMVPMatrix * aPosition;\n"				+
			" vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n"	+
			"}\n";
		private static final String FRAGMENT_SHADER =
			"#extension GL_OES_EGL_image_external : require\n"		+
			"precision mediump float;\n"							+
			"varying vec2 vTextureCoord;\n"							+
			"uniform samplerExternalOES sTexture;\n"				+
			"void main() {\n"										+
			" gl_FragColor = texture2D(sTexture, vTextureCoord);\n"	+
			"}\n";
		private final float[] triangleVerticesData = {
			// X, Y, Z, U, V
			-1.0f, -1.0f, 0, 0.f, 0.f,
			 1.0f, -1.0f, 0, 1.f, 0.f,
			-1.0f,  1.0f, 0, 0.f, 1.f,
			 1.0f,  1.0f, 0, 1.f, 1.f,
		};
	
		private final FloatBuffer triangleVertices;
		private final float[] mvpMatrix, stMatrix;

		private int textureID, program, umvpMatrixHandle, ustMatrixHandle, aPositionHandle, aTextureHandle;
	
		private STextureRender() {
			mvpMatrix = new float[16];
			stMatrix = new float[16];
			triangleVertices = ByteBuffer.allocateDirect(triangleVerticesData.length * FLOAT_SIZE_BYTES)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
			triangleVertices.put(triangleVerticesData).position(0);
			android.opengl.Matrix.setIdentityM(stMatrix, 0);
		}
	
		private int getTextureId() {
			return textureID;
		}
	
		private void drawFrame(SurfaceTexture st, boolean invert) {
			checkGlError("onDrawFrame start");
			st.getTransformMatrix(stMatrix);
			if (invert) {
				stMatrix[5] = -stMatrix[5];
				stMatrix[13] = 1.0f - stMatrix[13];
			}
	
			GLES10.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
			GLES10.glClear(GLES10.GL_COLOR_BUFFER_BIT);
			GLES20.glUseProgram(program);
			checkGlError("glUseProgram");
	
		    GLES10.glActiveTexture(GLES10.GL_TEXTURE0);
		    GLES10.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID);
			triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
			GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES10.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices);
			checkGlError("glVertexAttribPointer maPosition");
	
			GLES20.glEnableVertexAttribArray(aPositionHandle);
			checkGlError("glEnableVertexAttribArray maPositionHandle");
	
			triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
			GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES10.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices);
			checkGlError("glVertexAttribPointer maTextureHandle");
	
			GLES20.glEnableVertexAttribArray(aTextureHandle);
			checkGlError("glEnableVertexAttribArray maTextureHandle");
	
			android.opengl.Matrix.setIdentityM(mvpMatrix, 0);
			GLES20.glUniformMatrix4fv(umvpMatrixHandle, 1, false, mvpMatrix, 0);
			GLES20.glUniformMatrix4fv(ustMatrixHandle, 1, false, stMatrix,  0);
			GLES10.glDrawArrays(GLES10.GL_TRIANGLE_STRIP, 0, 4);
			checkGlError("glDrawArrays");
	
			GLES10.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
		}
	
		private void surfaceCreated() {
			program	= createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
			if (program == 0) {
				throw new RuntimeException("failed creating program");
			}
	
			aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition");
			checkLocation(aPositionHandle, "aPosition");
			aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
			checkLocation(aTextureHandle, "aTextureCoord");
			umvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
			checkLocation(umvpMatrixHandle, "uMVPMatrix");
			ustMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
			checkLocation(ustMatrixHandle, "uSTMatrix");

			int[] textures = new int[1];
			GLES10.glGenTextures(1, textures, 0);
	
			textureID = textures[0];
			GLES10.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID);
			checkGlError("glBindTexture mTextureID");
		
			GLES10.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES10.GL_TEXTURE_MIN_FILTER, GLES10.GL_NEAREST);
			GLES10.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES10.GL_TEXTURE_MAG_FILTER, GLES10.GL_LINEAR);
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES10.GL_TEXTURE_WRAP_S,     GLES10.GL_CLAMP_TO_EDGE);
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES10.GL_TEXTURE_WRAP_T,     GLES10.GL_CLAMP_TO_EDGE);
			checkGlError("glTexParameter");
		}
	
		private int loadShader(int shaderType, String source) {
			int shader = GLES20.glCreateShader(shaderType);
			checkGlError("glCreateShader type=" + shaderType);
	
			GLES20.glShaderSource(shader, source);
			GLES20.glCompileShader(shader);
			int[] compiled = new int[1];
			GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
			if (compiled[0] == 0) {
				GLES20.glDeleteShader(shader);
				shader = 0;
			}
			return shader;
		}
		
		private int createProgram(String vertexSource, String fragmentSource) {
			int program = 0;
			int vertexShader= loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
			if (vertexShader != 0) {
				int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
				if (pixelShader != 0) {
					program = GLES20.glCreateProgram();
					GLES20.glAttachShader(program, vertexShader);
					checkGlError("glAttachShader");
					GLES20.glAttachShader(program, pixelShader);
					checkGlError("glAttachShader");
					GLES20.glLinkProgram(program);
					int[] linkStatus = new int[1];
					GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
					if (linkStatus[0] != GLES10.GL_TRUE) {
						GLES20.glDeleteProgram(program);
						program = 0;
					}
				}
			}
			return program;
		}
	
		private void checkGlError(String op) {
			int error = GLES10.glGetError();
			if (error != GLES10.GL_NO_ERROR) {
				throw new RuntimeException(op + ": glError " + error);
			}
		}
	
		private void checkLocation(int location, String label) {
			if (location < 0) {
				throw new RuntimeException("Unable to locate '" + label + "' in program");
			}
		}
	}

}
