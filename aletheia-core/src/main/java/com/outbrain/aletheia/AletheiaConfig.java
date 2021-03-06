package com.outbrain.aletheia;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.outbrain.aletheia.configuration.endpoint.EndPointTemplate;
import com.outbrain.aletheia.configuration.endpoint.InMemoryEndPointTemplate;
import com.outbrain.aletheia.configuration.routing.ExtendedRoutingInfo;
import com.outbrain.aletheia.configuration.routing.Route;
import com.outbrain.aletheia.configuration.routing.RoutingInfo;
import com.outbrain.aletheia.datum.consumption.ConsumptionEndPoint;
import com.outbrain.aletheia.datum.production.ProductionEndPoint;
import com.outbrain.aletheia.datum.serialization.DatumSerDe;
import org.apache.commons.lang3.text.StrLookup;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class AletheiaConfig {

  private static final Logger logger = LoggerFactory.getLogger(AletheiaConfig.class);

  public static final String BREADCRUMBS = "breadcrumbs";

  public static final String ENDPOINTS_CONFIG_PATH = "aletheia.endpoints.config.path";
  public static final String ROUTING_CONFIG_PATH = "aletheia.routing.config.path";
  public static final String ENDPOINT_GROUPS_CONFIG_PATH = "aletheia.endpoint.groups.config.path";
  public static final String SERDES_CONFIG_PATH = "aletheia.serdes.config.path";

  public static final String SERDES = "aletheia.serdes.config";
  public static final String ENDPOINT_GROUPS = "aletheia.endpoint.groups.config";
  public static final String ROUTING = "aletheia.routing.config";
  public static final String ENDPOINTS = "aletheia.endpoints.config";

  public static final String DEFAULT_ENDPOINT = "aletheia.endpoint.defaultEndpoint";
  public static final String MULTIPLE_CONFIGURATIONS_PATH = "aletheia.multiple.config.path";
  public static final String ENDPOINT_GROUPS_EXTENSION = "aletheia.multiple.config.endpoint.groups.extension";
  public static final String ENDPOINTS_EXTENSION = "aletheia.multiple.config.endpoints.extension";
  public static final String ROUTING_EXTENSION = "aletheia.multiple.config.routing.extension";
  public static final String SERDES_EXTENSION = "aletheia.multiple.config.serdes.extension";

  private static final String CONSUME_SECTION = "consume";
  private static final String PRODUCE_SECTION = "produce";

  private final ObjectMapper objectMapper;
  private final String endPointsConfig;
  private final String routingConfig;
  private final String endPointGroupsConfig;
  private final String serDeConfig;
  private final Properties properties;

  private static final ObjectReader objectReader = new ObjectMapper().reader();
  private static final Map<String, Class<? extends EndPointTemplate>> customEndPointTemplates = new HashMap<>();


  public AletheiaConfig(final Properties properties) {
    this(properties,
         getPropertyOrReadConfig(ENDPOINTS, ENDPOINTS_CONFIG_PATH, ENDPOINTS_EXTENSION, properties),
         getPropertyOrReadConfig(ROUTING, ROUTING_CONFIG_PATH, ROUTING_EXTENSION, properties),
         getPropertyOrReadConfig(ENDPOINT_GROUPS, ENDPOINT_GROUPS_CONFIG_PATH, ENDPOINT_GROUPS_EXTENSION, properties),
         getPropertyOrReadConfig(SERDES, SERDES_CONFIG_PATH, SERDES_EXTENSION, properties));
  }

  private AletheiaConfig(final Properties properties,
                         final String endPointsConfig,
                         final String routingConfig,
                         final String endPointGroupsConfig,
                         final String serDeConfig) {

    this.properties = properties;

    Preconditions.checkNotNull(endPointsConfig, "endpoint config cannot be null");
    Preconditions.checkNotNull(routingConfig, "routing config cannot be null");
    Preconditions.checkNotNull(endPointGroupsConfig, "endpoint group config cannot be null");
    Preconditions.checkNotNull(serDeConfig, "serDe config cannot be null");

    this.endPointsConfig = endPointsConfig;
    this.routingConfig = routingConfig;
    this.endPointGroupsConfig = endPointGroupsConfig;
    this.serDeConfig = serDeConfig;

    objectMapper = createObjectMapper();
  }

  private static String getPropertyOrReadConfig(final String property,
                                                final String configPathProperty,
                                                final String extensionProperty,
                                                final Properties properties) {
    if (!Strings.isNullOrEmpty(properties.getProperty(property))) {
      return properties.getProperty(property);
    }

    final Set<String> configPaths = new HashSet<>();
    configPaths.add(properties.getProperty(configPathProperty));

    final String multipleConfigurationPath = properties.getProperty(MULTIPLE_CONFIGURATIONS_PATH);
    final String extension = extensionProperty != null ? properties.getProperty(extensionProperty) : null;

    if (!Strings.isNullOrEmpty(multipleConfigurationPath) && !Strings.isNullOrEmpty(extension)) {
      try {
        final Enumeration<URL> resourceFolderUrls = ClassLoader.getSystemResources(multipleConfigurationPath);
        while (resourceFolderUrls.hasMoreElements()) {
          final URL folderUrl = resourceFolderUrls.nextElement();
          final Set<String> resourceFiles = getResourceFilesFromFolder(folderUrl, multipleConfigurationPath);
          final Set<String> filteredResourceFiles = Sets.filter(resourceFiles, new Predicate<String>() {
            @Override
            public boolean apply(String input) {
              return input != null && input.endsWith(extension);
            }
          });
          configPaths.addAll(filteredResourceFiles);
        }
      }
      catch (IOException e) {
        logger.error("Could not read resources in " + multipleConfigurationPath, e);
      }
    }

    return readConfigFiles(configPaths);
  }

  private static Set<String> getResourceFilesFromFolder(URL folderUrl, String homePath) {
    final String folderPath = folderUrl.getPath();
    final Set<String> result = new HashSet<>();
    try {
      if (folderUrl.getProtocol().equals("jar")) {
        final JarURLConnection urlConnection = (JarURLConnection) folderUrl.openConnection();
        final JarFile jar = urlConnection.getJarFile();
        final Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          final String fileName = entries.nextElement().getName();
          if (fileName.startsWith(homePath + "/")) {
            result.add(fileName);
          }
        }
      } else {
        final File folder = new File(folderPath);
        final String[] files = folder.list();
        if (files != null) {
          for (final String file : files) {
            result.add(homePath + "/" + file);
          }
        }
      }
    }
    catch (IOException e) {
      logger.error("Error while reading from: " + folderPath, e);
    }
    return result;
  }

  public Properties getProperties() {
    return properties;
  }

  private ObjectMapper createObjectMapper() {
    final ObjectMapper jsonMapper = new ObjectMapper();

    jsonMapper.registerSubtypes(new NamedType(InMemoryEndPointTemplate.class, InMemoryEndPointTemplate.TYPE));

    for (final Map.Entry<String, Class<? extends EndPointTemplate>> custom : customEndPointTemplates.entrySet()) {
      jsonMapper.registerSubtypes(new NamedType(custom.getValue(), custom.getKey()));
    }

    return jsonMapper;
  }


  private String resolve(final String configurationWithPlaceholders, final Properties placeholderValues)
          throws IOException {

    return new StrSubstitutor(
            new StrLookup<String>() {
              @Override
              public String lookup(final String key) {
                Preconditions.checkNotNull(placeholderValues.getProperty(key),
                                           "placeholder: \"${%s}\" was not assigned",
                                           key);
                return placeholderValues.getProperty(key);
              }
            },
            "${",
            "}",
            '#')
            .replace(configurationWithPlaceholders);
  }

  private static String readConfigFiles(final Set<String> configPaths) {
    if (configPaths == null || configPaths.isEmpty()) {
      throw new RuntimeException("No Aletheia config files to load");
    }

    final JsonNode mergedConfig = objectReader.createObjectNode();

    for (final String resourcePath : configPaths) {
      if (!Strings.isNullOrEmpty(resourcePath)) {
        logger.info("Loading Aletheia config from " + resourcePath);
        try {
          final InputStream resourceAsStream = AletheiaConfig.class.getClassLoader().getResourceAsStream(resourcePath);
          final JsonNode json = objectReader.readTree(resourceAsStream);

          final Iterator<Map.Entry<String, JsonNode>> fieldsIterator = json.fields();
          while (fieldsIterator.hasNext()) {
            final Map.Entry<String, JsonNode> entry = fieldsIterator.next();
            final String key = entry.getKey();
            if (mergedConfig.has(key)) {
              throw new RuntimeException("Element " + key + " in file " + resourcePath + " exists in previously loaded Aletheia configuration");
            }
            ((ObjectNode) mergedConfig).set(key, entry.getValue());
          }
        } catch (final IOException e) {
          logger.error("Failed to read config from " + resourcePath, e);
        }
      }
    }
    return mergedConfig.toString();
  }

  private <T> T readSingleConfigKey(final String config,
                                    final String key,
                                    final Properties placeholders,
                                    final TypeReference<T> typeReference) {
    return readSingleConfigKey(config, key, Functions.<JsonNode>identity(), placeholders, typeReference);
  }

  private <T> T readSingleConfigKey(final String config,
                                    final String key,
                                    final Function<JsonNode, JsonNode> transformation,
                                    final Properties placeholders,
                                    final TypeReference<T> typeReference) {
    try {
      final JsonNode configSectionNode = objectReader.readTree(config).get(key);
      if (configSectionNode == null) {
        if (typeReference.getType() == EndPointTemplate.class &&
                !Strings.isNullOrEmpty(properties.getProperty(DEFAULT_ENDPOINT))) {
          final String defaultEndpointJson = properties.getProperty(DEFAULT_ENDPOINT);
          logger.info("Created default endpoint for " + key);
          return objectMapper.readValue(defaultEndpointJson, typeReference);
        }
        throw new NoSuchElementException(String.format("no such key: \"%s\" in config: \"%s\"", key, config));
      }
      final JsonNode transformedNode = transformation.apply(configSectionNode);
      Preconditions.checkNotNull(transformedNode, String.format("transformation error while parsing  key: \"%s\" in config: \"%s\"", key, config));
      final String configSectionString = transformedNode.toString();
      final String resolvedConfigSectionString = resolve(configSectionString, placeholders);
      return objectMapper.readValue(resolvedConfigSectionString, typeReference);
    } catch (final IOException e) {
      throw new RuntimeException(String.format("error while reading configuration key: \"%s\" in config: \"%s\"",
                                               key,
                                               config),
                                 e);
    }
  }

  public RoutingInfo getRouting(final String datumTypeId) {
    final ExtendedRoutingInfo extendedRoutingInfo = readSingleConfigKey(routingConfig,
                                                                        datumTypeId,
                                                                        properties,
                                                                        new TypeReference<ExtendedRoutingInfo>() {
                                                                        });
    final ImmutableList<List<Route>> expendedRouteGroupIds =
            FluentIterable.from(extendedRoutingInfo.getRouteGroupIds())
                          .transform(new Function<String, List<Route>>() {
                            @Override
                            public List<Route> apply(final String routeGroupId) {
                              return readSingleConfigKey(endPointGroupsConfig,
                                                         routeGroupId,
                                                         properties,
                                                         new TypeReference<List<Route>>() {
                                                         });
                            }
                          })
                          .toList();

    final List<Route> allRoutes =
            Lists.newArrayList(Iterables.concat(extendedRoutingInfo.getRoutes(),
                                                Iterables.concat(expendedRouteGroupIds)));

    return new RoutingInfo(allRoutes,
                           extendedRoutingInfo.getDatumKeySelectorClassName());
  }

  public <TDomainClass> DatumSerDe<TDomainClass> serDe(final String serDeId) {
    return readSingleConfigKey(serDeConfig,
                               serDeId,
                               properties,
                               new TypeReference<DatumSerDe<TDomainClass>>() {
                               });
  }

  public ProductionEndPoint getProductionEndPoint(final String endPointId) {
    final EndPointTemplate endPointTemplate =
            readSingleConfigKey(endPointsConfig,
                                endPointId,
                                new Function<JsonNode, JsonNode>() {
                                  @Override
                                  public JsonNode apply(final JsonNode jsonNode) {
                                    ((ObjectNode) jsonNode).remove(CONSUME_SECTION);
                                    return jsonNode;
                                  }
                                },
                                properties,
                                new TypeReference<EndPointTemplate>() {
                                });
    return endPointTemplate.getProductionEndPoint(endPointId);
  }

  public String getBreadcrumbEndPointId(final String datumTypeId) {
    return readSingleConfigKey(routingConfig,
                               datumTypeId,
                               new Function<JsonNode, JsonNode>() {
                                 @Override
                                 public JsonNode apply(final JsonNode jsonNode) {
                                   return jsonNode.get(BREADCRUMBS) != null ?
                                          jsonNode.get(BREADCRUMBS) :
                                          JsonNodeFactory.instance.textNode("");
                                 }
                               },
                               properties,
                               new TypeReference<String>() {
                               });
  }

  public ProductionEndPoint getBreadcrumbsProductionEndPoint(final String datumTypeId) {
    final String breadcrumbEndPointId = getBreadcrumbEndPointId(datumTypeId);
    return !Strings.isNullOrEmpty(breadcrumbEndPointId) ? getProductionEndPoint(breadcrumbEndPointId) : null;
  }

  public ConsumptionEndPoint getConsumptionEndPoint(final String endPointId) {
    final EndPointTemplate endPointTemplate =
            readSingleConfigKey(endPointsConfig,
                                endPointId,
                                new Function<JsonNode, JsonNode>() {
                                  @Override
                                  public JsonNode apply(final JsonNode jsonNode) {
                                    ((ObjectNode) jsonNode).remove(PRODUCE_SECTION);
                                    return jsonNode;
                                  }
                                },
                                properties,
                                new TypeReference<EndPointTemplate>() {
                                });
    return endPointTemplate.getConsumptionEndPoint(endPointId);
  }

  public DatumProducerConfig getDatumProducerConfig() {
    Preconditions.checkNotNull(properties.getProperty("aletheia.producer.source"),
                               "aletheia.producer.source must not be null");
    return new DatumProducerConfig(Integer.parseInt(properties.getProperty("aletheia.producer.incarnation", "1")),
                                   properties.getProperty("aletheia.producer.source"));
  }

  public DatumConsumerStreamConfig getDatumConsumerConfig() {
    Preconditions.checkNotNull(properties.getProperty("aletheia.consumer.source"),
                               "aletheia.consumer.source must not be null");
    return new DatumConsumerStreamConfig(Integer.parseInt(properties.getProperty("aletheia.consumer.incarnation", "1")),
                                         properties.getProperty("aletheia.consumer.source"));
  }

  public static void registerEndPointTemplate(final String type,
                                              final Class<? extends EndPointTemplate> endPointTemplateClass) {
    customEndPointTemplates.put(type, endPointTemplateClass);
  }

}

