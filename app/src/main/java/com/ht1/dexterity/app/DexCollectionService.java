/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ht1.dexterity.app;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

//import com.eveningoutpost.dexdrip.Models.ActiveBluetoothDevice;
//import com.eveningoutpost.dexdrip.Models.BgReading;
//import com.eveningoutpost.dexdrip.Sensor;
//import com.eveningoutpost.dexdrip.UtilityModels.CollectionServiceStarter;
//import com.eveningoutpost.dexdrip.UtilityModels.ForegroundServiceStarter;
//import com.eveningoutpost.dexdrip.UtilityModels.HM10Attributes;
//import com.eveningoutpost.dexdrip.Models.TransmitterData;

import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;
//import static com.activeandroid.ActiveAndroid.beginTransaction;
//import static com.activeandroid.ActiveAndroid.endTransaction;
//import static com.activeandroid.ActiveAndroid.setTransactionSuccessful;
//import com.activeandroid.query.Select;


class ActiveBluetoothDevice {
	private final static String TAG = DexCollectionService.class.getSimpleName();
	
	ActiveBluetoothDevice(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		address = prefs.getString("bt_device", "none");
	}
	
	static ActiveBluetoothDevice first(Context context) {
		return new ActiveBluetoothDevice(context);
	}
	
	static void connected(Context context) {
		Log.e(TAG, "BT device Connected");
		context.sendBroadcast(new Intent("BT_CONNECT"));
		
	}
	static void disconnected(Context context) {
		Log.e(TAG, "BT device DisConnected");
		context.sendBroadcast(new Intent("BT_DISCONNECT"));
		
	}
	boolean IsConfigured() {
		return (!address.equals("none"));
	}
	public String address;
}


class CollectionServiceStarter {
	static boolean  isBTWixel(Context context) {
		return true;
	}
	static boolean  isDexbridgeWixel(Context context) {
		return false;
	}

}


class ForegroundServiceStarter {


    public ForegroundServiceStarter(Context context, Service service) {
    }

    public void start() {
    }

    public void stop() {
    }

}


@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class DexCollectionService extends Service {
    private final static String TAG = DexCollectionService.class.getSimpleName();
    
    
    public static String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";
    public static String HM_10_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb";
    public static String HM_RX_TX = "0000ffe1-0000-1000-8000-00805f9b34fb";
    
    private String mDeviceAddress;
    SharedPreferences prefs;

    public DexCollectionService dexCollectionService;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;
    private ForegroundServiceStarter foregroundServiceStarter;
    private int mConnectionState = BluetoothProfile.STATE_DISCONNECTING;
    private BluetoothDevice device;
    private BluetoothGattCharacteristic mCharacteristic;
    long lastPacketTime;
    private byte[] lastdata = null;
    private Context mContext;
    private final int STATE_DISCONNECTED = BluetoothProfile.STATE_DISCONNECTED;
    private final int STATE_DISCONNECTING = BluetoothProfile.STATE_DISCONNECTING;
    private final int STATE_CONNECTING = BluetoothProfile.STATE_CONNECTING;
    private final int STATE_CONNECTED = BluetoothProfile.STATE_CONNECTED;

    public final UUID xDripDataService = UUID.fromString(HM_10_SERVICE);
    public final UUID xDripDataCharacteristic = UUID.fromString(HM_RX_TX);

    
    static public String  getConnectionStatus(Context context) {
    	ActiveBluetoothDevice activeBluetoothDevice = ActiveBluetoothDevice.first(context);
    	BluetoothManager BluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        boolean connected = false;
        if (BluetoothManager != null && activeBluetoothDevice != null) {
            for (BluetoothDevice bluetoothDevice : BluetoothManager.getConnectedDevices(BluetoothProfile.GATT)) {
                if (bluetoothDevice.getAddress().compareTo(activeBluetoothDevice.address) == 0) {
                    connected = true;
                }
            }
        }
        if(connected) {
            return "BLuetooth Connected";
        } else {
        	return "BLuetooth Disconnected";
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        foregroundServiceStarter = new ForegroundServiceStarter(getApplicationContext(), this);
        foregroundServiceStarter.start();
        mContext = getApplicationContext();
        dexCollectionService = this;
        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        listenForChangeInSettings();
        Log.w(TAG, "onCreate: STARTING SERVICE");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
    	Log.d(TAG, "onStartCommand: entering Connection state: " + mConnectionState);
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2){
            stopSelf();
            return START_NOT_STICKY;
        }
        if (CollectionServiceStarter.isBTWixel(getApplicationContext()) || CollectionServiceStarter.isDexbridgeWixel(getApplicationContext())) {
            setFailoverTimer();
        } else {
            stopSelf();
            return START_NOT_STICKY;
        }
        ActiveBluetoothDevice btDevice = ActiveBluetoothDevice.first(getApplicationContext());
        if (btDevice.IsConfigured()) {
        	attemptConnection();
        } else {
        	close();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "onDestroy entered");
        close();
        foregroundServiceStarter.stop();
        setRetryTimer();
        Log.w(TAG, "SERVICE STOPPED");
    }

    public SharedPreferences.OnSharedPreferenceChangeListener prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        	Log.e(TAG,"Settings have changed");
            if (key.compareTo("run_service_in_foreground") == 0) {
                Log.e("FOREGROUND", "run_service_in_foreground changed!");
                if (prefs.getBoolean("run_service_in_foreground", false)) {
                    foregroundServiceStarter = new ForegroundServiceStarter(getApplicationContext(), dexCollectionService);
                    foregroundServiceStarter.start();
                    Log.w(TAG, "Moving to foreground");
                } else {
                    dexCollectionService.stopForeground(true);
                    Log.w(TAG, "Removing from foreground");
                }
            }
            setRetryTimer(1);
        }
        
    };

    public void listenForChangeInSettings() {
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }
    public void setRetryTimer() {
    	setRetryTimer(1000 * 65);
    }

    public void setRetryTimer(long retry_in) {
        if (CollectionServiceStarter.isBTWixel(getApplicationContext()) || CollectionServiceStarter.isDexbridgeWixel(getApplicationContext())) {
            Log.d(TAG, "setRetryTimer: Restarting in: " + (retry_in/1000)  + " seconds");
            Calendar calendar = Calendar.getInstance();
            AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                alarm.setExact(alarm.RTC_WAKEUP, calendar.getTimeInMillis() + retry_in, PendingIntent.getService(this, 0, new Intent(this, DexCollectionService.class), 0));
            } else {
                alarm.set(alarm.RTC_WAKEUP, calendar.getTimeInMillis() + retry_in, PendingIntent.getService(this, 0, new Intent(this, DexCollectionService.class), 0));
            }
        }
    }

    public void setFailoverTimer() {
        if (CollectionServiceStarter.isBTWixel(getApplicationContext())|| CollectionServiceStarter.isDexbridgeWixel(getApplicationContext())) {
            long retry_in = (1000 * 60 * 6);
            Log.d(TAG, "setFailoverTimer: Fallover Restarting in: " + (retry_in / (60 * 1000)) + " minutes");
            Calendar calendar = Calendar.getInstance();
            AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                alarm.setExact(alarm.RTC_WAKEUP, calendar.getTimeInMillis() + retry_in, PendingIntent.getService(this, 0, new Intent(this, DexCollectionService.class), 0));
            } else {
                alarm.set(alarm.RTC_WAKEUP, calendar.getTimeInMillis() + retry_in, PendingIntent.getService(this, 0, new Intent(this, DexCollectionService.class), 0));
            }
        } else {
            stopSelf();
        }
    }

    public void attemptConnection() {
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager != null) {
            mBluetoothAdapter = mBluetoothManager.getAdapter();
            if (mBluetoothAdapter != null) {
                if (device != null) {
                    mConnectionState = STATE_DISCONNECTED;
                    for (BluetoothDevice bluetoothDevice : mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT)) {
                        if (bluetoothDevice.getAddress().compareTo(device.getAddress()) == 0) {
                            mConnectionState = STATE_CONNECTED;
                        }
                    }
                }

                Log.w(TAG, "attemptConnection: Connection state: " + mConnectionState);
                if (mConnectionState == STATE_DISCONNECTED || mConnectionState == STATE_DISCONNECTING) {
                	Log.w(TAG, "inside if .........." + STATE_DISCONNECTED + " " + STATE_DISCONNECTING);
                    ActiveBluetoothDevice btDevice = ActiveBluetoothDevice.first(getApplicationContext());
                    if (btDevice != null) {
                        mDeviceAddress = btDevice.address;
                        Log.w(TAG, "Device Addresses is " + mDeviceAddress);
                        if (mBluetoothAdapter.isEnabled() && mBluetoothAdapter.getRemoteDevice(mDeviceAddress) != null) {
                            connect(mDeviceAddress);
                            return;
                        }
                    }
                } else if (mConnectionState == STATE_CONNECTED) { //WOOO, we are good to go, nothing to do here!
                    Log.w(TAG, "attemptConnection: Looks like we are already connected, going to read!");
                    return;
                }
            }
        }
        setRetryTimer();
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                ActiveBluetoothDevice.connected(mContext);
                Log.w(TAG, "onConnectionStateChange: Connected to GATT server.");
                mBluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                lastdata = null;
                ActiveBluetoothDevice.disconnected(mContext);
                Log.w(TAG, "onConnectionStateChange: Disconnected from GATT server.");
                setRetryTimer();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService gattService = mBluetoothGatt.getService(xDripDataService);
                if (gattService != null) {
                    BluetoothGattCharacteristic gattCharacteristic = gattService.getCharacteristic(xDripDataCharacteristic);
                    if (gattCharacteristic != null) {
                        mCharacteristic = gattCharacteristic;
                        final int charaProp = gattCharacteristic.getProperties();
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            mBluetoothGatt.setCharacteristicNotification(gattCharacteristic, true);
                        } else {
                            Log.w(TAG, "onServicesDiscovered: characteristic " + xDripDataCharacteristic + " not found");
                        }
                    } else {
                        Log.w(TAG, "onServicesDiscovered: service " + xDripDataService + " not found");
                    }
                    Log.d(TAG, "onServicesDiscovered received success: " + status);
                }
            } else {
                Log.d(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        	Log.w(TAG, "onCharacteristicChanged entered");
            PowerManager powerManager = (PowerManager) mContext.getSystemService(mContext.POWER_SERVICE);
            PowerManager.WakeLock wakeLock1 = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "DexCollectionService");
            wakeLock1.acquire(8000);
            try {
                Log.w(TAG, "onCharacteristicChanged entered");
                final byte[] data = characteristic.getValue();
                if (lastdata != null && data != null && data.length > 0) {
                    if (!Arrays.equals(lastdata, data)) {
                        Log.v(TAG, "broadcastUpdate: new data.");
                        setSerialDataToTransmitterRawData(data, data.length);
                    } else if (Arrays.equals(lastdata, data)) {
                        Log.v(TAG, "broadcastUpdate: duplicate data, ignoring");
                    }
                } else if (data != null && data.length > 0) {
                    setSerialDataToTransmitterRawData(data, data.length);
                }
                lastdata = data;
            } finally {
                wakeLock1.release();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onDescriptorWrite: Wrote GATT Descriptor successfully.");
            } else {
                Log.d(TAG, "onDescriptorWrite: Error writing GATT Descriptor: " + status);
            }
        }
    };

    private boolean sendBtMessage(final ByteBuffer message) {
        //check mBluetoothGatt is available
        Log.w(TAG, "sendBtMessage: entered");
        if (mBluetoothGatt == null) {
            Log.e(TAG, "sendBtMessage: lost connection");
            return false;
        }

        byte[] value = message.array();
        Log.w(TAG, "sendBtMessage: sending message");
        mCharacteristic.setValue(value);

        return mBluetoothGatt.writeCharacteristic(mCharacteristic);
    }

    private Integer convertSrc(final String Src) {
        Integer res = 0;
        res |= getSrcValue(Src.charAt(0)) << 20;
        res |= getSrcValue(Src.charAt(1)) << 15;
        res |= getSrcValue(Src.charAt(2)) << 10;
        res |= getSrcValue(Src.charAt(3)) << 5;
        res |= getSrcValue(Src.charAt(4));
        return res;
    }

    private int getSrcValue(char ch) {
        int i;
        char[] cTable = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'W', 'X', 'Y'};
        for (i = 0; i < cTable.length; i++) {
            if (cTable[i] == ch) break;
        }
        return i;
    }

    public boolean connect(final String address) {
        Log.w(TAG, "connect: going to connect to device at address" + address);
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "connect: BluetoothAdapter not initialized or unspecified address.");
            setRetryTimer();
            return false;
        }
        if (mBluetoothGatt != null) {
            Log.w(TAG, "connect: mBluetoothGatt isnt null, Closing.");
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
        device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            setRetryTimer();
            return false;
        }
        Log.w(TAG, "connect: Trying to create a new connection.");
        mBluetoothGatt = device.connectGatt(getApplicationContext(), true, mGattCallback);
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    public void close() {
        Log.w(TAG, "close: Closing Connection");
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        setRetryTimer();
        mBluetoothGatt = null;
        mCharacteristic = null;
        mConnectionState = STATE_DISCONNECTED;
    }
    
    public void setSerialDataToTransmitterRawData(byte[] buffer, int len) {
    	 StringBuilder data_string = new StringBuilder();
         for (int i = 0; i < len; ++i) { 
        	 data_string.append((char) buffer[i]); 
        }
         Log.w(TAG, "Just recieved a packet !!! size " + len + " " + data_string.toString());
         String[] data = data_string.toString().split("\\s+");
         
         int raw_data = Integer.parseInt(data[0]);
         int sensor_battery_level = Integer.parseInt(data[1]);
         int bridgeBattery = 100 * Integer.parseInt(data[2]);
         int id = 0;
         int filtered = 0;
         if(data.length >= 4) {
        	 String sid = data[3].substring(0,2);
        	 String sfiltered = data[3].substring(2,5);
        	 id = Integer.parseInt(sid);
        	 filtered = 1000 * Integer.parseInt(sfiltered);
         }
         SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
         String transmitter_id = preferences.getString("transmitter_id", "6ABW4");

         Log.w(TAG, "Creating a packet: "+ transmitter_id +" " + id + " " +  raw_data+ " " +  filtered+ " " +  sensor_battery_level+ " " +  bridgeBattery);
         TransmitterRawData trd = new TransmitterRawData(getApplicationContext(), transmitter_id, id, raw_data, filtered, sensor_battery_level, bridgeBattery);
         SerialPortReader.setSerialDataToTransmitterRawData(getApplicationContext(), trd);
    }
/*
    public void setSerialDataToTransmitterRawData(byte[] buffer, int len) {
        long timestamp = new Date().getTime();
        if (CollectionServiceStarter.isDexbridgeWixel(getApplicationContext())) {
            Log.w(TAG, "setSerialDataToTransmitterRawData: Dealing with Dexbridge packet!");
            int DexSrc;
            int TransmitterID;
            String TxId;
            Calendar c = Calendar.getInstance();
            long secondsNow = c.getTimeInMillis();
            ByteBuffer tmpBuffer = ByteBuffer.allocate(len);
            tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
            tmpBuffer.put(buffer, 0, len);
            ByteBuffer txidMessage = ByteBuffer.allocate(6);
            txidMessage.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer[0] == 0x07 && buffer[1] == -15) {
                //We have a Beacon packet.  Get the TXID value and compare with dex_txid
                Log.w(TAG, "setSerialDataToTransmitterRawData: Received Beacon packet.");
                //DexSrc starts at Byte 2 of a Beacon packet.
                DexSrc = tmpBuffer.getInt(2);
                TxId = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("dex_txid", "00000");
                TransmitterID = convertSrc(TxId);
                if (TxId.compareTo("00000") != 0 && Integer.compare(DexSrc, TransmitterID) != 0) {
                    Log.w(TAG, "setSerialDataToTransmitterRawData: TXID wrong.  Expected " + TransmitterID + " but got " + DexSrc);
                    txidMessage.put(0, (byte) 0x06);
                    txidMessage.put(1, (byte) 0x01);
                    txidMessage.putInt(2, TransmitterID);
                    sendBtMessage(txidMessage);
                }
                return;
            }
            if (buffer[0] == 0x11 && buffer[1] == 0x00) {
                //we have a data packet.  Check to see if the TXID is what we are expecting.
                Log.w(TAG, "setSerialDataToTransmitterRawData: Received Data packet");
                //make sure we are not processing a packet we already have
                if (secondsNow - lastPacketTime < 60000) {
                    Log.v(TAG, "setSerialDataToTransmitterRawData: Received Duplicate Packet.  Exiting.");
                    return;
                } else {
                    lastPacketTime = secondsNow;
                }
                if (len >= 0x11) {
                    //DexSrc starts at Byte 12 of a data packet.
                    DexSrc = tmpBuffer.getInt(12);
                    TxId = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("dex_txid", "00000");
                    TransmitterID = convertSrc(TxId);
                    if (Integer.compare(DexSrc, TransmitterID) != 0) {
                        Log.w(TAG, "TXID wrong.  Expected " + TransmitterID + " but got " + DexSrc);
                        txidMessage.put(0, (byte) 0x06);
                        txidMessage.put(1, (byte) 0x01);
                        txidMessage.putInt(2, TransmitterID);
                        sendBtMessage(txidMessage);
                    }
                    PreferenceManager.getDefaultSharedPreferences(mContext).edit().putInt("bridge_battery", ByteBuffer.wrap(buffer).get(11)).apply();
                    //All is OK, so process it.
                    //first, tell the wixel it is OK to sleep.
                    Log.d(TAG, "setSerialDataToTransmitterRawData: Sending Data packet Ack, to put wixel to sleep");
                    ByteBuffer ackMessage = ByteBuffer.allocate(2);
                    ackMessage.put(0, (byte) 0x02);
                    ackMessage.put(1, (byte) 0xF0);
                    sendBtMessage(ackMessage);
                    Log.v(TAG, "setSerialDataToTransmitterRawData: Creating TransmitterData at " + timestamp);
                    ProcessNewTransmitterData(TransmitterData.create(buffer, len, timestamp), timestamp);
                }
            }
        } else {
            ProcessNewTransmitterData(TransmitterData.create(buffer, len, timestamp), timestamp);
        }
    }

    private void ProcessNewTransmitterData(TransmitterData transmitterData, long timestamp) {
        if (transmitterData != null) {
            Sensor sensor = Sensor.currentSensor();
            if (sensor != null) {
                sensor.latest_battery_level = transmitterData.sensor_battery_level;
                sensor.save();

                BgReading.create(transmitterData.raw_data, transmitterData.filtered_data, this, timestamp);
            } else {
                Log.w(TAG, "setSerialDataToTransmitterRawData: No Active Sensor, Data only stored in Transmitter Data");
            }
        }
    }
    */
}
