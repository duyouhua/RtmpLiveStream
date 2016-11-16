package com.androidyuan.publisher.param;

import tv.quanmin.livestreamlibrary.RtmpHelperJNI;

public class AudioParam {

	private int sampleRate = 44100;
	private int channel = 1;

	public AudioParam(int sampleRate, int channel) {
		this.sampleRate = sampleRate;
		this.channel = channel;
	}

	public AudioParam() {

		RtmpHelperJNI.spx_denoise_init(sampleRate);
	}

	public int getSampleRate() {
		return sampleRate;
	}

	public int getChannel() {
		return channel;
	}
}
