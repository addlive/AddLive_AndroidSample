package com.addlive.sampleapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.*;
import android.widget.*;
import com.addlive.Constants;
import com.addlive.platform.*;
import com.addlive.service.*;
import com.addlive.service.listener.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class AddLiveSampleApp extends Activity {

  /**
   * ===========================================================================
   * Constants
   * ===========================================================================
   */

  private static final long ADL_APP_ID = -1; // TODO set your app ID here.
  private static final String ADL_API_KEY = ""; // TODO set you API key here.


  private static final int STATS_INTERVAL = 2;
  private static final String LOG_TAG = "AddLiveDemo";

  /**
   * ===========================================================================
   * Nested classes
   * ===========================================================================
   */

  class AddLiveState {
    long userId = 0;
    boolean isInitialized = false;
    boolean isConnected = false;
    boolean isVideoPublished = false;
    boolean isAudioPublished = false;
    String scopeId = "";

    AddLiveState() {
      Random rand = new Random();
      userId = (1 + rand.nextInt(9999));
    }

    AddLiveState(AddLiveState state) {
      userId = state.userId;
      isInitialized = state.isInitialized;
      isConnected = state.isConnected;
      isVideoPublished = state.isVideoPublished;
      isAudioPublished = state.isAudioPublished;
      scopeId = state.scopeId;
    }

    void reset() {
      isConnected = false;
      isVideoPublished = false;
      isAudioPublished = false;
      scopeId = "";
    }
  }

  class MediaStatsView {
    TextView view = null;
    String audio = "";
    String video = "";

    MediaStatsView(TextView view) {
      this.view = view;
    }
  }

  class User {
    MediaStatsView statsView = null;
    String videoSinkId = "";
    boolean local = false;

    User(String videoSinkId, TextView view, boolean local) {
      this.statsView = new MediaStatsView(view);
      this.videoSinkId = videoSinkId;
      this.local = local;
    }
  }

  /**
   * ===========================================================================
   * Properties
   * ===========================================================================
   */

  // container to keep track of all users (includes local user as well), 
  // key is user ID
  private Map<Long, User> userMap = new HashMap<Long, User>();

  // BroadcastHandler to manage events (headphone and connectivity)
  private BroadcastHandler broadcastReceiver = null;

  // AddLive current connection state used for lifetime management
  private AddLiveState currentState = new AddLiveState();

  // AddLive saved connection state used for lifetime management
  private AddLiveState savedState = null;

  // WakeLock to prevent sleep while in call
  private WakeLock wakeLock = null;

  // keeps track if headphone is in use
  private boolean usesHeadphone = false;

  /**
   * ===========================================================================
   * Activity lifecycle management
   * ===========================================================================
   */

  @Override
  public void onCreate(Bundle savedInstanceState) {
    Log.v(LOG_TAG, "onCreate");

    super.onCreate(savedInstanceState);

    setContentView(R.layout.main);

    getWindow().setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

    // setup wake lock to prevent app from going into sleep mode while in call
    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "AddLiveSampleApp");

    // broadcast receiver for headset plugged and connectivity events
    broadcastReceiver = new BroadcastHandler(this);
    registerReceiver(broadcastReceiver,
        new IntentFilter(Intent.ACTION_HEADSET_PLUG));
    registerReceiver(broadcastReceiver,
        new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

    // local camera view: camera output is hardcoded to 640x480, size of the
    // view is reduced
    SurfaceView local = (SurfaceView) findViewById(R.id.local_video);
    local.setZOrderMediaOverlay(true);
    local.setLayoutParams(new RelativeLayout.LayoutParams(480 / 3, 640 / 3));

    // url/scope to connect to
    EditText url = (EditText) findViewById(R.id.edit_url);
    url.setSelection(url.getText().length());

    // local stats view
    TextView stats = (TextView) findViewById(R.id.text_stats);
    userMap.put(-1L, new User("", stats, true));

    initializeActions();
    initializeAddLive();
  }

  @Override
  public void onResume() {
    Log.v(LOG_TAG, "onResume");

    // restore saved application state
    if (savedState != null)
      currentState = new AddLiveState(savedState);
    savedState = null;

    if (currentState.isInitialized) { // app was previously initialized
      // start video preview
      SurfaceView local = (SurfaceView) findViewById(R.id.local_video);

      ADL.getService().startLocalVideo(new UIThreadResponder<String>(this) {
        @Override
        protected void handleResult(String videoSinkId) {
          setLocalVideoSink(videoSinkId);
        }

        @Override
        protected void handleError(int errCode, String errMessage) {
          Log.e(LOG_TAG, "Failed to start local video.");
        }
      }, local);

      // publish video
      if (currentState.isConnected && currentState.isVideoPublished) {
        onPublishVideo(true);
      }
    }

    // resume remote video view
    com.addlive.view.VideoView remote =
        (com.addlive.view.VideoView) findViewById(R.id.remote_video);
    remote.onResume();

    // foreground lifetime begin
    super.onResume();
  }

  @Override
  public void onPause() {
    Log.v(LOG_TAG, "onPause");

    // store current state
    savedState = new AddLiveState(currentState);

    if (currentState.isInitialized) {
      if (currentState.isConnected && currentState.isVideoPublished)
        onPublishVideo(false);

      ADL.getService().stopLocalVideo(new UIThreadResponder<Void>(this) {
        @Override
        protected void handleResult(Void result) {
          setLocalVideoSink("");
        }

        @Override
        protected void handleError(int errCode, String errMessage) {
          Log.e(LOG_TAG, "Failed to stop local video");
        }
      });
    }

    // pause remote video view
    com.addlive.view.VideoView view =
        (com.addlive.view.VideoView) findViewById(R.id.remote_video);
    view.onPause();

    // foreground lifetime end
    super.onPause();
  }

  @Override
  public void onDestroy() {
    Log.v(LOG_TAG, "onDestroy");

    unregisterReceiver(broadcastReceiver);

    ADL.release();
    currentState.reset();

    super.onDestroy();
  }

  /**
   * ===========================================================================
   * AddLive Platform initialization
   * ===========================================================================
   */

  private void initializeAddLive() {
    PlatformInitListener listener = new PlatformInitListener() {
      @Override
      public void onInitProgressChanged(InitProgressChangedEvent e) {
        // Actually not used by the platform for now. Just a placeholder
      }

      @Override
      public void onInitStateChanged(InitStateChangedEvent e) {
        onAdlInitStateChanged(e);
      }
    };
    PlatformInitOptions initOptions = new PlatformInitOptions();
    String storageDir =
        Environment.getExternalStorageDirectory().getAbsolutePath();
    initOptions.setStorageDir(storageDir);
    Log.d(LOG_TAG, "Initializing the AddLive SDK.");
    ADL.init(listener, initOptions, this);
  }

  // ===========================================================================

  private void onAdlInitStateChanged(InitStateChangedEvent e) {
    if (e.getState() == InitState.INITIALIZED) {
      onAdlInitialized();
    } else {
      onAdlInitError(e);
    }
  }

  // ===========================================================================

  private void onAdlInitialized() {
    Log.d(LOG_TAG, "AddLive SDK initialized");
    // set service listener, set application id and get version
    ADL.getService().addServiceListener(new ResponderAdapter<Void>(),
        getListener());

    Log.d(LOG_TAG, "Setting application id: " + ADL_APP_ID);
    ADL.getService().setApplicationId(new ResponderAdapter<Void>(),
        ADL_APP_ID);

    ADL.getService().getVersion(new UIThreadResponder<String>(this) {
      @Override
      protected void handleResult(String version) {
        Log.d(LOG_TAG, "AddLive SDK version: " + version);
        TextView versionLabel =
            (TextView) findViewById(R.id.sdk_version_label);
        versionLabel.append(version);
      }

      @Override
      protected void handleError(int errCode, String errMessage) {
        Log.e(LOG_TAG, "Failed to get version string.");
      }
    });

    // get all connected video capture devices
    ADL.getService().getVideoCaptureDeviceNames(
        new UIThreadResponder<Device[]>(this) {
          @Override
          protected void handleResult(Device[] devices) {
            onGetVideoCaptureDeviceNames(devices);
          }

          @Override
          protected void handleError(int errCode, String errMessage) {
            Log.e(LOG_TAG, "Failed to get video capture devices.");
          }
        }
    );

    // update UI
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Button button = (Button) findViewById(R.id.button_connect);
        button.setEnabled(true);

        findViewById(R.id.toggle_video).setEnabled(true);
        findViewById(R.id.toggle_audio).setEnabled(true);

        TextView status = (TextView) findViewById(R.id.text_status);
        status.setTextColor(Color.YELLOW);
        status.setText("Ready");

        TextView stats = (TextView) findViewById(R.id.text_stats);
        stats.setText("Uplink Stats");
      }
    });

    // AddLive is initialized
    currentState.isInitialized = true;
  }

  // ===========================================================================

  private void onAdlInitError(InitStateChangedEvent e) {
    Button button = (Button) findViewById(R.id.button_connect);
    button.setEnabled(true);

    TextView status = (TextView) findViewById(R.id.text_status);
    status.setTextColor(Color.RED);

    String errMessage = "ERROR: (" + e.getErrCode() + ") " +
        e.getErrMessage();
    status.setText(errMessage);

    Log.e(LOG_TAG, errMessage);
  }

  /**
   * ===========================================================================
   * UI Actions initialization and handling
   * ===========================================================================
   */

  private void initializeActions() {
    // initialize all button actions
    findViewById(R.id.button_connect).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            onConnect();
          }
        });

    findViewById(R.id.button_disconnect).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            onDisconnect();
          }
        });

    ((ToggleButton) findViewById(R.id.toggle_video)).setOnCheckedChangeListener(
        new CompoundButton.OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton buttonView,
                                       boolean isChecked) {
            onPublishVideo(isChecked);
          }
        });

    ((ToggleButton) findViewById(R.id.toggle_audio)).setOnCheckedChangeListener(
        new CompoundButton.OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton buttonView,
                                       boolean isChecked) {
            onPublishAudio(isChecked);
          }
        });

    findViewById(R.id.button_logs).
        setOnClickListener(new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            onLogsClicked();
          }
        });

    // initialize spinners (selects)
    Spinner ec = (Spinner) findViewById(R.id.spinner_ec);
    ec.setOnItemSelectedListener(
        new AdvAudioSettingsCtrl("enableAEC", "modeAECM"));
    updateAECConfiguration();

    Spinner ns = (Spinner) findViewById(R.id.spinner_ns);
    ns.setOnItemSelectedListener(
        new AdvAudioSettingsCtrl("enableNS", "modeNS"));
    ns.setSelection(Constants.NSModes.VERY_HIGH_SUPPRESSION);

    // initialize click on video (switches rendered video feed to next user)
    findViewById(R.id.video_layout).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            renderNextUser();
          }
        }
    );
  }

  // ===========================================================================

  private void onNetworkTest() {
    String salt = "Some random string salt";
    long timeNow = System.currentTimeMillis() / 1000;
    long expires = timeNow + (5 * 60);

    StringBuilder signatureBodyBuilder = new StringBuilder();
    signatureBodyBuilder.
        append(ADL_APP_ID).
        append("").
        append(currentState.userId).
        append(salt).
        append(expires).
        append(ADL_API_KEY);
    String signatureBody = signatureBodyBuilder.toString();
    MessageDigest digest;
    String signature = "";
    try {
      digest = MessageDigest.getInstance("SHA-256");
      digest.update(signatureBody.getBytes());
      signature = bytesToHexString(digest.digest());
    } catch (NoSuchAlgorithmException e1) {
      Log.e(LOG_TAG, "Failed to calculate authentication signature due to " +
          "missing SHA-256 algorithm.");
    }

    AuthDetails authDetails = new AuthDetails();
    authDetails.setUserId(currentState.userId);
    authDetails.setExpires(expires);
    authDetails.setSalt(salt);
    authDetails.setSignature(signature);

    ADL.getService().networkTest(
      new UIThreadResponder<Integer>(this) {
	@Override
	protected void handleResult(Integer quality) {
	  Log.v(LOG_TAG, "Network test result: " + quality);
	}

	@Override
	protected void handleError(int errCode, String errMessage) {
	  Log.e(LOG_TAG, "Failed to run network test.");
	}
      }, 1024, authDetails
    );
  }

  // ===========================================================================

  private void onConnect() {
    TextView status = (TextView) findViewById(R.id.text_status);
    status.setTextColor(Color.CYAN);
    status.setText("Connecting ...");

    Button connect = (Button) findViewById(R.id.button_connect);
    connect.setEnabled(false);

    EditText edit = (EditText) findViewById(R.id.edit_url);
    String scopeId = edit.getText().toString();

    Log.d(LOG_TAG, "Connecting to scope: '" + scopeId + "'");
    ConnectionDescriptor desc = genConnDescriptor(scopeId);

    UIThreadResponder<MediaConnection> connectResponder =
        new UIThreadResponder<MediaConnection>(this) {
          @Override
          protected void handleResult(MediaConnection result) {
            onConnected();
          }

          @Override
          protected void handleError(int errCode, String errMessage) {
            onConnectError(errCode, errMessage);
          }
        };

    ADL.getService().connect(connectResponder, desc);
  }

  // ===========================================================================

  private void onDisconnect() {
    Button disconnect = (Button) findViewById(R.id.button_disconnect);
    disconnect.setEnabled(false);

    TextView status = (TextView) findViewById(R.id.text_status);
    status.setTextColor(Color.CYAN);
    status.setText("Disconnecting ...");

    UIThreadResponder<Void> disconnectResponder =
        new UIThreadResponder<Void>(this) {
          @Override
          protected void handleResult(Void result) {
            onDisconnected("Ready");
          }

          @Override
          protected void handleError(int errCode, String errMessage) {
          }
        };

    ADL.getService().disconnect(disconnectResponder, currentState.scopeId);
  }

  // ===========================================================================

  private void onPublishVideo(final boolean publish) {
    if (!currentState.isConnected)
      return;

    UIThreadResponder<Void> publishResponder =
        new UIThreadResponder<Void>(this) {
          @Override
          protected void handleResult(Void result) {
            onPublishedVideo(publish);
          }

          @Override
          protected void handleError(int errCode, String errMessage) {
            onPublishError(errCode, errMessage);
          }
        };

    if (publish) {
      ADL.getService().publish(publishResponder, currentState.scopeId,
          MediaType.VIDEO);
    } else {
      ADL.getService().unpublish(publishResponder, currentState.scopeId,
          MediaType.VIDEO);
    }
  }

  // ===========================================================================

  private void onPublishAudio(final boolean publish) {
    if (!currentState.isConnected)
      return;

    UIThreadResponder<Void> publishResponder =
        new UIThreadResponder<Void>(this) {
          @Override
          protected void handleResult(Void result) {
            onPublishedAudio(publish);
          }

          @Override
          protected void handleError(int errCode, String errMessage) {
            onPublishError(errCode, errMessage);
          }
        };

    if (publish) {
      ADL.getService().publish(publishResponder, currentState.scopeId,
          MediaType.AUDIO);
    } else {
      ADL.getService().unpublish(publishResponder, currentState.scopeId,
          MediaType.AUDIO);
    }
  }

  // ===========================================================================

  private void onLogsClicked() {
    LogsPublisher publisher = new LogsPublisher();
    List<String> filter = new LinkedList<String>();
    filter.add("AddLive_SDK:V");
    filter.add(LOG_TAG + ":V");
    filter.add("*:S");
    publisher.run(filter, this);
  }

  /**
   * ===========================================================================
   * AddLive responses
   * ===========================================================================
   */

  private void onConnected() {
    Log.d(LOG_TAG, "Successfully connected to the scope");
    TextView status = (TextView) findViewById(R.id.text_status);
    status.setTextColor(Color.GREEN);
    status.setText("In Call");

    Button connect = (Button) findViewById(R.id.button_connect);
    connect.setVisibility(View.GONE);

    Button disconnect = (Button) findViewById(R.id.button_disconnect);
    disconnect.setVisibility(View.VISIBLE);
    disconnect.setEnabled(true);

    // tell SDK to measure statistics
    ADL.getService().startMeasuringStats(new ResponderAdapter<Void>(),
        currentState.scopeId, STATS_INTERVAL);

    currentState.isConnected = true;

    currentState.isVideoPublished =
        ((ToggleButton) findViewById(R.id.toggle_video)).isChecked();
    currentState.isAudioPublished =
        ((ToggleButton) findViewById(R.id.toggle_audio)).isChecked();

    wakeLock.acquire(); // prevent app from entering sleep mode
  }

  // ===========================================================================

  private void onConnectError(int errCode, String errMessage) {
    Log.e(LOG_TAG, "ERROR: (" + errCode + ") " + errMessage);

    TextView status = (TextView) findViewById(R.id.text_status);
    status.setTextColor(Color.RED);
    status.setText("ERROR: (" + errCode + ") " + errMessage);

    Button connect = (Button) findViewById(R.id.button_connect);
    connect.setEnabled(true);

    currentState.reset();
  }

  // ===========================================================================

  private void onDisconnected(String statusText) {
    if (statusText.length() > 0) {
      TextView status = (TextView) findViewById(R.id.text_status);
      status.setTextColor(Color.YELLOW);
      status.setText(statusText);
    }

    Button connect = (Button) findViewById(R.id.button_connect);
    connect.setVisibility(View.VISIBLE);
    connect.setEnabled(true);

    Button disconnect = (Button) findViewById(R.id.button_disconnect);
    disconnect.setVisibility(View.GONE);

    clearRemoteUsers();

    // clear remote video renderer
    com.addlive.view.VideoView view =
        (com.addlive.view.VideoView) findViewById(R.id.remote_video);
    view.removeRenderer();

    currentState.reset();

    wakeLock.release(); // allow app to enter sleep mode
  }

  // ===========================================================================

  private void onPublishError(int errCode, String errMessage) {
    TextView status = (TextView) findViewById(R.id.text_status);
    status.setTextColor(Color.RED);
    status.setText("ERROR: (" + errCode + ") " + errMessage);

    Log.e(LOG_TAG, "ERROR: " + errCode + " " + errMessage);
  }

  // ===========================================================================

  private void onGetVideoCaptureDeviceNames(Device[] devices) {
    int index = 0;

    // set camera device names in camera selection spinner
    String[] items = new String[devices.length];
    for (int i = 0; i < devices.length; i++) {
      items[i] = devices[i].getLabel();

      // look for front camera
      if (items[i].toLowerCase().contains("front")) {
        index = i;
        Log.v(LOG_TAG, "found front facing camera: " + i);
      }
    }

    SurfaceView view = (SurfaceView) findViewById(R.id.local_video);
    ADL.getService().setVideoCaptureDevice(new ResponderAdapter<Void>(),
        devices[index].getId(), view);

    ArrayAdapter<String> adapter = new ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, items);

    Spinner spinner = (Spinner) findViewById(R.id.spinner_camera);
    spinner.setOnItemSelectedListener(new CameraSelectionListener(devices));
    spinner.setAdapter(adapter);
    spinner.setSelection(index); // select front camera if available

    // start video preview
    ADL.getService().startLocalVideo(new UIThreadResponder<String>(this) {
      @Override
      protected void handleResult(String videoSinkId) {
        setLocalVideoSink(videoSinkId);
      }

      @Override
      protected void handleError(int errCode, String errMessage) {
        Log.e(LOG_TAG, "Failed to start local video.");
      }
    }, view);
  }

  // ===========================================================================

  private void onPublishedVideo(boolean publish) {
    currentState.isVideoPublished = publish;
    if (!publish) {
      User user = userMap.get(-1L);
      user.statsView.video = "";
      updateStats(user, "");
    }
  }

  // ===========================================================================

  private void onPublishedAudio(boolean publish) {
    currentState.isAudioPublished = publish;
    if (!publish) {
      User user = userMap.get(-1L);
      user.statsView.audio = "";
      updateStats(user, "");
    }
  }

  /**
   * ===========================================================================
   * AddLive Service Events handling
   * ===========================================================================
   */

  private AddLiveServiceListener getListener() {
    return new AddLiveServiceListenerAdapter() {
      @Override
      public void onVideoFrameSizeChanged(final VideoFrameSizeChangedEvent e) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            onAdlVideoFrameSizeChanged(e);
          }
        });
      }

      @Override
      public void onConnectionLost(final ConnectionLostEvent e) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            onAdlConnLost(e);
          }
        });
      }

      @Override
      public void onUserEvent(final UserStateChangedEvent e) {
        super.onUserEvent(e);
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            onAdlUserEvent(e);
          }
        });
      }

      @Override
      public void onMediaStreamEvent(final UserStateChangedEvent e) {
        super.onMediaStreamEvent(e);
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            onAdlMediaStream(e);
          }
        });
      }

      @Override
      public void onMediaStats(final MediaStatsEvent e) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            onAdlMediaStats(e);
          }
        });
      }

      @Override
      public void onMessage(final MessageEvent e) {
	runOnUiThread(new Runnable() {
	  @Override
	  public void run() {
	    onAdlMessage(e);
          }
	});
      }

      @Override
      public void onMediaConnTypeChanged(final MediaConnTypeChangedEvent e) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            onAdlMediaConnTypeChanged(e);
          }
        });
      }

      @Override
      public void onMediaIssue(
	final MediaIssueEvent e) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            onAdlMediaIssue(e);
          }
        });
      }
    };
  }

  // ===========================================================================

  void onAdlVideoFrameSizeChanged(VideoFrameSizeChangedEvent e) {
    Log.v(LOG_TAG, "videoFrameSizeChanged: " + e.getSinkId() +
        " -> " + e.getWidth() + "x" + e.getHeight());

    if (e.getSinkId().equals(userMap.get(-1L).videoSinkId)) {
      SurfaceView view = (SurfaceView) findViewById(R.id.local_video);
      view.setLayoutParams(new RelativeLayout.LayoutParams(
          e.getWidth() / 3, e.getHeight() / 3));
    } else {
      com.addlive.view.VideoView view =
          (com.addlive.view.VideoView) findViewById(R.id.remote_video);
      if (e.getSinkId().equals(view.getSinkId()))
        view.resolutionChanged(e.getWidth(), e.getHeight());
    }
  }

  // ===========================================================================

  private void onAdlConnLost(final ConnectionLostEvent e) {
    Log.v(LOG_TAG, "connLost: " + e.getErrMessage());

    TextView status = (TextView) findViewById(R.id.text_status);
    status.setTextColor(Color.RED);
    status.setText("connectionLost: (" + e.getErrCode() + ") "
        + e.getErrMessage());

    savedState = new AddLiveState(currentState);
    onDisconnected("");
  }

  // ===========================================================================

  private void onAdlMediaStats(MediaStatsEvent e) {
    if (e.getMediaType() == MediaType.AUDIO)
      onAudioStats(e);
    else if (e.getMediaType() == MediaType.VIDEO)
      onVideoStats(e);
  }

  private void onAudioStats(MediaStatsEvent e) {
    long userId = e.getRemoteUserId();
    User user = userMap.get(userId);
    MediaStats stats = e.getStats();

    String text = "";

    if (!user.local) {
      text += "User " + userId + ":";

      user.statsView.audio =
          "kbps = " + (8.0 * stats.getBitRate() / 1000.0)
              + " #Loss = " + stats.getTotalLoss()
              + " %Loss = " + stats.getLoss();
    } else {
      user.statsView.audio =
          "kbps = " + (8.0 * stats.getBitRate() / 1000.0)
              + " RTT = " + stats.getRtt()
              + " #Loss = " + stats.getTotalLoss()
              + " %Loss = " + stats.getLoss();
    }

    updateStats(user, text);
  }

  private void onVideoStats(MediaStatsEvent e) {
    long userId = e.getRemoteUserId();
    User user = userMap.get(userId);
    MediaStats stats = e.getStats();

    String text = "";

    if (!user.local) {
      text += "User " + userId + ":";

      user.statsView.video =
          "%CPU = " + stats.getTotalCpu()
              + " kbps = " + (8.0 * stats.getBitRate() / 1000.0)
              + " #Loss = " + stats.getTotalLoss()
              + " %Loss = " + stats.getLoss();
    } else {
      user.statsView.video =
          "%CPU = " + stats.getTotalCpu()
              + " kbps = " + (8.0 * stats.getBitRate() / 1000.0)
              + " #Loss = " + stats.getTotalLoss()
              + " %Loss = " + stats.getLoss()
              + " QDL = " + stats.getQueueDelay();
    }

    updateStats(user, text);
  }

  // ===========================================================================

  private void onAdlMessage(MessageEvent e) {
    Log.v(LOG_TAG, "Message: " + e.toString());
    Toast.makeText(this, "Got a message from user " + e.getSrcUserId(), 
		   Toast.LENGTH_SHORT).show();
  }

  // ===========================================================================

  private void onAdlMediaConnTypeChanged(MediaConnTypeChangedEvent e) {
    Log.v(LOG_TAG, "MediaConnTypeChanged: " + e.toString());
  }

  // ===========================================================================

  private void onAdlMediaIssue(MediaIssueEvent e) {
    Log.v(LOG_TAG, "MediaIssue: " + e.toString());
  }

  // ===========================================================================

  private void onAdlUserEvent(UserStateChangedEvent e) {
    Log.d(LOG_TAG, "onAdlUserEvent: " + e.toString());

    long userId = e.getUserId();
    boolean isConnected = e.isConnected();
    LinearLayout layout = (LinearLayout)
        findViewById(R.id.main_layout);

    if (isConnected) {
      Log.i(LOG_TAG, "Got new user connected: " + e.getUserId());
      // add downlink stats entry
      LinearLayout.LayoutParams lparams =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.FILL_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT);
      lparams.setMargins(0, 5, 0, 5);

      TextView tv = new TextView(this);
      tv.setLayoutParams(lparams);
      tv.setText("User " + userId + " Stats");
      tv.setGravity(Gravity.CENTER);
      tv.setTextSize(1, 13.0f);
      tv.setTypeface(Typeface.MONOSPACE);

      layout.addView(tv);

      // add new user
      userMap.put(userId, new User(e.getVideoSinkId(), tv, false));

      if (e.isVideoPublished()) {
        // render video from this user if another user is currently not rendered
        renderUserIfNotBusy(userId, e.getVideoSinkId());
      }
    } else {
      // remove downlink stats entry
      MediaStatsView statsView = userMap.get(userId).statsView;
      layout.removeView(statsView.view);

      // remove user
      userMap.remove(userId);

      // switch video to another user if available
      renderNextUserOrRemove();
    }
  }

  // ===========================================================================

  private void onAdlMediaStream(UserStateChangedEvent e) {
    Log.d(LOG_TAG, "onAdlMediaStream" + e.toString());

    if (e.getMediaType() == MediaType.AUDIO)
      onAudioStream(e);
    else if (e.getMediaType() == MediaType.VIDEO)
      onVideoStream(e);
  }

  private void onAudioStream(UserStateChangedEvent e) {
    if (!e.isAudioPublished()) { // audio has been unpublished
      long userId = e.getUserId();
      User user = userMap.get(userId);

      // clear audio stats
      user.statsView.audio = "";
      updateStats(user, "User " + userId + ":");
    }
  }

  private void onVideoStream(UserStateChangedEvent e) {
    long userId = e.getUserId();

    if (e.isVideoPublished()) { // video has been published
      // update video sink id for this user
      userMap.get(userId).videoSinkId = e.getVideoSinkId();

      // render video from this user if another user is currently not rendered
      renderUserIfNotBusy(userId, e.getVideoSinkId());
    } else {
      // video has been unpublished so we don't have a sink anymore
      userMap.get(userId).videoSinkId = "";

      // clear video stats
      User user = userMap.get(userId);
      user.statsView.video = "";
      updateStats(user, "User " + userId + ":");

      // switch video to another user if available
      renderNextUserOrRemove();
    }
  }

  /**
   * ===========================================================================
   * Private helpers
   * ===========================================================================
   */

  // generates the ConnectionDescriptor (authentication + video description)
  private ConnectionDescriptor genConnDescriptor(String url) {
    String[] urlSplit = url.split("/");
    if (urlSplit.length == 1)
      currentState.scopeId = urlSplit[0];
    else
      currentState.scopeId = urlSplit[1];

    ConnectionDescriptor desc = new ConnectionDescriptor();
    desc.setAutopublishAudio(
        ((ToggleButton) findViewById(R.id.toggle_audio)).isChecked());
    desc.setAutopublishVideo(
        ((ToggleButton) findViewById(R.id.toggle_video)).isChecked());
    desc.setScopeId(currentState.scopeId);
    desc.setUrl((urlSplit.length == 1) ? "" : url);

    // video stream description
    VideoStreamDescriptor videoStream = new VideoStreamDescriptor();
    videoStream.setMaxWidth(240);
    videoStream.setMaxHeight(320);
    videoStream.setMaxBitRate(512);
    videoStream.setMaxFps(15);
    desc.setVideoStream(videoStream);

    // authentication
    String salt = "Some random string salt";
    long timeNow = System.currentTimeMillis() / 1000;
    long expires = timeNow + (5 * 60);

    AuthDetails authDetails = new AuthDetails();
    authDetails.setUserId(currentState.userId);
    authDetails.setSalt(salt);
    authDetails.setExpires(expires);
    StringBuilder signatureBodyBuilder = new StringBuilder();
    signatureBodyBuilder.
        append(ADL_APP_ID).
        append(currentState.scopeId).
        append(currentState.userId).
        append(salt).
        append(expires).
        append(ADL_API_KEY);
    String signatureBody = signatureBodyBuilder.toString();
    MessageDigest digest;
    String signature = "";
    try {
      digest = MessageDigest.getInstance("SHA-256");
      digest.update(signatureBody.getBytes());
      signature = bytesToHexString(digest.digest());
    } catch (NoSuchAlgorithmException e1) {
      Log.e(LOG_TAG, "Failed to calculate authentication signature due to " +
          "missing SHA-256 algorithm.");
    }
    authDetails.setSignature(signature);
    desc.setAuthDetails(authDetails);

    return desc;
  }

  private static String bytesToHexString(byte[] bytes) {
    // http://stackoverflow.com/questions/332079
    StringBuilder sb = new StringBuilder();
    for (byte aByte : bytes) {
      String hex = Integer.toHexString(0xFF & aByte);
      if (hex.length() == 1) {
        sb.append('0');
      }
      sb.append(hex);
    }
    return sb.toString();
  }

  private void clearRemoteUsers() {
    // remove stats entries from layout
    LinearLayout layout = (LinearLayout) findViewById(R.id.main_layout);

    for (User user : userMap.values()) {
      if (!user.local)
        layout.removeView(user.statsView.view);
    }

    // remove all remote users
    Iterator<Map.Entry<Long, User>> it = userMap.entrySet().iterator();

    while (it.hasNext()) {
      Map.Entry<Long, User> e = it.next();

      if (!e.getValue().local)
        it.remove();
    }
  }

  private void setLocalVideoSink(String videoSinkId) {
    userMap.get(-1L).videoSinkId = videoSinkId;
  }

  // switch video feed to the next user available
  private boolean renderNextUser() {
    com.addlive.view.VideoView view =
        (com.addlive.view.VideoView) findViewById(R.id.remote_video);

    Iterator<Map.Entry<Long, User>> it = userMap.entrySet().iterator();

    while (it.hasNext()) {
      Map.Entry<Long, User> e = it.next();
      if (e.getValue().local)
        continue;

      if (e.getValue().videoSinkId.equals(view.getSinkId()))
        break;
    }

    while (it.hasNext()) {
      Map.Entry<Long, User> e = it.next();
      if (e.getValue().local)
        continue;

      if (e.getValue().videoSinkId.length() > 0) {
        renderUser(e.getKey(), e.getValue().videoSinkId);
        return true;
      }
    }

    it = userMap.entrySet().iterator();

    while (it.hasNext()) {
      Map.Entry<Long, User> e = it.next();
      if (e.getValue().local)
        continue;

      if (e.getValue().videoSinkId.equals(view.getSinkId()))
        break;

      if (e.getValue().videoSinkId.length() > 0) {
        renderUser(e.getKey(), e.getValue().videoSinkId);
        return true;
      }
    }

    return false;
  }

  // switch video feed to next avail. user or stop remote rendering completely
  private void renderNextUserOrRemove() {
    com.addlive.view.VideoView view =
        (com.addlive.view.VideoView) findViewById(R.id.remote_video);

    for (User user : userMap.values()) {
      if (user.videoSinkId.equals(view.getSinkId()))
        return;
    }

    if (renderNextUser())
      return;

    view.removeRenderer();
  }

  // render given video feed of user if no other is currently beeing renderer
  private void renderUserIfNotBusy(long userId, String videoSinkId) {
    com.addlive.view.VideoView view =
        (com.addlive.view.VideoView) findViewById(R.id.remote_video);

    if (view.getSinkId().length() > 0)
      return;

    renderUser(userId, videoSinkId);
  }

  // select rendered user in stats list (on bottom of application),
  // connect view to given sink (this will start the rendering) and 
  // tell streamer only to forward given user to us
  private void renderUser(long userId, String videoSinkId) {
    com.addlive.view.VideoView view =
        (com.addlive.view.VideoView) findViewById(R.id.remote_video);

    for (User user : userMap.values()) {
      if (user.videoSinkId.equals(view.getSinkId())) {
        user.statsView.view.setBackgroundResource(R.color.black);
      }
      if (user.videoSinkId.equals(videoSinkId)) {
        user.statsView.view.setBackgroundResource(R.color.lightblue);
      }
    }

    view.addRenderer(videoSinkId);

    long[] users = {userId};
    Log.d(LOG_TAG, "Calling set allowed senders with remote user id: " + userId);
    ADL.getService().setAllowedSenders(new ResponderAdapter<Void>(),
        currentState.scopeId, users);
  }

  // combine given text with audio and video stats strings
  private void updateStats(User user, String text) {
    if (user.statsView.audio.length() > 0)
      text += " [A] " + user.statsView.audio;
    else
      text += " [no audio]";
    if (user.statsView.video.length() > 0)
      text += " [V] " + user.statsView.video;
    else
      text += " [no video]";

    user.statsView.view.setText(text);
  }

  /**
   * ===========================================================================
   * Advanced audio settings management
   * ===========================================================================
   */

  class AdvAudioSettingsCtrl implements AdapterView.OnItemSelectedListener {

    private String enablePropertyName;
    private String modePropertyName;

    AdvAudioSettingsCtrl(String enablePropertyName, String modePropertyName) {
      this.enablePropertyName = enablePropertyName;
      this.modePropertyName = modePropertyName;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view,
                               int position, long id) {
      if (ADL.getService() == null) {
        Log.d(LOG_TAG, "Service not initialized ");
        return;
      }

      if (position == 0) {
        ADL.getService().setProperty(
            new ResponderAdapter<Void>(),
            "global.dev.audio." + enablePropertyName, "0");
      } else {
        ADL.getService().setProperty(
            new ResponderAdapter<Void>(),
            "global.dev.audio." + enablePropertyName, "1");

        ADL.getService().setProperty(
            new ResponderAdapter<Void>(),
            "global.dev.audio." + modePropertyName, "" + (position - 1));
      }
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {
      onItemSelected(null, null, 0, 0);
    }
  }

  private void updateAECConfiguration() {
    Spinner ec = (Spinner) findViewById(R.id.spinner_ec);
    ec.setSelection(
        usesHeadphone ? Constants.AECModes.DISABLED
            : Constants.AECModes.SPEAKERPHONE);
  }

  /**
   * ===========================================================================
   * BroadcastReceiver for system events
   * ===========================================================================
   */

  private void onHeadphonePlugged(boolean plugged) {
    usesHeadphone = plugged;
    updateAECConfiguration();
  }

  private void onNetworkChanged(NetworkInfo info) {
    Log.v(LOG_TAG, info.toString());

    if (info.getState() == NetworkInfo.State.CONNECTED) {
      if (savedState != null) {
        currentState = new AddLiveState(savedState);
        if (currentState.isConnected)
          onConnect();
        savedState = null;
      }
    }
  }

  private class BroadcastHandler extends BroadcastReceiver {
    private AddLiveSampleApp parent;

    public BroadcastHandler(AddLiveSampleApp parent) {
      this.parent = parent;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent.getAction().equals(Intent.ACTION_HEADSET_PLUG)) {
        int state = intent.getIntExtra("state", -1);
        this.parent.onHeadphonePlugged(state == 1);
        return;
      }

      if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
        NetworkInfo info = intent.getParcelableExtra(
            ConnectivityManager.EXTRA_NETWORK_INFO);
        this.parent.onNetworkChanged(info);
      }
    }
  }

  /**
   * ===========================================================================
   * Camera selection
   * ===========================================================================
   */

  class CameraSelectionListener implements AdapterView.OnItemSelectedListener {
    private Device[] devices;

    CameraSelectionListener(Device[] devices) {
      this.devices = devices;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view,
                               int position, long id) {
      if (ADL.getService() == null) {
        Log.d(LOG_TAG, "Service not initialized ");
        return;
      }

      String idx = this.devices[position].getId();
      Log.v(LOG_TAG, "Camera selection: " + position + " (" + idx + ")");

      SurfaceView surfaceView = (SurfaceView) findViewById(R.id.local_video);
      ADL.getService().setVideoCaptureDevice(new ResponderAdapter<Void>(),
          idx, surfaceView);
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }
  }

  /**
   * ===========================================================================
   * Intercept key presses
   * ===========================================================================
   */

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
/*
    if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
      
      return true;
    }
*/
    return super.onKeyDown(keyCode, event);
  }
}
