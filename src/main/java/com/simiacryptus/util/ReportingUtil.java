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

import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ReportingUtil {

  /**
   * The constant AUTO_BROWSE.
   */
  public static boolean AUTO_BROWSE = Boolean.parseBoolean(System.getProperty("AUTOBROWSE", Boolean.toString(false)));

  /**
   * Browse.
   *
   * @param uri the uri
   * @throws IOException the io exception
   */
  public static void browse(final URI uri) throws IOException {
    if (AUTO_BROWSE && !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
      Desktop.getDesktop().browse(uri);
  }

  /**
   * Gets last.
   *
   * @param <T>    the type parameter
   * @param stream the stream
   * @return the last
   */
  public static <T> T getLast(@javax.annotation.Nonnull final Stream<T> stream) {
    final List<T> collect = stream.collect(Collectors.toList());
    final T last = collect.get(collect.size() - 1);
    return last;
  }

  /**
   * Report.
   *
   * @param fragments the fragments
   * @throws IOException the io exception
   */
  public static void report(@javax.annotation.Nonnull final Stream<CharSequence> fragments) throws IOException {
    @javax.annotation.Nonnull final File outDir;
    if(new File("target").exists()) {
      outDir = new File("target/reports");
    } else {
      outDir = new File("reports");
    }
    outDir.mkdirs();
    final StackTraceElement caller = getLast(Arrays.stream(Thread.currentThread().getStackTrace())//
        .filter(x -> x.getClassName().contains("simiacryptus")));
    @javax.annotation.Nonnull final File report = new File(outDir, caller.getClassName() + "_" + caller.getLineNumber() + ".html");
    @javax.annotation.Nonnull final PrintStream out = new PrintStream(new FileOutputStream(report));
    out.println("<html><head></head><body>");
    fragments.forEach(out::println);
    out.println("</body></html>");
    out.close();
    if (AUTO_BROWSE && !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
      Desktop.getDesktop().browse(report.toURI());
  }

  /**
   * Report.
   *
   * @param fragments the fragments
   * @throws IOException the io exception
   */
  public static void report(final CharSequence... fragments) throws IOException {
    report(Stream.of(fragments));
  }
}
