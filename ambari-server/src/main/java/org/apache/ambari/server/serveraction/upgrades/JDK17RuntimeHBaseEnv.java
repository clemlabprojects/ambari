package org.apache.ambari.server.serveraction.upgrades;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.internal.Stack;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.UpgradeContext;
import org.apache.ambari.server.topology.Configuration;

import com.google.inject.Inject;
import com.google.inject.Injector;

//
public class JDK17RuntimeHBaseEnv extends AbstractUpgradeServerAction{

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JDK17RuntimeHBaseEnv.class);

    private static final String CONTENT_PROPERTY_NAME = "content";
    private static final String TARGET_CONFIG_TYPE = "hbase-env";
    private static final String SERVICE_NAME = "HBASE";
    @Inject
    private Injector injector;
    private UpgradeContext upgradeContext;
    @Override
    public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
            throws AmbariException, InterruptedException {


        String clusterName = getExecutionCommand().getClusterName();
        Cluster cluster = getClusters().getCluster(clusterName);
//        Set<String> installedServices = cluster.getServices().keySet();
        Config config = cluster.getDesiredConfigByType(TARGET_CONFIG_TYPE);
        if (config == null) {
            String skipMsg = String.format("Config type '%s' not present on this cluster; skipping upgrade step.", TARGET_CONFIG_TYPE);
            LOG.warn(skipMsg);
            return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", skipMsg, "");
        }
        AmbariMetaInfo ambariMetaInfo = injector.getInstance(AmbariMetaInfo.class);
        AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
        upgradeContext = getUpgradeContext(cluster);
        StackId stackId = getTargetStackId(cluster);
        Stack targetStack = new Stack(stackId.getStackName(),stackId.getStackVersion(),ambariManagementController);
        if (!stackId.getStackVersion().equals("1.3")){
            return  createCommandReport(0, HostRoleStatus.FAILED,"{}",
                    String.format("Target Stack is not 1.3, ignoring"), "");
        }
        if (targetStack == null) {
            return  createCommandReport(0, HostRoleStatus.FAILED,"{}",
                    String.format("Target Stack %s not found", stackId.getStackName()+"-"+stackId.getStackVersion()), "");
        }

        Configuration targetConfigurations = targetStack.getConfiguration(Arrays.asList(SERVICE_NAME));
        Map<String, String> properties = config.getProperties();
        // if content != targetContent ?
        // String content = properties.get(CONTENT_PROPERTY_NAME);
//        Service service = cluster.getService("HDFS");
        Map<String, String> envDefault = targetConfigurations.getProperties().get(TARGET_CONFIG_TYPE);

        properties.put(CONTENT_PROPERTY_NAME, envDefault.get(CONTENT_PROPERTY_NAME));


        config.setProperties(properties);
        config.save();
        agentConfigsHolder.updateData(cluster.getClusterId(), cluster.getHosts().stream().map(Host::getHostId).collect(Collectors.toList()));

        return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
                String.format("Overrided %s in service %s successfully", TARGET_CONFIG_TYPE, SERVICE_NAME), "");

    }
    /**
     * Retrieves the target stack ID for the stack upgrade or downgrade operation.
     *
     * @param cluster the cluster
     * @return the target {@link StackId}
     * @throws AmbariException if multiple stack id's are detected
     */
    private StackId getTargetStackId(Cluster cluster) throws AmbariException {

        // !!! FIXME in a per-service view, what does this become?
        Set<StackId> stackIds = new HashSet<>();

        for (Service service : cluster.getServices().values()) {
            RepositoryVersionEntity targetRepoVersion = upgradeContext.getTargetRepositoryVersion(service.getName());
            StackId targetStackId = targetRepoVersion.getStackId();
            stackIds.add(targetStackId);
        }

        if (1 != stackIds.size()) {
            throw new AmbariException("Services are deployed from multiple stacks and cannot determine a unique one.");
        }

        return stackIds.iterator().next();
    }
}
