/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.kubernetes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;

import static java.lang.String.format;

/**
 * A deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Mark Fisher
 * @author Donovan Muller
 */
public class KubernetesAppDeployer extends AbstractKubernetesDeployer implements AppDeployer {

	private static final String SERVER_PORT_KEY = "server.port";

	@Autowired
	public KubernetesAppDeployer(KubernetesDeployerProperties properties,
	                             KubernetesClient client) {
		this(properties, client, new DefaultContainerFactory(properties));
	}

	@Autowired
	public KubernetesAppDeployer(KubernetesDeployerProperties properties,
	                             KubernetesClient client, ContainerFactory containerFactory) {
		this.properties = properties;
		this.client = client;
		this.containerFactory = containerFactory;
	}

	@Override
	public String deploy(AppDeploymentRequest request) {

		String appId = createDeploymentId(request);
		logger.debug(String.format("Deploying app: %s", appId));

		try {
			AppStatus status = status(appId);
			if (!status.getState().equals(DeploymentState.unknown)) {
				throw new IllegalStateException(String.format("App '%s' is already deployed", appId));
			}

			int externalPort = configureExternalPort(request);

			String countProperty = request.getDeploymentProperties().get(COUNT_PROPERTY_KEY);
			int count = (countProperty != null) ? Integer.parseInt(countProperty) : 1;

			String indexedProperty = request.getDeploymentProperties().get(INDEXED_PROPERTY_KEY);
			boolean indexed = (indexedProperty != null) ? Boolean.valueOf(indexedProperty).booleanValue() : false;

			if (indexed) {
				for (int index=0 ; index < count ; index++) {
					String indexedId = appId + "-" + index;
					Map<String, String> idMap = createIdMap(appId, request, index);
					logger.debug(String.format("Creating Service: %s on %d with index %d", appId, externalPort, index));
					createService(indexedId, request, idMap, externalPort);
					if (properties.isCreateDeployment()) {
						logger.debug(String.format("Creating Deployment: %s with index %d", appId, index));
						createDeployment(indexedId, request, idMap, externalPort, 1, index);
					}
					else {
						logger.debug(String.format("Creating Replication Controller: %s with index %d", appId, index));
						createReplicationController(indexedId, request, idMap, externalPort, 1, index);
					}
				}
			}
			else {
				Map<String, String> idMap = createIdMap(appId, request, null);
				logger.debug(String.format("Creating Service: %s on {}", appId, externalPort));
				createService(appId, request, idMap, externalPort);
				if (properties.isCreateDeployment()) {
					logger.debug(String.format("Creating Deployment: %s", appId));
					createDeployment(appId, request, idMap, externalPort, count, null);
				}
				else {
					logger.debug(String.format("Creating Replication Controller: %s", appId));
					createReplicationController(appId, request, idMap, externalPort, count, null);
				}
			}

			return appId;
		} catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}

	@Override
	public void undeploy(String appId) {
		logger.debug(String.format("Undeploying app: %s", appId));
		AppStatus status = status(appId);
		if (status.getState().equals(DeploymentState.unknown)) {
			throw new IllegalStateException(String.format("App '%s' is not deployed", appId));
		}
		List<Service> apps =
			client.services().withLabel(SPRING_APP_KEY, appId).list().getItems();
		if (apps != null) {
			for (Service app : apps) {
				String appIdToDelete = app.getMetadata().getName();
				logger.debug(String.format("Deleting Resources for: %s", appIdToDelete));

				Service svc = client.services().withName(appIdToDelete).get();
				try {
					if (svc != null && "LoadBalancer".equals(svc.getSpec().getType())) {
						int tries = 0;
						int maxWait = properties.getMinutesToWaitForLoadBalancer() * 6; // we check 6 times per minute
						while (tries++ < maxWait) {
							if (svc.getStatus() != null && svc.getStatus().getLoadBalancer() != null &&
									svc.getStatus().getLoadBalancer().getIngress() != null &&
									svc.getStatus().getLoadBalancer().getIngress().isEmpty()) {
								if (tries % 6 == 0) {
									logger.warn("Waiting for LoadBalancer to complete before deleting it ...");
								}
								logger.debug(String.format("Waiting for LoadBalancer, try %d", tries));
								try {
									Thread.sleep(10000L);
								} catch (InterruptedException e) {
								}
								svc = client.services().withName(appIdToDelete).get();
							} else {
								break;
							}
						}
						logger.debug(String.format("LoadBalancer Ingress: %s",
								svc.getStatus().getLoadBalancer().getIngress().toString()));
					}
					Boolean svcDeleted = client.services().withName(appIdToDelete).delete();
					logger.debug(String.format("Deleted Service for: %s %b", appIdToDelete, svcDeleted));
					Boolean rcDeleted = client.replicationControllers().withName(appIdToDelete).delete();
					if (rcDeleted) {
						logger.debug(String.format("Deleted Replication Controller for: %s %b", appIdToDelete, rcDeleted));
					}
					Boolean deplDeleted = client.extensions().deployments().withName(appIdToDelete).delete();
					if (deplDeleted) {
						logger.debug(String.format("Deleted Deployment for: %s %b", appIdToDelete, deplDeleted));
					}
					Map<String, String> selector = new HashMap<>();
					selector.put(SPRING_APP_KEY, appIdToDelete);
					FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> podsToDelete =
							client.pods().withLabels(selector);
					if (podsToDelete != null && podsToDelete.list().getItems() != null) {
						Boolean podDeleted = podsToDelete.delete();
						logger.debug(String.format("Deleted Pods for: %s %b", appIdToDelete, podDeleted));
					} else {
						logger.debug(String.format("No Pods to delete for: %s", appIdToDelete));
					}
				} catch (RuntimeException e) {
					logger.error(e.getMessage(), e);
					throw e;
				}
			}
		}
	}

	@Override
	public AppStatus status(String appId) {
		Map<String, String> selector = new HashMap<>();
		ServiceList services = client.services().withLabel(SPRING_APP_KEY, appId).list();
		selector.put(SPRING_APP_KEY, appId);
		PodList podList = client.pods().withLabels(selector).list();
		if (logger.isDebugEnabled()) {
			logger.debug(String.format("Building AppStatus for app: %s", appId));
			if (podList != null && podList.getItems() != null) {
				logger.debug(String.format("Pods for appId %s: %d", appId, podList.getItems().size()));
				for (Pod pod : podList.getItems()) {
					logger.debug(String.format("Pod: %s", pod.getMetadata().getName()));
				}
			}
		}
		AppStatus status = buildAppStatus(appId, podList, services);
		logger.debug(String.format("Status for app: %s is %s", appId, status));

		return status;
	}

	@Override
	public RuntimeEnvironmentInfo environmentInfo() {
		return super.createRuntimeEnvironmentInfo(AppDeployer.class, this.getClass());
	}

	protected int configureExternalPort(final AppDeploymentRequest request) {
		int externalPort = 8080;
		Map<String, String> parameters = request.getDefinition().getProperties();
		if (parameters.containsKey(SERVER_PORT_KEY)) {
			externalPort = Integer.valueOf(parameters.get(SERVER_PORT_KEY));
		}

		return externalPort;
	}

	protected String createDeploymentId(AppDeploymentRequest request) {
		String groupId = request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY);
		String deploymentId;
		if (groupId == null) {
			deploymentId = String.format("%s", request.getDefinition().getName());
		}
		else {
			deploymentId = String.format("%s-%s", groupId, request.getDefinition().getName());
		}
		// Kubernetes does not allow . in the name and does not allow uppercase in the name
		return deploymentId.replace('.', '-').toLowerCase();
	}

	private Deployment createDeployment (
			String appId, AppDeploymentRequest request,
			Map<String, String> idMap, int externalPort, int replicas, Integer instanceIndex) {
		Deployment d = new DeploymentBuilder()
				.withNewMetadata()
					.withName(appId)
					.withLabels(idMap)
						.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
				.endMetadata()
				.withNewSpec()
					.withReplicas(replicas)
					.withNewTemplate()
						.withNewMetadata()
							.withLabels(idMap)
								.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
						.endMetadata()
						.withSpec(createPodSpec(appId, request, Integer.valueOf(externalPort), instanceIndex, false))
					.endTemplate()
				.endSpec()
				.build();

		return client.extensions().deployments().create(d);
	}

	private ReplicationController createReplicationController (
			String appId, AppDeploymentRequest request,
			Map<String, String> idMap, int externalPort, int replicas, Integer instanceIndex) {
		ReplicationController rc = new ReplicationControllerBuilder()
				.withNewMetadata()
					.withName(appId)
					.withLabels(idMap)
						.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
				.endMetadata()
				.withNewSpec()
					.withReplicas(replicas)
					.withSelector(idMap)
					.withNewTemplate()
						.withNewMetadata()
							.withLabels(idMap)
								.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
						.endMetadata()
						.withSpec(createPodSpec(appId, request, Integer.valueOf(externalPort), instanceIndex, false))
					.endTemplate()
				.endSpec()
				.build();

		return client.replicationControllers().create(rc);
	}

	protected void createService(String appId, AppDeploymentRequest request, Map<String, String> idMap, int externalPort) {
		ServiceSpecBuilder spec = new ServiceSpecBuilder();
		boolean isCreateLoadBalancer = false;
		String createLoadBalancer = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.createLoadBalancer");
		String createNodePort = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.createNodePort");

		if (createLoadBalancer != null && createNodePort != null) {
			throw new IllegalArgumentException("Cannot create NodePort and LoadBalancer at the same time.");
		}

		if (createLoadBalancer == null) {
			isCreateLoadBalancer = properties.isCreateLoadBalancer();
		}
		else {
			if ("true".equals(createLoadBalancer.toLowerCase())) {
				isCreateLoadBalancer = true;
			}
		}

		if (isCreateLoadBalancer) {
			spec.withType("LoadBalancer");
		}

		ServicePort servicePort = new ServicePort();
		servicePort.setPort(externalPort);

		if (createNodePort != null) {
			spec.withType("NodePort");
			if (!"true".equals(createNodePort.toLowerCase())) {
				try {
					Integer nodePort = Integer.valueOf(createNodePort);
					servicePort.setNodePort(nodePort);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException(String.format("Invalid value: %s: provided port is not valid.", createNodePort));
				}
			}
		}

		spec.withSelector(idMap)
			.addNewPortLike(servicePort).endPort();

		Map<String, String> annotations = getServiceAnnotations(request.getDeploymentProperties());

		client.services().inNamespace(client.getNamespace()).createNew()
				.withNewMetadata()
					.withName(appId)
					.withLabels(idMap)
					.withAnnotations(annotations)
					.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
					.endMetadata()
				.withSpec(spec.build())
				.done();
	}

	/**
	 * Get the service annotations for the deployment request.
	 *
	 * @param properties The deployment request deployment properties.
	 * @return map of annottaions
	 */
	protected Map<String, String> getServiceAnnotations(Map<String, String> properties) {
		Map<String, String> annotations = new HashMap<>();

		String annotationsProperty = properties
				.getOrDefault("spring.cloud.deployer.kubernetes.serviceAnnotations", "");

		if (StringUtils.isEmpty(annotationsProperty)) {
			annotationsProperty = this.properties.getServiceAnnotations();
		}

		if (StringUtils.hasText(annotationsProperty)) {
			String[] annotationPairs = annotationsProperty.split(",");
			for (String annotationPair : annotationPairs) {
				String[] annotation = annotationPair.split(":");
				Assert.isTrue(annotation.length == 2, format("Invalid annotation value: '{}'", annotationPair));
				annotations.put(annotation[0].trim(), annotation[1].trim());
			}
		}

		return annotations;
	}


}
