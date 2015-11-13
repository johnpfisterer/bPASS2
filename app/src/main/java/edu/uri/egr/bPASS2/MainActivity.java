package edu.uri.egr.bPASS2;

import android.app.AlertDialog;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;

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


/**
 * Created by cody on 10/8/15.\\
 * Edited by John on 10/28/15
 */
public class MainActivity extends HermesActivity {
    public static final String UART_SERVICE = RBLGattAttributes.BLE_SHIELD_SERVICE;
    public static final String UART_RX = RBLGattAttributes.BLE_SHIELD_RX;
    public static final String UART_TX = RBLGattAttributes.BLE_SHIELD_TX;

    // Create Activity global variables for things we need across different methods.
    private BluetoothGatt mGatt;
    private Subscription mDeviceSubscription;

    private int loop_i = 0;
    private int error_loop = 0;
    private LineGraphSeries<DataPoint> dataSeries;
    private final Handler mHandler = new Handler();

    private boolean alarmOn = false;

    private MediaPlayer mp;

    //MediaPlayer mp = MediaPlayer.create(this, R.raw.alarm2);

    // Access the views from our layout.
    @Bind(R.id.control_button) Switch mControlButton;
    @Bind(R.id.analog_value) TextView mTextValue;
    @Bind(R.id.heartrate_value) TextView hTextValue;
    @Bind(R.id.graph) GraphView graph;
    @Bind(R.id.motion_textView) TextView motion_TextView;
    @Bind(R.id.pulse_textView) TextView pulse_TextView;
    @Bind(R.id.o2_textView) TextView o2_TextView;


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
    }
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
        if (alarmOn == false){
            mp = MediaPlayer.create(this, R.raw.alarm2);
            mp.setLooping(true);
            mp.start();
            text_view.setTextColor(Color.rgb(255, 0, 0));
            alarmOn = true;
        }
        else{
            mp.stop();
            mp.release();
            mp = null;
            text_view.setTextColor(Color.rgb(0, 0, 0));
            alarmOn = false;
        }


    }
}
