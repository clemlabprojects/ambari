/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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
package org.apache.ambari.logsearch.doc;

import freemarker.template.Configuration;
import freemarker.template.Template;
import io.swagger.jaxrs.config.BeanConfig;
import io.swagger.models.Swagger;
import io.swagger.models.auth.BasicAuthDefinition;
import io.swagger.util.Yaml;
import org.apache.ambari.logsearch.conf.ApiDocConfig;
import org.apache.ambari.logsearch.config.api.LogSearchPropertyDescription;
import org.apache.ambari.logsearch.config.api.ShipperConfigElementDescription;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FileUtils;
import org.reflections.Reflections;
import org.reflections.scanners.FieldAnnotationsScanner;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Class to generate markdown files based on property annotations + rest API docs with swagger
 */
public class LogSearchDocumentationGenerator {

  private static final String SHIPPER_CONFIG_TEMPLATE_KEY = "shipperConfigs";
  private static final String LOGSEARCH_PROPERTIES_TEMPLATE_KEY = "logsearchProperties";
  private static final String LOGFEEDER_PROPERTIES_TEMPLATE_KEY = "logfeederProperties";

  private static final String OUTPUT_DIR_OPTION = "output-dir";
  private static final String GENERATE_REST_API_DOC = "generate-rest-api-doc";

  private static final String LOGSEARCH_PACKAGE = "org.apache.ambari.logsearch";
  private static final String LOGFEEDER_PACKAGE = "org.apache.ambari.logfeeder";
  private static final String CONFIG_API_PACKAGE = "org.apache.ambari.logsearch.config.json.model.inputconfig.impl";

  private static final String TEMPLATES_FOLDER = "templates";

  private static final String LOGSEARCH_PROPERTIES = "logsearch.properties";
  private static final String LOGSEARCH_PROPERTIES_MARKDOWN_TEMPLATE_FILE = "logsearch_properties.md.ftl";
  private static final String LOGSEARCH_PROPERTIES_MARKDOWN_OUTPUT = "logsearch_properties.md";

  private static final String LOGFEEDER_PROPERTIES = "logfeeder.properties";
  private static final String LOGFEEDER_PROPERTIES_MARKDOWN_TEMPLATE_FILE = "logfeeder_properties.md.ftl";
  private static final String LOGFEEDER_PROPERTIES_MARKDOWN_OUTPUT = "logfeeder_properties.md";

  private static final String SHIPPER_CONFIGURATIONS_MARKDOWN_TEMPLATE_FILE = "shipper_configurations.md.ftl";
  private static final String SHIPPER_CONFIGURATIONS_MARKDOWN_OUTPUT = "shipper_configurations.md";

  private static final String SWAGGER_API_DOC_FOLDER = "api-docs";
  private static final String SWAGGER_YAML_FILE_NAME = "logsearch-swagger.yaml";

  public static void main(String[] args) {
    try {
      Options options = new Options();
      options.addOption(Option.builder()
        .desc("Output folder of the markdowns")
        .longOpt(OUTPUT_DIR_OPTION)
        .hasArg()
        .required()
        .type(String.class)
        .build());
      options.addOption(Option.builder()
        .desc("Boolean flag to generate REST API doc")
        .longOpt(GENERATE_REST_API_DOC)
        .hasArg()
        .type(String.class)
        .build());
      CommandLineParser parser = new DefaultParser();
      CommandLine cmdLine = parser.parse(options, args);

      String outputDir = cmdLine.getOptionValue(OUTPUT_DIR_OPTION);
      File outputFileDir = new File(outputDir);
      if (!outputFileDir.exists() || !outputFileDir.isDirectory()) {
        throw new FileNotFoundException(String.format("Directory '%s' does not exist", outputDir));
      }

      final Map<String, List<PropertyDescriptionData>> propertyDescriptions = new ConcurrentHashMap<>();
      final List<String> configPackagesToScan = Arrays.asList(LOGSEARCH_PACKAGE, LOGFEEDER_PACKAGE);
      fillPropertyDescriptions(propertyDescriptions, configPackagesToScan);
      System.out.println(String.format("Number of logsearch.properties configuration descriptors found: %d", propertyDescriptions.get(LOGSEARCH_PROPERTIES).size()));
      System.out.println(String.format("Number of logfeeder.properties configuration descriptors found: %d", propertyDescriptions.get(LOGFEEDER_PROPERTIES).size()));

      final List<String> shipperConfigPackagesToScan = Arrays.asList(CONFIG_API_PACKAGE, LOGFEEDER_PACKAGE);
      ShipperConfigDescriptionDataHolder shipperConfigDescriptionDataHolder = createShipperConfigDescriptions(shipperConfigPackagesToScan);

      System.out.println(String.format("Number of top level section shipper descriptors found: %d", shipperConfigDescriptionDataHolder.getTopLevelConfigSections().size()));
      System.out.println(String.format("Number of input config section shipper descriptors found: %d", shipperConfigDescriptionDataHolder.getInputConfigSections().size()));
      System.out.println(String.format("Number of filter config section shipper descriptors found: %d", shipperConfigDescriptionDataHolder.getFilterConfigSections().size()));
      System.out.println(String.format("Number of mapper section shipper descriptors found: %d", shipperConfigDescriptionDataHolder.getPostMapValuesConfigSections().size()));
      System.out.println(String.format("Number of output config section shipper descriptors found: %d", shipperConfigDescriptionDataHolder.getOutputConfigSections().size()));

      final Configuration freemarkerConfiguration = new Configuration();
      final ClassPathResource cpr = new ClassPathResource(TEMPLATES_FOLDER);
      freemarkerConfiguration.setDirectoryForTemplateLoading(cpr.getFile());

      final Map<String, Object> logsearchModels = new HashMap<>();
      logsearchModels.put(LOGSEARCH_PROPERTIES_TEMPLATE_KEY, propertyDescriptions.get(LOGSEARCH_PROPERTIES));
      File logsearchPropertiesOutputFile = Paths.get(outputDir,LOGSEARCH_PROPERTIES_MARKDOWN_OUTPUT).toFile();
      writeMarkdown(freemarkerConfiguration, LOGSEARCH_PROPERTIES_MARKDOWN_TEMPLATE_FILE, logsearchModels, logsearchPropertiesOutputFile);

      final Map<String, Object> logfeederModels = new HashMap<>();
      logfeederModels.put(LOGFEEDER_PROPERTIES_TEMPLATE_KEY, propertyDescriptions.get(LOGFEEDER_PROPERTIES));
      File logfeederPropertiesOutputFile = Paths.get(outputDir, LOGFEEDER_PROPERTIES_MARKDOWN_OUTPUT).toFile();
      writeMarkdown(freemarkerConfiguration, LOGFEEDER_PROPERTIES_MARKDOWN_TEMPLATE_FILE, logfeederModels, logfeederPropertiesOutputFile);

      final Map<String, Object> shipperConfigModels = new HashMap<>();
      shipperConfigModels.put(SHIPPER_CONFIG_TEMPLATE_KEY, shipperConfigDescriptionDataHolder);

      File shipperConfigsOutputFile = Paths.get(outputDir, SHIPPER_CONFIGURATIONS_MARKDOWN_OUTPUT).toFile();
      writeMarkdown(freemarkerConfiguration, SHIPPER_CONFIGURATIONS_MARKDOWN_TEMPLATE_FILE, shipperConfigModels, shipperConfigsOutputFile);
      if (options.hasLongOption(GENERATE_REST_API_DOC) && "true".equals(cmdLine.getOptionValue(GENERATE_REST_API_DOC))) {
        System.out.println("REST API DOC re-generation is enabled");
        String swaggerYaml = generateSwaggerYaml();
        File swaggerYamlFile = Paths.get(outputDir, SWAGGER_API_DOC_FOLDER, SWAGGER_YAML_FILE_NAME).toFile();
        FileUtils.writeStringToFile(swaggerYamlFile, swaggerYaml, Charset.defaultCharset());
      } else {
        System.out.println("REST API DOC re-generation is disabled");
      }
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static String generateSwaggerYaml() throws Exception {
    ApiDocConfig apiDocConfig = new ApiDocConfig();
    BeanConfig beanConfig = apiDocConfig.swaggerConfig();
    Swagger swagger = beanConfig.getSwagger();
    swagger.addSecurityDefinition("basicAuth", new BasicAuthDefinition());
    beanConfig.configure(swagger);
    beanConfig.scanAndRead();
    return Yaml.mapper().writeValueAsString(swagger);
  }

  private static void writeMarkdown(Configuration freemarkerConfiguration, String templateName,
                                    Map<String, Object> models, File outputFile) throws Exception {
    final StringWriter stringWriter = new StringWriter();
    final Template template = freemarkerConfiguration.getTemplate(templateName);
    template.process(models, stringWriter);
    FileUtils.writeStringToFile(outputFile, stringWriter.toString(), Charset.defaultCharset(), false);
  }

  private static void fillPropertyDescriptions(Map<String, List<PropertyDescriptionData>> propertyDescriptions, List<String> packagesToScan) {
    List<PropertyDescriptionData> propertyDescriptionsList = getPropertyDescriptions(packagesToScan);
    Map<String, List<PropertyDescriptionData>> mapToAdd = propertyDescriptionsList.stream()
      .sorted(Comparator.comparing(PropertyDescriptionData::getName))
      .collect(Collectors.groupingBy(PropertyDescriptionData::getSource));
    propertyDescriptions.putAll(mapToAdd);
  }

  private static ShipperConfigDescriptionDataHolder createShipperConfigDescriptions(List<String> shipperConfigPackagesToScan) {
    final List<ShipperConfigDescriptionData> shipperConfigDescription = new ArrayList<>();
    Reflections reflections = new Reflections(shipperConfigPackagesToScan, new FieldAnnotationsScanner());
    Set<Field> fields = reflections.getFieldsAnnotatedWith(ShipperConfigElementDescription.class);
    for (Field field : fields) {
      ShipperConfigElementDescription description = field.getAnnotation(ShipperConfigElementDescription.class);
      shipperConfigDescription.add(new ShipperConfigDescriptionData(description.path(), description.description(),
        description.examples(), description.defaultValue()));
    }
    shipperConfigDescription.sort(Comparator.comparing(ShipperConfigDescriptionData::getPath));

    final List<ShipperConfigDescriptionData> topLevelConfigSections = shipperConfigDescription.stream()
      .filter(
        s -> (s.getPath().equals("/filter") || s.getPath().equals("/input") || s.getPath().equals("/output")))
      .distinct()
      .collect(Collectors.toList());

    final List<ShipperConfigDescriptionData> inputConfigSection = shipperConfigDescription.stream()
      .filter(
        s -> (s.getPath().startsWith("/input") && !s.getPath().equals("/input")))
      .distinct()
      .collect(Collectors.toList());

    final List<ShipperConfigDescriptionData> filterConfigSection = shipperConfigDescription.stream()
      .filter(
        s -> (s.getPath().startsWith("/filter") && !s.getPath().equals("/filter") && !s.getPath().startsWith("/filter/[]/post_map_values")) || s.getPath().equals("/filter/[]/post_map_values"))
      .distinct()
      .collect(Collectors.toList());

    final List<ShipperConfigDescriptionData> postMapValuesConfigSection = shipperConfigDescription.stream()
      .filter(
        s -> (s.getPath().startsWith("/filter/[]/post_map_values") && !s.getPath().equals("/filter/[]/post_map_values")))
      .distinct()
      .collect(Collectors.toList());

    final List<ShipperConfigDescriptionData> outputConfigSection = shipperConfigDescription.stream()
      .filter(
        s -> s.getPath().startsWith("/output/[]"))
      .distinct()
      .collect(Collectors.toList());

    return new ShipperConfigDescriptionDataHolder(topLevelConfigSections, inputConfigSection, filterConfigSection,
      postMapValuesConfigSection, outputConfigSection);
  }

  private static List<PropertyDescriptionData> getPropertyDescriptions(List<String> packagesToScan) {
    List<PropertyDescriptionData> result = new ArrayList<>();
    for (String packageToScan : packagesToScan) {
      Reflections reflections = new Reflections(packageToScan, new FieldAnnotationsScanner(), new MethodAnnotationsScanner());
      Set<Field> fields = reflections.getFieldsAnnotatedWith(LogSearchPropertyDescription.class);
      for (Field field : fields) {
        LogSearchPropertyDescription propDescription = field.getAnnotation(LogSearchPropertyDescription.class);
        for (String source : propDescription.sources()) {
          result.add(new PropertyDescriptionData(propDescription.name(), propDescription.description(), propDescription.examples(), propDescription.defaultValue(), source));
        }
      }
      Set<Method> methods = reflections.getMethodsAnnotatedWith(LogSearchPropertyDescription.class);
      for (Method method : methods) {
        LogSearchPropertyDescription propDescription = method.getAnnotation(LogSearchPropertyDescription.class);
        for (String source : propDescription.sources()) {
          result.add(new PropertyDescriptionData(propDescription.name(), propDescription.description(), propDescription.examples(), propDescription.defaultValue(), source));
        }
      }
    }
    return result;
  }
}
