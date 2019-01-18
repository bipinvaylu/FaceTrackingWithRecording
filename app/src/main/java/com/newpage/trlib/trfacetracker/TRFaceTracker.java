package com.newpage.trlib.trfacetracker;

import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.face.FirebaseVisionFace;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetector;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions;
import com.newpage.trlib.FFmpegRecorder;
import com.newpage.trlib.FrameProcessor;
import com.newpage.trlib.NPFrame;
import com.newpage.trlib.NPSize;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;


/**
 * This class is providing the feature of tracking the person face in live camera preview.
 */
public class TRFaceTracker {

    private static final float MIN_FACE_SIZE = 0.25f;
    private static final int RIGHT_ANGLE = 90;
    private static final int ACCEPTABLE_HEAD_TILT_DEGREE = 20;

    private FirebaseVisionFaceDetector faceDetector;
    private FirebaseVisionFaceDetectorOptions options;

    private volatile boolean isTracking = false;

    private FFmpegRecorder fFmpegRecorder;
    private int cameraViewWidth;
    private int cameraViewHeight;
    private int acceptableHeadTiltLevel;
    private float minFaceSize;

    private OnFaceFrameListener faceFrameListener;
    private OnHeadOverTiltListener headOverTiltListener;
    private OnFailureListener failureListener;

    private TRFaceTracker(FFmpegRecorder fFmpegRecorder, int cameraViewWidth, int cameraHeight, int acceptableHeadTiltLevel, float minFaceSize) {
        this.fFmpegRecorder = fFmpegRecorder;
        this.cameraViewWidth = cameraViewWidth;
        this.cameraViewHeight = cameraHeight;
        this.acceptableHeadTiltLevel = acceptableHeadTiltLevel;
        this.minFaceSize = minFaceSize;

        options =
                new FirebaseVisionFaceDetectorOptions.Builder()
                        .setPerformanceMode(FirebaseVisionFaceDetectorOptions.FAST)
                        .setLandmarkMode(FirebaseVisionFaceDetectorOptions.NO_LANDMARKS)
                        .setClassificationMode(FirebaseVisionFaceDetectorOptions.ALL_CLASSIFICATIONS)
                        .setMinFaceSize(this.minFaceSize)
                        .enableTracking()
                        .build();

        faceDetector = FirebaseVision.getInstance().getVisionFaceDetector(options);
    }

    public void startTracking() {

        fFmpegRecorder.setFrameProcessor(new FrameProcessor() {
            @Override
            public void process(@NonNull NPFrame frame) {
                if (isTracking) {
                    processFrame(frame);
                }
            }
        });

        isTracking = true;
    }

    public void stopTracking() {
        isTracking = false;
        fFmpegRecorder.clearFrameProcessor();

    }

    public boolean isTracking() {
        return isTracking;
    }

    public void destroy() {
        try {
            faceDetector.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setOnFaceFrameListener(OnFaceFrameListener faceFrameListener) {
        this.faceFrameListener = faceFrameListener;
    }

    public void setOnHeadOverTiltListener(OnHeadOverTiltListener headOverTiltListener) {
        this.headOverTiltListener = headOverTiltListener;
    }

    public void setOnFailureListener(OnFailureListener failureListener) {
        this.failureListener = failureListener;
    }

    private void processFrame(@NonNull NPFrame frame) {
        if (!isTracking) return;

        int format = frame.getFormat();
        if (!(format == FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21 ||
                format == FirebaseVisionImageMetadata.IMAGE_FORMAT_YV12)) {
            format = FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21;
        }

        TRSize trSize = new TRSize(frame.getSize().getWidth(), frame.getSize().getHeight());
        TRFrame trFrame = new TRFrame(
                frame.getData(),
                frame.getRotation(),
                trSize,
                format,
                frame.isCameraFacingBack()
        );
        FirebaseVisionImage fbvi = convertFrameToImage(trFrame);
        faceDetector.detectInImage(fbvi)
                .addOnSuccessListener(onSuccessListener)
                .addOnFailureListener(onFailureListener);
    }

    private FirebaseVisionImage convertFrameToImage(@NonNull TRFrame trFrame) {
        return FirebaseVisionImage.fromByteArray(trFrame.getData(), extractFrameMetadata(trFrame));
    }

    private FirebaseVisionImageMetadata extractFrameMetadata(@NonNull TRFrame trFrame) {

        return new FirebaseVisionImageMetadata.Builder()
                .setWidth(trFrame.getSize().getWidth())
                .setHeight(trFrame.getSize().getHeight())
                .setFormat(trFrame.getFormat())
                .setRotation(trFrame.getRotation() / RIGHT_ANGLE)
                .build();
    }

    private OnSuccessListener<List<FirebaseVisionFace>> onSuccessListener = new OnSuccessListener<List<FirebaseVisionFace>>() {
        @Override
        public void onSuccess(List<FirebaseVisionFace> firebaseVisionFaces) {
            Log.d(TRFaceTracker.class.getCanonicalName(),
                    "Bipin - Thread Name: " + Thread.currentThread().getName()
                            + ", Faces: " + firebaseVisionFaces.size());
            StringBuilder stringBuilder = new StringBuilder();
            if (firebaseVisionFaces.size() > 0) {
                FirebaseVisionFace fbVisionFace = firebaseVisionFaces.get(0);
                float headAngleY = fbVisionFace.getHeadEulerAngleY();
                float headAngleZ = fbVisionFace.getHeadEulerAngleZ();

//                Rect boundingBox = fbVisionFace.getBoundingBox();
//                Log.d(TRFaceTracker.class.getCanonicalName(),
//                        "Bipin - BoundingBox - Left: " + boundingBox.left +
//                                ", Right: " + boundingBox.right + ", Top: " + boundingBox.top +
//                                ", Bottom: " + boundingBox.bottom);
                if (Math.abs(headAngleZ) >= ACCEPTABLE_HEAD_TILT_DEGREE) {
                    if (headOverTiltListener != null) {
                        headOverTiltListener.onHeadOverTilt((int) headAngleZ);
                    }
                    if (headAngleZ < 0)
                        stringBuilder.append("Face Position: Over tilt left");
                    else
                        stringBuilder.append("Face Position: Over tilt right");
                } else {
                    stringBuilder.append("Face Position: Within frame");
                }
//                float smilingProb = fbVisionFace.getSmilingProbability();
//                if (smilingProb != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
//                    stringBuilder.append("\n").append("Smiling Probability: " + smilingProb);
//                }
//                float leOpenProb = fbVisionFace.getLeftEyeOpenProbability();
//                if (leOpenProb != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
//                    stringBuilder.append("\n").append("Left Eye Open Probability: " + leOpenProb);
//                }
//                float reOpenProb = fbVisionFace.getRightEyeOpenProbability();
//                if (reOpenProb != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
//                    stringBuilder.append("\n").append("Right Eye Open Probability: " + reOpenProb);
//                }

                stringBuilder.append("\n").append("Head Angle Y: " + headAngleY);
                stringBuilder.append("\n").append("Head Angle z: " + headAngleZ);
                Log.d(TRFaceTracker.class.getCanonicalName(), "Bipin - " + stringBuilder.toString());

                if (faceFrameListener != null) {
                    faceFrameListener.onFaceWithinFrame();
                }

            } else {
                stringBuilder.append("Face Position: Out of frame");
                Log.d(TRFaceTracker.class.getCanonicalName(), "Bipin - Error - " + stringBuilder.toString());
                if (faceFrameListener != null) {
                    faceFrameListener.onFaceOutsideFrame();
                }
            }
        }
    };

    private com.google.android.gms.tasks.OnFailureListener onFailureListener = new com.google.android.gms.tasks.OnFailureListener() {
        @Override
        public void onFailure(@NonNull Exception e) {
            Log.d(TRFaceTracker.class.getCanonicalName(), "Bipin - Failure - Face out of frame");
            if (failureListener != null) {
                failureListener.onFailure(e);
            }
        }
    };


    /**
     * This builder class is useful to create the object of {@link TRFaceTracker}
     */

    public static class Builder {
        private FFmpegRecorder fFmpegRecorder;
        private int cameraViewWidth;
        private int cameraViewHeight;

        private int acceptableHeadTiltLevel = ACCEPTABLE_HEAD_TILT_DEGREE;
        private float minFaceSize = MIN_FACE_SIZE;

        public Builder(FFmpegRecorder fFmpegRecorder, int cameraViewWidth, int cameraViewHeight) {
            this.fFmpegRecorder = fFmpegRecorder;
            this.cameraViewWidth = cameraViewWidth;
            this.cameraViewHeight = cameraViewHeight;
        }

        public Builder acceptableHeadTiltLevel(int tiltLevel) {
            this.acceptableHeadTiltLevel = tiltLevel;
            return this;
        }

        public Builder minFaceSize(float minFaceSize) {
            this.minFaceSize = minFaceSize;
            return this;
        }


        public TRFaceTracker build() {
            return new TRFaceTracker(
                    fFmpegRecorder,
                    cameraViewWidth,
                    cameraViewHeight,
                    acceptableHeadTiltLevel,
                    minFaceSize
            );
        }
    }


    /**
     * This interface is tracking the face position within frame or outside frame
     */
    public interface OnFaceFrameListener {
        void onFaceWithinFrame();

        void onFaceOutsideFrame();
    }


    public interface OnHeadOverTiltListener {
        /**
         * Fired when person head is tilt more then acceptable level
         *
         * @param tiltLevel This is int value, +ve for Right tilt and -ve for Left tilt
         */
        void onHeadOverTilt(int tiltLevel);
    }

    public interface OnFailureListener {
        void onFailure(Exception ex);
    }

}
