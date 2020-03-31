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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simiacryptus.lang.UncheckedSupplier;
import com.simiacryptus.ref.lang.RefAware;
import com.simiacryptus.ref.lang.RefUtil;
import com.simiacryptus.util.Util;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.util.function.Function;

public class NullNotebookOutput implements NotebookOutput {
  private final String name;

  public NullNotebookOutput(String name) {
    this.name = name;
  }

  public NullNotebookOutput() {
    this("null");
  }

  @Nonnull
  @Override
  public URI getArchiveHome() {
    return new File(".").toURI();
  }

  @Override
  public @NotNull JsonObject getMetadata() {
    return new JsonObject();
  }

  @Override
  public String getDisplayName() {
    return name;
  }

  @Override
  public void setDisplayName(String name) {
  }

  @Override
  public String getFileName() {
    return name;
  }

  @Nonnull
  @Override
  public FileHTTPD getHttpd() {
    return new NullHTTPD();
  }

  @Nonnull
  @Override
  public String getId() {
    return "";
  }

  @Override
  public int getMaxOutSize() {
    return 0;
  }

  @Nonnull
  @Override
  public File getResourceDir() {
    return new File(".");
  }

  @Nonnull
  @Override
  public File getRoot() {
    return new File(".");
  }

  @Override
  public void close() {
  }

  @Nonnull
  @Override
  public File svgFile(@Nonnull String rawImage) {
    return null;
  }

  @Nonnull
  @Override
  public File pngFile(@Nonnull final BufferedImage rawImage) {
    return null;
  }

  @Nonnull
  @Override
  public String jpg(@Nullable final BufferedImage rawImage, final CharSequence caption) {
    return "";
  }

  @Override
  public File jpgFile(@Nonnull BufferedImage rawImage) {
    return null;
  }

  @Override
  public <T> T eval(String title, @Nonnull @RefAware UncheckedSupplier<T> fn, int maxLog, StackTraceElement callingFrame) {
    try {
      return fn.get();
    } catch (Exception e) {
      throw Util.throwException(e);
    } finally {
      RefUtil.freeRef(fn);
    }
  }

  @Override
  public void onWrite(Runnable fn) {
  }

  @Override
  public void write() {
  }

  @Nonnull
  @Override
  public OutputStream file(@Nonnull CharSequence name) {
    return new ByteArrayOutputStream();
  }

  @Nonnull
  @Override
  public String file(@Nonnull CharSequence data, CharSequence caption) {
    return "";
  }

  @Nonnull
  @Override
  public CharSequence file(@Nonnull byte[] data, @Nonnull CharSequence filename, CharSequence caption) {
    return "";
  }

  @Nonnull
  @Override
  public String file(@Nonnull CharSequence data, @Nonnull CharSequence fileName, CharSequence caption) {
    return "";
  }

  @Override
  public void h1(CharSequence fmt, Object... args) {
  }

  @Override
  public void h2(CharSequence fmt, Object... args) {
  }

  @Override
  public void h3(CharSequence fmt, Object... args) {
  }

  @Nonnull
  @Override
  public String png(BufferedImage rawImage, CharSequence caption) {
    return "";
  }

  @Nonnull
  @Override
  public String svg(String rawImage, CharSequence caption) {
    return null;
  }

  @Nonnull
  @Override
  public CharSequence link(File file, CharSequence text) {
    return "";
  }

  @Override
  public void out(CharSequence fmt, Object... args) {
  }

  @Nonnull
  @Override
  public NotebookOutput onComplete(final Runnable... tasks) {
    return this;
  }

  @Override
  public JsonElement getMetadata(CharSequence key) {
    return null;
  }

  @Override
  public <T> T subreport(@Nonnull @RefAware Function<NotebookOutput, T> fn, String name) {
    try {
      return fn.apply(new NullNotebookOutput(name));
    } finally {
      RefUtil.freeRef(fn);
    }
  }

  @Nonnull
  @Override
  public NotebookOutput setCurrentHome() {
    return this;
  }

  @Nonnull
  @Override
  public NotebookOutput setArchiveHome(URI archiveHome) {
    return this;
  }

}
