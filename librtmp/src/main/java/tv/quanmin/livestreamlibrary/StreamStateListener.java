package tv.quanmin.livestreamlibrary;

import android.util.SparseArray;

/**
 * Created by wei on 16-8-27.
 */
public abstract class StreamStateListener implements IStreamStateListener {

    public static final int STOP_SECOND=60;
    public static final int[] NOTICE_SECOND={6,55,STOP_SECOND};
    public static final String[] NOTICE_STR={"当前网速较慢，请检查网络","当前网络不佳，直播可能自动结束","直播已结束"};
    SparseArray<String> noticeArr;

    public SparseArray<String> getNoticeArr()
    {
        if(noticeArr==null) {
            noticeArr = new SparseArray<String>();
            for (int i = 0; i < NOTICE_SECOND.length; i++) {
                noticeArr.put(NOTICE_SECOND[i], NOTICE_STR[i]);
            }
        }
        return noticeArr;
    }

    @Override
    public void onStreamPrepared(int var1) {

    }

    @Override
    public void onStreamStarted(int var1) {

    }

    @Override
    public void onStreamStopped(int var1) {

    }

    @Override
    public void onStreamError(int code) {

    }

    @Override
    public void onUploadBytes(int bytelen) {

    }

    @Override
    abstract  public void onReonnectSecond(long second);// {
//        if(noticeArr.indexOfKey((int)second)>-1){
//            toast(noticeArr.get((int)second));
//        }


    //}
}
