package org.kendar.jllm.base;

import org.kendar.jllm.LLMSettings;
import org.kendar.jllm.exceptions.LLMConfigManagerException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class LLMConfigManager {
  public static void initialize(LLMSettings settings){
    List<String> settingDirs = settings.getSettingDirs();
    if(settingDirs == null || settingDirs.isEmpty()){
      settingDirs = new ArrayList<>();
      settingDirs.add(System.getProperty("user.dir"));
    }
    for(var settingDir : settingDirs){
      var jllmDir = new File(settingDir, ".jllm");
      if(!jllmDir.exists()){
        if(!jllmDir.mkdirs()){
          throw new LLMConfigManagerException("Unable to create directory "+jllmDir.getAbsolutePath());
        }
      }
      var classifiersDir = new File(jllmDir, "classifiers");
      if(classifiersDir.exists() && classifiersDir.isDirectory()){
        loadClassifiers(classifiersDir);
      }
    }
  }

  private static void loadClassifiers(File classifiersDir){
    try(Stream<Path> paths = Files.walk(classifiersDir.toPath())){
      paths
        .filter(Files::isRegularFile)
        .filter(p -> p.toString().toLowerCase().endsWith(".json"))
        .forEach(LLMConfigManager::loadClassifier);
    } catch (IOException e) {
      throw new LLMConfigManagerException("Unable to read classifiers from "+classifiersDir.getAbsolutePath(), e);
    }
  }

  private static void loadClassifier(Path path){
    try {
      var classifier = LLMObjectMapper.getObjectMapper()
        .readValue(path.toFile(), LLMConfigClassifier.class);
      register(classifier);
    } catch (IOException e) {
      throw new LLMConfigManagerException("Unable to load classifier from "+path, e);
    }
  }

  private static Map<String,LLMClassifier> classifiers = new HashMap<>();
  public static void register(Object classifier){
    if(classifier instanceof LLMClassifier){
      var lc = (LLMClassifier) classifier;
      classifiers.put(lc.getName(),lc);
    }

  }
  public static <T>  T get(String name,Class<T> clazz){
    if(!classifiers.containsKey(name) && clazz.isAssignableFrom(LLMClassifier.class)){
      return (T)classifiers.get(name);
    }
    throw new LLMConfigManagerException("No item found for "+name+" of type "+clazz.getSimpleName());
  }
}
