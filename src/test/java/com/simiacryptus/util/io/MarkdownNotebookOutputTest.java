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
import org.junit.Test;

import java.io.File;
import java.util.stream.IntStream;

/**
 * The type Markdown notebook output test.
 */
public class MarkdownNotebookOutputTest {

  /**
   * Test.
   *
   * @throws Exception the exception
   */
  @Test
  public void test() throws Exception {
    try (NotebookOutput notebookOutput = MarkdownNotebookOutput.get(new File("target/report/test.md"), true)) {
      IntStream.range(0, 10).forEach(i -> {
        try {
          Thread.sleep(1000);
          notebookOutput.p("Iteration: " + i);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      });
    }
  }

  /**
   * Test subreport.
   *
   * @throws Exception the exception
   */
  @Test
  public void testSubreport() throws Exception {
    try (NotebookOutput notebookOutput = MarkdownNotebookOutput.get(new File("target/report/testSubreport.md"), true)) {
      IntStream.range(0, 10).forEach(i -> {
        notebookOutput.subreport("Iteration_" + i, subreport -> {
          IntStream.range(0, 10).forEach(j -> {
            try {
              Thread.sleep(100);
              subreport.p(String.format("Iteration: %d / %d", i, j));
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          });
          return null;
        });
      });
    }
  }

}
