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
import com.simiacryptus.ref.lang.RefAware;
import com.simiacryptus.ref.lang.RefUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.function.Function;

public interface NotebookOutput extends Closeable {

  @javax.annotation.Nullable
  URI getArchiveHome();

  @Nonnull
  NotebookOutput setArchiveHome(URI archiveHome);

  String getDisplayName();

  void setDisplayName(String name);

  String getFileName();

  @javax.annotation.Nullable
  FileHTTPD getHttpd();

  @Nonnull
  String getId();

  int getMaxOutSize();

  @Nonnull
  File getResourceDir();

  @Nonnull
  File getRoot();

  @Nonnull
  NotebookOutput setCurrentHome();

  default void run(@Nonnull @RefAware final Runnable fn) {
    try {
      this.eval(() -> {
        fn.run();
        return null;
      }, getMaxOutSize(), 3);
    } finally {
      RefUtil.freeRef(fn);
    }
  }

  default <T> T eval(final @RefAware UncheckedSupplier<T> fn) {
    return eval(fn, getMaxOutSize(), 3);
  }

  default <T> T out(final @RefAware UncheckedSupplier<T> fn) {
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

  <T> T eval(@RefAware UncheckedSupplier<T> fn, int maxLog, int framesNo);

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

  @Nonnull
  CharSequence link(File file, CharSequence text);

  default void out(final CharSequence fmt, final Object... args) {
    p(fmt, args);
  }

  void p(CharSequence fmt, Object... args);

  @Nonnull
  NotebookOutput onComplete(Runnable... tasks);

  @Override
  void close() throws IOException;

  default void setMetadata(CharSequence key, CharSequence value) {
  }

  default void appendMetadata(CharSequence key, CharSequence value, CharSequence delimiter) {
    @Nullable
    CharSequence prior = getMetadata(key);
    if (null == prior)
      setMetadata(key, value);
    else
      setMetadata(key, prior.toString() + delimiter + value);
  }

  @Nullable
  CharSequence getMetadata(CharSequence key);

  <T> T subreport(@RefAware Function<NotebookOutput, T> fn, String name);

  default <T> T subreport(String name, @RefAware Function<NotebookOutput, T> fn) {
    return subreport(fn, name);
  }

}
