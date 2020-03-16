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

import com.simiacryptus.lang.TimedResult;
import com.simiacryptus.lang.UncheckedSupplier;
import com.simiacryptus.ref.lang.RefAware;
import com.simiacryptus.ref.lang.RefUtil;
import com.simiacryptus.ref.wrappers.*;
import com.simiacryptus.util.CodeUtil;
import com.simiacryptus.util.ReportingUtil;
import com.simiacryptus.util.Util;
import com.simiacryptus.util.test.SysOutInterceptor;
import com.vladsch.flexmark.ext.escaped.character.EscapedCharacterExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.SubscriptExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.pdf.converter.PdfConverterExtension;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
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

import static com.simiacryptus.util.Util.pathToFile;
import static com.simiacryptus.util.Util.stripPrefix;

public class MarkdownNotebookOutput implements NotebookOutput {
  public static final Random random = new Random();
  private static final Logger logger = LoggerFactory.getLogger(MarkdownNotebookOutput.class);
  @Nonnull
  public static RefMap<String, Object> uploadCache = new RefHashMap<>();
  public static int MAX_OUTPUT = 1024 * 2;
  private static int excerptNumber = 0;
  private static int imageNumber = 0;
  @Nonnull
  private final File root;
  @Nonnull
  private final PrintStream primaryOut;
  private final List<CharSequence> markdownData = new ArrayList<>();
  private final List<Runnable> onComplete = new ArrayList<>();
  private final Map<CharSequence, CharSequence> metadata = new HashMap<>();
  @Nullable
  private final FileNanoHTTPD httpd;
  private final ArrayList<Runnable> onWriteHandlers = new ArrayList<>();
  private final String fileName;
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

  public MarkdownNotebookOutput(@Nonnull final File reportFile, boolean browse, @Nonnull String displayName, @Nonnull String fileName, UUID id, final int httpPort) {
    this.setDisplayName(displayName);
    this.fileName = fileName;
    root = reportFile.getAbsoluteFile();
    root.mkdirs();
    setCurrentHome();
    setArchiveHome(null);
    this.id = id;
    try {
      primaryOut = new PrintStream(new FileOutputStream(getReportFile("md")));
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
    FileNanoHTTPD httpd = httpPort <= 0 ? null : new FileNanoHTTPD(root, httpPort);
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
    logger.info(RefString.format("Serving %s from %s at http://localhost:%d", getDisplayName(), root.getAbsoluteFile(), httpPort));
    if (null != httpd) {
      try {
        httpd.init();
      } catch (Throwable e) {
        logger.warn("Error starting web server", e);
        httpd = null;
      }
    }
    this.httpd = httpd;
    if (browse && !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
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
    setMetadata("root", getRoot().getAbsolutePath());
    setMetadata("display_name", getDisplayName());
    setMetadata("file_name", getFileName());
    setMetadata("id", getId());
    setMetadata("local_root", getRoot().getAbsolutePath());
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
    if (e instanceof RuntimeException && e.getCause() != null && e.getCause() != e)
      return getExceptionString(e.getCause());
    if (e.getCause() != null && e.getCause() != e)
      return e.getClass().getSimpleName() + " / " + getExceptionString(e.getCause());
    return e.getClass().getSimpleName();
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

  @NotNull
  public static String replaceAll(@Nonnull String name, String search, String replace) {
    return name.replaceAll(search, replace);
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

  @Nonnull
  public File getReportFile(final String extension) {
    return new File(getRoot(), getFileName() + "." + extension);
  }

  @Override
  public void close() {
    try {
      primaryOut.close();
      try (@Nonnull
           PrintWriter out = new PrintWriter(new FileOutputStream(getReportFile("md")))) {
        write(out);
      }
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

  public void write(@Nonnull final PrintWriter out) {
    if (!metadata.isEmpty()) {
      if (isGHPage()) {
        out.println("---");
        metadata.forEach((key, value) -> {
          out.println(RefString.format("%s: %s", key, StringEscapeUtils.escapeJava(String.valueOf(value))));
        });
        out.println("---");
      }
      if (null != getMetadataLocation()) {
        writeMetadata(getReportFile("metadata.json"));
        writeMetadata(new File(getMetadataLocation(), id.toString() + ".json"));
      }
    }
    toc.forEach(x1 -> out.println(x1));
    out.print("\n\n");
    markdownData.forEach(x -> out.println(x));
  }

  public void setMetadata(CharSequence key, CharSequence value) {
    if (null == value) {
      metadata.remove(key);
    } else {
      metadata.put(key, value);
    }
  }

  @Override
  public CharSequence getMetadata(CharSequence key) {
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
    MutableDataSet options = new MutableDataSet();
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
    out("# " + anchor(anchorId) + msg);
  }

  @Override
  public void h2(@Nonnull final CharSequence fmt, final Object... args) {
    CharSequence anchorId = anchorId();
    @Nonnull
    CharSequence msg = format(fmt, args);
    toc.add(RefString.format("   1. [%s](#%s)", msg, anchorId));
    out("## " + anchor(anchorId) + fmt, args);
  }

  @Override
  public void h3(@Nonnull final CharSequence fmt, final Object... args) {
    CharSequence anchorId = anchorId();
    @Nonnull
    CharSequence msg = format(fmt, args);
    toc.add(RefString.format("      1. [%s](#%s)", msg, anchorId));
    out("### " + anchor(anchorId) + fmt, args);
  }

  @Nonnull
  @Override
  public String png(@Nullable final BufferedImage rawImage, final CharSequence caption) {
    if (null == rawImage)
      return "";
    @Nonnull final File file = pngFile(rawImage,
        new File(getResourceDir(), getFileName() + "." + ++MarkdownNotebookOutput.imageNumber + ".png"));
    return imageMarkdown(caption, file);
  }

  @Nonnull
  @Override
  public String svg(@Nullable final String rawImage, final CharSequence caption) {
    if (null == rawImage)
      return "";
    @Nonnull final File file = svgFile(rawImage,
        new File(getResourceDir(), getFileName() + "." + ++MarkdownNotebookOutput.imageNumber + ".svg"));
    return anchor(anchorId()) + "[" + caption + "](etc/" + file.getName() + ")";
  }

  @Override
  @Nonnull
  public File svgFile(@Nonnull final String rawImage, @Nonnull final File file) {
    try {
      FileUtils.write(file, rawImage, "UTF-8");
    } catch (IOException e) {
      throw Util.throwException(e);
    }
    return file;
  }

  @Override
  @Nonnull
  public File pngFile(@Nonnull final BufferedImage rawImage, @Nonnull final File file) {
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
    if (null == rawImage)
      return "";
    @Nonnull final File file = jpgFile(rawImage, new File(getResourceDir(), UUID.randomUUID().toString() + ".jpg"));
    return imageMarkdown(caption, file);
  }

  @Nonnull
  public String imageMarkdown(CharSequence caption, @Nonnull File file) {
    return anchor(anchorId()) + "![" + caption + "](etc/" + file.getName() + ")";
  }

  @Override
  @Nonnull
  public File jpgFile(@Nonnull final BufferedImage rawImage, @Nonnull final File file) {
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
    return file;
  }

  @Nullable
  @Override
  @SuppressWarnings("unchecked")
  public <T> T eval(@Nonnull @RefAware final UncheckedSupplier<T> fn, final int maxLog, final int framesNo) {
    try {
      final StackTraceElement callingFrame = Thread.currentThread().getStackTrace()[framesNo];
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
      out(anchor(anchorId()) + "Code from [%s:%s](%s#L%s) executed in %.2f seconds (%.3f gc): ",
          callingFrame.getFileName(), callingFrame.getLineNumber(), CodeUtil.codeUrl(callingFrame),
          callingFrame.getLineNumber(), obj.seconds(), obj.gc_seconds());
      out("```java");
      out("  " + replaceAll(sourceCode, "\n", "\n  "));
      out("```");

      if (!result.log.isEmpty()) {
        CharSequence summary = replaceAll(replaceAll(summarize(result.log, maxLog), "\n", "\n    "), "    ~", "");
        out(anchor(anchorId()) + "Logging: ");
        out("```");
        out("    " + summary);
        out("```");
      }
      out("");

      final Object eval = obj.getResult();
      try {
        if (null != eval) {
          out(anchor(anchorId()) + "Returns: \n");
          String str;
          boolean escape;
          if (eval instanceof Throwable) {
            @Nonnull final ByteArrayOutputStream out = new ByteArrayOutputStream();
            ((Throwable) eval).printStackTrace(new PrintStream(out));
            str = new String(out.toByteArray(), "UTF-8");
            escape = true;//
          } else if (eval instanceof Component) {
            str = png(Util.toImage((Component) eval), "Result");
            escape = false;
          } else if (eval instanceof BufferedImage) {
            str = png((BufferedImage) eval, "Result");
            escape = false;
          } else if (eval instanceof TableOutput) {
            str = ((TableOutput) eval).toMarkdownTable();
            escape = false;
          } else if (eval instanceof double[]) {
            str = RefArrays.toString((double[]) eval);
            escape = false;
          } else if (eval instanceof int[]) {
            str = RefArrays.toString((int[]) eval);
            escape = false;
          } else {
            str = eval.toString();
            escape = true;
          }
          @Nonnull
          String fmt = escape ? "    " + replaceAll(replaceAll(summarize(str, maxLog), "\n", "\n    "), "    ~", "") : str;
          if (escape) {
            out("```");
            out(fmt);
            out("```");
          } else {
            out(fmt);
          }
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
    } catch (@Nonnull final IOException e) {
      throw Util.throwException(e);
    } finally {
      RefUtil.freeRef(fn);
    }
  }

  @Nonnull
  @Override
  public CharSequence link(@Nonnull final File file, final CharSequence text) {
    try {
      return "[" + text + "](" + URLEncoder.encode(pathTo(file).toString(), "UTF-8") + ")";
    } catch (UnsupportedEncodingException e) {
      throw Util.throwException(e);
    }
  }

  @Nonnull
  public CharSequence pathTo(@Nonnull File file) {
    return stripPrefix(Util.toString(pathToFile(getRoot(), file)), "/");
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
  public <T> T subreport(@Nonnull @RefAware Function<NotebookOutput, T> fn, @Nonnull String name) {
    return subreport(name, fn, this);
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

  protected <T> T subreport(@Nonnull String reportName, @Nonnull @RefAware Function<NotebookOutput, T> fn, MarkdownNotebookOutput parent) {
    try {
      File root = getRoot();
      MarkdownNotebookOutput subreport = new MarkdownSubreport(root, parent, reportName);
      subreport.setArchiveHome(getArchiveHome());
      subreport.setMaxImageSize(getMaxImageSize());
      try {

        this.p(this.link(subreport.getReportFile("html"), "Subreport: " + reportName));
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
    } catch (FileNotFoundException e) {
      throw Util.throwException(e);
    } finally {
      RefUtil.freeRef(fn);
    }
  }

  private void writeMetadata(File file) {
    try (PrintStream metadataOut = new PrintStream(new FileOutputStream(file))) {
      metadataOut.print("{\n  ");
      metadataOut.print(metadata.entrySet().stream().map(entry ->
          String.format("\"%s\": \"%s\"", entry.getKey(), StringEscapeUtils.escapeJava(String.valueOf(entry.getValue())))
      ).reduce((a, b) -> a + ",\n  " + b).orElse(""));
      metadataOut.print("\n}");
    } catch (IOException e) {
      throw Util.throwException(e);
    }
  }

  @Nonnull
  private synchronized File writePdf(@Nonnull MutableDataSet options, @Nonnull File htmlFile) throws IOException {
    try (FileOutputStream out = new FileOutputStream(getReportFile("pdf"))) {
      PdfConverterExtension.exportToPdf(out, FileUtils.readFileToString(htmlFile, "UTF-8"),
          htmlFile.getAbsoluteFile().toURI().toString(), options);
    }
    return new File(replaceAll(htmlFile.getPath(), "\\.html$", ".pdf"));
  }

  @Nonnull
  private synchronized File writeHtml(@Nonnull MutableDataSet options) throws IOException {
    List<Extension> extensions = Arrays.asList(TablesExtension.create(), SubscriptExtension.create(),
        EscapedCharacterExtension.create());
    Parser parser = Parser.builder(options).extensions(extensions).build();
    HtmlRenderer renderer = HtmlRenderer.builder(options).extensions(extensions).escapeHtml(false).indentSize(2)
        .softBreak("\n").build();
    String txt = toString(toc) + "\n\n" + toString(markdownData);
    FileUtils.write(getReportFile("md"), txt, "UTF-8");
    File htmlFile = getReportFile("html");
    String html = renderer.render(parser.parse(txt));
    html = "<html><body>" + html + "</body></html>";
    try (FileOutputStream out = new FileOutputStream(htmlFile)) {
      IOUtils.write(html, out, Charset.forName("UTF-8"));
    }
    logger.info("Wrote " + htmlFile); //     log.info("Wrote " + htmlFile); //
    return htmlFile;
  }

}
