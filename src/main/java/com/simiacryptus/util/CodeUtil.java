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
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The type Code util.
 */
public class CodeUtil {

  private static final Logger logger = LoggerFactory.getLogger(CodeUtil.class);

  private static final List<CharSequence> sourceFolders = Arrays.asList("src/main/java", "src/test/java", "src/main/scala", "src/test/scala");
  /**
   * The constant projectRoot.
   */
  @javax.annotation.Nonnull
  public static File projectRoot = new File(System.getProperty("codeRoot", getDefaultProjectRoot()));
  private static final List<File> codeRoots = CodeUtil.scanLocalCodeRoots();
  /**
   * The Class source info.
   */
  public static HashMap<String, String> classSourceInfo = getDefaultClassInfo();

  private static String getDefaultProjectRoot() {
    if (new File("src").exists()) return "..";
    else return ".";
  }

  /**
   * Find file file.
   *
   * @param clazz the clazz
   * @return the file
   */
  @Nullable
  public static URI findFile(@Nullable final Class<?> clazz) {
    if (null == clazz) return null;
    String name = clazz.getName();
    if (null == name) return null;
    final CharSequence path = name.replaceAll("\\.", "/").replaceAll("\\$.*", "");
    return CodeUtil.findFile(path + ".java");
  }

  /**
   * Find file file.
   *
   * @param callingFrame the calling frame
   * @return the file
   */
  @javax.annotation.Nonnull
  public static URI findFile(@Nonnull final StackTraceElement callingFrame) {
    @javax.annotation.Nonnull final CharSequence[] packagePath = callingFrame.getClassName().split("\\.");
    String pkg = Arrays.stream(packagePath).limit(packagePath.length - 1).collect(Collectors.joining(File.separator));
    if (!pkg.isEmpty()) pkg += File.separator;
    @javax.annotation.Nonnull final String path = pkg + callingFrame.getFileName();
    return CodeUtil.findFile(path);
  }

  /**
   * Find file file.
   *
   * @param path the path
   * @return the file
   */
  @javax.annotation.Nonnull
  public static URI findFile(@Nonnull final String path) {
    URL classpathEntry = ClassLoader.getSystemResource(path);
    if (classpathEntry != null) {
      try {
        logger.debug(String.format("Resolved %s to %s", path, classpathEntry));
        return classpathEntry.toURI();
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    }
    for (final File root : CodeUtil.codeRoots) {
      @javax.annotation.Nonnull final File file = new File(root, path);
      if (file.exists()) {
        logger.debug(String.format("Resolved %s to %s", path, file));
        return file.toURI();
      }
    }
    throw new RuntimeException(String.format("Not Found: %s; Project Roots = %s", path, CodeUtil.codeRoots));
  }

  /**
   * Gets indent.
   *
   * @param txt the txt
   * @return the indent
   */
  @javax.annotation.Nonnull
  public static CharSequence getIndent(@javax.annotation.Nonnull final CharSequence txt) {
    @javax.annotation.Nonnull final Matcher matcher = Pattern.compile("^\\s+").matcher(txt);
    return matcher.find() ? matcher.group(0) : "";
  }

  /**
   * Gets heapCopy text.
   *
   * @param callingFrame the calling frame
   * @return the heapCopy text
   */
  public static String getInnerText(@javax.annotation.Nonnull final StackTraceElement callingFrame) {

    String[] split = callingFrame.getClassName().split("\\.");
    String fileResource = Arrays.stream(split).limit(split.length - 1).reduce((a, b) -> a + "/" + b).orElse("") + "/" + callingFrame.getFileName();
    URL resource = ClassLoader.getSystemResource(fileResource);

    try {
      final List<String> allLines;
      if (null != resource) {
        try {
          allLines = IOUtils.readLines(resource.openStream(), "UTF-8");
          logger.debug(String.format("Resolved %s to %s (%s lines)", callingFrame, resource, allLines.size()));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      } else {
        @Nonnull final URI file = CodeUtil.findFile(callingFrame);
        assert null != file;
        allLines = IOUtils.readLines(file.toURL().openStream(), "UTF-8");
        logger.debug(String.format("Resolved %s to %s (%s lines)", callingFrame, file, allLines.size()));
      }

      final int start = callingFrame.getLineNumber() - 1;
      final CharSequence txt = allLines.get(start);
      @javax.annotation.Nonnull final CharSequence indent = CodeUtil.getIndent(txt);
      @javax.annotation.Nonnull final ArrayList<CharSequence> lines = new ArrayList<>();
      int lineNum = start + 1;
      for (; lineNum < allLines.size() && (CodeUtil.getIndent(allLines.get(lineNum)).length() > indent.length() || String.valueOf(allLines.get(lineNum)).trim().isEmpty()); lineNum++) {
        final String line = allLines.get(lineNum);
        lines.add(line.substring(Math.min(indent.length(), line.length())));
      }
      logger.debug(String.format("Selected %s lines (%s to %s) for %s", lines.size(), start, lineNum, callingFrame));
      return lines.stream().collect(Collectors.joining("\n"));
    } catch (@javax.annotation.Nonnull final Throwable e) {
      logger.warn("Error assembling lines", e);
      return "";
    }
  }

  /**
   * Gets javadoc.
   *
   * @param clazz the clazz
   * @return the javadoc
   */
  public static String getJavadoc(@Nullable final Class<?> clazz) {
    try {
      if (null == clazz) return null;
      @Nullable final URI source = CodeUtil.findFile(clazz);
      if (null == source) return clazz.getName() + " not found";
      final List<String> lines = IOUtils.readLines(source.toURL().openStream(), Charset.forName("UTF-8"));
      final int classDeclarationLine = IntStream.range(0, lines.size())
          .filter(i -> lines.get(i).contains("class " + clazz.getSimpleName())).findFirst().getAsInt();
      final int firstLine = IntStream.rangeClosed(1, classDeclarationLine).map(i -> classDeclarationLine - i)
          .filter(i -> !lines.get(i).matches("\\s*[/\\*@].*")).findFirst().orElse(-1) + 1;
      final String javadoc = lines.subList(firstLine, classDeclarationLine).stream()
          .filter(s -> s.matches("\\s*[/\\*].*"))
          .map(s -> s.replaceFirst("^[ \t]*[/\\*]+", "").trim())
          .filter(x -> !x.isEmpty()).reduce((a, b) -> a + "\n" + b).orElse("");
      return javadoc.replaceAll("<p>", "\n");
    } catch (@javax.annotation.Nonnull final Throwable e) {
      e.printStackTrace();
      return "";
    }
  }

  private static List<File> scanLocalCodeRoots() {
    return Stream.concat(
        Stream.of(CodeUtil.projectRoot),
        Arrays.stream(CodeUtil.projectRoot.listFiles())
            .filter(file -> file.exists() && file.isDirectory())
            .collect(Collectors.toList()).stream()).flatMap(x -> scanProject(x).stream())
        .distinct().collect(Collectors.toList());
  }

  private static List<File> scanProject(File file) {
    return sourceFolders.stream().map(name -> new File(file, name.toString()))
        .filter(f -> f.exists() && f.isDirectory())
        .collect(Collectors.toList());
  }

  /**
   * Gets default class info.
   *
   * @return the default class info
   */
  public static HashMap<String, String> getDefaultClassInfo() {
    InputStream resourceAsStream = ClassLoader.getSystemResourceAsStream("META-INF/CodeUtil/classSourceInfo.json");
    if (null != resourceAsStream) {
      try {
        HashMap<String, String> map = JsonUtil.getMapper().readValue(IOUtils.toString(resourceAsStream, "UTF-8"), HashMap.class);
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
    HashMap<String, String> map = new HashMap<>();
    scanLocalCodeRoots().stream().map(f -> f.getParentFile().getParentFile().getParentFile().getAbsoluteFile()).distinct().forEach(root -> {
      String base = getGitBase(root, "");
      if (!base.isEmpty()) {
        File src = new File(root, "src");
        FileUtils.listFiles(src, null, true).forEach(file -> {
          try {
            map.put(src.getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath()).toString().replace('\\', '/'), base);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
      }
    });
    logger.debug("Class Info: " + JsonUtil.toJson(map));
    return map;
  }


  /**
   * Gets git base.
   *
   * @param absoluteFile the absolute file
   * @param def          the def
   * @return the git base
   */
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

  /**
   * Code url char sequence.
   *
   * @param callingFrame the calling frame
   * @return the char sequence
   */
  public static CharSequence codeUrl(StackTraceElement callingFrame) {
    String[] split = callingFrame.getClassName().split("\\.");
    String packagePath = Arrays.asList(split).subList(0, split.length - 1).stream().reduce((a, b) -> a + "/" + b).orElse("");
    String[] fileSplit = callingFrame.getFileName().split("\\.");
    String language = fileSplit[fileSplit.length - 1];
    String codePath = (language + "/" + packagePath + "/" + callingFrame.getFileName()).replaceAll("//", "/");
    if (classSourceInfo.containsKey("main/" + codePath)) return classSourceInfo.get("main/" + codePath) + "main/" + codePath;
    if (classSourceInfo.containsKey("test/" + codePath)) return classSourceInfo.get("test/" + codePath) + "test/" + codePath;
    return codePath;
  }

  public static LogInterception intercept(NotebookOutput log, String loggerName) {
    AtomicLong counter = new AtomicLong(0);
    return log.subreport("log_" + loggerName, sublog -> {
      ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName);
      logger.setLevel(Level.ALL);
      logger.setAdditive(false);
      AppenderBase<ILoggingEvent> appender = new AppenderBase<ILoggingEvent>() {
        PrintWriter out;
        long remainingOut = 0;
        long killAt = 0;

        @Override
        protected synchronized void append(ILoggingEvent iLoggingEvent) {
          if (null == out) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd_HH_mm_ss");
            String date = dateFormat.format(new Date());
            try {
              String caption = String.format("Log at %s", date);
              String filename = String.format("%s_%s.log", loggerName, date);
              out = new PrintWriter(sublog.file(filename));
              sublog.p("[%s](etc/%s)", caption, filename);
              sublog.write();
            } catch (Throwable e) {
              throw new RuntimeException(e);
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

        @Override
        public void stop() {
          if (null != out) {
            out.close();
            out = null;
          }
          super.stop();
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

  public abstract static class LogInterception implements AutoCloseable {
    public final AtomicLong counter;

    public LogInterception(AtomicLong counter) {
      this.counter = counter;
    }
  }
}
