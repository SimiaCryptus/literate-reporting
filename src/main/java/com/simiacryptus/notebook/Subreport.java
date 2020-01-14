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
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Function;

class Subreport extends MarkdownNotebookOutput {
  private final MarkdownNotebookOutput parent;

  public Subreport(@Nonnull File subreportFile, MarkdownNotebookOutput parent, @Nonnull String reportName) throws FileNotFoundException {
    super(subreportFile, -1, false, reportName, UUID.randomUUID());
    this.parent = parent;
  }

  @Override
  public FileHTTPD getHttpd() {
    return parent.getHttpd();
  }

  @Override
  public File writeZip(final File root, final String baseName) {
    return root;
  }

  @Override
  public <T> T subreport(@Nonnull Function<NotebookOutput, T> fn, String name) {
    String newName = name;
    assert null != newName;
    assert !newName.isEmpty();
    return subreport(newName, fn, parent);
  }

  @Override
  public void onWrite(Runnable fn) {
    parent.onWrite(fn);
  }

  @Override
  public void write() throws IOException {
    super.write();
    parent.write();
  }
}
