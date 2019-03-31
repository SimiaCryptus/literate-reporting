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

package com.simiacryptus.notebook;

import com.simiacryptus.lang.UncheckedSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The interface Notebook output.
 */
public interface NotebookOutput extends Closeable {

  /**
   * Concat consumer.
   *
   * @param fn     the fn
   * @param header the header
   * @return the consumer
   */
  static Consumer<NotebookOutput> concat(@Nonnull final Consumer<NotebookOutput> fn, @Nonnull final Consumer<NotebookOutput> header) {
    return log -> {
      header.accept(log);
      fn.accept(log);
    };
  }

  /**
   * Code.
   *
   * @param fn the fn
   */
  default void run(@Nonnull final Runnable fn) {
    this.eval(() -> {
      fn.run();
      return null;
    }, getMaxOutSize(), 3);
  }

  /**
   * Code t.
   *
   * @param <T> the type parameter
   * @param fn  the fn
   * @return the t
   */
  default <T> T eval(final UncheckedSupplier<T> fn) {
    return eval(fn, getMaxOutSize(), 3);
  }

  /**
   * Code t.
   *
   * @param <T> the type parameter
   * @param fn  the fn
   * @return the t
   */
  default <T> T out(final UncheckedSupplier<T> fn) {
    return eval(fn, Integer.MAX_VALUE, 3);
  }

  @Nonnull
  File svgFile(@Nonnull String rawImage, File file);

  /**
   * Png file file.
   *
   * @param rawImage the raw image
   * @param file     the file
   * @return the file
   */
  @Nonnull
  File pngFile(@Nonnull BufferedImage rawImage, File file);

  /**
   * Jpg string.
   *
   * @param rawImage the raw image
   * @param caption  the caption
   * @return the string
   */
  @Nonnull
  String jpg(@Nullable BufferedImage rawImage, CharSequence caption);

  /**
   * Jpg file file.
   *
   * @param rawImage the raw image
   * @param file     the file
   * @return the file
   */
  @Nonnull
  File jpgFile(@Nonnull BufferedImage rawImage, File file);

  /**
   * Code t.
   *
   * @param <T>      the type parameter
   * @param fn       the fn
   * @param maxLog   the max log
   * @param framesNo the frames no
   * @return the t
   */
  <T> T eval(UncheckedSupplier<T> fn, int maxLog, int framesNo);

  /**
   * Write.
   *
   * @throws IOException the io exception
   */
  void write() throws IOException;

  /**
   * File output stream.
   *
   * @param name the name
   * @return the output stream
   */
  @Nonnull
  OutputStream file(CharSequence name);

  /**
   * File string.
   *
   * @param data    the data
   * @param caption the caption
   * @return the string
   */
  @Nonnull
  String file(CharSequence data, CharSequence caption);

  /**
   * File string.
   *
   * @param data     the data
   * @param filename the filename
   * @param caption  the caption
   * @return the string
   */
  @Nonnull
  CharSequence file(byte[] data, CharSequence filename, CharSequence caption);

  /**
   * File string.
   *
   * @param data     the data
   * @param fileName the file name
   * @param caption  the caption
   * @return the string
   */
  @Nonnull
  String file(CharSequence data, CharSequence fileName, CharSequence caption);

  /**
   * H 1.
   *
   * @param fmt  the fmt
   * @param args the args
   */
  void h1(CharSequence fmt, Object... args);

  /**
   * H 2.
   *
   * @param fmt  the fmt
   * @param args the args
   */
  void h2(CharSequence fmt, Object... args);

  /**
   * H 3.
   *
   * @param fmt  the fmt
   * @param args the args
   */
  void h3(CharSequence fmt, Object... args);

  /**
   * Image string.
   *
   * @param rawImage the raw png
   * @param caption  the caption
   * @return the string
   */
  @Nonnull
  String png(BufferedImage rawImage, CharSequence caption);

  @Nonnull
  String svg(String rawImage, CharSequence caption);

  /**
   * Link string.
   *
   * @param file the file
   * @param text the text
   * @return the string
   */
  CharSequence link(File file, CharSequence text);

  /**
   * Out.
   *
   * @param fmt  the fmt
   * @param args the args
   */
  default void out(final CharSequence fmt, final Object... args) {
    p(fmt, args);
  }

  /**
   * P.
   *
   * @param fmt  the fmt
   * @param args the args
   */
  void p(CharSequence fmt, Object... args);

  /**
   * Gets root.
   *
   * @return the root
   */
  @Nonnull
  File getRoot();

  /**
   * On complete notebook output.
   *
   * @param tasks the tasks
   * @return the notebook output
   */
  @Nonnull
  NotebookOutput onComplete(Runnable... tasks);

  @Override
  void close() throws IOException;

  /**
   * Sets fm prop.
   *
   * @param key   the key
   * @param value the value
   */
  default void setFrontMatterProperty(CharSequence key, CharSequence value) {
  }

  /**
   * Append front matter property.
   *
   * @param key       the key
   * @param value     the value
   * @param delimiter the delimiter
   */
  default void appendFrontMatterProperty(CharSequence key, CharSequence value, CharSequence delimiter) {
    @Nullable CharSequence prior = getFrontMatterProperty(key);
    if (null == prior) setFrontMatterProperty(key, value);
    else setFrontMatterProperty(key, prior.toString() + delimiter + value);
  }

  /**
   * Gets front matter property.
   *
   * @param key the key
   * @return the front matter property
   */
  @Nullable
  CharSequence getFrontMatterProperty(CharSequence key);

  /**
   * Gets name.
   *
   * @return the name
   */
  CharSequence getName();

  /**
   * Sets name.
   *
   * @param name the name
   * @return the name
   */
  NotebookOutput setName(String name);

  /**
   * Gets resource dir.
   *
   * @return the resource dir
   */
  @Nonnull
  File getResourceDir();

  /**
   * Gets max out size.
   *
   * @return the max out size
   */
  int getMaxOutSize();

  /**
   * Gets httpd.
   *
   * @return the httpd
   */
  FileHTTPD getHttpd();

  /**
   * Subreport t.
   *
   * @param <T>        the type parameter
   * @param reportName the report name
   * @param fn         the fn
   * @return the t
   */
  <T> T subreport(String reportName, Function<NotebookOutput, T> fn);

  /**
   * Gets current home.
   *
   * @return the current home
   */
  URI getCurrentHome();

  /**
   * Sets current home.
   *
   * @param currentHome the current home
   * @return the current home
   */
  NotebookOutput setCurrentHome(URI currentHome);

  /**
   * Gets archive home.
   *
   * @return the archive home
   */
  URI getArchiveHome();

  /**
   * Sets archive home.
   *
   * @param archiveHome the archive home
   * @return the archive home
   */
  NotebookOutput setArchiveHome(URI archiveHome);

}
