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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.simiacryptus.ref.wrappers.RefString;
import com.simiacryptus.util.IOUtil;
import com.simiacryptus.util.Util;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public abstract class HtmlQuery<T> {
  protected static final Logger logger = LoggerFactory.getLogger(HtmlQuery.class);
  protected final String rawId = UUID.randomUUID().toString();
  protected final String id = "input_" + rawId + ".html";
  @Nonnull
  protected final Closeable handler_get;
  protected final Semaphore done = new Semaphore(0);
  @Nonnull
  protected final Closeable handler_post;
  @Nonnull
  final NotebookOutput log;
  @Nonnull
  protected String height1 = "200px";
  @Nonnull
  protected String height2 = "240px";
  @Nonnull
  String width = "100%";
  @Nullable
  private T value = null;

  public HtmlQuery(@Nonnull NotebookOutput log) {
    this.log = log;
    this.handler_get = log.getHttpd().addGET(id, "text/html", out -> {
      try {
        if (done.tryAcquire()) {
          done.release();
          IOUtil.writeString(getDisplayHtml(), out);
        } else {
          IOUtil.writeString(getActiveHtml(), out);
        }
      } catch (JsonProcessingException e) {
        throw Util.throwException(e);
      }
    });
    this.handler_post = log.getHttpd().addPOST(id, request -> {
      String responseHtml;
      try {
        Map<String, String> parms = request.getParms();
        HashMap<String, String> files = new HashMap<>();
        request.parseBody(files);
        final T value = valueFromParams(parms, files);
        if (value != null) {
          setValue(value);
          done.release();
          responseHtml = getDisplayHtml();
          FileUtils.write(new File(log.getRoot(), id), responseHtml, "UTF-8");
        } else {
          throw new RuntimeException("Submit var not found");
        }
      } catch (IOException e) {
        throw Util.throwException(e);
      }
      byte[] bytes = responseHtml.getBytes();
      return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html",
          new ByteArrayInputStream(bytes), bytes.length);
    });
    log.onWrite(() -> {
      try {
        FileUtils.write(new File(log.getRoot(), id), getDisplayHtml(), "UTF-8");
      } catch (IOException e) {
        e.printStackTrace();
      }
    });
  }

  @Nonnull
  protected abstract String getActiveHtml() throws JsonProcessingException;

  protected abstract String getDisplayHtml() throws JsonProcessingException;

  @Nullable
  public T getValue() {
    return value;
  }

  @Nonnull
  public HtmlQuery<T> setValue(@Nullable T value) {
    if (null != value)
      this.value = value;
    return this;
  }

  @javax.annotation.Nullable
  public abstract T valueFromParams(Map<String, String> parms, Map<String, String> files) throws IOException;

  @Nonnull
  public final HtmlQuery<T> print() {
    int lines = height();
    height1 = RefString.format("%dpx", lines);
    height2 = RefString.format("%dpx", lines + 40);
    log.p("<iframe src=\"" + id + "\" id=\"" + rawId
        + "\" style=\"margin: 0px; resize: both; overflow: auto; width: 100%; height: " + height2 + ";\""
        + " sandbox=\"allow-forms allow-modals allow-pointer-lock allow-popups allow-presentation allow-same-origin allow-scripts\""
        + " allow=\"geolocation; microphone; camera; midi; vr; accelerometer; gyroscope; payment; ambient-light-sensor; encrypted-media\""
        + " scrolling=\"auto\" allowtransparency=\"true\" allowpaymentrequest=\"true\" allowfullscreen=\"true\"></iframe>");
    return this;
  }

  public int height() {
    try {
      int textLines = getDisplayHtml().split("\n").length;
      return (int) (Math.max(Math.min(textLines, 20), 3) * (200.0 / 12));
    } catch (JsonProcessingException e) {
      throw Util.throwException(e);
    }
  }

  @Nullable
  public T get() {
    try {
      done.acquire();
      done.release();
      return getValue();
    } catch (InterruptedException e) {
      throw Util.throwException(e);
    }
  }

  @Nullable
  public T get(long t, @Nonnull TimeUnit u) {
    try {
      if (done.tryAcquire(t, u)) {
        done.release();
        return getValue();
      } else {
        return getValue();
      }
    } catch (InterruptedException e) {
      throw Util.throwException(e);
    }
  }

  @Override
  protected void finalize() throws Throwable {
    handler_get.close();
    handler_post.close();
    super.finalize();
  }
}
