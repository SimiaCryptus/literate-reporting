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

import com.simiacryptus.util.io.AsyncOutputStream;
import com.simiacryptus.util.io.TeeOutputStream;

import javax.annotation.Nonnull;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Function;

public class StreamNanoHTTPD extends FileNanoHTTPD {
  @javax.annotation.Nonnull
  public final TeeOutputStream dataReciever;
  @javax.annotation.Nonnull
  protected final URI gatewayUri;
  @javax.annotation.Nonnull
  private final File primaryFile;
  private final String mimeType;
  private boolean autobrowse = true;


  public StreamNanoHTTPD(final int port, final String mimeType, final File primaryFile) throws IOException {
    super(primaryFile.getParentFile(), port);
    try {
      gatewayUri = null == primaryFile ? null : new URI(String.format("http://localhost:%s/%s", port, primaryFile.getName()));
    } catch (@javax.annotation.Nonnull final URISyntaxException e) {
      throw new RuntimeException(e);
    }
    this.primaryFile = primaryFile;
    this.mimeType = mimeType;
    dataReciever = null == primaryFile ? null : new TeeOutputStream(new FileOutputStream(primaryFile), true) {
      @Override
      public void close() {
        try {
          Thread.sleep(100);
          StreamNanoHTTPD.this.stop();
        } catch (@javax.annotation.Nonnull final Exception e) {
          e.printStackTrace();
        }
      }
    };
  }

  public StreamNanoHTTPD(final int port) throws IOException {
    this(port, null, null);
  }

  public static Function<IHTTPSession, Response> asyncHandler(@javax.annotation.Nonnull final ExecutorService pool, final String mimeType, @javax.annotation.Nonnull final Consumer<OutputStream> logic, final boolean async) {
    return session -> {
      @javax.annotation.Nonnull final PipedInputStream snk = new PipedInputStream();
      @javax.annotation.Nonnull final Semaphore onComplete = new Semaphore(0);
      pool.submit(() -> {
        try (@javax.annotation.Nonnull OutputStream out = new BufferedOutputStream(new AsyncOutputStream(new PipedOutputStream(snk)))) {
          try {
            logic.accept(out);
          } finally {
            onComplete.release();
          }
        } catch (@javax.annotation.Nonnull final IOException e) {
          throw new RuntimeException(e);
        }
      });
      if (!async) {
        try {
          onComplete.acquire();
        } catch (@javax.annotation.Nonnull final InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
      return NanoHTTPD.newChunkedResponse(Response.Status.OK, mimeType, new BufferedInputStream(snk));
    };
  }

  @Override
  @javax.annotation.Nonnull
  public StreamNanoHTTPD init() throws IOException {
    super.init();
    if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
      new Thread(() -> {
        try {
          Thread.sleep(100);
          if (null != gatewayUri && isAutobrowse()) Desktop.getDesktop().browse(gatewayUri);
        } catch (@javax.annotation.Nonnull final Exception e) {
          e.printStackTrace();
        }
      }).start();
    return this;
  }

  public Closeable addAsyncHandler(final CharSequence path, final String mimeType, @Nonnull final Consumer<OutputStream> logic, final boolean async) {
    return addGET(path, StreamNanoHTTPD.asyncHandler(pool, mimeType, logic, async));
  }

  @Override
  public Response serve(final IHTTPSession session) {
    String requestPath = session.getUri();
    while (requestPath.startsWith("/")) {
      requestPath = requestPath.substring(1);
    }
    if (null != primaryFile && requestPath.equals(primaryFile.getName())) {
      try {
        @javax.annotation.Nonnull final Response response = NanoHTTPD.newChunkedResponse(Response.Status.OK, mimeType, new BufferedInputStream(dataReciever.newInputStream()));
        response.setGzipEncoding(false);
        return response;
      } catch (@javax.annotation.Nonnull final IOException e) {
        throw new RuntimeException(e);
      }
    } else {
      return super.serve(session);
    }
  }

  @Override
  protected boolean useGzipWhenAccepted(final Response r) {
    return false;
  }

  public boolean isAutobrowse() {
    return autobrowse;
  }

  public StreamNanoHTTPD setAutobrowse(boolean autobrowse) {
    this.autobrowse = autobrowse;
    return this;
  }
}
