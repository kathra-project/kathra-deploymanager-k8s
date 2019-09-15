/* 
 * Copyright 2019 The Kathra Authors.
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
 *
 *    IRT SystemX (https://www.kathra.org/)    
 *
 */
package org.kathra.deploymanager;

import org.kathra.utils.ConfigManager;

public class Config extends ConfigManager {

    private String CLUSTER_NAME;
    private String BROKER_URL;
    private String MODE;
    private String DOCKER_PULL_SECRET;
    private String RESOURCES_POLICY_FILEPATH;
    private String TOPLEVEL_DOMAIN;

    public Config() {
        CLUSTER_NAME = getProperty("CLUSTER_NAME");
        BROKER_URL = getProperty("BROKER_URL", "rabbitmq");
        MODE = getProperty("MODE", "master");
        DOCKER_PULL_SECRET = getProperty("DOCKER_PULL_SECRET");
        RESOURCES_POLICY_FILEPATH = getProperty("RESOURCES_POLICY_FILEPATH", "defaultResourcesPolicy.yml");
        TOPLEVEL_DOMAIN = getProperty("TOPLEVEL_DOMAIN", "irtsystemx.org");
    }

    public String getClusterName() {
        return CLUSTER_NAME;
    }

    public String getBrokerUrl() {
        return BROKER_URL;
    }

    public String getMode() {
        return MODE;
    }

    public String getDockerPullSecret() {
        return DOCKER_PULL_SECRET;
    }

    public String getResourcesPolicyFilepath() {
        return RESOURCES_POLICY_FILEPATH;
    }

    public String getTopLevelDomain() {
        return TOPLEVEL_DOMAIN;
    }
}
