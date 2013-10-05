/**
 * 
 */
package edu.isi.android.bluetoothftpdemo;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * This a service class which will launch the Bluetooth server in the background
 * @author mohit aggarwl
 *
 */
public class BluetoothServer extends Service {

	/* (non-Javadoc)
	 * @see android.app.Service#onBind(android.content.Intent)
	 */
	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		return null;
	}
	
	@Override
	public void onCreate(){
		
	}

	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId){
		return START_STICKY;
		
	}
	
	

}
