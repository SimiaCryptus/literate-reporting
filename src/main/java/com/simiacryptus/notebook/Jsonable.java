package com.simiacryptus.notebook;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.TypeFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public interface Jsonable<T extends Jsonable> {
  default ObjectMapper objectMapper() {
    ObjectMapper objectMapper = new ObjectMapper();
    return objectMapper
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(MapperFeature.USE_STD_BEAN_NAMING)
        .activateDefaultTyping(objectMapper.getPolymorphicTypeValidator());
  }

  default String toJson() throws JsonProcessingException {
    return objectMapper().writeValueAsString(this);
  }

  default T fromJson(String text) throws IOException {
    if(null == text) return null;
    if(text.isEmpty()) return null;
    return (T) objectMapper().readValue(new ByteArrayInputStream(text.getBytes()), getJavaType());
  }

  @JsonIgnore
  default JavaType getJavaType() {
    return TypeFactory.defaultInstance().constructType(getClass());
  }
}
