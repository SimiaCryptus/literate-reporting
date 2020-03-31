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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.simiacryptus.lang.UncheckedSupplier;
import com.simiacryptus.ref.lang.RefAware;
import com.simiacryptus.ref.lang.RefUtil;
import com.simiacryptus.util.CodeUtil;
import com.simiacryptus.util.JsonUtil;
import org.jetbrains.annotations.NotNull;

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

  @NotNull JsonObject getMetadata();

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

  default void math(String mathExpr) {
    out("```math\n" + mathExpr + "\n```\n");
  }

  default void mermaid(AdmonitionStyle qualifier, String title, String content) {
    collapsable(true, qualifier, title,"```mermaid\n" + content + "\n```\n");
    //String indent = "    ";
    //out("!!! " + qualifier.id + " \"" + title + "\"\n" + indent + content.replaceAll("\n", "\n" + indent) + "\n");
  }

  default void mermaid(String src) {
    out("```mermaid\n" + src + "\n```\n");
  }

  enum AdmonitionStyle {
    Abstract("abstract"),
    Bug("bug"),
    Error("error"),
    Example("example"),
    Failure("failure"),
    Help("help"),
    Info("info"),
    Note("note"),
    Quote("quote"),
    Success("success"),
    Tip("tip"),
    Warning("warning");

    public final String id;

    AdmonitionStyle(String id) {
      this.id = id;
    }
  }

  default void admonition(AdmonitionStyle qualifier, String title, String content) {
    String indent = "    ";
    out("!!! " + qualifier.id + " \"" + title + "\"\n" + indent + content.replaceAll("\n", "\n" + indent) + "\n");
  }

  default void collapsable(boolean initiallyOpen, AdmonitionStyle qualifier, String title, String content) {
    String indent = "    ";
    out((initiallyOpen ?"???+ ":"??? ") + qualifier.id + " \"" + title + "\"\n" + indent + content.replaceAll("\n", "\n" + indent) + "\n");
  }

  @Nonnull
  NotebookOutput setCurrentHome();

  default void run(@Nonnull @RefAware final Runnable fn) {
    try {
      this.eval(null, () -> {
        fn.run();
        return null;
      }, getMaxOutSize(), CodeUtil.getCallingFrame(3));
    } finally {
      RefUtil.freeRef(fn);
    }
  }

  default void run(String title, @Nonnull @RefAware final Runnable fn) {
    try {
      this.eval(title, () -> {
        fn.run();
        return null;
      }, getMaxOutSize(), CodeUtil.getCallingFrame(3));
    } finally {
      RefUtil.freeRef(fn);
    }
  }

  default <T> T eval(final @RefAware UncheckedSupplier<T> fn) {
    return eval(null, fn, getMaxOutSize(), CodeUtil.getCallingFrame(3));
  }

  default <T> T eval(String title, final @RefAware UncheckedSupplier<T> fn) {
    return eval(title, fn, getMaxOutSize(), CodeUtil.getCallingFrame(3));
  }

  default <T> T out(final @RefAware UncheckedSupplier<T> fn) {
    return eval(null, fn, Integer.MAX_VALUE, CodeUtil.getCallingFrame(3));
  }

  default <T> T out(String title, final @RefAware UncheckedSupplier<T> fn) {
    return eval(title, fn, Integer.MAX_VALUE, CodeUtil.getCallingFrame(3));
  }

  @Nonnull
  File svgFile(@Nonnull String rawImage);

  @Nonnull
  File pngFile(@Nonnull BufferedImage rawImage);

  @Nonnull
  String jpg(@Nullable BufferedImage rawImage, CharSequence caption);

  File jpgFile(@Nonnull BufferedImage rawImage);

  <T> T eval(String title, @RefAware UncheckedSupplier<T> fn, int maxLog, StackTraceElement callingFrame);

  void onWrite(Runnable fn);

  void write() throws IOException;

  @Nonnull
  OutputStream file(CharSequence name);

  default void json(Object obj) {
    out("\n\n```json\n  " + JsonUtil.toJson(obj).toString().replaceAll("\n","\n  ") + "\n```\n\n");
  }

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

  void out(final CharSequence fmt, final Object... args);

  default void p(CharSequence fmt, Object... args) {
    out(fmt, args);
  }

  @Nonnull
  NotebookOutput onComplete(Runnable... tasks);

  @Override
  void close() throws IOException;

  default void setMetadata(CharSequence key, CharSequence value) {
    setMetadata(key, value == null ? null : new JsonPrimitive(value.toString()));
  }

  default void setMetadata(CharSequence key, JsonElement value) {
  }

  default void addMetadata(CharSequence key, CharSequence value) {
    JsonElement prior = getMetadata(key);
    if (null == prior) {
      JsonArray jsonArray = new JsonArray();
      jsonArray.add(new JsonPrimitive(value.toString()));
      setMetadata(key, jsonArray);
    } else
      prior.getAsJsonArray().add(new JsonPrimitive(value.toString()));
  }

  JsonElement getMetadata(CharSequence key);

  <T> T subreport(@RefAware Function<NotebookOutput, T> fn, String name);

  default <T> T subreport(String name, @RefAware Function<NotebookOutput, T> fn) {
    return subreport(fn, name);
  }

}
