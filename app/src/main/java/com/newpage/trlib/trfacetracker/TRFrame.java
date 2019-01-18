package com.newpage.trlib.trfacetracker;

/**
 *
 */
class TRFrame {
    private byte[] mData = null;
    private int mRotation = 0;
    private TRSize mSize = null;
    private int mFormat = -1;
    private boolean isCameraFacingBack;

    TRFrame(byte[] data, int rotation, TRSize size, int format, boolean isCameraFacingBack) {
        this.mData = data;
        this.mRotation = rotation;
        this.mSize = size;
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

    public TRSize getSize() {
        return mSize;
    }

    public void setSize(TRSize mSize) {
        this.mSize = mSize;
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
