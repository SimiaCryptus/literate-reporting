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
import com.simiacryptus.ref.lang.RefAware;
import com.simiacryptus.ref.lang.RefUtil;
import com.simiacryptus.ref.wrappers.RefHashMap;
import com.simiacryptus.util.IOUtil;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public abstract @RefAware
class HtmlQuery<T> {
  protected static final Logger logger = LoggerFactory.getLogger(JsonQuery.class);
  protected final String rawId = UUID.randomUUID().toString();
  protected final String id = "input_" + rawId + ".html";
  protected final Closeable handler_get;
  protected final Semaphore done = new Semaphore(0);
  protected final Closeable handler_post;
  final NotebookOutput log;
  protected String height1 = "200px";
  protected String height2 = "240px";
  String width = "100%";
  private T value = null;

  public HtmlQuery(NotebookOutput log) {
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
        throw new RuntimeException(e);
      }
    });
    this.handler_post = log.getHttpd().addPOST(id, request -> {
      String responseHtml;
      try {
        Map<String, String> parms = request.getParms();
        RefHashMap<String, String> files = new RefHashMap<>();
        request.parseBody(RefUtil.addRef(files));
        final T value = valueFromParams(parms, RefUtil.addRef(files));
        if (null != files)
          files.freeRef();
        if (value != null) {
          setValue(value);
          done.release();
          responseHtml = getDisplayHtml();
          FileUtils.write(new File(log.getRoot(), id), responseHtml, "UTF-8");
        } else {
          throw new RuntimeException("Submit var not found");
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
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

  protected abstract String getActiveHtml() throws JsonProcessingException;

  protected abstract String getDisplayHtml() throws JsonProcessingException;

  public T getValue() {
    return value;
  }

  public HtmlQuery<T> setValue(T value) {
    if (null != value)
      this.value = value;
    return this;
  }

  public abstract T valueFromParams(Map<String, String> parms, Map<String, String> files) throws IOException;

  public final HtmlQuery<T> print() {
    int lines = height();
    height1 = String.format("%dpx", lines);
    height2 = String.format("%dpx", lines + 40);
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
      return (int) (Math.max(Math.min((textLines), 20), 3) * (200.0 / 12));
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  public T get() {
    try {
      done.acquire();
      done.release();
      return getValue();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public T get(long t, TimeUnit u) {
    try {
      if (done.tryAcquire(t, u)) {
        done.release();
        return getValue();
      } else {
        return getValue();
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void finalize() throws Throwable {
    if (null != handler_get)
      handler_get.close();
    if (null != handler_post)
      handler_post.close();
    super.finalize();
  }
}
