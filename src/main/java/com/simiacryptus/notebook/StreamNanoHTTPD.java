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

import com.simiacryptus.ref.wrappers.RefConsumer;
import com.simiacryptus.ref.wrappers.RefString;
import com.simiacryptus.util.ReportingUtil;
import com.simiacryptus.util.Util;
import com.simiacryptus.util.io.AsyncOutputStream;
import com.simiacryptus.util.io.TeeOutputStream;

import javax.annotation.Nonnull;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

public class StreamNanoHTTPD extends FileNanoHTTPD {
  @Nonnull
  public final TeeOutputStream dataReciever;
  @Nonnull
  protected final URI gatewayUri;
  @Nonnull
  private final File primaryFile;
  private final String mimeType;
  private boolean autobrowse = true;

  public StreamNanoHTTPD(final int port, final String mimeType, @Nonnull final File primaryFile) throws IOException {
    super(primaryFile.getParentFile(), port);
    try {
      gatewayUri = new URI(RefString.format("http://localhost:%s/%s", port, primaryFile.getName()));
    } catch (@Nonnull final URISyntaxException e) {
      throw Util.throwException(e);
    }
    this.primaryFile = primaryFile;
    this.mimeType = mimeType;
    dataReciever = new TeeOutputStream(new FileOutputStream(primaryFile), true) {
      @Override
      public void close() {
        try {
          Thread.sleep(100);
          StreamNanoHTTPD.this.stop();
        } catch (@Nonnull final Exception e) {
          e.printStackTrace();
        }
      }
    };
  }

  public StreamNanoHTTPD(final int port) throws IOException {
    this(port, null, null);
  }

  public boolean isAutobrowse() {
    return autobrowse;
  }

  @Nonnull
  public StreamNanoHTTPD setAutobrowse(boolean autobrowse) {
    this.autobrowse = autobrowse;
    return this;
  }

  @Nonnull
  public static Function<IHTTPSession, Response> asyncHandler(@Nonnull final ExecutorService pool,
                                                              final String mimeType, @Nonnull final RefConsumer<OutputStream> logic, final boolean async) {
    return session -> {
      @Nonnull final PipedInputStream snk = new PipedInputStream();
      @Nonnull final Semaphore onComplete = new Semaphore(0);
      pool.submit(() -> {
        try (@Nonnull
             OutputStream out = new BufferedOutputStream(new AsyncOutputStream(new PipedOutputStream(snk)))) {
          try {
            logic.accept(out);
          } finally {
            onComplete.release();
          }
        } catch (@Nonnull final IOException e) {
          throw Util.throwException(e);
        }
      });
      if (!async) {
        try {
          onComplete.acquire();
        } catch (@Nonnull final InterruptedException e) {
          throw Util.throwException(e);
        }
      }
      return NanoHTTPD.newChunkedResponse(Response.Status.OK, mimeType, new BufferedInputStream(snk));
    };
  }

  @Override
  @Nonnull
  public StreamNanoHTTPD init() throws IOException {
    super.init();
    if (ReportingUtil.canBrowse())
      new Thread(() -> {
        try {
          Thread.sleep(100);
          if (isAutobrowse())
            ReportingUtil.browse(gatewayUri);
        } catch (@Nonnull final Exception e) {
          e.printStackTrace();
        }
      }).start();
    return this;
  }

  @Nonnull
  public Closeable addAsyncHandler(final CharSequence path, final String mimeType,
                                   @Nonnull final RefConsumer<OutputStream> logic, final boolean async) {
    return addGET(path, StreamNanoHTTPD.asyncHandler(pool, mimeType, logic, async));
  }

  @Override
  public Response serve(@Nonnull final IHTTPSession session) {
    String requestPath = session.getUri();
    while (requestPath.startsWith("/")) {
      requestPath = requestPath.substring(1);
    }
    if (requestPath.equals(primaryFile.getName())) {
      try {
        @Nonnull final Response response = NanoHTTPD.newChunkedResponse(Response.Status.OK, mimeType,
            new BufferedInputStream(dataReciever.newInputStream()));
        response.setGzipEncoding(false);
        return response;
      } catch (@Nonnull final IOException e) {
        throw Util.throwException(e);
      }
    } else {
      return super.serve(session);
    }
  }

  @Override
  protected boolean useGzipWhenAccepted(final Response r) {
    return false;
  }
}
