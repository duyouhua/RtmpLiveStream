package com.androidyuan.publisher;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.view.SurfaceHolder;

import com.androidyuan.publisher.param.AudioParam;
import com.androidyuan.publisher.param.VideoParam;
import com.androidyuan.publisher.pusher.AudioPusher;
import com.androidyuan.publisher.pusher.VideoPusher;
import com.jutong.live.jni.PusherNative;

import tv.quanmin.livestreamlibrary.RtmpHelperJNI;

public class LivePusher {

	private final static String TAG = "LivePusher";

	static {
		System.loadLibrary("Pusher");
	}

	boolean mIsStart=false;
	private VideoParam videoParam;
	private AudioParam audioParam;
	private VideoPusher videoPusher;
	private PusherNative mNative;
	private AudioPusher audioPusher;
	private LiveStateChangeListener mListener;
	private Activity mActivity;

	public LivePusher(Activity activity, int width, int height, int bitrate,
			int fps, int cameraId) {
		mActivity = activity;
		videoParam = new VideoParam(width, height, bitrate, fps, cameraId);
		audioParam = new AudioParam();
		mNative = new PusherNative();
		RtmpHelperJNI.setup();
	}

	public void prepare(SurfaceHolder surfaceHolder) {
		surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		videoPusher = new VideoPusher(mActivity, surfaceHolder, videoParam,
				mNative);
		audioPusher = new AudioPusher(audioParam, mNative);
		videoPusher.setLiveStateChangeListener(mListener);
		audioPusher.setLiveStateChangeListener(mListener);
	}

	public void startPusher(String url) {

		mIsStart = true;

		int result = RtmpHelperJNI.open(url,
				android.os.Build.MODEL,
				android.os.Build.VERSION.RELEASE,
				getNetworkType(),
				800*1000,
				15
				);

		videoPusher.startPusher();
		audioPusher.startPusher();

	}

	public void stopPusher() {
		mIsStart = false;
		videoPusher.stopPusher();
		audioPusher.stopPusher();
		RtmpHelperJNI.close();
	}

	public void switchCamera() {
		videoPusher.switchCamera();
	}

	public void relase() {
		mActivity = null;
		stopPusher();
		videoPusher.setLiveStateChangeListener(null);
		audioPusher.setLiveStateChangeListener(null);
		mNative.setLiveStateChangeListener(null);
		videoPusher.release();
		audioPusher.release();
		RtmpHelperJNI.release();

	}

	public void setLiveStateChangeListener(LiveStateChangeListener listener) {
		mListener = listener;
		mNative.setLiveStateChangeListener(listener);
		if (null != videoPusher) {
			videoPusher.setLiveStateChangeListener(listener);
		}
		if (null != audioPusher) {
			audioPusher.setLiveStateChangeListener(listener);
		}

	}

	public boolean isStart()
	{
		return mIsStart;
	}

	public void setStart(boolean b) {
		mIsStart=b;
	}

	public String getNetworkType() {
		String netType = "unknown";
		ConnectivityManager connectivityManager = (ConnectivityManager) mActivity.getSystemService(
				Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
		if (networkInfo == null) {
			return netType;
		}
		int nType = networkInfo.getType();
		if (nType == ConnectivityManager.TYPE_MOBILE) {
			String extraInfo = networkInfo.getExtraInfo();
			if(extraInfo != null){
				netType = extraInfo.toLowerCase();
			}
		} else if (nType == ConnectivityManager.TYPE_WIFI) {
			netType = "wifi";
		}
		return netType;
	}
}
