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

package com.simiacryptus.util;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.google.common.collect.Lists;
import com.simiacryptus.notebook.MarkdownNotebookOutput;
import com.simiacryptus.ref.wrappers.RefString;
import com.simiacryptus.util.test.NotebookReportBase;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class S3Uploader {

  protected static final Logger logger = LoggerFactory.getLogger(NotebookReportBase.class);
  private static final AmazonS3 GLOBAL_S3 = AmazonS3ClientBuilder.standard()
      .withRegion(Regions.DEFAULT_REGION)
      .build();

  private static String getCurrentRegion() {
    try {
      Region currentRegion = Regions.getCurrentRegion();
      if (null == currentRegion)
        return Regions.US_EAST_1.getName();
      return currentRegion.getName();
    } catch (Throwable e) {
      return Regions.US_EAST_1.getName();
    }
  }

  public static void uploadOnComplete(MarkdownNotebookOutput log, AmazonS3 amazonS3) {
    log.onComplete(() -> {
      URI archiveHome = log.getArchiveHome();
      if (null != archiveHome) {
        upload(amazonS3, archiveHome, log.getRoot());
      }
    });
  }

  public static void uploadOnComplete(MarkdownNotebookOutput log) {
    log.onComplete(() -> {
      URI archiveHome = log.getArchiveHome();
      if (null != archiveHome) {
        upload(buildClientForBucket(archiveHome.getHost()), archiveHome, log.getRoot());
      }
    });
  }

  public static AmazonS3 buildClientForBucket(String bucket) {
    return buildClientForRegion(getRegion(bucket));
  }

  public static AmazonS3 buildClientForRegion(String region) {
    if (region.equals("US")) {
      return AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_1).build();
    } else {
      return AmazonS3ClientBuilder.standard().withRegion(region).build();
    }
  }

  public static String getRegion(String bucket) {
    String region;
    try {
      region = GLOBAL_S3.getBucketLocation(bucket);
    } catch (Throwable e) {
      e.printStackTrace();
      region = System.getProperty("AWS_REGION", getCurrentRegion());
    }
    return region;
  }

  @Nonnull
  public static Map<File, URL> upload(@Nonnull final AmazonS3 s3, final URI path, @Nonnull final File file) {
    return upload(s3, path, file, 3);
  }

  @Nonnull
  public static Map<File, URL> uploadDir(@Nonnull final AmazonS3 s3, final URI path, @Nonnull final File file) {
    return uploadDir(s3, path, file, 3);
  }

  public static HashSet<URL> rmDir(@Nonnull final AmazonS3 s3, final URI path) {
    HashSet<URL> map = new HashSet<>();
    rmDir(path, s3, map);
    return map;
  }

  @Nonnull
  public static Map<File, URL> upload(@Nonnull final AmazonS3 s3, @Nullable final URI path, @Nonnull final File file, int retries) {
    try {
      HashMap<File, URL> map = new HashMap<>();
      if (!file.exists()) {
        throw new RuntimeException(file.toString());
      }
      if (null == path) {
        return map;
      }
      if (file.isFile()) {
        uploadFile(file, path, s3, map);
      } else {
        uploadDir(file, path.resolve(file.getName() + "/"), s3, map);
      }
      return map;
    } catch (Throwable e) {
      if (retries > 0) {
        return upload(s3, path, file, retries - 1);
      }
      throw new RuntimeException("Error uploading " + file + " to " + path, e);
    }
  }

  @Nonnull
  public static Map<File, URL> uploadDir(@Nonnull final AmazonS3 s3, @Nullable final URI path, @Nonnull final File file, int retries) {
    try {
      HashMap<File, URL> map = new HashMap<>();
      if (!file.exists()) {
        throw new RuntimeException(file.toString());
      }
      if (null == path) {
        return map;
      }
      if (file.isFile()) {
        uploadFile(file, path, s3, map);
      } else {
        uploadDir(file, path, s3, map);
      }
      return map;
    } catch (Throwable e) {
      if (retries > 0) {
        return uploadDir(s3, path, file, retries - 1);
      }
      throw new RuntimeException("Error uploading " + file + " to " + path, e);
    }
  }

  public static void uploadDir(@Nonnull File file, URI filePath, @Nonnull AmazonS3 s3, HashMap<File, URL> map) {
    String scheme = filePath.getScheme();
    if (scheme.startsWith("s3")) {
      String bucket = filePath.getHost();
      String reportPath = filePath.getPath().replaceAll("//", "/").replaceAll("^/", "");
      logger.info(
          RefString.format("Scanning peer uploads to %s at s3 %s/%s", file.getAbsolutePath(), bucket, reportPath));

      List<S3ObjectSummary> preexistingFiles = listObjects(s3, bucket, reportPath).collect(Collectors.toList());
      for (S3ObjectSummary preexistingFile : preexistingFiles) {
        String key = preexistingFile.getKey();
        //logger.info(RefString.format("Preexisting File: '%s' + '%s'", reportPath, key));
        map.put(
            new File(file, key.substring(reportPath.length())).getAbsoluteFile(),
            s3.getUrl(bucket, key)
        );
      }
    }
    logger.info(RefString.format("Uploading folder %s to %s", file.getAbsolutePath(), filePath.toString()));
    for (File subfile : file.listFiles()) {
      map.putAll(upload(s3, filePath, subfile));
    }
  }

  public static void rmDir(URI filePath, @Nonnull AmazonS3 s3, HashSet<URL> map) {
    String scheme = filePath.getScheme();
    if (scheme.startsWith("s3")) {
      String bucket = filePath.getHost();
      String reportPath = filePath.getPath().replaceAll("//", "/").replaceAll("^/", "");
      logger.info(
          RefString.format("Scanning objects to delete at s3 %s/%s", bucket, reportPath));
      Lists.partition(listObjects(s3, bucket, reportPath)
          .map(S3ObjectSummary::getKey).distinct()
          //.map(key1 -> s3.getUrl(bucket, reportPath + key1).getPath())
          .collect(Collectors.toList()), 100).forEach(page -> {
        try {
          s3.deleteObjects(new DeleteObjectsRequest(bucket)
              .withQuiet(true)
              .withKeys(page.toArray(new String[]{})));
          page.forEach(key -> logger.info("Deleted " + key));
        } catch (Throwable e) {
          logger.warn("Error deleting files", e);
        }
      });
    }
  }

  public static Stream<S3ObjectSummary> listObjects(@Nonnull AmazonS3 s3, String bucket, String reportPath) {
    ObjectListing rootListing = s3.listObjects(new ListObjectsRequest().withBucketName(bucket).withPrefix(reportPath));
    List<ObjectListing> listingStream = getListingStream(s3, rootListing).collect(Collectors.toList());
    return Stream.concat(
        listingStream.stream().map(ObjectListing::getObjectSummaries).flatMap(Collection::stream),
        listingStream.stream().flatMap(listing -> {
          return listing.getCommonPrefixes().stream().flatMap(prefix -> {
            return listObjects(s3, bucket, reportPath + prefix);
          });
        })
    );
  }

  public static Stream<ObjectListing> getListingStream(AmazonS3 s3, ObjectListing listing) {
    if (listing.isTruncated()) {
      return Stream.concat(
          Stream.of(listing),
          getListingStream(s3, s3.listNextBatchOfObjects(listing))
      );
    } else {
      return Stream.of(listing);
    }
  }

  public static void uploadFile(@Nonnull File file, @NotNull URI path, @Nonnull AmazonS3 s3, HashMap<File, URL> map) throws UnsupportedEncodingException {
    String reportPath = path.resolve(URLEncoder.encode(file.getName(), "UTF-8")).getPath()
        .replaceAll("//", "/").replaceAll("^/", "");
    if (path.getScheme().startsWith("s3")) {
      logger.info(RefString.format("Uploading file %s to s3 %s/%s", file.getAbsolutePath(), path.getHost(), reportPath));
      boolean upload;
      try {
        ObjectMetadata existingMetadata;
        if (s3.doesObjectExist(path.getHost(), reportPath))
          existingMetadata = s3.getObjectMetadata(path.getHost(), reportPath);
        else
          existingMetadata = null;
        if (null != existingMetadata) {
          if (existingMetadata.getContentLength() != file.length()) {
            logger.info(RefString.format("Removing outdated file %s/%s", path.getHost(), reportPath));
            s3.deleteObject(path.getHost(), reportPath);
            upload = true;
          } else {
            logger.info(RefString.format("Existing file %s/%s", path.getHost(), reportPath));
            upload = false;
          }
        } else {
          logger.info(RefString.format("Not found file %s/%s", path.getHost(), reportPath));
          upload = true;
        }
      } catch (AmazonS3Exception e) {
        logger.info(RefString.format("Error listing %s/%s", path.getHost(), reportPath), e);
        upload = true;
      }
      if (upload) {
        s3.putObject(
            new PutObjectRequest(path.getHost(), reportPath, file).withCannedAcl(CannedAccessControlList.PublicRead));
      }
      map.put(file.getAbsoluteFile(), s3.getUrl(path.getHost(), reportPath));
    } else {
      try {
        logger.info(RefString.format("Copy file %s to %s", file.getAbsolutePath(), reportPath));
        FileUtils.copyFile(file, new File(reportPath));
      } catch (IOException e) {
        throw Util.throwException(e);
      }
    }
  }
}
