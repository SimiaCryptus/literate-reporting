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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * The type Json query.
 *
 * @param <T> the type parameter
 */
public class JsonQuery<T> extends StringQuery<T> {
  private ObjectMapper mapper = new ObjectMapper()
      //.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL)
      .enable(SerializationFeature.INDENT_OUTPUT);

  /**
   * Instantiates a new Json query.
   *
   * @param log the log
   */
  public JsonQuery(MarkdownNotebookOutput log) {
    super(log);
  }

  @Override
  protected T fromString(String text) throws IOException {
    try {
      return (T) mapper.readValue(new ByteArrayInputStream(text.getBytes()), getValue().getClass());
    } catch (Throwable e) {
      logger.warn("Error deserializing", e);
      return null;
    }
  }

  @Override
  protected String toString(T value) throws JsonProcessingException {
    return mapper.writeValueAsString(value);
  }

  /**
   * Gets mapper.
   *
   * @return the mapper
   */
  public ObjectMapper getMapper() {
    return mapper;
  }

  /**
   * Sets mapper.
   *
   * @param mapper the mapper
   * @return the mapper
   */
  public JsonQuery<T> setMapper(ObjectMapper mapper) {
    this.mapper = mapper;
    return this;
  }
}
