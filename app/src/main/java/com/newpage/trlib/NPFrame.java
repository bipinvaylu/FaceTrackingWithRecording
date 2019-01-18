package com.newpage.trlib;

/**
 *
 */
public class NPFrame {
    private byte[] mData;
    private int mRotation;
    private NPSize mNPSize;
    private int mFormat;
    private boolean isCameraFacingBack;

    public NPFrame(byte[] data, int rotation, NPSize NPSize, int format, boolean isCameraFacingBack) {
        this.mData = data;
        this.mRotation = rotation;
        this.mNPSize = NPSize;
        this.mFormat = format;
        this.isCameraFacingBack = isCameraFacingBack;
    }

    public byte[] getData() {
        return mData;
    }

    public void setData(byte[] mData) {
        this.mData = mData;
    }

    public int getRotation() {
        return mRotation;
    }

    public void setRotation(int mRotation) {
        this.mRotation = mRotation;
    }

    public NPSize getSize() {
        return mNPSize;
    }

    public void setSize(NPSize mNPSize) {
        this.mNPSize = mNPSize;
    }

    public int getFormat() {
        return mFormat;
    }

    public void setFormat(int mFormat) {
        this.mFormat = mFormat;
    }

    public boolean isCameraFacingBack() {
        return isCameraFacingBack;
    }

    public void setCameraFacingBack(boolean cameraFacingBack) {
        isCameraFacingBack = cameraFacingBack;
    }
}
