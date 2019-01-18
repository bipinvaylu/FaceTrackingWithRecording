package com.newpage.trlib;

import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;

/**
 *
 */

public interface FrameProcessor {
    @WorkerThread
    public void process(@NonNull NPFrame NPFrame);
}
