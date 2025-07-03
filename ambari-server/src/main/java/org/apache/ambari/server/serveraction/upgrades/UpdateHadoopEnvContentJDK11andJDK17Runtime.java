package org.apache.ambari.server.serveraction.upgrades;

import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.internal.Stack;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.*;
import org.apache.ambari.server.topology.Configuration;

//
public class UpdateHadoopEnvContentJDK11andJDK17Runtime extends AbstractUpgradeServerAction{

    private static final String TARGET_CONFIG_TYPE = "hadoop-env";
    private static final String CONTENT_PROPERTY_NAME = "content";

    @Inject
    private Injector injector;
    private UpgradeContext upgradeContext;
    @Override
    public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
            throws AmbariException, InterruptedException {


        String clusterName = getExecutionCommand().getClusterName();
        Cluster cluster = getClusters().getCluster(clusterName);
        Config config = cluster.getDesiredConfigByType(TARGET_CONFIG_TYPE);
        AmbariMetaInfo ambariMetaInfo = injector.getInstance(AmbariMetaInfo.class);
        AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
        StackId stackId = getTargetStackId(cluster);
        upgradeContext = getUpgradeContext(cluster);
        Stack targetStack = new Stack(stackId.getStackName(),stackId.getStackVersion(),ambariManagementController);
        if (!stackId.getStackVersion().equals("1.3")){
            return  createCommandReport(0, HostRoleStatus.FAILED,"{}",
                    String.format("Target Stack is not 1.3, ignoring"), "");
        }
        if (targetStack == null) {
            return  createCommandReport(0, HostRoleStatus.FAILED,"{}",
                    String.format("Target Stack %s not found", TARGET_CONFIG_TYPE), "");
        }

        Configuration targetHDFSConfiguration = targetStack.getConfiguration(Arrays.asList("HDFS"));
        Map<String, String> properties = config.getProperties();
        // if content != targetContent ?
        // String content = properties.get(CONTENT_PROPERTY_NAME);
        Map<String, String> hadoopEnvDefault = targetHDFSConfiguration.getProperties().get(TARGET_CONFIG_TYPE);
        properties.put(CONTENT_PROPERTY_NAME, hadoopEnvDefault.get(CONTENT_PROPERTY_NAME));


        config.setProperties(properties);
        config.save();
        agentConfigsHolder.updateData(cluster.getClusterId(), cluster.getHosts().stream().map(Host::getHostId).collect(Collectors.toList()));

        return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
                String.format("Override %s in service HDFS to config to content", TARGET_CONFIG_TYPE), "");

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
