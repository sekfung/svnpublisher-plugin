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
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.*;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.tmatesoft.svn.core.SVNException;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

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

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath filePath, @Nonnull Launcher launcher, @Nonnull TaskListener taskListener) throws AbortException {
        PrintStream buildLogger = taskListener.getLogger();
        try {
            EnvVars envVars = run.getEnvironment(taskListener);
            if (!filePath.exists()) {
                throw new IOException("io error");
            }
            FilePath filePath1 = new FilePath(filePath, "test1");
            filePath1.act(new TargetFileCallable("create"));
            SVNWorker repo = new SVNWorker.Builder()
                    .svnUrl(Utils.replaceVars(envVars, this.svnUrl))
                    .workingCopy(filePath.getRemote(), filePath.getChannel())
                    .strategy(strategy)
                    .credentials(DescriptorImpl.lookupCredentials(this.svnUrl, run.getParent(), this.credentialsId))
                    .build();
            if (Constants.NEVER_COMMIT.equalsIgnoreCase(strategy)) {
                repo.dispose();
                return;
            }
            try {
                List<ImportItem> artifact = Utils.parseAndReplaceEnvVars(envVars, cloneItems(this.artifacts));
                if (repo.createWorkingCopy(artifact, envVars).isEmpty()) {
                    repo.dispose();
                } else {
                    repo.setCommitMessage(Utils.replaceVars(envVars, commitMessage));
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
                SVNWorker svn = new SVNWorker
                        .Builder()
                        .svnUrl(url)
                        .credentials(cred)
                        .build();
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

        public ListBoxModel doFillStrategyItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Always", Constants.ALWAYS_COMMIT);
            items.add("Never", Constants.NEVER_COMMIT);
            items.add("Trigger", Constants.TRIGGER_COMMIT);
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
