/*
 * Copyright 2018 the original author or authors.
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
package com.libertymutualgroup.herman.aws.cft;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder;
import com.amazonaws.services.cloudformation.model.AlreadyExistsException;
import com.amazonaws.services.cloudformation.model.AmazonCloudFormationException;
import com.amazonaws.services.cloudformation.model.CreateStackRequest;
import com.amazonaws.services.cloudformation.model.DescribeStackResourcesRequest;
import com.amazonaws.services.cloudformation.model.DescribeStackResourcesResult;
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest;
import com.amazonaws.services.cloudformation.model.Parameter;
import com.amazonaws.services.cloudformation.model.Stack;
import com.amazonaws.services.cloudformation.model.StackResource;
import com.amazonaws.services.cloudformation.model.Tag;
import com.amazonaws.services.cloudformation.model.UpdateStackRequest;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.InvocationType;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.atlassian.bamboo.deployments.execution.DeploymentTaskContext;
import com.atlassian.bamboo.task.TaskException;
import com.atlassian.bamboo.variable.CustomVariableContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.libertymutualgroup.herman.aws.AwsExecException;
import com.libertymutualgroup.herman.aws.ecs.PropertyHandler;
import com.libertymutualgroup.herman.aws.ecs.TaskContextPropertyHandler;
import com.libertymutualgroup.herman.logging.HermanLogger;
import com.libertymutualgroup.herman.task.cft.CFTPushTaskProperties;
import com.libertymutualgroup.herman.util.FileUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CftPush {

    public static final String INTERRUPTED_WHILE_POLLING = "Interrupted while polling";
    private static final Logger LOGGER = LoggerFactory.getLogger(CftPush.class);
    private static final String BUILD_NUMBER = "buildNumber";
    private static final String MAVEN_GROUP = "maven.groupId";
    private static final String MAVEN_ART = "maven.artifactId";
    private static final String MAVEN_VERS = "maven.version";
    private static final int RANDOM_PASSWORD_LENGTH = 20;
    private static final int POLLING_INTERVAL_MS = 10000;
    private static final List<String> CFT_FILE_NAMES = Arrays.asList("cft.template", "cft.yml", "cft.json");
    private Properties props = new Properties();
    private Properties output = new Properties();
    private HermanLogger buildLogger;
    private DeploymentTaskContext taskContext;
    private AmazonCloudFormation cftClient;
    private AWSLambda lambdaClient;
    private Regions region;
    private CustomVariableContext customVariableContext;
    private CFTPushTaskProperties taskProperties;

    public CftPush(HermanLogger buildLogger, DeploymentTaskContext taskContext, AWSCredentials sessionCredentials,
        ClientConfiguration config, Regions region, CustomVariableContext customVariableContext,
        CFTPushTaskProperties taskProperties) {

        this.buildLogger = buildLogger;
        this.taskContext = taskContext;
        this.customVariableContext = customVariableContext;
        this.region = region;

        cftClient = AmazonCloudFormationClientBuilder.standard()
            .withCredentials(new AWSStaticCredentialsProvider(sessionCredentials)).withClientConfiguration(config)
            .withRegion(region).build();

        lambdaClient = AWSLambdaClientBuilder.standard()
            .withCredentials(new AWSStaticCredentialsProvider(sessionCredentials)).withClientConfiguration(config)
            .withRegion(region).build();

        this.taskProperties = taskProperties;
    }

    public void push() throws TaskException {

        // Input data outside of CFT
        String env = taskContext.getDeploymentContext().getEnvironmentName();
        String projecName = taskContext.getDeploymentContext().getDeploymentProjectName();

        if (!this.taskProperties.getCftPushVariableBrokerLambda().isEmpty()) {
            buildLogger.addLogEntry(
                "Getting CFT variables from Lambda: " + this.taskProperties.getCftPushVariableBrokerLambda());
            introspectEnvironment();
        }
        injectBambooContext();
        importPropFiles(env);

        String stackName = deriveStackName(projecName, env);

        if (!stackName.contains(region.getName())) {
            stackName = stackName + "-" + region.getName();
        }

        createStack(stackName);

        buildLogger.addLogEntry("Stack triggered...");
        waitForCompletion(stackName);
        outputStack(stackName);

        try (OutputStream fileOut = new FileOutputStream("stackoutput.properties")) {
            output.store(fileOut, null);
        } catch (IOException e) {
            throw new AwsExecException(e);
        }
    }

    private String deriveStackName(String deployProject, String deployEnvironment) {
        String concat = deployProject.replace(" ", "-") + "-" + deployEnvironment.replace(" ", "-");
        return concat.toLowerCase();
    }

    private void importPropFiles(String env) {
        File stackOut = new File("stackoutput.properties");

        try (FileReader stackRead = new FileReader(stackOut);) {
            if (stackOut.exists()) {
                props.load(stackRead);
                buildLogger.addLogEntry("Loaded stackoutput.properties");
            }
        } catch (IOException e) {
            LOGGER.debug("No stackoutput.properties", e);
            buildLogger.addLogEntry("No stackoutput.properties");
        }

        String root = taskContext.getRootDirectory().getAbsolutePath();
        File envProps = new File(root + File.separator + env + ".properties");
        try (FileReader envFile = new FileReader(envProps);) {
            // load second to allow env to override
            if (envProps.exists()) {
                props.load(envFile);
                buildLogger.addLogEntry("Loaded " + envProps.getName());
                buildLogger.addLogEntry("Props: " + props.toString());
            }
        } catch (IOException e) {
            LOGGER.debug("Property file not found for env: " + env, e);
            buildLogger.addLogEntry("No " + env + ".properties");
        }
    }

    private void injectBambooContext() {
        PropertyHandler handler = new TaskContextPropertyHandler(taskContext, customVariableContext);
        Properties bambooContext = handler.lookupProperties(BUILD_NUMBER, MAVEN_GROUP, MAVEN_ART,
            MAVEN_VERS);

        String randomPass = RandomStringUtils.randomAlphanumeric(RANDOM_PASSWORD_LENGTH);
        props.put("RandomPassword", randomPass);

        String build = "BUILD" + bambooContext.getProperty(BUILD_NUMBER);
        props.put("BuildId", build);

        String art = bambooContext.getProperty(MAVEN_ART);
        if (art != null) {
            props.put("ArtifactId", art);
        }

        String versionId = bambooContext.getProperty("maven.versionId");
        if (versionId != null) {
            props.put("Version", versionId);
        }

        String deployEnvironment = taskContext.getDeploymentContext().getEnvironmentName();
        if (deployEnvironment != null) {
            props.put("DeployEnvironment", deployEnvironment);
        }

    }

    private void createStack(String name) {
        String template = getTemplate();
        List<Parameter> parameters = convertPropsToCftParams(template);

        String deployEnvironment = taskContext.getDeploymentContext().getEnvironmentName();

        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag().withKey("Name").withValue(name));
        tags.add(new Tag().withKey(this.taskProperties.getAppTagKey()).withValue(name));
        tags.add(new Tag().withKey(this.taskProperties.getAppTagKey() + "_uid").withValue("app-e312c4299a"));
        tags.add(new Tag().withKey(this.taskProperties.getAppTagKey() + "_env").withValue(deployEnvironment));
        tags.add(new Tag().withKey(this.taskProperties.getSbuTagKey()).withValue(this.taskProperties.getSbu()));

        PropertyHandler handler = new TaskContextPropertyHandler(taskContext, customVariableContext);
        Properties bambooContext = handler.lookupProperties(BUILD_NUMBER, MAVEN_GROUP, MAVEN_ART,
            MAVEN_VERS);

        String artifactId = bambooContext.getProperty(MAVEN_ART);
        if (artifactId != null && StringUtils.isNotEmpty(artifactId)) {
            tags.add(new Tag().withKey(this.taskProperties.getCompany() + "_gav")
                .withValue(bambooContext.getProperty(MAVEN_GROUP) + ":"
                    + bambooContext.getProperty(MAVEN_ART) + ":"
                    + bambooContext.getProperty(MAVEN_VERS)));
        }

        try {
            CreateStackRequest createStackRequest = new CreateStackRequest().withCapabilities("CAPABILITY_IAM")
                .withCapabilities("CAPABILITY_NAMED_IAM").withStackName(name).withTemplateBody(template)
                .withTags(tags).withParameters(parameters);

            cftClient.createStack(createStackRequest);
        } catch (AlreadyExistsException e) {
            LOGGER.debug("Stack already exists: " + name, e);

            UpdateStackRequest updateStackRequest = new UpdateStackRequest().withCapabilities("CAPABILITY_IAM")
                .withCapabilities("CAPABILITY_NAMED_IAM").withStackName(name).withTemplateBody(template)
                .withParameters(parameters).withTags(tags);
            try {
                cftClient.updateStack(updateStackRequest);
            } catch (AmazonCloudFormationException noUpdateException) {
                LOGGER.debug("Stack has no updates: " + name, noUpdateException);

                if (noUpdateException.getMessage().contains("No updates are to be performed")) {
                    buildLogger.addLogEntry("No CFT Updates to apply, skipping CFT Push...");
                } else {
                    buildLogger.addLogEntry(noUpdateException.toString());
                    throw new AwsExecException();
                }
            } catch (AmazonServiceException ase) {
                LOGGER.debug("UpdateStackRequest threw an exception for " + name, ase);

                buildLogger.addLogEntry(ase.toString());
                throw new AwsExecException();
            }
        }

    }

    private String getTemplate() {
        String root = taskContext.getRootDirectory().getAbsolutePath();
        FileUtil fileUtil = new FileUtil(root, buildLogger);

        String template = null;
        for (String fileName: CFT_FILE_NAMES) {
            boolean fileExists = fileUtil.fileExists(fileName);
            if (fileExists) {
                template = fileUtil.findFile(fileName, false);
                buildLogger.addLogEntry("Template used: " + fileName);
            }
        }
        if (template == null) {
            throw new AwsExecException("CloudFormation template not found. Valid file names: "
                + String.join(", ", CFT_FILE_NAMES));
        }
        return template;
    }

    private List<Parameter> convertPropsToCftParams(String template) {
        List<Parameter> parameters = new ArrayList<>();
        for (Object key: props.keySet()) {
            if (template.contains((String) key)) {
                parameters.add(new Parameter().withParameterKey((String) key)
                    .withParameterValue(props.getProperty((String) key)));
            }
        }
        return parameters;
    }

    private void introspectEnvironment() throws TaskException {
        InvokeRequest cftVariableBrokerReq = new InvokeRequest()
            .withFunctionName(this.taskProperties.getCftPushVariableBrokerLambda())
            .withInvocationType(InvocationType.RequestResponse)
            .withPayload("\"" + region.getName().toLowerCase() + "\"");

        String variableJson = new String(this.lambdaClient.invoke(cftVariableBrokerReq).getPayload().array(),
            Charset.forName("UTF-8"));
        Map<String, String> variables;
        try {
            variables = new ObjectMapper().readValue(variableJson, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            buildLogger.addLogEntry(e.getMessage());
            buildLogger.addLogEntry("Unable to parse variables from " + variableJson);
            throw new TaskException(e.getMessage(), e);
        }

        for (Map.Entry<String, String> entry: variables.entrySet()) {
            buildLogger.addLogEntry("Injecting " + entry.getKey() + " = " + entry.getValue());
            props.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * @param stackName
     */
    private void outputStack(String stackName) {

        DescribeStackResourcesRequest req = new DescribeStackResourcesRequest();
        req.setStackName(stackName);

        DescribeStackResourcesResult res = cftClient.describeStackResources(req);

        for (StackResource r: res.getStackResources()) {
            buildLogger.addLogEntry(r.getPhysicalResourceId());
            buildLogger.addLogEntry(r.getResourceType());
            output.put("aws.stack." + r.getLogicalResourceId(), r.getPhysicalResourceId());
        }

        List<String> resources = new ArrayList<>();
        for (StackResource r: res.getStackResources()) {
            if ("AWS::ECS::TaskDefinition".equals(r.getResourceType())) {
                String id = r.getPhysicalResourceId();
                String task = id.split("/")[1];
                task = task.replace(":", "-");
                resources.add(task);

            }
        }

    }

    private void waitForCompletion(String stackName) {
        DescribeStacksRequest wait = new DescribeStacksRequest();
        wait.setStackName(stackName);
        Boolean completed = false;

        buildLogger.addLogEntry("Waiting...");

        // Try waiting at the start to avoid a race before the stack starts updating
        sleep();
        while (!completed) {
            List<Stack> stacks = cftClient.describeStacks(wait).getStacks();

            completed = reportStatusAndCheckCompletionOf(stacks);

            // Not done yet so sleep for 10 seconds.
            if (!completed) {
                sleep();
            }
        }

        buildLogger.addLogEntry("done");
    }

    private void sleep() {
        try {
            Thread.sleep(POLLING_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            buildLogger.addLogEntry(INTERRUPTED_WHILE_POLLING);
            throw new AwsExecException(INTERRUPTED_WHILE_POLLING);
        }
    }

    private Boolean reportStatusAndCheckCompletionOf(List<Stack> stacks) {
        for (Stack stack: stacks) {
            reportStatusOf(stack);
            if (stack.getStackStatus().contains("IN_PROGRESS")) {
                return false;
            }

            if (stack.getStackStatus().contains("FAILED") || stack.getStackStatus().contains("ROLLBACK")) {
                throw new AwsExecException("CFT pushed failed - " + stack.getStackStatus());
            }
        }
        return true;
    }

    private void reportStatusOf(Stack stack) {

        String status = stack.getStackStatus();
        String reason = stack.getStackStatusReason();
        if (reason != null) {
            status += " : " + reason;
        }
        buildLogger.addLogEntry(status);
    }

}
