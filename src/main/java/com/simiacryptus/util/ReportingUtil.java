/*
 * Copyright (c) 2019 by Andrew Charneski.
 *
 * The author licenses this file to you under the
 * Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.simiacryptus.util;

import com.simiacryptus.ref.wrappers.RefSystem;

import javax.annotation.Nonnull;
import java.awt.*;
import java.io.IOException;
import java.net.URI;

public class ReportingUtil {

  public static final boolean BROWSE_SUPPORTED = !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
      && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
  public static boolean AUTO_BROWSE = Boolean.parseBoolean(
      RefSystem.getProperty("AUTOBROWSE", Boolean.toString(true))) && BROWSE_SUPPORTED;
  public static boolean AUTO_BROWSE_LIVE = Boolean
      .parseBoolean(RefSystem.getProperty("AUTOBROWSE_LIVE", Boolean.toString(true)))
      && BROWSE_SUPPORTED;

  public static void browse(@Nonnull final URI uri) throws IOException {
    if (AUTO_BROWSE)
      Desktop.getDesktop().browse(uri);
  }

  public static boolean canBrowse() {
    return !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
  }
}
