package enmasse.controller.address;

import enmasse.config.LabelKeys;
import enmasse.controller.address.api.DestinationApi;
import enmasse.controller.common.ConfigWatcher;
import enmasse.controller.common.DestinationClusterGenerator;
import enmasse.controller.common.Kubernetes;
import enmasse.controller.model.Destination;
import io.fabric8.openshift.client.OpenShiftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for a single address space
 */
public class AddressSpaceController extends ConfigWatcher<Destination> {
    private static final Logger log = LoggerFactory.getLogger(AddressSpaceController.class);
    private final DestinationApi destinationApi;
    private final Kubernetes kubernetes;
    private final DestinationClusterGenerator clusterGenerator;

    public AddressSpaceController(DestinationApi destinationApi, Kubernetes kubernetes, OpenShiftClient client, DestinationClusterGenerator clusterGenerator) {
        super(Collections.singletonMap(LabelKeys.TYPE, "address-config"), kubernetes.getInstanceId().getNamespace(), client);
        this.destinationApi = destinationApi;
        this.kubernetes = kubernetes;
        this.clusterGenerator = clusterGenerator;
    }

    @Override
    protected synchronized void checkConfigs(Set<Destination> newDestinations) {
        log.debug("Check destinations in address space controller: " + newDestinations);

        Map<String, Set<Destination>> destinationByGroup = newDestinations.stream().collect(Collectors.groupingBy(Destination::group, Collectors.toSet()));
        validateDestinationGroups(destinationByGroup);

        List<DestinationCluster> clusterList = kubernetes.listClusters();
        log.debug("Current set of clusters: " + clusterList);
        createBrokers(clusterList, destinationByGroup);
        deleteBrokers(clusterList, destinationByGroup);

        for (Destination destination : newDestinations) {
            checkStatus(destination);
        }
    }

    @Override
    public Set<Destination> listConfigs() {
        return destinationApi.listDestinations();
    }

    /*
     * Ensure that a destination groups meet the criteria of all destinations sharing the same properties, until we can
     * support a mix.
     */
    private static void validateDestinationGroups(Map<String, Set<Destination>> destinationByGroup) {
        for (Map.Entry<String, Set<Destination>> entry : destinationByGroup.entrySet()) {
            Iterator<Destination> it = entry.getValue().iterator();
            Destination first = it.next();
            while (it.hasNext()) {
                Destination current = it.next();
                if (current.storeAndForward() != first.storeAndForward() ||
                    current.multicast() != first.multicast() ||
                    !current.flavor().equals(first.flavor()) ||
                    !current.group().equals(first.group())) {

                    throw new IllegalArgumentException("All destinations in a destination group must share the same properties. Found: " + current + " and " + first);
                }
            }
        }
    }

    private void createBrokers(List<DestinationCluster> clusterList, Map<String, Set<Destination>> newDestinationGroups) {
        newDestinationGroups.entrySet().stream()
                .filter(group -> !brokerExists(clusterList, group.getKey()))
                .map(group -> clusterGenerator.generateCluster(group.getValue()))
                .forEach(cluster -> {
                    if (!cluster.getResources().getItems().isEmpty()) {
                        log.info("Creating cluster {}", cluster.getClusterId());
                        kubernetes.create(cluster.getResources());
                    }
                });
    }

    private boolean brokerExists(List<DestinationCluster> clusterList, String clusterId) {
        for (DestinationCluster existing : clusterList) {
            if (existing.getClusterId().equals(clusterId)) {
                return true;
            }
        }
        return false;
    }

    private void deleteBrokers(Collection<DestinationCluster> clusterList, Map<String, Set<Destination>> newDestinationGroups) {
        clusterList.stream()
                .filter(cluster -> newDestinationGroups.entrySet().stream()
                        .noneMatch(destinationGroup -> cluster.getClusterId().equals(destinationGroup.getKey())))
                .forEach(cluster -> {

                    log.info("Deleting cluster {}", cluster.getClusterId());
                    kubernetes.delete(cluster.getResources());
                });
    }

    private void checkStatus(Destination destination) {
        // TODO: Check non-store-and-forward clusters as well
        boolean isReady = !destination.storeAndForward() || kubernetes.isDestinationClusterReady(destination.group());
        Destination.Builder updated = new Destination.Builder(destination);
        updated.status(new Destination.Status(isReady));
        destinationApi.replaceDestination(updated.build());
    }
}
