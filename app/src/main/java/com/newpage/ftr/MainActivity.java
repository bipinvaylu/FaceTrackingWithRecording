package com.newpage.ftr;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.newpage.trlib.FFmpegRecorder;
import com.newpage.trlib.FFmpegRecorderListener;
import com.newpage.trlib.FixedRatioCroppedTextureView;
import com.newpage.trlib.trfacetracker.TRFaceTracker;
import com.newpage.trlib.util.CameraHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int PREFERRED_PREVIEW_WIDTH = 640;
    private static final int PREFERRED_PREVIEW_HEIGHT = 480;

    private FFmpegRecorder fFmpegRecorder;
    // Workaround for https://code.google.com/p/android/issues/detail?id=190966
    private Runnable doAfterAllPermissionsGranted;


    private TRFaceTracker trFaceTracker;
    private int trackerState = 0; // 0-NA, 1-Starting,  2-Within Frame, 3-Outside frame, -1-Error
    private TextView mInfo;

    private Handler mHandler;
    private Runnable mUpdateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        FixedRatioCroppedTextureView mPreview = findViewById(R.id.camera_preview);
        mInfo = findViewById(R.id.info);
        findViewById(R.id.btnRecording).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(fFmpegRecorder == null) return;

                if(fFmpegRecorder.isRecording()) {
                    fFmpegRecorder.stopVideoRecording();
                    ((Button)v).setText("Start Recording");
                } else {
                    String recordedTime = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    File videoFile = CameraHelper.getOutputMediaFile(recordedTime, CameraHelper.MEDIA_TYPE_VIDEO);
                    fFmpegRecorder.startVideoRecording(videoFile.getAbsolutePath());
                    ((Button)v).setText("Stop Recording");
                }
            }
        });

        findViewById(R.id.btnTracking).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(trFaceTracker == null) return;

                if(trFaceTracker.isTracking()) {
                    trFaceTracker.stopTracking();
                    ((Button)v).setText("Start Tracking");
                    trackerState = 0;
                } else {
                    trFaceTracker.startTracking();
                    ((Button)v).setText("Stop Tracking");
                    trackerState = 1;
                }
            }
        });


        fFmpegRecorder = new FFmpegRecorder(this, mPreview);
        fFmpegRecorder.setListener(new FFmpegRecorderListener() {
            @Override
            public void onVideoRecorded(final File videoFile) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this,
                                "File stored: " + videoFile.getAbsolutePath(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        trFaceTracker = new TRFaceTracker
                .Builder(fFmpegRecorder, PREFERRED_PREVIEW_WIDTH, PREFERRED_PREVIEW_HEIGHT)
                .build();

        trFaceTracker.setOnFaceFrameListener(new TRFaceTracker.OnFaceFrameListener() {
            @Override
            public void onFaceWithinFrame() {
                Log.d(MainActivity.LOG_TAG,
                        "----- Bipin: onFaceWithinFrame called");
                trackerState = 2;

            }

            @Override
            public void onFaceOutsideFrame() {
                Log.d(MainActivity.LOG_TAG,
                        "----- Bipin: onFaceOutsideFrame called");
                trackerState = 3;
            }
        });

        trFaceTracker.setOnFailureListener(new TRFaceTracker.OnFailureListener() {
            @Override
            public void onFailure(Exception ex) {
                Log.e(MainActivity.LOG_TAG,
                        "----- Bipin: onFailure called");
                trackerState = -1;
            }
        });

        mHandler = new Handler();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        fFmpegRecorder.destory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                // 0-NA, 1-Starting,  2-Within Frame, 3-Outside frame, -1-Error
                String trackerOutput = "NA";
                mInfo.setTextColor(Color.parseColor("#778888"));
                if(trackerState == -1) {
                    trackerOutput = "ERROR";
                    mInfo.setTextColor(Color.parseColor("#880000"));
                } else if(trackerState == 1) {
                    trackerOutput = "Starting...";
                    mInfo.setTextColor(Color.parseColor("#F08822"));
                } else if(trackerState == 2) {
                    trackerOutput = "Face within frame";
                    mInfo.setTextColor(Color.parseColor("#008800"));
                } else if(trackerState == 3) {
                    trackerOutput = "Face outside frame";
                    mInfo.setTextColor(Color.parseColor("#FF8822"));
                }
                String info = String.format(
                        "Is recording: %s \nIs tracking: %s \nTracker output: %s",
                        fFmpegRecorder.isRecording() ? "Yes" : "No",
                        trFaceTracker.isTracking() ? "Yes" : "No",
                        trackerOutput
                );
                mInfo.setText(info);
                mHandler.post(this);
            }
        };

        mHandler.post(mUpdateRunnable);

        if (doAfterAllPermissionsGranted != null) {
            doAfterAllPermissionsGranted.run();
            doAfterAllPermissionsGranted = null;
        } else {
            String[] neededPermissions = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
            };
            List<String> deniedPermissions = new ArrayList<>();
            for (String permission : neededPermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permission);
                }
            }
            if (deniedPermissions.isEmpty()) {
                // All permissions are granted
                doAfterAllPermissionsGranted();
            } else {
                String[] array = new String[deniedPermissions.size()];
                array = deniedPermissions.toArray(array);
                ActivityCompat.requestPermissions(this, array, REQUEST_PERMISSIONS);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        fFmpegRecorder.stopCameraPreview();
        trFaceTracker.stopTracking();
        fFmpegRecorder.stopVideoRecording();
        mHandler.removeCallbacks(mUpdateRunnable);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean permissionsAllGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    permissionsAllGranted = false;
                    break;
                }
            }
            if (permissionsAllGranted) {
                doAfterAllPermissionsGranted = new Runnable() {
                    @Override
                    public void run() {
                        doAfterAllPermissionsGranted();
                    }
                };
            } else {
                doAfterAllPermissionsGranted = new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, R.string.permissions_denied_exit, Toast.LENGTH_SHORT).show();
                        finish();
                    }
                };
            }
        }
    }

    private void doAfterAllPermissionsGranted() {
        fFmpegRecorder.startCameraPreview();
    }

}
