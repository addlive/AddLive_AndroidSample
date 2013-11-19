package com.addlive.sampleapp;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.io.IOException;

/**
 * Created by tkozak on 14/11/13.
 */
public class TextureViewActivity extends Activity implements TextureView.SurfaceTextureListener {
  private Camera mCamera;
  private TextureView mTextureView;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    mTextureView = new TextureView(this);
    mTextureView.setSurfaceTextureListener(this);

    setContentView(mTextureView);
  }

  @Override
  public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {

    Log.d("AddLive", "Got texture available");
    mCamera = Camera.open();

    Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
    mTextureView.setLayoutParams(new FrameLayout.LayoutParams(
        previewSize.width, previewSize.height, Gravity.CENTER));

    try {
      mCamera.setPreviewTexture(surface);
    } catch (IOException t) {
    }

    mCamera.startPreview();

    mTextureView.setAlpha(0.5f);
    mTextureView.setRotation(45.0f);
  }

  @Override
  public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    // Ignored, the Camera does all the work for us
  }

  @Override
  public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
    mCamera.stopPreview();
    mCamera.release();
    return true;
  }

  @Override
  public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    Log.d("AddLive", "Got new texture");
//    mTextureView.getBitmap().
  }
}