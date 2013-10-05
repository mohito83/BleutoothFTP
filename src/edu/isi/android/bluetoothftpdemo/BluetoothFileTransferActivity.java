package edu.isi.android.bluetoothftpdemo;

import java.util.Set;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

public class BluetoothFileTransferActivity extends Activity {

	// for debugging purpose
	private static final String TAG = "BluetoothFileTransferActivity";

	private static final int REQUEST_ENABLE_BT = 3;
	private Button discoverBT;

	private BluetoothAdapter mBluetoothAdapter = null;
	private Set<BluetoothDevice> pairedDevices = null;

	private ConnectionService connService = null;

	// private ArrayAdapter<BluetoothDevice> pairedDevArrAdapter = new
	// ArrayAdapter<BluetoothDevice>(this, textViewResourceId);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_bluetooth_file_transfer);

		Log.d(TAG, "Starting main activity!!");

		/*
		 * create an Broadcast register and register the event that you are
		 * interested in
		 */
		// Register for broadcasts when a device is discovered
		IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
		this.registerReceiver(mReceiver, filter);

		// Register for broadcasts when discovery has finished
		filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		this.registerReceiver(mReceiver, filter);

		/* To check if the device has the bluetooth hardware */

		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mBluetoothAdapter == null) {
			Log.e(TAG,
					"No bluetooth capabilities available on the device. Exiting!!");
			Toast.makeText(this, "Bluetooth is not available",
					Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		discoverBT = (Button) findViewById(R.id.discoverbutton);
		discoverBT.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				/*
				 * Start searching for the devices
				 */
				searchForBTDevices();
			}

		});

	}

	/**
	 * This method searches for the BT devices
	 */
	private void searchForBTDevices() {
		Log.d(TAG, "Start searching the devices");
		// 1. get paired device list
		pairedDevices = mBluetoothAdapter.getBondedDevices();
		if (pairedDevices != null && pairedDevices.size() > 0) {
			for (BluetoothDevice dev : pairedDevices) {
				Log.i(TAG, "PEERED DEVICES::  Device name " + dev.getName()
						+ " and address " + dev.getAddress());
			}
		}

		// 2. discover non-paired devices
		// mBluetoothAdapter.startDiscovery();

		if (connService != null) {
			connectToDeviceAsMaster();
		}

	}

	@Override
	public void onStart() {
		super.onStart();
		Log.d(TAG, "++ ON START ++");

		/*
		 * If device has bluetooth harware but it is disabled currently, then
		 * prompt user to switch on the bluetooth manually
		 */

		if (!mBluetoothAdapter.isEnabled()) {
			/*
			 * Intent enableBtIntent = new Intent(
			 * BluetoothAdapter.ACTION_REQUEST_ENABLE);
			 * startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
			 */

			// make your device discoverable
			Intent makeDiscoverable = new Intent(
					BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			makeDiscoverable.putExtra(
					BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			startActivityForResult(makeDiscoverable, REQUEST_ENABLE_BT);

		} else {
			Log.d(TAG,
					"Bluetooth is already enabled. Setting up the file transfer");
			startServerThread();
		}

	}

	@Override
	public void onResume() {
		super.onResume();
		Log.i(TAG, "+ ON RESUME +");

		// Performing this check in onResume() covers the case in which BT was
		// not enabled during onStart(), so we were paused to enable it...
		// onResume() will be called when ACTION_REQUEST_ENABLE activity
		// returns.
		if (connService != null) {
			// Only if the state is STATE_NONE, do we know that we haven't
			// started already
			if (connService.getState() == ConnectionService.STATE_NONE) {
				// Start the Bluetooth chat services
				connService.start();
			}
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		// Make sure we're not doing discovery anymore
		if (mBluetoothAdapter != null) {
			mBluetoothAdapter.cancelDiscovery();
		}

		// Unregister broadcast listeners
		this.unregisterReceiver(mReceiver);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.bluetooth_file_transfer, menu);
		return true;
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case REQUEST_ENABLE_BT:
			if (resultCode == 300) {
				Log.i(TAG,
						"Bluetooth is enabled and discoverable on this device.");
				Log.i(TAG, "Setting up the connections");
				startServerThread();
			} else {
				Log.d(TAG, "User cancels the bluetooth activation. Exiting!!");
				// finish();
			}
			break;

		default:
			break;
		}
	}

	// The BroadcastReceiver that listens for discovered devices and
	// changes the title when discovery is finished
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			// When discovery finds a device
			if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent
						.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				// If it's already paired, skip it, because it's been listed
				// already
				if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
					// TODO :mNewDevicesArrayAdapter.add(device.getName() + "\n"
					// + device.getAddress());
					Log.i(TAG,
							"Device Discovery:: Device name "
									+ device.getName() + " and address "
									+ device.getAddress());

				}
				// When discovery is finished, change the Activity title
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED
					.equals(action)) {
				setProgressBarIndeterminateVisibility(false);
				/*
				 * setTitle(R.string.select_device); if
				 * (mNewDevicesArrayAdapter.getCount() == 0) { String noDevices
				 * = getResources().getText(R.string.none_found).toString();
				 * mNewDevicesArrayAdapter.add(noDevices); }
				 */
			}
		}
	};

	/**
	 * This method setup the connections for socket communication
	 */
	private void startServerThread() {
		if (connService == null)
			connService = new ConnectionService(this, mBluetoothAdapter);
		connService.start();

		/*
		 * // send data to the first device in the paired devices list if
		 * (pairedDevices != null && !pairedDevices.isEmpty()) { BluetoothDevice
		 * device = pairedDevices.iterator().next();
		 * 
		 * if (device != null) { connService.connect(device); } }
		 */
	}

	private void connectToDeviceAsMaster() {
		// send data to the first device in the paired devices list
		if (pairedDevices != null && !pairedDevices.isEmpty()) {
			BluetoothDevice device = pairedDevices.iterator().next();

			if (device != null) {
				Toast.makeText(
						this,
						"Connecting to device: " + device.getName() + "/"
								+ device.getAddress(), Toast.LENGTH_LONG)
						.show();
				connService.connect(device);
			}
		}
	}

}
