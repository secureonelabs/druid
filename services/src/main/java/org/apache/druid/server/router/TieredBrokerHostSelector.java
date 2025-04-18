/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.apache.druid.server.router;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import org.apache.druid.client.selector.Server;
import org.apache.druid.discovery.DiscoveryDruidNode;
import org.apache.druid.discovery.DruidNodeDiscovery;
import org.apache.druid.discovery.DruidNodeDiscoveryProvider;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.lifecycle.LifecycleStop;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.query.Query;
import org.apache.druid.server.coordinator.rules.LoadRule;
import org.apache.druid.server.coordinator.rules.Rule;
import org.apache.druid.sql.http.SqlQuery;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 */
public class TieredBrokerHostSelector
{
  private static EmittingLogger log = new EmittingLogger(TieredBrokerHostSelector.class);

  private final CoordinatorRuleManager ruleManager;
  private final TieredBrokerConfig tierConfig;
  private final List<TieredBrokerSelectorStrategy> strategies;

  // brokerService -> broker-nodes-holder
  private final ConcurrentHashMap<String, NodesHolder> servers = new ConcurrentHashMap<>();

  private final DruidNodeDiscoveryProvider druidNodeDiscoveryProvider;

  private final Object lock = new Object();

  private volatile boolean started = false;

  private static final Function<DiscoveryDruidNode, Server> TO_SERVER = new Function<>()
  {
    @Override
    public Server apply(final DiscoveryDruidNode instance)
    {
      return new Server()
      {
        @Override
        public String getHost()
        {
          return instance.getDruidNode().getHostAndPortToUse();
        }

        @Override
        public String getAddress()
        {
          return instance.getDruidNode().getHost();
        }

        @Override
        public int getPort()
        {
          return instance.getDruidNode().getPortToUse();
        }

        @Override
        public String getScheme()
        {
          return instance.getDruidNode().getServiceScheme();
        }
      };
    }
  };

  @Inject
  public TieredBrokerHostSelector(
      CoordinatorRuleManager ruleManager,
      TieredBrokerConfig tierConfig,
      DruidNodeDiscoveryProvider druidNodeDiscoveryProvider,
      List<TieredBrokerSelectorStrategy> strategies
  )
  {
    this.ruleManager = ruleManager;
    this.tierConfig = tierConfig;
    this.druidNodeDiscoveryProvider = druidNodeDiscoveryProvider;
    this.strategies = strategies;
  }

  @LifecycleStart
  public void start()
  {
    synchronized (lock) {
      if (started) {
        return;
      }

      for (Map.Entry<String, String> entry : tierConfig.getTierToBrokerMap().entrySet()) {
        servers.put(entry.getValue(), new NodesHolder());
      }

      DruidNodeDiscovery druidNodeDiscovery = druidNodeDiscoveryProvider.getForNodeRole(NodeRole.BROKER);
      druidNodeDiscovery.registerListener(
          new DruidNodeDiscovery.Listener()
          {
            @Override
            public void nodesAdded(Collection<DiscoveryDruidNode> nodes)
            {
              nodes.forEach(
                  (node) -> {
                    NodesHolder nodesHolder = servers.get(node.getDruidNode().getServiceName());
                    if (nodesHolder != null) {
                      nodesHolder.add(node.getDruidNode().getHostAndPortToUse(), TO_SERVER.apply(node));
                    }
                  }
              );
            }

            @Override
            public void nodesRemoved(Collection<DiscoveryDruidNode> nodes)
            {
              nodes.forEach(
                  (node) -> {
                    NodesHolder nodesHolder = servers.get(node.getDruidNode().getServiceName());
                    if (nodesHolder != null) {
                      nodesHolder.remove(node.getDruidNode().getHostAndPortToUse());
                    }
                  }
              );
            }
          }
      );

      started = true;
    }
  }


  @LifecycleStop
  public void stop()
  {
    synchronized (lock) {
      if (!started) {
        return;
      }

      started = false;
    }
  }

  public String getDefaultServiceName()
  {
    return tierConfig.getDefaultBrokerServiceName();
  }

  public <T> Pair<String, Server> select(final Query<T> query)
  {
    synchronized (lock) {
      if (!ruleManager.isStarted() || !started) {
        return getDefaultLookup();
      }
    }

    String brokerServiceName = null;

    for (TieredBrokerSelectorStrategy strategy : strategies) {
      final Optional<String> optionalName = strategy.getBrokerServiceName(tierConfig, query);
      if (optionalName.isPresent()) {
        brokerServiceName = optionalName.get();
        break;
      }
    }

    if (brokerServiceName == null) {
      // For Union Queries tier will be selected on the rules for first dataSource.
      List<Rule> rules = ruleManager.getRulesWithDefault(Iterables.getFirst(query.getDataSource().getTableNames(), null));

      // find the rule that can apply to the entire set of intervals
      DateTime now = DateTimes.nowUtc();
      int lastRulePosition = -1;
      LoadRule baseRule = null;

      for (Interval interval : query.getIntervals()) {
        int currRulePosition = 0;
        for (Rule rule : rules) {
          if (rule instanceof LoadRule && currRulePosition > lastRulePosition && rule.appliesTo(interval, now)) {
            lastRulePosition = currRulePosition;
            baseRule = (LoadRule) rule;
            break;
          }
          currRulePosition++;
        }
      }

      if (baseRule == null) {
        return getDefaultLookup();
      }

      // in the baseRule, find the broker of highest priority
      for (Map.Entry<String, String> entry : tierConfig.getTierToBrokerMap().entrySet()) {
        if (baseRule.getTieredReplicants().containsKey(entry.getKey())) {
          brokerServiceName = entry.getValue();
          break;
        }
      }
    }

    if (brokerServiceName == null) {
      if (query.context().isDebug()) {
        log.info(
            "Using default broker service[%s] for query with datasource [%s] and intervals[%s].",
            tierConfig.getDefaultBrokerServiceName(),
            query.getDataSource(),
            query.getIntervals()
        );
      }
      brokerServiceName = tierConfig.getDefaultBrokerServiceName();
    }

    return getServerPair(brokerServiceName);
  }

  /**
   * Finds a server for the given brokerServiceName and returns a pair containing
   * the brokerServiceName and the found server. Uses the default broker service
   * if no server is found for the given brokerServiceName.
   */
  private Pair<String, Server> getServerPair(String brokerServiceName)
  {
    NodesHolder nodesHolder = servers.get(brokerServiceName);
    if (nodesHolder == null) {
      log.error(
          "No nodesHolder found for brokerServiceName[%s]. Using default selector for[%s]",
          brokerServiceName,
          tierConfig.getDefaultBrokerServiceName()
      );
      nodesHolder = servers.get(tierConfig.getDefaultBrokerServiceName());
    }

    return new Pair<>(brokerServiceName, nodesHolder.pick());
  }

  public Pair<String, Server> selectForSql(SqlQuery sqlQuery)
  {
    synchronized (lock) {
      if (!started) {
        return getDefaultLookup();
      }
    }

    // Resolve brokerServiceName using Tier selector strategies
    String brokerServiceName = null;
    for (TieredBrokerSelectorStrategy strategy : strategies) {
      final Optional<String> optionalName = strategy.getBrokerServiceName(tierConfig, sqlQuery);
      if (optionalName.isPresent()) {
        brokerServiceName = optionalName.get();
        break;
      }
    }

    // Use defaut if not resolved by strategies
    if (brokerServiceName == null) {
      brokerServiceName = tierConfig.getDefaultBrokerServiceName();

      // Log if query debugging is enabled
      if (sqlQuery.queryContext().isDebug()) {
        log.info(
            "No brokerServiceName found for SQL Query [%s], Context [%s]. Using default selector for [%s].",
            sqlQuery.getQuery(),
            sqlQuery.getContext(),
            tierConfig.getDefaultBrokerServiceName()
        );
      }
    }

    return getServerPair(brokerServiceName);
  }

  public Pair<String, Server> getDefaultLookup()
  {
    final String brokerServiceName = tierConfig.getDefaultBrokerServiceName();
    return new Pair<>(brokerServiceName, servers.get(brokerServiceName).pick());
  }

  public Map<String, List<Server>> getAllBrokers()
  {
    return Maps.transformValues(
        servers,
        new Function<>()
        {
          @Override
          public List<Server> apply(NodesHolder input)
          {
            return input.getAll();
          }
        }
    );
  }

  private static class NodesHolder
  {
    private AtomicInteger roundRobinIndex = new AtomicInteger(-1);

    private Map<String, Server> nodesMap = new HashMap<>();
    private ImmutableList<Server> nodes = ImmutableList.of();

    void add(String id, Server node)
    {
      synchronized (this) {
        nodesMap.put(id, node);
        nodes = ImmutableList.copyOf(nodesMap.values());
      }
    }

    void remove(String id)
    {
      synchronized (this) {
        if (nodesMap.remove(id) != null) {
          nodes = ImmutableList.copyOf(nodesMap.values());
        }
      }
    }

    List<Server> getAll()
    {
      return nodes;
    }

    Server pick()
    {
      ImmutableList<Server> currNodes = nodes;

      if (currNodes.size() == 0) {
        return null;
      }

      return currNodes.get(getIndex(currNodes));
    }

    int getIndex(ImmutableList<Server> currNodes)
    {
      while (true) {
        int index = roundRobinIndex.get();
        int nextIndex = index + 1;
        if (nextIndex >= currNodes.size()) {
          nextIndex = 0;
        }
        if (roundRobinIndex.compareAndSet(index, nextIndex)) {
          return nextIndex;
        }
      }
    }
  }
}
