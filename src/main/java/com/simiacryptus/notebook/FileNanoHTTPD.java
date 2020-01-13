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
import com.simiacryptus.ref.lang.RefAware;
import com.simiacryptus.ref.lang.RefUtil;
import com.simiacryptus.ref.wrappers.RefString;
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

public class FileNanoHTTPD extends NanoHTTPD implements FileHTTPD {
  static final Logger log = LoggerFactory.getLogger(FileNanoHTTPD.class);

  public final Map<CharSequence, Function<IHTTPSession, Response>> getHandlers = new HashMap<>();
  public final Map<CharSequence, Function<IHTTPSession, Response>> postHandlers = new HashMap<>();
  protected final ExecutorService pool = Executors
      .newCachedThreadPool(new ThreadFactoryBuilder().setDaemon(true).build());
  private final File root;

  public FileNanoHTTPD(File root, final int port) {
    super(port);
    this.root = root;
  }

  @Nonnull
  public static FileNanoHTTPD create(final int port, @Nonnull final File path, final String mimeType)
      throws IOException {
    return new FileNanoHTTPD(path, port).init();
  }

  public static Function<IHTTPSession, Response> handler(final String mimeType,
      @Nonnull final Consumer<OutputStream> logic) {
    return session -> {
      try (@Nonnull
      ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        logic.accept(out);
        out.flush();
        final byte[] bytes = out.toByteArray();
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mimeType, new ByteArrayInputStream(bytes),
            bytes.length);
      } catch (@Nonnull final Throwable e) {
        log.warn("Error handling httprequest", e);
        throw new RuntimeException(e);
      }
    };
  }

  @Override
  public Closeable addGET(final CharSequence path, final Function<IHTTPSession, Response> value) {
    getHandlers.put(path, value);
    return () -> getHandlers.remove(path, value);
  }

  @Override
  public Closeable addPOST(final CharSequence path, final Function<IHTTPSession, Response> value) {
    Function<IHTTPSession, Response> put = postHandlers.put(path, value);
    return () -> postHandlers.remove(path, put);
  }

  public Closeable addGET(final CharSequence path, final String mimeType, @Nonnull final Consumer<OutputStream> logic) {
    return addGET(path, FileNanoHTTPD.handler(mimeType, logic));
  }

  @Nonnull
  public FileNanoHTTPD init() throws IOException {
    start(30000);
    return this;
  }

  @Override
  public Response serve(final IHTTPSession session) {
    String requestPath = Util.stripPrefix(session.getUri(), "/");
    @Nonnull
    final File file = new File(root, requestPath);
    if (session.getMethod() == Method.GET) {
      Comparator<Map.Entry<CharSequence, Function<IHTTPSession, Response>>> objectComparator = Comparator
          .comparingInt(x -> {
            int temp_02_0001 = x.getKey().length();
            if (null != x)
              RefUtil.freeRef(x);
            return temp_02_0001;
          });
      Optional<Map.Entry<CharSequence, Function<NanoHTTPD.IHTTPSession, NanoHTTPD.Response>>> temp_02_0005 = getHandlers
          .entrySet().stream().filter(e -> {
            String prefix = e.getKey().toString();
            if (null != e)
              RefUtil.freeRef(e);
            if (prefix.isEmpty() && requestPath.isEmpty())
              return true;
            if (prefix.isEmpty() || requestPath.isEmpty())
              return false;
            return requestPath.startsWith(prefix);
          }).sorted(objectComparator.reversed()).findFirst();
      Optional<Function<IHTTPSession, Response>> handler = temp_02_0005.map(e -> {
        Function<NanoHTTPD.IHTTPSession, NanoHTTPD.Response> temp_02_0002 = e.getValue();
        if (null != e)
          RefUtil.freeRef(e);
        return temp_02_0002;
      });
      if (null != temp_02_0005)
        RefUtil.freeRef(temp_02_0005);
      handler.orElse(null);
      if (handler.isPresent()) {
        try {
          return RefUtil.get(handler).apply(session);
        } catch (Throwable e) {
          log.warn("Error requesting " + session.getUri(), e);
          throw new RuntimeException(e);
        }
      } else if (null != file && file.exists() && file.isFile()) {
        try {
          return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, null, new FileInputStream(file), file.length());
        } catch (@Nonnull final FileNotFoundException e) {
          throw new RuntimeException(e);
        }
      } else {
        log.warn(RefString.format("Not Found: %s\n\tCurrent Path: %s\n\t%s", requestPath, root.getAbsolutePath(),
            RefUtil.get(getHandlers.keySet().stream().map(handlerPath -> "Installed Handler: " + handlerPath)
                .reduce((a, b) -> a + "\n\t" + b))));
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
      }
    } else if (session.getMethod() == Method.POST) {
      Optional<Map.Entry<CharSequence, Function<NanoHTTPD.IHTTPSession, NanoHTTPD.Response>>> temp_02_0006 = this.postHandlers
          .entrySet().stream().filter(e -> {
            boolean temp_02_0003 = requestPath.startsWith(e.getKey().toString());
            if (null != e)
              RefUtil.freeRef(e);
            return temp_02_0003;
          }).findAny();
      Optional<Function<IHTTPSession, Response>> handler = temp_02_0006.map(e -> {
        Function<NanoHTTPD.IHTTPSession, NanoHTTPD.Response> temp_02_0004 = e.getValue();
        if (null != e)
          RefUtil.freeRef(e);
        return temp_02_0004;
      });
      if (null != temp_02_0006)
        RefUtil.freeRef(temp_02_0006);
      handler.orElse(null);
      if (handler.isPresent()) {
        try {
          return RefUtil.get(handler).apply(session);
        } catch (Throwable e) {
          log.warn("Error requesting " + session.getUri(), e);
          throw new RuntimeException(e);
        }
      } else {
        log.warn(RefString.format("Not Found: %s\n\tCurrent Path: %s\n\t%s", requestPath, root.getAbsolutePath(),
            RefUtil.get(this.getHandlers.keySet().stream().map(handlerPath -> "Installed Handler: " + handlerPath)
                .reduce((a, b) -> a + "\n\t" + b))));
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
      }
    } else {
      return NanoHTTPD.newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "test/plain", "Invalid Method");
    }
  }

}
