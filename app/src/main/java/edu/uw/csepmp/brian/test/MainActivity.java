package edu.uw.csepmp.brian.test;

import android.app.Activity;
import android.app.FragmentManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.androidplot.Plot;
import com.androidplot.util.PixelUtils;
import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.XYPlot;
import com.androidplot.xy.XYSeries;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.Observable;
import java.util.Observer;
import java.util.UUID;


public class MainActivity extends Activity implements BluetoothAdapter.LeScanCallback {
    // State machine
    final private static int STATE_BLUETOOTH_OFF = 1;
    final private static int STATE_DISCONNECTED = 2;
    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
            if (state == BluetoothAdapter.STATE_ON) {
                upgradeState(STATE_DISCONNECTED);
            } else if (state == BluetoothAdapter.STATE_OFF) {
                downgradeState(STATE_BLUETOOTH_OFF);
            }
        }
    };
    final private static int STATE_CONNECTING = 3;
    final private static int STATE_CONNECTED = 4;
    MyObservable dataChangeSignal;
    DynamicSeries series;
    private int state;
    private boolean scanStarted;
    private boolean scanning;
    private final BroadcastReceiver scanModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            scanning = (bluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_NONE);
            scanStarted &= scanning;
            updateUi();
        }
    };
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice bluetoothDevice;
    private RFduinoService rfduinoService;
    private ServiceConnection rfduinoServiceConnection;
    private Button enableBluetoothButton;
    private TextView scanStatusText;
    private Button scanButton;
    private TextView deviceInfoText;
    private TextView connectionStatusText;
    private Button connectButton;
    private Button disconnectButton;
    private RetainedFragment dataFragment;
    XYPlot dynamicPlot;
    private boolean serviceBound;
    private boolean connectionIsOld = false;
    private boolean fromNotification = false;
    private boolean serviceInForeground = false;
    private int MAX_SERIES_SIZE = 750;
    private int counter =0;


    private final BroadcastReceiver rfduinoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.v("Main", "rfduinoReceiver called with " + action);
            if (RFduinoService.ACTION_CONNECTED.equals(action)) {
                upgradeState(STATE_CONNECTED);
            } else if (RFduinoService.ACTION_DISCONNECTED.equals(action)) {
                downgradeState(STATE_DISCONNECTED);
            } else if (RFduinoService.ACTION_DATA_AVAILABLE.equals(action)) {
                addData(intent.getByteArrayExtra(RFduinoService.EXTRA_DATA));
                counter++;
                if(counter % 200 == 0 && series.size() == MAX_SERIES_SIZE) {
                    Log.d("Main", "Run BPM");

                    counter =0;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Float BPM = series.getBPM();
                            TextView bpmTextView = (TextView) findViewById(R.id.BPM);
                            bpmTextView.setText(BPM + " BPM");
                        }
                    });
                }
            }
        }
    };

    @Override
    public void onLeScan(BluetoothDevice device, final int rssi, final byte[] scanRecord) {
        bluetoothAdapter.stopLeScan(this);
        bluetoothDevice = device;
        scanning = false;

        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                deviceInfoText.setText(
                        BluetoothHelper.getDeviceInfoText(bluetoothDevice, rssi, scanRecord));
                updateUi();
            }
        });
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {

        // android boilerplate stuff
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(
                getString(R.string.preference_file_key), Context.MODE_PRIVATE);
        serviceInForeground = sharedPref.getBoolean("foregroundServiceRunning", false);

        // find the retained fragment on activity restarts
        FragmentManager fm = getFragmentManager();
        dataFragment = (RetainedFragment) fm.findFragmentByTag("data");

        // create the fragment and data the first time
        if (dataFragment == null) {
            // add the fragment
            dataFragment = new RetainedFragment();
            fm.beginTransaction().add(dataFragment, "data").commit();
        }
        else
        {
            BTLEBundle btleBundle = dataFragment.getData();
            if(btleBundle != null)
            {
                bluetoothDevice = btleBundle.device;
                serviceBound = btleBundle.isBound;
                scanStarted = btleBundle.scanStarted;
                scanning = btleBundle.scanning;
                if(serviceBound) {
                    // only restore the connection if there has been one
                    rfduinoServiceConnection = btleBundle.connection;
                    rfduinoService = btleBundle.service;
                    connectionIsOld = true; // setting this flag to true to indicate a rotation
                }
                state = btleBundle.state_;

                Log.w("Main", "Bundle restored from fragment, state is " + String.valueOf(state));
            }
        }


        Intent inti = getIntent();
        if((inti.getAction().equals("RFduinoTest_CallToMain")) || (serviceInForeground))
        {
            Log.w("Main", "Return from notifictation");
            Intent stopForegroundIntent = new Intent(getApplicationContext(), RFduinoService.class);
            stopForegroundIntent.setAction("RFduinoService_StopForeground");
            getApplicationContext().startService(stopForegroundIntent);
            serviceInForeground = false;
            // Saving to sharedPreferences that the service is running in foreground now
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putBoolean("foregroundServiceRunning", serviceInForeground);
            editor.commit();
            fromNotification = true;
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // rebind to service if it currently isn't
        if(!serviceBound) {
            rfduinoServiceConnection = genServiceConnection();
        }

        if(fromNotification) {
            Intent rfduinoIntent = new Intent(getApplicationContext(), RFduinoService.class);
            getApplicationContext().bindService(rfduinoIntent, rfduinoServiceConnection, BIND_AUTO_CREATE);
        }

        // Bluetooth
        enableBluetoothButton = (Button) findViewById(R.id.enableBluetooth);
        enableBluetoothButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableBluetoothButton.setEnabled(false);
                enableBluetoothButton.setText(
                        bluetoothAdapter.enable() ? "Enabling bluetooth..." : "Enable failed!");
            }
        });

        // Find Device
        scanStatusText = (TextView) findViewById(R.id.scanStatus);

        scanButton = (Button) findViewById(R.id.scan);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanStarted = true;
                bluetoothAdapter.startLeScan(
                        new UUID[]{ RFduinoService.UUID_SERVICE },
                        MainActivity.this);
            }
        });

        // Device Info
        deviceInfoText = (TextView) findViewById(R.id.deviceInfo);

        // Connect Device
        connectionStatusText = (TextView) findViewById(R.id.connectionStatus);

        connectButton = (Button) findViewById(R.id.connect);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.setEnabled(false);
                connectionStatusText.setText("Connecting...");
                // if device was rotated we need to set up a new service connection with this activity
                if (connectionIsOld) {
                    Log.w("Main", "Rebuilding connection after rotation");
                    connectionIsOld = false;
                    rfduinoServiceConnection = genServiceConnection();
                }
                if (serviceBound) {
                    if (rfduinoService.initialize()) {
                        if (rfduinoService.connect(bluetoothDevice.getAddress())) {
                            upgradeState(STATE_CONNECTING);
                        }
                    }
                } else {
                    Intent rfduinoIntent = new Intent(getApplicationContext(), RFduinoService.class);
                    getApplicationContext().bindService(rfduinoIntent, rfduinoServiceConnection, BIND_AUTO_CREATE);
                }
            }
        });

        // Disconnect Device
        disconnectButton = (Button) findViewById(R.id.disconnect);
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.setEnabled(false);

                disconnect();
            }
        });



        // refresh the ui if a restored fragment was found
        if (dataFragment != null) {
            updateUi();
        }

        dynamicPlot = (XYPlot) findViewById(R.id.dynamicXYPlot);

        MyPlotUpdater plotUpdater = new MyPlotUpdater(dynamicPlot);

        // getInstance and position datasets:
        dataChangeSignal = new MyObservable();
        series = new DynamicSeries();

        LineAndPointFormatter formatter1 = new LineAndPointFormatter(
                Color.rgb(0, 0, 0), null, null, null);
        formatter1.getLinePaint().setStrokeJoin(Paint.Join.ROUND);
        formatter1.getLinePaint().setStrokeWidth(10);
        dynamicPlot.addSeries(series, formatter1);

        dataChangeSignal.addObserver(plotUpdater);


        dynamicPlot.getGraphWidget().getDomainLabelPaint().setColor(Color.TRANSPARENT);
        dynamicPlot.getGraphWidget().getRangeLabelPaint().setColor(Color.TRANSPARENT);

        // create a dash effect for domain and range grid lines:
        DashPathEffect dashFx = new DashPathEffect(
                new float[]{PixelUtils.dpToPix(3), PixelUtils.dpToPix(3)}, 0);
        dynamicPlot.getGraphWidget().getDomainGridLinePaint().setPathEffect(dashFx);
        dynamicPlot.getGraphWidget().getRangeGridLinePaint().setPathEffect(dashFx);

        dynamicPlot.getLayoutManager().remove(dynamicPlot.getDomainLabelWidget());
        dynamicPlot.getLayoutManager().remove(dynamicPlot.getLegendWidget());

        dynamicPlot.setRangeBoundaries(0,127, BoundaryMode.FIXED);
        dynamicPlot.setDomainBoundaries(0, series.size()/2, BoundaryMode.FIXED);


    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.w("Main", "onStart called");
        registerReceiver(scanModeReceiver, new IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED));
        registerReceiver(bluetoothStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        registerReceiver(rfduinoReceiver, RFduinoService.getIntentFilter());

        if(state <= STATE_DISCONNECTED) {
            updateState(bluetoothAdapter.isEnabled() ? STATE_DISCONNECTED : STATE_BLUETOOTH_OFF);
        }

    }

    @Override
    protected void onStop() {
        super.onStop();

        bluetoothAdapter.stopLeScan(this);

        unregisterReceiver(scanModeReceiver);
        unregisterReceiver(bluetoothStateReceiver);
        unregisterReceiver(rfduinoReceiver);
    }

    @Override
    protected  void onDestroy()
    {
        if(isFinishing() && serviceBound)
        {
            // shut down service if background action is not wanted
            //if(!backgroundService) {
            if(!true) {
                Log.w("Main", "Service is unbound");
                //Intent stopBackgroundIntent = new Intent(getApplicationContext(), RFduinoService.class);
                //stopBackgroundIntent.setAction("RFduinoService_Stop");
                getApplicationContext().unbindService(rfduinoServiceConnection);
                //getApplicationContext().stopService(stopBackgroundIntent);
            }
            else {
                // store the data in the fragment
                BTLEBundle btleBundle = new BTLEBundle();
                btleBundle.device = bluetoothDevice;
                btleBundle.state_ = state;
                btleBundle.isBound = serviceBound;
                btleBundle.scanStarted = scanStarted;
                btleBundle.scanning = scanning;
                if(serviceBound) {
                    // only save the connection if there is one
                    btleBundle.connection = rfduinoServiceConnection;
                    btleBundle.service = rfduinoService;
                }
                if(rfduinoService != null) {
                    Log.w("Main","Bundle saved to service");
                    rfduinoService.setData(btleBundle);
                }
                // Saving to sharedPreferences that the service is running in foreground now
                SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(
                        getString(R.string.preference_file_key), Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();
                editor.putBoolean("foregroundServiceRunning", true);
                editor.commit();

                Log.w("Main","Service pushed into foreground");
                Intent startBackgroundIntent = new Intent(getApplicationContext(), RFduinoService.class);
                startBackgroundIntent.setAction("RFduinoService_StartForeground");
                getApplicationContext().startService(startBackgroundIntent);

                if(rfduinoServiceConnection != null) {
                    getApplicationContext().unbindService(rfduinoServiceConnection);
                }
            }
        }

        // rotating behaviour is handled below
        else if(!isFinishing()) {
            // store the data in the fragment
            BTLEBundle btleBundle = new BTLEBundle();
            btleBundle.device = bluetoothDevice;
            btleBundle.state_ = state;
            btleBundle.isBound = serviceBound;
            btleBundle.scanStarted = scanStarted;
            btleBundle.scanning = scanning;
            if(serviceBound) {
                // only save the connection if there is one
                btleBundle.connection = rfduinoServiceConnection;
                btleBundle.service = rfduinoService;
            }

            if(dataFragment != null) {
                Log.w("Main","Bundle saved to fragment");
                dataFragment.setData(btleBundle);
            }
        }

        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    private void updateUi() {
        // Enable Bluetooth
        boolean on = state > STATE_BLUETOOTH_OFF;
        enableBluetoothButton.setEnabled(!on);
        enableBluetoothButton.setText(on ? "Bluetooth enabled" : "Enable Bluetooth");
        scanButton.setEnabled(on);

        // Scan
        if (scanStarted && scanning) {
            scanStatusText.setText("Scanning...");
            scanButton.setText("Stop Scan");
            scanButton.setEnabled(true);
        } else if (scanStarted) {
            scanStatusText.setText("Scan started...");
            scanButton.setEnabled(false);
        } else {
            scanStatusText.setText("");
            scanButton.setText("Scan");
            scanButton.setEnabled(true);
        }

        // Connect
        String connectionText = "Disconnected";
        if (state == STATE_CONNECTING) {
            connectionText = "Connecting...";
        } else if (state == STATE_CONNECTED) {
            connectionText = "Connected";
        }
        connectionStatusText.setText(connectionText);
        connectButton.setEnabled(bluetoothDevice != null && state == STATE_DISCONNECTED);
        disconnectButton.setEnabled(bluetoothDevice != null && state == STATE_CONNECTED);

        Log.w("Main","Updated UI to state " + state);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private ServiceConnection genServiceConnection() {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                serviceBound = true;
                rfduinoService = ((RFduinoService.LocalBinder) service).getService();
                Log.w("Main","onServiceConnected called, service = "+ rfduinoService.toString());
                if(fromNotification) {
                    BTLEBundle bundle = rfduinoService.restoreData();
                    if(bundle != null) {
                        state = bundle.state_;
                        bluetoothDevice = bundle.device;
                        scanStarted = bundle.scanStarted;
                        scanning = bundle.scanning;
                        Log.w("Main","State restored from service, state: "+ state);
                    }
                    Log.w("Main","Stopping service before unbinding");
                    Intent stopIntent = new Intent(getApplicationContext(),RFduinoService.class);
                    getApplicationContext().stopService(stopIntent);
                    fromNotification = false;
                    if(state<STATE_CONNECTED) {
                        disconnect();
                    }
                    updateUi();
                }
                else{
                    if (rfduinoService.initialize()) {
                        if (rfduinoService.connect(bluetoothDevice.getAddress())) {
                            upgradeState(STATE_CONNECTING);
                        }
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w("Main","onServiceDisconnected called");
                rfduinoService = null;
                downgradeState(STATE_DISCONNECTED);
            }
        };
    }

    //My method to update the graph
    private void addData(byte[] data) {
        float newFloat = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        series.addValue(newFloat);
        dataChangeSignal.notifyObservers();
        dynamicPlot.setDomainBoundaries(0, series.size()/2, BoundaryMode.FIXED);
    }

    private void disconnect(){
        Log.w("Main","Calling disconnect");
        if(rfduinoService != null) {
            rfduinoService.disconnect();
            rfduinoService = null;
        }
        else {Log.w("Main","Service empty");}
        if(rfduinoServiceConnection != null) {
            getApplicationContext().unbindService(rfduinoServiceConnection);
            serviceBound = false;
        }
        else{ Log.w("Main","ServiceConnection empty");}
    }

    private void upgradeState(int newState) {
        if (newState > state) {
            updateState(newState);
        }
    }

    private void downgradeState(int newState) {
        if (newState < state) {
            updateState(newState);
        }
    }

    private void updateState(int newState) {
        state = newState;
        updateUi();
    }

    // redraws a plot whenever an update is received:
    private class MyPlotUpdater implements Observer {
        Plot plot;

        public MyPlotUpdater(Plot plot) {
            this.plot = plot;
        }

        @Override
        public void update(Observable o, Object arg) {
            plot.redraw();
        }
    }

    class DynamicSeries implements XYSeries {
        private LinkedList<Float> data =  new LinkedList<>();
        private String title;
        private Float runningAverage = 0f;


        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public int size() {
            return data.size();
        }

        @Override
        public Number getX(int index) {
            return index;
        }

        @Override
        public Number getY(int index) {
            return data.get(index);
        }


        public void addValue(Float newValue) {
            if (data.size() == MAX_SERIES_SIZE) {
                data.pop();
            }
            data.add(newValue);
        }

        public float getBPM() {
            float min = data.getFirst();
            float max = data.getFirst();
            float median;

            for(int i=0; i< data.size(); i++) {
                float current = data.get(i);
                if(current > max) {
                    max = current;
                }
                if(current < min) {
                    min = current;
                }
            }

            median = ((max - min) / 2 )+ min ;

            Log.d("getBPM", "max " + max + " min " + min + " median " + median);

            float prev = 0;
            float beats =0 ;
            int prevIndex = 0;
            for(int i=0; i< data.size(); i++) {
                float current = data.get(i);
                if (prev > median && current < median) {
                    if(prevIndex != 0 ) {
                        Log.d("getBPM", "interval between beats " + (i - prevIndex));

                    }
                    prevIndex = i;
                    beats++;
                }
                prev = current;
            }

            //For some reason my window is 9 seconds long.  That doesn't make sense but I measured
            //it several times with a stopwatch and that's what it is
            float currentBPM = beats * 60/9;
            Log.d("getBPM", "rounded " + currentBPM + " running " + runningAverage);
            if(runningAverage == 0 )
                runningAverage = currentBPM;
            if ((Math.abs(runningAverage - currentBPM) > runningAverage * .2f) ||
                    (currentBPM > 200  || currentBPM < 50)) {
                runningAverage =  runningAverage * .9f + currentBPM * .1f;
            } else {
                runningAverage = runningAverage * .6f + currentBPM * .4f;
            }
            return Math.round(runningAverage);
        }


    }

    class MyObservable extends Observable {
        @Override
        public void notifyObservers() {
            setChanged();
            super.notifyObservers();
        }
    }
}
