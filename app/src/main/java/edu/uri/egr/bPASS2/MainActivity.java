package edu.uri.egr.bPASS2;

import android.app.AlertDialog;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.Switch;
import android.widget.TextView;

// ACCEL
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.hardware.SensorEventListener;

// Timer Tasks
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import android.os.Handler;

import butterknife.Bind;
import butterknife.OnClick;
import edu.uri.egr.hermesble.HermesBLE;
import edu.uri.egr.hermesble.attributes.RBLGattAttributes;
import edu.uri.egr.hermesble.ui.BLESelectionDialog;
import edu.uri.egr.hermesui.activity.HermesActivity;
import rx.Subscription;
import timber.log.Timber;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import android.media.MediaPlayer;


/**
 * Created by cody on 10/8/15.\\
 * Edited by John on 10/28/15
 */
public class MainActivity extends HermesActivity implements SensorEventListener{
    //for bluetooth
    public static final String UART_SERVICE = RBLGattAttributes.BLE_SHIELD_SERVICE,
                               UART_RX = RBLGattAttributes.BLE_SHIELD_RX,
                               UART_TX = RBLGattAttributes.BLE_SHIELD_TX;

    // Create Activity global variables for things we need across different methods.
    private BluetoothGatt mGatt;
    private Subscription mDeviceSubscription;

    // for the graphing
    private int loop_i = 0,
                error_loop = 0;
    private LineGraphSeries<DataPoint> dataSeries;
    private final Handler mHandler = new Handler();

    //for the Alarm Phase
    private boolean alarmOn[] = new boolean[4]; // [passAlarm hrAlarm rrAlarm tempAlarm]
    private MediaPlayer mp;         //set up to play sounds

    //For the STOP button double click
    private static final long DOUBLE_PRESS_INTERVAL = 250; // in millis
    private long lastPressTime;
    private boolean mHasDoubleClicked = false;

    //For Heart Rate
    private int heartRate[] = new int[60];
    private int maxHR = 220,
                indexHR = 0;
    private boolean isHRBuffered = false;

    //For Respiration Rate
    private int respirationRate[] = new int[60];
    private int maxRR = 30,
            indexRR = 0;
    private boolean isRRBuffered = false;


    // Access the views from our layout.
    @Bind(R.id.control_button) Switch mControlButton;
    @Bind(R.id.heartRate_value) TextView heartTextValue;
    @Bind(R.id.temp_value) TextView tempTextValue;
    @Bind(R.id.graph) GraphView graph;
    @Bind(R.id.motion_textView) TextView motion_textView;
    @Bind(R.id.pulse_textView) TextView pulse_textView;
    @Bind(R.id.o2_textView) TextView o2_textView;
    @Bind(R.id.temp_textView) TextView temp_textView;
    @Bind(R.id.pressure_value) TextView pTextValue;
    @Bind(R.id.age_editText) TextView ageEditText;

    // Accelerometer Variables
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private float vals[] = new float[3];
    private float gravity[] = new float[3];
    private float linearAcceleration[] = new float[3];

    //Timer variables
    private int elasped_time = 0;
    final Handler myHandler = new Handler();
    TextView timerstring;
    private float abs_accel[] = new float[3];
    private int age = 0;

    /*
    This is called when the Activity is created.  In Android, this will be when the activity is started fresh
    for the first time, or when the screen is rotated.  Pressing the back button will cause the view to be
    destroyed, but pressing home, and then using multitasking to get back, will not (most of the time)
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dataSeries = new LineGraphSeries<DataPoint>();
        graph.addSeries(dataSeries);
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(100);

        runBLE();

         /* Configure the accelerometer to report data at the fastest rate. */
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorManager.registerListener(this,mAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        /*
        Accelerometer, SENSOR_DELAY_FASTEST: 18-20 ms
        Accelerometer, SENSOR_DELAY_GAME: 37-39 ms
        Accelerometer, SENSOR_DELAY_UI: 85-87 ms
        Accelerometer, SENSOR_DELAY_NORMAL: 215-230 ms */

        // Timer
        timerstring = (TextView)findViewById(R.id.textView4);
        Timer myTimer = new Timer();
        myTimer.schedule(new TimerTask(){
            @Override
            public void run(){
                updateGUI();
            }
        },0,1000); // run every 1 seconds
    }
    /*
    This Method Runs the bluetooth connection
     */
    protected void  runBLE(){
        BLESelectionDialog dialog = new BLESelectionDialog();
        // Now, we need to subscribe to it.
        mDeviceSubscription = dialog.getObservable() // Get the Observable from the device dialog.

                // We'll want to close our activity if we don't select any devices.
                //.doOnCompleted(() -> { if (mGatt == null) finish(); })

                // Once we get the device, we hit this flatMap.
                // Using flatMap, we can convert this device into a connection.
                .flatMap(HermesBLE::connect)

                        // Only continue if our connection event type is STATE_CONNECTED
                .filter(event -> event.type == BluetoothProfile.STATE_CONNECTED)

                        // After the above runs, we'll be connected.  So, the first "event" we get will be a success.
                        // Lets take out the BluetoothGatt from this event and save it.  We'll need it to clean up later.
                .doOnNext(event -> mGatt = event.gatt)

                .flatMap(event -> HermesBLE.getServices(event.gatt))
                .subscribe(bleServiceEvent -> {
                            mControlButton.setEnabled(true);
                            // Do stuff here in response to services.
                            HermesBLE.listen(bleServiceEvent.gatt, UART_SERVICE, UART_RX)

                                    .flatMap(event -> new UartEvaluator().handle(event))
                                    .subscribe(uartEvent -> {
                                        Timber.d("Received event: %02x - with data: %s", uartEvent.type, String.valueOf(uartEvent.data));
                                        if (uartEvent.type == 0x0C) {
                                            heartTextValue.setText(String.valueOf(uartEvent.data));

                                            // stores last 60 index of HR
                                            heartRate[indexHR] = uartEvent.data;
                                            indexHR++;
                                            if (indexHR == heartRate.length) {
                                                indexHR = 0;
                                                isHRBuffered = true;
                                            }
                                            if (isHRBuffered) {
                                                checkHRAverage();
                                            }
                                            dataSeries.appendData(new DataPoint(loop_i, uartEvent.data), true, 400);
                                            //graph.addSeries(dataSeries);
                                            //mHandler.postDelayed(this, 200);
                                            loop_i++;
                                        } else if (uartEvent.type == 0x0B) {
                                            tempTextValue.setText(String.valueOf(uartEvent.data));
                                        } else if (uartEvent.type == 0x0A) {
                                            pTextValue.setText(String.valueOf(uartEvent.data));

                                            // stores last 60 index of RR
                                            respirationRate[indexRR] = uartEvent.data;
                                            indexRR++;
                                            if (indexRR == respirationRate.length) {
                                                indexRR = 0;
                                                isRRBuffered = true;
                                            }
                                            if (isRRBuffered) {
                                                checkRRAverage();
                                            }
                                        }
                                        // Do stuff here in response to the data event.
                                    });
                        },
                        err -> {
                            // Do something here in response to the error.
                            error_loop++;
                            if (error_loop < 10){
                                runBLE();
                                showSimplePopUp();
                            }
                            else {
                                error_loop = 0;
                                showSimplePopUp();
                            }


                        });
        // We also need to make sure our dialog can be seen.  If this isn't run, then nothing shows up!.
        dialog.show(getFragmentManager(), "dialog");
    }

    //@Override
    public void onSensorChanged(SensorEvent sensorEvent)//
    {
        Sensor mySensor = sensorEvent.sensor;

        final float alpha = (float)0.8;

        /* Save the sensor variables locally so we can manipulate and display later. */
        for (int j = 0; j < 3; j++) {
            vals[j] = sensorEvent.values[j];
        }

        // Isolate the force of gravity with the low-pass filter.
        gravity[0] = alpha * gravity[0] + (1 - alpha) * vals[0];
        gravity[1] = alpha * gravity[1] + (1 - alpha) * vals[1];
        gravity[2] = alpha * gravity[2] + (1 - alpha) * vals[2];

        // Remove the gravity contribution with the high-pass filter.
        linearAcceleration[0] = vals[0] - gravity[0];
        linearAcceleration[1] = vals[1] - gravity[1];
        linearAcceleration[2] = vals[2] - gravity[2];

        // Take the absolute value of all axis and resets the time if above a threshold
        for (int i=0; i<3; i++){
            abs_accel[i] = Math.abs(linearAcceleration[i]);
            if (abs_accel[i] > 0.5) {
                if(elasped_time < 30) {
                    elasped_time = 0;
                    endAllAlarms();
                }
            }
        }
    }

    //@Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void updateGUI(){
        if (elasped_time == 20|elasped_time == 30){
            activatePassAlarm(motion_textView);
        }
        elasped_time++;
        myHandler.post(updateTime);
    }

    final Runnable updateTime = new Runnable(){
        public void run(){
            timerstring.setText(String.valueOf(elasped_time));
        }
    };

    final MyRunnable updateMotionColor = new MyRunnable(){
        public void run(){ motion_textView.setBackgroundColor(Color.parseColor("#F44336"));
        }
    };

    // Hook into our control button, and allow us to run code when one clicks on it.
    @OnClick(R.id.control_button)
    public void onControlClicked() {
        // Create a byte package to send over to the nano.
        byte[] data = new byte[]{(byte) 0xA0, (byte) 0x00, (byte) 0x00};

        // Trigger the value we send.  This is a toggle button - so whenever we're running, shut off.  Whenever we're off, turn on.
        if (mControlButton.isChecked())
            data[1] = (byte) 0x01; // Send a value of 1 if we're enabled.

        // Finally, write the value!
        HermesBLE.write(mGatt, UART_SERVICE, UART_TX, data);
    }
    @OnClick(R.id.alarmButton)
    public void onAlarmClicked() {
        activatePassAlarm(o2_textView);
    }    //When  the big end button is double pressed stop the alarms

    @OnClick(R.id.endAlarmButton)
    public void onEndAlarmClicked() {
        // Get current time in nano seconds.
        long pressTime = System.currentTimeMillis();
        // If double click...
        if (pressTime - lastPressTime <= DOUBLE_PRESS_INTERVAL) {
            //endAlarm(o2_TextView);
            //endAlarm(pulse_TextView);
            endAllAlarms();
        }
        // record the last time the menu button was pressed.
        lastPressTime = pressTime;
    }
    private void showSimplePopUp() {

        AlertDialog.Builder helpBuilder = new AlertDialog.Builder(this);
        helpBuilder.setTitle("Connection Error");
        helpBuilder.setMessage("There was an Error and the App will Crash");
        helpBuilder.setPositiveButton("Ok",
                new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface dialog, int which) {
                        // Do nothing but close the dialog
                    }
                });

        // Remember, create doesn't show the dialog
        AlertDialog helpDialog = helpBuilder.create();
        helpDialog.show();
    }
    private void activatePassAlarm(TextView text_view){
        if (!alarmOn[0]){
            if(isAnyTrue(alarmOn)){
                mp.stop();
                mp.release();
                mp = null;
                myHandler.post(updateMotionColor);
            }
            mp = MediaPlayer.create(this, R.raw.passprealarm);
            mp.setLooping(true);
            mp.start();
            alarmOn[0] = true;
        }
        else {
            mp.stop();
            mp.release();
            mp = null;
            myHandler.post(updateMotionColor);
            mp = MediaPlayer.create(this, R.raw.passfullalarm);
            mp.setLooping(true);
            mp.start();
            alarmOn[0] = true;
        }
    }

    private void activateHeartRateAlarm(){
        if (!isAnyTrue(alarmOn)) {
            pulse_textView.setBackgroundColor(Color.parseColor("#F44336"));
            mp = MediaPlayer.create(this, R.raw.tachycardiaalarm);
            mp.setLooping(true);
            mp.start();
        }
        alarmOn[2] = true;
    }

    private void activateRespirationRateAlarm(){
        if (!isAnyTrue(alarmOn)) {
            o2_textView.setBackgroundColor(Color.parseColor("#F44336"));
            mp = MediaPlayer.create(this, R.raw.tachycardiaalarm);
            mp.setLooping(true);
            mp.start();
        }
        alarmOn[3] = true;
    }

    //Turn off the alarm
    private void endAllAlarms() {
        if (isAnyTrue(alarmOn)) {
            mp.stop();
            mp.release();
            mp = null;
            TextView[] alarms = {motion_textView, o2_textView, temp_textView, pulse_textView};
            for(int i = 0; i < alarms.length; i++){
                alarms[i].setBackgroundColor(Color.parseColor("#4CAF50"));
            }
            Arrays.fill(alarmOn, false); // set all alarms to false
            elasped_time = 0;
        }
    }

    //Turn off the alarm
    private void endAlarm(TextView text_view) {
        if (!alarmOn[0]) {
            mp.stop();
            mp.release();
            mp = null;
            text_view.setBackgroundColor(Color.parseColor("#4CAF50"));
            elasped_time = 0;
        }
    }

    @OnClick(R.id.age_button)
    public void onAgeClicked() {
        try {
            age = Integer.parseInt(ageEditText.getText().toString());
        } catch (NumberFormatException e) {
            ageEditText.setText("Enter a number");
        }
        maxHR = 220 - age;

        InputMethodManager inputManager = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(),
                InputMethodManager.HIDE_NOT_ALWAYS);
        heartTextValue.setText(String.valueOf(maxHR));
    }
    
    protected void onDestroy() {
        super.onDestroy(); // Call super, because things.

        HermesBLE.close(mGatt); // Have Hermes handle closing out our bluetooth connection for us.
        mDeviceSubscription.unsubscribe(); // And unsubscribe from the dialog we created.

        // Finally, just incase we're not really closing, make sure we do - by running finish.
        finish();
    }

    /**
     * Checks to see if any value is true
     * @param array
     * @return true if any boolean in the array true.
     */
    private boolean isAnyTrue(boolean[] array)
    {
        for(boolean index : array){
            if(index){
                return true;
            }
        }
        return false;
    }

    /**
     * Calculates the average heart rate and will trigger an alarm if appropriate
     */
    private void checkHRAverage(){
        int sum = 0;
        for(int i : heartRate){
            sum = sum + i;
        }
        int average = sum/60;
        if(average > maxHR) {
            if (!alarmOn[1]){
                activateHeartRateAlarm();
            }
        }else{
            if(alarmOn[1]){
                endAlarm(pulse_textView);
            }
            alarmOn[1] = false;
        }
    }

    /**
     * Calculates the average heart rate and will trigger an alarm if appropriate
     */
    private void checkRRAverage(){
        int sum = 0;
        for(int i : respirationRate){
            sum = sum + i;
        }
        int average = sum/60;
        if(average > maxRR){
            if(!alarmOn[2]){
                //activateRespirationRateAlarm();
            }
        }else{
            if(alarmOn[2]) {
                endAlarm(o2_textView);
            }
            alarmOn[2] = false;
        }
    }


}
