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
import com.simiacryptus.util.IOUtil;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  protected static final Logger logger = LoggerFactory.getLogger(JsonQuery.class);
  protected final String id = "input_" + UUID.randomUUID().toString() + ".html";
  protected final Closeable handler_get;
  protected final Semaphore done = new Semaphore(0);
  protected final Closeable handler_post;
  final MarkdownNotebookOutput log;
  protected String height1 = "200px";
  protected String height2 = "240px";
  String width = "100%";
  private T value = null;

  public HtmlQuery(MarkdownNotebookOutput log) {
    this.log = log;
    FileHTTPD httpd = this.log.getHttpd();
    this.handler_get = httpd.addGET(id, "text/html", out -> {
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
    this.handler_post = httpd.addPOST(id, request -> {
      String responseHtml;
      try {
        Map<String, String> parms = request.getParms();
        HashMap<String, String> files = new HashMap<>();
        request.parseBody(files);
        setValue(valueFromParams(parms));
        done.release();
        responseHtml = getDisplayHtml();
        FileUtils.write(new File(log.getRoot(), id), responseHtml, "UTF-8");
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      byte[] bytes = responseHtml.getBytes();
      return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", new ByteArrayInputStream(bytes), bytes.length);
    });
    log.onWrite(() -> {
      try {
        FileUtils.write(new File(log.getRoot(), id), getDisplayHtml(), "UTF-8");
      } catch (IOException e) {
        e.printStackTrace();
      }
    });
  }

  public abstract T valueFromParams(Map<String, String> parms) throws IOException;

  protected abstract String getActiveHtml() throws JsonProcessingException;

  protected abstract String getDisplayHtml() throws JsonProcessingException;

  public HtmlQuery<T> print() {
    int lines = height();
    height1 = String.format("%dpx", lines);
    height2 = String.format("%dpx", lines + 40);
    log.p("<iframe src=" + id + " style=\"margin: 0px; width: 100%; height: " + height2 + ";\"></iframe>");
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
    if (null != handler_get) handler_get.close();
    if (null != handler_post) handler_post.close();
    super.finalize();
  }

  public T getValue() {
    return value;
  }

  public HtmlQuery<T> setValue(T value) {
    this.value = value;
    return this;
  }
}
