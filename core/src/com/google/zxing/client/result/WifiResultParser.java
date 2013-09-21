/*
 * Copyright 2010 ZXing authors
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

package com.google.zxing.client.result;

import com.google.zxing.Result;

/**
 * <p>Parses a WIFI configuration string. Strings will be of the form:</p>
 *
 * <p>{@code WIFI:T:[network type];S:[network SSID];P:[network password];H:[hidden?];U:[identity];E:[EAPType];N:[Phase2AuthType];;}</p>
 *
 * <p>The fields can appear in any order. For simple networks, only "S:" is required.</p>
 *
 * <p>For WAP-EAP (802.1x) networks, U, P, E, and N fields are required.</p>
 *
 * @author Vikram Aggarwal
 * @author Sean Owen
 */
public final class WifiResultParser extends ResultParser {

  @Override
  public WifiParsedResult parse(Result result) {
    String rawText = getMassagedText(result);
    if (!rawText.startsWith("WIFI:")) {
      return null;
    }
    String ssid = matchSinglePrefixedField("S:", rawText, ';', false);
    if (ssid == null || ssid.isEmpty()) {
      return null;
    }
    String pass = matchSinglePrefixedField("P:", rawText, ';', false);
    String type = matchSinglePrefixedField("T:", rawText, ';', false);
    if (type == null) {
      type = "nopass";
    }
    boolean hidden = Boolean.parseBoolean(matchSinglePrefixedField("H:", rawText, ';', false));

    String identity = matchSinglePrefixedField("U:", rawText, ';', false);

    if (identity != null && !identity.isEmpty())
    {
      if (pass == null || pass.isEmpty())
        return null;

      String eapType = matchSinglePrefixedField("E:", rawText, ';', false);
      String phase2Type = matchSinglePrefixedField("N:", rawText, ';', false);

      if(eapType == null || eapType.isEmpty())
        return null;

      if (phase2Type == null || phase2Type.isEmpty())
        return null;

      return new WifiParsedResult(type, ssid, pass, hidden, identity, eapType, phase2Type);
    }

    return new WifiParsedResult(type, ssid, pass, hidden);
  }
}