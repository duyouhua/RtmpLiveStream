#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "rtmp.h"
#include "log.h"
#include "xiecc_rtmp.h"
#include <android/log.h>
#include <pthread.h>
#define AAC_ADTS_HEADER_SIZE 7
#define FLV_TAG_HEAD_LEN 11
#define FLV_PRE_TAG_LEN 4

#define  LOG_TAG    "rtmp-muxer"

#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const AVal av_onMetaData = AVC("onMetaData");
static const AVal av_duration = AVC("duration");
static const AVal av_width = AVC("width");
static const AVal av_height = AVC("height");
static const AVal av_videocodecid = AVC("videocodecid");
static const AVal av_avcprofile = AVC("avcprofile");
static const AVal av_avclevel = AVC("avclevel");
static const AVal av_fps = AVC("fps");
static const AVal av_bps = AVC("bps");
static const AVal av_audiocodecid = AVC("audiocodecid");
static const AVal av_audiosamplerate = AVC("audiosamplerate");
static const AVal av_audiochannels = AVC("audiochannels");
static const AVal av_avc1 = AVC("avc1");
static const AVal av_mp4a  = AVC("mp4a");
static const AVal av_onPrivateData = AVC("onPrivateData");
static const AVal av_record = AVC("record");
static const AVal av_build = AVC("build");
static const AVal av_os = AVC("os");
static const AVal av_version = AVC("version");
static const AVal av_device = AVC("device");
static const AVal av_network = AVC("network");
static const AVal av_author = AVC("author");
static const AVal av_copyright = AVC("copyright");

static pthread_mutex_t v_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t vs_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t vl_mutex = PTHREAD_MUTEX_INITIALIZER;
static uint32_t total_packet = 0;
static uint32_t losed_packet_rate = 0;
static uint32_t losed_packet = 0;
static uint32_t sended_size = 0;
static uint32_t sended_size_kps = 0;
#define UINT32_MAX_VALUE 2147483640; 

static FILE *g_file_log = NULL;
static char * log_filename = "/sdcard/rmtp.log";
RTMP *rtmp;

static FILE *g_file_handle = NULL;
static uint64_t g_time_begin;

int video_config_ok = 0;
int audio_config_ok = 0;

void init_rtmp_log_file()
{
     g_file_log = fopen(log_filename, "wb");
     if(!g_file_log)
     {
        LOGE("fail to open rtmp log file:%s",log_filename);
	return;
     }
     RTMP_LogSetOutput(g_file_log);

}

void close_rtmp_log_file()
{
     RTMP_LogSetOutput(NULL);
     if(g_file_log)
	 fclose(g_file_log);
     g_file_log = NULL;
}
void flv_file_open(const char *filename) {
    if (NULL == filename) {
        return;
    }

    g_file_handle = fopen(filename, "wb");

    return;
}

void flv_file_close() {
    if (g_file_handle) {
        fclose(g_file_handle);
    }
}

//void write_flv_header(bool is_have_audio, bool is_have_video) {
//    char flv_file_header[] = "FLV\x1\x5\0\0\0\x9\0\0\0\0"; // have audio and have video
//
//    if (is_have_audio && is_have_video) {
//        flv_file_header[4] = 0x05;
//    } else if (is_have_audio && !is_have_video) {
//        flv_file_header[4] = 0x04;
//    } else if (!is_have_audio && is_have_video) {
//        flv_file_header[4] = 0x01;
//    } else {
//        flv_file_header[4] = 0x00;
//    }
//
//    fwrite(flv_file_header, 13, 1, g_file_handle);
//
//    return;
//}

static uint8_t gen_audio_tag_header()
{
    /*

    UB [4] Format of SoundData. The following values are defined:
    0 = Linear PCM, platform endian
    1 = ADPCM
    2 = MP3
    3 = Linear PCM, little endian
    4 = Nellymoser 16 kHz mono
    5 = Nellymoser 8 kHz mono
    6 = Nellymoser
    7 = G.711 A-law logarithmic PCM
    8 = G.711 mu-law logarithmic PCM
    9 = reserved
    10 = AAC *****************
    11 = Speex
    14 = MP3 8 kHz
    15 = Device-specific sound

   SoundRate UB [2] Sampling rate. The following values are defined:
    0 = 5.5 kHz
    1 = 11 kHz
    2 = 22 kHz
    3 = 44 kHz  ************* specification says mark it always 44khz

    SoundSize UB [1]

    to 16 bits internally.
    0 = 8-bit samples
    1 = 16-bit samples *************

    SoundType UB [1] Mono or stereo sound
    0 = Mono sound
    1 = Stereo sound ***********  specification says: even if sound is not stereo, mark as stereo


    */
    uint8_t soundType = 1; // should be always 1 - stereo --- config.channel_configuration - 1; //0 mono, 1 stero
    uint8_t soundRate = 3;  //44Khz it should be always 44Khx
    uint8_t val = 0;

    /*
    switch (config.sample_frequency_index) {
        case 10: { //11.025k
            soundRate = 1;
            break;
        }
        case 7: { //22k
            soundRate = 2;
            break;
        }
        case 4: { //44k
            soundRate = 3;
            break;
        }
        default:
        {
            return val;
        }
    }
    */
    // 0xA0 means this is AAC
    //soundrate << 2  44khz
    // 0x02 means there are 16 bit samples
    val = 0xA0 | (soundRate << 2) | 0x02 | soundType;
    return val;
}

int rtmp_open_for_write(const char *url, const char *device, const char *version,const char *network, int bps, int fps) {
    //init_rtmp_log_file();
    rtmp = RTMP_Alloc();
    if (rtmp == NULL) {
        return -1;
    }

    RTMP_Init(rtmp);
    int ret = RTMP_SetupURL(rtmp, url);

    if (!ret) {
        RTMP_Free(rtmp);
        rtmp = NULL;
        return -2;
    }

    RTMP_EnableWrite(rtmp);


    ret = RTMP_Connect(rtmp, NULL);
    if (!ret) {
        RTMP_Free(rtmp);
        rtmp = NULL;
        return -3;
    }
    ret = RTMP_ConnectStream(rtmp, 0);

    if (!ret) {
        RTMP_Free(rtmp);
        rtmp = NULL;
        return -4;
    }

    video_config_ok = 0;
    audio_config_ok = 0;

    if (RTMP_IsConnected(rtmp)) {

        uint32_t offset = 0;
        char buffer[512];
        char *output = buffer;
        char *outend = buffer + sizeof(buffer);
        char send_buffer[512];
		//AVal av_os_val = AVC("Android");
		AVal av_device_val = {device,strlen(device)};
		AVal av_version_val = {version,strlen(version)};
		AVal av_network_val = {network,strlen(network)};
		AVal av_copyright_val = AVC("@QuanMin.TV");
		//AVal av_author_val = AVC("Deng.HaiFeng");
		AVal av_build_val = AVC(__DATE__);

        output = AMF_EncodeString(output, outend, &av_onMetaData);
        *output++ = AMF_ECMA_ARRAY;

        output = AMF_EncodeInt32(output, outend, AMF_OBJECT);
		output = AMF_EncodeNamedNumber(output, outend, &av_fps, fps);
		output = AMF_EncodeNamedNumber(output, outend, &av_bps, bps);
		//output = AMF_EncodeNamedString(output, outend, &av_os, &av_os_val);
		output = AMF_EncodeNamedString(output, outend, &av_version, &av_version_val);
		output = AMF_EncodeNamedString(output, outend, &av_device, &av_device_val);
		output = AMF_EncodeNamedString(output, outend, &av_network, &av_network_val);
		output = AMF_EncodeNamedString(output, outend, &av_copyright, &av_copyright_val);
		//output = AMF_EncodeNamedString(output, outend, &av_author, &av_author_val);
		output = AMF_EncodeNamedString(output, outend, &av_build, &av_build_val);
        output = AMF_EncodeInt24(output, outend, AMF_OBJECT_END);

        int body_len = output - buffer;
        int output_len = body_len + FLV_TAG_HEAD_LEN + FLV_PRE_TAG_LEN;

        send_buffer[offset++] = 0x12; //tagtype scripte
        send_buffer[offset++] = (uint8_t) (body_len >> 16); //data len
        send_buffer[offset++] = (uint8_t) (body_len >> 8); //data len
        send_buffer[offset++] = (uint8_t) (body_len); //data len
        send_buffer[offset++] = 0; //time stamp
        send_buffer[offset++] = 0; //time stamp
        send_buffer[offset++] = 0; //time stamp
        send_buffer[offset++] = 0; //time stamp
        send_buffer[offset++] = 0x00; //stream id 0
        send_buffer[offset++] = 0x00; //stream id 0
        send_buffer[offset++] = 0x00; //stream id 0

        memcpy(send_buffer + offset, buffer, body_len);

        //init_signal_action();
        //init_timer();

        return RTMP_Write(rtmp, send_buffer, output_len);
    }
    return -1;
}

int rtmp_close() {
    if (rtmp) {
        RTMP_Close(rtmp);
        RTMP_Free(rtmp);
        rtmp = NULL;
    }
    //close_rtmp_log_file();
}

int rtmp_is_connected()
{
    if (rtmp) {
        if (RTMP_IsConnected(rtmp)) {
            return 1;
        }
    }
    return 0;
}


// @brief send audio frame
// @param [in] data       : AACAUDIODATA
// @param [in] size       : AACAUDIODATA size
// @param [in] dts_us     : decode timestamp of frame
// @param [in] abs_ts     : indicate whether you'd like to use absolute time stamp
int rtmp_sender_write_audio_frame(uint8_t *data,
                                  int size,
                                  uint64_t dts_us,
                                  uint32_t abs_ts)
{

    int val = 0;
    uint32_t audio_ts = (uint32_t)dts_us;
    uint32_t offset;
    uint32_t body_len;
    uint32_t output_len;
    char *output ;

    //Audio OUTPUT
    offset = 0;

    if (audio_config_ok == 0) {
        // first packet is two bytes AudioSpecificConfig

        //rtmp_xiecc->config = gen_config(audio_frame);
        body_len = 2 + 2; //AudioTagHeader + AudioSpecificConfig
        output_len = body_len + FLV_TAG_HEAD_LEN + FLV_PRE_TAG_LEN;
        output = malloc(output_len);
        // flv tag header
        output[offset++] = 0x08; //tagtype audio
        output[offset++] = (uint8_t)(body_len >> 16); //data len
        output[offset++] = (uint8_t)(body_len >> 8); //data len
        output[offset++] = (uint8_t)(body_len); //data len
        output[offset++] = (uint8_t)(audio_ts >> 16); //time stamp
        output[offset++] = (uint8_t)(audio_ts >> 8); //time stamp
        output[offset++] = (uint8_t)(audio_ts); //time stamp
        output[offset++] = (uint8_t)(audio_ts >> 24); //time stamp
        output[offset++] = abs_ts; //stream id 0
        output[offset++] = 0x00; //stream id 0
        output[offset++] = 0x00; //stream id 0

        //flv AudioTagHeader
        output[offset++] = gen_audio_tag_header(); // sound format aac
        output[offset++] = 0x00; //aac sequence header

        //flv VideoTagBody --AudioSpecificConfig
        //    uint8_t audio_object_type = rtmp_xiecc->config.audio_object_type;
        output[offset++] = data[0]; //(audio_object_type << 3)|(rtmp_xiecc->config.sample_frequency_index >> 1);
        output[offset++] = data[1]; //((rtmp_xiecc->config.sample_frequency_index & 0x01) << 7) \
                           //| (rtmp_xiecc->config.channel_configuration << 3) ;
        //no need to set pre_tag_size

        uint32_t fff = body_len + FLV_TAG_HEAD_LEN;
        output[offset++] = (uint8_t)(fff >> 24); //data len
        output[offset++] = (uint8_t)(fff >> 16); //data len
        output[offset++] = (uint8_t)(fff >> 8); //data len
        output[offset++] = (uint8_t)(fff); //data len

        if (g_file_handle) {
            fwrite(output, output_len, 1, g_file_handle);
        }
        val = RTMP_Write(rtmp, output, output_len);
        free(output);
        //rtmp_xiecc->audio_config_ok = 1;
        audio_config_ok = 1;
    }
    else {

        body_len = 2 +
                   size; //aac header + raw data size // adts_len - AAC_ADTS_HEADER_SIZE; // audito tag header + adts_len - remove adts header + AudioTagHeader
        output_len = body_len + FLV_TAG_HEAD_LEN + FLV_PRE_TAG_LEN;
        output = malloc(output_len);
        // flv tag header
        output[offset++] = 0x08; //tagtype audio
        output[offset++] = (uint8_t) (body_len >> 16); //data len
        output[offset++] = (uint8_t) (body_len >> 8); //data len
        output[offset++] = (uint8_t) (body_len); //data len
        output[offset++] = (uint8_t) (audio_ts >> 16); //time stamp
        output[offset++] = (uint8_t) (audio_ts >> 8); //time stamp
        output[offset++] = (uint8_t) (audio_ts); //time stamp
        output[offset++] = (uint8_t) (audio_ts >> 24); //time stamp
        output[offset++] = abs_ts; //stream id 0
        output[offset++] = 0x00; //stream id 0
        output[offset++] = 0x00; //stream id 0

        //flv AudioTagHeader
        output[offset++] = gen_audio_tag_header(); // sound format aac
        output[offset++] = 0x01; //aac raw data

        //flv VideoTagBody --raw aac data
        memcpy(output + offset, data, size); // data + AAC_ADTS_HEADER_SIZE -> data,
        // (adts_len - AAC_ADTS_HEADER_SIZE) -> size


        //previous tag size
        uint32_t fff = body_len + FLV_TAG_HEAD_LEN;
        offset += size; // (adts_len - AAC_ADTS_HEADER_SIZE);
        output[offset++] = (uint8_t) (fff >> 24); //data len
        output[offset++] = (uint8_t) (fff >> 16); //data len
        output[offset++] = (uint8_t) (fff >> 8); //data len
        output[offset++] = (uint8_t) (fff); //data len

        if (g_file_handle) {
            fwrite(output, output_len, 1, g_file_handle);
        }
        val = RTMP_Write(rtmp, output, output_len);
        free(output);
    }
    return (val > 0) ? 0: -1;
}

static uint32_t find_start_code(uint8_t *buf, uint32_t zeros_in_startcode)
{
    uint32_t info;
    uint32_t i;

    info = 1;
    if ((info = (buf[zeros_in_startcode] != 1)? 0: 1) == 0)
        return 0;

    for (i = 0; i < zeros_in_startcode; i++)
        if (buf[i] != 0)
        {
            info = 0;
            break;
        };

    return info;
}
/**
 *
 * len: the packet of data that  will be determined
 *
 *
 * total:
 * total size of the packet
 */
static uint8_t * get_nal(uint32_t *len, uint8_t **offset, uint8_t *start, uint32_t total)
{
    uint32_t info;
    uint8_t *q ;
    uint8_t *p  =  *offset;
    *len = 0;

    if ((p - start) >= total){
	LOGE("start of get_nal err p-start=%d,total=%d",p-start,total);
        return NULL;
    }

    while(1) {
        info =  find_start_code(p, 3);
        if (info == 1)
            break;
        p++;
        if ((p - start) >= total){
	    LOGE("middle of get_nal err p-start=%d,total=%d",p-start,total);
            return NULL;
	}
    }
    q = p + 4;
    p = q;
    while(1) {
        info =  find_start_code(p, 3);
        if (info == 1)
            break;
        p++;
        if ((p - start) >= total)
            //return NULL;
            break;
    }

    *len = (p - q);
    *offset = p;
    return q;
}


// @brief send video frame, now only H264 supported
// @param [in] rtmp_sender handler
// @param [in] size       : video data size
// @param [in] dts_us     : decode timestamp of frame
// @param [in] key        : key frame indicate, [0: non key] [1: key]
// @param [in] abs_ts     : indicate whether you'd like to use absolute time stamp
int rtmp_sender_write_video_frame(uint8_t *data,
                                  int size,
                                  uint64_t dts_us,
                                  int key,
                                  uint32_t abs_ts)
{
    uint8_t * buf;
    uint8_t * buf_offset;
    int val = 0;
    int total;
    uint32_t ts;
    uint32_t nal_len;
    uint32_t nal_len_n;
    uint8_t *nal;
    uint8_t *nal_n;
    char *output ;
    uint32_t offset = 0;
    uint32_t body_len;
    uint32_t output_len;

    buf = data;
    buf_offset = data;
    total = size;
    ts = (uint32_t)dts_us;
    //ts = RTMP_GetTime() - start_time;
    offset = 0;

    //LOGE("data:%d,%d,%d,%d",data[0],data[1],data[2],data[3]);
    nal = get_nal(&nal_len, &buf_offset, buf, total);
    if (nal == NULL) {
        LOGE("not found nal unit.");
        return -1;
    }
    if (nal[0] == 0x67)  {
        LOGE("GET AVC PPS");
        if (video_config_ok == 1) {
            RTMP_Log(RTMP_LOGERROR, "video config is already set");
            LOGE("video config is already set");
            //only send video seq set once;
            return -1;
        }
        nal_n  = get_nal(&nal_len_n, &buf_offset, buf, total); //get pps
        if (nal_n == NULL) {
            RTMP_Log(RTMP_LOGERROR, "No Nal after SPS\n");
            LOGE("No Nal after SPS");
            return -1;
        }
	LOGE("GET AVC PPS");
        body_len = nal_len + nal_len_n + 16;
        output_len = body_len + FLV_TAG_HEAD_LEN + FLV_PRE_TAG_LEN;
        output = malloc(output_len);
        if (!output) {
            LOGD("Memory is not allocated...");
        }
        // flv tag header
        output[offset++] = 0x09; //tagtype video
        output[offset++] = (uint8_t)(body_len >> 16); //data len
        output[offset++] = (uint8_t)(body_len >> 8); //data len
        output[offset++] = (uint8_t)(body_len); //data len
        output[offset++] = (uint8_t)(ts >> 16); //time stamp
        output[offset++] = (uint8_t)(ts >> 8); //time stamp
        output[offset++] = (uint8_t)(ts); //time stamp
        output[offset++] = (uint8_t)(ts >> 24); //time stamp
        output[offset++] = abs_ts; //stream id 0
        output[offset++] = 0x00; //stream id 0
        output[offset++] = 0x00; //stream id 0

        //flv VideoTagHeader
        output[offset++] = 0x17; //key frame, AVC
        output[offset++] = 0x00; //avc sequence header
        output[offset++] = 0x00; //composit time ??????????
        output[offset++] = 0x00; // composit time
        output[offset++] = 0x00; //composit time

        //flv VideoTagBody --AVCDecoderCOnfigurationRecord
        output[offset++] = 0x01; //configurationversion
        output[offset++] = nal[1]; //avcprofileindication
        output[offset++] = nal[2]; //profilecompatibilty
        output[offset++] = nal[3]; //avclevelindication
        output[offset++] = 0xff; //reserved + lengthsizeminusone
        output[offset++] = 0xe1; //numofsequenceset
        output[offset++] = (uint8_t)(nal_len >> 8); //sequence parameter set length high 8 bits
        output[offset++] = (uint8_t)(nal_len); //sequence parameter set  length low 8 bits
        memcpy(output + offset, nal, nal_len); //H264 sequence parameter set
        offset += nal_len;
        output[offset++] = 0x01; //numofpictureset
        output[offset++] = (uint8_t)(nal_len_n >> 8); //picture parameter set length high 8 bits
        output[offset++] = (uint8_t)(nal_len_n); //picture parameter set length low 8 bits
        memcpy(output + offset, nal_n, nal_len_n); //H264 picture parameter set

        //no need set pre_tag_size ,RTMP NO NEED
        // flv test

        offset += nal_len_n;
        uint32_t fff = body_len + FLV_TAG_HEAD_LEN;
        output[offset++] = (uint8_t)(fff >> 24); //data len
        output[offset++] = (uint8_t)(fff >> 16); //data len
        output[offset++] = (uint8_t)(fff >> 8); //data len
        output[offset++] = (uint8_t)(fff); //data len

        if (g_file_handle) {
           //fwrite(output, output_len, 1, g_file_handle);
        }
        val = RTMP_Write(rtmp, output, output_len);
        //RTMP Send out
        free(output);
        video_config_ok = 1;
    }
    else if (nal[0] == 0x65)
    {
        body_len = nal_len + 5 + 4; //flv VideoTagHeader +  NALU length
        output_len = body_len + FLV_TAG_HEAD_LEN + FLV_PRE_TAG_LEN;
        output = malloc(output_len);
        if (!output) {
            LOGD("Memory is not allocated...");
        }
        // flv tag header
        output[offset++] = 0x09; //tagtype video
        output[offset++] = (uint8_t)(body_len >> 16); //data len
        output[offset++] = (uint8_t)(body_len >> 8); //data len
        output[offset++] = (uint8_t)(body_len); //data len
        output[offset++] = (uint8_t)(ts >> 16); //time stamp
        output[offset++] = (uint8_t)(ts >> 8); //time stamp
        output[offset++] = (uint8_t)(ts); //time stamp
        output[offset++] = (uint8_t)(ts >> 24); //time stamp
        output[offset++] = abs_ts; //stream id 0
        output[offset++] = 0x00; //stream id 0
        output[offset++] = 0x00; //stream id 0

        //flv VideoTagHeader
        output[offset++] = 0x17; //key frame, AVC
        output[offset++] = 0x01; //avc NALU unit
        output[offset++] = 0x00; //composit time ??????????
        output[offset++] = 0x00; // composit time
        output[offset++] = 0x00; //composit time

        output[offset++] = (uint8_t)(nal_len >> 24); //nal length
        output[offset++] = (uint8_t)(nal_len >> 16); //nal length
        output[offset++] = (uint8_t)(nal_len >> 8); //nal length
        output[offset++] = (uint8_t)(nal_len); //nal length
        memcpy(output + offset, nal, nal_len);

        //no need set pre_tag_size ,RTMP NO NEED

        offset += nal_len;
        uint32_t fff = body_len + FLV_TAG_HEAD_LEN;
        output[offset++] = (uint8_t)(fff >> 24); //data len
        output[offset++] = (uint8_t)(fff >> 16); //data len
        output[offset++] = (uint8_t)(fff >> 8); //data len
        output[offset++] = (uint8_t)(fff); //data len

        if (g_file_handle) {
            //fwrite(output, output_len, 1, g_file_handle);
        }
        val = RTMP_Write(rtmp, output, output_len);
        //RTMP Send out
        free(output);
    }

    else if ((nal[0] & 0x1f) == 0x01)
    {
        body_len = nal_len + 5 + 4; //flv VideoTagHeader +  NALU length
        output_len = body_len + FLV_TAG_HEAD_LEN + FLV_PRE_TAG_LEN;
        output = malloc(output_len);
        if (!output) {
            LOGD("Memory is not allocated...");
        }
        // flv tag header
        output[offset++] = 0x09; //tagtype video
        output[offset++] = (uint8_t)(body_len >> 16); //data len
        output[offset++] = (uint8_t)(body_len >> 8); //data len
        output[offset++] = (uint8_t)(body_len); //data len
        output[offset++] = (uint8_t)(ts >> 16); //time stamp
        output[offset++] = (uint8_t)(ts >> 8); //time stamp
        output[offset++] = (uint8_t)(ts); //time stamp
        output[offset++] = (uint8_t)(ts >> 24); //time stamp
        output[offset++] = abs_ts; //stream id 0
        output[offset++] = 0x00; //stream id 0
        output[offset++] = 0x00; //stream id 0

        //flv VideoTagHeader
        output[offset++] = 0x27; //not key frame, AVC
        output[offset++] = 0x01; //avc NALU unit
        output[offset++] = 0x00; //composit time ??????????
        output[offset++] = 0x00; // composit time
        output[offset++] = 0x00; //composit time

        output[offset++] = (uint8_t)(nal_len >> 24); //nal length
        output[offset++] = (uint8_t)(nal_len >> 16); //nal length
        output[offset++] = (uint8_t)(nal_len >> 8); //nal length
        output[offset++] = (uint8_t)(nal_len); //nal length
        memcpy(output + offset, nal, nal_len);

        //no need set pre_tag_size ,RTMP NO NEED

        offset += nal_len;
        uint32_t fff = body_len + FLV_TAG_HEAD_LEN;
        output[offset++] = (uint8_t)(fff >> 24); //data len
        output[offset++] = (uint8_t)(fff >> 16); //data len
        output[offset++] = (uint8_t)(fff >> 8); //data len
        output[offset++] = (uint8_t)(fff); //data len

        if (g_file_handle) {
            //fwrite(output, output_len, 1, g_file_handle);
        }
        val = RTMP_Write(rtmp, output, output_len);

        //RTMP Send out
        free(output);
    } else {
	    LOGE("not known frame:%d",nal[0]);
    }

    pthread_mutex_lock(&v_mutex); 
    total_packet++;
    if(val > 0) {
        sended_size += val;
    } else {
        losed_packet++;
    }
    pthread_mutex_unlock(&v_mutex);

    return (val > 0) ? 0: -1;
}
#if 0
void init_signal_action()  { 
    struct sigaction act;
    act.sa_handler = signal_handle;
    act.sa_flags = 0;
    sigemptyset(&act.sa_mask);
    sigaction(SIGPROF,&act,NULL);
}

void init_timer()  {
    struct itimerval value;
    value.it_value.tv_sec = 2;
    value.it_value.tv_usec = 0;
    value.it_interval=value.it_value;
    setitimer(ITIMER_PROF,&value,NULL);
}
#endif

void caculatekps() {
     pthread_mutex_lock(&v_mutex);

     sended_size_kps = sended_size/ 2000 ;
     if(total_packet){
        losed_packet_rate = losed_packet * 100 / total_packet;
     }else{
        losed_packet_rate = 0;
     }
     sended_size = 0;
     losed_packet = 0;
     total_packet = 0;

     pthread_mutex_unlock(&v_mutex);
     //LOGE("testing kps:%d   losed packet rate: %d", sended_size_kps, losed_packet_rate);
     //notifyKpsAndRateJNI(sended_size_kps,losed_packet_rate);
}

int getSendedKps(){
    int kps;
    pthread_mutex_lock(&v_mutex);
    kps = sended_size_kps;
    pthread_mutex_unlock(&v_mutex);
    return kps;
}

int getPacketRate() {
    int rate;
    pthread_mutex_lock(&v_mutex);
    rate = losed_packet_rate;
    pthread_mutex_unlock(&v_mutex);
    return rate;
}
int rtmp_sender_rtmpWrite(uint8_t *data,int offset,int size)
{
     int val = 0;
     //LOGE("rtmpWrite:data =%d,offset =%d,size=%d",data,offset,size);
     val = RTMP_Write(rtmp, data+offset,size);
     pthread_mutex_lock(&v_mutex);
     total_packet++;
     if(val > 0) {
         sended_size += val;
     } else {
         losed_packet++;
     }
     pthread_mutex_unlock(&v_mutex);
     return (val > 0) ? 0 : -1; 
}
