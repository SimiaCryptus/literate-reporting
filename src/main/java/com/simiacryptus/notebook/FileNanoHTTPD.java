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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.simiacryptus.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.*;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The type File nano httpd.
 */
public class FileNanoHTTPD extends NanoHTTPD implements FileHTTPD {
  /**
   * The Log.
   */
  static final Logger log = LoggerFactory.getLogger(FileNanoHTTPD.class);

  /**
   * The Custom getHandlers.
   */
  public final Map<CharSequence, Function<IHTTPSession, Response>> getHandlers = new HashMap<>();
  /**
   * The Post handlers.
   */
  public final Map<CharSequence, Function<IHTTPSession, Response>> postHandlers = new HashMap<>();
  /**
   * The Pool.
   */
  protected final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setDaemon(true).build());
  private final File root;

  /**
   * Instantiates a new File nano httpd.
   *
   * @param root the root
   * @param port the port
   */
  public FileNanoHTTPD(File root, final int port) {
    super(port);
    this.root = root;
  }

  /**
   * Create output stream.
   *
   * @param port     the port
   * @param path     the path
   * @param mimeType the mime type
   * @return the output stream
   * @throws IOException the io exception
   */
  @javax.annotation.Nonnull
  public static FileNanoHTTPD create(final int port, @Nonnull final File path, final String mimeType) throws IOException {
    return new FileNanoHTTPD(path, port).init();
  }

  /**
   * Sync handler function.
   *
   * @param mimeType the mime type
   * @param logic    the logic
   * @return the function
   */
  public static Function<IHTTPSession, Response> handler(
      final String mimeType,
      @Nonnull final Consumer<OutputStream> logic
  ) {
    return session -> {
      try (@javax.annotation.Nonnull ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        logic.accept(out);
        out.flush();
        final byte[] bytes = out.toByteArray();
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mimeType, new ByteArrayInputStream(bytes), bytes.length);
      } catch (@javax.annotation.Nonnull final Throwable e) {
        log.warn("Error handling httprequest", e);
        throw new RuntimeException(e);
      }
    };
  }

  /**
   * Add session handler function.
   *
   * @param path  the path
   * @param value the value
   * @return the function
   */
  @Override
  public Closeable addGET(final CharSequence path, final Function<IHTTPSession, Response> value) {
    Function<IHTTPSession, Response> put = getHandlers.put(path, value);
    return () -> getHandlers.remove(path, put);
  }

  @Override
  public Closeable addPOST(final CharSequence path, final Function<IHTTPSession, Response> value) {
    Function<IHTTPSession, Response> put = postHandlers.put(path, value);
    return () -> postHandlers.remove(path, put);
  }

  /**
   * Add sync handler.
   *
   * @param path     the path
   * @param mimeType the mime type
   * @param logic    the logic
   */
  public Closeable addGET(final CharSequence path, final String mimeType, @Nonnull final Consumer<OutputStream> logic) {
    return addGET(path, FileNanoHTTPD.handler(mimeType, logic));
  }

  /**
   * Init stream nano httpd.
   *
   * @return the stream nano httpd
   * @throws IOException the io exception
   */
  @javax.annotation.Nonnull
  public FileNanoHTTPD init() throws IOException {
    start(30000);
    return this;
  }

  @Override
  public Response serve(final IHTTPSession session) {
    String requestPath = Util.stripPrefix(session.getUri(), "/");
    @javax.annotation.Nonnull final File file = new File(root, requestPath);
    if (session.getMethod() == Method.GET) {
      Comparator<Map.Entry<CharSequence, Function<IHTTPSession, Response>>> objectComparator = Comparator.comparing(x -> x.getKey().length());
      Optional<Function<IHTTPSession, Response>> handler = getHandlers.entrySet().stream()
          .filter(e -> {
            String prefix = e.getKey().toString();
            if (prefix.isEmpty() && requestPath.isEmpty()) return true;
            if (prefix.isEmpty() || requestPath.isEmpty()) return false;
            return requestPath.startsWith(prefix);
          })
          .sorted(objectComparator.reversed())
          .findFirst()
          .map(e -> e.getValue());
      handler.orElse(null);
      if (handler.isPresent()) {
        try {
          return handler.get().apply(session);
        } catch (Throwable e) {
          log.warn("Error requesting " + session.getUri(), e);
          throw new RuntimeException(e);
        }
      } else if (null != file && file.exists() && file.isFile()) {
        try {
          return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, null, new FileInputStream(file), file.length());
        } catch (@javax.annotation.Nonnull final FileNotFoundException e) {
          throw new RuntimeException(e);
        }
      } else {
        log.warn(String.format(
            "Not Found: %s\n\tCurrent Path: %s\n\t%s",
            requestPath,
            root.getAbsolutePath(),
            getHandlers.keySet().stream()
                .map(handlerPath -> "Installed Handler: " + handlerPath)
                .reduce((a, b) -> a + "\n\t" + b).get()
        ));
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
      }
    } else if (session.getMethod() == Method.POST) {
      Optional<Function<IHTTPSession, Response>> handler = this.postHandlers.entrySet().stream()
          .filter(e -> requestPath.startsWith(e.getKey().toString())).findAny()
          .map(e -> e.getValue());
      handler.orElse(null);
      if (handler.isPresent()) {
        try {
          return handler.get().apply(session);
        } catch (Throwable e) {
          log.warn("Error requesting " + session.getUri(), e);
          throw new RuntimeException(e);
        }
      } else {
        log.warn(String.format(
            "Not Found: %s\n\tCurrent Path: %s\n\t%s",
            requestPath,
            root.getAbsolutePath(),
            this.getHandlers.keySet().stream()
                .map(handlerPath -> "Installed Handler: " + handlerPath)
                .reduce((a, b) -> a + "\n\t" + b).get()
        ));
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
      }
    } else {
      return NanoHTTPD.newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "test/plain", "Invalid Method");
    }
  }

}
