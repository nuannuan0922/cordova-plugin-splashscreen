/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/

package org.apache.cordova.splashscreen;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AlphaAnimation;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;

import static android.content.Context.MODE_PRIVATE;

public class SplashScreen extends CordovaPlugin {
    private static final String LOG_TAG = "SplashScreen";
    // Cordova 3.x.x has a copy of this plugin bundled with it (SplashScreenInternal.java).
    // Enable functionality only if running on 4.x.x.
    private static final boolean HAS_BUILT_IN_SPLASH_SCREEN = Integer.valueOf(CordovaWebView.CORDOVA_VERSION.split("\\.")[0]) < 4;
    private static final int DEFAULT_SPLASHSCREEN_DURATION = 3000;
    private static final int DEFAULT_FADE_DURATION = 500;
    private static Dialog splashDialog;
    private static ProgressDialog spinnerDialog;
    private static boolean firstShow = true;
    private static boolean lastHideAfterDelay; // https://issues.apache.org/jira/browse/CB-9094

    /**
     * Displays the splash drawable.
     */
    private ImageView splashImageView;

    /**
     * Remember last device orientation to detect orientation changes.
     */
    private int orientation;

    MediaPlayer player;
    SurfaceHolder surfaceHolder;
    LinearLayout ll_skip;

    private boolean isLoadFinished = false;
    private boolean isVideoDisplayed = false;

    private static boolean firstMoviewShow = true;

    public static SharedPreferences prefrence_config;
    public static final String WELCOME_VIDEO = "WelcomeVedio";
    public static final String WELCOME_VIDEO_ISPLAY = "WelcomeVedio_isplay";
    private boolean isplay = false;

    // Helper to be compile-time compatible with both Cordova 3.x and 4.x.
    private View getView() {
        try {
            return (View)webView.getClass().getMethod("getView").invoke(webView);
        } catch (Exception e) {
            return (View)webView;
        }
    }

    @Override
    protected void pluginInitialize() {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return;
        }
        // Make WebView invisible while loading URL
        // CB-11326 Ensure we're calling this on UI thread
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getView().setVisibility(View.INVISIBLE);
            }
        });
        prefrence_config = cordova.getActivity().getSharedPreferences(WELCOME_VIDEO,MODE_PRIVATE);
        isplay = prefrence_config.getBoolean(WELCOME_VIDEO_ISPLAY, false);

        int drawableId = preferences.getInteger("SplashDrawableId", 0);
        if (drawableId == 0) {
            String splashResource = preferences.getString("SplashScreen", "screen");
            if (splashResource != null) {
                drawableId = cordova.getActivity().getResources().getIdentifier(splashResource, "drawable", cordova.getActivity().getClass().getPackage().getName());
                if (drawableId == 0) {
                    drawableId = cordova.getActivity().getResources().getIdentifier(splashResource, "drawable", cordova.getActivity().getPackageName());
                }
                preferences.set("SplashDrawableId", drawableId);
            }
        }

        // Save initial orientation.
        orientation = cordova.getActivity().getResources().getConfiguration().orientation;

        if (firstShow) {
            boolean autoHide = preferences.getBoolean("AutoHideSplashScreen", true);
            showSplashScreen(autoHide);
        }

        if (preferences.getBoolean("SplashShowOnlyFirstTime", true)) {
            firstShow = false;
        }

    }

    /**
     * Shorter way to check value of "SplashMaintainAspectRatio" preference.
     */
    private boolean isMaintainAspectRatio () {
        return preferences.getBoolean("SplashMaintainAspectRatio", false);
    }

    private int getFadeDuration () {
        int fadeSplashScreenDuration = preferences.getBoolean("FadeSplashScreen", true) ?
            preferences.getInteger("FadeSplashScreenDuration", DEFAULT_FADE_DURATION) : 0;

        if (fadeSplashScreenDuration < 30) {
            // [CB-9750] This value used to be in decimal seconds, so we will assume that if someone specifies 10
            // they mean 10 seconds, and not the meaningless 10ms
            fadeSplashScreenDuration *= 1000;
        }

        return fadeSplashScreenDuration;
    }

    @Override
    public void onPause(boolean multitasking) {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return;
        }
        // hide the splash screen to avoid leaking a window
        this.removeSplashScreen(true);
    }

    @Override
    public void onDestroy() {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return;
        }
        // hide the splash screen to avoid leaking a window
        this.removeSplashScreen(true);
        // If we set this to true onDestroy, we lose track when we go from page to page!
        //firstShow = true;
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("hide")) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    webView.postMessage("splashscreen", "hide");
                }
            });
        } else if (action.equals("show")) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    webView.postMessage("splashscreen", "show");
                }
            });
        } else {
            return false;
        }

        callbackContext.success();
        return true;
    }

    @Override
    public Object onMessage(String id, Object data) {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return null;
        }
        if ("splashscreen".equals(id)) {
            if ("hide".equals(data.toString())) {
                this.removeSplashScreen(false);
            } else {
                this.showSplashScreen(false);
            }
        } else if ("spinner".equals(id)) {
            if ("stop".equals(data.toString())) {
                getView().setVisibility(View.VISIBLE);
            }
        } else if ("onReceivedError".equals(id)) {
            this.spinnerStop();
        }
        return null;
    }

    // Don't add @Override so that plugin still compiles on 3.x.x for a while
    public void onConfigurationChanged(Configuration newConfig) {
        if (newConfig.orientation != orientation) {
            orientation = newConfig.orientation;

            // Splash drawable may change with orientation, so reload it.
            if (splashImageView != null) {
                int drawableId = preferences.getInteger("SplashDrawableId", 0);
                if (drawableId != 0) {
                    splashImageView.setImageDrawable(cordova.getActivity().getResources().getDrawable(drawableId));
                }
            }
        }
    }

    private void removeSplashScreen(final boolean forceHideImmediately) {
        isLoadFinished = true;
        if(!isVideoDisplayed) {
            ll_skip.setVisibility(View.VISIBLE);
        }
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                if (splashDialog != null && splashDialog.isShowing()) {
                    final int fadeSplashScreenDuration = getFadeDuration();
                    // CB-10692 If the plugin is being paused/destroyed, skip the fading and hide it immediately
                    if (fadeSplashScreenDuration > 0 && forceHideImmediately == false) {
                        AlphaAnimation fadeOut = new AlphaAnimation(1, 0);
                        fadeOut.setInterpolator(new DecelerateInterpolator());
                        fadeOut.setDuration(fadeSplashScreenDuration);

                        splashImageView.setAnimation(fadeOut);
                        splashImageView.startAnimation(fadeOut);

                        fadeOut.setAnimationListener(new Animation.AnimationListener() {
                            @Override
                            public void onAnimationStart(Animation animation) {
                                spinnerStop();
                            }

                            @Override
                            public void onAnimationEnd(Animation animation) {
                                spinnerStop();
                                if(isVideoDisplayed) {
                                    if (splashDialog != null && splashDialog.isShowing()) {
                                        splashDialog.dismiss();
                                        splashDialog = null;
                                        splashImageView = null;
                                    }
                                }
                            }

                            @Override
                            public void onAnimationRepeat(Animation animation) {
                            }
                        });
                    } else {
                        spinnerStop();
                        if(isVideoDisplayed) {
                            splashDialog.dismiss();
                            splashDialog = null;
                            splashImageView = null;
                        }
                    }
                }
            }
        });
    }

    private int getId(String idName)
    {
        return cordova.getActivity().getResources().getIdentifier(idName, "id", cordova.getActivity().getPackageName());
    }
    private int getLayout(String layoutName)
    {
        return cordova.getActivity().getResources().getIdentifier(layoutName, "layout", cordova.getActivity().getPackageName());
    }


    /**
     * Shows the splash screen over the full Activity
     */
    @SuppressWarnings("deprecation")
    private void showSplashScreen(final boolean hideAfterDelay) {
        final int splashscreenTime = preferences.getInteger("SplashScreenDelay", DEFAULT_SPLASHSCREEN_DURATION);
        final int drawableId = preferences.getInteger("SplashDrawableId", 0);

        final int fadeSplashScreenDuration = getFadeDuration();
        final int effectiveSplashDuration = Math.max(0, splashscreenTime - fadeSplashScreenDuration);

        lastHideAfterDelay = hideAfterDelay;

        // If the splash dialog is showing don't try to show it again
        if (splashDialog != null && splashDialog.isShowing()) {
            return;
        }
        if (drawableId == 0 || (splashscreenTime <= 0 && hideAfterDelay)) {
            return;
        }

        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                // Get reference to display
                Display display = cordova.getActivity().getWindowManager().getDefaultDisplay();
                final Context context = webView.getContext();

                // Use an ImageView to render the image because of its flexible scaling options.

                View video_view = LayoutInflater.from(context).inflate(getLayout("splash_welcome_video"),null);
                LinearLayout ll_image =  (LinearLayout) video_view.findViewById(getId("imageview"));
                SurfaceView surface = (SurfaceView) video_view.findViewById(getId("sv_video"));
                ll_skip = (LinearLayout) video_view.findViewById(getId("ll_skip"));
                ll_skip.setOnClickListener(new View.OnClickListener(

                ) {
                  @Override
                  public void onClick(View v) {
                      prefrence_config.edit().putBoolean(WELCOME_VIDEO_ISPLAY, true)
                              .commit();
                      if (isLoadFinished) {
                          if (player.isPlaying()) {
                              player.stop();
                          }
                          player.release();
                          if (splashDialog != null && splashDialog.isShowing()) {
                              splashDialog.dismiss();
                              splashDialog = null;
                              splashImageView = null;
                          }
                      }
                  }
                });

              firstMoviewShow = preferences.getBoolean("SplashScreenVideoShowOnlyOnce", false);
              if(firstMoviewShow && isplay){
                  isVideoDisplayed = true;
                  ll_image.setVisibility(View.VISIBLE);
                  surface.setVisibility(View.GONE);
                  ll_skip.setVisibility(View.GONE);
              }else {
                  surfaceHolder = surface.getHolder();// SurfaceHolder是SurfaceView的控制接口
                  surfaceHolder.addCallback(new SurfaceHolder.Callback() {
                      @Override
                      public void surfaceCreated(SurfaceHolder holder) {
                          // 必须在surface创建后才能初始化MediaPlayer,否则不会显示图像
                          String movie_url = preferences.getString("SplashScreenVideoPath", "");

                          String url = webView.getUrl().replace("index.html", movie_url);
                          Log.e("webView.getUrl0()=====", url);

                          try {
                              player = new MediaPlayer();
                              if (url.startsWith("file:///android_asset"))
                              {
                                  Log.e("webView.getUrl1()=====", "www/" + movie_url);
                                  AssetManager assetManager = context.getAssets();
                                  AssetFileDescriptor fileDescriptor = assetManager.openFd("www/" + movie_url);
                                  player.setDataSource(fileDescriptor.getFileDescriptor(),
                                          fileDescriptor.getStartOffset(),
                                          fileDescriptor.getLength());
                              }
                              else
                              {
                                  player.setDataSource(cordova.getActivity(), Uri.parse(url));
                              }

                              player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                              player.setDisplay(surfaceHolder);
                              // 设置显示视频显示在SurfaceView上
                              player.prepare();
                              player.start();
                              player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {

                                  @Override
                                  public void onCompletion(MediaPlayer mediaPlayer) {
                                      Log.e("Completion============", "Completion");
                                      isVideoDisplayed = true;
                                      prefrence_config.edit().putBoolean(WELCOME_VIDEO_ISPLAY, true)
                                              .commit();
                                      if (mediaPlayer.isPlaying()) {
                                          mediaPlayer.stop();
                                      }
                                      mediaPlayer.release();
                                      if (splashDialog != null && splashDialog.isShowing()) {
                                          splashDialog.dismiss();
                                          splashDialog = null;
                                          splashImageView = null;
                                      }
                                  }
                              });

                          } catch (IOException e) {
                              e.printStackTrace();
                              isVideoDisplayed = true;
                          }


                      }

                      @Override
                      public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

                      }

                      @Override
                      public void surfaceDestroyed(SurfaceHolder holder) {

                      }


                  }); // 因为这个类实现了SurfaceHolder.Callback接口，所以回调参数直接this
                  surfaceHolder.setFixedSize(320, 220);// 显示的分辨率,不设置为视频默认
                  surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);// Surface类型
              }
              splashImageView = new ImageView(context);
                splashImageView.setImageResource(drawableId);
                LayoutParams layoutParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                splashImageView.setLayoutParams(layoutParams);

                splashImageView.setMinimumHeight(display.getHeight());
                splashImageView.setMinimumWidth(display.getWidth());

                // TODO: Use the background color of the webView's parent instead of using the preference.
                splashImageView.setBackgroundColor(preferences.getInteger("backgroundColor", Color.BLACK));

                if (isMaintainAspectRatio()) {
                    // CENTER_CROP scale mode is equivalent to CSS "background-size:cover"
                    splashImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                }
                else {
                    // FIT_XY scales image non-uniformly to fit into image view.
                    splashImageView.setScaleType(ImageView.ScaleType.FIT_XY);
                }

                ll_image.addView(splashImageView);
                // Create and show the dialog
                splashDialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
                // check to see if the splash screen should be full screen
                if ((cordova.getActivity().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        == WindowManager.LayoutParams.FLAG_FULLSCREEN) {
                    splashDialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
//                splashDialog.setContentView(splashImageView);
              splashDialog.setContentView(video_view);

              splashDialog.setCancelable(false);
                splashDialog.show();

                if (preferences.getBoolean("ShowSplashScreenSpinner", true)) {
                    spinnerStart();
                }

                // Set Runnable to remove splash screen just in case
                if (hideAfterDelay) {
                    final Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            if (lastHideAfterDelay) {
                                removeSplashScreen(false);
                            }
                        }
                    }, effectiveSplashDuration);
                }
            }
        });
    }

    // Show only spinner in the center of the screen
    private void spinnerStart() {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                spinnerStop();

                spinnerDialog = new ProgressDialog(webView.getContext());
                spinnerDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) {
                        spinnerDialog = null;
                    }
                });

                spinnerDialog.setCancelable(false);
                spinnerDialog.setIndeterminate(true);

                RelativeLayout centeredLayout = new RelativeLayout(cordova.getActivity());
                centeredLayout.setGravity(Gravity.CENTER);
                centeredLayout.setLayoutParams(new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

                ProgressBar progressBar = new ProgressBar(webView.getContext());
                RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                progressBar.setLayoutParams(layoutParams);

                centeredLayout.addView(progressBar);

                spinnerDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                spinnerDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

                spinnerDialog.show();
                spinnerDialog.setContentView(centeredLayout);
            }
        });
    }

    private void spinnerStop() {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                if (spinnerDialog != null && spinnerDialog.isShowing()) {
                    spinnerDialog.dismiss();
                    spinnerDialog = null;
                }
            }
        });
    }

}
