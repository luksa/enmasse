Recipe for setting up metrics and grafana

1. Setup hawkular with OpenShift. For local deployment, doing `oc cluster up --metrics` should be enough. For advanced install, see https://docs.openshift.org/latest/install_config/cluster_metrics.html
2. Make sure your user is setup with cluster-admin privileges (either oc login -u system:admin or use oc --config <kubeconfig> <command>)
3. Select the openshift-infra project (using default is also OK, but I usually deploy all the metrics stuff in openshift-infra)
4. Deploy hawkular-openshift-agent for collecting metrics from EnMasse. Follow the instructions at https://github.com/openshift/origin-metrics#deploying-the-hawkular-openshift-agent (<b>NOTE</b>: When processing the hawkular-openshift-agent template, set IMAGE_VERSION=1.4.0.Final to ensure that it doesn't crash when handling mbean wildcards)
5. Deploy EnMasse to desired project/namespace
6. Deploy the hawkular-grafana using one of these templates: https://github.com/hawkular/hawkular-grafana-datasource/tree/master/docker/openshift . This will build the whole thing from source, so takes a while
7. When the hawkular-grafana deployment is done, a route has been created (oc get route -n openshift-infra to list). Go to the exposed route, login (admin/admin)
8. Setup a new data source with type hawkular (host found in the openshift-infra project, I had to use the service ip from 'oc get service hawkular-metrics -n openshift-infra' since it was unable to resolve the metrics route host), and configure it with tenant: <enmasse namespace> and password: <valid token for enmasse project owner> (oc whoami -t)
8. You can now create dashboards and select metrics coming from brokers. An example dashboard can be found here: https://github.com/EnMasseProject/enmasse/blob/master/documentation/grafana/dashboard.json
