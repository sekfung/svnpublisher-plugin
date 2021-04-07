package com.mtvi.plateng.subversion;

import com.cloudbees.plugins.credentials.Credentials;
import com.google.common.collect.Lists;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.remoting.VirtualChannel;
import hudson.scm.CredentialsSVNAuthenticationProviderImpl;
import jenkins.security.MasterToSlaveCallable;
import jenkins.security.Roles;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.remoting.RoleChecker;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.*;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SVNForceImport can be used to import a maven project into an svn repository.
 * It has the ability to import numerous different files/folders based on
 * matching a regular expression pattern. Each matched item can be renamed and
 * placed in differing folders.
 * <p>
 * SVNForceImport can also read a projects pom file and extract Major Minor and
 * Patch version numbers.
 *
 * @author travassos
 * @version 1.0
 */
public class SVNWorker implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(SVNWorker.class.getName());
    private SVNClientManager manager;
    private SVNRepository repository;
    private File workingCopy;
    private String commitMessage = "";
    private Launcher launcher;
    private File baseLocalDir;
    private String strategy;
    private Credentials credentials;

    private SVNWorker(String url, String workspace, Launcher launcher, Credentials credentials, String strategy) {
        try {
            File workSpaceFile = new File(workspace);
            this.workingCopy = new File(workSpaceFile, Constants.PLUGIN_NAME);
            this.baseLocalDir = workSpaceFile;
            this.strategy = strategy;
            this.credentials = credentials;
            this.launcher = launcher;
            initRepo(SVNURL.parseURIDecoded(url));
        } catch (SVNException ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage());
        }
    }
    private SVNWorker(String url, Credentials credentials) {
        try {
            initRepo(SVNURL.parseURIDecoded(url));
        } catch (SVNException ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage());
        }
    }


    private void initRepo(SVNURL repoUrl) throws SVNException {
        SVNClientManager manager = createManager();
        repository = manager.createRepository(repoUrl, true);
    }

    public SVNClientManager createManager() {
        ISVNAuthenticationManager sam;
        ISVNOptions options;

        File configDir = SVNWCUtil.getDefaultConfigurationDirectory();
        sam = SVNWCUtil.createDefaultAuthenticationManager(configDir);
        sam.setAuthenticationProvider(new CredentialsSVNAuthenticationProviderImpl(credentials));
        options = SVNWCUtil.createDefaultOptions(configDir, true);

        DAVRepositoryFactory.setup();
        manager = SVNClientManager.newInstance(options, sam);
        return manager;
    }

    private static void cleanWorkspace(File workspace) {
        try {
            if (!workspace.exists()) {
                LOGGER.info("workspace is not existed: " + workspace.getName());
                return;
            }
            FileUtils.deleteDirectory(workspace);
        } catch (IOException e) {
            LOGGER.info(e.getMessage());
            e.printStackTrace();
        }
    }


    public String getCommitMessage() {
        return commitMessage;
    }

    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    public SVNClientManager getSVNManager() {
        return this.manager;
    }

    public SVNRepository getSVNRepository() {
        return this.repository;
    }

    public static String getRelativePath(SVNURL repoURL, SVNRepository repository) throws SVNException {
        String repoPath = repoURL.getPath().substring(repository.getRepositoryRoot(false).getPath().length());
        if (!repoPath.startsWith("/")) {
            repoPath = "/" + repoPath;
        }
        return repoPath;
    }


    public List<File> createWorkingCopy(List<ImportItem> item, EnvVars envVars) throws SVNPublisherException {
        List<File> files = Lists.newArrayList();
        try {
            cleanWorkspace(workingCopy);
            SVNURL svnPath = repository.getLocation();
            launcher.getChannel().call(new CheckoutTask(svnPath, workingCopy));
            for (ImportItem i : item) {
                SVNURL svnDestination = svnPath.appendPath(i.getPath(), true);
                SVNNodeKind pathType = repository.checkPath(getRelativePath(svnDestination, repository), repository.getLatestRevision());
                File dir = new File(workingCopy, i.getPath());
                if (pathType == SVNNodeKind.NONE) {
                    launcher.getChannel().call(new AddTask(dir));
                } else if (pathType == SVNNodeKind.DIR) {
                    launcher.getChannel().call(new CheckoutTask(svnPath, dir));
                }
                FilePath localPath = new FilePath(new FilePath(baseLocalDir), i.getLocalPath());
                files.addAll(localPath.act(new CopyFilesTask(i, envVars, strategy, dir)));
            }
        } catch (SVNException e) {
            throw new SVNPublisherException("Error in repository " + e.getMessage());
        } catch (Exception ex1) {
            throw new SVNPublisherException(ex1);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return files;
    }

    public void commit() throws IOException, InterruptedException {
        workingCopy.act(new CommitTask(commitMessage));
    }

    public void dispose() {
        cleanWorkspace(workingCopy);
        manager.dispose();
    }


    public static class Builder {
        private String url;
        private String workingCopy;
        private Launcher launcher;
        private Credentials credentials;
        private String strategy = "always";

        public Builder svnUrl(String svnUrl) {
            this.url = svnUrl;
            return this;
        }

        public Builder workingCopy(String workingCopy, Launcher launcher) {
            this.launcher = launcher;
            this.workingCopy = workingCopy;
            return this;
        }

        public Builder credentials(Credentials credentials) {
            this.credentials = credentials;
            return this;
        }

        public Builder strategy(String strategy) {
            this.strategy = strategy;
            return this;
        }

        public SVNWorker build() {
            if ("".equalsIgnoreCase(url)) {
                throw new IllegalArgumentException("svn url is empty");
            }
            if (credentials == null) {
                throw new IllegalArgumentException("credentials not found");
            }
            if (launcher == null || "".equalsIgnoreCase(workingCopy)) {
                return new SVNWorker(url, credentials);
            }
            return new SVNWorker(url, workingCopy, launcher, credentials, strategy);
        }
    }

    private class CheckoutTask extends MasterToSlaveCallable<SVNPropertyData, Throwable> {
        private static final long serialVersionUID = 2L;
        private SVNURL svnPath;
        private File file;

        CheckoutTask(SVNURL svnPath, File file) {
            this.svnPath = svnPath;
            this.file = file;
        }

        @Override
        public SVNPropertyData call() throws Throwable {
            try {
                SVNClientManager manager = createManager();
                if (!file.exists()) {
                    throw new IOException("io exception");
                }
                manager.getUpdateClient().doCheckout(svnPath, new File(file, Constants.PLUGIN_NAME), SVNRevision.HEAD, SVNRevision.HEAD, SVNDepth.INFINITY, true);
                return manager.getWCClient().doGetProperty(svnPath, null, SVNRevision.HEAD, SVNRevision.HEAD);
            } catch (SVNException e) {
                LOGGER.info(e.getMessage());
                e.printStackTrace();
            }
            return null;
        }
    }

    private  class AddTask extends MasterToSlaveCallable<Boolean, Throwable> {
        private static final long serialVersionUID = 3L;

        private File file;

        AddTask(File file) {
            this.file = file;
        }


        @Override
        public Boolean call() throws Throwable {
            if (!file.exists() && !file.mkdirs()) {
                throw new IOException("mkdir file failed: " + file.getName());
            }
            try {
                SVNClientManager manager = createManager();
                manager.getWCClient().doAdd(file, false, true, false, SVNDepth.INFINITY, false, false, true);
            } catch (SVNException e) {
                LOGGER.info(e.getMessage());
                e.printStackTrace();
                return false;
            }
            return true;
        }
    }

    private  class CopyFilesTask extends MasterToSlaveCallable<List<File>, Throwable> {
        private static final long serialVersionUID = 4L;
        private ImportItem item;
        private EnvVars envVars;
        private String strategy;
        private FilePath workingCopy;

        CopyFilesTask(ImportItem item, EnvVars vars, String strategy, FilePath localPath) {
            this.item = item;
            this.envVars = vars;
            this.strategy = strategy;
            this.workingCopy = localPath;
        }


        @Override
        public List<File> call() throws Throwable {
            List<File> files = new ArrayList<>();
            String[] params = item.getParams().split(",");
            //  empty params equals always commit
            if (Constants.ALWAYS_COMMIT.equalsIgnoreCase(this.strategy)) {
                params = new String[]{""};
            }
            try {
                SVNClientManager manager = createManager();
                List<File> filesToCopy = Utils.findFilesWithPattern(workingCopy, item.getPattern(), params, envVars);
                for (File f : filesToCopy) {
                    File wc = new File(workingCopy, f.getName());
                    boolean toAdd = !wc.exists();
                    FileUtils.copyFile(new File(file, f.getName()), wc);
                    if (toAdd) {
                        manager.getWCClient().doAdd(wc, false, false, false, SVNDepth.INFINITY, false, false, false);
                    }
                    files.add(wc);
                }
            } catch (SVNPublisherException | SVNException e) {
                LOGGER.info(e.getMessage());
                e.printStackTrace();
            }
            return files;
        }
    }


    private class CommitTask implements FilePath.FileCallable<Boolean>, Serializable {
        private static final long serialVersionUID = 5L;
        private transient SVNClientManager manager;
        private transient String commitMessage;

        CommitTask( String commitMsg) {
            this.manager = manager;
            this.commitMessage = commitMsg;
        }

        @Override
        public Boolean invoke(File file, VirtualChannel virtualChannel) throws IOException, InterruptedException {
            try {
                SVNClientManager svnClientManager = createManager();
                SVNCommitClient commit = manager.getCommitClient();
                SVNCommitPacket a = commit.doCollectCommitItems(new File[]{file}, false, true, SVNDepth.INFINITY, null);
                commit.doCommit(a, false, commitMessage);
                return true;
            } catch (SVNException e) {
                LOGGER.info(e.getMessage());
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public void checkRoles(RoleChecker roleChecker) throws SecurityException {
            roleChecker.check(this, Roles.SLAVE);
        }
    }

}
