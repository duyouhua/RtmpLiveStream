package com.androidyuan.publisher.pusher;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import com.androidyuan.publisher.param.AudioParam;
import com.jutong.live.jni.PusherNative;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;

import tv.quanmin.livestreamlibrary.RtmpHelperJNI;

public class AudioPusher extends Pusher {

	private final static String TAG = "AudioPusher";

	private static final String ACODEC = "audio/mp4a-latm";
	private final long presentationTimeUs;

	private AudioParam mParam;
	private int minBufferSize;


	private byte[] audioBuffer;
	private int audioBuffersize;

	private AudioRecord audioRecord;
	private MediaCodec aencoder;
	private MediaCodec.BufferInfo aebi;


	public AudioPusher(AudioParam param, PusherNative pusherNative) {
		super(pusherNative);
		mParam = param;
		// int channel = mParam.getChannel() == 1 ? AudioFormat.CHANNEL_IN_MONO
		// : AudioFormat.CHANNEL_IN_STEREO;

		minBufferSize = AudioRecord.getMinBufferSize(
				mParam.getSampleRate(),
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT
		);

		audioRecord = new AudioRecord(
				MediaRecorder.AudioSource.MIC,
				mParam.getSampleRate(),
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				minBufferSize
		);

		RtmpHelperJNI.spx_denoise_init(mParam.getSampleRate());

		try {
			aencoder = MediaCodec.createEncoderByType(ACODEC);
			aebi = new MediaCodec.BufferInfo();

			// setup the aencoder.
			// @see
			// https://developer.android.com/reference/android/media/MediaCodec.html
			MediaFormat aformat = MediaFormat.createAudioFormat(ACODEC,
					mParam.getSampleRate(),
					mParam.getChannel());

			aformat.setInteger(MediaFormat.KEY_BIT_RATE,24*1000);
			aformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
			aencoder.configure(aformat, null, null,
					MediaCodec.CONFIGURE_FLAG_ENCODE);

		} catch (IOException e) {
			e.printStackTrace();
		}

		presentationTimeUs = new Date().getTime() * 1000;

	}

	@Override
	public void startPusher() {

		if (null == audioRecord) {
			return;
		}

		aencoder.start();

		mPusherRuning = true;
		if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED) {
			try {
				audioRecord.startRecording();
				new Thread(new AudioRecordTask()).start();
			} catch (Throwable th) {
				th.printStackTrace();
				if (null != mListener) {
					mListener.onErrorPusher(PusherNative.MSG_ERROR_AUDIO_RECORD);
				}
			}
		}
	}

	@Override
	public void stopPusher() {
		if (null == audioRecord) {
			return;
		}
		mPusherRuning = false;
		if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING)
			audioRecord.stop();
	}

	@Override
	public void release() {
		if (null == audioRecord) {
			return;
		}

		mPusherRuning = false;
		if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED)
			audioRecord.release();
		audioRecord = null;

		if (aencoder != null) {
			Log.i(TAG, "stop aencoder");
			aencoder.stop();
			aencoder.release();
			aencoder = null;
		}


		RtmpHelperJNI.spx_denoise_destroy();


	}

	/*******************
	 * 聲音 onGetPcmFrame獲取 pcm raw
	 ********************/
	@SuppressLint("NewApi")
	private void onGetPcmFrame(byte[] data) {
		// Log.i(TAG, String.format("got PCM audio, size=%d", data.length));

		// feed the aencoder with yuv frame, got the encoded 264 es stream.
		ByteBuffer[] inBuffers = aencoder.getInputBuffers();
		ByteBuffer[] outBuffers = aencoder.getOutputBuffers();

		if (true) {
			int inBufferIndex = aencoder.dequeueInputBuffer(-1);
			// Log.i(TAG, String.format("try to dequeue input vbuffer, ii=%d",
			// inBufferIndex));
			if (inBufferIndex >= 0) {
				ByteBuffer bb = inBuffers[inBufferIndex];
				bb.clear();
				bb.put(data, 0, data.length);
				long pts = new Date().getTime() * 1000 - presentationTimeUs;
				// Log.i(TAG, String.format("feed PCM to encode %dB, pts=%d",
				// data.length, pts / 1000));
				// SrsHttpFlv.srs_print_bytes(TAG, data, data.length);
				aencoder.queueInputBuffer(inBufferIndex, 0, data.length, pts, 0);
			}
		}

		for (;;) {
			int outBufferIndex = aencoder.dequeueOutputBuffer(aebi, 0);
			// Log.i(TAG,
			// String.format("try to dequeue output vbuffer, ii=%d, oi=%d",
			// inBufferIndex, outBufferIndex));
			if (outBufferIndex >= 0) {
				ByteBuffer bb = outBuffers[outBufferIndex];
				// Log.i(TAG, String.format("encoded aac %dB, pts=%d",
				// aebi.size, aebi.presentationTimeUs / 1000));
				// SrsHttpFlv.srs_print_bytes(TAG, bb, aebi.size);
				onEncodedAacFrame(bb, aebi);
				aencoder.releaseOutputBuffer(outBufferIndex, false);
			} else {
				break;
			}
		}
	}

	/*******************
	 * 聲音 onEncodedAacFrame 編碼AAC
	 ********************/
	@SuppressLint("NewApi")
	private void onEncodedAacFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {

		try {

			int length = es.limit() - es.position();
			if ((audioBuffer == null) || (audioBuffersize < length)) {
				audioBuffer = null;
				audioBuffer = new byte[length];
				audioBuffersize = length;
			}

			es.get(audioBuffer, es.position(), length);
			int ret = RtmpHelperJNI.writeAudio(
					audioBuffer,
					0,
					length,
					(int) bi.presentationTimeUs / 1000);
			if (ret < 0) {
				Log.e(TAG, String.format("write audio sample failed:%d.", ret));

			}
		} catch (Exception e) {
			Log.e(TAG, "muxer write audio sample failed.");
			e.printStackTrace();
		}
	}

	class AudioRecordTask implements Runnable {

		@Override
		public void run() {
			while (mPusherRuning
					&& audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
				final byte[] buffer = new byte[2048];
				int len = audioRecord.read(buffer, 0, buffer.length);

				len = RtmpHelperJNI.speex_denoise(buffer, len);
				byte[] audio = new byte[len];
				System.arraycopy(buffer, 0, audio, 0, len);

				if (0 < len) {
					onGetPcmFrame(audio);
				}
			}
		}
	}


}
