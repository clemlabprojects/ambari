package org.apache.ambari.server.serveraction.upgrades;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import com.google.inject.Inject;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.StackConfigurationRequest;
import org.apache.ambari.server.controller.StackServiceRequest;
import org.apache.ambari.server.controller.StackServiceResponse;
import org.apache.ambari.server.serveraction.AbstractServerAction;
import org.apache.ambari.server.state.*;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.UpgradeContext;
import org.apache.ambari.server.state.UpgradeContext;


import java.util.concurrent.ConcurrentMap;

public class CreateKnoxGatewayLog4j2 extends AbstractUpgradeServerAction {
    public static final String VERSION_TAG = "version1";

    public static final String KNOX_SERVICE_NAME = "knox";
    public static final String KNOX_GATEWAY_LOG4j2_CONFIG = "gateway-log4j2";

    @Inject
    private Clusters clusters;

    @Inject
    private ConfigFactory configFactory;

    @Inject
    private ConfigHelper configHelper;

    @Inject
    private ServiceComponentSupport serviceComponentSupport;

    @Inject
    private Clusters m_clusters;

    @Inject
    private AmbariManagementController m_controller;

    @Override
    public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext) throws AmbariException, InterruptedException {
        String clusterName = getExecutionCommand().getClusterName();
        Cluster cluster = clusters.getCluster(clusterName);

        Config knoxGatewayLog4j2 = cluster.getDesiredConfigByType(KNOX_GATEWAY_LOG4j2_CONFIG);
        if (knoxGatewayLog4j2 != null){
            return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
                    String.format("changes are not required, config already exists"), "");
        }
        UpgradeContext upgradeContext = getUpgradeContext(cluster);
        StackId targetStackId = upgradeContext.getTargetStack();

        if (upgradeContext.isServiceSupported(KNOX_SERVICE_NAME)){
            // reading default gateway-log4j and injecting it
            StackServiceRequest stackServiceRequest = new StackServiceRequest(targetStackId.getStackName(), targetStackId.getStackVersion(), KNOX_SERVICE_NAME);



            StackServiceResponse knoxStackService = m_controller.getStackServices(Collections.singleton(stackServiceRequest))
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Could not find stack service " + KNOX_SERVICE_NAME));





        }
        return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
                String.format("changes are not required, config already exists"), "");

    }
}
