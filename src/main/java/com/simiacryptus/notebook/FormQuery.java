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

public abstract class FormQuery<T> extends HtmlQuery<T> {
  protected final String cancelLabel = "Cancel";
  private String submitLabel = "Upload";
  private boolean cancelable = false;

  public FormQuery(@Nonnull NotebookOutput log) {
    super(log);
  }

  @Nonnull
  @Override
  protected String getActiveHtml() throws JsonProcessingException {
    return "<html>" + getHeader() + "<body style=\"margin: 0;\">"
        + "<form action=\"" + id + "\" method=\"POST\" enctype=\"multipart/form-data\">" + getFormInnerHtml() + "<br/>"
        + "<input type=\"submit\" name=\"action\" value=\"" + getSubmitLabel() + "\">"
        + (isCancelable() ? ("<br/>" + "<input type=\"submit\" name=\"action\" value=\"" + cancelLabel + "\">") : "")
        + "</form></body></html>";
  }

  @Override
  protected String getDisplayHtml() throws JsonProcessingException {
    return "<html>" + getHeader() + "<body style=\"margin: 0;\">" + getFormInnerHtml() + "</body></html>";
  }

  protected abstract String getFormInnerHtml() throws JsonProcessingException;

  @Nonnull
  protected String getHeader() {
    return "";
  }

  public String getSubmitLabel() {
    return submitLabel;
  }

  public void setSubmitLabel(String submitLabel) {
    this.submitLabel = submitLabel;
  }

  public boolean isCancelable() {
    return cancelable;
  }

  public void setCancelable(boolean cancelable) {
    this.cancelable = cancelable;
  }
}
