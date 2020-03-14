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

package com.simiacryptus.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.simiacryptus.notebook.NotebookOutput;
import com.simiacryptus.ref.lang.RefUtil;
import com.simiacryptus.ref.lang.ReferenceCountingBase;
import com.simiacryptus.ref.wrappers.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CodeUtil {

  private static final Logger logger = LoggerFactory.getLogger(CodeUtil.class);

  private static final List<CharSequence> sourceFolders = Arrays.asList("src/main/java", "src/test/java",
      "src/main/scala", "src/test/scala");
  @Nonnull
  public static File projectRoot = new File(
      RefSystem.getProperty("codeRoot", getDefaultProjectRoot()));
  private static final List<File> codeRoots = CodeUtil.scanLocalCodeRoots();
  public static Map<String, String> classSourceInfo = getDefaultClassInfo();

  public static Map<String, String> getDefaultClassInfo() {
    InputStream resourceAsStream = ClassLoader.getSystemResourceAsStream("META-INF/CodeUtil/classSourceInfo.json");
    if (null != resourceAsStream) {
      try {
        Map<String, String> map = JsonUtil.getMapper().readValue(IOUtils.toString(resourceAsStream, "UTF-8"),
            HashMap.class);
        logger.debug("Class Info: " + JsonUtil.toJson(map));
        return map;
      } catch (Throwable e) {
        logger.warn("Error loading", e);
      } finally {
        try {
          resourceAsStream.close();
        } catch (IOException e) {
          logger.warn("Error closing", e);
        }
      }
    }
    Map<String, String> map = new HashMap<>();
    scanLocalCodeRoots().stream().map(f -> f.getParentFile().getParentFile().getParentFile().getAbsoluteFile())
        .distinct().forEach(root -> {
      String base = getGitBase(root, "");
      if (!base.isEmpty()) {
        File src = new File(root, "src");
        FileUtils.listFiles(src, null, true).forEach(file -> {
          try {
            map.put(src.getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath()).toString()
                .replace('\\', '/'), base);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
      }
    });
    logger.debug("Class Info: " + JsonUtil.toJson(RefUtil.addRef(map)));
    return map;
  }

  @Nonnull
  private static String getDefaultProjectRoot() {
    if (new File("src").exists())
      return "../..";
    else
      return ".";
  }

  @Nullable
  public static URI findFile(@Nullable final Class<?> clazz) {
    if (null == clazz)
      return null;
    String name = clazz.getName();
    if (null == name)
      return null;
    final CharSequence path = name.replaceAll("\\.", "/").replaceAll("\\$.*", "");
    return CodeUtil.findFile(path + ".java");
  }

  @Nonnull
  public static URI findFile(@Nonnull final StackTraceElement callingFrame) {
    @Nonnull final CharSequence[] packagePath = callingFrame.getClassName().split("\\.");
    String pkg = RefArrays.stream(packagePath)
        .limit(packagePath.length - 1)
        .collect(RefCollectors.joining(File.separator));
    if (!pkg.isEmpty())
      pkg += File.separator;
    @Nonnull final String path = pkg + callingFrame.getFileName();
    return CodeUtil.findFile(path);
  }

  @Nonnull
  public static URI findFile(@Nonnull final String path) {
    URL classpathEntry = ClassLoader.getSystemResource(path);
    if (classpathEntry != null) {
      try {
        logger.debug(RefString.format("Resolved %s to %s", path, classpathEntry));
        return classpathEntry.toURI();
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    }
    for (final File root : CodeUtil.codeRoots) {
      @Nonnull final URI file = findFile(path, root);
      if (file != null) return file;
    }
    throw new RuntimeException(RefString.format("Not Found: %s; Project Roots = %s", path, CodeUtil.codeRoots));
  }

  @org.jetbrains.annotations.Nullable
  public static URI findFile(@Nonnull String path, File root) {
    @Nonnull final File file = new File(root, path);
    if (file.exists()) {
      logger.debug(RefString.format("Resolved %s to %s", path, file));
      return file.toURI();
    }
    for (File child : root.listFiles()) {
      if (child.isDirectory()) {
        @Nonnull final URI uri = findFile(path, child);
        if (uri != null) return uri;
      }
    }
    return null;
  }

  @Nonnull
  public static CharSequence getIndent(@Nonnull final CharSequence txt) {
    @Nonnull final Matcher matcher = Pattern.compile("^\\s+").matcher(txt);
    return matcher.find() ? matcher.group(0) : "";
  }

  public static String getInnerText(@Nonnull final StackTraceElement callingFrame) {

    String[] split = callingFrame.getClassName().split("\\.");
    String fileResource = RefArrays.stream(split).limit(split.length - 1).reduce((a, b) -> a + "/" + b).orElse("") + "/"
        + callingFrame.getFileName();
    URL resource = ClassLoader.getSystemResource(fileResource);

    try {
      final List<String> allLines;
      if (null != resource) {
        try {
          allLines = IOUtils.readLines(resource.openStream(), "UTF-8");
          logger.debug(RefString.format("Resolved %s to %s (%s lines)", callingFrame, resource, allLines.size()));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      } else {
        @Nonnull final URI file = CodeUtil.findFile(callingFrame);
        allLines = IOUtils.readLines(file.toURL().openStream(), "UTF-8");
        logger.debug(RefString.format("Resolved %s to %s (%s lines)", callingFrame, file, allLines.size()));
      }

      final int start = callingFrame.getLineNumber() - 1;
      final CharSequence txt = allLines.get(start);
      @Nonnull final CharSequence indent = CodeUtil.getIndent(txt);
      @Nonnull final RefArrayList<CharSequence> lines = new RefArrayList<>();
      int lineNum = start + 1;
      for (; lineNum < allLines.size() && (CodeUtil.getIndent(allLines.get(lineNum)).length() > indent.length()
          || String.valueOf(allLines.get(lineNum)).trim().isEmpty()); lineNum++) {
        final String line = allLines.get(lineNum);
        lines.add(line.substring(Math.min(indent.length(), line.length())));
      }
      logger.debug(RefString.format("Selected %s lines (%s to %s) for %s", lines.size(), start, lineNum, callingFrame));
      String temp_00_0001 = lines.stream().collect(RefCollectors.joining("\n"));
      lines.freeRef();
      return temp_00_0001;
    } catch (@Nonnull final Throwable e) {
      logger.warn("Error assembling lines", e);
      return "";
    }
  }

  @Nullable
  public static String getJavadoc(@Nullable final Class<?> clazz) {
    try {
      if (null == clazz)
        return null;
      @Nullable final URI source = CodeUtil.findFile(clazz);
      if (null == source)
        return clazz.getName() + " not found";
      final List<String> lines = IOUtils.readLines(source.toURL().openStream(), Charset.forName("UTF-8"));
      final int classDeclarationLine = RefIntStream.range(0, lines.size())
          .filter(i -> lines.get(i).contains("class " + clazz.getSimpleName())).findFirst().getAsInt();
      final int firstLine = RefIntStream.rangeClosed(1, classDeclarationLine).map(i -> classDeclarationLine - i)
          .filter(i -> !lines.get(i).matches("\\s*[/\\*@].*")).findFirst().orElse(-1) + 1;
      final String javadoc = lines.subList(firstLine, classDeclarationLine).stream()
          .filter(s -> s.matches("\\s*[/\\*].*")).map(s -> s.replaceFirst("^[ \t]*[/\\*]+", "").trim())
          .filter(x -> !x.isEmpty()).reduce((a, b) -> a + "\n" + b).orElse("");
      return javadoc.replaceAll("<p>", "\n");
    } catch (@Nonnull final Throwable e) {
      e.printStackTrace();
      return "";
    }
  }

  @Nonnull
  public static CharSequence codeUrl(@Nonnull StackTraceElement callingFrame) {
    String[] split = callingFrame.getClassName().split("\\.");
    RefList<String> temp_00_0003 = RefArrays.asList(split);
    RefList<String> temp_00_0004 = temp_00_0003.subList(0, split.length - 1);
    String packagePath = temp_00_0004.stream().reduce((a, b) -> a + "/" + b).orElse("");
    temp_00_0004.freeRef();
    temp_00_0003.freeRef();
    assert callingFrame.getFileName() != null;
    String[] fileSplit = callingFrame.getFileName().split("\\.");
    String language = fileSplit[fileSplit.length - 1];
    String codePath = (language + "/" + packagePath + "/" + callingFrame.getFileName()).replaceAll("//", "/");
    if (classSourceInfo.containsKey("main/" + codePath))
      return classSourceInfo.get("main/" + codePath) + "main/" + codePath;
    if (classSourceInfo.containsKey("test/" + codePath))
      return classSourceInfo.get("test/" + codePath) + "test/" + codePath;
    return codePath;
  }

  public static LogInterception intercept(@Nonnull NotebookOutput log, String loggerName) {
    AtomicLong counter = new AtomicLong(0);
    return log.subreport(sublog -> {
      ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName);
      logger.setLevel(Level.ALL);
      logger.setAdditive(false);
      AppenderBase<ILoggingEvent> appender = new AppenderBase<ILoggingEvent>() {
        @Nullable
        PrintWriter out;
        int index = 0;
        long remainingOut = 0;
        long killAt = 0;

        @Override
        public void stop() {
          if (null != out) {
            out.close();
            out = null;
          }
          super.stop();
        }

        @Override
        protected synchronized void append(@Nonnull ILoggingEvent iLoggingEvent) {
          if (null == out) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd_HH_mm_ss");
            String date = dateFormat.format(new Date());
            try {
              String caption = RefString.format("Log at %s", date);
              String filename = RefString.format("%s_%s_%s.log", loggerName, date, index++);
              out = new PrintWriter(sublog.file(filename));
              sublog.p("[%s](etc/%s)", caption, filename);
              sublog.write();
            } catch (Throwable e) {
              throw new RuntimeException(e);
            }
            killAt = RefSystem.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
            remainingOut = 10L * 1024 * 1024;
          }
          String formattedMessage = iLoggingEvent.getFormattedMessage();
          out.println(formattedMessage);
          out.flush();
          int length = formattedMessage.length();
          remainingOut -= length;
          counter.addAndGet(length);
          if (remainingOut < 0 || killAt < RefSystem.currentTimeMillis()) {
            out.close();
            out = null;
          }
        }
      };
      appender.setName(UUID.randomUUID().toString());
      appender.start();
      logger.addAppender(appender);
      return new LogInterception(counter) {
        @Override
        public void close() {
          logger.detachAppender(appender);
          appender.stop();
        }
      };
    }, log.getName() + "_" + "log_" + loggerName);
  }

  public static <T> T withRefLeakMonitor(@Nonnull NotebookOutput log, @Nonnull RefFunction<NotebookOutput, T> fn) {
    try (
        LogInterception refLeakLog = intercept(log, ReferenceCountingBase.class.getCanonicalName())) {
      T result = fn.apply(log);
      RefSystem.gc();
      Thread.sleep(1000);
      if (refLeakLog.counter.get() != 0)
        throw new AssertionError(RefString.format("RefLeak logged %d bytes", refLeakLog.counter.get()));
      return result;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void withRefLeakMonitor(@Nonnull NotebookOutput log, @Nonnull RefConsumer<NotebookOutput> fn) {
    try (
        LogInterception refLeakLog = intercept(log, ReferenceCountingBase.class.getCanonicalName())) {
      fn.accept(log);
      RefSystem.gc();
      Thread.sleep(1000);
      if (refLeakLog.counter.get() != 0)
        throw new AssertionError(RefString.format("RefLeak logged %d bytes", refLeakLog.counter.get()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected static String getGitBase(File absoluteFile, String def) {
    try {
      Repository repository = new RepositoryBuilder().setWorkTree(absoluteFile).build();
      StoredConfig config = repository.getConfig();
      String head = repository.resolve("HEAD").toObjectId().getName();
      String remoteUrl = config.getString("remote", "origin", "url");
      Pattern githubPattern = Pattern.compile("git@github.com:([^/]+)/([^/]+).git");
      Matcher matcher = githubPattern.matcher(remoteUrl);
      if (matcher.matches()) {
        return "https://github.com/" + matcher.group(1) + "/" + matcher.group(2) + "/tree/" + head + "/src/";
      }
    } catch (Throwable e) {
      logger.debug("Error querying local git config for " + absoluteFile, e);
    }
    return def;
  }

  private static List<File> scanLocalCodeRoots() {
    File projectRoot = CodeUtil.projectRoot;
    if (null == projectRoot) throw new IllegalStateException();
    return Stream.of(
        Stream.of(projectRoot),
        childFolders(projectRoot).stream(),
        childFolders(projectRoot).stream().flatMap(x -> childFolders(x).stream())
    ).reduce((a, b) -> Stream.concat(a, b)).get()
        .flatMap(x -> scanProject(x).stream())
        .distinct().collect(Collectors.toList());
  }

  @NotNull
  private static List<File> childFolders(File projectRoot) {
    File[] files = projectRoot.listFiles();
    if(files == null) {
      logger.info("Not found: " + projectRoot.getAbsolutePath());
      return new ArrayList<>();
    }
    return Arrays.stream(files)
        .filter(file -> file.exists() && file.isDirectory()).collect(Collectors.toList());
  }

  private static List<File> scanProject(File file) {
    return sourceFolders.stream().map(name -> new File(file, name.toString()))
        .filter(f -> f.exists() && f.isDirectory()).collect(Collectors.toList());
  }

  public abstract static class LogInterception implements AutoCloseable {
    public final AtomicLong counter;

    public LogInterception(AtomicLong counter) {
      this.counter = counter;
    }
  }
}
