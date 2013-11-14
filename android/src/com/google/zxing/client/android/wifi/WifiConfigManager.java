/*
 * Copyright (C) 2011 ZXing authors
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

package com.google.zxing.client.android.wifi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import com.google.zxing.client.result.WifiParsedResult;

/**
 * @author Vikram Aggarwal
 * @author Sean Owen
 */
public final class WifiConfigManager extends AsyncTask<WifiParsedResult,Object,Object> {

  private static final String TAG = WifiConfigManager.class.getSimpleName();

  private static final Pattern HEX_DIGITS = Pattern.compile("[0-9A-Fa-f]+");

  private final WifiManager wifiManager;

  public WifiConfigManager(WifiManager wifiManager) {
    this.wifiManager = wifiManager;
  }

  @Override
  protected Object doInBackground(WifiParsedResult... args) {
    WifiParsedResult theWifiResult = args[0];
    // Start WiFi, otherwise nothing will work
    if (!wifiManager.isWifiEnabled()) {
      Log.i(TAG, "Enabling wi-fi...");
      if (wifiManager.setWifiEnabled(true)) {
        Log.i(TAG, "Wi-fi enabled");
      } else {
        Log.w(TAG, "Wi-fi could not be enabled!");
        return null;
      }
      // This happens very quickly, but need to wait for it to enable. A little busy wait?
      int count = 0;
      while (!wifiManager.isWifiEnabled()) {
        if (count >= 10) {
          Log.i(TAG, "Took too long to enable wi-fi, quitting");
          return null;
        }
        Log.i(TAG, "Still waiting for wi-fi to enable...");
        try {
          Thread.sleep(1000L);
        } catch (InterruptedException ie) {
          // continue
        }
        count++;
      }
    }
    String networkTypeString = theWifiResult.getNetworkEncryption();
    NetworkType networkType;
    try {
      networkType = NetworkType.forIntentValue(networkTypeString);
    } catch (IllegalArgumentException ignored) {
      Log.w(TAG, "Bad network type; see NetworkType values: " + networkTypeString);
      return null;
    }
    if (networkType == NetworkType.NO_PASSWORD) {
      changeNetworkUnEncrypted(wifiManager, theWifiResult);
    } else {
      String password = theWifiResult.getPassword();
      if (password != null && !password.isEmpty()) {
        if (networkType == NetworkType.WEP) {
          changeNetworkWEP(wifiManager, theWifiResult);
        } else if (networkType == NetworkType.WPA) {
          changeNetworkWPA(wifiManager, theWifiResult);
        } else if (networkType == NetworkType.WPAEAP) {
          changeNetworkWPAEAP(wifiManager, theWifiResult);
        }
      }
    }
    return null;
  }

  /**
   * Update the network: either create a new network or modify an existing network
   * @param config the new network configuration
   */
  private static void updateNetwork(WifiManager wifiManager, WifiConfiguration config) {
    Integer foundNetworkID = findNetworkInExistingConfig(wifiManager, config.SSID);
    if (foundNetworkID != null) {
      Log.i(TAG, "Removing old configuration for network " + config.SSID);
      wifiManager.removeNetwork(foundNetworkID);
      wifiManager.saveConfiguration();
    }
    int networkId = wifiManager.addNetwork(config);
    if (networkId >= 0) {
      // Try to disable the current network and start a new one.
      if (wifiManager.enableNetwork(networkId, true)) {
        Log.i(TAG, "Associating to network " + config.SSID);
        wifiManager.saveConfiguration();
      } else {
        Log.w(TAG, "Failed to enable network " + config.SSID);
      }
    } else {
      Log.w(TAG, "Unable to add network " + config.SSID);
    }
  }

  private static WifiConfiguration changeNetworkCommon(WifiParsedResult wifiResult) {
    WifiConfiguration config = new WifiConfiguration();
    config.allowedAuthAlgorithms.clear();
    config.allowedGroupCiphers.clear();
    config.allowedKeyManagement.clear();
    config.allowedPairwiseCiphers.clear();
    config.allowedProtocols.clear();
    // Android API insists that an ascii SSID must be quoted to be correctly handled.
    config.SSID = quoteNonHex(wifiResult.getSsid());
    config.hiddenSSID = wifiResult.isHidden();
    return config;
  }

  // Adding a WEP network
  private static void changeNetworkWEP(WifiManager wifiManager, WifiParsedResult wifiResult) {
    WifiConfiguration config = changeNetworkCommon(wifiResult);
    config.wepKeys[0] = quoteNonHex(wifiResult.getPassword(), 10, 26, 58);
    config.wepTxKeyIndex = 0;
    config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
    updateNetwork(wifiManager, config);
  }

  // Adding a WPA or WPA2 network
  private static void changeNetworkWPA(WifiManager wifiManager, WifiParsedResult wifiResult) {
    WifiConfiguration config = changeNetworkCommon(wifiResult);
    // Hex passwords that are 64 bits long are not to be quoted.
    config.preSharedKey = quoteNonHex(wifiResult.getPassword(), 64);
    config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
    config.allowedProtocols.set(WifiConfiguration.Protocol.WPA); // For WPA
    config.allowedProtocols.set(WifiConfiguration.Protocol.RSN); // For WPA2
    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
    config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
    config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
    updateNetwork(wifiManager, config);
  }

  private static int length;
  private static String ssid = "";
  private static Date timeout = null;
  public static final String dateTimeFormatString = "yyyy-MM-dd HH:mm:ss";
  public static final BroadcastReceiver WifiBroadcastReceiver =
    new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        Log.v(TAG, "Receiver received wifi connection state broadcast notification.");

        synchronized (this) { //enormous sync context here, but it's the best we can do for now.
          if (ssid.equals(""))
            return; //not currently listening for events
          else {
            Log.v(TAG, "Processing broadcast notification...");

            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiInfo currentConnection = wifiManager.getConnectionInfo();

            if(currentConnection.getSupplicantState() != SupplicantState.COMPLETED) {
              Log.v(TAG, "Invalid connection state: " + currentConnection.getSupplicantState());
              return;
            }

            if (!ssid.equals(currentConnection.getSSID()) && !("\"" + ssid + "\"").equals(currentConnection.getSSID())) {
              Log.v(TAG, "Connection SSID " + currentConnection.getSSID() + " is not related to the connection we care about ("
                    + ssid + ").");
              return;
            }

            SQLiteDatabase db = new WifiSessionOpenHelper(context).getWritableDatabase();

            ContentValues values = new ContentValues();
            Log.v(TAG, "Current Network Id: " + currentConnection.getNetworkId());
            values.put(WifiSessionOpenHelper.KEY_NETWORK_ID, currentConnection.getNetworkId());
            //ref: http://stackoverflow.com/a/3914498/577298
            TimeZone tz = TimeZone.getTimeZone("UTC");
            DateFormat dateFormat = new SimpleDateFormat(dateTimeFormatString);
            dateFormat.setTimeZone(tz);
            Date now = new Date();
            String nowAsString = dateFormat.format(now);
            Log.v(TAG, "Connection Start Time: " + nowAsString);
            values.put(WifiSessionOpenHelper.KEY_START_TIME, nowAsString);
            Log.v(TAG, "Connection Duration: " + length);

            if (timeout != null) {
              length  = (int)Math.round((timeout.getTime()- now.getTime()) / 1000); //to nearest second
              if (length < 0)
                length = 0;
              timeout = null;
            }
            values.put(WifiSessionOpenHelper.KEY_LENGTH, length);

            db.insert(WifiSessionOpenHelper.TABLE_NAME, null, values);
            db.close();
            Log.d(TAG, "Session information stored for network id " + currentConnection.getNetworkId());

            Toast.makeText(context, "Connection to \"" + ssid + "\" established.", Toast.LENGTH_SHORT).show();
            ssid = "";

            Log.v(TAG, "Finished broadcast receiver for " + currentConnection.getSSID() + ".");
          }
        }
      }
    };

  private static void changeNetworkWPAEAP(WifiManager wifiManager, WifiParsedResult wifiResult) {
    WifiConfiguration config = changeNetworkCommon(wifiResult);
    // Hex passwords that are 64 bits long are not to be quoted.
    config.preSharedKey = quoteNonHex(wifiResult.getPassword(), 64);
    config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
    config.allowedProtocols.set(WifiConfiguration.Protocol.WPA); // For WPA
    config.allowedProtocols.set(WifiConfiguration.Protocol.RSN); // For WPA2
    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
    config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
    config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
    setEapConfiguration(config, wifiResult);

    String sessionLengthString = wifiResult.getSessionLength();
    if (sessionLengthString != null && !sessionLengthString.isEmpty()) {
      synchronized (WifiBroadcastReceiver) {
        ssid = wifiResult.getSsid();
        if (sessionLengthString.matches("[0-9]{4}-[0-1][0-9]-[0-9]{2} ((0|1)[0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]")) {
          try {
            String formatString = "yyyy-MM-dd HH:mm:ss";
            SimpleDateFormat dateParser = new SimpleDateFormat(formatString);
            dateParser.setTimeZone(TimeZone.getTimeZone("UTC")); //timeouts should be in UTC
            timeout = dateParser.parse(sessionLengthString);
          }
          catch (ParseException e) {
           //swallow exception
          }
        }
        else {
          length = Integer.parseInt(sessionLengthString);
        }
      }
    }

    updateNetwork(wifiManager, config);
  }

  private static void setEapConfiguration(WifiConfiguration config, WifiParsedResult wifiResult) {
    if (android.os.Build.VERSION.SDK_INT >=18) {
      setEapConfigurationCurrent(config, wifiResult);
    }
    else {
      setEapConfigurationLegacy(config, wifiResult);
    }
  }

  @TargetApi(18)
  private static void setEapConfigurationCurrent(WifiConfiguration config, WifiParsedResult wifiResult) {
    if("PEAP".equals(wifiResult.getEapType())) {
      config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.PEAP);
    }
    else if("PWD".equals(wifiResult.getEapType())) {
      config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
    }
    else if("TLS".equals(wifiResult.getEapType())) {
      config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
    }
    else if("TTLS".equals(wifiResult.getEapType())) {
      config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
    }
    else {
      config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.NONE);
    }

    if("GTC".equals(wifiResult.getEapType())) {
      config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);
    }
    else if("MSCHAP".equals(wifiResult.getEapType())) {
      config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAP);
    }
    else if("MSCHAPV2".equals(wifiResult.getEapType())) {
      config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2);
    }
    else if("PAP".equals(wifiResult.getEapType())) {
      config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.PAP);
    }
    else {
      config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.NONE);
    }

    config.enterpriseConfig.setIdentity(wifiResult.getIdentity());
    config.enterpriseConfig.setPassword(wifiResult.getPassword());
  }

  /**
   * Legacy enterprise wireless configuration, for use on devices with API level < 18
   * @param selectedConfig
   * @param wifiResult
   */
  private static void setEapConfigurationLegacy(WifiConfiguration selectedConfig, WifiParsedResult wifiResult) {
    //ref: http://stackoverflow.com/a/4375874/577298
    final String INT_PRIVATE_KEY = "private_key";
    final String INT_PHASE2 = "phase2";
    final String INT_PASSWORD = "password";
    final String INT_IDENTITY = "identity";
    final String INT_EAP = "eap";
    final String INT_CLIENT_CERT = "client_cert";
    final String INT_CA_CERT = "ca_cert";
    final String INT_ANONYMOUS_IDENTITY = "anonymous_identity";
    final String INT_ENTERPRISEFIELD_NAME = "android.net.wifi.WifiConfiguration$EnterpriseField";
    // Reflection magic here too, need access to non-public APIs
    try {
      // Let the magic start
      Class[] wcClasses = WifiConfiguration.class.getClasses();
      // null for overzealous java compiler
      Class wcEnterpriseField = null;

      for (Class wcClass : wcClasses)
        if (wcClass.getName().equals(INT_ENTERPRISEFIELD_NAME))
        {
            wcEnterpriseField = wcClass;
            break;
        }
      boolean noEnterpriseFieldType = false;
      if(wcEnterpriseField == null)
        noEnterpriseFieldType = true; // Cupcake/Donut access enterprise settings directly

      Field wcefAnonymousId = null, wcefCaCert = null, wcefClientCert = null, wcefEap = null, wcefIdentity = null, wcefPassword = null, wcefPhase2 = null, wcefPrivateKey = null;
      Field[] wcefFields = WifiConfiguration.class.getFields();
      // Dispatching Field vars
      for (Field wcefField : wcefFields)
      {
        if (wcefField.getName().equals(INT_ANONYMOUS_IDENTITY))
          wcefAnonymousId = wcefField;
        else if (wcefField.getName().equals(INT_CA_CERT))
          wcefCaCert = wcefField;
        else if (wcefField.getName().equals(INT_CLIENT_CERT))
          wcefClientCert = wcefField;
        else if (wcefField.getName().equals(INT_EAP))
          wcefEap = wcefField;
        else if (wcefField.getName().equals(INT_IDENTITY))
          wcefIdentity = wcefField;
        else if (wcefField.getName().equals(INT_PASSWORD))
          wcefPassword = wcefField;
        else if (wcefField.getName().equals(INT_PHASE2))
          wcefPhase2 = wcefField;
        else if (wcefField.getName().equals(INT_PRIVATE_KEY))
          wcefPrivateKey = wcefField;
      }

      Method wcefSetValue = null;
      if(!noEnterpriseFieldType){
       for(Method m: wcEnterpriseField.getMethods())
          //System.out.println(m.getName());
          if(m.getName().trim().equals("setValue"))
              wcefSetValue = m;
      }

      /*EAP Method*/
      if(!noEnterpriseFieldType)
      {
              wcefSetValue.invoke(wcefEap.get(selectedConfig), wifiResult.getEapType());
      }
      else
      {
              wcefEap.set(selectedConfig, wifiResult.getEapType());
      }
      /*EAP Phase 2 Authentication*/
      if(!noEnterpriseFieldType)
      {
              wcefSetValue.invoke(wcefPhase2.get(selectedConfig), wifiResult.getPhase2Type());
      }
      else
      {
            wcefPhase2.set(selectedConfig, wifiResult.getPhase2Type());
      }
      /*EAP Anonymous Identity*/
      /*if(!noEnterpriseFieldType)
      {
              wcefSetValue.invoke(wcefAnonymousId.get(selectedConfig), ENTERPRISE_ANON_IDENT);
      }
      else
      {
            wcefAnonymousId.set(selectedConfig, ENTERPRISE_ANON_IDENT);
      }*/
      //TODO: re-enable this
      /*EAP CA Certificate*/
      /*if(!noEnterpriseFieldType)
      {
              wcefSetValue.invoke(wcefCaCert.get(selectedConfig), ENTERPRISE_CA_CERT);
      }
      else
      {
            wcefCaCert.set(selectedConfig, ENTERPRISE_CA_CERT);
      }*/
      /*EAP Private key*/
      /*if(!noEnterpriseFieldType)
      {
              wcefSetValue.invoke(wcefPrivateKey.get(selectedConfig), ENTERPRISE_PRIV_KEY);
      }
      else
      {
            wcefPrivateKey.set(selectedConfig, ENTERPRISE_PRIV_KEY);
      }*/
      /*EAP Identity*/
      if(!noEnterpriseFieldType)
      {
              wcefSetValue.invoke(wcefIdentity.get(selectedConfig), wifiResult.getIdentity());
      }
      else
      {
            wcefIdentity.set(selectedConfig, wifiResult.getIdentity());
      }
      /*EAP Password*/
      if(!noEnterpriseFieldType)
      {
              wcefSetValue.invoke(wcefPassword.get(selectedConfig), wifiResult.getPassword());
      }
      else
      {
            wcefPassword.set(selectedConfig, wifiResult.getPassword());
      }
      /*EAP Client certificate*/
      /*if(!noEnterpriseFieldType)
      {
          wcefSetValue.invoke(wcefClientCert.get(selectedConfig), ENTERPRISE_CLIENT_CERT);
      }
      else
      {
            wcefClientCert.set(selectedConfig, ENTERPRISE_CLIENT_CERT);
      }*/
      /*// Adhoc for CM6
      // if non-CM6 fails gracefully thanks to nested try-catch

      try{
      Field wcAdhoc = WifiConfiguration.class.getField("adhocSSID");
      Field wcAdhocFreq = WifiConfiguration.class.getField("frequency");
      //wcAdhoc.setBoolean(selectedConfig, prefs.getBoolean(PREF_ADHOC,
      //      false));
      wcAdhoc.setBoolean(selectedConfig, false);
      int freq = 2462;    // default to channel 11
      //int freq = Integer.parseInt(prefs.getString(PREF_ADHOC_FREQUENCY,
      //"2462"));     // default to channel 11
      //System.err.println(freq);
      wcAdhocFreq.setInt(selectedConfig, freq); 
      } catch (Exception e)
      {
          e.printStackTrace();
      }*/

    } catch (Exception e)
    {
      e.printStackTrace();
    }
  }

// Adding an open, unsecured network
  private static void changeNetworkUnEncrypted(WifiManager wifiManager, WifiParsedResult wifiResult) {
    WifiConfiguration config = changeNetworkCommon(wifiResult);
    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
    updateNetwork(wifiManager, config);
  }

  private static Integer findNetworkInExistingConfig(WifiManager wifiManager, String ssid) {
    List<WifiConfiguration> existingConfigs = wifiManager.getConfiguredNetworks();
    for (WifiConfiguration existingConfig : existingConfigs) {
      if (existingConfig.SSID.equals(ssid)) {
        return existingConfig.networkId;
      }
    }
    return null;
  }

  private static String quoteNonHex(String value, int... allowedLengths) {
    return isHexOfLength(value, allowedLengths) ? value : convertToQuotedString(value);
  }

  /**
   * Encloses the incoming string inside double quotes, if it isn't already quoted.
   * @param s the input string
   * @return a quoted string, of the form "input".  If the input string is null, it returns null
   * as well.
   */
  private static String convertToQuotedString(String s) {
    if (s == null || s.isEmpty()) {
      return null;
    }
    // If already quoted, return as-is
    if (s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
      return s;
    }
    return '\"' + s + '\"';
  }

  /**
   * @param value input to check
   * @param allowedLengths allowed lengths, if any
   * @return true if value is a non-null, non-empty string of hex digits, and if allowed lengths are given, has
   *  an allowed length
   */
  private static boolean isHexOfLength(CharSequence value, int... allowedLengths) {
    if (value == null || !HEX_DIGITS.matcher(value).matches()) {
      return false;
    }
    if (allowedLengths.length == 0) {
      return true;
    }
    for (int length : allowedLengths) {
      if (value.length() == length) {
        return true;
      }
    }
    return false;
  }

}
