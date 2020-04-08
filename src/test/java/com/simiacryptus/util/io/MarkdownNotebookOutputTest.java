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

package com.simiacryptus.util.io;

import com.simiacryptus.notebook.MarkdownNotebookOutput;
import com.simiacryptus.notebook.NotebookOutput;
import com.simiacryptus.notebook.NotebookOutput.AdmonitionStyle;
import com.simiacryptus.ref.wrappers.RefIntStream;
import com.simiacryptus.ref.wrappers.RefString;
import com.simiacryptus.util.Util;
import com.simiacryptus.util.test.NotebookReportBase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashMap;

public class MarkdownNotebookOutputTest extends NotebookReportBase {

  @Override
  public @Nonnull ReportType getReportType() {
    return ReportType.Components;
  }

  @Override
  protected Class<?> getTargetClass() {
    return MarkdownNotebookOutput.class;
  }

  @Test
  @DisplayName("Noteboook Example")
  public void test() {
    MarkdownNotebookOutput log = getLog();

    log.h1("Code");
    log.eval(() -> {
      System.out.println("This is some output");
      return "This is a STRING return value";
    });
    log.eval("JSON Test", () -> {
      HashMap<String, Object> map = new HashMap<>();
      {
        map.put("foo", "bar");
        map.put("fooz", Arrays.asList("baz", "bat"));
      }
      return map;
    });
    Assertions.assertThrows(RuntimeException.class, () -> {
      log.eval(() -> {
        for (int i = 0; i < 10000; i++) {
          System.out.println("Very, very long output");
        }
        throw new RuntimeException("Test Exception");
      });
    });

    log.h1("Math");
    log.p("Output Message");
    log.p("Here is some math: $`a^2+b^2=c^2`$");
    log.math("a^2+b^2=c^2");

    log.h1("Diagrams");
    // See http://mermaid-js.github.io/mermaid/#/examples
    log.mermaid(
        "graph TD;\n" +
            "  A-->B;\n" +
            "  A-->C;\n" +
            "  B-->D;\n" +
            "  C-->D;");
    log.mermaid(AdmonitionStyle.Example, "With Highlight Style",
        "sequenceDiagram\n" +
            "    Alice ->> Bob: Hello Bob, how are you?\n" +
            "    Bob-->>John: How about you John?\n" +
            "    Bob--x Alice: I am good thanks!\n" +
            "    Bob-x John: I am good thanks!\n" +
            "    Note right of John: Bob thinks a long<br/>long time, so long<br/>that the text does<br/>not fit on a row.\n" +
            "\n" +
            "    Bob-->Alice: Checking with John...\n" +
            "    Alice->John: Yes... John, how are you?");

    log.h1("Boxes");
    // See https://github.com/vsch/flexmark-java/wiki/Admonition-Extension
    log.admonition(AdmonitionStyle.Help, "Flexmark Admonition Extension",
        "[https://github.com/vsch/flexmark-java/wiki/Admonition-Extension](https://github.com/vsch/flexmark-java/wiki/Admonition-Extension)");
    log.admonition(AdmonitionStyle.Example, "Preformatted Text",
        "```\n" +
            "preformatted test\n" +
            "another         line!\n" +
            "```\n");
    log.admonition(AdmonitionStyle.Example, "Code Display",
        "```java\n" +
            "//  This is  _a_  test\n" +
            "System.out.println(\"Testing\");\n" +
            "```\n");
    log.admonition(AdmonitionStyle.Example, "Normal markdown",
        "block content \n" +
            "block content \n" +
            "block content \n");
    log.collapsable(false, AdmonitionStyle.Note, "Collapsed Block",
        "block content \n" +
            "block content \n" +
            "block content \n");
    log.p("Done");
  }

  @Test
  public void testSubreport() {
    NotebookOutput log = getLog();
    RefIntStream.range(0, 10).forEach(i -> {
      log.subreport(String.format("%s (Iteration %d)", log.getDisplayName(), i),
          subreport -> {
            RefIntStream.range(0, 10).forEach(j -> {
              try {
                Thread.sleep(100);
                subreport.p(RefString.format("Iteration: %d / %d", i, j));
              } catch (InterruptedException e) {
                throw Util.throwException(e);
              }
            });
            return null;
          });
    });
  }

}
