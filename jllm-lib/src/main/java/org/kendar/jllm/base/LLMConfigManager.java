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
      DefaultsSeeder.seed(new File(settingDir));
      var classifiersDir = new File(jllmDir, "classifiers");
      if(classifiersDir.exists() && classifiersDir.isDirectory()){
        loadClassifiers(classifiersDir);
      }
      var agentsDir = new File(jllmDir, "agents");
      if(agentsDir.exists() && agentsDir.isDirectory()){
        loadAgents(agentsDir);
      }
    }
  }

  private static void loadAgents(File agentsDir){
    try(Stream<Path> paths = Files.walk(agentsDir.toPath())){
      paths
        .filter(Files::isRegularFile)
        .filter(p -> p.toString().toLowerCase().endsWith(".xml"))
        .forEach(LLMConfigManager::loadAgent);
    } catch (IOException e) {
      throw new LLMConfigManagerException("Unable to read agents from "+agentsDir.getAbsolutePath(), e);
    }
  }

  private static void loadAgent(Path path){
    try {
      var agent = LLMObjectMapper.getXmlMapper()
        .readValue(path.toFile(), LLMConfigAgent.class);
      register(agent);
    } catch (IOException e) {
      throw new LLMConfigManagerException("Unable to load agent from "+path, e);
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
  private static Map<String,LLMAgent> agents = new HashMap<>();
  public static void register(Object classifier){
    if(classifier instanceof LLMClassifier){
      var lc = (LLMClassifier) classifier;
      classifiers.put(lc.getName(),lc);
    } else if(classifier instanceof LLMAgent){
      var la = (LLMAgent) classifier;
      agents.put(la.getName(),la);
    }

  }
  public static <T>  T get(String name,Class<T> clazz){
    if(classifiers.containsKey(name) && LLMClassifier.class.isAssignableFrom(clazz)){
      return (T)classifiers.get(name);
    }
    if(agents.containsKey(name) && LLMAgent.class.isAssignableFrom(clazz)){
      return (T)agents.get(name);
    }
    throw new LLMConfigManagerException("No item found for "+name+" of type "+clazz.getSimpleName());
  }

  public static LLMClassifier getClassifier(String classifierName) {
    return get(classifierName,LLMClassifier.class);
  }

  public static LLMAgent getAgent(String agentName) {
    return get(agentName,LLMAgent.class);
  }

  /** All registered agents, for building the auto-delegation catalog. */
  public static java.util.Collection<LLMAgent> listAgents() {
    return new ArrayList<>(agents.values());
  }
}
