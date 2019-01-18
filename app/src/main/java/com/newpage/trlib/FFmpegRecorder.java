package com.newpage.trlib;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.newpage.trlib.data.FrameToRecord;
import com.newpage.trlib.data.RecordFragment;
import com.newpage.trlib.util.CameraHelper;
import com.newpage.trlib.util.MiscUtils;

import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameFilter;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.LinkedBlockingQueue;

import static java.lang.Thread.State.WAITING;

public class FFmpegRecorder implements
        TextureView.SurfaceTextureListener {

    private static final String LOG_TAG = FFmpegRecorder.class.getSimpleName();

    private static final int PREFERRED_PREVIEW_WIDTH = 640;
    private static final int PREFERRED_PREVIEW_HEIGHT = 480;

    // both in milliseconds
    private static final long MIN_VIDEO_LENGTH = 1 * 1000;
    private static final long MAX_VIDEO_LENGTH = 90 * 1000;

    private FixedRatioCroppedTextureView mPreview;

    private int mCameraId;
    private Camera mCamera;
    private FFmpegFrameRecorder mFrameRecorder;
    private VideoRecordThread mVideoRecordThread;
    private AudioRecordThread mAudioRecordThread;
    private volatile boolean mRecording = false;
    private File mVideo;
    private LinkedBlockingQueue<FrameToRecord> mFrameToRecordQueue;
    private LinkedBlockingQueue<FrameToRecord> mRecycledFrameQueue;
    private int mFrameToRecordCount;
    private int mFrameRecordedCount;
    private long mTotalProcessFrameTime;
    private Stack<RecordFragment> mRecordFragments;

    private int sampleAudioRateInHz = 44100;
    /* The sides of width and height are based on camera orientation.
    That is, the preview size is the size before it is rotated. */
    private int mPreviewWidth = PREFERRED_PREVIEW_WIDTH;
    private int mPreviewHeight = PREFERRED_PREVIEW_HEIGHT;
    // Output video size
    private int videoWidth = 320;
    private int videoHeight = 240;
    private int frameRate = 30;
    private int frameDepth = Frame.DEPTH_UBYTE;
    private int frameChannels = 2;

    private Activity mActivity;

    private FrameProcessor mFrameProcessor;
    private FFmpegRecorderListener mListener;

    public FFmpegRecorder(
            Activity context,
            FixedRatioCroppedTextureView preview
    ) {
        this.mActivity = context;
        this.mPreview = preview;
        mCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
        setPreviewSize(mPreviewWidth, mPreviewHeight);
        mPreview.setCroppedSizeWeight(videoWidth, videoHeight);
        mPreview.setSurfaceTextureListener(this);

    }

    //TODO: Add multiple frame processor support
    public void setFrameProcessor(FrameProcessor frameProcessor) {
        this.mFrameProcessor = frameProcessor;
    }

    public void clearFrameProcessor() {
        this.mFrameProcessor = null;
    }

    public void setListener(FFmpegRecorderListener listener) {
        this.mListener = listener;
    }

    public void clearListener() {
        this.mListener = null;
    }

    public boolean isRecording() {
        return mRecording;
    }

    public void startCameraPreview() {
        acquireCamera();
        SurfaceTexture surfaceTexture = mPreview.getSurfaceTexture();
        if (surfaceTexture != null) {
            // SurfaceTexture already created
            startPreview(surfaceTexture);
        }
    }

    public void stopCameraPreview() {
        stopPreview();
    }

    public void startVideoRecording(final String videoFilePath) {
        if (!mRecording) {
            // At most buffer 10 Frame
            mFrameToRecordQueue = new LinkedBlockingQueue<>(10);
            // At most recycle 2 Frame
            mRecycledFrameQueue = new LinkedBlockingQueue<>(2);
            mRecordFragments = new Stack<>();

            new ThreadWrapper().doInBackground(new Runnable() {
                @Override
                public void run() {
                    initRecorder(videoFilePath);
                    startRecorder();

                    RecordFragment recordFragment = new RecordFragment();
                    recordFragment.setStartTimestamp(System.currentTimeMillis());
                    mRecordFragments.push(recordFragment);
                    mRecording = true;

                    startRecording();
                }
            });

        }
    }

    public void stopVideoRecording() {
        if (mRecording) {
            new ThreadWrapper().doInBackground(new Runnable() {
                @Override
                public void run() {
                    mRecordFragments.peek().setEndTimestamp(System.currentTimeMillis());
                    mRecording = false;
                    stopRecording();
                    stopRecorder();
                    releaseRecorder(false);

                }
            });

        }
    }

    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture surface, int width, int height) {
        startPreview(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public void destory() {
        stopRecorder();
        releaseRecorder(true);
    }

    private void setPreviewSize(int width, int height) {
        if (MiscUtils.isOrientationLandscape(mActivity)) {
            mPreview.setPreviewSize(width, height);
        } else {
            // Swap width and height
            mPreview.setPreviewSize(height, width);
        }
    }

    private void startPreview(SurfaceTexture surfaceTexture) {
        if (mCamera == null) {
            return;
        }

        Camera.Parameters parameters = mCamera.getParameters();
        List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
        Camera.Size previewSize = CameraHelper.getOptimalSize(previewSizes,
                PREFERRED_PREVIEW_WIDTH, PREFERRED_PREVIEW_HEIGHT);
        // if changed, reassign values and request layout
        if (mPreviewWidth != previewSize.width || mPreviewHeight != previewSize.height) {
            mPreviewWidth = previewSize.width;
            mPreviewHeight = previewSize.height;
            setPreviewSize(mPreviewWidth, mPreviewHeight);
            mPreview.requestLayout();
        }
        parameters.setPreviewSize(mPreviewWidth, mPreviewHeight);
//        parameters.setPreviewFormat(ImageFormat.NV21);
        if (parameters.getSupportedFocusModes().contains(
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }
        mCamera.setParameters(parameters);

        mCamera.setDisplayOrientation(CameraHelper.getCameraDisplayOrientation(
                mActivity, mCameraId));

        // YCbCr_420_SP (NV21) format
        byte[] bufferByte = new byte[mPreviewWidth * mPreviewHeight * 3 / 2];
        mCamera.addCallbackBuffer(bufferByte);
        mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {

            private long lastPreviewFrameTime;

            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                long thisPreviewFrameTime = System.currentTimeMillis();
                if (lastPreviewFrameTime > 0) {
                    Log.d(LOG_TAG, "Preview frame interval: " + (thisPreviewFrameTime - lastPreviewFrameTime) + "ms");
                }
                lastPreviewFrameTime = thisPreviewFrameTime;
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(mCameraId, info);
                int orientation = info.orientation;
                // get video data
                if (mRecording) {
                    if (mAudioRecordThread == null || !mAudioRecordThread.isRunning()) {
                        // wait for AudioRecord to init and start
                        mRecordFragments.peek().setStartTimestamp(System.currentTimeMillis());
                    } else {
                        // pop the current record fragment when calculate total recorded time
                        RecordFragment curFragment = mRecordFragments.pop();
                        long recordedTime = calculateTotalRecordedTime(mRecordFragments);
                        // push it back after calculation
                        mRecordFragments.push(curFragment);
                        long curRecordedTime = System.currentTimeMillis()
                                - curFragment.getStartTimestamp() + recordedTime;
                        // check if exceeds time limit
                        if (curRecordedTime > MAX_VIDEO_LENGTH) {
                            new FinishRecordingTask(false).start();
                            return;
                        }

                        long timestamp = 1000 * curRecordedTime;
                        Frame frame;
                        FrameToRecord frameToRecord = mRecycledFrameQueue.poll();
                        if (frameToRecord != null) {
                            frame = frameToRecord.getFrame();
                            frameToRecord.setTimestamp(timestamp);
                        } else {
                            frame = new Frame(mPreviewWidth, mPreviewHeight, frameDepth, frameChannels);
                            frameToRecord = new FrameToRecord(timestamp, frame);
                        }
                        ((ByteBuffer) frame.image[0].position(0)).put(data);

                        if (mFrameToRecordQueue.offer(frameToRecord)) {
                            mFrameToRecordCount++;
                        }

                        if(mFrameProcessor != null) {
                            mFrameProcessor.process(convertToNPFrame(
                                    frame,
                                    orientation,
                                    avutil.AV_PIX_FMT_NV21,
                                    mCameraId == Camera.CameraInfo.CAMERA_FACING_BACK
                            ));
                        }

                    }
                } else if(mFrameProcessor != null) {
                    Frame frame = new Frame(mPreviewWidth, mPreviewHeight, frameDepth, frameChannels);
                    ((ByteBuffer) frame.image[0].position(0)).put(data);
                    mFrameProcessor.process(convertToNPFrame(
                            frame,
                            orientation,
                            avutil.AV_PIX_FMT_NV21,
                            mCameraId == Camera.CameraInfo.CAMERA_FACING_BACK
                    ));
                }
                mCamera.addCallbackBuffer(data);
            }
        });

        try {
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        mCamera.startPreview();
    }

    private void stopPreview() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallbackWithBuffer(null);
        }
    }

    private void acquireCamera() {
        try {
            mCamera = Camera.open(mCameraId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();        // release the camera for other applications
            mCamera = null;
        }
    }

    private void initRecorder(String filePath) {
        Log.i(LOG_TAG, "init mFrameRecorder");
        mVideo = new File(filePath);
        Log.i(LOG_TAG, "Output Video: " + filePath);

        mFrameRecorder = new FFmpegFrameRecorder(mVideo, videoWidth, videoHeight, 1);
        mFrameRecorder.setFormat("mp4");
        mFrameRecorder.setSampleRate(sampleAudioRateInHz);
        mFrameRecorder.setFrameRate(frameRate);

        // Use H264
        mFrameRecorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        // See: https://trac.ffmpeg.org/wiki/Encode/H.264#crf
        /*
         * The range of the quantizer scale is 0-51: where 0 is lossless, 23 is default, and 51 is worst possible. A lower value is a higher quality and a subjectively sane range is 18-28. Consider 18 to be visually lossless or nearly so: it should look the same or nearly the same as the input but it isn't technically lossless.
         * The range is exponential, so increasing the CRF value +6 is roughly half the bitrate while -6 is roughly twice the bitrate. General usage is to choose the highest CRF value that still provides an acceptable quality. If the output looks good, then try a higher value and if it looks bad then choose a lower value.
         */
        mFrameRecorder.setVideoOption("crf", "28");
        mFrameRecorder.setVideoOption("preset", "superfast");
        mFrameRecorder.setVideoOption("tune", "zerolatency");

        Log.i(LOG_TAG, "mFrameRecorder initialize success");
    }

    private void releaseRecorder(boolean deleteFile) {
        if (mFrameRecorder != null) {
            try {
                mFrameRecorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            mFrameRecorder = null;

            if (deleteFile) {
                mVideo.delete();
            } else if(mListener != null) {
                mListener.onVideoRecorded(mVideo);
            }
        }
    }

    private void startRecorder() {
        try {
            mFrameRecorder.start();
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRecorder() {
        if (mFrameRecorder != null) {
            try {
                mFrameRecorder.stop();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
        }

        mRecordFragments.clear();
    }

    private void startRecording() {
        mAudioRecordThread = new AudioRecordThread();
        mAudioRecordThread.start();
        mVideoRecordThread = new VideoRecordThread();
        mVideoRecordThread.start();
    }

    private void stopRecording() {
        if (mAudioRecordThread != null) {
            if (mAudioRecordThread.isRunning()) {
                mAudioRecordThread.stopRunning();
            }
        }

        if (mVideoRecordThread != null) {
            if (mVideoRecordThread.isRunning()) {
                mVideoRecordThread.stopRunning();
            }
        }

        try {
            if (mAudioRecordThread != null) {
                mAudioRecordThread.join();
            }
            if (mVideoRecordThread != null) {
                mVideoRecordThread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mAudioRecordThread = null;
        mVideoRecordThread = null;


        mFrameToRecordQueue.clear();
        mRecycledFrameQueue.clear();
    }

    private NPFrame convertToNPFrame(Frame frame, int rotation, int format, boolean isBackCamera) {
        NPSize npSize;
        if (frame.imageWidth > 0 &&  frame.imageHeight > 0) {
            npSize = new NPSize(frame.imageWidth,
                    frame.imageHeight);
        } else {
            npSize = new NPSize(videoWidth, videoHeight);
        }


        ByteBuffer buf = ((ByteBuffer) frame.image[0].position(0));
        byte[] arr = new byte[buf.remaining()];
        buf.get(arr);
        return new NPFrame(
                arr,
                rotation,
                npSize,
                format,
                isBackCamera
        );
    }
    private long calculateTotalRecordedTime(Stack<RecordFragment> recordFragments) {
        long recordedTime = 0;
        for (RecordFragment recordFragment : recordFragments) {
            recordedTime += recordFragment.getDuration();
        }
        return recordedTime;
    }

    class RunningThread extends Thread {
        boolean isRunning;

        public boolean isRunning() {
            return isRunning;
        }

        public void stopRunning() {
            this.isRunning = false;
        }
    }

    class AudioRecordThread extends RunningThread {
        private AudioRecord mAudioRecord;
        private ShortBuffer audioData;

        public AudioRecordThread() {
            int bufferSize = AudioRecord.getMinBufferSize(sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            audioData = ShortBuffer.allocate(bufferSize);
        }

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            Log.d(LOG_TAG, "mAudioRecord startRecording");
            mAudioRecord.startRecording();

            isRunning = true;
            /* ffmpeg_audio encoding loop */
            while (isRunning) {
                if (mRecording && mFrameRecorder != null) {
                    int bufferReadResult = mAudioRecord.read(audioData.array(), 0, audioData.capacity());
                    audioData.limit(bufferReadResult);
                    if (bufferReadResult > 0) {
                        Log.v(LOG_TAG, "bufferReadResult: " + bufferReadResult);
                        try {
                            mFrameRecorder.recordSamples(audioData);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(LOG_TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            Log.d(LOG_TAG, "mAudioRecord stopRecording");
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
            Log.d(LOG_TAG, "mAudioRecord released");
        }
    }

    class VideoRecordThread extends RunningThread {
        @Override
        public void run() {
            int previewWidth = mPreviewWidth;
            int previewHeight = mPreviewHeight;

            List<String> filters = new ArrayList<>();
            // Transpose
            String transpose = null;
            String hflip = null;
            String vflip = null;
            String crop = null;
            String scale = null;
            int cropWidth;
            int cropHeight;
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(mCameraId, info);
            int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
            switch (rotation) {
                case Surface.ROTATION_0:
                    switch (info.orientation) {
                        case 270:
                            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                transpose = "transpose=clock_flip"; // Same as preview display
                            } else {
                                transpose = "transpose=cclock"; // Mirrored horizontally as preview display
                            }
                            break;
                        case 90:
                            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                transpose = "transpose=cclock_flip"; // Same as preview display
                            } else {
                                transpose = "transpose=clock"; // Mirrored horizontally as preview display
                            }
                            break;
                    }
                    cropWidth = previewHeight;
                    cropHeight = cropWidth * videoHeight / videoWidth;
                    crop = String.format("crop=%d:%d:%d:%d",
                            cropWidth, cropHeight,
                            (previewHeight - cropWidth) / 2, (previewWidth - cropHeight) / 2);
                    // swap width and height
                    scale = String.format("scale=%d:%d", videoHeight, videoWidth);
                    break;
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    switch (rotation) {
                        case Surface.ROTATION_90:
                            // landscape-left
                            switch (info.orientation) {
                                case 270:
                                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                        hflip = "hflip";
                                    }
                                    break;
                            }
                            break;
                        case Surface.ROTATION_270:
                            // landscape-right
                            switch (info.orientation) {
                                case 90:
                                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                                        hflip = "hflip";
                                        vflip = "vflip";
                                    }
                                    break;
                                case 270:
                                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                        vflip = "vflip";
                                    }
                                    break;
                            }
                            break;
                    }
                    cropHeight = previewHeight;
                    cropWidth = cropHeight * videoWidth / videoHeight;
                    crop = String.format("crop=%d:%d:%d:%d",
                            cropWidth, cropHeight,
                            (previewWidth - cropWidth) / 2, (previewHeight - cropHeight) / 2);
                    scale = String.format("scale=%d:%d", videoWidth, videoHeight);
                    break;
                case Surface.ROTATION_180:
                    break;
            }
            // transpose
            if (transpose != null) {
                filters.add(transpose);
            }
            // horizontal flip
            if (hflip != null) {
                filters.add(hflip);
            }
            // vertical flip
            if (vflip != null) {
                filters.add(vflip);
            }
            // crop
            if (crop != null) {
                filters.add(crop);
            }
            // scale (to designated size)
            if (scale != null) {
                filters.add(scale);
            }

            FFmpegFrameFilter frameFilter = new FFmpegFrameFilter(TextUtils.join(",", filters),
                    previewWidth, previewHeight);
            frameFilter.setPixelFormat(avutil.AV_PIX_FMT_NV21);
            frameFilter.setFrameRate(frameRate);
            try {
                frameFilter.start();
            } catch (FrameFilter.Exception e) {
                e.printStackTrace();
            }

            isRunning = true;
            FrameToRecord recordedFrame;

            while (isRunning || !mFrameToRecordQueue.isEmpty()) {
                try {
                    recordedFrame = mFrameToRecordQueue.take();
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                    try {
                        frameFilter.stop();
                    } catch (FrameFilter.Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }

                if (mFrameRecorder != null) {
                    long timestamp = recordedFrame.getTimestamp();
                    if (timestamp > mFrameRecorder.getTimestamp()) {
                        mFrameRecorder.setTimestamp(timestamp);
                    }
                    long startTime = System.currentTimeMillis();
//                    Frame filteredFrame = recordedFrame.getFrame();
                    Frame filteredFrame = null;
                    try {
                        frameFilter.push(recordedFrame.getFrame());
                        filteredFrame = frameFilter.pull();
                    } catch (FrameFilter.Exception e) {
                        e.printStackTrace();
                    }
                    try {
                        mFrameRecorder.record(filteredFrame);
                    } catch (FFmpegFrameRecorder.Exception e) {
                        e.printStackTrace();
                    }
                    long endTime = System.currentTimeMillis();
                    long processTime = endTime - startTime;
                    mTotalProcessFrameTime += processTime;
                    Log.d(LOG_TAG, "This frame process time: " + processTime + "ms");
                    long totalAvg = mTotalProcessFrameTime / ++mFrameRecordedCount;
                    Log.d(LOG_TAG, "Avg frame process time: " + totalAvg + "ms");
                }
                Log.d(LOG_TAG, mFrameRecordedCount + " / " + mFrameToRecordCount);
                mRecycledFrameQueue.offer(recordedFrame);
            }
        }

        public void stopRunning() {
            super.stopRunning();
            if (getState() == WAITING) {
                interrupt();
            }
        }
    }

    class FinishRecordingTask extends Thread {

        private boolean mDeleteFile;

        FinishRecordingTask(boolean deleteFile) {
            this.mDeleteFile = deleteFile;
        }

        @Override
        public void run() {
            stopRecording();
            stopRecorder();
            releaseRecorder(mDeleteFile);
        }
    }


    class ThreadWrapper {

        private Thread mThread;

        public void doInBackground(Runnable runnable) {
            mThread = new Thread(runnable);
            mThread.start();
        }

    }



}
