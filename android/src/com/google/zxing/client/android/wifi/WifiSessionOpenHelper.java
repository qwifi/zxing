package com.google.zxing.client.android.wifi;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class WifiSessionOpenHelper extends SQLiteOpenHelper {

	public static final String DATABASE_NAME = "wifi";
	private static final int DATABASE_VERSION = 2;
	public static final String TABLE_NAME = "sessions";
	public static final String KEY_NETWORK_ID = "network_id";
	public static final String KEY_START_TIME = "start_time";
	public static final String KEY_LENGTH = "length";

	private static final String TABLE_CREATE = "CREATE TABLE " + TABLE_NAME
			+ " (" + KEY_NETWORK_ID + " INTEGER, " + KEY_START_TIME + " TEXT,"
			+ KEY_LENGTH + " INTEGER" + ");";

	public WifiSessionOpenHelper(Context context) {
		super(context, DATABASE_NAME, null, DATABASE_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL(TABLE_CREATE);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
	}

}
