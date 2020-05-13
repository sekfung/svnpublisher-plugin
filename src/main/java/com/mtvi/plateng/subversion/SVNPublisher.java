package com.mtvi.plateng.subversion;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.common.collect.Lists;
import hudson.*;
import hudson.model.*;
import hudson.security.ACL;
import hudson.tasks.*;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.tmatesoft.svn.core.SVNException;

import javax.annotation.Nonnull;

/**
 * The jenkins plugin wrapper is based off of (and on occasion copied verbatim
 * from) the twitter plugin by justinedelson and cactusman.
 *
 * @author bsmith
 */
public class SVNPublisher extends Recorder implements SimpleBuildStep {

    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    private static final Logger LOGGER = Logger.getLogger(SVNPublisher.class.getName());

    private String svnUrl;
    private String credentialsId;
    private String commitMessage;
    private String strategy;
    private List<ImportItem> artifacts = Lists.newArrayList();
    ;

    @DataBoundConstructor
    public SVNPublisher(String svnUrl, String credentialsId, String commitMessage, String strategy, List<ImportItem> artifacts) {
        this.svnUrl = svnUrl;
        this.credentialsId = credentialsId;
        this.artifacts = artifacts;
        this.commitMessage = commitMessage;
        this.strategy = strategy;
    }

    public String getSvnUrl() {
        return svnUrl;
    }

    public List<ImportItem> getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(ArrayList<ImportItem> items) {
        this.artifacts = items;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public void setArtifacts(List<ImportItem> artifacts) {
        this.artifacts = artifacts;
    }

    public String getStrategy() {
        return strategy;
    }

    private List<ImportItem> cloneItems(List<ImportItem> oldArtifacts) {
        List<ImportItem> newArts = Lists.newArrayList();
        if (oldArtifacts != null) {
            for (ImportItem a : oldArtifacts) {
                newArts.add(new ImportItem(a));
            }
        }
        return newArts;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath filePath, @Nonnull Launcher launcher, @Nonnull TaskListener taskListener) throws InterruptedException, IOException {
        PrintStream buildLogger = taskListener.getLogger();

        String workspace;
        try {
            EnvVars envVars = run.getEnvironment(taskListener);
            String target = envVars.get("WORKSPACE");
            workspace = envVars.get("WORKSPACE") + SVNWorker.systemSeparator + "svnpublisher";
            SVNWorker repo = new SVNWorker(SVNWorker.replaceVars(envVars, this.svnUrl), workspace, SVNWorker.replaceVars(envVars, target), DescriptorImpl.lookupCredentials(this.svnUrl, run.getParent(), this.credentialsId));
            try {
                List<ImportItem> artifact = SVNWorker.parseAndReplaceEnvVars(envVars, cloneItems(this.artifacts));
                if (repo.createWorkingCopy(artifact, envVars).isEmpty()) {
                    repo.dispose();
                } else {
                    repo.setCommitMessage(SVNWorker.replaceVars(envVars, commitMessage));
                    repo.commit();
                }
            } catch (SVNPublisherException ex) {
                buildLogger.println(ex.getMessage());
                throw new AbortException(ex.getMessage());
            } finally {
                repo.dispose();
            }
        } catch (IOException | InterruptedException ex) {
            buildLogger.println(ex.getMessage());
            throw new AbortException(ex.getMessage());
        }
    }

    @Extension @Symbol("publishSVN")
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());

        public DescriptorImpl() {
            super(SVNPublisher.class);
            load();
        }

        @Override
        public String getDisplayName() {
            return "Publish to Subversion repository";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public <P extends AbstractProject> FormValidation doCheckCredentialsId(@AncestorInPath Item context, @QueryParameter("svnUrl") final String url, @QueryParameter("credentialsId") final String credentialsId) {
            if ("".equalsIgnoreCase(credentialsId.trim())) {
                return FormValidation.error("credential is not valid");
            }
            try {
                Credentials cred = DescriptorImpl.lookupCredentials(url, context, credentialsId);
                SVNWorker svn = new SVNWorker(url, cred);
                if (svn.getSVNRepository() == null) {
                    return FormValidation.error("Can not connect to repository");
                }
                svn.getSVNRepository().getRepositoryPath("/");
                return FormValidation.ok("Connected to repository");
            } catch (SVNException ex) {
                return FormValidation.error(ex.getErrorMessage().getMessage());
            }
        }

        public <P extends AbstractProject> FormValidation doCheckSvnURL(@AncestorInPath Item context, @QueryParameter("svnUrl") final String url, @QueryParameter("credentialsId") final String credentialsId) {
            if ("".equalsIgnoreCase(url.trim())) {
                return FormValidation.error("svn url is not valid");
            }
            return doCheckCredentialsId(context, url, credentialsId);
        }

//        public <P extends AbstractProject> FormValidation doCheckTarget(@AncestorInPath AbstractProject project, @QueryParameter("target") final String target){
//             if ("".equalsIgnoreCase(target.trim()))
//                 return FormValidation.error("Path is not valid");
//            try {
//                File f  = new File(SVNWorker.replaceVars(project.getSomeBuildWithWorkspace().getEnvironment(TaskListener.NULL), target));
//                if (!f.exists()) return FormValidation.error("Path does not exists");
//            } catch (IOException | InterruptedException ex) {
//                return FormValidation.error(ex.getMessage());
//            }
//            return FormValidation.ok();
//         }

        public ListBoxModel doFillStrategyItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Always", "always");
            items.add("Never", "never");
            items.add("Trigger", "trigger");
            return items;
        }
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context, @QueryParameter String svnUrl) {
            List<DomainRequirement> domainRequirements;
            domainRequirements = URIRequirementBuilder.fromUri(svnUrl.trim()).build();
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(
                            CredentialsMatchers.anyOf(
                                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                    CredentialsMatchers.instanceOf(StandardCertificateCredentials.class),
                                    CredentialsMatchers.instanceOf(SSHUserPrivateKey.class)
                            ),
                            CredentialsProvider.lookupCredentials(StandardCredentials.class,
                                    context,
                                    ACL.SYSTEM,
                                    domainRequirements)
                    );
        }

        private static Credentials lookupCredentials(String repoUrl, Item context, String credentialsId) {
            return credentialsId == null ? null : CredentialsMatchers
                    .firstOrNull(CredentialsProvider.lookupCredentials(StandardCredentials.class, context,
                            ACL.SYSTEM, URIRequirementBuilder.fromUri(repoUrl).build()),
                            CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId),
                                    CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardCredentials.class),
                                            CredentialsMatchers.instanceOf(SSHUserPrivateKey.class))));
        }
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public void setSvnUrl(String svnUrl) {
        this.svnUrl = svnUrl;
    }


}
