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
import com.simiacryptus.ref.lang.ReferenceCountingBase;
import com.simiacryptus.ref.wrappers.RefConsumer;
import com.simiacryptus.ref.wrappers.RefFunction;
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
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CodeUtil {

  public static final String CLASS_SOURCE_INFO_RS = "META-INF/CodeUtil/classSourceInfo.json";
  private static final Logger logger = LoggerFactory.getLogger(CodeUtil.class);
  @Nonnull
  public static File projectRoot = new File(
      System.getProperty("codeRoot", getDefaultProjectRoot()));
  private static String[] CODE_EXTENSIONS = {"scala", "java"};
  private static String[] CODE_ROOTS = {"src/main/java", "src/test/java", "src/main/scala", "src/test/scala"};
  public static Map<String, String> classSourceInfo = getClassSourceInfo();

  public static Map<String, String> getClassSourceInfo() {
    Map<String, String> map = load(CLASS_SOURCE_INFO_RS);
    if (null == map) {
      map = loadClassSourceInfo();
    }
    return map;
  }

  @Nonnull
  private static String getDefaultProjectRoot() {
    if (new File("src").exists())
      return "../..";
    else
      return ".";
  }

  @NotNull
  public static Map<String, String> loadClassSourceInfo() {
    Map<String, String> map = new HashMap<>();
    FileUtils.listFiles(projectRoot, CODE_EXTENSIONS, true)
        .stream()
        .map(File::getAbsoluteFile)
        .distinct()
        .collect(Collectors.groupingBy(x -> gitRoot(x).getAbsoluteFile()))
        .forEach((root, files) -> {
          try {
            URI origin = gitOrigin(root.getCanonicalFile());
            if(null != origin) {
              for (File sourceRoot : findFiles(root, CODE_ROOTS)) {
                for (File file : files) {
                  if (file.getCanonicalPath().startsWith(sourceRoot.getCanonicalPath())) {
                    String filePath = relative(sourceRoot, file).toString().replace('\\', '/');
                    String sourcePath = relative(root, sourceRoot).toString().replace('\\', '/');
                    URI resolve = origin.resolve(sourcePath);
                    if(null != resolve) map.put(filePath, resolve.toString());
                  }
                }
              }
            } else {
              logger.warn("No git origin url for " + root);
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
    //logger.debug("Class Info: " + JsonUtil.toJson(RefUtil.addRef(map)));
    return map;
  }

  @NotNull
  public static Path relative(File src, File file) {
    return src.getAbsoluteFile().toPath().relativize(file.getAbsoluteFile().toPath());
  }

  @org.jetbrains.annotations.Nullable
  public static Map<String, String> load(String path) {
    InputStream resourceAsStream = ClassLoader.getSystemResourceAsStream(path);
    Map<String, String> map = null;
    if (null != resourceAsStream) {
      try {
        map = JsonUtil.getMapper().readValue(IOUtils.toString(resourceAsStream, "UTF-8"),
            HashMap.class);
        logger.debug("Class Info: " + JsonUtil.toJson(map));
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
    return map;
  }

  @Nullable
  public static URI findFile(@Nullable final Class<?> clazz) {
    return CodeUtil.findFile(filename(clazz));
  }

  @org.jetbrains.annotations.Nullable
  public static String filename(@Nullable Class<?> clazz) {
    if (null == clazz)
      return null;
    String name = clazz.getName();
    if (null == name)
      return null;
    final CharSequence path = name.replaceAll("\\.", "/").replaceAll("\\$.*", "");
    return path + ".java";
  }

  @Nonnull
  public static URI findFile(@Nonnull final StackTraceElement callingFrame) {
    return CodeUtil.findFile(filename(callingFrame));
  }

  public static String filename(@Nonnull StackTraceElement callingFrame) {
    String pkg = getPackagePath(callingFrame.getClassName());
    if (!pkg.isEmpty())
      pkg += File.separator;
    return pkg + callingFrame.getFileName();
  }

  @NotNull
  public static String getPackagePath(String className) {
    @Nonnull final CharSequence[] packagePath = className.split("\\.");
    return Arrays.stream(packagePath)
        .limit(packagePath.length - 1)
        .collect(Collectors.joining(File.separator));
  }

  public static URI findFile(final String path) {
    if (null == path) return null;
    URL classpathEntry = ClassLoader.getSystemResource(path);
    if (classpathEntry != null) {
      try {
        logger.debug(String.format("Resolved %s to %s", path, classpathEntry));
        return classpathEntry.toURI();
      } catch (URISyntaxException e) {
        throw Util.throwException(e);
      }
    }
    final File file = findFile(projectRoot, path);
    if (file != null) return file.toURI();
    throw new RuntimeException(String.format("Not Found: %s", path));
  }

  @org.jetbrains.annotations.Nullable
  public static File findFile(File root, @Nonnull String path) {
    @Nonnull File file = new File(root, path);
    if (file.exists()) {
      logger.debug(String.format("Resolved %s to %s", path, file));
      return file;
    }
    for (File child : root.listFiles()) {
      if (child.isDirectory()) {
        file = findFile(child, path);
        if (file != null) return file;
      }
    }
    return null;
  }

  public static List<File> findFiles(File root, @Nonnull String... paths) {
    ArrayList<File> files = new ArrayList<>();
    for (String path : paths) {
      @Nonnull File file = new File(root, path);
      if (file.exists()) {
        logger.debug(String.format("Resolved %s to %s", paths, file));
        files.add(file);
      }
    }
    for (File child : root.listFiles()) {
      if (child.isDirectory()) {
        files.addAll(findFiles(child, paths));
      }
    }
    return files;
  }

  @Nonnull
  public static CharSequence getIndent(@Nonnull final CharSequence txt) {
    @Nonnull final Matcher matcher = Pattern.compile("^\\s+").matcher(txt);
    return matcher.find() ? matcher.group(0) : "";
  }

  public static String getInnerText(@Nonnull final StackTraceElement callingFrame) {

    String[] split = callingFrame.getClassName().split("\\.");
    String fileResource = Arrays.stream(split).limit(split.length - 1).reduce((a, b) -> a + "/" + b).orElse("") + "/"
        + callingFrame.getFileName();
    URL resource = ClassLoader.getSystemResource(fileResource);

    try {
      final List<String> allLines;
      if (null != resource) {
        try {
          allLines = IOUtils.readLines(resource.openStream(), "UTF-8");
          logger.debug(String.format("Resolved %s to %s (%s lines)", callingFrame, resource, allLines.size()));
        } catch (IOException e) {
          throw Util.throwException(e);
        }
      } else {
        @Nonnull final URI file = CodeUtil.findFile(callingFrame);
        allLines = IOUtils.readLines(file.toURL().openStream(), "UTF-8");
        logger.debug(String.format("Resolved %s to %s (%s lines)", callingFrame, file, allLines.size()));
      }

      final int start = callingFrame.getLineNumber() - 1;
      final CharSequence txt = allLines.get(start);
      @Nonnull final CharSequence indent = CodeUtil.getIndent(txt);
      @Nonnull final ArrayList<CharSequence> lines = new ArrayList<>();
      int lineNum = start + 1;
      for (; lineNum < allLines.size() && (CodeUtil.getIndent(allLines.get(lineNum)).length() > indent.length()
          || String.valueOf(allLines.get(lineNum)).trim().isEmpty()); lineNum++) {
        final String line = allLines.get(lineNum);
        lines.add(line.substring(Math.min(indent.length(), line.length())));
      }
      logger.debug(String.format("Selected %s lines (%s to %s) for %s", lines.size(), start, lineNum, callingFrame));
      return lines.stream().collect(Collectors.joining("\n"));
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
      final int classDeclarationLine = IntStream.range(0, lines.size())
          .filter(i -> lines.get(i).contains("class " + clazz.getSimpleName())).findFirst().getAsInt();
      final int firstLine = IntStream.rangeClosed(1, classDeclarationLine).map(i -> classDeclarationLine - i)
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
    String className = callingFrame.getClassName();
    String fileName = callingFrame.getFileName();
    assert fileName != null;
    //String codePath = (language(fileName) + "/" + packagePath(className) + "/" + fileName).replaceAll("//", "/");
    return codeUrl((packagePath(className) + "/" + fileName).replaceAll("//", "/"));
  }

  @Nonnull
  public static CharSequence codeUrl(String codePath) {
    if (classSourceInfo.containsKey(codePath))
      return classSourceInfo.get(codePath) + "/" + codePath;
    return codePath;
  }

  @NotNull
  public static String packagePath(String className) {
    String[] split = className.split("\\.");
    return Arrays.asList(split).subList(0, split.length - 1)
        .stream().reduce((a, b) -> a + "/" + b).orElse("");
  }

  public static String language(String fileName) {
    String[] fileSplit = fileName.split("\\.");
    return fileSplit[fileSplit.length - 1];
  }

  public static LogInterception intercept(@Nonnull NotebookOutput log, String loggerName) {
    AtomicLong counter = new AtomicLong(0);
    return log.subreport("Logs for " + loggerName, sublog -> {
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
              String caption = String.format("Log at %s", date);
              String filename = String.format("%s_%s_%s.log", loggerName, date, index++);
              out = new PrintWriter(sublog.file(filename));
              sublog.p("[%s](etc/%s)", caption, filename);
              sublog.write();
            } catch (Throwable e) {
              throw Util.throwException(e);
            }
            killAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
            remainingOut = 10L * 1024 * 1024;
          }
          String formattedMessage = iLoggingEvent.getFormattedMessage();
          out.println(formattedMessage);
          out.flush();
          int length = formattedMessage.length();
          remainingOut -= length;
          counter.addAndGet(length);
          if (remainingOut < 0 || killAt < System.currentTimeMillis()) {
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
    });
  }

  public static <T> T withRefLeakMonitor(@Nonnull NotebookOutput log, @Nonnull RefFunction<NotebookOutput, T> fn) {
    try (
        LogInterception refLeakLog = intercept(log, ReferenceCountingBase.class.getCanonicalName())) {
      T result = fn.apply(log);
      System.gc();
      Thread.sleep(1000);
      if (refLeakLog.counter.get() != 0)
        throw new AssertionError(String.format("RefLeak logged %d bytes", refLeakLog.counter.get()));
      return result;
    } catch (Exception e) {
      throw Util.throwException(e);
    }
  }

  public static AutoCloseable refLeakMonitor(@Nonnull NotebookOutput log) {
    LogInterception refLeakLog = intercept(log, ReferenceCountingBase.class.getCanonicalName());
    return () -> {
      long bytes = refLeakLog.counter.get();
      refLeakLog.close();
      log.setMetadata("refleak", Long.toString(bytes));
      if (bytes > 0) {
        throw new AssertionError(String.format("RefLeak logged %d bytes", bytes));
      }
    };
  }

  public static void withRefLeakMonitor(@Nonnull NotebookOutput log, @Nonnull RefConsumer<NotebookOutput> fn) {
    try (AutoCloseable refLeakLog = refLeakMonitor(log)) {
      fn.accept(log);
      System.gc();
      Thread.sleep(1000);
    } catch (Exception e) {
      throw Util.throwException(e);
    }
  }

  public static StackTraceElement getCallingFrame(int framesNo) {
    return Thread.currentThread().getStackTrace()[framesNo];
  }

  public static File gitRoot(File file) {
    if (!new File(file, ".git").exists()) {
      File parentFile = file.getParentFile();
      if (null == parentFile) return null;
      return gitRoot(parentFile);
    } else {
      return file;
    }
  }

  public static URI gitOrigin(File absoluteFile) {
    if (!new File(absoluteFile, ".git").exists()) {
      File parentFile = absoluteFile.getParentFile();
      if (null == parentFile) return null;
      return gitOrigin(parentFile).resolve(absoluteFile.getName() + "/");
    }
    try {
      Repository repository = new RepositoryBuilder().setWorkTree(absoluteFile).build();
      StoredConfig config = repository.getConfig();
      String head = repository.resolve("HEAD").toObjectId().getName();
      String remoteUrl = config.getString("remote", "origin", "url");
      return gitRemoteToHTTP(head, remoteUrl);
    } catch (Throwable e) {
      logger.warn("Error querying local git config for " + absoluteFile, e);
      return null;
    }
  }

  @NotNull
  public static URI gitRemoteToHTTP(String head, String remoteUrl) {
    Pattern githubPattern = Pattern.compile("git@github.com:([^/]+)/([^/]+).git");
    Matcher matcher = githubPattern.matcher(remoteUrl);
    if (matcher.matches()) {
      try {
        return new URI("https://github.com/" + matcher.group(1) + "/" + matcher.group(2) + "/tree/" + head + "/");
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    } else {
      throw new RuntimeException("Cannot convert " + remoteUrl + " to HTTP link");
    }
  }

  @NotNull
  private static List<File> childFolders(File projectRoot) {
    File[] files = projectRoot.listFiles();
    if (files == null) {
      logger.info("Not found: " + projectRoot.getAbsolutePath());
      return new ArrayList<>();
    }
    return Arrays.stream(files)
        .filter(file -> file.exists() && file.isDirectory()).collect(Collectors.toList());
  }

  public abstract static class LogInterception implements AutoCloseable {
    public final AtomicLong counter;

    public LogInterception(AtomicLong counter) {
      this.counter = counter;
    }
  }
}
