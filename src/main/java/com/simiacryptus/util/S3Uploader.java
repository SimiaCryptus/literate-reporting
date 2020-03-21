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

import com.amazonaws.regions.*;
import com.amazonaws.regions.Region;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.simiacryptus.notebook.MarkdownNotebookOutput;
import com.simiacryptus.ref.lang.RefUtil;
import com.simiacryptus.ref.wrappers.RefString;
import com.simiacryptus.util.test.NotebookReportBase;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    if(region.equals("US")) {
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
  public static Map<File, URL> upload(@Nonnull final AmazonS3 s3, @Nullable final URI path, @Nonnull final File file, int retries) {
    try {
      HashMap<File, URL> map = new HashMap<>();
      if (!file.exists()) {
        throw new RuntimeException(file.toString());
      }
      if (null == path) {
        return map;
      }
      String bucket = path.getHost();
      String scheme = path.getScheme();
      if (file.isFile()) {
        String reportPath = path.resolve(URLEncoder.encode(file.getName(), "UTF-8")).getPath()
            .replaceAll("//", "/").replaceAll("^/", "");
        if (scheme.startsWith("s3")) {
          logger.info(RefString.format("Uploading file %s to s3 %s/%s", file.getAbsolutePath(), bucket, reportPath));
          boolean upload;
          try {
            ObjectMetadata existingMetadata;
            if (s3.doesObjectExist(bucket, reportPath))
              existingMetadata = s3.getObjectMetadata(bucket, reportPath);
            else
              existingMetadata = null;
            if (null != existingMetadata) {
              if (existingMetadata.getContentLength() != file.length()) {
                logger.info(RefString.format("Removing outdated file %s/%s", bucket, reportPath));
                s3.deleteObject(bucket, reportPath);
                upload = true;
              } else {
                logger.info(RefString.format("Existing file %s/%s", bucket, reportPath));
                upload = false;
              }
            } else {
              logger.info(RefString.format("Not found file %s/%s", bucket, reportPath));
              upload = true;
            }
          } catch (AmazonS3Exception e) {
            logger.info(RefString.format("Error listing %s/%s", bucket, reportPath), e);
            upload = true;
          }
          if (upload) {
            s3.putObject(
                new PutObjectRequest(bucket, reportPath, file).withCannedAcl(CannedAccessControlList.PublicRead));
          }
          RefUtil.freeRef(map.put(file.getAbsoluteFile(), s3.getUrl(bucket, reportPath)));
        } else {
          try {
            logger.info(RefString.format("Copy file %s to %s", file.getAbsolutePath(), reportPath));
            FileUtils.copyFile(file, new File(reportPath));
          } catch (IOException e) {
            throw Util.throwException(e);
          }
        }
      } else {
        URI filePath = path.resolve(file.getName() + "/");
        if (scheme.startsWith("s3")) {
          String reportPath = filePath.getPath().replaceAll("//", "/").replaceAll("^/", "");
          logger.info(
              RefString.format("Scanning peer uploads to %s at s3 %s/%s", file.getAbsolutePath(), bucket, reportPath));
          List<S3ObjectSummary> preexistingFiles = s3
              .listObjects(new ListObjectsRequest().withBucketName(bucket).withPrefix(reportPath)).getObjectSummaries()
              .stream().collect(Collectors.toList());
          for (S3ObjectSummary preexistingFile : preexistingFiles) {
            logger.info(RefString.format("Preexisting File: '%s' + '%s'", reportPath, preexistingFile.getKey()));
            RefUtil.freeRef(map.put(
                new File(file, preexistingFile.getKey()).getAbsoluteFile(),
                s3.getUrl(bucket, reportPath + preexistingFile.getKey())
            ));
          }
        }
        logger.info(RefString.format("Uploading folder %s to %s", file.getAbsolutePath(), filePath.toString()));
        for (File subfile : file.listFiles()) {
          map.putAll(upload(s3, filePath, subfile));
        }
      }
      return map;
    } catch (Throwable e) {
      if (retries > 0) {
        return upload(s3, path, file, retries - 1);
      }
      throw new RuntimeException("Error uploading " + file + " to " + path, e);
    }
  }
}
