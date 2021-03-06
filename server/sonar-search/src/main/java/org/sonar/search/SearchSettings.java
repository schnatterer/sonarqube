/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.search;

import org.apache.commons.lang.StringUtils;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.process.MessageException;
import org.sonar.process.ProcessConstants;
import org.sonar.process.Props;
import org.sonar.search.script.ListUpdate;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

class SearchSettings {

  private static final Logger LOGGER = LoggerFactory.getLogger(SearchSettings.class);

  public static final String PROP_HTTP_PORT = "sonar.search.httpPort";
  public static final String PROP_MARVEL_HOSTS = "sonar.search.marvelHosts";

  private final Props props;
  private final Set<String> masterHosts = new LinkedHashSet<String>();
  private final String clusterName;
  private final String hostName;
  private final int tcpPort;

  SearchSettings(Props props) {
    this.props = props;
    masterHosts.addAll(Arrays.asList(StringUtils.split(props.value(ProcessConstants.CLUSTER_MASTER_HOST, ""), ",")));
    clusterName = props.value(ProcessConstants.CLUSTER_NAME);
    hostName = props.value(ProcessConstants.SEARCH_HOST);
    Integer port = props.valueAsInt(ProcessConstants.SEARCH_PORT);
    if (port == null) {
      throw new MessageException("Property is not set: " + ProcessConstants.SEARCH_PORT);
    }
    tcpPort = port.intValue();
  }

  boolean inCluster() {
    return props.valueAsBoolean(ProcessConstants.CLUSTER_ACTIVATE, false);
  }

  boolean isMaster() {
    return props.valueAsBoolean(ProcessConstants.CLUSTER_MASTER, false);
  }

  String clusterName() {
    return clusterName;
  }

  int tcpPort() {
    return tcpPort;
  }
  
  String hostName() {
    return hostName;
  }

  Settings build() {
    ImmutableSettings.Builder builder = ImmutableSettings.settingsBuilder();
    configureFileSystem(builder);
    configureStorage(builder);
    configurePlugins(builder);
    configureNetwork(builder);
    configureCluster(builder);
    configureMarvel(builder);
    return builder.build();
  }

  private void configureFileSystem(ImmutableSettings.Builder builder) {
    File homeDir = props.nonNullValueAsFile(ProcessConstants.PATH_HOME);
    File dataDir, workDir, logDir;

    // data dir
    String dataPath = props.value(ProcessConstants.PATH_DATA);
    if (StringUtils.isNotEmpty(dataPath)) {
      dataDir = new File(dataPath, "es");
    } else {
      dataDir = new File(homeDir, "data/es");
    }
    builder.put("path.data", dataDir.getAbsolutePath());

    // working dir
    String workPath = props.value(ProcessConstants.PATH_TEMP);
    if (StringUtils.isNotEmpty(workPath)) {
      workDir = new File(workPath);
    } else {
      workDir = new File(homeDir, "temp");
    }
    builder.put("path.work", workDir.getAbsolutePath());
    builder.put("path.plugins", workDir.getAbsolutePath());

    // log dir
    String logPath = props.value(ProcessConstants.PATH_LOGS);
    if (StringUtils.isNotEmpty(logPath)) {
      logDir = new File(logPath);
    } else {
      logDir = new File(homeDir, "log");
    }
    builder.put("path.logs", logDir.getAbsolutePath());
  }

  private void configurePlugins(ImmutableSettings.Builder builder) {
    builder
      .put("script.default_lang", "native")
      .put(String.format("script.native.%s.type", ProcessConstants.ES_PLUGIN_LISTUPDATE_SCRIPT_NAME),
        ListUpdate.UpdateListScriptFactory.class.getName());
  }

  private void configureNetwork(ImmutableSettings.Builder builder) {
    // disable multicast
    builder.put("discovery.zen.ping.multicast.enabled", "false");
    builder.put("transport.tcp.port", tcpPort);
    if (hostName != null) {
      builder.put("transport.host", hostName);
    }
    // Elasticsearch sets the default value of TCP reuse address to true only on non-MSWindows machines, but why ?
    builder.put("network.tcp.reuse_address", true);

    Integer httpPort = props.valueAsInt(PROP_HTTP_PORT);
    if (httpPort == null) {
      // standard configuration
      builder.put("http.enabled", false);
    } else {
      LOGGER.warn(String.format(
        "Elasticsearch HTTP connector is enabled on port %d. MUST NOT BE USED FOR PRODUCTION", httpPort));
      // see https://github.com/lmenezes/elasticsearch-kopf/issues/195
      builder.put("http.cors.enabled", true);
      builder.put("http.enabled", true);
      if (hostName != null) {
        builder.put("http.host", hostName);
      } else {
        builder.put("http.host", "127.0.0.1");
      }
      builder.put("http.port", httpPort);
    }
  }

  private void configureStorage(ImmutableSettings.Builder builder) {
    builder
      .put("index.number_of_shards", "1")
      .put("index.refresh_interval", "30s")
      .put("indices.store.throttle.type", "none");
  }

  private void configureCluster(ImmutableSettings.Builder builder) {
    int replicationFactor = 0;
    if (inCluster()) {
      replicationFactor = 1;
      if (isMaster()) {
        LOGGER.info("Elasticsearch cluster enabled. Master node.");
        builder.put("node.master", true);
      } else if (!masterHosts.isEmpty()) {
        LOGGER.info("Elasticsearch cluster enabled. Node connecting to master: {}", masterHosts);
        builder.put("discovery.zen.ping.unicast.hosts", StringUtils.join(masterHosts, ","));
        builder.put("node.master", false);
        builder.put("discovery.zen.minimum_master_nodes", 1);
      } else {
        throw new MessageException(String.format("Not an Elasticsearch master nor slave. Please check properties %s and %s",
          ProcessConstants.CLUSTER_MASTER, ProcessConstants.CLUSTER_MASTER_HOST));
      }
    }
    builder.put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, replicationFactor);
    builder.put("cluster.name", clusterName);
    builder.put("cluster.routing.allocation.awareness.attributes", "rack_id");
    builder.put("node.rack_id", props.value(ProcessConstants.CLUSTER_NODE_NAME, "unknown"));
    builder.put("node.name", props.value(ProcessConstants.CLUSTER_NODE_NAME));
  }

  private void configureMarvel(ImmutableSettings.Builder builder) {
    Set<String> marvels = new TreeSet<String>();
    marvels.addAll(Arrays.asList(StringUtils.split(props.value(PROP_MARVEL_HOSTS, ""), ",")));

    // If we're collecting indexing data send them to the Marvel host(s)
    if (!marvels.isEmpty()) {
      String hosts = StringUtils.join(marvels, ",");
      LOGGER.info(String.format("Elasticsearch Marvel is enabled for %s", hosts));
      builder.put("marvel.agent.exporter.es.hosts", hosts);
    }
  }
}
