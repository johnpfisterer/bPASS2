package edu.uri.egr.bPASS2;

import android.app.AlertDialog;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Message;
import android.view.GestureDetector;
import android.view.Menu;
import android.widget.Switch;
import android.widget.TextView;

// ACCEL
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.hardware.SensorEventListener;

// Timer Tasks
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

import android.os.Handler;
import android.widget.Toast;


/**
 * Created by cody on 10/8/15.\\
 * Edited by John on 10/28/15
 */
public class MainActivity extends HermesActivity implements SensorEventListener{
    //for bluetooth
    public static final String UART_SERVICE = RBLGattAttributes.BLE_SHIELD_SERVICE;
    public static final String UART_RX = RBLGattAttributes.BLE_SHIELD_RX;
    public static final String UART_TX = RBLGattAttributes.BLE_SHIELD_TX;

    // Create Activity global variables for things we need across different methods.
    private BluetoothGatt mGatt;
    private Subscription mDeviceSubscription;
    // for the graphing
    private int loop_i = 0;
    private int error_loop = 0;
    private LineGraphSeries<DataPoint> dataSeries;
    private final Handler mHandler = new Handler();
    //for the Alarm Phase
    private boolean alarmOn = false; //is the alarm on
    private MediaPlayer mp;         //set up to play sounds
    //For the STOP buttin double click
    private static final long DOUBLE_PRESS_INTERVAL = 250; // in millis
    private long lastPressTime;
    private boolean mHasDoubleClicked = false;

    // Access the views from our layout.
    @Bind(R.id.control_button) Switch mControlButton;
    @Bind(R.id.analog_value) TextView mTextValue;
    @Bind(R.id.heartrate_value) TextView hTextValue;
    @Bind(R.id.graph) GraphView graph;
    @Bind(R.id.motion_textView) TextView motion_TextView;
    @Bind(R.id.pulse_textView) TextView pulse_TextView;
    @Bind(R.id.o2_textView) TextView o2_TextView;

    // Accelerometer Variables
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private float vals[] = new float[3];
    private float gravity[] = new float[3];
    private float linear_acceleration[] = new float[3];

    //Timer variables
    private int elasped_time = 0;
    final Handler myHandler = new Handler();
    TextView timerstring;
    private float abs_accel[] = new float[3];


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
            public void run(){UpdateGUI();}
        },0,1000);
    }
    /*
    This Method Runs the bluetooth connection
     */
    protected void  runBLE(){
        BLESelectionDialog dialog = new BLESelectionDialog();
        // Now, we need to subscribe to it.  This might look like black magic, but just follow the comments.
        mDeviceSubscription = dialog.getObservable() // Get the Observable from the device dialog.

                // We'll want to close our activity if we don't select any devices.
                //.doOnCompleted(() -> { if (mGatt == null) finish(); })

                // Once we get the device, we hit this flatMap.  Using flatMap, we can convert this device into a connection.
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
                                        if (uartEvent.type == 0x0B) {
                                            mTextValue.setText(String.valueOf(uartEvent.data));
                                            dataSeries.appendData(new DataPoint(loop_i, uartEvent.data), true, 400);
                                            //graph.addSeries(dataSeries);
                                            //mHandler.postDelayed(this, 200);
                                            loop_i++;
                                        } else if (uartEvent.type == 0x0A)
                                            hTextValue.setText(String.valueOf(uartEvent.data));
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
        linear_acceleration[0] = vals[0] - gravity[0];
        linear_acceleration[1] = vals[1] - gravity[1];
        linear_acceleration[2] = vals[2] - gravity[2];

        // Take the absolute value of all axis and resets the time if above a threshold
        for (int i=0; i<3; i++){
            abs_accel[i] = Math.abs(linear_acceleration[i]);
            if (abs_accel[i] > 0.5) {
                if(elasped_time < 30) {
                    elasped_time = 0;
                    endAlarm(motion_TextView);
                }
            }
        }
    }

    //@Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void UpdateGUI(){
        if (elasped_time == 20|elasped_time == 30){
            activateAlarm(motion_TextView);
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
        public void run(){ motion_TextView.setTextColor(Color.RED);
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
        activateAlarm(o2_TextView);
    }    //When  the big end button is double pressed stop the alarms
    @OnClick(R.id.endAlarmButton)
    public void onEndAlarmClicked() {
        // Get current time in nano seconds.
        long pressTime = System.currentTimeMillis();
        // If double click...
        if (pressTime - lastPressTime <= DOUBLE_PRESS_INTERVAL) {
            //endAlarm(o2_TextView);
            //endAlarm(pulse_TextView);
            endAlarm(motion_TextView);
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
    private void activateAlarm(TextView text_view){
        if (!alarmOn){
            mp = MediaPlayer.create(this, R.raw.passprealarm);
            mp.setLooping(true);
            mp.start();
            alarmOn = true;
        }
        else {
            alarmOn = false;
            mp.stop();
            mp.release();
            mp = null;

            mp = MediaPlayer.create(this, R.raw.passfullalarm);
            mp.setLooping(true);
            mp.start();
            alarmOn = true;
        }
    }

    //Turn off the alarm
    private void endAlarm(TextView text_view) {
        if (alarmOn) {
            mp.stop();
            mp.release();
            mp = null;
            text_view.setTextColor(Color.BLACK);
            alarmOn = false;
            elasped_time = 0;
        }
    }
    /*
    onDestroy is ran every time the activity is destroyed.  This is normally the last we see of the Activity.
    Because of this, we don't want our bluetooth subscriptions to continue to run.
    We NEED to tell HermesBLE to clean up our mess.  Otherwise, good luck connecting again!
     */
    @Override
    protected void onDestroy() {
        super.onDestroy(); // Call super, because things.

        HermesBLE.close(mGatt); // Have Hermes handle closing out our bluetooth connection for us.
        mDeviceSubscription.unsubscribe(); // And unsubscribe from the dialog we created.

        // Finally, just incase we're not really closing, make sure we do - by running finish.
        finish();
    }

}
