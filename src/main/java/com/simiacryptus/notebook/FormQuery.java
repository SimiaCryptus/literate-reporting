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

public abstract class FormQuery<T> extends HtmlQuery<T> {
  public FormQuery(MarkdownNotebookOutput log) {
    super(log);
  }

  @Override
  protected String getActiveHtml() throws JsonProcessingException {
    return "<html><body style=\"margin: 0;\">" +
        "<form action=\"" + id + "\" method=\"POST\">" +
        getFormInnerHtml() +
        "<br/><input type=\"submit\">" +
        "</form></body></html>";
  }

  protected abstract String getFormInnerHtml() throws JsonProcessingException;

  @Override
  protected String getDisplayHtml() throws JsonProcessingException {
    return "<html><body style=\"margin: 0;\">" + getFormInnerHtml() + "</body></html>";
  }
}
