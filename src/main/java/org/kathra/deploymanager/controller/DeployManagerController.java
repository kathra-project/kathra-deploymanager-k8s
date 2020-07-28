/*
 * Copyright (c) 2020. The Kathra Authors.
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
 *
 * Contributors:
 *    IRT SystemX (https://www.kathra.org/)
 *
 */
package org.kathra.deploymanager.controller;

import com.rabbitmq.client.Address;
import org.kathra.deploymanager.Config;
import org.kathra.deploymanager.service.DeployManagerService;
import org.kathra.utils.KathraException;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.cdi.ContextName;
import org.apache.camel.component.rabbitmq.RabbitMQComponent;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import javax.activation.FileDataSource;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;
import java.io.File;
import java.util.*;

@ApplicationScoped
@Named("DeployManagerController")
@ContextName("DeployManager")
public class DeployManagerController extends RouteBuilder implements DeployManagerService {

    public static final Logger logger = LoggerFactory.getLogger("DeployManagerController");
    ProducerTemplate pt;
    KubernetesClient client = new DefaultKubernetesClient();
    static private Config config = new Config();
    Map resources = null;

    Yaml yaml = new Yaml();

    /**
     * deploy
     *
     * @param file       YML TO DEPLOY (required)
     * @param namespace  namespace (required)
     * @param cluster    cluster (required)
     * @param commitId   commitId (optional)
     * @param branchName branchName (optional)
     * @param jobName    jobName (optional)
     * @return String
     */
    public String deploy(FileDataSource file, String namespace, String commitId, String cluster, String branchName, String jobName) throws Exception {
        LinkedHashMap resource = yaml.load(FileUtils.openInputStream(file.getFile()));
        if (config.getMode().equals("master") || !config.getClusterName().equals(cluster)) {
            Map<String, Object> headers = new HashMap();
            headers.put("namespace", namespace);
            logger.info("Sending resource to dispatcher, size =" + resource.size());
            pt.sendBodyAndHeaders("rabbitmq://" + cluster, resource, headers);
            return "Forwarding and Deploying in cluster " + cluster;
        } else if (config.getClusterName().equals(cluster)) {
            deployResource(namespace, resource);
            return "Deploying in cluster " + config.getClusterName();
        }
        throw new KathraException("Error").errorCode(KathraException.ErrorCode.INTERNAL_SERVER_ERROR);
    }

    public void deployResource(String namespace, Map resource) throws Exception {
        logger.info("Deploying resources in namespace: " + namespace);
        Namespace ns = client.namespaces().withName(namespace).get();

        if (ns == null) {
            client.namespaces()
                    .createNew()
                    .withNewMetadata()
                    .withName(namespace)
                    .addToLabels("name", namespace)
                    .endMetadata()
                    .done();

            applyLimitsAndQuotasToNamespace(namespace);
            addImagePullSecretToNamespace(namespace);
        }

        List<Map<String, Object>> objects = (List<Map<String, Object>>) resource.get("objects");
        HasMetadata k8sResource = null;

        for (Map<String, Object> obj : objects) {
            String kind = (String) obj.get("kind");
            switch (kind) {
                case "Service":
                    k8sResource = client.services().load(IOUtils.toInputStream(yaml.dump(obj), "UTF-8")).get();
                    ObjectMeta metadata = k8sResource.getMetadata();
                    if (metadata == null) {
                        metadata = new ObjectMeta();
                        k8sResource.setMetadata(metadata);
                    }
                    Map<String, String> labels = metadata.getLabels();
                    if (labels != null && !labels.isEmpty()) {
                        String expose = labels.get("expose");
                        if (expose != null && expose.equals("true")) {
                            deployServiceIngress(namespace, metadata.getName());
                            deployServiceIngressV2(namespace, metadata.getName());
                        }


                    }

                    Map<String, String> annotations = k8sResource.getMetadata().getAnnotations();
                    if (annotations == null) {
                        annotations = new LinkedHashMap();
                        k8sResource.getMetadata().setAnnotations(annotations);
                    }
                    k8sResource.getMetadata().getAnnotations().put("kathra/exposeUrl", "http://" + metadata.getName() + "." + namespace + "." + config.getTopLevelDomain());
                    break;

                case "Deployment":
                    k8sResource = client.apps().deployments().load(IOUtils.toInputStream(yaml.dump(obj), "UTF-8")).get();
                    break;
                case "Ingress":
                    k8sResource = client.extensions().ingresses().load(IOUtils.toInputStream(yaml.dump(obj), "UTF-8")).get();
                    break;
                case "Secret":
                    k8sResource = client.secrets().load(IOUtils.toInputStream(yaml.dump(obj), "UTF-8")).get();
                    break;
                case "ConfigMap":
                    k8sResource = client.configMaps().load(IOUtils.toInputStream(yaml.dump(obj), "UTF-8")).get();
                    break;
                case "Job":
                    k8sResource = client.batch().jobs().load(IOUtils.toInputStream(yaml.dump(obj), "UTF-8")).get();
                    break;
                case "PersistentVolumeClaim":
                    k8sResource = client.persistentVolumeClaims().load(IOUtils.toInputStream(yaml.dump(obj), "UTF-8")).get();
                    break;
                default:
                    throw new KathraException("BUG With K8sResource Template");
            }

            try {
                client.resource(k8sResource).inNamespace(namespace).createOrReplace();
            } catch (Exception e) {
                logger.error("Error deploying resource " +k8sResource.toString(),e);
                e.printStackTrace();
            } finally {
                continue;
            }
        }
    }

    public void applyLimitsAndQuotasToNamespace(String namespaceName) throws Exception {
        Namespace ns = client.namespaces().withName(namespaceName).get();
        if (ns != null) {
            client.limitRanges()
                    .inNamespace(namespaceName)
                    .createOrReplaceWithNew()
                    .withNewMetadata().withName("limits").endMetadata()
                    .withNewSpec().addNewLimit()
                    .withType("Container")
                    .addToDefault("cpu", new Quantity("200m"))
                    .addToDefault("memory", new Quantity("512Mi"))
                    .addToDefaultRequest("cpu", new Quantity("50m"))
                    .addToDefaultRequest("memory", new Quantity("192Mi"))
                    .endLimit().endSpec()
                    .done();

            // ResourceQuota compute-resource
            HasMetadata computeResources = client.resourceQuotas().load(IOUtils.toInputStream((String) resources.get("computeResources"), "utf-8")).get();
            client.resource(computeResources).inNamespace(namespaceName).createOrReplace();

            // ResourceQuota object-counts
            HasMetadata objectCounts = client.resourceQuotas().load(IOUtils.toInputStream((String) resources.get("objectCounts"), "utf-8")).get();
            client.resource(objectCounts).inNamespace(namespaceName).createOrReplace();
        } else throw new KathraException("No namespace with name " + namespaceName);
    }

    public void addImagePullSecretToNamespace(String namespaceName) {
        // Create secret
        Map<String, String> data = new HashMap();
        data.put(".dockerconfigjson", config.getDockerPullSecret());
        client.secrets()
                .inNamespace(namespaceName)
                .createOrReplaceWithNew()
                .withData(data)
                .withType("kubernetes.io/dockerconfigjson")
                .withNewMetadata()
                .withName("regcred")
                .endMetadata()
                .done();

        // Reference as ImagePullSecret
        client.serviceAccounts().
                inNamespace(namespaceName)
                .withName("default")
                .edit()
                .addNewImagePullSecret("regcred")
                .done();
    }

    private void deployServiceIngress(String platformName, String serviceName) {
        client.extensions().ingresses()
                .inNamespace(platformName).createOrReplaceWithNew().withNewMetadata()
                .withName(serviceName)
                .addToAnnotations("kubernetes.io/ingress.class", "traefik")
                .addToLabels("ingress", "plain").endMetadata()
                .withNewSpec().addNewRule()
                .withHost(serviceName + "." + platformName + "." + config.getTopLevelDomain())
                .withNewHttp().addNewPath().withNewBackend()
                .withServiceName(serviceName)
                .withServicePort(new IntOrString(80)).endBackend().endPath().endHttp().endRule().endSpec().done();
    }

    private void deployServiceIngressV2(String platformName, String serviceName) {
        client.extensions().ingresses()
                .inNamespace(platformName).createOrReplaceWithNew().withNewMetadata()
                .withName(serviceName+"-workaround")
                .addToAnnotations("kubernetes.io/ingress.class", "traefik")
                .addToLabels("ingress", "plain").endMetadata()
                .withNewSpec().addNewRule()
                .withHost(serviceName + "-" + platformName + "-svc." + config.getTopLevelDomain())
                .withNewHttp().addNewPath().withNewBackend()
                .withServiceName(serviceName)
                .withServicePort(new IntOrString(80)).endBackend().endPath().endHttp().endRule().endSpec().done();
    }

    @Override
    public void configure() throws Exception {
        pt = getContext().createProducerTemplate();

        RabbitMQComponent rabbitmq = (RabbitMQComponent) getContext().getComponent("rabbitmq");
        rabbitmq.setAddresses(config.getBrokerUrl());

        if (config.getResourcesPolicyFilepath().startsWith("/")) {
            resources = yaml.load(FileUtils.readFileToString(new File(config.getResourcesPolicyFilepath()), "utf-8"));
        } else {
            resources = yaml.load(IOUtils.toString(getClass().getClassLoader().getResourceAsStream(config.getResourcesPolicyFilepath()), "utf-8"));
        }

        if (resources == null)
            throw new KathraException("Unable to parse resourceQuotas from provided file:" + config.getResourcesPolicyFilepath());

        from("rabbitmq://" + config.getClusterName())
                .routeId("deploy-route")
                .to("bean:DeployManagerController?method=deployResource(${header.namespace},${body})").end();
    }
}

