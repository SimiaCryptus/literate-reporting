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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simiacryptus.lang.TimedResult;
import com.simiacryptus.lang.UncheckedSupplier;
import com.simiacryptus.ref.lang.RefAware;
import com.simiacryptus.ref.lang.RefUtil;
import com.simiacryptus.ref.wrappers.*;
import com.simiacryptus.util.CodeUtil;
import com.simiacryptus.util.JsonUtil;
import com.simiacryptus.util.ReportingUtil;
import com.simiacryptus.util.Util;
import com.simiacryptus.util.test.SysOutInterceptor;
import com.vladsch.flexmark.ext.admonition.AdmonitionExtension;
import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension;
import com.vladsch.flexmark.ext.escaped.character.EscapedCharacterExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.SubscriptExtension;
import com.vladsch.flexmark.ext.gitlab.GitLabExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.toc.TocExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.pdf.converter.PdfConverterExtension;
import com.vladsch.flexmark.util.data.DataSet;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MarkdownNotebookOutput implements NotebookOutput {
  public static final String ADMONITION_INDENT_DELIMITER = "";//""\u00A0";
  public static final Random random = new Random();
  private static final boolean useAdmonition = false;
  private static final Logger logger = LoggerFactory.getLogger(MarkdownNotebookOutput.class);
  @Nonnull
  public static RefMap<String, Object> uploadCache = new RefHashMap<>();
  public static int MAX_OUTPUT = 1024 * 8;
  private static int excerptNumber = 0;
  private static int imageNumber = 0;
  @Nonnull
  private final File root;
  @Nonnull
  private final PrintStream primaryOut;
  private final List<CharSequence> markdownData = new ArrayList<>();
  private final List<Runnable> onComplete = new ArrayList<>();
  private final Map<CharSequence, JsonElement> metadata = new HashMap<>();
  @Nullable
  private final FileNanoHTTPD httpd;
  private final ArrayList<Runnable> onWriteHandlers = new ArrayList<>();
  private final String fileName;
  private final HashSet<String> headers = new HashSet<>();
  @Nonnull
  public List<CharSequence> toc = new ArrayList<>();
  int anchor = 0;
  UUID id;
  private String displayName;
  private int maxImageSize = 1600;
  @Nullable
  private URI archiveHome = null;
  private boolean enableZip = false;
  private boolean enablePdf = false;
  private boolean ghPage = false;
  private File metadataLocation = null;

  public MarkdownNotebookOutput(@Nonnull final File reportFile, boolean browse) {
    this(reportFile, browse, reportFile.getName());
  }

  public MarkdownNotebookOutput(@Nonnull final File reportFile, boolean browse, String displayName) {
    this(reportFile, browse, displayName, UUID.randomUUID(), random.nextInt(2 * 1024) + 2 * 1024);
  }

  public MarkdownNotebookOutput(@Nonnull final File reportFile, boolean browse, @Nonnull String displayName, UUID id, final int httpPort) {
    this(reportFile, browse, displayName, displayName, id, httpPort);
  }

  public MarkdownNotebookOutput(@Nonnull final File reportFile, boolean browse, UUID id, @Nonnull String displayName, final int httpPort) {
    this(reportFile, browse, displayName, id.toString(), id, httpPort);
  }

  public MarkdownNotebookOutput(@Nonnull final File root, boolean browse, @Nonnull String displayName, @Nonnull String fileName, UUID id, final int httpPort) {
    this.setDisplayName(displayName);
    this.fileName = fileName;
    this.root = root.getAbsoluteFile();
    this.root.mkdirs();
    setCurrentHome();
    setArchiveHome(null);
    this.id = id;
    try {
      primaryOut = new PrintStream(new FileOutputStream(getReportFile("md")));
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
    FileNanoHTTPD httpd = httpPort <= 0 ? null : new FileNanoHTTPD(this.root, httpPort);
    if (null != httpd)
      httpd.addGET("", "text/html", out -> {
        try {
          write();
          try (FileInputStream input = new FileInputStream(getReportFile("html"))) {
            IOUtils.copy(input, out);
          }
        } catch (IOException e) {
          throw Util.throwException(e);
        }
      });
    if (null != httpd)
      httpd.addGET("pdf", "application/pdf", out -> {
        try {
          write();
          try (FileInputStream input = new FileInputStream(getReportFile("pdf"))) {
            IOUtils.copy(input, out);
          }
        } catch (IOException e) {
          throw Util.throwException(e);
        }
      });
    if (null != httpd)
      httpd.addGET("shutdown", "text/plain", out -> {
        try (PrintStream printStream = new PrintStream(out)) {
          printStream.print("Closing...");
          close();
          printStream.print("Done");
        }
        logger.warn("Exiting notebook", new RuntimeException("Stack Trace"));
        RefSystem.exit(0);
      });
    logger.info(RefString.format("Serving %s from %s at http://localhost:%d", getDisplayName(), this.root.getAbsoluteFile(), httpPort));
    if (null != httpd) {
      try {
        httpd.init();
      } catch (Throwable e) {
        logger.warn("Error starting web server", e);
        httpd = null;
      }
    }
    this.httpd = httpd;
    if (browse && ReportingUtil.canBrowse()) {
      if (null != httpd)
        new Thread(() -> {
          try {
            while (!this.httpd.isAlive())
              Thread.sleep(100);
            if (ReportingUtil.AUTO_BROWSE_LIVE)
              ReportingUtil.browse(new URI(RefString.format("http://localhost:%d", httpPort)));
          } catch (@Nonnull InterruptedException | IOException | URISyntaxException e) {
            e.printStackTrace();
          }
        }).start();
      onComplete(() -> {
        try {
          ReportingUtil.browse(getReportFile("html").toURI());
        } catch (IOException e) {
          throw Util.throwException(e);
        }
      });
    }
    if (null != httpd) {
      onComplete(() -> {
        this.httpd.stop();
      });
    }
    //setMetadata("root", getRoot().getAbsolutePath());
    setMetadata("file_name", getFileName());
    setMetadata("id", getId());
  }

  @Nullable
  @Override
  public URI getArchiveHome() {
    return archiveHome;
  }

  @Override
  public String getDisplayName() {
    return displayName;
  }

  @Override
  public void setDisplayName(@Nonnull String name) {
    setMetadata("display_name", name);
    this.displayName = name;
  }

  public String getFileName() {
    return fileName;
  }

  @Nullable
  public FileHTTPD getHttpd() {
    return null != this.httpd ? httpd : new NullHTTPD();
  }

  @Nonnull
  @Override
  public String getId() {
    return id.toString();
  }

  public int getMaxImageSize() {
    return maxImageSize;
  }

  public int getMaxOutSize() {
    return MAX_OUTPUT;
  }

  @Override
  @NotNull
  public JsonObject getMetadata() {
    JsonObject jsonObject = new JsonObject();
    metadata.forEach((key, value) -> jsonObject.add(key.toString(), value));
    return jsonObject;
  }

  public File getMetadataLocation() {
    return metadataLocation;
  }

  public void setMetadataLocation(File metadataLocation) {
    this.metadataLocation = metadataLocation;
  }

  @Nonnull
  public File getResourceDir() {
    @Nonnull final File etc = new File(getRoot(), "etc").getAbsoluteFile();
    etc.mkdirs();
    return etc;
  }

  @Override
  @Nonnull
  public File getRoot() {
    return root;
  }

  public boolean isEnablePdf() {
    return enablePdf;
  }

  @Nonnull
  public MarkdownNotebookOutput setEnablePdf(boolean enablePdf) {
    this.enablePdf = enablePdf;
    return this;
  }

  public boolean isEnableZip() {
    return enableZip;
  }

  public void setEnableZip(boolean enableZip) {
    this.enableZip = enableZip;
  }

  public boolean isGHPage() {
    return ghPage;
  }

  public void setGhPage(boolean ghPage) {
    this.ghPage = ghPage;
  }

  @Nonnull
  public static MarkdownNotebookOutput get(@Nonnull File path) {
    path.getAbsoluteFile().getParentFile().mkdirs();
    return new MarkdownNotebookOutput(path, true);
  }

  @Nonnull
  public static CharSequence getExceptionString(Throwable e) {
    if (e.getCause() != null && e.getCause() != e) {
      if (e instanceof RuntimeException) {
        return getExceptionString(e.getCause());
      } else {
        return e.getClass().getSimpleName() + " / " + getExceptionString(e.getCause());
      }
    } else {
      return e.getClass().getSimpleName();
    }
  }

  @Nonnull
  public static NotebookOutput get(@Nonnull final String s) {
    return get(new File(s));
  }

  public static String stripPrefixes(String str, String... prefixes) {
    AtomicReference<String> reference = new AtomicReference<>(str);
    while (RefStream.of(prefixes).filter(prefix -> reference.get().startsWith(prefix)).findFirst().isPresent()) {
      reference.set(reference.get().substring(1));
    }
    return reference.get();
  }

  @javax.annotation.Nullable
  public static File writeZip(@Nonnull final File root, final String baseName) throws IOException {
    File zipFile = new File(root, baseName + ".zip");
    logger.info(RefString.format("Archiving %s to %s", root.getAbsolutePath(), zipFile.getAbsolutePath()));
    try (@Nonnull
         ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))) {
      zipArchive(root, root, out,
          file -> !file.getName().equals(baseName + ".zip") && !file.getName().endsWith(".pdf"));
    }
    return zipFile;
  }

  public static void zipArchive(@Nonnull final File root, @Nonnull final File dir, @Nonnull final ZipOutputStream out,
                                @Nonnull final Predicate<? super File> filter) {
    RefArrays.stream(dir.listFiles()).filter(filter).forEach(file -> {
      if (file.isDirectory()) {
        zipArchive(root, file, out, filter);
      } else {
        String absRoot = root.getAbsolutePath();
        String absFile = file.getAbsolutePath();
        String relativeFile = absFile.substring(absRoot.length());
        if (relativeFile.startsWith(File.separator))
          relativeFile = relativeFile.substring(1);
        try {
          out.putNextEntry(new ZipEntry(relativeFile));
          IOUtils.copy(new FileInputStream(file), out);
        } catch (IOException e) {
          throw Util.throwException(e);
        }
      }
    });
  }

  @Override
  public void addHeaderHtml(String html) {
    headers.add(html);
  }

  @Nonnull
  public File getReportFile(final String extension) {
    File file = new File(getRoot(), getFileName() + "." + extension);
    file.getParentFile().mkdirs();
    return file;
  }

  @Override
  public void close() {
    try {
      primaryOut.close();
      File root = getRoot();
      write();
      if (isEnableZip())
        writeZip(root, getFileName());
      onComplete.stream().forEach(fn -> {
        try {
          fn.run();
        } catch (Throwable e) {
          logger.info("Error closing log", e);
        }
      });
    } catch (Throwable e) {
      logger.info("Error closing log", e);
    }
  }

  @Override
  @Nonnull
  public NotebookOutput onComplete(@Nonnull Runnable... tasks) {
    RefArrays.stream(tasks).forEach(e -> onComplete.add(e));
    return this;
  }

  @Nonnull
  public String toString(@Nonnull final List<CharSequence> list) {
    if (list.size() > 0 && list.stream().allMatch(x -> {
      if (x.length() > 1) {
        char c = x.charAt(0);
        return c == ' ' || c == '\t';
      }
      return false;
    })) {
      return toString(
          list.stream().map(x -> x.subSequence(1, x.length()).toString()).collect(Collectors.toList()));
    } else
      return list.stream().reduce((a, b) -> a + "\n" + b).orElse("").toString();
  }

  public void setMetadata(CharSequence key, JsonElement value) {
    if (null == value) {
      metadata.remove(key);
    } else {
      metadata.put(key, value);
    }
  }

  @Override
  public JsonElement getMetadata(CharSequence key) {
    return metadata.get(key);
  }

  @Nonnull
  public CharSequence anchor(CharSequence anchorId) {
    return RefString.format("<a id=\"%s\"></a>", anchorId);
  }

  @Nonnull
  public CharSequence anchorId() {
    return RefString.format("p-%d", anchor++);
  }

  @Override
  public void onWrite(Runnable fn) {
    onWriteHandlers.add(fn);
  }

  @Override
  public void write() throws IOException {
    DataSet options = new MutableDataSet()
        .set(TablesExtension.COLUMN_SPANS, false)
        .set(TablesExtension.APPEND_MISSING_COLUMNS, true)
        .set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
        .set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true)
        .set(Parser.EXTENSIONS, Arrays.asList(
            TablesExtension.create(),
            TocExtension.create(),
            SubscriptExtension.create(),
            EscapedCharacterExtension.create(),
            GitLabExtension.create(),
            AdmonitionExtension.create(),
            AnchorLinkExtension.create()
        ))
        .toImmutable();
    JsonObject metadata = getMetadata();
    if (!metadata.keySet().isEmpty()) {
      Gson gson = new GsonBuilder().setPrettyPrinting().create();
      FileUtils.write(getReportFile("metadata.json"), gson.toJson(metadata), "UTF-8");
    }
    onWriteHandlers.stream().forEach(runnable -> runnable.run());
    File htmlFile = writeHtml(options);
    try {
      if (isEnablePdf())
        writePdf(options, htmlFile);
    } catch (Throwable e) {
      logger.info("Error writing pdf", e);
    }
  }

  @Nonnull
  @Override
  public OutputStream file(@Nonnull final CharSequence name) {
    try {
      return new FileOutputStream(resolveResource(name), false);
    } catch (@Nonnull final FileNotFoundException e) {
      throw Util.throwException(e);
    }
  }

  @Nonnull
  public File resolveResource(@Nonnull final CharSequence name) {
    return new File(getResourceDir(), Util.stripPrefix(name.toString(), "etc/"));
  }

  @Nonnull
  @Override
  public String file(final CharSequence data, final CharSequence caption) {
    return file(data, ++MarkdownNotebookOutput.excerptNumber + ".txt", caption);
  }

  @Nonnull
  @Override
  public CharSequence file(@Nonnull byte[] data, @Nonnull CharSequence filename, CharSequence caption) {
    return file(new String(data, Charset.forName("UTF-8")), filename, caption);
  }

  @Nonnull
  @Override
  public String file(@Nullable final CharSequence data, @Nonnull final CharSequence fileName,
                     final CharSequence caption) {
    try {
      if (null != data) {
        IOUtils.write(data, new FileOutputStream(new File(getResourceDir(), fileName.toString())),
            Charset.forName("UTF-8"));
      }
    } catch (@Nonnull final IOException e) {
      throw Util.throwException(e);
    }
    return "[" + caption + "](etc/" + fileName + ")";
  }

  @Override
  public void h1(@Nonnull final CharSequence fmt, final Object... args) {
    CharSequence anchorId = anchorId();
    @Nonnull
    CharSequence msg = format(fmt, args);
    toc.add(RefString.format("1. [%s](#%s)", msg, anchorId));
    out("# " + anchor(anchorId) + msg + "\n");
  }

  @Override
  public void h2(@Nonnull final CharSequence fmt, final Object... args) {
    CharSequence anchorId = anchorId();
    @Nonnull
    CharSequence msg = format(fmt, args);
    toc.add(RefString.format("   1. [%s](#%s)", msg, anchorId));
    out("## " + anchor(anchorId) + fmt + "\n", args);
  }

  @Override
  public void h3(@Nonnull final CharSequence fmt, final Object... args) {
    CharSequence anchorId = anchorId();
    @Nonnull
    CharSequence msg = format(fmt, args);
    toc.add(RefString.format("      1. [%s](#%s)", msg, anchorId));
    out("### " + anchor(anchorId) + fmt + "\n", args);
  }

  @Nonnull
  @Override
  public String png(@Nullable final BufferedImage rawImage, final CharSequence caption) {
    if (null == rawImage)
      return "";
    @Nonnull final File file = pngFile(rawImage);
    return imageMarkdown(caption, file);
  }

  @Nonnull
  @Override
  public String svg(@Nullable final String rawImage, final CharSequence caption) {
    if (null == rawImage)
      return "";
    @Nonnull final File file = svgFile(rawImage);
    return anchor(anchorId()) + "[" + caption + "](etc/" + file.getName() + ")";
  }

  @Override
  @Nonnull
  public File svgFile(@Nonnull final String rawImage) {
    File file = new File(getResourceDir(), getFileName() + "." + ++MarkdownNotebookOutput.imageNumber + ".svg");
    try {
      FileUtils.write(file, rawImage, "UTF-8");
    } catch (IOException e) {
      throw Util.throwException(e);
    }
    return file;
  }

  @Override
  @Nonnull
  public File pngFile(@Nonnull final BufferedImage rawImage) {
    File file = new File(getResourceDir(), getFileName() + "." + ++MarkdownNotebookOutput.imageNumber + ".png");
    @Nullable final BufferedImage stdImage = Util.maximumSize(rawImage, getMaxImageSize());
    try {
      if (stdImage != rawImage) {
        @Nonnull final String rawName = file.getName().replace(".png", "_raw.png");
        ImageIO.write(rawImage, "png", new File(file.getParent(), rawName));
      }
      assert stdImage != null;
      ImageIO.write(stdImage, "png", file);
    } catch (IOException e) {
      throw Util.throwException(e);
    }
    return file;
  }

  @Nonnull
  @Override
  public String jpg(@Nullable final BufferedImage rawImage, final CharSequence caption) {
    if (null == rawImage) return "";
    return imageMarkdown(caption, jpgFile(rawImage));
  }

  @NotNull
  @Override
  public File jpgFile(@NotNull BufferedImage rawImage) {
    File jpgFile = new File(getResourceDir(), UUID.randomUUID().toString() + ".jpg");
    jpgFile(rawImage, jpgFile);
    return jpgFile;
  }

  @Nonnull
  public String imageMarkdown(CharSequence caption, @Nonnull File file) {
    return anchor(anchorId()) + "![" + caption + "](etc/" + file.getName() + ")";
  }

  public void jpgFile(@Nonnull final BufferedImage rawImage, @Nonnull final File file) {
    @Nullable final BufferedImage stdImage = Util.maximumSize(rawImage, getMaxImageSize());
    if (stdImage != rawImage) {
      try {
        @Nonnull final String rawName = file.getName().replace(".jpg", "_raw.jpg");
        ImageIO.write(rawImage, "jpg", new File(file.getParent(), rawName));
      } catch (IOException e) {
        throw new RuntimeException(
            RefString.format("Error processing image with dims (%d,%d)", rawImage.getWidth(), rawImage.getHeight()), e);
      }
    }
    try {
      assert stdImage != null;
      ImageIO.write(stdImage, "jpg", file);
    } catch (Throwable e) {
      assert stdImage != null;
      logger.warn(RefString.format("Error processing image with dims (%d,%d)", stdImage.getWidth(), stdImage.getHeight()),
          e);
    }
  }

  @Nullable
  @Override
  @SuppressWarnings("unchecked")
  public <T> T eval(String title, @Nonnull @RefAware final UncheckedSupplier<T> fn, final int maxLog, StackTraceElement callingFrame) {
    try {
      final String sourceCode = CodeUtil.getInnerText(callingFrame);
      final SysOutInterceptor.LoggedResult<TimedResult<Object>> result = SysOutInterceptor.withOutput(() -> {
        long priorGcMs = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x -> x.getCollectionTime())
            .sum();
        final long start = RefSystem.nanoTime();
        try {
          @Nullable
          Object result1 = null;
          try {
            result1 = fn.get();
          } catch (@Nonnull final Exception e) {
            throw Util.throwException(e);
          }
          long gcTime = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x -> x.getCollectionTime())
              .sum() - priorGcMs;
          return new TimedResult<Object>(result1, RefSystem.nanoTime() - start, gcTime);
        } catch (@Nonnull final Throwable e) {
          long gcTime = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x -> x.getCollectionTime())
              .sum() - priorGcMs;
          return new TimedResult<Object>(e, RefSystem.nanoTime() - start, gcTime);
        }
      });
      TimedResult<Object> obj = result.getObj();
      String fileName = callingFrame.getFileName();
      String language = language(fileName);
      out(anchor(anchorId()));

      String codeID = fileName + ":" + callingFrame.getLineNumber();
      String codeLink = CodeUtil.codeUrl(callingFrame) + "#L" + callingFrame.getLineNumber();
      String codeTitle = "__[" + (null == title ? codeID : title) + "](" + codeLink + ")__";
      String perfString = String.format(
          " executed in %.2f seconds (%.3f gc): ",
          obj.seconds(), obj.gc_seconds());
      if (useAdmonition) {
        out(codeTitle);
        collapsable(false, AdmonitionStyle.Abstract,
            codeID + perfString,
            "```" + language + "\n  " + ADMONITION_INDENT_DELIMITER + sourceCode.replaceAll("\n", "\n  " + ADMONITION_INDENT_DELIMITER) + "\n```"
        );
      } else {
        out(codeTitle + perfString);
        out("```" + language);
        out("  " + sourceCode.replaceAll("\n", "\n  "));
        out("```");
      }

      if (!result.log.isEmpty()) {
        CharSequence summary = summarize(result.log, maxLog).replaceAll("\n", "\n    ").replaceAll("    ~", "");
        collapsable(false, AdmonitionStyle.Quote, "Logging", "```\n    " + summary + "\n```");
      }

      final Object eval = obj.getResult();
      try {
        if (null != eval) {
          printResult(eval, maxLog);
          out("\n\n");
          if (eval instanceof Throwable) {
            if (eval instanceof RuntimeException) {
              throw (RuntimeException) eval;
            } else {
              throw Util.throwException((Throwable) eval);
            }
          }
        }
      } finally {
        result.freeRef();
        obj.freeRef();
      }
      return (T) eval;
    } finally {
      RefUtil.freeRef(fn);
    }
  }

  public void printResult(Object eval, int maxLog) {
    if (eval instanceof Throwable) {
      collapsable(false, AdmonitionStyle.Failure, ((Throwable) eval).getMessage(), "```\n" + escape(Util.toString((Throwable) eval), maxLog) + "\n    \n```");
    } else {
      final String escape;
      final String str;
      if (eval instanceof Component) {
        str = png(Util.toImage((Component) eval), "Result");
        escape = "";
      } else if (eval instanceof BufferedImage) {
        str = png((BufferedImage) eval, "Result");
        escape = "";
      } else if (eval instanceof TableOutput) {
        str = ((TableOutput) eval).toMarkdownTable();
        escape = "";
      } else if (eval instanceof double[]) {
        str = Arrays.toString((double[]) eval);
        escape = "";
      } else if (eval instanceof int[]) {
        str = Arrays.toString((int[]) eval);
        escape = "";
      } else if (eval instanceof String) {
        str = eval.toString();
        escape = "txt";
      } else if (eval instanceof JsonObject) {
        str = new GsonBuilder().setPrettyPrinting().create().toJson(eval);
        escape = "json";
      } else {
        CharSequence toJson;
        String escape1;
        try {
          toJson = JsonUtil.toJson(eval);
          escape1 = "json";
        } catch (Throwable e) {
          toJson = eval.toString();
          escape1 = "txt";
        }
        str = toJson.toString();
        escape = escape1;
      }
      if (escape.equals("txt")) {
        if (useAdmonition) {
          admonition(AdmonitionStyle.Success, "Returning", "```\n" + escape(str, maxLog) + "\n```");
        } else {
          out("Returns\n```\n" + escape(str, maxLog) + "\n```");
        }
      } else if (escape.equals("json")) {
        if (useAdmonition) {
          admonition(AdmonitionStyle.Success, "Returning", "```json\n" + escape(str, maxLog) + "\n```");
        } else {
          out("Returns\n```json\n" + escape(str, maxLog) + "\n```");
        }
      } else {
        if (useAdmonition) {
          admonition(AdmonitionStyle.Success, "Returning", str);
        } else {
          out("Returns\n\n" + str);
        }
      }
    }
  }

  public String language(String fileName) {
    String[] split = fileName.split("\\.");
    return split[split.length - 1];
  }

  @Nonnull
  @Override
  public CharSequence link(@Nonnull final File file, final CharSequence text) {
    try {
      String relative = pathTo(file).toString();
      if (File.separatorChar != '/') relative = relative.replace(File.separatorChar, '/');
      return "[" + text + "](" + URLEncoder.encode(relative, "UTF-8").replaceAll("%2F", "/") + ")";
    } catch (UnsupportedEncodingException e) {
      throw Util.throwException(e);
    }
  }

  @Nonnull
  public CharSequence pathTo(@Nonnull File file) {
    return Util.pathTo(getRoot(), file);
  }

  @Override
  public void out(@Nonnull final CharSequence fmt, final Object... args) {
    @Nonnull final String msg = format(fmt, args);
    markdownData.add(msg);
    primaryOut.println(msg);
    logger.info(msg);
  }

  @Nonnull
  public String format(@Nonnull CharSequence fmt, @Nonnull Object... args) {
    return 0 == args.length ? fmt.toString() : RefString.format(fmt.toString(), args);
  }

  @Override
  public void p(final CharSequence fmt, final Object... args) {
    out(anchor(anchorId()).toString() + fmt + "\n", args);
  }

  @Nonnull
  public String summarize(@Nonnull String logSrc, final int maxLog) {
    if (logSrc.length() > ((long) maxLog * 2)) {
      @Nonnull final String prefix = logSrc.substring(0, maxLog);
      logSrc = prefix + RefString.format(
          (prefix.endsWith("\n") ? "" : "\n") + "~```\n~..." + file(logSrc, "skipping %s bytes") + "...\n~```\n",
          logSrc.length() - 2 * maxLog) + logSrc.substring(logSrc.length() - maxLog);
    }
    return logSrc;
  }

  @Override
  public <T> T subreport(@Nonnull String displayName, @Nonnull @RefAware Function<NotebookOutput, T> fn) {
    return subreport(displayName, fn, this);
  }

  @Override
  public <T> T subreport(@Nonnull String displayName, String fileName, @Nonnull @RefAware Function<NotebookOutput, T> fn) {
    return subreport(displayName, fileName, fn, this);
  }

  @Nonnull
  public NotebookOutput setMaxImageSize(int maxImageSize) {
    this.maxImageSize = maxImageSize;
    return this;
  }

  @Nonnull
  @Override
  public NotebookOutput setCurrentHome() {
    return this;
  }

  @Nonnull
  @Override
  public NotebookOutput setArchiveHome(URI archiveHome) {
    this.archiveHome = archiveHome;
    setMetadata("archive", null == archiveHome ? null : archiveHome.toString());
    logger.info(RefString.format("Changed archive home to %s", archiveHome));
    return this;
  }

  public @NotNull String escape(String str, int maxLog) {
    return escape(str, maxLog, "    " + ADMONITION_INDENT_DELIMITER);
  }

  @NotNull
  public String escape(String str, int maxLog, String prefix) {
    return prefix + summarize(str, maxLog)
        .replaceAll("\n", "\n" + prefix)
        .replaceAll(prefix + "~", "");
  }

  protected <T> T subreport(@Nonnull String displayName, @Nonnull @RefAware Function<NotebookOutput, T> fn, MarkdownNotebookOutput parent) {
    return subreport(displayName, displayName, fn, parent);
  }

  protected <T> T subreport(@Nonnull String displayName, @Nonnull String fileName, @Nonnull @RefAware Function<NotebookOutput, T> fn, MarkdownNotebookOutput parent) {
    try {
      File root = getRoot();
      MarkdownNotebookOutput subreport = new MarkdownSubreport(root, parent, displayName, fileName);
      subreport.setArchiveHome(getArchiveHome());
      subreport.setMaxImageSize(getMaxImageSize());
      try {
        this.p(this.link(subreport.getReportFile("html"), "Subreport: " + displayName));
        getHttpd().addGET(subreport.getFileName() + ".html", "text/html", out -> {
          try {
            subreport.write();
            try (FileInputStream input = new FileInputStream(subreport.getReportFile("html"))) {
              IOUtils.copy(input, out);
            }
          } catch (IOException e) {
            throw Util.throwException(e);
          }
        });
        try {
          return fn.apply(subreport);
        } catch (Throwable e) {
          return subreport.eval(() -> {
            throw e;
          });
        }
      } finally {
        subreport.close();
      }
    } finally {
      RefUtil.freeRef(fn);
    }
  }

  @Nonnull
  private synchronized File writePdf(DataSet options, @Nonnull File htmlFile) throws IOException {
    try (FileOutputStream out = new FileOutputStream(getReportFile("pdf"))) {
      PdfConverterExtension.exportToPdf(out,
          FileUtils.readFileToString(htmlFile, "UTF-8"),
          htmlFile.getAbsoluteFile().toURI().toString(),
          options);
    }
    return new File(htmlFile.getPath().replaceAll("\\.html$", ".pdf"));
  }

  @Nonnull
  private synchronized File writeHtml(DataSet options) throws IOException {
    Parser parser = Parser.builder(options)
        .build();
    HtmlRenderer renderer = HtmlRenderer.builder(options)
        .escapeHtml(false)
        .indentSize(2)
        .softBreak("\n")
        .build();
    String txt;
    if (true) {
      txt = String.format("%s\n\n%s",
          toString(toc),
          toString(markdownData));
    } else {
      txt = String.format("???+ info \"%s\"\n    %s\n\n%s",
          getDisplayName(),
          toString(toc).replaceAll("\n", "\n    "),
          toString(markdownData));
    }
    FileUtils.write(getReportFile("md"), txt, "UTF-8");
    File htmlFile = getReportFile("html");
    FileUtils.write(new File(getRoot(), "admonition.css"), AdmonitionExtension.getDefaultCSS(), "UTF-8");
    FileUtils.write(new File(getRoot(), "admonition.js"), AdmonitionExtension.getDefaultScript(), "UTF-8");
    String bodyInnerHtml = renderer.render(parser.parse(txt));
    String headerInnerHtml = "<title>" + getDisplayName() + "</title>" +
        // Mermaid:
        "<script src=\"https://cdn.jsdelivr.net/npm/mermaid@8.4.0/dist/mermaid.min.js\"></script>\n" +
        // Katex:
        "<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/katex@0.11.1/dist/katex.min.css\" integrity=\"sha384-zB1R0rpPzHqg7Kpt0Aljp8JPLqbXI3bhnPWROx27a9N0Ll6ZP/+DiW/UqRcLbRjq\" crossorigin=\"anonymous\">\n" +
        "<script defer src=\"https://cdn.jsdelivr.net/npm/katex@0.11.1/dist/katex.min.js\" integrity=\"sha384-y23I5Q6l+B6vatafAwxRu/0oK/79VlbSz7Q9aiSZUvyWYIYsd+qj+o24G5ZU2zJz\" crossorigin=\"anonymous\"></script>\n" +
        "<script defer src=\"https://cdn.jsdelivr.net/npm/katex@0.11.1/dist/contrib/auto-render.min.js\" integrity=\"sha384-kWPLUVMOks5AQFrykwIup5lo0m3iMkkHrD0uJ4H5cjeGihAutqP0yW0J6dpFiVkI\" crossorigin=\"anonymous\" onload=\"renderMathInElement(document.body);\"></script>\n" +
        // Prism:
        "<link href=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.19.0/themes/prism.min.css\" rel=\"stylesheet\" />\n" +
        // Admonition:
        "<link href=\"admonition.css\" rel=\"stylesheet\" />\n" +
        "" + headers.stream().reduce((a, b) -> a + "\n" + b).orElse("");
    String bodyPrefix = "" +
        // Prism:
        "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.19.0/prism.min.js\"></script>\n" +
        "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.19.0/plugins/autoloader/prism-autoloader.min.js\"></script>\n" +
        "";
    String bodySuffix = "\n" +
        // Admonition:
        "<script src=\"admonition.js\"></script>" +
        "";
    bodyInnerHtml = "<html><head>" + headerInnerHtml + "</head><body>" + bodyPrefix + bodyInnerHtml + bodySuffix + "</body></html>";
    try (FileOutputStream out = new FileOutputStream(htmlFile)) {
      IOUtils.write(bodyInnerHtml, out, Charset.forName("UTF-8"));
    }
    logger.info("Wrote " + htmlFile); //     log.info("Wrote " + htmlFile); //
    return htmlFile;
  }

}
