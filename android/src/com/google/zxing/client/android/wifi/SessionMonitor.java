package com.google.zxing.client.android.wifi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class SessionMonitor extends Service {

	public static final String TAG = SessionMonitor.class.getSimpleName();

	private List<Integer> expiringSoon = new ArrayList<Integer>();
	private int aboutToExpireTime = 20; // in seconds

	private WifiManager wifiManager;

	private static final String[] columns = new String[] {
			WifiSessionOpenHelper.KEY_NETWORK_ID,
			WifiSessionOpenHelper.KEY_START_TIME,
			WifiSessionOpenHelper.KEY_LENGTH };

	private class Monitor implements Runnable {

		public Monitor() {
		}

		@Override
		public void run() {
			while (SessionMonitor.isActive.get()) {
				cull();
				synchronized (this) {
					try {
						wait(500);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		}

		private void cull() {
			SQLiteDatabase db = new WifiSessionOpenHelper(SessionMonitor.this)
					.getWritableDatabase();

			WifiInfo currentConnection = wifiManager.getConnectionInfo();

			// check for connections that will soon expire
			String query = "strftime('%s','now') - strftime('%s', "
					+ WifiSessionOpenHelper.KEY_START_TIME + ") > ("
					+ WifiSessionOpenHelper.KEY_LENGTH + " - "
					+ Integer.toString(aboutToExpireTime) + ")";

			Cursor queryResult = db.query(true,
					WifiSessionOpenHelper.TABLE_NAME, columns, query, null,
					null, null, null, null);

			if (queryResult.getCount() > 0) {
				Log.v(TAG, "Found connection(s) that are about to expire.");

				queryResult.moveToFirst();
				while (!queryResult.isAfterLast()) {
					int networkId = queryResult.getInt(0);
					Log.v(TAG, "Network id: " + Integer.toString(networkId));

					if (!expiringSoon.contains(networkId)) {
						if (networkId == currentConnection.getNetworkId()) {
							toast("Connection to " + currentConnection.getSSID()
									+ " about to expire.", Toast.LENGTH_SHORT);
						}

						expiringSoon.add(networkId);
					}

					queryResult.moveToNext();
				}
			}

			// check for expired connections
			query = "strftime('%s','now') - strftime('%s', "
					+ WifiSessionOpenHelper.KEY_START_TIME + ") > "
					+ WifiSessionOpenHelper.KEY_LENGTH;

			queryResult = db.query(true, WifiSessionOpenHelper.TABLE_NAME,
					columns, query, null, null, null, null, null);

			if (queryResult.getCount() > 0) {
				Log.v(TAG, "Found connection(s) with timeout.");

				queryResult.moveToFirst();
				while (!queryResult.isAfterLast()) {
					int networkId = queryResult.getInt(0);

					if (networkId == currentConnection.getNetworkId()) {
						Log.v(TAG, "Current connection has expired.");

						toast("Connection to " + currentConnection.getSSID()
								+ " has expired.", Toast.LENGTH_LONG);
					}

					String startTimeAsString = queryResult.getString(1);
					int length = queryResult.getInt(2);

					Log.v(TAG, "Id: " + networkId);
					Log.v(TAG, "Start Time: " + startTimeAsString);
					Log.v(TAG, "Length: " + length);

					Log.d(TAG, "Connection to " + networkId
							+ " timed out, forgetting...");

					db.delete(WifiSessionOpenHelper.TABLE_NAME,
							WifiSessionOpenHelper.KEY_NETWORK_ID + "="
									+ networkId, null);

					int i = expiringSoon.indexOf(networkId);
					if (i != -1) {
						expiringSoon.remove(i);
					}

					wifiManager.removeNetwork(networkId);
					Log.d(TAG, "Connection forgotten.");

					queryResult.moveToNext();
				}
			}

			db.close();
		}
	}

	private final Thread monitorThread = new Thread(new Monitor());

	private final static AtomicBoolean isActive = new AtomicBoolean();

	@Override
	public void onCreate() {
		Log.d(TAG, "Session monitor service created.");
		wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "Starting session monitor...");
		isActive.set(true);
		monitorThread.start();

		Log.d(TAG, "Session monitor started.");
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		// We don't provide binding, so return null
		return null;
	}

	@Override
	public void onDestroy() {
		isActive.set(false);
		Log.d(TAG, "Session monitor service terminated.");
	}

	private void toast(final String message, final int length) {
		Handler h = new Handler(getMainLooper());

		h.post(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(SessionMonitor.this, message, length).show();
			}
		});
	}
}
