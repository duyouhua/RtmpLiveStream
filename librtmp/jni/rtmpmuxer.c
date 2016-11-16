#include <jni.h>
#include <android/log.h>

#include "flvmuxer/xiecc_rtmp.h"
#include "speex_types.h"
#include "speex_preprocess.h"
#include "speex_config_types.h"
#include "ring_buffer.h"
static SpeexPreprocessState *st = NULL;
static RingBuffer *spx_ringbuffer = NULL;
static RingBuffer *denoise_ringbuffer = NULL;
static int static_samplerate = 44100;
#define AUDIO_FRAME_SIZE  882
#define HELP_JNI_CLASS_NAME "tv/quanmin/livestreamlibrary/RtmpHelperJNI"

#ifndef NULL
#define NULL   ((void *) 0)
#endif

JavaVM *m_psJavaVM;
JNIEnv* gjvmenv = NULL;
jclass  gjclass_helpjni;

void qmtv_native_init(JNIEnv*  env, jobject thiz)
{
    (*m_psJavaVM)->AttachCurrentThread(m_psJavaVM,&gjvmenv, 0);

    jclass local_jclass = (*gjvmenv)->FindClass(gjvmenv,(const char*)HELP_JNI_CLASS_NAME);
    gjclass_helpjni = (jclass)(*gjvmenv)->NewGlobalRef(gjvmenv,local_jclass);
    (*gjvmenv)->DeleteLocalRef(gjvmenv,local_jclass);
}

jmethodID qmtv_get_jmethod(const char *methodName, const char *paramCode){
    return (*gjvmenv)->GetStaticMethodID(gjvmenv,gjclass_helpjni,methodName,paramCode);
}

/**
 * if it returns bigger than 0 it is successfull
 */
JNIEXPORT jint publish_MainActivity_open(JNIEnv *env, jobject instance, jstring url_,jstring device_,jstring version_,jstring network_,jint bps_,jint fps_) {
    const char *url = (*env)->GetStringUTFChars(env, url_, 0);
	const char *device = (*env)->GetStringUTFChars(env, device_, 0);
	const char *version = (*env)->GetStringUTFChars(env, version_, 0);
	const char *network = (*env)->GetStringUTFChars(env, network_, 0);

    __android_log_print(ANDROID_LOG_INFO, "qmtv", "url =  %s",url);

    int result = rtmp_open_for_write(url,device,version,network,bps_,fps_);

    (*env)->ReleaseStringUTFChars(env, url_, url);
	(*env)->ReleaseStringUTFChars(env, device_, device);
	(*env)->ReleaseStringUTFChars(env, version_, version);
	(*env)->ReleaseStringUTFChars(env, network_, network);

    //qmtv_native_init(env, instance);

    return result;
}
JNIEXPORT jint publish_MainActivity_writeAudio(JNIEnv *env, jobject instance,
                                                       jbyteArray data_, jint offset, jint length,
                                                       jint timestamp) {
    jbyte *data = (*env)->GetByteArrayElements(env, data_, NULL);

    jint result = rtmp_sender_write_audio_frame(data, length, timestamp, 0);

    (*env)->ReleaseByteArrayElements(env, data_, data, 0);
    return result;
}
JNIEXPORT jint publish_MainActivity_writeVideo(JNIEnv *env, jobject instance,
                                                       jbyteArray data_, jint offset, jint length,
                                                       jint timestamp) {
    jbyte *data = (*env)->GetByteArrayElements(env, data_, NULL);

    //__android_log_print(ANDROID_LOG_INFO, "qmtv", "offset = %d, length = %d",offset,length);

    jint result = rtmp_sender_write_video_frame(data, length, timestamp, 0, 0);

    (*env)->ReleaseByteArrayElements(env, data_, data, 0);

    return result;
}

JNIEXPORT jint 
publish_MainActivity_rtmpWrite(JNIEnv *env, jobject instance,
                                                      jbyteArray data_, jint offset, jint length)
{
    jbyte *data = (*env)->GetByteArrayElements(env, data_, NULL);

    jint result = rtmp_sender_rtmpWrite(data, offset,length);

    (*env)->ReleaseByteArrayElements(env, data_, data, 0);

    return result;
}

JNIEXPORT jint publish_MainActivity_close(JNIEnv *env, jobject instance) {
    rtmp_close();
    return 0;

}
JNIEXPORT jint publish_MainActivity_isConnected(JNIEnv *env, jobject instance) {
    return rtmp_is_connected();
}
JNIEXPORT jint qmtv_native_getKps(JNIEnv *env, jobject instance) {
    return getSendedKps();
}
JNIEXPORT jint qmtv_native_getRate(JNIEnv *env, jobject instance) {
    return getPacketRate();
}

JNIEXPORT jint qmtv_native_caculatekps(JNIEnv *env, jobject instance) {
    caculatekps();
    return 0;
}

void speex_denoise(short *data, int size) {
   int i;
   if (st == NULL) return; 
   i = 1;
   speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_DENOISE, &i);
   i = -10;
   speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &i);
   speex_preprocess_run(st, data);
}

JNIEXPORT jint publish_MainActivity_spx_denoise_init(JNIEnv *env, jobject instance,
                        int samplerate) {
    int frameCount_20ms = 20 *samplerate /1000;
    static_samplerate = samplerate;
    spx_ringbuffer = WebRtc_CreateBuffer(frameCount_20ms*5 , 2);
    denoise_ringbuffer = WebRtc_CreateBuffer(frameCount_20ms*5 ,2);
    /*initialization denoise st*/
    st = speex_preprocess_state_init(frameCount_20ms,samplerate);
    if(st)
      return 0;
    else
      return -1;
}

JNIEXPORT jint publish_MainActivity_spx_denoise_destroy(JNIEnv *env, jobject instance) {
    if (st == NULL) return 0;
    speex_preprocess_state_destroy(st);
    WebRtc_FreeBuffer(spx_ringbuffer);
    WebRtc_FreeBuffer(denoise_ringbuffer);
    spx_ringbuffer = NULL;
    denoise_ringbuffer = NULL;
    st = NULL;
    return 0;
}
JNIEXPORT jint publish_MainActivity_speex_denoise(JNIEnv *env, jobject instance,jbyteArray data_,jint size) {
    jbyte *data = (*env)->GetByteArrayElements(env, data_, NULL);
    int frameCount = size / 2;
    int frameCount_20ms = 20 * static_samplerate /1000;
    short tempData[frameCount_20ms];

    WebRtc_WriteBuffer(spx_ringbuffer,data,frameCount);
    while(WebRtc_available_read(spx_ringbuffer)>=frameCount_20ms){
         WebRtc_ReadBuffer(spx_ringbuffer,NULL,tempData,frameCount_20ms);
         speex_denoise(tempData,frameCount_20ms);
         WebRtc_WriteBuffer(denoise_ringbuffer,tempData,frameCount_20ms);
    } 
    int  denoiseFrame=WebRtc_ReadBuffer(denoise_ringbuffer,NULL,data,frameCount);
    (*env)->ReleaseByteArrayElements(env, data_, data, 0);
    return denoiseFrame*2;
}

void notifyKpsAndRateJNI(unsigned int kps,unsigned int rate){
    jmethodID methodid = qmtv_get_jmethod("notifyKpsAndRate","(II)V");
    (*gjvmenv)->CallStaticVoidMethod(gjvmenv, gjclass_helpjni, methodid, kps, rate);
}

jint JNI_OnLoad(JavaVM *vm, void *reserved){
    m_psJavaVM=vm;

    if(!gjvmenv){
        (*m_psJavaVM)->GetEnv(m_psJavaVM,(void**)&gjvmenv, JNI_VERSION_1_4);
    }

    jclass jclass_helpjni   = (*gjvmenv)->FindClass(gjvmenv,(const char*)HELP_JNI_CLASS_NAME);

    JNINativeMethod activity_method_maps[] = {
        { "open",           "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)I",(void *) publish_MainActivity_open },
        { "writeAudio",     "([BIII)I",                 (void *) publish_MainActivity_writeAudio },
        { "writeVideo",     "([BIII)I",                 (void *) publish_MainActivity_writeVideo },
	{ "rtmpWrite",      "([BII)I",                  (void *) publish_MainActivity_rtmpWrite },
        { "close",          "()I",                      (void *) publish_MainActivity_close },
        { "isConnected",    "()I",                      (void *) publish_MainActivity_isConnected },
        { "speex_denoise",  "([BI)I",                   (void *) publish_MainActivity_speex_denoise },
        { "spx_denoise_init",  "(I)I",                   (void *) publish_MainActivity_spx_denoise_init },
        { "spx_denoise_destroy", "()I",                (void *) publish_MainActivity_spx_denoise_destroy },
        { "setup",          "()V",                      (void *) qmtv_native_init },
        { "getKps",          "()I",                      (void *) qmtv_native_getKps },
        { "getRate",          "()I",                      (void *) qmtv_native_getRate },
        { "caculatekps",          "()I",                      (void *) qmtv_native_caculatekps },
    };
    (*gjvmenv)->RegisterNatives(gjvmenv,jclass_helpjni,activity_method_maps,sizeof(activity_method_maps)/sizeof(activity_method_maps[0]));

    return JNI_VERSION_1_4;
}
