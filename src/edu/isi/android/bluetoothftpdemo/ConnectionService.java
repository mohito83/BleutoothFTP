/**
 * 
 */
package edu.isi.android.bluetoothftpdemo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for incoming
 * connections, a thread for connecting with a device, and a thread for
 * performing data transmissions when connected.
 * 
 * @author mohit aggarwal
 * 
 */
public class ConnectionService {

	private static final String TAG = "ConnectionService";

	// Name for the SDP record when creating server socket
	private static final String FTP_SERVICE = "CustomFTPService";
	private static final UUID MY_UUID = UUID
			.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
	private static final String APP_CONTENT_FOLDER_NAME = "PiFiContent";
	private static final String META_DATA_FILE_NAME = "videos.dat";

	private Context context;
	private BluetoothAdapter mAdapter;
	private AcceptThread mAcceptThread;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	private int mState;
	private boolean isExtDrMounted;

	private byte buffer[] = new byte[Short.MAX_VALUE];

	private final File path, metaFile;

	// Constants that indicate the current connection state
	public static final int STATE_NONE = 0; // we're doing nothing
	public static final int STATE_LISTEN = 1; // now listening for incoming
												// connections
	public static final int STATE_CONNECTING = 2; // now initiating an outgoing
													// connection
	public static final int STATE_CONNECTED = 3; // now connected to a remote
													// device

	
	/**
	 *  For control messages during data communication over sockets 
	 */
	private int dataState = NO_DATA_META;
	
	/*
	 * Flag for no data transfer
	 */
	private static final int NO_DATA_META=0;
	/*
	 * Flag for identifying that data is from master
	 */
	private static final int DATA_FROM_MASTER=1000;
	/*
	 * Flag for identifying data to master
	 */
	private static final int DATA_TO_MASTER=1001;
	/*
	 * Flag for identifying meta data from master
	 */
	private static final int META_FROM_MASTER=1002;
	/*
	 * Flag for identifying meta data to master
	 */
	private static final int META_TO_MASTER=1003;
	
	
	/**
	 * Consturctor for the class.
	 * 
	 * @param bluetoothFileTransferActivity
	 * 
	 * @param adapter
	 */
	public ConnectionService(Context bluetoothFileTransferActivity,
			BluetoothAdapter adapter) {
		context = bluetoothFileTransferActivity;
		mAdapter = adapter;
		mState = STATE_NONE;
		isExtDrMounted = Environment.MEDIA_MOUNTED.equals(Environment
				.getExternalStorageState());
		File sdr = Environment.getExternalStorageDirectory();
		path = new File(sdr, APP_CONTENT_FOLDER_NAME);
		if (!path.exists()) {
			path.mkdir();
		}
		metaFile = new File(path, META_DATA_FILE_NAME);
		if (!metaFile.exists()){
			PrintWriter writer;
			try {
				writer = new PrintWriter("the-file-name.txt", "UTF-8");
				writer.println("Hello World!!");
				writer.close();
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
		}
	}

	/**
	 * This thread runs while listening for incoming connections. It behaves
	 * like a server-side client. It runs until a connection is accepted (or
	 * until cancelled).
	 */
	private class AcceptThread extends Thread {
		// The local server socket
		private final BluetoothServerSocket mmServerSocket;

		public AcceptThread() {
			BluetoothServerSocket tmp = null;

			// Create a new listening server socket
			try {
				tmp = mAdapter.listenUsingRfcommWithServiceRecord(FTP_SERVICE,
						MY_UUID);
			} catch (IOException e) {
				Log.e(TAG, "AcceptThread: Socket listen() failed", e);
			}
			mmServerSocket = tmp;
		}

		public void run() {
			Log.d(TAG, "BEGIN mAcceptThread" + this);

			BluetoothSocket socket = null;

			// Listen to the server socket if we're not connected
			while (true/* mState != STATE_CONNECTED */) {
				if (mmServerSocket != null) {
					try {
						// This is a blocking call and will only return on a
						// successful connection or an exception
						socket = mmServerSocket.accept();
					} catch (IOException e) {
						Log.e(TAG, "AcceptThread: accept() failed", e);
						break;
					}
				}

				// If a connection was accepted
				if (socket != null) {
					synchronized (ConnectionService.this) {
						switch (mState) {
						case STATE_LISTEN:
						case STATE_CONNECTING:
							// Situation normal. Start the connected thread.
							connected(socket, socket.getRemoteDevice(), true);
							break;
						case STATE_NONE:
						case STATE_CONNECTED:
							// Either not ready or already connected. Terminate
							// new socket.
							try {
								socket.close();
							} catch (IOException e) {
								Log.e(TAG,
										"AcceptThread: Could not close unwanted socket",
										e);
							}
							break;
						}
					}
				}
			}

			Log.i(TAG, "END mAcceptThread");

		}

		public void cancel() {
			Log.d(TAG, "AcceptThread: socket cancel " + this);
			try {
				mmServerSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "AcceptThread: close() of server failed", e);
			}
		}
	}

	/**
	 * This thread runs while attempting to make an outgoing connection with a
	 * device. It runs straight through; the connection either succeeds or
	 * fails.
	 */
	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;

		public ConnectThread(BluetoothDevice device) {
			mmDevice = device;
			BluetoothSocket tmp = null;

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
				tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
			} catch (IOException e) {
				Log.e(TAG, "ConnectThread: create() failed", e);
			}
			mmSocket = tmp;
		}

		public void run() {
			Log.i(TAG, "ConnectThread: BEGIN mConnectThread ");

			// Always cancel discovery because it will slow down a connection
			mAdapter.cancelDiscovery();

			// Make a connection to the BluetoothSocket
			try {
				// This is a blocking call and will only return on a
				// successful connection or an exception
				mmSocket.connect();
			} catch (IOException e) {
				// Close the socket
				try {
					mmSocket.close();
				} catch (IOException e2) {
					Log.e(TAG,
							"unable to close() socket during connection failure",
							e2);
				}
				connectionFailed();
				return;
			}

			// Reset the ConnectThread because we're done
			synchronized (ConnectionService.this) {
				mConnectThread = null;
			}

			// Start the connected thread
			connected(mmSocket, mmDevice, false);
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "ConnectThread: close() socket failed", e);
			}
		}
	}

	/**
	 * This thread runs during a connection with a remote device. It handles all
	 * incoming and outgoing transmissions.
	 */
	private class ConnectedThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		private final boolean isServer;

		public ConnectedThread(BluetoothSocket socket, boolean isServer) {
			Log.d(TAG, "create ConnectedThread: ");
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;
			this.isServer = isServer;

			// Get the BluetoothSocket input and output streams
			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		public void run() {
			Log.i(TAG, "BEGIN mConnectedThread");
			// byte[] buffer = new byte[1024];

			// Keep listening to the InputStream while connected
			while (true) {
				try {
					// TODO store bytes in the external storage
					// check for external storage device

					if (isExtDrMounted) {
						// Read from the InputStream
						DataInputStream din = new DataInputStream(mmInStream);
						String fileName = din.readUTF();
						long fileLen = din.readLong();

						// We can read and write the media
						/*metaFile = new File(path, fileName);*/

						try {
							// Make sure the Pictures directory exists.
							path.mkdirs();

							// Very simple code to copy a picture from the
							// application's
							// resource into the external file. Note that this
							// code does
							// no error checking, and assumes the picture is
							// small (does not
							// try to copy it in chunks). Note that if external
							// storage is
							// not currently mounted this will silently fail.
							OutputStream os = new FileOutputStream(metaFile);
							while (fileLen > 0) {
								fileLen -= din.read(buffer);
								os.write(buffer);
							}
							os.close();
							Toast.makeText(
									context,
									"File: " + fileName
											+ " received successfully",
									Toast.LENGTH_LONG).show();
						} catch (IOException e) {
							// Unable to create file, likely because external
							// storage is
							// not currently mounted.
							Log.e("ExternalStorage", "Error writing " + metaFile, e);
						}
					}

					/*
					 * // Send the obtained bytes to the UI Activity
					 * mHandler.obtainMessage(BluetoothChat.MESSAGE_READ, bytes,
					 * -1, buffer).sendToTarget();
					 */
				} catch (IOException e) {
					Log.e(TAG, "disconnected", e);
					connectionLost();
					// Start the service over to restart listening mode
					// ConnectionService.this.start();
					break;
				}
			}
		}

		/**
		 * Write to the connected OutStream.
		 * 
		 * @param buffer
		 *            The bytes to write
		 * @param nfName
		 * @param len
		 */
		public void write(byte[] buffer, String nfName, long len) {
			try {
				DataOutputStream dos = new DataOutputStream(mmOutStream);
				dos.writeUTF(nfName);
				dos.writeLong(len);
				dos.write(buffer);

				/*
				 * // Share the sent message back to the UI Activity
				 * mHandler.obtainMessage(BluetoothChat.MESSAGE_WRITE, -1, -1,
				 * buffer).sendToTarget();
				 */
			} catch (IOException e) {
				Log.e(TAG, "Exception during write", e);
			}
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}

	/**
	 * Set the current state of the chat connection
	 * 
	 * @param state
	 *            An integer defining the current connection state
	 */
	private synchronized void setState(int state) {
		Log.d(TAG, "setState() " + mState + " -> " + state);
		mState = state;

		/*
		 * // Give the new state to the Handler so the UI Activity can update
		 * mHandler.obtainMessage(BluetoothChat.MESSAGE_STATE_CHANGE, state, -1)
		 * .sendToTarget();
		 */
	}

	/**
	 * Return the current connection state.
	 */
	public synchronized int getState() {
		return mState;
	}

	/**
	 * Start the chat service. Specifically start AcceptThread to begin a
	 * session in listening (server) mode. Called by the Activity onResume()
	 */
	public synchronized void start() {
		Log.d(TAG, "start");

		/*
		 * // Cancel any thread attempting to make a connection if
		 * (mConnectThread != null) { mConnectThread.cancel(); mConnectThread =
		 * null; }
		 * 
		 * // Cancel any thread currently running a connection if
		 * (mConnectedThread != null) { mConnectedThread.cancel();
		 * mConnectedThread = null; }
		 */

		setState(STATE_LISTEN);

		// Start the thread to listen on a BluetoothServerSocket
		if (mAcceptThread == null) {
			mAcceptThread = new AcceptThread();
			mAcceptThread.start();
		}
	}

	/**
	 * Start the ConnectThread to initiate a connection to a remote device.
	 * 
	 * @param device
	 *            The BluetoothDevice to connect
	 * @param secure
	 *            Socket Security type - Secure (true) , Insecure (false)
	 */
	public void connect(BluetoothDevice device) {
		Log.d(TAG, "connect to: " + device);

		/*
		 * // Cancel any thread attempting to make a connection if (mState ==
		 * STATE_CONNECTING) { if (mConnectThread != null) {
		 * mConnectThread.cancel(); mConnectThread = null; } }
		 * 
		 * // Cancel any thread currently running a connection if
		 * (mConnectedThread != null) { mConnectedThread.cancel();
		 * mConnectedThread = null; }
		 */

		// Start the thread to connect with the given device
		mConnectThread = new ConnectThread(device);
		mConnectThread.start();
		setState(STATE_CONNECTING);
	}

	/**
	 * Start the ConnectedThread to begin managing a Bluetooth connection
	 * 
	 * @param socket
	 *            The BluetoothSocket on which the connection was made
	 * @param device
	 *            The BluetoothDevice that has been connected
	 * @param isServer
	 *            TODO
	 */
	public synchronized void connected(BluetoothSocket socket,
			BluetoothDevice device, boolean isServer) {
		Log.d(TAG, "connected");

		// Cancel the thread that completed the connection
		/*
		 * if (mConnectThread != null) { mConnectThread.cancel(); mConnectThread
		 * = null; }
		 * 
		 * // Cancel any thread currently running a connection if
		 * (mConnectedThread != null) { mConnectedThread.cancel();
		 * mConnectedThread = null; }
		 */

		// Cancel the accept thread because we only want to connect to one
		// device
		/*
		 * if (mAcceptThread != null) { mAcceptThread.cancel(); mAcceptThread =
		 * null; }
		 */

		// Start the thread to manage the connection and perform transmissions
		mConnectedThread = new ConnectedThread(socket, isServer);
		mConnectedThread.start();

		/*
		 * // Send the name of the connected device back to the UI Activity
		 * Message msg =
		 * mHandler.obtainMessage(BluetoothChat.MESSAGE_DEVICE_NAME); Bundle
		 * bundle = new Bundle(); bundle.putString(BluetoothChat.DEVICE_NAME,
		 * device.getName()); msg.setData(bundle); mHandler.sendMessage(msg);
		 */

		setState(STATE_CONNECTED);
		if (isServer) {
			write();
		}
	}

	/**
	 * Stop all threads
	 */
	public synchronized void stop() {
		Log.d(TAG, "stop");

		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		if (mAcceptThread != null) {
			mAcceptThread.cancel();
			mAcceptThread = null;
		}

		setState(STATE_NONE);
	}

	/**
	 * Write to the ConnectedThread in an unsynchronized manner
	 * 
	 * @param out
	 *            The bytes to write
	 * @see ConnectedThread#write(byte[])
	 */
	public synchronized void write() {
		// Create temporary object
		ConnectedThread r;
		// Synchronize a copy of the ConnectedThread
		synchronized (this) {
			if (mState != STATE_CONNECTED)
				return;
			r = mConnectedThread;
		}
		// Perform the write unsynchronized
		// TODO store bytes in the external storage
		// check for external storage device
		File file = null;
		// byte[] data = new byte[1024];

		if (isExtDrMounted) {
			// We can read and write the media
			file = new File(path, "PifiMetadata.txt");

			try {
				// Make sure the Pictures directory exists.
				path.mkdirs();

				// Very simple code to copy a picture from the application's
				// resource into the external file. Note that this code does
				// no error checking, and assumes the picture is small (does not
				// try to copy it in chunks). Note that if external storage is
				// not currently mounted this will silently fail.
				InputStream is = new FileInputStream(file);
				is.read(buffer);
				is.close();

			} catch (IOException e) {
				// Unable to create file, likely because external storage is
				// not currently mounted.
				Log.e("ExternalStorage", "Error writing " + file, e);
			}
		}
		r.write(buffer, file.getName(), file.length());
	}

	/**
	 * Indicate that the connection attempt failed and notify the UI Activity.
	 */
	private void connectionFailed() {
		/*
		 * // Send a failure message back to the Activity Message msg =
		 * mHandler.obtainMessage(BluetoothChat.MESSAGE_TOAST); Bundle bundle =
		 * new Bundle(); bundle.putString(BluetoothChat.TOAST,
		 * "Unable to connect device"); msg.setData(bundle);
		 * mHandler.sendMessage(msg);
		 */

		// Start the service over to restart listening mode
		ConnectionService.this.start();
	}

	/**
	 * Indicate that the connection was lost and notify the UI Activity.
	 */
	private void connectionLost() {
		/*
		 * // Send a failure message back to the Activity Message msg =
		 * mHandler.obtainMessage(BluetoothChat.MESSAGE_TOAST); Bundle bundle =
		 * new Bundle(); bundle.putString(BluetoothChat.TOAST,
		 * "Device connection was lost"); msg.setData(bundle);
		 * mHandler.sendMessage(msg);
		 */

		// Start the service over to restart listening mode
		ConnectionService.this.start();
	}

	/**
	 * @return the dataState
	 */
	public synchronized int getDataState() {
		return dataState;
	}

	/**
	 * @param dataState the dataState to set
	 */
	public synchronized void setDataState(int dataState) {
		this.dataState = dataState;
	}

}
