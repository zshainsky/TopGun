package splunk.conf19.topgun;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONException;
import org.json.JSONObject;

// For HTTP Post Requests
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.VolleyError;
import com.android.volley.Response;
import com.android.volley.AuthFailureError;
import com.android.volley.toolbox.Volley;
import com.android.volley.toolbox.StringRequest;
import com.mapbox.mapboxsdk.offline.OfflineRegionError;

import dji.common.battery.WarningRecord;
import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.battery.BatteryState;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.FlightMode;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.ux.widget.FPVWidget;

public class MainActivity extends AppCompatActivity {

    // The rate at which we will run our task in seconds
    private double telemetryTaskRateSeconds = .5;
    // Convert to milliseconds as this is what the timer API expects
    private double telemetryTaskRateMs = telemetryTaskRateSeconds * 1000;
    // Timer object we will use to schedule and cancel the task
    private Timer telemetryTaskTimer;

    // HTTP HEC Endpoint
    String ec2URL = "54.153.8.100";
    String hecPort = "8088";
    String hecEndpoint = "/services/collector";
    String hecToken = "06136b13-c8ff-4be1-861b-15dbf1baceaf";
    String hecURL = "http://" + ec2URL + ":" + hecPort + hecEndpoint;


    // Queue to handle all POST requests
    RequestQueue requestQueue;

    // Indexed Fields
    String aircraftSerialNumber = "N/A";
    String batterySerialNumber = "N/A";

    // Input Values
    String eventIndex;
    String metricIndex;
    String[] availableIndexes;
    String droneName;
    boolean isObstacleAvoidanceDisabled;
    boolean isTripodModeEnabled;
    int droneNameSelection;


    // UI Elements
    Spinner drone;
    Switch obstacleAvoidanceSwitch;
    Switch tripodModeSwitch;
    Button submit;

    //String eventIndex = "sandbox";
    //String eventIndex = "telemetryeventtest";
    // String eventIndex = "goosetest";
    //String mavericIndex = "mavericktest";

    // Stateful Fields
    final HashMap<String, String> batteryData = new HashMap<String, String>();

    private static final String TAG = MainActivity.class.getName();
    public static final String FLAG_CONNECTION_CHANGE = "dji_sdk_connection_change";
    private static BaseProduct mProduct;
    private Handler mHandler;

    private static final String[] REQUIRED_PERMISSION_LIST = new String[]{
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
    };
    private List<String> missingPermission = new ArrayList<>();
    private AtomicBoolean isRegistrationInProgress = new AtomicBoolean(false);
    private static final int REQUEST_PERMISSION_CODE = 12345;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.e("onCreate", "True");

        // When the compile and target version is higher than 22, please request the following permission at runtime to ensure the SDK works well.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkAndRequestPermissions();
        }

        MApplication.getEventBus().register(this);

        // Create requestQueue for HTTP Post Requests to HEC
        requestQueue = Volley.newRequestQueue(this);

        setContentView(R.layout.activity_main);

        // Initialize DJI SDK Manager
        Log.e("lopper.getmainlooper()", Looper.getMainLooper().toString());
        mHandler = new Handler(Looper.getMainLooper());

        // Initialize data input variables
        initDataInputVariables();

    }

    @Override
    protected void onDestroy() {
        MApplication.getEventBus().unregister(this);
        Log.e("onDestroy", "True");

        super.onDestroy();
    }

    @Override
    protected void onPause() {
        Log.e("onPause", "True");
        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.e("onResume", "True");
//        mHandler = new Handler(Looper.getMainLooper());
//        MApplication.getEventBus().register(this);

        super.onResume();
    }

    @Override
    protected void onStop() {
        Log.e("onStop", "True");
        super.onStop();
    }

    @Override
    protected void onStart() {
        Log.e("onStart", "True");
        super.onStart();
    }

    /**
     * Checks if there is any missing permissions, and
     * requests runtime permission if needed.
     */
    private void checkAndRequestPermissions() {
        // Check for permissions
        for (String eachPermission : REQUIRED_PERMISSION_LIST) {
            if (ContextCompat.checkSelfPermission(this, eachPermission) != PackageManager.PERMISSION_GRANTED) {
                missingPermission.add(eachPermission);
            }
        }
        // Request for missing permissions
        if (missingPermission.isEmpty()) {
            startSDKRegistration();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            showToast("Need to grant the permissions!");
            ActivityCompat.requestPermissions(this,
                    missingPermission.toArray(new String[missingPermission.size()]),
                    REQUEST_PERMISSION_CODE);
        }

    }

    /**
     * Result of runtime permission request
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Check for granted permission and remove from missing list
        if (requestCode == REQUEST_PERMISSION_CODE) {
            for (int i = grantResults.length - 1; i >= 0; i--) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    missingPermission.remove(permissions[i]);
                }
            }
        }
        // If there is enough permission, we will start the registration
        if (missingPermission.isEmpty()) {
            startSDKRegistration();
        } else {
            showToast("Missing permissions!!!");
        }
    }

    private void startSDKRegistration() {
        if (isRegistrationInProgress.compareAndSet(false, true)) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    showToast("registering, pls wait...");

                    DJISDKManager.getInstance().registerApp(MainActivity.this.getApplicationContext(), new DJISDKManager.SDKManagerCallback() {
                        @Override
                        public void onRegister(DJIError djiError) {
                            if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                                showToast("Register Success");
                                DJISDKManager.getInstance().startConnectionToProduct();

                            } else {
                                showToast("Register sdk fails, please check the bundle id and network connection!");
                            }
                            Log.v(TAG, djiError.getDescription());
                        }

                        @Override
                        public void onProductDisconnect() {
                            Log.d(TAG, "onProductDisconnect");
                            showToast("Product Disconnected");
                            notifyStatusChange();

                            stopTelemetryTask();

                        }
                        @Override
                        public void onProductConnect(BaseProduct baseProduct) {
                            Log.d(TAG, String.format("onProductConnect newProduct:%s", baseProduct));
                            showToast("Product Connected");
                            notifyStatusChange();

                            // Sets member variable (String) aircraftSerialNumber
                            getAircraftSerialNumberEventData();
                            // Sets member variable (String) batterySerialNumber
                            getBatterySerialNumber();
                            // Run once on first connect to build HashMap
                            getBatteryDiagnosticEventData();

                            // Disable Obstacle Avoidance
                            disableObstacleAvoidence(isObstacleAvoidanceDisabled);

                            // Set Flight Mode
                            Log.e("isTripodModeEnableC", String.valueOf(isTripodModeEnabled));
                            setTripodMode(isTripodModeEnabled);

                            startTelemetryTask();
                        }
                        @Override
                        public void onComponentChange(BaseProduct.ComponentKey componentKey, BaseComponent oldComponent,
                                                      BaseComponent newComponent) {

                            if (newComponent != null) {
                                newComponent.setComponentListener(new BaseComponent.ComponentListener() {

                                    @Override
                                    public void onConnectivityChange(boolean isConnected) {
                                        Log.d(TAG, "onComponentConnectivityChanged: " + isConnected);
                                        notifyStatusChange();
                                    }
                                });
                            }
                            Log.d(TAG,
                                    String.format("onComponentChange key:%s, oldComponent:%s, newComponent:%s",
                                            componentKey,
                                            oldComponent,
                                            newComponent));

                        }

                        @Override
                        public void onInitProcess(DJISDKInitEvent djisdkInitEvent, int i) {

                        }

                        @Override
                        public void onDatabaseDownloadProgress(long l, long l1) {

                        }

                    });
                }
            });
        }
    }

    private void notifyStatusChange() {
        if (mHandler == null) {
            Log.e("mHandler","NULL");
        }

        mHandler.removeCallbacks(updateRunnable);
        MApplication.getEventBus().post(new ConnectivityChangeEvent());

        mHandler.postDelayed(updateRunnable, 500);
    }

    private Runnable updateRunnable = new Runnable() {

        @Override
        public void run() {
            Intent intent = new Intent(FLAG_CONNECTION_CHANGE);
            sendBroadcast(intent);
        }
    };

    private void showToast(final String toastMsg) {

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), toastMsg, Toast.LENGTH_LONG).show();
            }
        });
    }

    public void submit(View view) {
        Context context = this.getApplicationContext();
        SharedPreferences sharedPref = context.getSharedPreferences(getString(R.string.com_splunk_cong19_topgun_preferenceFlightOptions), Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();

        editor.putString(getString(R.string.droneName), (String) drone.getSelectedItem());
        editor.putBoolean(getString(R.string.isObstacleAvoidanceDisabled), obstacleAvoidanceSwitch.isChecked());
        editor.putBoolean(getString(R.string.isTripodModeEnabled), tripodModeSwitch.isChecked());

        droneName = (String) drone.getSelectedItem();
        droneNameSelection = drone.getSelectedItemPosition();
        eventIndex = availableIndexes[droneNameSelection];
        isObstacleAvoidanceDisabled = obstacleAvoidanceSwitch.isChecked();
        isTripodModeEnabled = tripodModeSwitch.isChecked();

        /** REVIEW: Does order matter here? ... Validate if this works*/
        disableObstacleAvoidence(isObstacleAvoidanceDisabled);
        setTripodMode(isTripodModeEnabled);

        editor.commit();
        setContentView(R.layout.activity_main);
    }

    public void getInputsFromUser(View view) {
        Log.e("test","test");
        setContentView(R.layout.activity_data_input);
        initUIVariables();
    }


    public static class ConnectivityChangeEvent {
    }

    protected void startTelemetryTask() {
        // Create a new timer instance
        telemetryTaskTimer = new Timer();
        // Create the task that we will schedule on the timer
        TimerTask task = new TimerTask() {

            // Create JSON Object to send to Metric store
            JSONObject metric;
            ArrayList<JSONObject> metricList = new ArrayList<JSONObject>();
            String metricListForPost = "";

            JSONObject eventDataBody = new JSONObject();
            // Event Data
            HashMap<String, String> eventList = new HashMap<String, String> ();
            // Indexed Fields
            HashMap<String, String> fieldList = new HashMap<String, String> ();

            // Initialize timestamp minute integer
            int tsMinute = 0;

            @Override
            public void run() {
                try {
                    // Get Time Stamp to be sent as _time for Splunk
                    String ts = getTimestampString();

                    /** EVENTS: Adding events data to be sent to Splunk */
                    fieldList.put("aircraftSerialNumber", aircraftSerialNumber);
                    //Get Battery Serial Number
                    fieldList.put("batterySerialNumber", batterySerialNumber);

                    // Get minute value from epoch time
                    tsMinute = Integer.valueOf(ts.substring(ts.length()-2));

                    // Add previous battery data from last minute to every event
                    eventList.putAll(batteryData);
                    // Trigger update of battery diagnostic once per minute
                    if(tsMinute % 20 == 0 && tsMinute != 0) {
                        eventList.putAll(getBatteryDiagnosticEventData());
                        Log.e("batteryDataTest", batteryData.toString());
                    }

                    // Determine if Tripod mode should be turned on when aircraft is flying. Enable / Disable Tripod mode if necessary
                    sendTripodModeCommand();

                    // Critical Flight values
                    eventList.put("velocityX", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getVelocityX()));
                    eventList.put("velocityY", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getVelocityY()));
                    eventList.put("velocityZ", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getVelocityZ()));
                    eventList.put("headingDirection", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getAircraftHeadDirection()));
                    eventList.put("altitude", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getAltitude()));
                    eventList.put("satelliteCount", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getSatelliteCount()));
                    eventList.put("ultrasonicHeight", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getUltrasonicHeightInMeters()));

                    // REVIEW: Might want to leave Latitude and Longitude out if we aren't going to get satellite in the conference...can make this decision when we are there
                    eventList.put("latitude", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getLatitude()));
                    eventList.put("longitude", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getLongitude()));

                    // Additional flight values
                    eventList.put("flightCount", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getFlightCount()));
                    eventList.put("flightTime", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getFlightTimeInSeconds()));
                    eventList.put("flightMode", MApplication.getAircraftInstance().getFlightController().getState().getFlightModeString());
                    eventList.put("isFlying", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().isFlying()));
                    eventList.put("isObstacleAvoidanceDisabled", String.valueOf(isObstacleAvoidanceDisabled));

                    // Add all values in HashMap<String, String> to a single JSON Object for the "event" field in our POST request
                    eventDataBody = createEventDataBody(ts, eventList, fieldList);
                    // Create POST request
                    StringRequest hecPost = createPostRequest(eventDataBody.toString());

                    /** METRICS: Adding metric data to be sent to Splunk
                     metricList.add(createMetricDataBody(ts, "velocityX", MApplication.getAircraftInstance().getFlightController().getState().getVelocityX()));
                     metricList.add(createMetricDataBody(ts, "velocityY", MApplication.getAircraftInstance().getFlightController().getState().getVelocityY()));
                     metricList.add(createMetricDataBody(ts, "velocityZ", MApplication.getAircraftInstance().getFlightController().getState().getVelocityZ()));
                     metricList.add(createMetricDataBody(ts, "headingDirection", MApplication.getAircraftInstance().getFlightController().getState().getAircraftHeadDirection()));
                     metricList.add(createMetricDataBody(ts, "altitude", MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getAltitude()));
                     metricList.add(createMetricDataBody(ts, "satelliteCount", MApplication.getAircraftInstance().getFlightController().getState().getSatelliteCount()));
                     metricList.add(createMetricDataBody(ts, "ultrasonicHeight", MApplication.getAircraftInstance().getFlightController().getState().getUltrasonicHeightInMeters()));

                     // REVIEW: Might want to leave Latitude and Longitude out if we aren't going to get satellite in the conference...can make this decision when we are there
                     metricList.add(createMetricDataBody(ts, "latitude", MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getLatitude()));
                     metricList.add(createMetricDataBody(ts, "longitude", MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getLongitude()));

                     /** REVIEW: This is a string. Need to send as an event to an event index...This will change the values in the POST body that we send
                     * metricList.add(createMetricDataBody(ts, "flightMode", DJISampleApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getAltitude()));

                     metricListForPost = addMetricDataBody(metricList);
                     StringRequest hecPost = createPostRequest(metricListForPost);

                     */

                    // Add HTTP Post to a queue to be run by background threads
                    requestQueue.add(hecPost);
                    eventList.clear();

                } catch (Exception e){ }

            }
        };
        // Schedule the task to run at a fixed interval
        telemetryTaskTimer.scheduleAtFixedRate(task, 0, (long) telemetryTaskRateMs);
    }


    protected void stopTelemetryTask() {
        // Cancel the timer and remove any queued but not yet executed tasks
        telemetryTaskTimer.cancel();
    }

    public void sendTripodModeCommand() {
        // Check if aircraft is flying then check if tripod mode is enabled and not already in tripodmode
        if (MApplication.getAircraftInstance().getFlightController().getState().isFlying()) {
            if (MApplication.getAircraftInstance().getFlightController().getState().getFlightMode().value() != FlightMode.TRIPOD.value()) {
                if (isTripodModeEnabled) {
                    setTripodMode(true);
                    Log.e("Setting Tripod Mode", "TRUE");
                }
            } else {
                if (!isTripodModeEnabled) {
                    setTripodMode(false);
                    Log.e("Setting Tripod Mode", "FALSE");
                }
            }
        }
    }

    public void initDataInputVariables() {
        Context context = this.getApplicationContext();
        SharedPreferences sharedPref = context.getSharedPreferences(getString(R.string.com_splunk_cong19_topgun_preferenceFlightOptions), Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        Resources res = getResources();
        availableIndexes = res.getStringArray(R.array.indexNames);

        if(sharedPref.getAll().size() == 0) {
            // Initial run...set values below if nothing is set
            droneNameSelection = 0;
            droneName = res.getStringArray(R.array.droneNames)[droneNameSelection];
            eventIndex = availableIndexes[droneNameSelection];
            editor.putString(getString(R.string.droneName), droneName);

            // Start enabled
            isObstacleAvoidanceDisabled = true;
            editor.putBoolean(getString(R.string.isObstacleAvoidanceDisabled), isObstacleAvoidanceDisabled);
            isTripodModeEnabled = true;
            editor.putBoolean(getString(R.string.isTripodModeEnabled), isTripodModeEnabled);

        } else {
            // App has run previously and values already exist in the sharedPref file
            for (Map.Entry<String,?> entry : sharedPref.getAll().entrySet()) {
                System.out.println("Key = " + entry.getKey() + ", Value = " + entry.getValue());
                switch (entry.getKey()) {
                    case "droneName": {
                        droneName = sharedPref.getString(getString(R.string.droneName), "N/A");
                        if (droneName.charAt(0) == 'g' || droneName.charAt(0) == 'G') {
                            droneNameSelection = 0;
                        } else {
                            droneNameSelection = 1;
                        }
                    }
                    case "isObstacleAvoidanceDisabled": {
                        isObstacleAvoidanceDisabled = sharedPref.getBoolean(getString(R.string.isObstacleAvoidanceDisabled), false);
                    }
                    case "isTripodModeEnabled": {
                        Log.e("isTripodModeEnabled", String.valueOf(sharedPref.getBoolean(getString(R.string.isTripodModeEnabled),false)));
                        isTripodModeEnabled = sharedPref.getBoolean(getString(R.string.isTripodModeEnabled), true);
                    }
                    default:
                }
            }
        }
        // Set index name
        eventIndex = availableIndexes[droneNameSelection];
        editor.commit();
    }

    public void initUIVariables()  {
        drone = findViewById(R.id.droneName);
        obstacleAvoidanceSwitch = findViewById(R.id.disableObstacleAvoidance);
        tripodModeSwitch = findViewById(R.id.enableTripodMode);
        submit = findViewById(R.id.submit);

        // Create an ArrayAdapter using the string array and a default spinner layout
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.droneNames, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner
        drone.setAdapter(adapter);
        drone.setSelection(droneNameSelection);

        obstacleAvoidanceSwitch.setChecked(isObstacleAvoidanceDisabled);
        tripodModeSwitch.setChecked(isTripodModeEnabled);

    }

    public void setTripodMode(boolean enabled) {
        if(isFlightControllerConnected()) {
            Log.e("setTripodModeCon", String.valueOf(enabled));
            MApplication.getAircraftInstance().getFlightController().setTripodModeEnabled(enabled, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if (djiError != null) {
                        Log.e("SetTripodMode", djiError.toString());
                    }
                }
            });
        }
    }

    public void getAircraftSerialNumberEventData() {
        Log.e("ASN: isPrductConnected", String.valueOf(MApplication.getProductInstance().isConnected()));
        Log.e("ASN: isAirConnected", String.valueOf(MApplication.getAircraftInstance().isConnected()));
        Log.e("ASN: isMAppProdNull", String.valueOf(MApplication.getProductInstance() == null));
        Log.e("ASN: isMAppAirNull", String.valueOf(MApplication.getAircraftInstance() == null));
        Log.e("ASN: isFlightConnNull", String.valueOf(MApplication.getAircraftInstance().getFlightController() == null));
        //Log.e("ASN: isFlightConn", String.valueOf(MApplication.getAircraftInstance().getFlightController().isConnected()));

        if(isFlightControllerConnected()) {
            MApplication.getAircraftInstance().getFlightController().getSerialNumber(new CommonCallbacks.CompletionCallbackWith<String>() {
                @Override
                public void onSuccess(String s) {
                    Log.e("AirCraftSerialNumber", s);
                    aircraftSerialNumber = s;
                }

                @Override
                public void onFailure(DJIError djiError) {
                    Log.e("ASN: isDJIErrNull", String.valueOf(djiError == null));

                }
            });
        }
    }

    public void getBatterySerialNumber() {
        if(DJISDKManager.getInstance() != null && DJISDKManager.getInstance().getProduct().getBattery() != null) {
            DJISDKManager.getInstance().getProduct().getBattery().getSerialNumber(new CommonCallbacks.CompletionCallbackWith<String>() {
                @Override
                public void onSuccess(String s) {
                    batterySerialNumber = s;
                }

                @Override
                public void onFailure(DJIError djiError) {
                    Log.e("BatterySerialNumberE", String.valueOf(djiError == null));

                }
            });
        }
    }

    public HashMap<String, String> getBatteryDiagnosticEventData() {
        if(DJISDKManager.getInstance() != null && DJISDKManager.getInstance().getProduct().getBattery() != null) {
            DJISDKManager.getInstance().getProduct().getBattery().setStateCallback(new BatteryState.Callback() {
                @Override
                public void onUpdate(BatteryState batteryState) {
                    if (batteryState == null) return;
                    else {
                        try {
                            batteryData.put("batteryCharge", String.valueOf(batteryState.getChargeRemainingInPercent()));
                            batteryData.put("batteryTemperature", String.valueOf(batteryState.getTemperature()));
                        } catch (Exception e) {
                        }
                    }
                }
            });

            DJISDKManager.getInstance().getProduct().getBattery().getLatestWarningRecord(new CommonCallbacks.CompletionCallbackWith<WarningRecord>() {
                @Override
                public void onSuccess(WarningRecord warningRecord) {
                    try {
                        batteryData.put("batteryIsShortCircuited", String.valueOf(warningRecord.isShortCircuited()));
                        batteryData.put("batteryIsLowTemp", String.valueOf(warningRecord.isLowTemperature()));
                        batteryData.put("batteryIsOverHeated", String.valueOf(warningRecord.isOverHeated()));
                        batteryData.put("batteryDamagedCellIndex", String.valueOf(warningRecord.getDamagedCellIndex()));
                    } catch (Exception e) {
                    }
                }

                @Override
                public void onFailure(DJIError djiError) {
                    try {
                        //batteryData.put("fail_batteryWarnings", djiError.toString());
                    } catch (Exception e) {
                    }
                }
            });
        }

        return batteryData;
    }

    public void disableObstacleAvoidence(boolean isDisabled) {
        // Invert disabled so that true = a disabled state
        isDisabled = !isDisabled;

        if(isFlightControllerConnected()) {
            if (MApplication.getAircraftInstance().getFlightController().getFlightAssistant() != null) {
                MApplication.getAircraftInstance().getFlightController().getFlightAssistant().setCollisionAvoidanceEnabled(isDisabled, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if(djiError != null) {
                            Log.e("DisableCollAvoidFail", djiError.toString());
                            Log.e("DisableCollAvoidFail", String.valueOf(djiError.getErrorCode()));
                        }
                    }
                });

                MApplication.getAircraftInstance().getFlightController().getFlightAssistant().setActiveObstacleAvoidanceEnabled(isDisabled, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if(djiError != null) {
                            Log.e("DisableObstAvoidFail", djiError.toString());
                            Log.e("DisableObstAvoidFail", String.valueOf(djiError.getErrorCode()));
                        }
                    }
                });

                MApplication.getAircraftInstance().getFlightController().getFlightAssistant().setRTHObstacleAvoidanceEnabled(isDisabled, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if(djiError != null) {
                            Log.e("DisableRTHObstAvoidFail", djiError.toString());
                            Log.e("DisableRTHObstAvoidFail", String.valueOf(djiError.getErrorCode()));
                        }
                    }
                });
            }
        }
    }

    public boolean isFlightControllerConnected(){
        if(MApplication.getAircraftInstance() != null && MApplication.getAircraftInstance().isConnected()) {
            if(MApplication.getAircraftInstance().getFlightController() != null && MApplication.getAircraftInstance().getFlightController().isConnected()) {
                return true;
            }
        }
        return false;
    }

    public JSONObject addMetricToFields(String metric_name, Object _value){
        //String fields = "\"metric_name\":\"" + metric_name + "\",\"_value\"" + _value.toString() + "\"";
        JSONObject fields = new JSONObject();
        try {

            /** Add Dimensions Here */

            fields.put("metric_name", metric_name);
            fields.put("_value", _value);

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return fields;
    }

    /** REVIEW: This code builds a json object to mimic the format for metric index data: http://dev.splunk.com/view/event-collector/SP-CAAAFDN */
    public JSONObject createMetricDataBody(String _time, String metric_name, Object _value) {
        JSONObject metricEvent = new JSONObject();

        try {
            metricEvent.put("time", _time);
            metricEvent.put("event", "metric");
            metricEvent.put("source", "spark");
            metricEvent.put("host", droneName);
            metricEvent.put("index", metricIndex);

            metricEvent.put("fields", addMetricToFields(metric_name, _value));

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return metricEvent;

    }

    public String addMetricDataBody(ArrayList<JSONObject> metricObjects) {

        String temp = "";

        for (int i=0; i<metricObjects.size(); i++) {
            temp += metricObjects.get(i).toString();
        }

        return temp;
    }

    public JSONObject addDataToEvent(HashMap<String, String> eventList) {
        JSONObject eventData = new JSONObject();
        try {
            for (Map.Entry<String,String> entry : eventList.entrySet()) {
                eventData.put(entry.getKey(), entry.getValue());
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return eventData;

    }

    public JSONObject convertHashMapToData(HashMap<String, String> dataHashMap) {
        JSONObject data = new JSONObject();
        try {
            for (Map.Entry<String,String> entry : dataHashMap.entrySet()) {
                data.put(entry.getKey(), entry.getValue());
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }
        return data;
    }

    public JSONObject createEventDataBody(String _time, HashMap<String, String> eventList, HashMap<String, String> fieldsList) {
        JSONObject eventData = new JSONObject();
        try {
            eventData.put("time", _time);
            eventData.put("source", "spark");
            eventData.put("host", droneName);
            eventData.put("index", eventIndex);

            eventData.put("event", convertHashMapToData(eventList));
            eventData.put("fields", convertHashMapToData(fieldsList));

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return eventData;
    }

    /** REVIEW: Create the HTTP request and return the object so it can be addded to the Queue above */
    public StringRequest createPostRequest(final String metricListForPost){
        // metricEvent is the JSON payload to send to Splunk...
        StringRequest stringRequest = new StringRequest(Request.Method.POST, hecURL, new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {
                Log.e("HTTP Response", response.toString());

            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                error.printStackTrace();
            }
        }){

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                HashMap<String, String> headers = new HashMap<String, String>();
                headers.put("Authorization", "Splunk " + hecToken);
                headers.put("Content-Type","application/x-www-form-urlencoded");

                return headers;
            }

            @Override
            public byte[] getBody() throws AuthFailureError {
                String httpPostBody = metricListForPost;
                // usually you'd have a field with some values you'd want to escape, you need to do it yourself if overriding getBody. here's how you do it
                try {
                    return metricListForPost == null ? null : metricListForPost.getBytes("utf-8");

                } catch (UnsupportedEncodingException exception) {
                    Log.e("ERROR", "exception", exception);
                    // return null and don't pass any POST string if you encounter encoding error
                    return null;
                }
            }

        };

        return stringRequest;
    }

    public String getTimestampString() {
        // Get Time Stamp to be sent as _time for Splunk
        Long tsLong = System.currentTimeMillis()/1000;
        return tsLong.toString();
    }

    public void testHTTPPost(View view) {
        // Code to clear cache...using for testing...might need later
        // requestQueue.getCache().clear();

        // Get Time Stamp to be sent as _time for Splunk
        String ts = getTimestampString();
        Log.e("Timestamp",ts);


        /** METRIC Data Test
        // Create JSON Object to send to Metric store
        JSONObject metric;
        ArrayList<JSONObject> metricList = new ArrayList<JSONObject>();
        String metricListForPost = "";

        metric = createMetricDataBody(ts, "velocityX", 123456789);
        metricList.add(metric);
        metric = createMetricDataBody(ts, "velocityY", 987654321);
        metricList.add(metric);
        metric = createMetricDataBody(ts, "velocityZ", 9997);
        metricList.add(metric);

        metricListForPost = addMetricDataBody(metricList);
        StringRequest hecPost = createPostRequest(metricListForPost);
         */


        // Event Data Test
        // Create JSON Object to send to Metric store
        JSONObject eventDataBody;
        final HashMap<String, String> eventList = new HashMap<String, String> ();
        final HashMap<String, String> fieldList = new HashMap<String, String> ();

        eventList.put("velocityX", "123456789");
        eventList.put("velocityY", "987654321");
        eventList.put("velocityZ", "9998");

        eventDataBody = createEventDataBody(ts, eventList, fieldList);

        StringRequest hecPost = createPostRequest(eventDataBody.toString());

        // Add HTTP Post to a queue to be run by background threads
        try {
            requestQueue.add(hecPost);
        } catch (Exception e) {
            Log.e("Volley: Add to queue", e.getMessage());
        }
    }
}