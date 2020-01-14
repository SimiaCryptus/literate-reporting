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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Map;

public abstract class StringQuery<T> extends FormQuery<T> {
  @Nonnull
  String formVar = "data";

  public StringQuery(@Nonnull MarkdownNotebookOutput log) {
    super(log);
  }

  @Nonnull
  @Override
  protected String getFormInnerHtml() throws JsonProcessingException {
    return "<textarea name=\"" + formVar + "\" style=\"margin: 0px; width: " + width + "; height: " + height1 + ";\">"
        + toString(getValue()) + "</textarea>";
  }

  @Nullable
  public T valueFromParams(@Nonnull Map<String, String> parms, Map<String, String> files) throws IOException {
    return fromString(parms.get(formVar));
  }

  protected abstract String toString(T value) throws JsonProcessingException;

  @Nullable
  protected abstract T fromString(String text) throws IOException;

  public static class SimpleStringQuery extends StringQuery<String> {

    public SimpleStringQuery(@Nonnull MarkdownNotebookOutput log) {
      super(log);
    }

    @Override
    protected String fromString(String text) {
      return text;
    }

    @Override
    protected String toString(String value) {
      return value;
    }
  }
}
