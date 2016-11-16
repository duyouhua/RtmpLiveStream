package tv.quanmin.livestreamlibrary;

import android.util.Log;

/**
 * Created by wei on 16/8/19.
 *
 * java call native
 */
public class RtmpHelperJNI {

    static {
        System.loadLibrary("rtmp-jni");
    }

    public RtmpHelperJNI() {
    }

    public static native void setup();

    public static native void release();

    public static native  int open(String url,String device,String version,String network,int bps,int fps);

    public static native  int writeVideo(byte[] data, int offset, int length, int timestamp);

    public static native int writeAudio(byte[] data, int offset, int length, int timestamp);

    public static native int speex_denoise(byte[] data, int length);
    public static native int spx_denoise_init(int samplerate);
    public static native int spx_denoise_destroy();

    public static native int isConnected();

    public static native int close();

    public static native int getKps();

    public static native int getRate();

    public static native void setStateListener(Object obj);

	public static void notifyKpsAndRate(int kps, int rate){
        Log.i("RtmpHelperJNI", String.format("notifyKpsAndRate kps = %d, rate = %d", kps, rate));
	}
    public static native int caculatekps();

    public native int rtmpWrite(byte[] data,int offset,int length);
}
