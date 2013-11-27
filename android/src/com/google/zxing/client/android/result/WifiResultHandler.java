/*
 * Copyright (C) 2008 ZXing authors
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

package com.google.zxing.client.android.result;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.widget.Toast;

import com.google.zxing.client.android.CaptureActivity;
import com.google.zxing.client.android.R;
import com.google.zxing.client.android.common.executor.AsyncTaskExecInterface;
import com.google.zxing.client.android.common.executor.AsyncTaskExecManager;
import com.google.zxing.client.android.wifi.WifiConfigManager;
import com.google.zxing.client.result.ParsedResult;
import com.google.zxing.client.result.WifiParsedResult;

/**
 * Handles wifi access information.
 *
 * @author Vikram Aggarwal
 * @author Sean Owen
 */
public final class WifiResultHandler extends ResultHandler {

  private static final String TAG = WifiResultHandler.class.getSimpleName();

  private final CaptureActivity parent;
  private final AsyncTaskExecInterface taskExec;

  public WifiResultHandler(CaptureActivity activity, ParsedResult result) {
    super(activity, result);
    parent = activity;
    taskExec = new AsyncTaskExecManager().build();
  }

  @Override
  public int getButtonCount() {
    // We just need one button, and that is to configure the wireless.  This could change in the future.
    return 1;
  }

  @Override
  public int getButtonText(int index) {
    return R.string.button_wifi;
  }

  @Override
  public void handleButtonPress(int index) {
    if (index == 0) {
      WifiParsedResult wifiResult = (WifiParsedResult) getResult();
      WifiManager wifiManager = (WifiManager) getActivity().getSystemService(Context.WIFI_SERVICE);
      if (wifiManager == null) {
        Log.w(TAG, "No WifiManager available from device");
        return;
      }
      final Activity activity = getActivity();
      activity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          Toast.makeText(activity.getApplicationContext(), R.string.wifi_changing_network, Toast.LENGTH_SHORT).show();
        }
      });
      taskExec.execute(new WifiConfigManager(wifiManager), wifiResult);
      parent.restartPreviewAfterDelay(0L);
    }
  }

  // Display the name of the network and the network type to the user.
  @Override
  public CharSequence getDisplayContents() {
    WifiParsedResult wifiResult = (WifiParsedResult) getResult();
    StringBuilder contents = new StringBuilder(50);
    String wifiLabel = parent.getString(R.string.wifi_ssid_label);
    ParsedResult.maybeAppend(wifiLabel + '\n' + wifiResult.getSsid(), contents);
    String typeLabel = parent.getString(R.string.wifi_type_label);
    ParsedResult.maybeAppend(typeLabel + '\n' + wifiResult.getNetworkEncryption(), contents);

    String sessionLengthString = wifiResult.getSessionLength();
    if(sessionLengthString != null && !sessionLengthString.isEmpty()) {
      if (sessionLengthString.matches("[0-9]+")) {
        try {
          int sessionLength = Integer.parseInt(sessionLengthString);
          String units = parent.getString(R.string.units_seconds_label);
          //find best display units for session length
          if (sessionLength / 86400 > 0) {
            sessionLength /= 86400;
            units = parent.getString(R.string.units_days_label);
          }
          else if (sessionLength / 3600 > 0) {
            sessionLength /= 3600;
            units = parent.getString(R.string.units_hours_label);
          }
          else if (sessionLength / 60 > 0) {
            sessionLength /= 60;
            units = parent.getString(R.string.units_minutes_label);
          }

          String sessionLengthLabel = parent.getString(R.string.wifi_session_length_label);
          ParsedResult.maybeAppend(sessionLengthLabel + '\n' + Integer.toString(sessionLength) + " " + units, contents);
        }
        catch(NumberFormatException exception) {
          //swallow exception
        }
      }
      else if (sessionLengthString.matches("[0-9]{4}-[0-1][0-9]-[0-9]{2} ((0|1)[0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]")) {
        try {
          TimeZone utcTimeZone = TimeZone.getTimeZone("UTC");
          DateFormat utcDateFormat = new SimpleDateFormat(WifiConfigManager.DateTimeFormatString);
          utcDateFormat.setTimeZone(utcTimeZone);
          Date utcDate = utcDateFormat.parse(sessionLengthString);

          TimeZone localTimeZone = TimeZone.getDefault();
          DateFormat localDateFormat = new SimpleDateFormat("yyyy-MM-dd h:mm:ss a z");
          localDateFormat.setTimeZone(localTimeZone);

          String result = localDateFormat.format(utcDate);

          String sessionTimeoutLabel = parent.getString(R.string.wifi_session_timeout_label);
          ParsedResult.maybeAppend(sessionTimeoutLabel + '\n' + result, contents);
        } catch (ParseException e) {
          ParsedResult.maybeAppend("Unrecognized Time Format", contents);
        }
      }

      String identity = wifiResult.getIdentity();
      ParsedResult.maybeAppend(identity, contents);
      String password = wifiResult.getPassword();
      ParsedResult.maybeAppend(password, contents);

      Log.v(TAG, "Access code: '" + identity + "' '" + password + "'");
    }

    return contents.toString();
  }

  @Override
  public int getDisplayTitle() {
    return R.string.result_wifi;
  }
}