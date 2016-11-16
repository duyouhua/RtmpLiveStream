package tv.quanmin.livestreamlibrary;

/**
 * Created by wei on 16/8/19.
 */
public interface IStreamStateListener {



    //参考百度
    int RESULT_CODE_OF_OPERATION_SUCCEEDED = 0;
    int ERROR_CODE_OF_PREPARE_SESSION_FAILED = -1;
    int ERROR_CODE_OF_CONNECT_TO_SERVER_FAILED = -2;
    int ERROR_CODE_OF_DISCONNECT_FROM_SERVER_FAILED = -3;
    int ERROR_CODE_OF_OPEN_MIC_FAILED = -4;
    int ERROR_CODE_OF_OPEN_CAMERA_FAILED = -5;
    int ERROR_CODE_OF_UNKNOWN_STREAMING_ERROR = -6;
    int ERROR_CODE_OF_PACKET_REFUSED_BY_SERVER = -32;
    int ERROR_CODE_OF_WEAK_CONNECTION = -35;
    int ERROR_CODE_OF_SERVER_INTERNAL_ERROR = -104;
    int ERROR_CODE_OF_CONNECTION_TIMEOUT = -110;

    void onStreamPrepared(int var1);

    void onStreamStarted(int var1);

    void onStreamStopped(int var1);

    void onStreamError(int code);


    void onReonnectSecond(long second);

    void onUploadBytes(int bytelen);//一秒内流量的回调   应该在1-5s钟之间的频率

}
