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

import com.simiacryptus.ref.wrappers.RefString;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.Map;

public class UploadImageQuery extends FormQuery<File> {

  private final String key;
  @Nonnull
  String formVar = "data";

  public UploadImageQuery(String key, @Nonnull NotebookOutput log) {
    super(log);
    this.key = key;
  }

  @Nonnull
  @Override
  protected String getFormInnerHtml() {
    File currentValue = getValue();
    if (null != currentValue) {
      return RefString.format("<img src=\"etc/%s\" />", currentValue.getName());
    } else {
      return "<b>" + key + "</b><br/><input type=\"file\" name=\"" + formVar + "\" accept=\"image/*\">";
    }
  }

  @Nonnull
  @Override
  public File valueFromParams(@Nonnull Map<String, String> parms, @Nonnull Map<String, String> files) throws IOException {
    File tmpFile = new File(files.get(formVar));
    File logFile = ((MarkdownNotebookOutput) log).resolveResource(parms.get(formVar));
    FileUtils.copyFile(tmpFile, logFile);
    return logFile;
  }

}
