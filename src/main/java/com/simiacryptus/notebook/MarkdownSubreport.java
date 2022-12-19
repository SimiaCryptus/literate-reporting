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
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.function.Function;

class MarkdownSubreport extends MarkdownNotebookOutput {
  private final MarkdownNotebookOutput parent;

  public MarkdownSubreport(@Nonnull File subreportFile, MarkdownNotebookOutput parent, @Nonnull String displayName, String fileName, @Nullable URI archiveHome) {
    this(subreportFile, parent, displayName, UUID.randomUUID(), fileName, archiveHome);
  }

  public MarkdownSubreport(@Nonnull File subreportFile, MarkdownNotebookOutput parent, @Nonnull String displayName, UUID id, String fileName, @Nullable URI archiveHome) {
    super(subreportFile, false, displayName, fileName, id, -1);
    super.setArchiveHome(archiveHome);
    this.parent = parent;
    setEnableZip(false);
  }

  @Override
  public @Nonnull NotebookOutput setArchiveHome(URI archiveHome) {
    return this;
  }

  @Override
  public FileHTTPD getHttpd() {
    return parent.getHttpd();
  }

  @Override
  public @NotNull JsonObject getMetadata() {
    // Subreports never have metadata
    return new JsonObject();
  }

  @Override
  public <T> T subreport(String displayName, @Nonnull @RefAware Function<NotebookOutput, T> fn) {
    assert null != displayName;
    assert !displayName.isEmpty();
    return subreport(displayName, fn, parent);
  }

  @Override
  public <T> T subreport(String displayName, String fileName, @Nonnull @RefAware Function<NotebookOutput, T> fn) {
    assert null != displayName;
    assert !displayName.isEmpty();
    assert null != fileName;
    assert !fileName.isEmpty();
    return subreport(displayName, fileName, fn, parent);
  }

  @Override
  public Closeable onWrite(Runnable fn) {
    return parent.onWrite(fn);
  }

  @Override
  public void write() throws IOException {
    super.write();
    parent.write();
  }
}
