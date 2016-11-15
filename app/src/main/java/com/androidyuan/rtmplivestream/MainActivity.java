package com.androidyuan.rtmplivestream;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import com.androidyuan.publisher.LivePusher;
import com.androidyuan.publisher.LiveStateChangeListener;
import com.jutong.live.jni.PusherNative;


public class MainActivity extends Activity implements View.OnClickListener,
        SurfaceHolder.Callback, LiveStateChangeListener {

    private Button mbtnPusher;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;

    private LivePusher mLivePusher;

    private Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {

                case PusherNative.MSG_ERROR_PREVIEW:
                    toast("视频预览开始失败");
                    mLivePusher.stopPusher();
                    break;
                case PusherNative.MSG_ERROR_AUDIO_RECORD:
                    toast("音频录制失败");
                    mLivePusher.stopPusher();
                    break;
                case PusherNative.MSG_ERROR_SETUP_AUDIO_CODEC:
                    toast("音频编码器配置失败");
                    mLivePusher.stopPusher();
                    break;
                case PusherNative.MSG_ERROR_SETUP_VIDEO_CODEC:
                    toast("视频频编码器配置失败");
                    mLivePusher.stopPusher();
                    break;
                case PusherNative.MSG_ERROR_NOT_CONNECTSERVER:
                    toast("流媒体服务器/网络等问题");
                    mLivePusher.stopPusher();
                    break;
            }
            mbtnPusher.setText("推流");
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        //防止屏幕休眠
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mbtnPusher = (Button) findViewById(R.id.button_first);
        mbtnPusher.setOnClickListener(this);

        findViewById(R.id.button_take).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mLivePusher.switchCamera();
                    }
                });


        mSurfaceView = (SurfaceView) this.findViewById(R.id.surface);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
        mLivePusher = new LivePusher(this, 960, 720, 1024 * 1000, 15,
                Camera.CameraInfo.CAMERA_FACING_FRONT);
        mLivePusher.setLiveStateChangeListener(this);
        mLivePusher.prepare(mSurfaceHolder);

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        mLivePusher.relase();
    }

    @Override
    public void onClick(View v) {
        if (mLivePusher.isStart()) {

            mbtnPusher.setText("推流");
            mLivePusher.stopPusher();

        } else {

            mbtnPusher.setText("停止");
            mLivePusher.startPusher(
                    "rtmp://192.168.199.178:1935/live/livepublisher"
            );// TODO: 设置流媒体服务器地址

        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        System.out.println("MAIN: CREATE");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
            int height) {
        System.out.println("MAIN: CHANGE");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        System.out.println("MAIN: DESTORY");
    }

    /**
     * 可能运行在子线程
     */
    @Override
    public void onErrorPusher(int code) {
        System.out.println("code:" + code);
        mHandler.sendEmptyMessage(code);
        mLivePusher.setStart(false);

    }

    /**
     * 可能运行在子线程
     */
    @Override
    public void onStartPusher() {
        Log.d(getTag(), "开始推流");
    }

    /**
     * 可能运行在子线程
     */
    @Override
    public void onStopPusher() {
        Log.d(getTag(), "结束推流");
    }


    private void toast(String str) {
        if (!TextUtils.isEmpty(str)) {
            Toast.makeText(MainActivity.this, str, Toast.LENGTH_SHORT).show();
        }
    }

    private String getTag() {
        return getClass().getSimpleName();
    }

}
