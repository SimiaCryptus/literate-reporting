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

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

public abstract class NanoHTTPD {

  public static final int SOCKET_READ_TIMEOUT = 5000;
  public static final String MIME_PLAINTEXT = "text/plain";
  public static final String MIME_HTML = "text/html";
  protected static final String CHARSET_REGEX = "[ |\t]*(charset)[ |\t]*=[ |\t]*['|\"]?([^\"^'^;]*)['|\"]?";
  public static final Pattern CHARSET_PATTERN = Pattern.compile(CHARSET_REGEX, Pattern.CASE_INSENSITIVE);
  protected static final String BOUNDARY_REGEX = "[ |\t]*(boundary)[ |\t]*=[ |\t]*['|\"]?([^\"^'^;]*)['|\"]?";
  public static final Pattern BOUNDARY_PATTERN = Pattern.compile(BOUNDARY_REGEX, Pattern.CASE_INSENSITIVE);
  protected static final String CONTENT_DISPOSITION_REGEX = "([ |\t]*Content-Disposition[ |\t]*:)(.*)";
  protected static final Pattern CONTENT_DISPOSITION_PATTERN = Pattern.compile(CONTENT_DISPOSITION_REGEX, Pattern.CASE_INSENSITIVE);
  protected static final String CONTENT_TYPE_REGEX = "([ |\t]*content-type[ |\t]*:)(.*)";
  protected static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile(CONTENT_TYPE_REGEX, Pattern.CASE_INSENSITIVE);
  protected static final String CONTENT_DISPOSITION_ATTRIBUTE_REGEX = "[ |\t]*([a-zA-Z]*)[ |\t]*=[ |\t]*['|\"]([^\"^']*)['|\"]";
  protected static final Pattern CONTENT_DISPOSITION_ATTRIBUTE_PATTERN = Pattern.compile(CONTENT_DISPOSITION_ATTRIBUTE_REGEX);
  protected static final String QUERY_STRING_PARAMETER = "NanoHttpd.QUERY_STRING";
  protected static final Logger LOG = Logger.getLogger(NanoHTTPD.class.getName());
  protected static Map<String, String> MIME_TYPES;
  protected final String hostname;
  protected final int myPort;
  protected volatile ServerSocket myServerSocket;
  protected ServerSocketFactory serverSocketFactory = new DefaultServerSocketFactory();
  protected Thread myThread;
  protected AsyncRunner asyncRunner;
  protected TempFileManagerFactory tempFileManagerFactory;

  public NanoHTTPD(int port) {
    this(null, port);
  }

  public NanoHTTPD(String hostname, int port) {
    this.hostname = hostname;
    this.myPort = port;
    setTempFileManagerFactory(new DefaultTempFileManagerFactory());
    setAsyncRunner(new DefaultAsyncRunner());
  }

  public static Map<String, String> mimeTypes() {
    if (MIME_TYPES == null) {
      MIME_TYPES = new HashMap<String, String>();
      loadMimeTypes(MIME_TYPES, "META-INF/nanohttpd/default-mimetypes.properties");
      loadMimeTypes(MIME_TYPES, "META-INF/nanohttpd/mimetypes.properties");
      if (MIME_TYPES.isEmpty()) {
        LOG.log(Level.WARNING, "no mime types found in the classpath! please provide mimetypes.properties");
      }
    }
    return MIME_TYPES;
  }

  protected static void loadMimeTypes(Map<String, String> result, String resourceName) {
    try {
      Enumeration<URL> resources = NanoHTTPD.class.getClassLoader().getResources(resourceName);
      while (resources.hasMoreElements()) {
        URL url = resources.nextElement();
        Properties properties = new Properties();
        InputStream stream = null;
        try {
          stream = url.openStream();
          properties.load(url.openStream());
        } catch (IOException e) {
          LOG.log(Level.SEVERE, "could not load mimetypes from " + url, e);
        } finally {
          safeClose(stream);
        }
        result.putAll((Map) properties);
      }
    } catch (IOException e) {
      LOG.log(Level.INFO, "no mime types available at " + resourceName);
    }
  }

  public static SSLServerSocketFactory makeSSLSocketFactory(KeyStore loadedKeyStore, KeyManager[] keyManagers) throws IOException {
    SSLServerSocketFactory res = null;
    try {
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(loadedKeyStore);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(keyManagers, trustManagerFactory.getTrustManagers(), null);
      res = ctx.getServerSocketFactory();
    } catch (Exception e) {
      throw new IOException(e.getMessage());
    }
    return res;
  }

  public static SSLServerSocketFactory makeSSLSocketFactory(KeyStore loadedKeyStore, KeyManagerFactory loadedKeyFactory) throws IOException {
    try {
      return makeSSLSocketFactory(loadedKeyStore, loadedKeyFactory.getKeyManagers());
    } catch (Exception e) {
      throw new IOException(e.getMessage());
    }
  }

  public static SSLServerSocketFactory makeSSLSocketFactory(String keyAndTrustStoreClasspathPath, char[] passphrase) throws IOException {
    try {
      KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
      InputStream keystoreStream = NanoHTTPD.class.getResourceAsStream(keyAndTrustStoreClasspathPath);
      keystore.load(keystoreStream, passphrase);
      KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagerFactory.init(keystore, passphrase);
      return makeSSLSocketFactory(keystore, keyManagerFactory);
    } catch (Exception e) {
      throw new IOException(e.getMessage());
    }
  }

  public static String getMimeTypeForFile(String uri) {
    int dot = uri.lastIndexOf('.');
    String mime = null;
    if (dot >= 0) {
      mime = mimeTypes().get(uri.substring(dot + 1).toLowerCase());
    }
    return mime == null ? "application/octet-stream" : mime;
  }

  protected static final void safeClose(Object closeable) {
    try {
      if (closeable != null) {
        if (closeable instanceof Closeable) {
          ((Closeable) closeable).close();
        } else if (closeable instanceof Socket) {
          ((Socket) closeable).close();
        } else if (closeable instanceof ServerSocket) {
          ((ServerSocket) closeable).close();
        } else {
          throw new IllegalArgumentException("Unknown object to close");
        }
      }
    } catch (IOException e) {
      NanoHTTPD.LOG.log(Level.SEVERE, "Could not close", e);
    }
  }

  protected static Map<String, List<String>> decodeParameters(Map<String, String> parms) {
    return decodeParameters(parms.get(NanoHTTPD.QUERY_STRING_PARAMETER));
  }

  protected static Map<String, List<String>> decodeParameters(String queryString) {
    Map<String, List<String>> parms = new HashMap<String, List<String>>();
    if (queryString != null) {
      StringTokenizer st = new StringTokenizer(queryString, "&");
      while (st.hasMoreTokens()) {
        String e = st.nextToken();
        int sep = e.indexOf('=');
        String propertyName = sep >= 0 ? decodePercent(e.substring(0, sep)).trim() : decodePercent(e).trim();
        if (!parms.containsKey(propertyName)) {
          parms.put(propertyName, new ArrayList<String>());
        }
        String propertyValue = sep >= 0 ? decodePercent(e.substring(sep + 1)) : null;
        if (propertyValue != null) {
          parms.get(propertyName).add(propertyValue);
        }
      }
    }
    return parms;
  }

  protected static String decodePercent(String str) {
    String decoded = null;
    try {
      decoded = URLDecoder.decode(str, "UTF8");
    } catch (UnsupportedEncodingException ignored) {
      NanoHTTPD.LOG.log(Level.WARNING, "Encoding not supported, ignored", ignored);
    }
    return decoded;
  }

  public static Response newChunkedResponse(Response.IStatus status, String mimeType, InputStream data) {
    return new Response(status, mimeType, data, -1);
  }

  public static Response newFixedLengthResponse(Response.IStatus status, String mimeType, InputStream data, long totalBytes) {
    return new Response(status, mimeType, data, totalBytes);
  }

  public static Response newFixedLengthResponse(Response.IStatus status, String mimeType, String txt) {
    if (txt == null) {
      return newFixedLengthResponse(status, mimeType, new ByteArrayInputStream(new byte[0]), 0);
    } else {
      byte[] bytes;
      try {
        bytes = txt.getBytes("UTF-8");
      } catch (UnsupportedEncodingException e) {
        NanoHTTPD.LOG.log(Level.SEVERE, "encoding problem, responding nothing", e);
        bytes = new byte[0];
      }
      return newFixedLengthResponse(status, mimeType, new ByteArrayInputStream(bytes), bytes.length);
    }
  }

  public static Response newFixedLengthResponse(String msg) {
    return newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_HTML, msg);
  }

  public synchronized void closeAllConnections() {
    stop();
  }

  protected ClientHandler createClientHandler(final Socket finalAccept, final InputStream inputStream) {
    return new ClientHandler(inputStream, finalAccept);
  }

  protected ServerRunnable createServerRunnable(final int timeout) {
    return new ServerRunnable(timeout);
  }

  protected boolean useGzipWhenAccepted(Response r) {
    return r.getMimeType() != null && r.getMimeType().toLowerCase().contains("text/");
  }

  public final int getListeningPort() {
    return this.myServerSocket == null ? -1 : this.myServerSocket.getLocalPort();
  }

  public final boolean isAlive() {
    return wasStarted() && !this.myServerSocket.isClosed() && this.myThread.isAlive();
  }

  public ServerSocketFactory getServerSocketFactory() {
    return serverSocketFactory;
  }

  public void setServerSocketFactory(ServerSocketFactory serverSocketFactory) {
    this.serverSocketFactory = serverSocketFactory;
  }

  public String getHostname() {
    return hostname;
  }

  public TempFileManagerFactory getTempFileManagerFactory() {
    return tempFileManagerFactory;
  }

  public void setTempFileManagerFactory(TempFileManagerFactory tempFileManagerFactory) {
    this.tempFileManagerFactory = tempFileManagerFactory;
  }

  public void makeSecure(SSLServerSocketFactory sslServerSocketFactory, String[] sslProtocols) {
    this.serverSocketFactory = new SecureServerSocketFactory(sslServerSocketFactory, sslProtocols);
  }

  // -------------------------------------------------------------------------------
  // //
  //
  // Threading Strategy.
  //
  // -------------------------------------------------------------------------------
  // //

  public Response serve(IHTTPSession session) {
    Map<String, String> files = new HashMap<String, String>();
    Method method = session.getMethod();
    if (Method.PUT.equals(method) || Method.POST.equals(method)) {
      try {
        session.parseBody(files);
      } catch (IOException ioe) {
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
      } catch (ResponseException re) {
        return newFixedLengthResponse(re.getStatus(), NanoHTTPD.MIME_PLAINTEXT, re.getMessage());
      }
    }

    Map<String, String> parms = session.getParms();
    parms.put(NanoHTTPD.QUERY_STRING_PARAMETER, session.getQueryParameterString());
    return serve(session.getUri(), method, session.getHeaders(), parms, files);
  }

  @Deprecated
  public Response serve(String uri, Method method, Map<String, String> headers, Map<String, String> parms, Map<String, String> files) {
    return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found");
  }

  public void setAsyncRunner(AsyncRunner asyncRunner) {
    this.asyncRunner = asyncRunner;
  }

  public void start() throws IOException {
    start(NanoHTTPD.SOCKET_READ_TIMEOUT);
  }

  public void start(final int timeout) throws IOException {
    start(timeout, true);
  }

  // -------------------------------------------------------------------------------
  // //

  public void start(final int timeout, boolean daemon) throws IOException {
    this.myServerSocket = this.getServerSocketFactory().create();
    this.myServerSocket.setReuseAddress(true);

    ServerRunnable serverRunnable = createServerRunnable(timeout);
    this.myThread = new Thread(serverRunnable);
    this.myThread.setDaemon(daemon);
    this.myThread.setName("NanoHttpd Main Listener");
    this.myThread.start();
    while (!serverRunnable.hasBinded && serverRunnable.bindException == null) {
      try {
        Thread.sleep(10L);
      } catch (Throwable e) {
        // on android this may not be allowed, that's why we
        // catch throwable the wait should be very short because we are
        // just waiting for the bind of the socket
      }
    }
    if (serverRunnable.bindException != null) {
      throw serverRunnable.bindException;
    }
  }

  public void stop() {
    try {
      safeClose(this.myServerSocket);
      this.asyncRunner.closeAll();
      if (this.myThread != null) {
        this.myThread.join();
      }
    } catch (Exception e) {
      NanoHTTPD.LOG.log(Level.SEVERE, "Could not stop all connections", e);
    }
  }

  public final boolean wasStarted() {
    return this.myServerSocket != null && this.myThread != null;
  }

  public enum Method {
    GET,
    PUT,
    POST,
    DELETE,
    HEAD,
    OPTIONS,
    TRACE,
    CONNECT,
    PATCH;

    static Method lookup(String method) {
      for (Method m : Method.values()) {
        if (m.toString().equalsIgnoreCase(method)) {
          return m;
        }
      }
      return null;
    }
  }

  public interface AsyncRunner {

    void closeAll();

    void closed(ClientHandler clientHandler);

    void exec(ClientHandler code);
  }

  public interface IHTTPSession {

    void execute() throws IOException;

    CookieHandler getCookies();

    Map<String, String> getHeaders();

    InputStream getInputStream();

    Method getMethod();

    Map<String, String> getParms();

    String getQueryParameterString();

    String getUri();

    void parseBody(Map<String, String> files) throws IOException, ResponseException;
  }

  public interface TempFile {

    void delete() throws Exception;

    String getName();

    OutputStream open() throws Exception;
  }

  public interface TempFileManager {

    void clear();

    TempFile createTempFile(String filename_hint) throws Exception;
  }

  public interface TempFileManagerFactory {

    TempFileManager create();
  }

  public interface ServerSocketFactory {

    ServerSocket create() throws IOException;

  }

  public static class Cookie {

    protected final String n,
    v,
    e;

    public Cookie(String name, String value) {
      this(name, value, 30);
    }

    public Cookie(String name, String value, int numDays) {
      this.n = name;
      this.v = value;
      this.e = getHTTPTime(numDays);
    }

    public Cookie(String name, String value, String expires) {
      this.n = name;
      this.v = value;
      this.e = expires;
    }

    public static String getHTTPTime(int days) {
      Calendar calendar = Calendar.getInstance();
      SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
      dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
      calendar.add(Calendar.DAY_OF_MONTH, days);
      return dateFormat.format(calendar.getTime());
    }

    public String getHTTPHeader() {
      String fmt = "%s=%s; expires=%s";
      return String.format(fmt, this.n, this.v, this.e);
    }
  }

  public static class DefaultAsyncRunner implements AsyncRunner {

    protected final List<ClientHandler> running = Collections.synchronizedList(new ArrayList<NanoHTTPD.ClientHandler>());
    protected long requestCount;

    public List<ClientHandler> getRunning() {
      return running;
    }

    @Override
    public void closeAll() {
      // copy of the list for concurrency
      for (ClientHandler clientHandler : new ArrayList<ClientHandler>(this.running)) {
        clientHandler.close();
      }
    }

    @Override
    public void closed(ClientHandler clientHandler) {
      this.running.remove(clientHandler);
    }

    @Override
    public void exec(ClientHandler clientHandler) {
      ++this.requestCount;
      Thread t = new Thread(clientHandler);
      t.setDaemon(true);
      t.setName("NanoHttpd Request Processor (#" + this.requestCount + ")");
      this.running.add(clientHandler);
      t.start();
    }
  }

  public static class DefaultTempFile implements TempFile {

    protected final File file;

    protected final OutputStream fstream;

    public DefaultTempFile(File tempdir) throws IOException {
      this.file = File.createTempFile("NanoHTTPD-", "", tempdir);
      this.fstream = new FileOutputStream(this.file);
    }

    @Override
    public void delete() throws Exception {
      safeClose(this.fstream);
      if (!this.file.delete()) {
        throw new Exception("could not delete temporary file");
      }
    }

    @Override
    public String getName() {
      return this.file.getAbsolutePath();
    }

    @Override
    public OutputStream open() {
      return this.fstream;
    }
  }

  public static class DefaultTempFileManager implements TempFileManager {

    protected final File tmpdir;

    protected final List<TempFile> tempFiles;

    public DefaultTempFileManager() {
      this.tmpdir = new File(System.getProperty("java.io.tmpdir"));
      if (!tmpdir.exists()) {
        tmpdir.mkdirs();
      }
      this.tempFiles = new ArrayList<TempFile>();
    }

    @Override
    public void clear() {
      for (TempFile file : this.tempFiles) {
        try {
          file.delete();
        } catch (Exception ignored) {
          NanoHTTPD.LOG.log(Level.WARNING, "could not delete file ", ignored);
        }
      }
      this.tempFiles.clear();
    }

    @Override
    public TempFile createTempFile(String filename_hint) throws Exception {
      DefaultTempFile tempFile = new DefaultTempFile(this.tmpdir);
      this.tempFiles.add(tempFile);
      return tempFile;
    }
  }

  public static class DefaultServerSocketFactory implements ServerSocketFactory {

    @Override
    public ServerSocket create() throws IOException {
      return new ServerSocket();
    }

  }

  public static class SecureServerSocketFactory implements ServerSocketFactory {

    protected SSLServerSocketFactory sslServerSocketFactory;

    protected String[] sslProtocols;

    public SecureServerSocketFactory(SSLServerSocketFactory sslServerSocketFactory, String[] sslProtocols) {
      this.sslServerSocketFactory = sslServerSocketFactory;
      this.sslProtocols = sslProtocols;
    }

    @Override
    public ServerSocket create() throws IOException {
      SSLServerSocket ss = null;
      ss = (SSLServerSocket) this.sslServerSocketFactory.createServerSocket();
      if (this.sslProtocols != null) {
        ss.setEnabledProtocols(this.sslProtocols);
      } else {
        ss.setEnabledProtocols(ss.getSupportedProtocols());
      }
      ss.setUseClientMode(false);
      ss.setWantClientAuth(false);
      ss.setNeedClientAuth(false);
      return ss;
    }

  }

  public static class Response implements Closeable {

    protected final Map<String, String> header = new HashMap<String, String>();
    protected Response.IStatus status;
    protected String mimeType;
    protected InputStream data;
    protected long contentLength;
    protected Method requestMethod;
    protected boolean chunkedTransfer;
    protected boolean encodeAsGzip;
    protected boolean keepAlive;

    protected Response(Response.IStatus status, String mimeType, InputStream data, long totalBytes) {
      this.status = status;
      this.mimeType = mimeType;
      if (data == null) {
        this.data = new ByteArrayInputStream(new byte[0]);
        this.contentLength = 0L;
      } else {
        this.data = data;
        this.contentLength = totalBytes;
      }
      this.chunkedTransfer = this.contentLength < 0;
      keepAlive = true;
    }

    protected static boolean headerAlreadySent(Map<String, String> header, String name) {
      boolean alreadySent = false;
      for (String headerName : header.keySet()) {
        alreadySent |= headerName.equalsIgnoreCase(name);
      }
      return alreadySent;
    }

    protected static long sendContentLengthHeaderIfNotAlreadyPresent(PrintWriter pw, Map<String, String> header, long size) {
      for (String headerName : header.keySet()) {
        if (headerName.equalsIgnoreCase("content-length")) {
          try {
            return Long.parseLong(header.get(headerName));
          } catch (NumberFormatException ex) {
            return size;
          }
        }
      }

      pw.print("Content-Length: " + size + "\r\n");
      return size;
    }

    @Override
    public void close() throws IOException {
      if (this.data != null) {
        this.data.close();
      }
    }

    public void addHeader(String name, String value) {
      this.header.put(name, value);
    }

    public InputStream getData() {
      return this.data;
    }

    public void setData(InputStream data) {
      this.data = data;
    }

    public String getHeader(String name) {
      for (String headerName : header.keySet()) {
        if (headerName.equalsIgnoreCase(name)) {
          return header.get(headerName);
        }
      }
      return null;
    }

    public String getMimeType() {
      return this.mimeType;
    }

    public void setMimeType(String mimeType) {
      this.mimeType = mimeType;
    }

    public Method getRequestMethod() {
      return this.requestMethod;
    }

    public void setRequestMethod(Method requestMethod) {
      this.requestMethod = requestMethod;
    }

    public Response.IStatus getStatus() {
      return this.status;
    }

    public void setStatus(Response.IStatus status) {
      this.status = status;
    }

    public void setGzipEncoding(boolean encodeAsGzip) {
      this.encodeAsGzip = encodeAsGzip;
    }

    public void setKeepAlive(boolean useKeepAlive) {
      this.keepAlive = useKeepAlive;
    }

    protected void send(OutputStream outputStream) {
      String mime = this.mimeType;
      SimpleDateFormat gmtFrmt = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
      gmtFrmt.setTimeZone(TimeZone.getTimeZone("GMT"));

      try {
        if (this.status == null) {
          throw new Error("sendResponse(): Status can't be null.");
        }
        PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8")), false);
        pw.print("HTTP/1.1 " + this.status.getDescription() + " \r\n");

        if (mime != null) {
          pw.print("Content-Type: " + mime + "\r\n");
        }

        if (this.header == null || this.header.get("Date") == null) {
          pw.print("Date: " + gmtFrmt.format(new Date()) + "\r\n");
        }

        if (this.header != null) {
          for (String key : this.header.keySet()) {
            String value = this.header.get(key);
            pw.print(key + ": " + value + "\r\n");
          }
        }

        if (!headerAlreadySent(header, "connection")) {
          pw.print("Connection: " + (this.keepAlive ? "keep-alive" : "close") + "\r\n");
        }

        if (headerAlreadySent(this.header, "content-length")) {
          encodeAsGzip = false;
        }

        if (encodeAsGzip) {
          pw.print("Content-Encoding: gzip\r\n");
          setChunkedTransfer(true);
        }

        long pending = this.data != null ? this.contentLength : 0;
        if (this.requestMethod != Method.HEAD && this.chunkedTransfer) {
          pw.print("Transfer-Encoding: chunked\r\n");
        } else if (!encodeAsGzip) {
          pending = sendContentLengthHeaderIfNotAlreadyPresent(pw, this.header, pending);
        }
        pw.print("\r\n");
        pw.flush();
        sendBodyWithCorrectTransferAndEncoding(outputStream, pending);
        outputStream.flush();
        safeClose(this.data);
      } catch (IOException ioe) {
        NanoHTTPD.LOG.log(Level.SEVERE, "Could not send response to the client", ioe);
      }
    }

    protected void sendBodyWithCorrectTransferAndEncoding(OutputStream outputStream, long pending) throws IOException {
      if (this.requestMethod != Method.HEAD && this.chunkedTransfer) {
        ChunkedOutputStream chunkedOutputStream = new ChunkedOutputStream(outputStream);
        sendBodyWithCorrectEncoding(chunkedOutputStream, -1);
        chunkedOutputStream.finish();
      } else {
        sendBodyWithCorrectEncoding(outputStream, pending);
      }
    }

    protected void sendBodyWithCorrectEncoding(OutputStream outputStream, long pending) throws IOException {
      if (encodeAsGzip) {
        GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream);
        sendBody(gzipOutputStream, -1);
        gzipOutputStream.finish();
      } else {
        sendBody(outputStream, pending);
      }
    }

    protected void sendBody(OutputStream outputStream, long pending) throws IOException {
      long BUFFER_SIZE = 16 * 1024;
      byte[] buff = new byte[(int) BUFFER_SIZE];
      boolean sendEverything = pending == -1;
      while (pending > 0 || sendEverything) {
        long bytesToRead = sendEverything ? BUFFER_SIZE : Math.min(pending, BUFFER_SIZE);
        int read = this.data.read(buff, 0, (int) bytesToRead);
        if (read <= 0) {
          break;
        }
        outputStream.write(buff, 0, read);
        if (!sendEverything) {
          pending -= read;
        }
      }
    }

    public void setChunkedTransfer(boolean chunkedTransfer) {
      this.chunkedTransfer = chunkedTransfer;
    }

    public enum Status implements Response.IStatus {
      SWITCH_PROTOCOL(101, "Switching Protocols"),
      OK(200, "OK"),
      CREATED(201, "Created"),
      ACCEPTED(202, "Accepted"),
      NO_CONTENT(204, "No Content"),
      PARTIAL_CONTENT(206, "Partial Content"),
      REDIRECT(301, "Moved Permanently"),
      NOT_MODIFIED(304, "Not Modified"),
      BAD_REQUEST(400, "Bad Request"),
      UNAUTHORIZED(401, "Unauthorized"),
      FORBIDDEN(403, "Forbidden"),
      NOT_FOUND(404, "Not Found"),
      METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
      NOT_ACCEPTABLE(406, "Not Acceptable"),
      REQUEST_TIMEOUT(408, "Request Timeout"),
      CONFLICT(409, "Conflict"),
      RANGE_NOT_SATISFIABLE(416, "Requested Range Not Satisfiable"),
      INTERNAL_ERROR(500, "Internal Server Error"),
      NOT_IMPLEMENTED(501, "Not Implemented"),
      UNSUPPORTED_HTTP_VERSION(505, "HTTP Version Not Supported");

      protected final int requestStatus;

      protected final String description;

      Status(int requestStatus, String description) {
        this.requestStatus = requestStatus;
        this.description = description;
      }

      @Override
      public String getDescription() {
        return "" + this.requestStatus + " " + this.description;
      }

      @Override
      public int getRequestStatus() {
        return this.requestStatus;
      }

    }

    public interface IStatus {

      String getDescription();

      int getRequestStatus();
    }

    protected static class ChunkedOutputStream extends FilterOutputStream {

      public ChunkedOutputStream(OutputStream out) {
        super(out);
      }

      @Override
      public void write(int b) throws IOException {
        byte[] data = {
            (byte) b
        };
        write(data, 0, 1);
      }

      @Override
      public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (len == 0)
          return;
        out.write(String.format("%x\r\n", len).getBytes());
        out.write(b, off, len);
        out.write("\r\n".getBytes());
      }

      public void finish() throws IOException {
        out.write("0\r\n\r\n".getBytes());
      }

    }
  }

  public static final class ResponseException extends RuntimeException {

    protected static final long serialVersionUID = 6569838532917408380L;

    protected final Response.Status status;

    public ResponseException(Response.Status status, String message) {
      super(message);
      this.status = status;
    }

    public ResponseException(Response.Status status, String message, Exception e) {
      super(message, e);
      this.status = status;
    }

    public Response.Status getStatus() {
      return this.status;
    }
  }

  public class ClientHandler implements Runnable {

    protected final InputStream inputStream;

    protected final Socket acceptSocket;

    protected ClientHandler(InputStream inputStream, Socket acceptSocket) {
      this.inputStream = inputStream;
      this.acceptSocket = acceptSocket;
    }

    public void close() {
      safeClose(this.inputStream);
      safeClose(this.acceptSocket);
    }

    @Override
    public void run() {
      OutputStream outputStream = null;
      try {
        outputStream = this.acceptSocket.getOutputStream();
        TempFileManager tempFileManager = NanoHTTPD.this.tempFileManagerFactory.create();
        HTTPSession session = new HTTPSession(tempFileManager, this.inputStream, outputStream, this.acceptSocket.getInetAddress());
        while (!this.acceptSocket.isClosed()) {
          session.execute();
        }
      } catch (Exception e) {
        // When the socket is closed by the client,
        // we throw our own SocketException
        // to break the "keep alive" loop above. If
        // the exception was anything other
        // than the expected SocketException OR a
        // SocketTimeoutException, print the
        // stacktrace
        if (!(e instanceof SocketException && "NanoHttpd Shutdown".equals(e.getMessage())) && !(e instanceof SocketTimeoutException)) {
          NanoHTTPD.LOG.log(Level.FINE, "Communication with the client broken", e);
        }
      } finally {
        safeClose(outputStream);
        safeClose(this.inputStream);
        safeClose(this.acceptSocket);
        NanoHTTPD.this.asyncRunner.closed(this);
      }
    }
  }

  public class CookieHandler implements Iterable<String> {

    protected final HashMap<String, String> cookies = new HashMap<String, String>();

    protected final ArrayList<Cookie> queue = new ArrayList<Cookie>();

    public CookieHandler(Map<String, String> httpHeaders) {
      String raw = httpHeaders.get("cookie");
      if (raw != null) {
        String[] tokens = raw.split(";");
        for (String token : tokens) {
          String[] data = token.trim().split("=");
          if (data.length == 2) {
            this.cookies.put(data[0], data[1]);
          }
        }
      }
    }

    public void delete(String name) {
      set(name, "-delete-", -30);
    }

    @Override
    public Iterator<String> iterator() {
      return this.cookies.keySet().iterator();
    }

    public String read(String name) {
      return this.cookies.get(name);
    }

    public void set(Cookie cookie) {
      this.queue.add(cookie);
    }

    public void set(String name, String value, int expires) {
      this.queue.add(new Cookie(name, value, Cookie.getHTTPTime(expires)));
    }

    public void unloadQueue(Response response) {
      for (Cookie cookie : this.queue) {
        response.addHeader("Set-Cookie", cookie.getHTTPHeader());
      }
    }
  }

  protected class DefaultTempFileManagerFactory implements TempFileManagerFactory {

    @Override
    public TempFileManager create() {
      return new DefaultTempFileManager();
    }
  }

  protected class HTTPSession implements IHTTPSession {

    public static final int BUFSIZE = 8192;
    public static final int MAX_HEADER_SIZE = 1024;
    protected static final int REQUEST_BUFFER_LEN = 512;
    protected static final int MEMORY_STORE_LIMIT = 1024;
    protected final TempFileManager tempFileManager;

    protected final OutputStream outputStream;

    protected final BufferedInputStream inputStream;

    protected int splitbyte;

    protected int rlen;

    protected String uri;

    protected Method method;

    protected Map<String, String> parms;

    protected Map<String, String> headers;

    protected CookieHandler cookies;

    protected String queryParameterString;

    protected String remoteIp;

    protected String protocolVersion;

    public HTTPSession(TempFileManager tempFileManager, InputStream inputStream, OutputStream outputStream) {
      this.tempFileManager = tempFileManager;
      this.inputStream = new BufferedInputStream(inputStream, HTTPSession.BUFSIZE);
      this.outputStream = outputStream;
    }

    public HTTPSession(TempFileManager tempFileManager, InputStream inputStream, OutputStream outputStream, InetAddress inetAddress) {
      this.tempFileManager = tempFileManager;
      this.inputStream = new BufferedInputStream(inputStream, HTTPSession.BUFSIZE);
      this.outputStream = outputStream;
      this.remoteIp = inetAddress.isLoopbackAddress() || inetAddress.isAnyLocalAddress() ? "127.0.0.1" : inetAddress.getHostAddress();
      this.headers = new HashMap<String, String>();
    }

    protected void decodeHeader(BufferedReader in, Map<String, String> pre, Map<String, String> parms, Map<String, String> headers) throws ResponseException {
      try {
        // Read the request line
        String inLine = in.readLine();
        if (inLine == null) {
          return;
        }

        StringTokenizer st = new StringTokenizer(inLine);
        if (!st.hasMoreTokens()) {
          throw new ResponseException(Response.Status.BAD_REQUEST, "BAD REQUEST: Syntax error. Usage: GET /example/file.html");
        }

        pre.put("method", st.nextToken());

        if (!st.hasMoreTokens()) {
          throw new ResponseException(Response.Status.BAD_REQUEST, "BAD REQUEST: Missing URI. Usage: GET /example/file.html");
        }

        String uri = st.nextToken();

        // Decode parameters from the URI
        int qmi = uri.indexOf('?');
        if (qmi >= 0) {
          decodeParms(uri.substring(qmi + 1), parms);
          uri = decodePercent(uri.substring(0, qmi));
        } else {
          uri = decodePercent(uri);
        }

        // If there's another token, its protocol version,
        // followed by HTTP headers.
        // NOTE: this now forces header names lower case since they are
        // case insensitive and vary by client.
        if (st.hasMoreTokens()) {
          protocolVersion = st.nextToken();
        } else {
          protocolVersion = "HTTP/1.1";
          NanoHTTPD.LOG.log(Level.FINE, "no protocol version specified, strange. Assuming HTTP/1.1.");
        }
        String line = in.readLine();
        while (line != null && line.trim().length() > 0) {
          int p = line.indexOf(':');
          if (p >= 0) {
            headers.put(line.substring(0, p).trim().toLowerCase(Locale.US), line.substring(p + 1).trim());
          }
          line = in.readLine();
        }

        pre.put("uri", uri);
      } catch (IOException ioe) {
        throw new ResponseException(Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage(), ioe);
      }
    }

    protected void decodeMultipartFormData(String boundary, String encoding, ByteBuffer fbuf, Map<String, String> parms, Map<String, String> files) throws ResponseException {
      try {
        int[] boundary_idxs = getBoundaryPositions(fbuf, boundary.getBytes());
        if (boundary_idxs.length < 2) {
          throw new ResponseException(Response.Status.BAD_REQUEST, "BAD REQUEST: Content type is multipart/form-data but contains less than two boundary strings.");
        }

        byte[] part_header_buff = new byte[MAX_HEADER_SIZE];
        for (int bi = 0; bi < boundary_idxs.length - 1; bi++) {
          fbuf.position(boundary_idxs[bi]);
          int len = (fbuf.remaining() < MAX_HEADER_SIZE) ? fbuf.remaining() : MAX_HEADER_SIZE;
          fbuf.get(part_header_buff, 0, len);
          BufferedReader in = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(part_header_buff, 0, len), Charset.forName(encoding)), len);

          int headerLines = 0;
          // First line is boundary string
          String mpline = in.readLine();
          headerLines++;
          if (!mpline.contains(boundary)) {
            throw new ResponseException(Response.Status.BAD_REQUEST, "BAD REQUEST: Content type is multipart/form-data but chunk does not start with boundary.");
          }

          String part_name = null, file_name = null, content_type = null;
          // Parse the reset of the header lines
          mpline = in.readLine();
          headerLines++;
          while (mpline != null && mpline.trim().length() > 0) {
            Matcher matcher = CONTENT_DISPOSITION_PATTERN.matcher(mpline);
            if (matcher.matches()) {
              String attributeString = matcher.group(2);
              matcher = CONTENT_DISPOSITION_ATTRIBUTE_PATTERN.matcher(attributeString);
              while (matcher.find()) {
                String key = matcher.group(1);
                if (key.equalsIgnoreCase("name")) {
                  part_name = matcher.group(2);
                } else if (key.equalsIgnoreCase("filename")) {
                  file_name = matcher.group(2);
                }
              }
            }
            matcher = CONTENT_TYPE_PATTERN.matcher(mpline);
            if (matcher.matches()) {
              content_type = matcher.group(2).trim();
            }
            mpline = in.readLine();
            headerLines++;
          }
          int part_header_len = 0;
          while (headerLines-- > 0) {
            part_header_len = scipOverNewLine(part_header_buff, part_header_len);
          }
          // Read the part data
          if (part_header_len >= len - 4) {
            throw new ResponseException(Response.Status.INTERNAL_ERROR, "Multipart header size exceeds MAX_HEADER_SIZE.");
          }
          int part_data_start = boundary_idxs[bi] + part_header_len;
          int part_data_end = boundary_idxs[bi + 1] - 4;

          fbuf.position(part_data_start);
          if (content_type == null) {
            // Read the part into a string
            byte[] data_bytes = new byte[part_data_end - part_data_start];
            fbuf.get(data_bytes);
            parms.put(part_name, new String(data_bytes, encoding));
          } else {
            // Read it into a file
            String path = saveTmpFile(fbuf, part_data_start, part_data_end - part_data_start, file_name);
            if (!files.containsKey(part_name)) {
              files.put(part_name, path);
            } else {
              int count = 2;
              while (files.containsKey(part_name + count)) {
                count++;
              }
              files.put(part_name + count, path);
            }
            parms.put(part_name, file_name);
          }
        }
      } catch (ResponseException re) {
        throw re;
      } catch (Exception e) {
        throw new ResponseException(Response.Status.INTERNAL_ERROR, e.toString());
      }
    }

    protected int scipOverNewLine(byte[] part_header_buff, int index) {
      while (part_header_buff[index] != '\n') {
        index++;
      }
      return ++index;
    }

    protected void decodeParms(String parms, Map<String, String> p) {
      if (parms == null) {
        this.queryParameterString = "";
        return;
      }

      this.queryParameterString = parms;
      StringTokenizer st = new StringTokenizer(parms, "&");
      while (st.hasMoreTokens()) {
        String e = st.nextToken();
        int sep = e.indexOf('=');
        if (sep >= 0) {
          p.put(decodePercent(e.substring(0, sep)).trim(), decodePercent(e.substring(sep + 1)));
        } else {
          p.put(decodePercent(e).trim(), "");
        }
      }
    }

    @Override
    public void execute() throws IOException {
      Response r = null;
      try {
        // Read the first 8192 bytes.
        // The full header should fit in here.
        // Apache's default header limit is 8KB.
        // Do NOT assume that a single read will get the entire header
        // at once!
        byte[] buf = new byte[HTTPSession.BUFSIZE];
        this.splitbyte = 0;
        this.rlen = 0;

        int read = -1;
        this.inputStream.mark(HTTPSession.BUFSIZE);
        try {
          read = this.inputStream.read(buf, 0, HTTPSession.BUFSIZE);
        } catch (Exception e) {
          safeClose(this.inputStream);
          safeClose(this.outputStream);
          throw new SocketException("NanoHttpd Shutdown");
        }
        if (read == -1) {
          // socket was been closed
          safeClose(this.inputStream);
          safeClose(this.outputStream);
          throw new SocketException("NanoHttpd Shutdown");
        }
        while (read > 0) {
          this.rlen += read;
          this.splitbyte = findHeaderEnd(buf, this.rlen);
          if (this.splitbyte > 0) {
            break;
          }
          read = this.inputStream.read(buf, this.rlen, HTTPSession.BUFSIZE - this.rlen);
        }

        if (this.splitbyte < this.rlen) {
          this.inputStream.reset();
          this.inputStream.skip(this.splitbyte);
        }

        this.parms = new HashMap<String, String>();
        if (null == this.headers) {
          this.headers = new HashMap<String, String>();
        } else {
          this.headers.clear();
        }

        // Create a BufferedReader for parsing the header.
        BufferedReader hin = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buf, 0, this.rlen)));

        // Decode the header into parms and header java properties
        Map<String, String> pre = new HashMap<String, String>();
        decodeHeader(hin, pre, this.parms, this.headers);

        if (null != this.remoteIp) {
          this.headers.put("remote-addr", this.remoteIp);
          this.headers.put("http-client-ip", this.remoteIp);
        }

        this.method = Method.lookup(pre.get("method"));
        if (this.method == null) {
          throw new ResponseException(Response.Status.BAD_REQUEST, "BAD REQUEST: Syntax error.");
        }

        this.uri = pre.get("uri");

        this.cookies = new CookieHandler(this.headers);

        String connection = this.headers.get("connection");
        boolean keepAlive = protocolVersion.equals("HTTP/1.1") && (connection == null || !connection.matches("(?i).*close.*"));

        // Ok, now do the serve()

        // TODO: long body_size = getBodySize();
        // TODO: long pos_before_serve = this.inputStream.totalRead()
        // (requires implementaion for totalRead())
        r = serve(this);
        // TODO: this.inputStream.skip(body_size -
        // (this.inputStream.totalRead() - pos_before_serve))

        if (r == null) {
          throw new ResponseException(Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: Serve() returned a null response.");
        } else {
          String acceptEncoding = this.headers.get("accept-encoding");
          this.cookies.unloadQueue(r);
          r.setRequestMethod(this.method);
          r.setGzipEncoding(useGzipWhenAccepted(r) && acceptEncoding != null && acceptEncoding.contains("gzip"));
          r.setKeepAlive(keepAlive);
          r.send(this.outputStream);
        }
        if (!keepAlive || "close".equalsIgnoreCase(r.getHeader("connection"))) {
          throw new SocketException("NanoHttpd Shutdown");
        }
      } catch (SocketException e) {
        // throw it out to close socket object (finalAccept)
        throw e;
      } catch (SocketTimeoutException ste) {
        // treat socket timeouts the same way we treat socket exceptions
        // i.e. close the stream & finalAccept object by throwing the
        // exception up the call stack.
        throw ste;
      } catch (IOException ioe) {
        Response resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
        resp.send(this.outputStream);
        safeClose(this.outputStream);
      } catch (ResponseException re) {
        Response resp = newFixedLengthResponse(re.getStatus(), NanoHTTPD.MIME_PLAINTEXT, re.getMessage());
        resp.send(this.outputStream);
        safeClose(this.outputStream);
      } finally {
        safeClose(r);
        this.tempFileManager.clear();
      }
    }

    protected int findHeaderEnd(final byte[] buf, int rlen) {
      int splitbyte = 0;
      while (splitbyte + 1 < rlen) {

        // RFC2616
        if (buf[splitbyte] == '\r' && buf[splitbyte + 1] == '\n' && splitbyte + 3 < rlen && buf[splitbyte + 2] == '\r' && buf[splitbyte + 3] == '\n') {
          return splitbyte + 4;
        }

        // tolerance
        if (buf[splitbyte] == '\n' && buf[splitbyte + 1] == '\n') {
          return splitbyte + 2;
        }
        splitbyte++;
      }
      return 0;
    }

    protected int[] getBoundaryPositions(ByteBuffer b, byte[] boundary) {
      int[] res = new int[0];
      if (b.remaining() < boundary.length) {
        return res;
      }

      int search_window_pos = 0;
      byte[] search_window = new byte[4 * 1024 + boundary.length];

      int first_fill = (b.remaining() < search_window.length) ? b.remaining() : search_window.length;
      b.get(search_window, 0, first_fill);
      int new_bytes = first_fill - boundary.length;

      do {
        // Search the search_window
        for (int j = 0; j < new_bytes; j++) {
          for (int i = 0; i < boundary.length; i++) {
            if (search_window[j + i] != boundary[i])
              break;
            if (i == boundary.length - 1) {
              // Match found, add it to results
              int[] new_res = new int[res.length + 1];
              System.arraycopy(res, 0, new_res, 0, res.length);
              new_res[res.length] = search_window_pos + j;
              res = new_res;
            }
          }
        }
        search_window_pos += new_bytes;

        // Copy the end of the buffer to the start
        System.arraycopy(search_window, search_window.length - boundary.length, search_window, 0, boundary.length);

        // Refill search_window
        new_bytes = search_window.length - boundary.length;
        new_bytes = (b.remaining() < new_bytes) ? b.remaining() : new_bytes;
        b.get(search_window, boundary.length, new_bytes);
      } while (new_bytes > 0);
      return res;
    }

    @Override
    public CookieHandler getCookies() {
      return this.cookies;
    }

    @Override
    public final Map<String, String> getHeaders() {
      return this.headers;
    }

    @Override
    public final InputStream getInputStream() {
      return this.inputStream;
    }

    @Override
    public final Method getMethod() {
      return this.method;
    }

    @Override
    public final Map<String, String> getParms() {
      return this.parms;
    }

    @Override
    public String getQueryParameterString() {
      return this.queryParameterString;
    }

    protected RandomAccessFile getTmpBucket() {
      try {
        TempFile tempFile = this.tempFileManager.createTempFile(null);
        return new RandomAccessFile(tempFile.getName(), "rw");
      } catch (Exception e) {
        throw new Error(e); // we won't recover, so throw an error
      }
    }

    @Override
    public final String getUri() {
      return this.uri;
    }

    public long getBodySize() {
      if (this.headers.containsKey("content-length")) {
        return Long.parseLong(this.headers.get("content-length"));
      } else if (this.splitbyte < this.rlen) {
        return this.rlen - this.splitbyte;
      }
      return 0;
    }

    @Override
    public void parseBody(Map<String, String> files) throws IOException, ResponseException {
      RandomAccessFile randomAccessFile = null;
      try {
        long size = getBodySize();
        ByteArrayOutputStream baos = null;
        DataOutput request_data_output = null;

        // Store the request in memory or a file, depending on size
        if (size < MEMORY_STORE_LIMIT) {
          baos = new ByteArrayOutputStream();
          request_data_output = new DataOutputStream(baos);
        } else {
          randomAccessFile = getTmpBucket();
          request_data_output = randomAccessFile;
        }

        // Read all the body and write it to request_data_output
        byte[] buf = new byte[REQUEST_BUFFER_LEN];
        while (this.rlen >= 0 && size > 0) {
          this.rlen = this.inputStream.read(buf, 0, (int) Math.min(size, REQUEST_BUFFER_LEN));
          size -= this.rlen;
          if (this.rlen > 0) {
            request_data_output.write(buf, 0, this.rlen);
          }
        }

        ByteBuffer fbuf = null;
        if (baos != null) {
          fbuf = ByteBuffer.wrap(baos.toByteArray(), 0, baos.size());
        } else {
          fbuf = randomAccessFile.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, randomAccessFile.length());
          randomAccessFile.seek(0);
        }

        // If the method is POST, there may be parameters
        // in data section, too, read it:
        if (Method.POST.equals(this.method)) {
          String contentType = "";
          String contentTypeHeader = this.headers.get("content-type");

          StringTokenizer st = null;
          if (contentTypeHeader != null) {
            st = new StringTokenizer(contentTypeHeader, ",; ");
            if (st.hasMoreTokens()) {
              contentType = st.nextToken();
            }
          }

          if ("multipart/form-data".equalsIgnoreCase(contentType)) {
            // Handle multipart/form-data
            if (!st.hasMoreTokens()) {
              throw new ResponseException(Response.Status.BAD_REQUEST,
                  "BAD REQUEST: Content type is multipart/form-data but boundary missing. Usage: GET /example/file.html");
            }
            decodeMultipartFormData(getAttributeFromContentHeader(contentTypeHeader, BOUNDARY_PATTERN, null), //
                getAttributeFromContentHeader(contentTypeHeader, CHARSET_PATTERN, "US-ASCII"), fbuf, this.parms, files);
          } else {
            byte[] postBytes = new byte[fbuf.remaining()];
            fbuf.get(postBytes);
            String postLine = new String(postBytes).trim();
            // Handle application/x-www-form-urlencoded
            if ("application/x-www-form-urlencoded".equalsIgnoreCase(contentType)) {
              decodeParms(postLine, this.parms);
            } else if (postLine.length() != 0) {
              // Special case for raw POST data => create a
              // special files entry "postData" with raw content
              // data
              files.put("postData", postLine);
            }
          }
        } else if (Method.PUT.equals(this.method)) {
          files.put("content", saveTmpFile(fbuf, 0, fbuf.limit(), null));
        }
      } finally {
        safeClose(randomAccessFile);
      }
    }

    protected String getAttributeFromContentHeader(String contentTypeHeader, Pattern pattern, String defaultValue) {
      Matcher matcher = pattern.matcher(contentTypeHeader);
      return matcher.find() ? matcher.group(2) : defaultValue;
    }

    protected String saveTmpFile(ByteBuffer b, int offset, int len, String filename_hint) {
      String path = "";
      if (len > 0) {
        FileOutputStream fileOutputStream = null;
        try {
          TempFile tempFile = this.tempFileManager.createTempFile(filename_hint);
          ByteBuffer src = b.duplicate();
          fileOutputStream = new FileOutputStream(tempFile.getName());
          FileChannel dest = fileOutputStream.getChannel();
          src.position(offset).limit(offset + len);
          dest.write(src.slice());
          path = tempFile.getName();
        } catch (Exception e) { // Catch exception if any
          throw new Error(e); // we won't recover, so throw an error
        } finally {
          safeClose(fileOutputStream);
        }
      }
      return path;
    }
  }

  public class ServerRunnable implements Runnable {

    protected final int timeout;

    protected IOException bindException;

    protected boolean hasBinded = false;

    protected ServerRunnable(int timeout) {
      this.timeout = timeout;
    }

    @Override
    public void run() {
      try {
        myServerSocket.bind(hostname != null ? new InetSocketAddress(hostname, myPort) : new InetSocketAddress(myPort));
        hasBinded = true;
      } catch (IOException e) {
        this.bindException = e;
        return;
      }
      do {
        try {
          final Socket finalAccept = NanoHTTPD.this.myServerSocket.accept();
          if (this.timeout > 0) {
            finalAccept.setSoTimeout(this.timeout);
          }
          final InputStream inputStream = finalAccept.getInputStream();
          NanoHTTPD.this.asyncRunner.exec(createClientHandler(finalAccept, inputStream));
        } catch (IOException e) {
          NanoHTTPD.LOG.log(Level.FINE, "Communication with the client broken", e);
        }
      } while (!NanoHTTPD.this.myServerSocket.isClosed());
    }
  }
}
