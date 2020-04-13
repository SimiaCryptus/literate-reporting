/*
 * Copyright (c) 2020 by Andrew Charneski.
 *
 * The author licenses this file to you under the
 * Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.simiacryptus.util.test;

import com.simiacryptus.lang.Settings;
import com.simiacryptus.util.Util;

import java.net.URI;

import static com.simiacryptus.lang.Settings.get;

public class TestSettings implements Settings {
  public static final TestSettings INSTANCE = new TestSettings();
  public final String tag = get("GIT_TAG", "master");
  public final String testRepo = get("TEST_REPO", "H:\\SimiaCryptus\\all-projects\\reports");
  public final URI testArchive = get("TEST_ARCHIVE", Util.getURI("s3://code.simiacrypt.us/tests/"));
  //  public final URI testArchive = get("TEST_ARCHIVE", (URI) null);
  public boolean isInteractive = false;

  private TestSettings() {
  }

}
