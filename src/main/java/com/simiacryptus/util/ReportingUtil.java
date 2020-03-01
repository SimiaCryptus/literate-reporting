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

import com.simiacryptus.ref.wrappers.*;

import javax.annotation.Nonnull;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;

public class ReportingUtil {

  public static final boolean BROWSE_SUPPORTED = !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
      && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
  public static boolean AUTO_BROWSE = Boolean.parseBoolean(
      RefSystem.getProperty("AUTOBROWSE", Boolean.toString(false))) && BROWSE_SUPPORTED;
  public static boolean AUTO_BROWSE_LIVE = Boolean
      .parseBoolean(RefSystem.getProperty("AUTOBROWSE_LIVE", Boolean.toString(false)))
      && BROWSE_SUPPORTED;

  public static void browse(@Nonnull final URI uri) throws IOException {
    if (AUTO_BROWSE)
      Desktop.getDesktop().browse(uri);
  }

  public static <T> T getLast(@Nonnull final RefStream<T> stream) {
    final RefList<T> collect = stream.collect(RefCollectors.toList());
    T t = collect.get(collect.size() - 1);
    collect.freeRef();
    return t;
  }

  public static void report(@Nonnull final RefStream<CharSequence> fragments) throws IOException {
    @Nonnull final File outDir;
    if (new File("target").exists()) {
      outDir = new File("target/reports");
    } else {
      outDir = new File("reports");
    }
    outDir.mkdirs();
    final StackTraceElement caller = getLast(RefArrays.stream(Thread.currentThread().getStackTrace())//
        .filter(x -> x.getClassName().contains("simiacryptus")));
    @Nonnull final File report = new File(outDir, caller.getClassName() + "_" + caller.getLineNumber() + ".html");
    @Nonnull final PrintStream out = new PrintStream(new FileOutputStream(report));
    out.println("<html><head></head><body>");
    fragments.forEach(x -> out.println(x));
    out.println("</body></html>");
    out.close();
    ReportingUtil.browse(report.toURI());
  }

  public static void report(final CharSequence... fragments) throws IOException {
    report(RefStream.of(fragments));
  }
}
