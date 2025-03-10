/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.choreo.connect.tests.context;

import org.apache.commons.lang3.StringUtils;
import org.testcontainers.containers.DockerComposeContainer;
import org.wso2.choreo.connect.tests.mockbackend.MockBackendServer;
import org.wso2.choreo.connect.tests.util.TestConstant;
import org.wso2.choreo.connect.tests.util.Utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Choreo Connect instance class.
 */
public class CcInstance extends ChoreoConnectImpl {
    private static volatile CcInstance instance = null;

    /**
     * Initialize a docker compose container environment for Choreo Connect
     *
     * @param confFileName  - a conf.toml filename in integration/test-integration/src/test/resources/configs
     * @param backendServiceFile - a file in integration/test-integration/src/test/resources/dockerCompose
     *                          with docker-compose service section
     * @throws IOException if an error occurs while appending backend service to docker-compose file
     * @throws CCTestException if an error occurs while appending backend service to docker-compose file
     */
    private CcInstance(String dockerComposeFile, String confFileName, String backendServiceFile,
                       boolean withCustomJwtTransformer, boolean withAnalyticsMetricImpl, List<String> startupAPIs)
            throws IOException, CCTestException {
        createTmpMgwSetup();
        String targetDir = Utils.getTargetDirPath();
        if (!StringUtils.isEmpty(confFileName)) {
            Utils.copyFile(targetDir + TestConstant.TEST_RESOURCES_PATH + TestConstant.CONFIGS_DIR
                            + File.separator + confFileName,
                    ccTempPath + TestConstant.DOCKER_COMPOSE_CC_DIR + TestConstant.CONFIG_TOML_PATH);
        }
        if (withCustomJwtTransformer && withAnalyticsMetricImpl) {
            addCustomJwtTransformer();
        }

        if (!StringUtils.isEmpty(dockerComposeFile)) {
            Utils.copyFile(targetDir + TestConstant.TEST_RESOURCES_PATH
                    + TestConstant.TEST_DOCKER_COMPOSE_DIR + File.separator + dockerComposeFile,
                    ccTempPath + TestConstant.DOCKER_COMPOSE_CC_DIR + TestConstant.DOCKER_COMPOSE_YAML_PATH);
        }

        for (String apiProjectPath: startupAPIs) {
            String substring = apiProjectPath.substring(apiProjectPath.lastIndexOf(File.separator) + 1);
            if (apiProjectPath.endsWith(".zip")) {
                String fileName = substring;
                Utils.copyFile(apiProjectPath, ccTempPath + TestConstant.DOCKER_COMPOSE_DIR +
                        TestConstant.STARTUP_APIS_DIR + File.separator + fileName);
            } else {
                String dirName = substring;
                Utils.copyDirectory(apiProjectPath, ccTempPath + TestConstant.DOCKER_COMPOSE_DIR +
                        TestConstant.STARTUP_APIS_DIR + File.separator + dirName);
            }
        }
        String dockerComposePath = ccTempPath + TestConstant.DOCKER_COMPOSE_CC_DIR
                        + TestConstant.DOCKER_COMPOSE_YAML_PATH;
        MockBackendServer.addMockBackendServiceToDockerCompose(dockerComposePath, backendServiceFile);
        environment = new DockerComposeContainer(new File(dockerComposePath)).withLocalCompose(true);
        addCcLoggersToEnv();
    }

    public static CcInstance getInstance() throws CCTestException {
        if (instance != null) {
            return instance;
        } else throw new CCTestException("CcInstance not initialized");
    }

    public static class Builder {
        String dockerComposeFile;
        String confFileName;
        String backendServiceFile;
        List<String> startupAPIProjectFiles = new ArrayList<>();
        boolean withCustomJwtTransformer = false;
        boolean withAnalyticsMetricImpl = false;

        public Builder withNewDockerCompose(String dockerComposeFile) {
            this.dockerComposeFile = dockerComposeFile;
            return this;
        }

        public Builder withNewConfig(String confFileName){
            this.confFileName = confFileName;
            return this;
        }
        public Builder withBackendServiceFile(String backendServiceFile){
            this.backendServiceFile = backendServiceFile;
            return this;
        }
        //Currently both added via same jar
        public Builder withAllCustomImpls() {
            this.withCustomJwtTransformer = true;
            this.withAnalyticsMetricImpl = true;
            return this;
        }

        public Builder withStartupAPI(String filePath) {
            this.startupAPIProjectFiles.add(filePath);
            return this;
        }


        public CcInstance build() throws IOException, CCTestException {
            instance = new CcInstance(this.dockerComposeFile, this.confFileName, this.backendServiceFile,
                                this.withCustomJwtTransformer, this.withAnalyticsMetricImpl, this.startupAPIProjectFiles);
            return instance;
        }
    }
}
