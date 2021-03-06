/*
 * Copyright 2016 Code Above Lab LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.codeabovelab.dm.cluman.ds.nodes;

import com.codeabovelab.dm.cluman.cluster.docker.management.DockerServiceEvent;
import com.codeabovelab.dm.cluman.model.*;
import com.codeabovelab.dm.cluman.persistent.PersistentBusFactory;
import com.codeabovelab.dm.cluman.reconfig.ReConfigObject;
import com.codeabovelab.dm.cluman.reconfig.ReConfigurable;
import com.codeabovelab.dm.cluman.security.AccessContext;
import com.codeabovelab.dm.cluman.security.AccessContextFactory;
import com.codeabovelab.dm.cluman.security.SecuredType;
import com.codeabovelab.dm.cluman.security.TempAuth;
import com.codeabovelab.dm.cluman.ui.HttpException;
import com.codeabovelab.dm.cluman.validate.ExtendedAssert;
import com.codeabovelab.dm.common.kv.KeyValueStorage;
import com.codeabovelab.dm.common.kv.KvStorageEvent;
import com.codeabovelab.dm.common.kv.WriteOptions;
import com.codeabovelab.dm.common.kv.mapping.*;
import com.codeabovelab.dm.common.mb.MessageBus;
import com.codeabovelab.dm.common.security.Action;
import com.codeabovelab.dm.common.validate.ValidityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

/**
 * Simple wrapper around InstanceStorage for save node info
 */
@ReConfigurable
@Component
public class NodeStorage implements NodeInfoProvider {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final KvMapperFactory kvmf;
    private final KvMap<NodeRegistrationImpl> nodes;
    private final MessageBus<NodeEvent> nodeEventBus;
    private final PersistentBusFactory persistentBusFactory;
    private final ExecutorService executorService;

    @Autowired
    public NodeStorage(KvMapperFactory kvmf,
                       @Qualifier(NodeEvent.BUS) MessageBus<NodeEvent> nodeEventBus,
                       @Qualifier(DockerServiceEvent.BUS) MessageBus<DockerServiceEvent> dockerBus,
                       PersistentBusFactory persistentBusFactory,
                       ExecutorService executorService) {
        this.kvmf = kvmf;
        this.nodeEventBus = nodeEventBus;
        this.persistentBusFactory = persistentBusFactory;
        KeyValueStorage storage = kvmf.getStorage();
        String nodesPrefix = storage.getPrefix() + "/nodes/";
        this.nodes = KvMap.builder(NodeRegistrationImpl.class, NodeInfoImpl.Builder.class)
          .path(nodesPrefix)
          .adapter(new KvMapAdapterImpl())
          .localListener((e) -> {
              if(e.getAction() == KvMapLocalEvent.Action.CREATE) {
                  AccessContextFactory.getLocalContext().assertGranted(SecuredType.NODE.id(e.getKey()), Action.CREATE);
              }
          })
          .listener(this::onKVEvent)
          .mapper(kvmf)
          .build();
        this.executorService = executorService;

        dockerBus.asSubscriptions().subscribe(this::onDockerServiceEvent);
    }

    @PostConstruct
    public void init() {
        nodes.load();
    }

    private void onKVEvent(KvMapEvent<NodeRegistrationImpl> e) {
        String key = e.getKey();
        KvStorageEvent.Crud action = e.getAction();
        try (TempAuth ta = TempAuth.asSystem()) {
            switch (action) {
                case DELETE: {
                    NodeRegistrationImpl nr = getNodeRegistrationInternal(key);
                    NodeInfoImpl ni = nr == null? NodeInfoImpl.builder().name(key).build() : nr.getNodeInfo();
                    fireNodeModification(nr, StandardActions.DELETE, ni);
                    break;
                }
                default: {
                    NodeRegistrationImpl nr = this.nodes.getIfPresent(key);
                    // update events will send from node registration
                    if (nr != null && action == KvStorageEvent.Crud.CREATE) {
                        fireNodeModification(nr, StandardActions.CREATE, nr.getNodeInfo());
                    }
                }
            }
        }
    }

    private void fireNodeModification(NodeRegistrationImpl nr, String action, NodeInfoImpl ni) {
        // NodeRegistrationImpl - may be null in some cases
        NodeEvent ne = NodeEvent.builder()
          .action(action)
          .node(ni)
          .build();
        //we use async execution only from another event handler
        this.executorService.execute(() -> {
            try (TempAuth auth = TempAuth.asSystem()) {
                this.nodeEventBus.accept(ne);
            }
        });
    }

    private void onDockerServiceEvent(DockerServiceEvent e) {
        if(e instanceof DockerServiceEvent.DockerServiceInfoEvent) {
            // we update health of all presented nodes
            DockerServiceInfo info = ((DockerServiceEvent.DockerServiceInfoEvent) e).getInfo();
            for(NodeInfo node: info.getNodeList()) {
                NodeRegistrationImpl reg = getOrCreateNodeRegistration(node.getName());
                if(reg != null) {
                    reg.updateHealth(node.getHealth());
                }
            }
        }
    }

    @Scheduled(fixedDelay = 60_000L)
    private void checkNodes() {
        // periodically check online status of nodes
        for(NodeRegistrationImpl nr: nodes.values()) {
            nr.getNodeInfo();
        }
    }

    private NodeRegistrationImpl newRegistration(NodeInfo nodeInfo) {
        return new NodeRegistrationImpl(persistentBusFactory, nodeInfo, this::fireNodeModification);
    }

    public boolean hasNode(Predicate<Object> predicate, String nodeId) {
        NodeRegistration instance = getNodeRegistrationInternal(nodeId);
        return instance != null && predicate.test(instance);
    }

    public NodeRegistration getNodeRegistration(String nodeId) {
        NodeRegistrationImpl nr = getNodeRegistrationInternal(nodeId);
        checkAccess(nr, Action.READ);
        return nr;
    }

    private void checkAccess(NodeRegistrationImpl nr, Action read) {
        if(nr == null) {
            return;
        }
        AccessContextFactory.getLocalContext().assertGranted(nr.getOid(), read);
    }

    /**
     * This method newer been covered by security, because used for security checks.
     * @param nodeId name of node
     * @return registration or null
     */
    NodeRegistrationImpl getNodeRegistrationInternal(String nodeId) {
        try {
            return nodes.get(nodeId);
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if(cause instanceof ValidityException) {
                log.error("Not load node '{}' due validation error: {}", nodeId, cause.getMessage());
            } else {
                log.error("On loading: {} ", nodeId, e);
            }
        }
        return null;
    }

    public void removeNode(String name) {
        //we check name for prevent names like '../'
        NodeUtils.checkName(name);
        NodeRegistrationImpl nr = getNodeRegistrationInternal(name);
        checkAccess(nr, Action.DELETE);
        nodes.remove(name);
    }

    private NodeRegistrationImpl getOrCreateNodeRegistration(String name) {
        ExtendedAssert.matchAz09Hyp(name, "node name");
        return nodes.computeIfAbsent(name, (n) -> {
            AccessContextFactory.getLocalContext().assertGranted(SecuredType.NODE.id(name), Action.CREATE);
            NodeRegistrationImpl nr = newRegistration(NodeInfoImpl.builder().name(name));
            return nr;
        });
    }

    /**
     * Register or update node.

     * @param nodeUpdate data with node update
     * @param ttl
     */
    public void updateNode(NodeUpdate nodeUpdate, int ttl) {
        NodeInfo node = nodeUpdate.getNode();

        NodeRegistrationImpl nr = getOrCreateNodeRegistration(node.getName());
        nr.update(ttl);// important that it must be before other update methods
        nr.updateNodeInfo(b -> {
            b.address(node.getAddress());
            b.labels(node.getLabels());
            b.mergeHealth(node.getHealth());
        });
        updateSwarmRegistration(nr);
        save(nr);
    }

    private void save(NodeRegistrationImpl nr) {
        Assert.notNull(nr, "NodeRegistrationImpl is null");
        // we use copy of node info, for data consistency
        nodes.flush(nr.getName());
    }

    public void setNodeCluster(String nodeName, String cluster) {
        NodeRegistrationImpl nr = getNodeRegistrationInternal(nodeName);
        if(nr == null) {
            throw new HttpException(HttpStatus.NOT_FOUND, "Node '" + nodeName + "' is not found");
        }
        checkAccess(nr, Action.UPDATE);
        NodeInfo ni = nr.getNodeInfo();
        String oldCluster = ni.getCluster();
        if(!Objects.equals(oldCluster, cluster)) {
            //here may be race condition
            nr.setCluster(cluster);
            save(nr);
        }
        updateSwarmRegistration(nr);
        if(StringUtils.hasText(oldCluster) && !oldCluster.equals(cluster)) {
            //it optional but reduce time when node appear in two clusters in same time
            try {
                kvmf.getStorage().delete(getDiscoveryKey(oldCluster, nr.getNodeInfo().getAddress()), null);
            } catch (Exception e) {
                log.error("Can not remove node {} swarm-registration from cluster {} due: {}", nodeName, cluster, e.getMessage());
            }
        }
    }

    @Override
    public String getNodeCluster(String node) {
        NodeRegistrationImpl nr = getNodeRegistrationInternal(node);
        if (nr == null) {
            return null;
        }
        return nr.getCluster();
    }

    private void updateSwarmRegistration(NodeRegistrationImpl nr) {
        NodeInfo ni = nr.getNodeInfo();
        String cluster = ni.getCluster();
        if(!StringUtils.hasText(cluster)) {
            return;
        }
        Assert.doesNotContain(cluster, "/", "Bad cluster name: " + cluster);
        checkAccess(nr, Action.UPDATE);
        String address = ni.getAddress();
        try {
            kvmf.getStorage().set(getDiscoveryKey(cluster, address),
              address,
              WriteOptions.builder().ttl(nr.getTtl()).build());
        } catch (Exception e) {
            log.error("Can not update swarm registration: of node {} from cluster {}", address, cluster, e);
        }
    }

    private String getDiscoveryKey(String cluster, String address) {
        return "/discovery/" + cluster + "/docker/swarm/nodes/" + address;
    }

    @Override
    public NodeInfo getNodeInfo(String node) {
        NodeRegistrationImpl instance = getNodeRegistrationInternal(node);
        if(instance == null) {
            return null;
        }
        checkAccess(instance, Action.READ);
        return instance.getNodeInfo();
    }

    /**
     *
     * @param predicate functor which is return true for InstanceInfo's which will be passed to result.
     * @return
     */
    public Collection<NodeInfo> getNodes(Predicate<? super NodeRegistration> predicate) {
        Set<String> keys = listNodeNames();
        AccessContext ac = AccessContextFactory.getLocalContext();
        List<NodeInfo> nodeList = new ArrayList<>(keys.size());
        for (String key : keys) {
            NodeRegistrationImpl nr = getNodeRegistrationInternal(key);
            // when node invalid we may receive null
            if (nr == null || !predicate.test(nr) || !ac.isGranted(nr.getOid(), Action.READ)) {
                continue;
            }
            nodeList.add(nr.getNodeInfo());
        }
        nodeList.sort(null);
        return nodeList;
    }

    private Set<String> listNodeNames() {
        return nodes.list();
    }

    @ReConfigObject
    private NodeStorageConfigObj getConfig() {
        Set<String> nodeNames = listNodeNames();
        NodeStorageConfigObj obj = new NodeStorageConfigObj();
        List<NodeInfoImpl> list = new ArrayList<>();
        obj.setNodes(list);
        for(String nodeName: nodeNames) {
            NodeRegistrationImpl nri = getNodeRegistrationInternal(nodeName);
            if(nri == null) {
                continue;
            }
            list.add(nri.getNodeInfo());
        }
        return obj;
    }

    @ReConfigObject
    private void setConfig(NodeStorageConfigObj o) {
        List<NodeInfoImpl> nodes = o.getNodes();
        for(NodeInfoImpl nc: nodes) {
            NodeRegistrationImpl nr = getOrCreateNodeRegistration(nc.getName());
            if(nr == null) {
                return;
            }
            nr.updateNodeInfo(b -> {
                NodeMetrics om = b.getHealth();
                b.from(nc);
                b.health(om);
            });
            save(nr);
        }
    }

    private class KvMapAdapterImpl implements KvMapAdapter<NodeRegistrationImpl> {
        @Override
        public Object get(String key, NodeRegistrationImpl source) {
            return NodeInfoImpl.builder(source.getNodeInfo());
        }

        @Override
        public NodeRegistrationImpl set(String key, NodeRegistrationImpl source, Object value) {
            NodeInfo ni = (NodeInfo) value;
            if(source == null) {
                NodeInfoImpl.Builder nib = NodeInfoImpl.builder(ni);
                nib.setName(key);
                source = newRegistration(nib);
            } else {
                source.updateNodeInfo(b -> {
                    NodeMetrics om = b.getHealth();
                    b.from(ni);
                    b.health(om);
                });
            }
            return source;
        }

        @Override
        public Class<?> getType(NodeRegistrationImpl source) {
            return NodeInfoImpl.Builder.class;
        }
    }
}
