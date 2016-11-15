package com.androidyuan.publisher;

public interface LiveStateChangeListener {

	// 针对视频 ，准备完成
    void onErrorPusher(int code);

	// 开始推流
    void onStartPusher();

	// 停止推流
	void onStopPusher();
}
