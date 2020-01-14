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

  @Override
  public String getName() {
    return name;
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
  public File svgFile(@Nonnull String rawImage, File file) {
    return null;
  }

  @Nonnull
  @Override
  public File pngFile(@Nonnull final BufferedImage rawImage, final File file) {
    return null;
  }

  @Nonnull
  @Override
  public String jpg(@Nullable final BufferedImage rawImage, final CharSequence caption) {
    return "";
  }

  @Nonnull
  @Override
  public File jpgFile(@Nonnull final BufferedImage rawImage, final File file) {
    return null;
  }

  @Override
  public <T> T eval(@Nonnull UncheckedSupplier<T> fn, int maxLog, int framesNo) {
    try {
      return fn.get();
    } catch (Exception e) {
      throw new RuntimeException(e);
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
  public void p(CharSequence fmt, Object... args) {

  }

  @Nonnull
  @Override
  public NotebookOutput onComplete(final Runnable... tasks) {
    return this;
  }

  @Nullable
  @Override
  public CharSequence getFrontMatterProperty(CharSequence key) {
    return null;
  }

  @Override
  public <T> T subreport(@Nonnull Function<NotebookOutput, T> fn, String name) {
    return fn.apply(new NullNotebookOutput(name));
  }

  @Nonnull
  @Override
  public NotebookOutput setCurrentHome(URI currentHome) {
    return this;
  }

  @Nonnull
  @Override
  public NotebookOutput setArchiveHome(URI archiveHome) {
    return this;
  }

  @Nonnull
  @Override
  public NotebookOutput setName(String name) {
    return this;
  }

}
