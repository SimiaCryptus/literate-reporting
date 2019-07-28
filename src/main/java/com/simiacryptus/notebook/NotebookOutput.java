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

public interface NotebookOutput extends Closeable {

  static Consumer<NotebookOutput> concat(@Nonnull final Consumer<NotebookOutput> fn, @Nonnull final Consumer<NotebookOutput> header) {
    return log -> {
      header.accept(log);
      fn.accept(log);
    };
  }

  default void run(@Nonnull final Runnable fn) {
    this.eval(() -> {
      fn.run();
      return null;
    }, getMaxOutSize(), 3);
  }

  default <T> T eval(final UncheckedSupplier<T> fn) {
    return eval(fn, getMaxOutSize(), 3);
  }

  default <T> T out(final UncheckedSupplier<T> fn) {
    return eval(fn, Integer.MAX_VALUE, 3);
  }

  @Nonnull
  File svgFile(@Nonnull String rawImage, File file);

  @Nonnull
  File pngFile(@Nonnull BufferedImage rawImage, File file);

  @Nonnull
  String jpg(@Nullable BufferedImage rawImage, CharSequence caption);

  @Nonnull
  File jpgFile(@Nonnull BufferedImage rawImage, File file);

  <T> T eval(UncheckedSupplier<T> fn, int maxLog, int framesNo);

  void onWrite(Runnable fn);

  void write() throws IOException;

  @Nonnull
  OutputStream file(CharSequence name);

  @Nonnull
  String file(CharSequence data, CharSequence caption);

  @Nonnull
  CharSequence file(byte[] data, CharSequence filename, CharSequence caption);

  @Nonnull
  String file(CharSequence data, CharSequence fileName, CharSequence caption);

  void h1(CharSequence fmt, Object... args);

  void h2(CharSequence fmt, Object... args);

  void h3(CharSequence fmt, Object... args);

  @Nonnull
  String png(BufferedImage rawImage, CharSequence caption);

  @Nonnull
  String svg(String rawImage, CharSequence caption);

  CharSequence link(File file, CharSequence text);

  default void out(final CharSequence fmt, final Object... args) {
    p(fmt, args);
  }

  void p(CharSequence fmt, Object... args);

  @Nonnull
  File getRoot();

  @Nonnull
  NotebookOutput onComplete(Runnable... tasks);

  @Override
  void close() throws IOException;

  default void setFrontMatterProperty(CharSequence key, CharSequence value) {
  }

  default void appendFrontMatterProperty(CharSequence key, CharSequence value, CharSequence delimiter) {
    @Nullable CharSequence prior = getFrontMatterProperty(key);
    if (null == prior) setFrontMatterProperty(key, value);
    else setFrontMatterProperty(key, prior.toString() + delimiter + value);
  }

  @Nullable
  CharSequence getFrontMatterProperty(CharSequence key);

  CharSequence getName();

  NotebookOutput setName(String name);

  @Nonnull
  File getResourceDir();

  int getMaxOutSize();

  FileHTTPD getHttpd();

  <T> T subreport(String reportName, Function<NotebookOutput, T> fn);

  URI getCurrentHome();

  NotebookOutput setCurrentHome(URI currentHome);

  URI getArchiveHome();

  NotebookOutput setArchiveHome(URI archiveHome);

}
