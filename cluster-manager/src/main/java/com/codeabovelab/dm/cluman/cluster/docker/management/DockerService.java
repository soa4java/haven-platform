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

package com.codeabovelab.dm.cluman.cluster.docker.management;

import com.codeabovelab.dm.cluman.cluster.docker.ClusterConfig;
import com.codeabovelab.dm.cluman.cluster.docker.management.argument.*;
import com.codeabovelab.dm.cluman.cluster.docker.management.result.ProcessEvent;
import com.codeabovelab.dm.cluman.cluster.docker.management.result.RemoveImageResult;
import com.codeabovelab.dm.cluman.cluster.docker.management.result.ServiceCallResult;
import com.codeabovelab.dm.cluman.cluster.docker.model.*;
import com.codeabovelab.dm.cluman.model.DockerContainer;
import com.codeabovelab.dm.cluman.model.DockerServiceInfo;
import com.codeabovelab.dm.cluman.model.ImageDescriptor;
import com.codeabovelab.dm.common.cache.DefineCache;
import org.springframework.cache.annotation.Cacheable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Docker client API (ReadOnly)
 */
public interface DockerService {

    String CACHE_CONTAINER_DETAILS = "ContainerDetails";
    String DS_PREFIX = "ds:";

    /**
     * Name of cluster <p/>
     * Note that only one of {@link #getCluster()} or {@link #getNode()} can has non null value.
     * @return
     */
    String getCluster();

    /**
     * Name of node. <p/>
     * Note that only one of {@link #getCluster()} or {@link #getNode()} can has non null value.
     * @return
     */
    String getNode();

    /**
     * Return id of service.
     * @see com.codeabovelab.dm.cluman.ds.swarm.DockerServices#getById(String)
     * @return string id of service
     */
    default String getId() {
        StringBuilder sb = new StringBuilder(DS_PREFIX);
        String cluster = getCluster();
        if (cluster != null) {
            sb.append("cluster:").append(cluster);
        } else {
            sb.append("node:").append(getNode());
        }
        return sb.toString();
    }

    boolean isOnline();

    /**
     * Retrieve list of Docker containers.
     * @return
     */
    List<DockerContainer> getContainers(GetContainersArg arg);

    /**
     * Retrieve details info about one container.
     * @param id
     * @return container or null if not found
     */
    @Cacheable(CACHE_CONTAINER_DETAILS)
    @DefineCache(expireAfterWrite = 30_000L, invalidator = DockerCacheInvalidator.class)
    ContainerDetails getContainer(String id);

    /**
     * Get container stats based on resource usage
     * @param arg
     * @return
     */
    ServiceCallResult getStatistics(GetStatisticsArg arg);

    /**
     * Display system-wide information
     * @return info
     */
    DockerServiceInfo getInfo();

    /**
     * Start specified by id container
     * @param id
     * @return result
     */
    ServiceCallResult startContainer(String id);

    /**
     * Stop specified by id container
     * @param arg
     * @return result
     */
    ServiceCallResult stopContainer(StopContainerArg arg);

    /**
     * Get container logs
     * @param arg
     * @return
     */
    ServiceCallResult getContainerLog(GetLogContainerArg arg);

    ServiceCallResult subscribeToEvents(GetEventsArg arg);

    ServiceCallResult restartContainer(StopContainerArg arg);
    ServiceCallResult killContainer(KillContainerArg arg);
    ServiceCallResult deleteContainer(DeleteContainerArg arg);
    CreateContainerResponse createContainer(CreateContainerCmd cmd);
    ServiceCallResult createTag(TagImageArg cmd);


    ServiceCallResult updateContainer(UpdateContainerCmd cmd);

    ServiceCallResult renameContainer(String id, String newName);

    ServiceCallResult createNetwork(CreateNetworkCmd cmd);

    List<Network> getNetworks();

    List<ImageItem> getImages(GetImagesArg arg);

    /**
     * Pull image and return low-level information on the image name
     * @param name name with tag (otherwise retrieved the last image)
     * @param watcher consume events in method execution, allow null
     * @return image
     */
    @Cacheable(value = "Image", key = "name")
    @DefineCache(expireAfterWrite = Integer.MAX_VALUE)
    ImageDescriptor pullImage(String name, Consumer<ProcessEvent> watcher);

    /**
     * return low-level information on the image name, not pull image.
     * @param name name with tag (otherwise retrieved the last image)
     * @return image of null when it not found
     */
    ImageDescriptor getImage(String name);

    /**
     * May return null when is not supported.
     * @return
     */
    ClusterConfig getClusterConfig();

    RemoveImageResult removeImage(RemoveImageArg arg);
}
