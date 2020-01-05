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

import com.simiacryptus.ref.lang.RefAware;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

public @RefAware
class EditImageQuery extends HtmlQuery<BufferedImage> {

  private final int heightPx;
  private final int widthPx;
  private final String initUrl;

  public EditImageQuery(NotebookOutput log, BufferedImage image) {
    super(log);
    this.widthPx = image.getWidth();
    this.heightPx = image.getHeight();
    this.width = (widthPx + 40) + "px";
    this.height1 = (heightPx + 100) + "px";
    this.height2 = (heightPx + 100) + "px";
    this.initUrl = "etc/" + this.rawId + "_init.png";
    save(log, image);
  }

  @Override
  protected String getActiveHtml() {
    return "<html>" + getHeader() + "<body style=\"margin: 0;\">" + getFormInnerHtml() + "</body></html>";
  }

  @Override
  protected String getDisplayHtml() {
    return "<html>" + getHeader() + "<body style=\"margin: 0;\">" + getFormInnerHtml() + "</body></html>";
  }

  protected String getFormInnerHtml() {
    return "<div id=\"paint-app\"></div>";
  }

  protected String getHeader() {
    try {
      final String jsInject = String.format("init('%s', '%s', %s, %s)", rawId, initUrl, widthPx, heightPx);
      return "<style>\n" + IOUtils.toString(getClass().getClassLoader().getResource("paint.css"), "UTF-8")
          + "\n</style>" + "<script>\n" + IOUtils.toString(getClass().getClassLoader().getResource("paint.js"), "UTF-8")
          + "\n" + jsInject + "\n</script>";
    } catch (IOException e) {
      logger.warn("Error loading javascript", e);
      return "";
    }
  }

  public BufferedImage save(NotebookOutput log, BufferedImage image) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      ImageIO.write(image, "PNG", buffer);
      FileUtils.writeByteArrayToFile(new File(log.getRoot(), initUrl), buffer.toByteArray());
    } catch (IOException e) {
      e.printStackTrace();
    }
    return image;
  }

  @Override
  public BufferedImage valueFromParams(Map<String, String> parms,
                                       Map<String, String> files) throws IOException {
    String postData = files.get("postData");
    String prefix = "data:image/png;base64,";
    assert (postData.startsWith(prefix));
    postData = postData.substring(prefix.length());
    return save(log, ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(postData))));
  }

}
