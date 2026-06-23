package org.kendar.jllm.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

public class LLMObjectMapper {
  private static ObjectMapper objectMapper=null;
  private static XmlMapper xmlMapper=null;
  private static final Object lock = new Object();
  public static ObjectMapper getObjectMapper(){
    if(objectMapper==null){
      synchronized (lock) {
        objectMapper = new ObjectMapper();
        objectMapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // Ollama returns the chat reply as a single "message" object while LLMResponse
        // models it as a list, so accept a lone object where an array is expected.
        objectMapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
      }
    }
    return objectMapper;
  }

  public static XmlMapper getXmlMapper(){
    if(xmlMapper==null){
      synchronized (lock) {
        xmlMapper = new XmlMapper();
        xmlMapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
      }
    }
    return xmlMapper;
  }
}
