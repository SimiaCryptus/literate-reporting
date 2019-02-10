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

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The type String query.
 *
 * @param <T> the type parameter
 */
public abstract class StringQuery<T> {

  /**
   * The constant logger.
   */
  protected static final Logger logger = LoggerFactory.getLogger(JsonQuery.class);
  /**
   * The Id.
   */
  protected final String id = "input_" + UUID.randomUUID().toString() + ".html";
  /**
   * The Handler get.
   */
  protected final Closeable handler_get;
  /**
   * The Done.
   */
  protected final Semaphore done = new Semaphore(0);
  /**
   * The Handler post.
   */
  protected final Closeable handler_post;
  /**
   * The Log.
   */
  final MarkdownNotebookOutput log;
  /**
   * The Value.
   */
  protected T value = null;
  /**
   * The Width.
   */
  String width = "100%";
  /**
   * The Form var.
   */
  String formVar = "data";
  private String height1 = "200px";
  private String height2 = "240px";

  /**
   * Instantiates a new String query.
   *
   * @param log the log
   */
  public StringQuery(MarkdownNotebookOutput log) {
    this.log = log;
    FileHTTPD httpd = this.log.getHttpd();
    this.handler_get = httpd.addGET(id, "text/html", out -> {
      try {
        if (done.tryAcquire()) {
          done.release();
          IOUtil.writeString(getRead(), out);
        } else {
          IOUtil.writeString(getWrite(), out);
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
        String text = parms.get(formVar);
        logger.info("Json input: " + text);
        value = fromString(text);
        done.release();
        responseHtml = getRead();
        FileUtils.write(new File(log.getRoot(), id), responseHtml, "UTF-8");
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      byte[] bytes = responseHtml.getBytes();
      return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", new ByteArrayInputStream(bytes), bytes.length);
    });
  }

  /**
   * Gets write.
   *
   * @return the write
   * @throws JsonProcessingException the json processing exception
   */
  protected String getWrite() throws JsonProcessingException {
    return "<html><body style=\"margin: 0;\">" +
        "<form action=\"" + id + "\" method=\"POST\">" +
        "<textarea name=\"" + formVar + "\" style=\"margin: 0px; width: " + width + "; height: " + height1 + ";\">" + getString(value) + "</textarea>" +
        "<br/><input type=\"submit\">" +
        "</form></body></html>";
  }

  private String getRead() throws JsonProcessingException {
    return "<html><body style=\"margin: 0;\">" +
        "<textarea name=\"" + formVar + "\" style=\"margin: 0px; width: " + width + "; height: " + height2 + ";\">" + getString(value) + "</textarea>" +
        "</body></html>";
  }

  /**
   * From string t.
   *
   * @param text the text
   * @return the t
   * @throws IOException the io exception
   */
  protected abstract T fromString(String text) throws IOException;

  /**
   * Gets string.
   *
   * @param value the value
   * @return the string
   * @throws JsonProcessingException the json processing exception
   */
  protected abstract String getString(T value) throws JsonProcessingException;

  /**
   * Print string query.
   *
   * @param initial the initial
   * @return the string query
   */
  public StringQuery<T> print(@Nonnull T initial) {
    value = initial;
    try {
      int textLines = getString(value).split("\n").length;
      int lines = (int) (Math.max(Math.min((textLines), 20), 3) * (200.0 / 12));
      height1 = String.format("%dpx", lines);
      height2 = String.format("%dpx", lines + 40);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
    log.p("<iframe src=" + id + " style=\"margin: 0px; width: 100%; height: " + height2 + ";\"></iframe>");
    return this;
  }

  /**
   * Get t.
   *
   * @return the t
   */
  public T get() {
    try {
      done.acquire();
      done.release();
      return value;
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Get t.
   *
   * @param t the t
   * @param u the u
   * @return the t
   */
  public T get(long t, TimeUnit u) {
    try {
      if (done.tryAcquire(t, u)) {
        done.release();
        return value;
      } else {
        return value;
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

  /**
   * The type Simple string query.
   */
  public static class SimpleStringQuery extends StringQuery<String> {

    /**
     * Instantiates a new Simple string query.
     *
     * @param log the log
     */
    public SimpleStringQuery(MarkdownNotebookOutput log) {
      super(log);
    }

    @Override
    protected String fromString(String text) {
      return text;
    }

    @Override
    protected String getString(String value) {
      return value;
    }
  }
}
