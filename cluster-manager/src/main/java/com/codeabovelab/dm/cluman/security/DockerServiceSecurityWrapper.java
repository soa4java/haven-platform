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

package com.codeabovelab.dm.cluman.security;

import com.codeabovelab.dm.cluman.cluster.docker.ClusterConfig;
import com.codeabovelab.dm.cluman.cluster.docker.management.DockerService;
import com.codeabovelab.dm.cluman.cluster.docker.management.argument.*;
import com.codeabovelab.dm.cluman.cluster.docker.management.result.ProcessEvent;
import com.codeabovelab.dm.cluman.cluster.docker.management.result.RemoveImageResult;
import com.codeabovelab.dm.cluman.cluster.docker.management.result.ServiceCallResult;
import com.codeabovelab.dm.cluman.cluster.docker.model.*;
import com.codeabovelab.dm.cluman.model.DockerContainer;
import com.codeabovelab.dm.cluman.model.DockerServiceInfo;
import com.codeabovelab.dm.cluman.model.ImageDescriptor;
import com.codeabovelab.dm.common.security.Action;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.Assert;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 */
public class DockerServiceSecurityWrapper implements DockerService {

    private final AccessContextFactory aclContextFactory;
    private final DockerService service;

    public DockerServiceSecurityWrapper(AccessContextFactory aclContextFactory, DockerService service) {
        this.aclContextFactory = aclContextFactory;
        this.service = service;
    }

    public void checkServiceAccess(Action action) {
        AccessContext context = aclContextFactory.getContext();
        checkServiceAccessInternal(context, action);
    }

    private void checkServiceAccessInternal(AccessContext context, Action action) {
        Assert.notNull(action, "Action is null");
        String cluster = getCluster();
        if(cluster != null) {
            boolean granted = context.isGranted(SecuredType.CLUSTER.id(cluster), action);
            if(!granted) {
                throw new AccessDeniedException("Access to cluster docker service '" + cluster + "' with " + action + " is denied.");
            }
        }
        String node = getNode();
        if(node != null) {
            boolean granted = context.isGranted(SecuredType.NODE.id(node), action);
            if(!granted) {
                throw new AccessDeniedException("Access to node docker service '" + node + "' with " + action + " is denied.");
            }
        }
    }

    public void checkContainerAccess(String id, Action action) {
        Assert.notNull(action, "Action is null");
        AccessContext context = aclContextFactory.getContext();
        checkServiceAccessInternal(context, action == Action.READ? Action.READ : Action.ALTER_INSIDE);
        boolean granted = context.isGranted(SecuredType.CONTAINER.id(id), action);
        if(!granted) {
            throw new AccessDeniedException("Access to container '" + id + "' with " + action + " is denied.");
        }
    }

    public void checkImageAccess(AccessContext context, String id, Action action) {
        Assert.notNull(action, "Action is null");
        checkServiceAccessInternal(context, action == Action.READ? Action.READ : Action.ALTER_INSIDE);
        boolean granted = context.isGranted(SecuredType.LOCAL_IMAGE.id(id), action);
        if(!granted) {
            throw new AccessDeniedException("Access to image '" + id + "' with " + action + " is denied.");
        }
    }

    public void checkNetworkAccess(String name, Action action) {
        Assert.notNull(action, "Action is null");
        AccessContext context = aclContextFactory.getContext();
        checkServiceAccessInternal(context, action == Action.READ? Action.READ : Action.ALTER_INSIDE);
        boolean granted = context.isGranted(SecuredType.NETWORK.id(name), action);
        if(!granted) {
            throw new AccessDeniedException("Access to image '" + name + "' with " + action + " is denied.");
        }
    }

    @Override
    public String getCluster() {
        return service.getCluster();
    }

    @Override
    public String getNode() {
        return service.getNode();
    }

    @Override
    public boolean isOnline() {
        return service.isOnline();
    }

    @Override
    public List<DockerContainer> getContainers(GetContainersArg arg) {
        AccessContext context = aclContextFactory.getContext();
        checkServiceAccessInternal(context, Action.READ);
        return service.getContainers(arg).stream().filter((img) -> {
            return context.isGranted(SecuredType.CONTAINER.id(img.getId()), Action.READ);
        }).collect(Collectors.toList());
    }

    @Override
    public ContainerDetails getContainer(String id) {
        checkContainerAccess(id, Action.READ);
        return service.getContainer(id);
    }

    @Override
    public ServiceCallResult getStatistics(GetStatisticsArg arg) {
        checkContainerAccess(arg.getId(), Action.READ);
        return service.getStatistics(arg);
    }

    @Override
    public DockerServiceInfo getInfo() {
        checkServiceAccess(Action.READ);
        return service.getInfo();
    }

    @Override
    public ServiceCallResult startContainer(String id) {
        checkContainerAccess(id, Action.EXECUTE);
        return service.startContainer(id);
    }


    @Override
    public ServiceCallResult stopContainer(StopContainerArg arg) {
        checkContainerAccess(arg.getId(), Action.EXECUTE);
        return service.stopContainer(arg);
    }

    @Override
    public ServiceCallResult getContainerLog(GetLogContainerArg arg) {
        checkContainerAccess(arg.getId(), Action.READ);
        return service.getContainerLog(arg);
    }

    @Override
    public ServiceCallResult subscribeToEvents(GetEventsArg arg) {
        checkServiceAccess(Action.READ);
        return service.subscribeToEvents(arg);
    }

    @Override
    public ServiceCallResult restartContainer(StopContainerArg arg) {
        checkContainerAccess(arg.getId(), Action.EXECUTE);
        return service.restartContainer(arg);
    }

    @Override
    public ServiceCallResult killContainer(KillContainerArg arg) {
        checkContainerAccess(arg.getId(), Action.DELETE);
        return service.killContainer(arg);
    }

    @Override
    public ServiceCallResult deleteContainer(DeleteContainerArg arg) {
        checkContainerAccess(arg.getId(), Action.DELETE);
        return service.deleteContainer(arg);
    }

    @Override
    public CreateContainerResponse createContainer(CreateContainerCmd cmd) {
        checkContainerAccess(null, Action.CREATE);
        return service.createContainer(cmd);
    }

    @Override
    public ServiceCallResult createTag(TagImageArg cmd) {
        checkImageAccess(aclContextFactory.getContext(), cmd.getImageName(), Action.UPDATE);
        return service.createTag(cmd);
    }

    @Override
    public ServiceCallResult updateContainer(UpdateContainerCmd cmd) {
        checkContainerAccess(cmd.getId(), Action.UPDATE);
        return service.updateContainer(cmd);
    }

    @Override
    public ServiceCallResult renameContainer(String id, String newName) {
        checkContainerAccess(id, Action.UPDATE);
        return service.renameContainer(id, newName);
    }

    @Override
    public ServiceCallResult createNetwork(CreateNetworkCmd cmd) {
        checkNetworkAccess(cmd.getName(), Action.CREATE);
        return service.createNetwork(cmd);
    }

    @Override
    public List<Network> getNetworks() {
        AccessContext context = aclContextFactory.getContext();
        checkServiceAccessInternal(context, Action.READ);
        return service.getNetworks().stream().filter((net) -> {
            return context.isGranted(SecuredType.NETWORK.id(net.getId()), Action.READ);
        }).collect(Collectors.toList());
    }

    @Override
    public List<ImageItem> getImages(GetImagesArg arg) {
        AccessContext context = aclContextFactory.getContext();
        checkServiceAccessInternal(context, Action.READ);
        return  service.getImages(arg).stream().filter((img) -> {
            return context.isGranted(SecuredType.LOCAL_IMAGE.id(img.getId()), Action.READ);
        }).collect(Collectors.toList());
    }

    @Override
    public ImageDescriptor pullImage(String name, Consumer<ProcessEvent> watcher) {
        // here service can load image, but we cannot check access by name, and need check it by id after loading
        AccessContext context = aclContextFactory.getContext();
        checkServiceAccessInternal(context, Action.READ);
        ImageDescriptor image = service.pullImage(name, watcher);
        checkImageAccess(context, name, Action.READ);
        return image;
    }

    @Override
    public ImageDescriptor getImage(String name) {
        // here service can load image, but we cannot check access by name, and need check it by id after loading
        AccessContext context = aclContextFactory.getContext();
        checkServiceAccessInternal(context, Action.READ);
        ImageDescriptor image = service.getImage(name);
        checkImageAccess(context, name, Action.READ);
        return image;
    }

    @Override
    public ClusterConfig getClusterConfig() {
        checkServiceAccess(Action.READ);
        return service.getClusterConfig();
    }

    @Override
    public RemoveImageResult removeImage(RemoveImageArg arg) {
        checkImageAccess(aclContextFactory.getContext(), arg.getImageId(), Action.DELETE);
        return service.removeImage(arg);
    }
}
