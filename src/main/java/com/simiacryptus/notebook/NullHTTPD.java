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

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.OutputStream;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The type Null httpd.
 */
class NullHTTPD implements FileHTTPD {
  @Override
  public Closeable addGET(CharSequence path, Function<NanoHTTPD.IHTTPSession, NanoHTTPD.Response> value) {
    return () -> {
    };
  }

  @Override
  public Closeable addPOST(CharSequence path, Function<NanoHTTPD.IHTTPSession, NanoHTTPD.Response> value) {
    return () -> {
    };
  }

  @Override
  public Closeable addGET(final CharSequence path, final String mimeType, @Nonnull final Consumer<OutputStream> logic) {
    return () -> {
    };
  }
}
