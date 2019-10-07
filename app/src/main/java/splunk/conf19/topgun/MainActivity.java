package splunk.conf19.topgun;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

// For HTTP Post Requests
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.VolleyError;
import com.android.volley.Response;
import com.android.volley.AuthFailureError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.android.volley.toolbox.StringRequest;

import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.battery.BatteryState;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;

public class MainActivity extends AppCompatActivity {

    // The rate at which we will run our task in seconds
    private int telemetryTaskRateSeconds = 1;
    // Convert to milliseconds as this is what the timer API expects
    private int telemetryTaskRateMs = telemetryTaskRateSeconds * 1000;
    // Timer object we will use to schedule and cancel the task
    private Timer telemetryTaskTimer;

    // HTTP HEC Endpoint
    String ec2URL = "54.153.8.100";
    String hecPort = "8088";
    String hecEndpoint = "/services/collector";
    String hecToken = "06136b13-c8ff-4be1-861b-15dbf1baceaf";
    String hecURL = "http://" + ec2URL + ":" + hecPort + hecEndpoint;
    String metricIndex = "telemetrytest";

    // Queue to handle all POST requests
    RequestQueue requestQueue;

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

        // When the compile and target version is higher than 22, please request the following permission at runtime to ensure the SDK works well.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkAndRequestPermissions();
        }

        MApplication.getEventBus().register(this);

        // Create requestQueue for HTTP Post Requests to HEC
        requestQueue = Volley.newRequestQueue(this);

        setContentView(R.layout.activity_main);

        //Initialize DJI SDK Manager
        mHandler = new Handler(Looper.getMainLooper());

    }

    @Override
    protected void onDestroy() {
        MApplication.getEventBus().unregister(this);
        super.onDestroy();
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

            @Override
            public void run() {
                try {
                    Log.e("VELOCITY_X", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getVelocityX()));
                    Log.e("VELOCITY_Y", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getVelocityY()));
                    Log.e("VELOCITY_Z", String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getVelocityZ()));
                    Log.e("HEADING_DIRECTION",String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getAircraftHeadDirection()));
                    Log.e("ALTITUDE",String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getAltitude()));
                    Log.e("LATITUDE",String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getLatitude()));
                    Log.e("LONGITUDE",String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getLongitude()));
                    Log.e("FLIGHT_MODE",String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getFlightModeString()));
                    Log.e("SATELLITE_COUNT",String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getSatelliteCount()));
                    Log.e("ULTRASONIC_HEIGHT",String.valueOf(MApplication.getAircraftInstance().getFlightController().getState().getUltrasonicHeightInMeters()));

                    // Get Time Stamp to be sent as _time for Splunk
                    String ts = getTimestampString();
                    Log.e("Timestamp",ts);

                    metricList.add(createMetricDataBody(ts, "velocityX", MApplication.getAircraftInstance().getFlightController().getState().getVelocityX()));
                    metricList.add(createMetricDataBody(ts, "velocityY", MApplication.getAircraftInstance().getFlightController().getState().getVelocityY()));
                    metricList.add(createMetricDataBody(ts, "velocityZ", MApplication.getAircraftInstance().getFlightController().getState().getVelocityZ()));
                    metricList.add(createMetricDataBody(ts, "headingDirection", MApplication.getAircraftInstance().getFlightController().getState().getAircraftHeadDirection()));
                    metricList.add(createMetricDataBody(ts, "altitude", MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getAltitude()));
                    metricList.add(createMetricDataBody(ts, "satelliteCount", MApplication.getAircraftInstance().getFlightController().getState().getSatelliteCount()));
                    metricList.add(createMetricDataBody(ts, "ultrasonicHeight", MApplication.getAircraftInstance().getFlightController().getState().getUltrasonicHeightInMeters()));

                    /** REVIEW: Might want to leave Latitude and Longitude out if we aren't going to get satellite in the conference...can make this decision when we are there */
                    metricList.add(createMetricDataBody(ts, "latitude", MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getLatitude()));
                    metricList.add(createMetricDataBody(ts, "longitude", MApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getLongitude()));

                    /** REVIEW: This is a string. Need to send as an event to an event index...This will change the values in the POST body that we send
                     * metricList.add(createMetricDataBody(ts, "flightMode", DJISampleApplication.getAircraftInstance().getFlightController().getState().getAircraftLocation().getAltitude()));
                     */


                    metricListForPost = addMetricDataBody(metricList);
                    StringRequest hecPost = createPostRequest(metricListForPost);

                    // Add HTTP Post to a queue to be run by background threads
                    requestQueue.add(hecPost);
                    metricList.clear();

                } catch (Exception e){
                    Log.e("Trying Timer Task", e.getMessage());
                }


                DJISDKManager.getInstance().getProduct().getBattery().setStateCallback(new BatteryState.Callback() {
                    @Override
                    public void onUpdate(BatteryState batteryState) {
                        if (batteryState == null) return;
                        else {
                            int charge = batteryState.getChargeRemainingInPercent();
                            float temperature = batteryState.getTemperature();

                            Log.e("Battery", String.valueOf(charge));
                            Log.e("Temperature", String.valueOf(temperature));

                        }
                    }
                });


            }
        };
        // Schedule the task to run at a fixed interval
        telemetryTaskTimer.scheduleAtFixedRate(task, 0, telemetryTaskRateMs);
    }


    protected void stopTelemetryTask() {
        // Cancel the timer and remove any queued but not yet executed tasks
        telemetryTaskTimer.cancel();
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
            metricEvent.put("host", "Goose");
            metricEvent.put("index", "telemetrytest");

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

        // Add HTTP Post to a queue to be run by background threads
        try {
            requestQueue.add(hecPost);
        } catch (Exception e) {
            Log.e("Volley: Add to queue", e.getMessage());
        }

    }

}