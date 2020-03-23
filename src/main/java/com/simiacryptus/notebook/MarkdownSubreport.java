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

import com.google.gson.JsonObject;
import com.simiacryptus.ref.lang.RefAware;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Function;

class MarkdownSubreport extends MarkdownNotebookOutput {
  private final MarkdownNotebookOutput parent;

  public MarkdownSubreport(@Nonnull File subreportFile, MarkdownNotebookOutput parent, @Nonnull String reportName) throws FileNotFoundException {
    super(subreportFile, false, UUID.randomUUID(), reportName, -1);
    this.parent = parent;
    setEnableZip(false);
  }

  @Override
  public FileHTTPD getHttpd() {
    return parent.getHttpd();
  }

  @Override
  public <T> T subreport(@Nonnull @RefAware Function<NotebookOutput, T> fn, String name) {
    assert null != name;
    assert !name.isEmpty();
    return subreport(name, fn, parent);
  }

  @Override
  public void onWrite(Runnable fn) {
    parent.onWrite(fn);
  }

  @Override
  public void write() throws IOException {
    super.write();
    //parent.write();
  }

  @Override
  public @NotNull JsonObject getMetadata() {
    // Subreports never have metadata
    return new JsonObject();
  }
}
