/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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
 */

package com.google.jenkins.plugins.k8sengine;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.api.client.http.HttpTransport;
import com.google.api.services.cloudresourcemanager.model.Project;
import com.google.api.services.compute.model.Zone;
import com.google.api.services.container.model.Cluster;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.jenkins.plugins.credentials.oauth.GoogleOAuth2Credentials;
import com.google.jenkins.plugins.k8sengine.client.ClientFactory;
import com.google.jenkins.plugins.k8sengine.client.CloudResourceManagerClient;
import com.google.jenkins.plugins.k8sengine.client.ComputeClient;
import com.google.jenkins.plugins.k8sengine.client.ContainerClient;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/** Provides a build step for publishing build artifacts to a Kubernetes cluster running on GKE. */
public class KubernetesEnginePublisher extends Notifier implements SimpleBuildStep, Serializable {
  private static final Logger LOGGER = Logger.getLogger(KubernetesEnginePublisher.class.getName());

  static final String EMPTY_NAME = "- none -";
  static final String EMPTY_VALUE = "";

  private String projectId;
  private String clusterName;
  private String credentialsId;
  private String zone;
  private String manifestPattern;
  private boolean verifyDeployments;
  private boolean verifyServices;
  private boolean isTestCleanup;
  private KubeConfigAfterBuildStep afterBuildStep = null;

  /** Constructs a new {@link KubernetesEnginePublisher}. */
  @DataBoundConstructor
  public KubernetesEnginePublisher() {}

  @DataBoundSetter
  public void setProjectId(String projectId) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(projectId));
    this.projectId = projectId;
  }

  public String getProjectId() {
    return this.projectId;
  }

  @DataBoundSetter
  public void setClusterName(String clusterName) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(clusterName));
    this.clusterName = clusterName;
  }

  public String getClusterName() {
    return this.clusterName;
  }

  public String getCredentialsId() {
    return credentialsId;
  }

  @DataBoundSetter
  public void setCredentialsId(String credentialsId) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(credentialsId));
    this.credentialsId = credentialsId;
  }

  @DataBoundSetter
  public void setManifestPattern(String manifestPattern) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(manifestPattern));
    this.manifestPattern = manifestPattern;
  }

  public String getZone() {
    return this.zone;
  }

  @DataBoundSetter
  public void setZone(String zone) {
    this.zone = zone;
  }

  @DataBoundSetter
  public void setVerifyDeployments(boolean verifyDeployments) {
    this.verifyDeployments = verifyDeployments;
  }

  public boolean isVerifyDeployments() {
    return this.verifyDeployments;
  }

  @DataBoundSetter
  public void setVerifyServices(boolean verifyServices) {
    this.verifyServices = verifyServices;
  }

  public boolean isVerifyServices() {
    return this.verifyServices;
  }

  @VisibleForTesting
  void setAfterBuildStep(KubeConfigAfterBuildStep afterBuildStep) {
    this.afterBuildStep = afterBuildStep;
  }

  /** {@inheritDoc} */
  @Override
  public void perform(
      @Nonnull Run<?, ?> run,
      @Nonnull FilePath workspace,
      @Nonnull Launcher launcher,
      @Nonnull TaskListener listener)
      throws AbortException, InterruptedException, IOException {
    LOGGER.log(
        Level.INFO,
        String.format(
            "GKE Deploying, projectId: %s cluster: %s zone: %s", projectId, clusterName, zone));

    // create a connection to the GKE API client
    ContainerClient client = getContainerClient(credentialsId);

    // retrieve the cluster
    Cluster cluster = client.getCluster(projectId, zone, clusterName);

    // generate a kubeconfig for the cluster
    KubeConfig kubeConfig = KubeConfig.fromCluster(projectId, cluster);

    // run kubectl apply
    KubectlWrapper.runKubectlCommand(
        new JenkinsRunContext.Builder()
            .workspace(workspace)
            .launcher(launcher)
            .taskListener(listener)
            .run(run)
            .build(),
        kubeConfig,
        "apply",
        ImmutableList.<String>of("-f", workspace.child(manifestPattern).getRemote()));

    // run the after build step if it exists
    // NOTE(craigatgoogle): Due to the reflective way this class is created, initializers aren't
    // run, so we still have to check for null.
    if (afterBuildStep != null) {
      afterBuildStep.perform(kubeConfig, run, workspace, launcher, listener);
    }
  }

  @Override
  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.BUILD;
  }

  @Symbol("kubernetesEngineDeploy")
  @Extension
  public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
    private ClientFactory clientFactory;

    @Nonnull
    @Override
    public String getDisplayName() {
      return Messages.KubernetesEnginePublisher_DisplayName();
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
      return true;
    }

    @VisibleForTesting
    ClientFactory getClientFactory(Jenkins context, String credentialsId) throws AbortException {
      if (this.clientFactory == null
          || !this.clientFactory.getCredentialsId().equals(credentialsId)) {
        this.clientFactory = new ClientFactory(context, credentialsId);
      }
      return this.clientFactory;
    }

    public FormValidation doCheckClusterName(@QueryParameter String value) {
      if (Strings.isNullOrEmpty(value)) {
        return FormValidation.error(Messages.KubernetesEnginePublisher_ClusterRequired());
      }

      // TODO(craigatgoogle): check to ensure the cluster exists within GKE cluster
      return FormValidation.ok();
    }

    public FormValidation doCheckManifestPattern(@QueryParameter String value) {
      if (Strings.isNullOrEmpty(value)) {
        return FormValidation.error(Messages.KubernetesEnginePublisher_ManifestRequired());
      }
      return FormValidation.ok();
    }

    public ListBoxModel doFillZoneItems(
        @AncestorInPath Jenkins context,
        @QueryParameter("projectId") String projectId,
        @QueryParameter("credentialsId") String credentialsId) {
      ListBoxModel items = new ListBoxModel();
      items.add(EMPTY_NAME, EMPTY_VALUE);
      if (Strings.isNullOrEmpty(projectId) || Strings.isNullOrEmpty(credentialsId)) {
        return items;
      }

      ClientFactory clientFactory;
      try {
        clientFactory = getClientFactory(context, credentialsId);
      } catch (AbortException ae) {
        LOGGER.log(Level.SEVERE, Messages.KubernetesEnginePublisher_CredentialAuthFailed(), ae);
        items.clear();
        items.add(Messages.KubernetesEnginePublisher_CredentialAuthFailed(), EMPTY_VALUE);
        return items;
      }

      try {
        ComputeClient compute = clientFactory.computeClient();
        List<Zone> zones = compute.getZones(projectId);

        if (zones.isEmpty()) {
          return items;
        }

        zones.forEach(z -> items.add(z.getName()));
        items.get(1).selected = true;
        return items;
      } catch (IOException ioe) {
        LOGGER.log(Level.SEVERE, Messages.KubernetesEnginePublisher_ZoneFillError(), ioe);
        items.clear();
        items.add(Messages.KubernetesEnginePublisher_ZoneFillError(), EMPTY_VALUE);
        return items;
      }
    }

    public FormValidation doCheckZone(
        @AncestorInPath Jenkins context,
        @QueryParameter("zone") String zone,
        @QueryParameter("projectId") String projectId,
        @QueryParameter("credentialsId") String credentialsId) {
      if (Strings.isNullOrEmpty(zone)) {
        return FormValidation.error(Messages.KubernetesEnginePublisher_ZoneRequired());
      } else if (Strings.isNullOrEmpty(projectId) || Strings.isNullOrEmpty(credentialsId)) {
        return FormValidation.error(
            Messages.KubernetesEnginePublisher_ZoneProjectIdCredentialRequired());
      }

      ClientFactory clientFactory;
      try {
        clientFactory = getClientFactory(context, credentialsId);
      } catch (AbortException ae) {
        return FormValidation.error(Messages.KubernetesEnginePublisher_CredentialAuthFailed());
      }

      try {
        ComputeClient compute = clientFactory.computeClient();
        List<Zone> zones = compute.getZones(projectId);
        Optional<Zone> matchingZone =
            zones.stream().filter(z -> zone.equalsIgnoreCase(z.getName())).findFirst();
        if (!matchingZone.isPresent()) {
          return FormValidation.error(Messages.KubernetesEnginePublisher_ZoneNotInProject());
        }
      } catch (IOException ioe) {
        return FormValidation.error(Messages.KubernetesEnginePublisher_ZoneVerificationError());
      }

      return FormValidation.ok();
    }

    public ListBoxModel doFillProjectIdItems(
        @AncestorInPath Jenkins context,
        @QueryParameter("credentialsId") final String credentialsId) {
      ListBoxModel items = new ListBoxModel();
      items.add(EMPTY_NAME, EMPTY_VALUE);
      if (Strings.isNullOrEmpty(credentialsId)) {
        return items;
      }

      ClientFactory clientFactory;
      try {
        clientFactory = this.getClientFactory(context, credentialsId);
      } catch (AbortException ae) {
        LOGGER.log(Level.SEVERE, Messages.KubernetesEnginePublisher_CredentialAuthFailed(), ae);
        items.clear();
        items.add(Messages.KubernetesEnginePublisher_CredentialAuthFailed(), EMPTY_VALUE);
        return items;
      }

      String defaultProjectId = clientFactory.getDefaultProjectId();
      try {
        CloudResourceManagerClient client = clientFactory.cloudResourceManagerClient();
        List<Project> projects = client.getAccountProjects();

        if (projects.isEmpty()) {
          return items;
        }

        projects
            .stream()
            .filter(p -> !p.getProjectId().equals(defaultProjectId))
            .forEach(p -> items.add(p.getProjectId()));

        if (Strings.isNullOrEmpty(defaultProjectId)) {
          items.get(1).selected = true;
          return items;
        }

        if (projects.size() == items.size()) {
          items.add(new Option(defaultProjectId, defaultProjectId, true));
        } else {
          // Add defaultProjectId anyway, but select the first available item.
          items.add(defaultProjectId);
          items.get(1).selected = true;
        }
        return items;
      } catch (IOException ioe) {
        LOGGER.log(Level.SEVERE, Messages.KubernetesEnginePublisher_ProjectIDFillError(), ioe);
        items.clear();
        items.add(Messages.KubernetesEnginePublisher_ProjectIDFillError(), EMPTY_VALUE);
        return items;
      }
    }

    public FormValidation doCheckProjectId(
        @AncestorInPath Jenkins context,
        @QueryParameter("projectId") String projectId,
        @QueryParameter("credentialsId") String credentialsId) {
      if (Strings.isNullOrEmpty(projectId)) {
        return FormValidation.error(Messages.KubernetesEnginePublisher_ProjectIDRequired());
      } else if (Strings.isNullOrEmpty(credentialsId)) {
        return FormValidation.error(
            Messages.KubernetesEnginePublisher_ProjectCredentialIDRequired());
      }

      ClientFactory clientFactory;
      try {
        clientFactory = getClientFactory(context, credentialsId);
      } catch (AbortException ae) {
        return FormValidation.error(Messages.KubernetesEnginePublisher_CredentialAuthFailed());
      }

      try {
        CloudResourceManagerClient client = clientFactory.cloudResourceManagerClient();
        List<Project> projects = client.getAccountProjects();
        Optional<Project> matchingProject =
            projects.stream().filter(p -> projectId.equals(p.getProjectId())).findFirst();
        if (!matchingProject.isPresent()) {
          return FormValidation.error(
              Messages.KubernetesEnginePublisher_ProjectIDNotUnderCredential());
        }
      } catch (IOException ioe) {
        return FormValidation.error(
            Messages.KubernetesEnginePublisher_ProjectIDVerificationError());
      }

      return FormValidation.ok();
    }

    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Jenkins context) {
      if (context == null || !context.hasPermission(Item.CONFIGURE)) {
        return new StandardListBoxModel();
      }

      return new StandardListBoxModel()
          .includeEmptyValue()
          .includeMatchingAs(
              ACL.SYSTEM,
              context,
              StandardCredentials.class,
              Collections.<DomainRequirement>emptyList(),
              CredentialsMatchers.instanceOf(GoogleOAuth2Credentials.class));
    }

    public FormValidation doCheckCredentialsId(
        @QueryParameter("projectId") String projectId,
        @QueryParameter("credentialsId") String credentialsId) {
      if (credentialsId.isEmpty()) {
        return FormValidation.error(Messages.KubernetesEnginePublisher_NoCredential());
      }

      if (projectId.isEmpty()) {
        return FormValidation.error(
            Messages.KubernetesEnginePublisher_CredentialProjectIDRequired());
      }

      try {
        getContainerClient(credentialsId);
      } catch (AbortException | RuntimeException e) {
        return FormValidation.error(Messages.KubernetesEnginePublisher_CredentialAuthFailed());
      }

      return FormValidation.ok();
    }
  }

  private static ContainerClient getContainerClient(String credentialsId) throws AbortException {
    return new ClientFactory(
            Jenkins.getInstance(),
            ImmutableList.<DomainRequirement>of(),
            credentialsId,
            Optional.<HttpTransport>empty())
        .containerClient();
  }

  @FunctionalInterface
  interface KubeConfigAfterBuildStep extends Serializable {
    public void perform(
        KubeConfig kubeConfig,
        Run<?, ?> run,
        FilePath workspace,
        Launcher launcher,
        TaskListener listener)
        throws AbortException, InterruptedException, IOException;
  }
}
