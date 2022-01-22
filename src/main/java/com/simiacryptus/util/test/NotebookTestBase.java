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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.simiacryptus.notebook.MarkdownNotebookOutput;
import com.simiacryptus.notebook.NotebookOutput;
import com.simiacryptus.util.CodeUtil;
import com.simiacryptus.util.S3Uploader;
import com.simiacryptus.util.Util;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@ExtendWith(NotebookTestBase.ReportingTestExtension.class)
public abstract class NotebookTestBase {

  public static final Map<File, String> reports = new HashMap<>();
  protected static final Logger logger = LoggerFactory.getLogger(NotebookTestBase.class);

  static {
    SysOutInterceptor.INSTANCE.init();
  }

  private MarkdownNotebookOutput log;

  protected MarkdownNotebookOutput getLog() {
    return log;
  }

  @Nonnull
  public abstract ReportType getReportType();

  protected abstract Class<?> getTargetClass();

  @Nullable
  public static CharSequence setClassData(@Nonnull NotebookOutput log, @Nullable Class<?> networkClass,
                                          final CharSequence prefix) {
    if (null == networkClass)
      return null;
    @Nullable
    String javadoc = CodeUtil.getJavadoc(networkClass);
    assert javadoc != null;
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("simpleName", networkClass.getSimpleName());
    jsonObject.addProperty("canonicalName", networkClass.getCanonicalName());
    String filename = CodeUtil.filename(networkClass);
    if (null != filename) jsonObject.addProperty("link", CodeUtil.codeUrl(filename).toString());
    jsonObject.addProperty("javaDoc", javadoc);
    log.setMetadata(prefix, jsonObject);
    return javadoc;
  }

  @NotNull
  public static String toPathString(@Nonnull Class<?> sourceClass) {
    return toPathString(sourceClass, File.separatorChar);
  }

  @NotNull
  public static String toPathString(@Nonnull Class<?> sourceClass, char separatorChar) {
    return sourceClass.getCanonicalName()
        .replace('.', separatorChar)
        .replace('$', separatorChar);
  }

  public void printHeader(@Nonnull NotebookOutput log, TestInfo testInfo) {
    log.setMetadata("created_on", new JsonPrimitive(System.currentTimeMillis()));
    log.setMetadata("report_type", getReportType().name());
    CharSequence targetDescription = setClassData(log, getTargetClass(), "target");
    if (null != targetDescription && targetDescription.length() > 0) {
      log.p("__Target Description:__ " + targetDescription);
    } else {
      log.p("__Target Class:__ " + getTargetClass().getSimpleName());
    }
    CharSequence reportDescription = setClassData(log, getClass(), "report");
    if (null != reportDescription && reportDescription.length() > 0) {
      log.p("__Report Description:__ " + reportDescription);
    } else {
      log.p("__Report Class:__ " + getClass().getSimpleName());
    }

    String testName = testInfo.getTestMethod().get().getName();
    String displayName = testInfo.getDisplayName();
    log.p("__Test:__ " + displayName + " (via " + testName + ")");
  }

  @AfterEach
  void closeLog() {
    if (null != log) {
      log.close();
      this.log = null;
    }
  }

  @BeforeEach
  void initializeLog(TestInfo testInfo) {
    Class<?> targetClass = getTargetClass();
    String timeId = new SimpleDateFormat("yyyyMMddmmss").format(new Date());
    @Nonnull
    File reportRoot = new File(Util.mkString(File.separator,
        TestSettings.INSTANCE.testRepo,
        toPathString(targetClass),
        testInfo.getTestClass().map(c1 -> c1.getSimpleName()).orElse(""),
        testInfo.getTestMethod().get().getName(),
        timeId
    ));
    reportRoot.mkdirs();
    logger.info(String.format("Output Location: %s", reportRoot.getAbsoluteFile()));
    if (null != log) throw new IllegalStateException();
    log = new MarkdownNotebookOutput(
        reportRoot, true, testInfo.getTestMethod().get().getName()
    );
    String displayName = testInfo.getDisplayName();
    if (displayName != null && !displayName.isEmpty()) {
      log.setDisplayName(displayName);
    }
    reports.put(log.getReportFile("html"), log.getDisplayName());
    log.setEnableZip(false);
    URI testArchive = TestSettings.INSTANCE.testArchive;
    if (null != testArchive) {
      log.setArchiveHome(testArchive.resolve(
          Util.mkString("/",
              toPathString(targetClass, '/'),
              testInfo.getTestClass().map(c -> c.getSimpleName()).orElse(""),
              testInfo.getTestMethod().get().getName(),
              timeId
          )
      ));
    }
    S3Uploader.uploadOnComplete(log);
    File metadataLocation = new File(TestSettings.INSTANCE.testRepo, "registry");
    metadataLocation.mkdirs();
    log.setMetadataLocation(metadataLocation);
    printHeader(this.log, testInfo);
  }

  public enum ReportType {
    Applications, Components, Models, Data, Optimizers, Experiments
  }

  static class ReportingTestExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {
    private static final String START_TIME = "start time";
    private static final String START_GC_TIME = "start gc time";
    private static final String REFLEAK_MONITOR = "refleak monitor";

    public static ExtensionContext.Store getTestStore(ExtensionContext context) {
      return context.getStore(ExtensionContext.Namespace.create(ReportingTestExtension.class, context.getRequiredTestMethod()));
    }

    public static long gcTime() {
      return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x -> x.getCollectionTime()).sum();
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
      ExtensionContext.Store store = getTestStore(context);
      store.put(START_TIME, System.currentTimeMillis());
      store.put(START_GC_TIME, gcTime());
      NotebookTestBase reportingTest = (NotebookTestBase) context.getTestInstance().get();
      store.put(REFLEAK_MONITOR, CodeUtil.refLeakMonitor(reportingTest.getLog()));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
      System.gc();
      logger.info("Total memory after GC: " + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
      ExtensionContext.Store store = getTestStore(context);
      long duration = System.currentTimeMillis() - store.remove(START_TIME, long.class);
      long gcTime = gcTime() - store.remove(START_GC_TIME, long.class);
      NotebookTestBase reportingTest = (NotebookTestBase) context.getTestInstance().get();
      MarkdownNotebookOutput log = reportingTest.getLog();
      JsonObject perfData = new JsonObject();
      perfData.addProperty("execution_time", String.format("%.3f", duration / 1e3));
      perfData.addProperty("gc_time", String.format("%.3f", gcTime / 1e3));
      log.setMetadata("performance", perfData);
      Optional<Throwable> executionException = context.getExecutionException();
      if (executionException.isPresent()) {
        Throwable throwable = executionException.get();
        if (throwable instanceof TestAbortedException) {
          log.setArchiveHome(null);
        } else {
          String string = MarkdownNotebookOutput.getExceptionString(throwable).toString();
          string = string.replaceAll("\n", "<br/>").trim();
          log.setMetadata("result", string);
        }
      } else {
        log.setMetadata("result", "OK");
      }
      log.out(//false, NotebookOutput.AdmonitionStyle.Info, "Metadata",
          "\n\n```json\n  " +
              new GsonBuilder().setPrettyPrinting().create().toJson(log.getMetadata()).replaceAll("\n", "\n  ") +
              "\n```\n\n");
      store.remove(REFLEAK_MONITOR, AutoCloseable.class).close();
    }

  }

}
