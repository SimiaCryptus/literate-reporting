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
import com.simiacryptus.util.CodeUtil;
import com.simiacryptus.util.ReportingUtil;
import com.simiacryptus.util.Util;
import com.simiacryptus.util.test.SysOutInterceptor;
import com.vladsch.flexmark.Extension;
import com.vladsch.flexmark.ext.escaped.character.EscapedCharacterExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.SubscriptExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.pdf.converter.PdfConverterExtension;
import com.vladsch.flexmark.util.options.MutableDataSet;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
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
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.simiacryptus.util.Util.pathToFile;
import static com.simiacryptus.util.Util.stripPrefix;

public class MarkdownNotebookOutput implements NotebookOutput {
  public static final Random random = new Random();
  static final Logger log = LoggerFactory.getLogger(MarkdownNotebookOutput.class);
  private static final Logger logger = LoggerFactory.getLogger(MarkdownNotebookOutput.class);
  public static Map<String, Object> uploadCache = new HashMap<>();
  public static int MAX_OUTPUT = 1024 * 2;
  private static int excerptNumber = 0;
  private static int imageNumber = 0;
  @javax.annotation.Nonnull
  private final File root;
  @javax.annotation.Nonnull
  private final PrintStream primaryOut;
  private final List<CharSequence> markdownData = new ArrayList<>();
  private final List<Runnable> onComplete = new ArrayList<>();
  private final Map<CharSequence, CharSequence> frontMatter = new HashMap<>();
  private final FileNanoHTTPD httpd;
  private final ArrayList<Runnable> onWriteHandlers = new ArrayList<>();
  @javax.annotation.Nonnull
  public List<CharSequence> toc = new ArrayList<>();
  int anchor = 0;
  UUID id;
  private String name;
  private int maxImageSize = 1600;
  private URI currentHome = null;
  private URI archiveHome = null;
  private boolean enableZip = false;
  private boolean enablePdf = false;

  public MarkdownNotebookOutput(@Nonnull final File reportFile, boolean browse) throws FileNotFoundException {
    this(reportFile, random.nextInt(2 * 1024) + 2 * 1024, browse, reportFile.getName(), UUID.randomUUID()
    );
  }

  public MarkdownNotebookOutput(
      @Nonnull final File reportFile,
      final int httpPort, boolean browse, String name, UUID id
  ) throws FileNotFoundException {
    this.setName(name);
    root = reportFile.getAbsoluteFile();
    root.mkdirs();
    setCurrentHome(root.toURI());
    setArchiveHome(null);
    this.id = id;
    primaryOut = new PrintStream(new FileOutputStream(new File(root, this.id + ".md")));
    FileNanoHTTPD httpd = httpPort <= 0 ? null : new FileNanoHTTPD(root, httpPort);
    if (null != httpd) httpd.addGET("", "text/html", out -> {
      try {
        write();
        try (FileInputStream input = new FileInputStream(new File(getRoot(), this.id + ".html"))) {
          IOUtils.copy(input, out);
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    if (null != httpd) httpd.addGET("pdf", "application/pdf", out -> {
      try {
        write();
        try (FileInputStream input = new FileInputStream(new File(getRoot(), this.id + ".pdf"))) {
          IOUtils.copy(input, out);
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    if (null != httpd) httpd.addGET("shutdown", "text/plain", out -> {
      try (PrintStream printStream = new PrintStream(out)) {
        printStream.print("Closing...");
        close();
        printStream.print("Done");
      }
      logger.warn("Exiting notebook", new RuntimeException("Stack Trace"));
      System.exit(0);
    });
    log.info(String.format("Serving %s/%s at http://localhost:%d", root.getAbsoluteFile(), getName(), httpPort));
    if (null != httpd) {
      try {
        httpd.init();
      } catch (Throwable e) {
        log.warn("Error starting web server", e);
        httpd = null;
      }
    }
    this.httpd = httpd;
    if (browse && !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
      if (null != httpd) new Thread(() -> {
        try {
          while (!this.httpd.isAlive()) Thread.sleep(100);
          if (ReportingUtil.AUTO_BROWSE_LIVE) ReportingUtil.browse(new URI(String.format("http://localhost:%d", httpPort)));
        } catch (InterruptedException | IOException | URISyntaxException e) {
          e.printStackTrace();
        }
      }).start();
      onComplete(() -> {
        try {
          ReportingUtil.browse(new File(getRoot(), getId() + ".html").toURI());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
    }
    if (null != httpd) onComplete(() -> {
      this.httpd.stop();
    });
  }

  public static Consumer<NotebookOutput> wrapFrontmatter(@Nonnull final Consumer<NotebookOutput> fn) {
    return log -> {
      @Nonnull TimedResult<Void> time = TimedResult.time(() -> {
        try {
          fn.accept(log);
          log.setFrontMatterProperty("result", "OK");
        } catch (Throwable e) {
          log.setFrontMatterProperty("result", getExceptionString(e).toString().replaceAll("\n", "<br/>").trim());
          throw (RuntimeException) (e instanceof RuntimeException ? e : new RuntimeException(e));
        }
      });
      log.setFrontMatterProperty("execution_time", String.format("%.6f", time.timeNanos / 1e9));
    };
  }

  public static NotebookOutput get(File path) {
    try {
      StackTraceElement callingFrame = Thread.currentThread().getStackTrace()[2];
      String methodName = callingFrame.getMethodName();
      path.getAbsoluteFile().getParentFile().mkdirs();
      return new MarkdownNotebookOutput(new File(path, methodName), true);
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @Nonnull
  public static CharSequence getExceptionString(Throwable e) {
    if (e instanceof RuntimeException && e.getCause() != null && e.getCause() != e)
      return getExceptionString(e.getCause());
    if (e.getCause() != null && e.getCause() != e)
      return e.getClass().getSimpleName() + " / " + getExceptionString(e.getCause());
    return e.getClass().getSimpleName();
  }

  public static NotebookOutput get(final String s) {
    return get(new File(s));
  }

  public static String stripPrefixes(String str, String... prefixes) {
    AtomicReference<String> reference = new AtomicReference<>(str);
    while (Stream.of(prefixes).filter(reference.get()::startsWith).findFirst().isPresent()) {
      reference.set(reference.get().substring(1));
    }
    return reference.get();
  }

  @Override
  public void close() {
    try {
      if (null != primaryOut) {
        primaryOut.close();
      }
      try (@javax.annotation.Nonnull PrintWriter out = new PrintWriter(new FileOutputStream(getReportFile()))) {
        write(out);
      }
      File root = getRoot();
      write();
      if (isEnableZip()) writeZip(root, getName().toString());
      onComplete.stream().forEach(fn -> {
        try {
          fn.run();
        } catch (Throwable e) {
          log.info("Error closing log", e);
        }
      });
    } catch (Throwable e) {
      log.info("Error closing log", e);
    }
  }

  @Override
  @Nonnull
  public File getRoot() {
    return root;
  }

  public File writeZip(final File root, final String baseName) throws IOException {
    File zipFile = new File(root, baseName + ".zip");
    logger.info(String.format("Archiving %s to %s", root.getAbsolutePath(), zipFile.getAbsolutePath()));
    try (@Nonnull ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))) {
      zipArchive(root, root, out, file -> !file.getName().equals(baseName + ".zip") && !file.getName().endsWith(".pdf"));
    }
    return zipFile;
  }

  @Override
  @Nonnull
  public NotebookOutput onComplete(Runnable... tasks) {
    Arrays.stream(tasks).forEach(onComplete::add);
    return this;
  }

  @Nonnull
  public String toString(final List<CharSequence> list) {
    if (list.size() > 0 && list.stream().allMatch(x -> {
      if (x.length() > 1) {
        char c = x.charAt(0);
        return c == ' ' || c == '\t';
      }
      return false;
    })) return toString(list.stream().map(x -> x.subSequence(1, x.length()).toString()).collect(Collectors.toList()));
    else return list.stream().reduce((a, b) -> a + "\n" + b).orElse("").toString();
  }

  public void zipArchive(final File root, final File dir, final ZipOutputStream out, final Predicate<? super File> filter) {
    Arrays.stream(dir.listFiles()).filter(filter).forEach(file ->
    {
      if (file.isDirectory()) {
        zipArchive(root, file, out, filter);
      } else {
        String absRoot = root.getAbsolutePath();
        String absFile = file.getAbsolutePath();
        String relativeFile = absFile.substring(absRoot.length());
        if (relativeFile.startsWith(File.separator)) relativeFile = relativeFile.substring(1);
        try {
          out.putNextEntry(new ZipEntry(relativeFile));
          IOUtils.copy(new FileInputStream(file), out);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  public void write(final PrintWriter out) {
    if (!frontMatter.isEmpty()) {
      out.println("---");
      frontMatter.forEach((key, value) -> {
        CharSequence escaped = StringEscapeUtils.escapeJson(String.valueOf(value))
            .replaceAll("\n", " ")
            .replaceAll(":", "&#58;")
            .replaceAll("\\{", "\\{")
            .replaceAll("\\}", "\\}");
        out.println(String.format("%s: %s", key, escaped));
      });
      out.println("---");
    }
    toc.forEach(out::println);
    out.print("\n\n");
    markdownData.forEach(out::println);
  }

  public void setFrontMatterProperty(CharSequence key, CharSequence value) {
    frontMatter.put(key, value);
  }

  @Override
  public CharSequence getFrontMatterProperty(CharSequence key) {
    return frontMatter.get(key);
  }

  @Override
  public String getName() {
    return name;
  }

  public CharSequence anchor(CharSequence anchorId) {
    return String.format("<a id=\"%s\"></a>", anchorId);
  }

  public CharSequence anchorId() {
    return String.format("p-%d", anchor++);
  }

  @Override
  public void onWrite(Runnable fn) {
    onWriteHandlers.add(fn);
  }

  @Override
  public void write() throws IOException {
    MutableDataSet options = new MutableDataSet();
    onWriteHandlers.stream().forEach(Runnable::run);
    File htmlFile = writeHtml(options);
    try {
      if (isEnablePdf()) writePdf(options, htmlFile);
    } catch (Throwable e) {
      logger.info("Error writing pdf", e);
    }
  }

  private synchronized File writePdf(MutableDataSet options, File htmlFile) throws IOException {
    File root = getRoot();
    CharSequence baseName = getName();
    try (FileOutputStream out = new FileOutputStream(new File(root, baseName + ".pdf"))) {
      PdfConverterExtension.exportToPdf(out, FileUtils.readFileToString(htmlFile, "UTF-8"), htmlFile.getAbsoluteFile().toURI().toString(), options);
    }
    return new File(htmlFile.getPath().replaceAll("\\.html$", ".pdf"));
  }

  private synchronized File writeHtml(MutableDataSet options) throws IOException {
    List<Extension> extensions = Arrays.asList(
        TablesExtension.create(),
        SubscriptExtension.create(),
        EscapedCharacterExtension.create()
    );
    Parser parser = Parser.builder(options).extensions(extensions).build();
    HtmlRenderer renderer = HtmlRenderer.builder(options).extensions(extensions).escapeHtml(false).indentSize(2).softBreak("\n").build();
    String txt = toString(toc) + "\n\n" + toString(markdownData);
    FileUtils.write(new File(getRoot(), id + ".md"), txt, "UTF-8");
    File htmlFile = new File(getRoot(), id + ".html");
    String html = renderer.render(parser.parse(txt));
    html = "<html><body>" + html + "</body></html>";
    try (FileOutputStream out = new FileOutputStream(htmlFile)) {
      IOUtils.write(html, out, Charset.forName("UTF-8"));
    }
    log.info("Wrote " + htmlFile); //     log.info("Wrote " + htmlFile); //
    return htmlFile;
  }

  @javax.annotation.Nonnull
  @Override
  public OutputStream file(@javax.annotation.Nonnull final CharSequence name) {
    try {
      return new FileOutputStream(resolveResource(name), false);
    } catch (@javax.annotation.Nonnull final FileNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @Nonnull
  public File resolveResource(@Nonnull final CharSequence name) {
    return new File(getResourceDir(), Util.stripPrefix(name.toString(), "etc/"));
  }

  @javax.annotation.Nonnull
  @Override
  public String file(final CharSequence data, final CharSequence caption) {
    return file(data, ++MarkdownNotebookOutput.excerptNumber + ".txt", caption);
  }

  @javax.annotation.Nonnull
  @Override
  public CharSequence file(@javax.annotation.Nonnull byte[] data, @javax.annotation.Nonnull CharSequence filename, CharSequence caption) {
    return file(new String(data, Charset.forName("UTF-8")), filename, caption);
  }

  @javax.annotation.Nonnull
  @Override
  public String file(@Nullable final CharSequence data, @javax.annotation.Nonnull final CharSequence fileName, final CharSequence caption) {
    try {
      if (null != data) {
        IOUtils.write(data, new FileOutputStream(new File(getResourceDir(), fileName.toString())), Charset.forName("UTF-8"));
      }
    } catch (@javax.annotation.Nonnull final IOException e) {
      throw new RuntimeException(e);
    }
    return "[" + caption + "](etc/" + fileName + ")";
  }

  @javax.annotation.Nonnull
  public File getResourceDir() {
    @javax.annotation.Nonnull final File etc = new File(getReportFile().getParentFile(), "etc").getAbsoluteFile();
    etc.mkdirs();
    return etc;
  }

  @Override
  public void h1(@javax.annotation.Nonnull final CharSequence fmt, final Object... args) {
    CharSequence anchorId = anchorId();
    @javax.annotation.Nonnull CharSequence msg = format(fmt, args);
    toc.add(String.format("1. [%s](#%s)", msg, anchorId));
    out("# " + anchor(anchorId) + msg);
  }

  @Override
  public void h2(@javax.annotation.Nonnull final CharSequence fmt, final Object... args) {
    CharSequence anchorId = anchorId();
    @javax.annotation.Nonnull CharSequence msg = format(fmt, args);
    toc.add(String.format("   1. [%s](#%s)", msg, anchorId));
    out("## " + anchor(anchorId) + fmt, args);
  }

  @Override
  public void h3(@javax.annotation.Nonnull final CharSequence fmt, final Object... args) {
    CharSequence anchorId = anchorId();
    @javax.annotation.Nonnull CharSequence msg = format(fmt, args);
    toc.add(String.format("      1. [%s](#%s)", msg, anchorId));
    out("### " + anchor(anchorId) + fmt, args);
  }

  @javax.annotation.Nonnull
  @Override
  public String png(@Nullable final BufferedImage rawImage, final CharSequence caption) {
    if (null == rawImage) return "";
    @Nonnull final File file = pngFile(rawImage, new File(getResourceDir(), getName() + "." + ++MarkdownNotebookOutput.imageNumber + ".png"));
    return imageMarkdown(caption, file);
  }

  @javax.annotation.Nonnull
  @Override
  public String svg(@Nullable final String rawImage, final CharSequence caption) {
    if (null == rawImage) return "";
    @Nonnull final File file = svgFile(rawImage, new File(getResourceDir(), getName() + "." + ++MarkdownNotebookOutput.imageNumber + ".svg"));
    return anchor(anchorId()) + "[" + caption + "](etc/" + file.getName() + ")";
  }

  @Override
  public String getId() {
    return id.toString();
  }

  @Override
  @Nonnull
  public File svgFile(@Nonnull final String rawImage, final File file) {
    try {
      FileUtils.write(file, rawImage, "UTF-8");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return file;
  }

  @Override
  @Nonnull
  public File pngFile(@Nonnull final BufferedImage rawImage, final File file) {
    @Nullable final BufferedImage stdImage = Util.maximumSize(rawImage, getMaxImageSize());
    try {
      if (stdImage != rawImage) {
        @Nonnull final String rawName = file.getName().replace(".png", "_raw.png");
        ImageIO.write(rawImage, "png", new File(file.getParent(), rawName));
      }
      ImageIO.write(stdImage, "png", file);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return file;
  }

  @javax.annotation.Nonnull
  @Override
  public String jpg(@Nullable final BufferedImage rawImage, final CharSequence caption) {
    if (null == rawImage) return "";
    @Nonnull final File file = jpgFile(rawImage, new File(getResourceDir(), UUID.randomUUID().toString() + ".jpg"));
    return imageMarkdown(caption, file);
  }

  public String imageMarkdown(CharSequence caption, File file) {
    return anchor(anchorId()) + "![" + caption + "](etc/" + file.getName() + ")";
  }

  @Override
  @Nonnull
  public File jpgFile(@Nonnull final BufferedImage rawImage, final File file) {
    @Nullable final BufferedImage stdImage = Util.maximumSize(rawImage, getMaxImageSize());
    if (stdImage != rawImage) {
      try {
        @Nonnull final String rawName = file.getName().replace(".jpg", "_raw.jpg");
        ImageIO.write(rawImage, "jpg", new File(file.getParent(), rawName));
      } catch (IOException e) {
        throw new RuntimeException(String.format("Error processing image with dims (%d,%d)", rawImage.getWidth(), rawImage.getHeight()), e);
      }
    }
    try {
      ImageIO.write(stdImage, "jpg", file);
    } catch (Throwable e) {
      log.warn(String.format("Error processing image with dims (%d,%d)", stdImage.getWidth(), stdImage.getHeight()), e);
    }
    return file;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T eval(@javax.annotation.Nonnull final UncheckedSupplier<T> fn, final int maxLog, final int framesNo) {
    try {
      final StackTraceElement callingFrame = Thread.currentThread().getStackTrace()[framesNo];
      final String sourceCode = CodeUtil.getInnerText(callingFrame);
      @javax.annotation.Nonnull final SysOutInterceptor.LoggedResult<TimedResult<Object>> result = SysOutInterceptor.withOutput(() -> {
        long priorGcMs = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x -> x.getCollectionTime()).sum();
        final long start = System.nanoTime();
        try {
          @Nullable Object result1 = null;
          try {
            result1 = fn.get();
          } catch (@javax.annotation.Nonnull final RuntimeException e) {
            throw e;
          } catch (@javax.annotation.Nonnull final Exception e) {
            throw new RuntimeException(e);
          }
          long gcTime = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x -> x.getCollectionTime()).sum() - priorGcMs;
          return new TimedResult<Object>(result1, System.nanoTime() - start, gcTime);
        } catch (@javax.annotation.Nonnull final Throwable e) {
          long gcTime = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x -> x.getCollectionTime()).sum() - priorGcMs;
          return new TimedResult<Object>(e, System.nanoTime() - start, gcTime);
        }
      });
      out(anchor(anchorId()) + "Code from [%s:%s](%s#L%s) executed in %.2f seconds (%.3f gc): ",
          callingFrame.getFileName(), callingFrame.getLineNumber(),
          CodeUtil.codeUrl(callingFrame), callingFrame.getLineNumber(),
          result.obj.seconds(), result.obj.gc_seconds()
      );
      out("```java");
      out("  " + sourceCode.replaceAll("\n", "\n  "));
      out("```");

      if (!result.log.isEmpty()) {
        CharSequence summary = summarize(result.log, maxLog).replaceAll("\n", "\n    ").replaceAll("    ~", "");
        out(anchor(anchorId()) + "Logging: ");
        out("```");
        out("    " + summary);
        out("```");
      }
      out("");

      final Object eval = result.obj.result;
      if (null != eval) {
        out(anchor(anchorId()) + "Returns: \n");
        String str;
        boolean escape;
        if (eval instanceof Throwable) {
          @javax.annotation.Nonnull final ByteArrayOutputStream out = new ByteArrayOutputStream();
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
          str = Arrays.toString(((double[]) eval));
          escape = false;
        } else if (eval instanceof int[]) {
          str = Arrays.toString(((int[]) eval));
          escape = false;
        } else {
          str = eval.toString();
          escape = true;
        }
        @javax.annotation.Nonnull String fmt = escape ? "    " + summarize(str, maxLog).replaceAll("\n", "\n    ").replaceAll("    ~", "") : str;
        if (escape) {
          out("```");
          out(fmt);
          out("```");
        } else {
          out(fmt);
        }
        out("\n\n");
        if (eval instanceof RuntimeException) {
          throw ((RuntimeException) result.obj.result);
        }
        if (eval instanceof Throwable) {
          throw new RuntimeException((Throwable) result.obj.result);
        }
      }
      return (T) eval;
    } catch (@javax.annotation.Nonnull final IOException e) {
      throw new RuntimeException(e);
    }
  }

  @javax.annotation.Nonnull
  @Override
  public CharSequence link(@javax.annotation.Nonnull final File file, final CharSequence text) {
    try {
      return "[" + text + "](" + URLEncoder.encode(pathTo(file).toString(), "UTF-8") + ")";
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  public CharSequence pathTo(@javax.annotation.Nonnull File file) {
    return stripPrefix(Util.toString(pathToFile(getReportFile(), file)), "/");
  }

  @Override
  public void out(@javax.annotation.Nonnull final CharSequence fmt, final Object... args) {
    @javax.annotation.Nonnull final String msg = format(fmt, args);
    markdownData.add(msg);
    primaryOut.println(msg);
    log.info(msg);
  }

  @javax.annotation.Nonnull
  public String format(@javax.annotation.Nonnull CharSequence fmt, @javax.annotation.Nonnull Object... args) {
    return 0 == args.length ? fmt.toString() : String.format(fmt.toString(), args);
  }

  @Override
  public void p(final CharSequence fmt, final Object... args) {
    out(anchor(anchorId()).toString() + fmt + "\n", args);
  }

  @javax.annotation.Nonnull
  public String summarize(@javax.annotation.Nonnull String logSrc, final int maxLog) {
    if (logSrc.length() > maxLog * 2) {
      @javax.annotation.Nonnull final String prefix = logSrc.substring(0, maxLog);
      logSrc = prefix + String.format(
          (prefix.endsWith("\n") ? "" : "\n") + "~```\n~..." + file(logSrc, "skipping %s bytes") + "...\n~```\n",
          logSrc.length() - 2 * maxLog
      ) + logSrc.substring(logSrc.length() - maxLog);
    }
    return logSrc;
  }

  public int getMaxOutSize() {
    return MAX_OUTPUT;
  }

  public FileHTTPD getHttpd() {
    return (null != this.httpd) ? httpd : new NullHTTPD();
  }

  @Override
  public <T> T subreport(Function<NotebookOutput, T> fn, String name) {
    return subreport(name, fn, this);
  }

  protected <T> T subreport(String reportName, Function<NotebookOutput, T> fn, MarkdownNotebookOutput parent) {
    try {
      File root = getRoot();
      MarkdownNotebookOutput subreport = new Subreport(root, parent, reportName);
      subreport.setArchiveHome(getArchiveHome());
      subreport.setMaxImageSize(getMaxImageSize());
      try {
        this.p(this.link(new File(root, subreport.id + ".html"), "Subreport: " + reportName));
        getHttpd().addGET(subreport.id + ".html", "text/html", out -> {
          try {
            subreport.write();
            try (FileInputStream input = new FileInputStream(new File(root, subreport.id + ".html"))) {
              IOUtils.copy(input, out);
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
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
      throw new RuntimeException(e);
    }
  }

  public int getMaxImageSize() {
    return maxImageSize;
  }

  public NotebookOutput setMaxImageSize(int maxImageSize) {
    this.maxImageSize = maxImageSize;
    return this;
  }

  @Override
  public NotebookOutput setCurrentHome(URI currentHome) {
    this.currentHome = currentHome;
    return this;
  }

  @Override
  public URI getArchiveHome() {
    return archiveHome;
  }

  @Override
  public NotebookOutput setArchiveHome(URI archiveHome) {
    this.archiveHome = archiveHome;
    logger.info(String.format("Changed archive home to %s", archiveHome));
    return this;
  }

  @Override
  public NotebookOutput setName(String name) {
    this.name = name.replaceAll("\\.md$", "").replaceAll("\\$$", "").replaceAll("[:%\\{\\}\\(\\)\\+\\*]", "_").replaceAll("_{2,}", "_");
    int maxLength = 128;
    try {
      this.name = stripPrefixes(URLEncoder.encode(this.name, "UTF-8"), "_", "/", "-", " ", ".");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
    if (this.name.length() > maxLength) {
      this.name = this.name.substring(this.name.length() - maxLength);
    }

    return this;
  }

  @Nonnull
  public File getReportFile() {
    return new File(getRoot(), getName() + ".md");
  }


  public boolean isEnableZip() {
    return enableZip;
  }

  public MarkdownNotebookOutput setEnableZip(boolean enableZip) {
    this.enableZip = enableZip;
    return this;
  }

  public boolean isEnablePdf() {
    return enablePdf;
  }

  public MarkdownNotebookOutput setEnablePdf(boolean enablePdf) {
    this.enablePdf = enablePdf;
    return this;
  }

}
