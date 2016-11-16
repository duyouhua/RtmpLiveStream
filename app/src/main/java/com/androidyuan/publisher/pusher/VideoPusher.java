package com.androidyuan.publisher.pusher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;

import com.androidyuan.publisher.param.VideoParam;
import com.jutong.live.jni.PusherNative;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.quanmin.livestreamlibrary.RtmpHelperJNI;

public class VideoPusher extends Pusher implements Callback, PreviewCallback {

	private final static String TAG = "VideoPusher";


	private static final String VCODEC = "video/avc";

	private final static int SCREEN_PORTRAIT = 0;
	private final static int SCREEN_LANDSCAPE_LEFT = 90;
	private final static int SCREEN_LANDSCAPE_RIGHT = 270;
	private final static int width = 360;
	private final static int height = 640;
	private final long presentationTimeUs;
	ExecutorService previewDataExecutor = Executors.newSingleThreadExecutor();
	private boolean mPreviewRunning;
	private Camera mCamera;
	private SurfaceHolder mHolder;
	private VideoParam mParam;
	private byte[] buffer;
	private byte[] raw;
	private Activity mActivity;
	private int screen;
	private int mFrameCount=0;
	private int mPreviewCount=0;
	private Timer mTimer;
	private byte[] videoBuffer;
	private int videoBuffersize;
	private MediaCodec vencoder;
	private MediaCodecInfo vmci;
	private MediaCodec.BufferInfo vebi;
	private int vcolor;


	public VideoPusher(Activity activity, SurfaceHolder surfaceHolder,
			VideoParam param, PusherNative pusherNative) {
		super(pusherNative);
		mActivity = activity;
		mParam = param;
		mHolder = surfaceHolder;
		surfaceHolder.addCallback(this);

		// choose the right vencoder, perfer qcom then google.


		// 影像與聲音編碼開始時間
		presentationTimeUs = new Date().getTime() * 1000;

		vmci = chooseVideoEncoder(null, null);
		vcolor = chooseVideoEncoder();
		try {
			vencoder = MediaCodec.createByCodecName(vmci.getName());
		} catch (IOException e) {
			e.printStackTrace();
		}
		vebi = new MediaCodec.BufferInfo();

		// setup the vencoder.
		// @see
		// https://developer.android.com/reference/android/media/MediaCodec.html
		MediaFormat vformat = MediaFormat.createVideoFormat(VCODEC,
				width, height);
		vformat.setInteger(MediaFormat.KEY_COLOR_FORMAT, vcolor);
		vformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
		vformat.setInteger(MediaFormat.KEY_BIT_RATE, mParam.getBitrate());
		vformat.setInteger(MediaFormat.KEY_FRAME_RATE, mParam.getFps());
		vformat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 20);
		Log.i(TAG,String.format("vencoder %s, color=%d, bitrate=%d, fps=%d, gop=%d, size=%dx%d",
				vmci.getName(),
				vcolor,
				mParam.getBitrate(),
				mParam.getFps(),
				20,
				width,
				height));

		// the following error can be ignored:
		// 1. the storeMetaDataInBuffers error:
		// [OMX.qcom.video.encoder.avc] storeMetaDataInBuffers (output) failed
		// w/ err -2147483648
		// @see http://bigflake.com/mediacodec/#q12
		vencoder.configure(vformat,
				null,
				null,
				MediaCodec.CONFIGURE_FLAG_ENCODE);


		// start device and encoder.
		Log.i(TAG, "start avc vencoder");

	}

	/*******************
	 * 影像 YV12toYUV420PackedSemiPlanar YV12toYUV420Planar
	 ********************/
	// the color transform, @see
	// http://stackoverflow.com/questions/15739684/mediacodec-and-camera-color-space-incorrect
	private static byte[] YV12toYUV420PackedSemiPlanar(final byte[] input,
			final byte[] output, final int width, final int height) {
		/*
		 * COLOR_TI_FormatYUV420PackedSemiPlanar is NV12 We convert by putting
		 * the corresponding U and V bytes together (interleaved).
		 */
		final int frameSize = width * height;
		final int qFrameSize = frameSize / 4;

		System.arraycopy(input, 0, output, 0, frameSize); // Y

		for (int i = 0; i < qFrameSize; i++) {
			output[frameSize + i * 2] = input[frameSize + i]; // Cb
			// (U)
			output[frameSize + i * 2 + 1] = input[frameSize + i + qFrameSize]; // Cr (V)
		}
		return output;
	}

	private TimerTask createCollectTask()
	{
		return new TimerTask() {
			@Override
			public void run() {

				Log.d("FPS",mFrameCount+" "+mPreviewCount);

				mFrameCount=0;
				mPreviewCount=0;
			}
		};
	}

	@Override
	public void startPusher() {
		startPreview();
		mPusherRuning = true;

		mTimer=new Timer();
		mTimer.schedule(createCollectTask(),1000,1000);

		vencoder.start();
	}

	@Override
	public void stopPusher() {
		mPusherRuning = false;
		if(mTimer!=null) {
			mTimer.cancel();
		}
	}

	@Override
	public void release() {
		mPusherRuning = false;
		mActivity = null;
		stopPreview();
		vencoder.release();
		videoBuffer=null;
	}

	public void switchCamera() {
		if (mParam.getCameraId() == CameraInfo.CAMERA_FACING_BACK) {
			mParam.setCameraId(CameraInfo.CAMERA_FACING_FRONT);
		} else {
			mParam.setCameraId(CameraInfo.CAMERA_FACING_BACK);
		}
		stopPreview();
		startPreview();
	}

	private void stopPreview() {
		if (mPreviewRunning && mCamera != null) {
			mCamera.setPreviewCallback(null);
			mCamera.stopPreview();
			mCamera.release();
			mCamera = null;
			mPreviewRunning = false;
		}
	}

	@SuppressWarnings("deprecation")
	private void startPreview() {
		if (mPreviewRunning) {
			return;
		}
		try {
			mCamera = Camera.open(mParam.getCameraId());
			Camera.Parameters parameters = mCamera.getParameters();
			parameters.setPreviewFormat(ImageFormat.NV21);
			setPreviewSize(parameters);
			setPreviewFpsRange(parameters);
			setPreviewOrientation(parameters);
			mCamera.setParameters(parameters);
			buffer = new byte[mParam.getWidth() * mParam.getHeight() * 3 / 2];
			raw = new byte[mParam.getWidth() * mParam.getHeight() * 3 / 2];
			mCamera.addCallbackBuffer(buffer);
			mCamera.setPreviewCallbackWithBuffer(this);
			mCamera.setPreviewDisplay(mHolder);

			if(!isHorScreen())
			{
				mCamera.setDisplayOrientation(90);
			}

			mCamera.startPreview();
			mPreviewRunning = true;
		} catch (Exception ex) {
			ex.printStackTrace();
			if (null != mListener) {
				mListener.onErrorPusher(PusherNative.MSG_ERROR_PREVIEW);
			}
		}
	}

	private void setPreviewSize(Camera.Parameters parameters) {
		List<Integer> supportedPreviewFormats = parameters
				.getSupportedPreviewFormats();
		for (Integer integer : supportedPreviewFormats) {
			System.out.println("支持:" + integer);
		}
		List<Size> supportedPreviewSizes = parameters
				.getSupportedPreviewSizes();
		Size size = supportedPreviewSizes.get(0);
		Log.d(TAG, "支持 " + size.width + "x" + size.height);
		int m = Math.abs(size.height * size.width - mParam.getHeight()
				* mParam.getWidth());
		supportedPreviewSizes.remove(0);
		Iterator<Size> iterator = supportedPreviewSizes.iterator();
		while (iterator.hasNext()) {
			Size next = iterator.next();
			Log.d(TAG, "支持 " + next.width + "x" + next.height);
			int n = Math.abs(next.height * next.width - mParam.getHeight()
					* mParam.getWidth());
			if (n < m) {
				m = n;
				size = next;
			}
		}
		mParam.setHeight(size.height);
		mParam.setWidth(size.width);
		parameters.setPreviewSize(mParam.getWidth(), mParam.getHeight());
		Log.d(TAG, "预览分辨率 width:" + size.width + " height:" + size.height);
	}

	private void setPreviewFpsRange(Camera.Parameters parameters) {
		int range[] = new int[2];
		parameters.getPreviewFpsRange(range);
		Log.d(TAG, "预览帧率 fps:" + range[0] + " - " + range[1]);
	}

	private void setPreviewOrientation(Camera.Parameters parameters) {
		CameraInfo info = new CameraInfo();
		Camera.getCameraInfo(mParam.getCameraId(), info);
		int rotation = mActivity.getWindowManager().getDefaultDisplay()
				.getRotation();
		screen = 0;
		switch (rotation) {
		case Surface.ROTATION_0:
			screen = SCREEN_PORTRAIT;
			mNative.setVideoOptions(mParam.getHeight(), mParam.getWidth(),
					mParam.getBitrate(), mParam.getFps());
			break;
		case Surface.ROTATION_90: // 横屏 左边是头部(home键在右边)
			screen = SCREEN_LANDSCAPE_LEFT;
			mNative.setVideoOptions(mParam.getWidth(), mParam.getHeight(),
					mParam.getBitrate(), mParam.getFps());
			break;
		case Surface.ROTATION_180:
			screen = 180;
			break;
		case Surface.ROTATION_270:// 横屏 头部在右边
			screen = SCREEN_LANDSCAPE_RIGHT;
			mNative.setVideoOptions(mParam.getWidth(), mParam.getHeight(),
					mParam.getBitrate(), mParam.getFps());
			break;
		}
		int result;
		if (info.facing == CameraInfo.CAMERA_FACING_FRONT) {
			result = (info.orientation + screen) % 360;
			result = (360 - result) % 360; // compensate the mirror
		} else { // back-facing
			result = (info.orientation - screen + 360) % 360;
		}
		mCamera.setDisplayOrientation(result);

		// // 如果是竖屏 设置预览旋转90度，并且由于回调帧数据也需要旋转所以宽高需要交换
		// if (mContext.getResources().getConfiguration().orientation ==
		// Configuration.ORIENTATION_PORTRAIT) {
		// mNative.setVideoOptions(mParam.getHeight(), mParam.getWidth(),
		// mParam.getBitrate(), mParam.getFps());
		// parameters.set("orientation", "portrait");
		// mCamera.setDisplayOrientation(90);
		// } else {
		// mNative.setVideoOptions(mParam.getWidth(), mParam.getHeight(),
		// mParam.getBitrate(), mParam.getFps());
		// parameters.set("orientation", "landscape");
		// mCamera.setDisplayOrientation(0);
		// }
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {

	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		mHolder = holder;
		stopPreview();
		startPreview();
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		stopPreview();
	}

	@Override
	public void onPreviewFrame(final byte[] data, final Camera camera) {

		mPreviewCount++;
		Runnable orientationFixRun=new Runnable() {
			@Override
			public void run() {

				if(mPusherRuning) {

					byte[] frame = new byte[data.length];
					if (vcolor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar
							|| vcolor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
						YV12toYUV420PackedSemiPlanar(data, frame, width, height);
					} else {
						System.arraycopy(data, 0, frame, 0, data.length);
					}

					onGetYuvFrame(data);
				}

//				if (mPusherRuning) {
//					switch (screen) {
//						case SCREEN_PORTRAIT:
//							portraitData2Raw(data);
//							break;
//						case SCREEN_LANDSCAPE_LEFT:
//							raw = data;
//							break;
//						case SCREEN_LANDSCAPE_RIGHT:
//							landscapeData2Raw(data);
//							break;
//					}
//
//					onGetYuvFrame(raw);
//					mFrameCount++;
//
//				}
				camera.addCallbackBuffer(buffer);
			}
		};


		previewDataExecutor.execute(orientationFixRun);
	}

	/*******************
	 * 影像 chooseVideoEncoder 影像選擇編碼器
	 ********************/
	// choose the right supported color format. @see below:
	// https://developer.android.com/reference/android/media/MediaCodecInfo.html
	// https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities.html
	@SuppressLint("NewApi")
	private int chooseVideoEncoder() {
		// choose the encoder "video/avc":
		// 1. select one when type matched.
		// 2. perfer google avc.
		// 3. perfer qcom avc.
		// vmci = chooseVideoEncoder("google", vmci);
		// vmci = chooseVideoEncoder("qcom", vmci);

		int matchedColorFormat = 0;
		MediaCodecInfo.CodecCapabilities cc = vmci
				.getCapabilitiesForType(VCODEC);
		for (int i = 0; i < cc.colorFormats.length; i++) {
			int cf = cc.colorFormats[i];
			Log.i(TAG, String.format(
					"vencoder %s supports color fomart 0x%x(%d)",
					vmci.getName(), cf, cf));

			// choose YUV for h.264, prefer the bigger one.
			// corresponding to the color space transform in onPreviewFrame
			if ((cf >= MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar && cf <= MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
				if (cf > matchedColorFormat) {
					matchedColorFormat = cf;
				}
			}
		}

		for (int i = 0; i < cc.profileLevels.length; i++) {
			MediaCodecInfo.CodecProfileLevel pl = cc.profileLevels[i];
			Log.i(TAG, String.format(
					"vencoder %s support profile %d, level %d", vmci.getName(),
					pl.profile, pl.level));
		}

		Log.i(TAG,String.format("vencoder %s choose color format 0x%x(%d)",
				vmci.getName(),
				matchedColorFormat,
				matchedColorFormat));

		return matchedColorFormat;
	}


	/*******************
	 * 影像 chooseVideoEncoder 影像選擇編碼器
	 ********************/
	// choose the video encoder by name.
	@SuppressLint("NewApi")
	private MediaCodecInfo chooseVideoEncoder(String name, MediaCodecInfo def) {
		int nbCodecs = MediaCodecList.getCodecCount();
		for (int i = 0; i < nbCodecs; i++) {
			MediaCodecInfo mci = MediaCodecList.getCodecInfoAt(i);
			if (!mci.isEncoder()) {
				continue;
			}

			String[] types = mci.getSupportedTypes();
			for (int j = 0; j < types.length; j++) {
				if (types[j].equalsIgnoreCase(VCODEC)) {
					// Log.i(TAG, String.format("vencoder %s types: %s",
					// mci.getName(), types[j]));
					if (name == null) {
						return mci;
					}

					if (mci.getName().contains(name)) {
						return mci;
					}
				}
			}
		}

		return def;
	}


	private void onGetYuvFrame(byte[] data) {

		if(vencoder==null)
			return;

		// feed the vencoder with yuv frame, got the encoded 264 es stream.

		ByteBuffer[] inBuffers = vencoder.getInputBuffers();
		ByteBuffer[] outBuffers = vencoder.getOutputBuffers();

		if (true) {
			int inBufferIndex = vencoder.dequeueInputBuffer(-1);

			if (inBufferIndex >= 0) {
				ByteBuffer bb = inBuffers[inBufferIndex];
				bb.clear();
				bb.put(data, 0, data.length);

				long pts = new Date().getTime() * 1000 - presentationTimeUs;

				vencoder.queueInputBuffer(inBufferIndex, 0, data.length, pts, 0);
			}
		}

		for (;;) {
			int outBufferIndex = vencoder.dequeueOutputBuffer(vebi, 0);

			if (outBufferIndex >= 0) {
				ByteBuffer bb = outBuffers[outBufferIndex];
				onEncodedAnnexbFrame(bb, vebi);
				vencoder.releaseOutputBuffer(outBufferIndex, false);
			}

			if (outBufferIndex < 0) {
				break;
			}
		}
	}

	private void onEncodedAnnexbFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {
		try {
			// muxer.writeSampleData(vtrack, es, bi);
//			Log.e(TAG, String.format(
//					"rtmpmuxer pos=%d,size=%d,limit=%d,timestamp=%d",
//					es.position(), bi.size, es.limit(),
//					(int) bi.presentationTimeUs / 1000));
			int length = es.limit() - es.position();
			if ((videoBuffer == null) || (videoBuffersize < length)) {
				videoBuffer = null;
				videoBuffer = new byte[length];
				videoBuffersize = length;
			}
			es.get(videoBuffer, es.position(), length);

			int ret = 0;
			ret = RtmpHelperJNI.writeVideo(
					videoBuffer,
					0,
					length,
					(int) bi.presentationTimeUs / 1000
			);
			if (ret < 0) {
				Log.e(TAG, String.format(
						"rtmpmuxer write video sample failed:%d.", ret));
			}
		} catch (Exception e) {

			e.printStackTrace();
		}
	}




	private void landscapeData2Raw(byte[] data) {
		int width = mParam.getWidth(), height = mParam.getHeight();
		int y_len = width * height;
		int k = 0;
		// y数据倒叙插入raw中
		for (int i = y_len - 1; i > -1; i--) {
			raw[k] = data[i];
			k++;
		}
		// System.arraycopy(data, y_len, raw, y_len, uv_len);
		// v1 u1 v2 u2
		// v3 u3 v4 u4
		// 需要转换为:
		// v4 u4 v3 u3
		// v2 u2 v1 u1
		int maxpos = data.length - 1;
		int uv_len = y_len >> 2; // 4:1:1
		for (int i = 0; i < uv_len; i++) {
			int pos = i << 1;
			raw[y_len + i * 2] = data[maxpos - pos - 1];
			raw[y_len + i * 2 + 1] = data[maxpos - pos];
		}
	}

	private void portraitData2Raw(byte[] data) {
		// if (mContext.getResources().getConfiguration().orientation !=
		// Configuration.ORIENTATION_PORTRAIT) {
		// raw = data;
		// return;
		// }
		int width = mParam.getWidth(), height = mParam.getHeight();
		int y_len = width * height;
		int uvHeight = height >> 1; // uv数据高为y数据高的一半
		int k = 0;
		if (mParam.getCameraId() == CameraInfo.CAMERA_FACING_BACK) {
			for (int j = 0; j < width; j++) {
				for (int i = height - 1; i >= 0; i--) {
					raw[k++] = data[width * i + j];
				}
			}
			for (int j = 0; j < width; j += 2) {
				for (int i = uvHeight - 1; i >= 0; i--) {
					raw[k++] = data[y_len + width * i + j];
					raw[k++] = data[y_len + width * i + j + 1];
				}
			}
		} else {
			for (int i = 0; i < width; i++) {
				int nPos = width - 1;
				for (int j = 0; j < height; j++) {
					raw[k] = data[nPos - i];
					k++;
					nPos += width;
				}
			}
			for (int i = 0; i < width; i += 2) {
				int nPos = y_len + width - 1;
				for (int j = 0; j < uvHeight; j++) {
					raw[k] = data[nPos - i - 1];
					raw[k + 1] = data[nPos - i];
					k += 2;
					nPos += width;
				}
			}
		}
	}


	public boolean isHorScreen() {
		Configuration mConfiguration = mActivity.getResources().getConfiguration(); //获取设置的配置信息
		int ori = mConfiguration.orientation; //获取屏幕方向
		if (ori == Configuration.ORIENTATION_LANDSCAPE) {
			return true;
		} else if (ori == Configuration.ORIENTATION_PORTRAIT) {
			return false;
		}
		return false;
	}

}
