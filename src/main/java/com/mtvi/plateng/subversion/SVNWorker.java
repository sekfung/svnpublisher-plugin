package com.mtvi.plateng.subversion;

import com.cloudbees.plugins.credentials.Credentials;
import com.google.common.collect.Lists;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import hudson.scm.CredentialsSVNAuthenticationProviderImpl;
import jenkins.MasterToSlaveFileCallable;
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

import java.io.*;
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
    private FilePath workingCopy;
    private String commitMessage = "";
    private String baseLocalDir;
    private String strategy;

    public SVNWorker() {
        throw new IllegalArgumentException("");
    }

    private SVNWorker(String url, FilePath workspace, String baseLocalDir, Credentials credentials, String strategy) {
        try {
            initRepo(SVNURL.parseURIDecoded(url), credentials);
            this.workingCopy = workspace;
            this.baseLocalDir = baseLocalDir;
            this.strategy = strategy;
        } catch (SVNException ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage());
        }
    }

    private SVNWorker(String url, Credentials credentials) {
        try {
            initRepo(SVNURL.parseURIDecoded(url), credentials);
        } catch (SVNException ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage());
        }
    }

    private void initRepo(SVNURL repoUrl, Credentials credentials) throws SVNException {
        ISVNAuthenticationManager sam;
        ISVNOptions options;

        File configDir = SVNWCUtil.getDefaultConfigurationDirectory();
        sam = SVNWCUtil.createDefaultAuthenticationManager(configDir);
        sam.setAuthenticationProvider(new CredentialsSVNAuthenticationProviderImpl(credentials));
        options = SVNWCUtil.createDefaultOptions(configDir, true);

        DAVRepositoryFactory.setup();
        manager = SVNClientManager.newInstance(options, sam);
        repository = manager.createRepository(repoUrl, true);
    }

    private static void cleanWorkspace(FilePath workspace) {
        try {
            workspace.act(new TargetFileCallable("delete"));
        } catch (IOException | InterruptedException e) {
            LOGGER.info(e.getMessage());
            e.printStackTrace();
        }
    }

    private static void createWorkspace(FilePath workspace) {
        try {
            workspace.act(new TargetFileCallable("create"));
        } catch (IOException | InterruptedException e) {
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

    private SVNPropertyData checkoutDir(SVNURL svnPath, FilePath workingcopy) throws SVNException, IOException, InterruptedException {
        final SVNPropertyData[] data = {null};
        workingcopy.act(new MasterToSlaveFileCallable<Void>() {
            @Override
            public Void invoke(File file, VirtualChannel virtualChannel) throws IOException, InterruptedException {
                try {
                    manager.getUpdateClient().doCheckout(svnPath, file, SVNRevision.HEAD, SVNRevision.HEAD, SVNDepth.INFINITY, true);
                    data[0] = manager.getWCClient().doGetProperty(svnPath, null, SVNRevision.HEAD, SVNRevision.HEAD);
                } catch (SVNException e) {
                    e.printStackTrace();
                }
                return null;
            }
        });
        return data[0];
    }

    private void addDir(FilePath workingcopy) throws IOException, InterruptedException {
        workingcopy.act(new MasterToSlaveFileCallable<Void>() {
            @Override
            public Void invoke(File file, VirtualChannel virtualChannel) throws IOException, InterruptedException {
                try {
                    if (!file.exists() && !file.mkdirs()) {
                        throw new IOException("create workingcopy failed");
                    }
                    manager.getWCClient().doAdd(file, false, true, false, SVNDepth.INFINITY, false, false, true);
                } catch (SVNException e) {
                    e.printStackTrace();
                }
                return null;
            }
        });
    }

    public List<File> createWorkingCopy(List<ImportItem> item, EnvVars envVars) throws SVNPublisherException {
        List<File> files = Lists.newArrayList();
        try {
            cleanWorkspace(new FilePath(workingCopy, "tags"));
            SVNURL svnPath = repository.getLocation();
            createWorkspace(new FilePath(workingCopy, "test"));
            if (workingCopy != null) {
                workingCopy.act(new CheckoutTask(manager, svnPath));
            }
//            checkoutDir(svnPath, workingCopy);

            for (ImportItem i : item) {
                SVNURL svnDestination = svnPath.appendPath(i.getPath(), true);
                SVNNodeKind pathType = repository.checkPath(getRelativePath(svnDestination, repository), repository.getLatestRevision());
                FilePath dir = new FilePath(workingCopy, i.getPath());
                if (pathType == SVNNodeKind.NONE) {
                    dir.act(new AddTask(manager));
                } else if (pathType == SVNNodeKind.DIR) {
                    dir.act(new CheckoutTask(manager, svnDestination));
                }
                FilePath localPath = new FilePath(new File(baseLocalDir, i.getLocalPath()));
                files.addAll(localPath.act(new CopyFilesTask(manager, i, envVars, strategy, dir)));
            }
        } catch (SVNException e) {
            throw new SVNPublisherException("Error in repository " + e.getMessage());
        } catch (Exception ex1) {
            throw new SVNPublisherException(ex1);
        }
        return files;
    }

    public void commit() throws IOException, InterruptedException {
        workingCopy.act(new CommitTask(manager, commitMessage));
    }

    public void dispose() {
        cleanWorkspace(workingCopy);
        manager.dispose();
    }


    public static class Builder {
        private String url;
        private FilePath workingCopy;
        private String baseLocalDir;
        private Credentials credentials;
        private String strategy = "always";

        public Builder svnUrl(String svnUrl) {
            this.url = svnUrl;
            return this;
        }

        public Builder workingCopy(FilePath workingCopy) {
            this.workingCopy = workingCopy;
            return this;
        }

        public Builder baseLocalDir(String baseLocalDir) {
            this.baseLocalDir = baseLocalDir;
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
            return new SVNWorker(url, workingCopy, baseLocalDir, credentials, strategy);
        }
    }

    private static class CheckoutTask extends MasterToSlaveFileCallable<SVNPropertyData> {
        private transient SVNURL svnPath;
        private transient SVNClientManager manager;

        CheckoutTask(SVNClientManager manager, SVNURL svnPath) {
            this.manager = manager;
            this.svnPath = svnPath;
        }

        @Override
        public SVNPropertyData invoke(File file, VirtualChannel virtualChannel) throws IOException, InterruptedException {
            File workingCopy = new File(file, Constants.PLUGIN_NAME);
            if (!workingCopy.exists()) {
                workingCopy.mkdirs();
            }
            try {
                manager.getUpdateClient().doCheckout(svnPath, workingCopy, SVNRevision.HEAD, SVNRevision.HEAD, SVNDepth.INFINITY, true);
                return manager.getWCClient().doGetProperty(svnPath, null, SVNRevision.HEAD, SVNRevision.HEAD);
            } catch (SVNException e) {
                LOGGER.info(e.getMessage());
                e.printStackTrace();
            }
            return null;
        }

        @Override
        public void checkRoles(RoleChecker roleChecker) throws SecurityException {
            roleChecker.check(this, Roles.SLAVE);
        }
    }

    private static class AddTask implements FilePath.FileCallable<Boolean>, Serializable {
        private transient SVNClientManager manager;

        AddTask(SVNClientManager manager) {
            this.manager = manager;
        }

        @Override
        public Boolean invoke(File file, VirtualChannel virtualChannel) throws IOException, InterruptedException {
            if (!file.exists() && !file.mkdirs()) {
                throw new IOException("mkdir file failed: " + file.getName());
            }
            try {
                manager.getWCClient().doAdd(file, false, true, false, SVNDepth.INFINITY, false, false, true);
            } catch (SVNException e) {
                LOGGER.info(e.getMessage());
                e.printStackTrace();
                return false;
            }
            return true;
        }

        @Override
        public void checkRoles(RoleChecker roleChecker) throws SecurityException {
            roleChecker.check(this, Roles.SLAVE);
        }
    }

    private static class CopyFilesTask implements FilePath.FileCallable<List<File>>, Serializable {
        private transient SVNClientManager manager;
        private ImportItem item;
        private transient EnvVars envVars;
        private String strategy;
        private FilePath workingCopy;

        CopyFilesTask(SVNClientManager manager, ImportItem item, EnvVars vars, String strategy, FilePath workingCopy) {
            this.manager = manager;
            this.item = item;
            this.envVars = vars;
            this.strategy = strategy;
            this.workingCopy = workingCopy;
        }

        @Override
        public List<File> invoke(File file, VirtualChannel virtualChannel) throws IOException, InterruptedException {
            List<File> files = new ArrayList<>();
            String[] params = item.getParams().split(",");
            //  empty params equals always commit
            if (Constants.ALWAYS_COMMIT.equalsIgnoreCase(this.strategy)) {
                params = new String[]{""};
            }
            try {
                List<File> filesToCopy = Utils.findFilesWithPattern(new FilePath(file), item.getPattern(), params, envVars);
                for (File f : filesToCopy) {
                    File wc = new File(workingCopy.getRemote(), f.getName());
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

        @Override
        public void checkRoles(RoleChecker roleChecker) throws SecurityException {
            roleChecker.check(this, Roles.SLAVE);
        }
    }


    private static class CommitTask implements FilePath.FileCallable<Boolean>, Serializable {
        private transient SVNClientManager manager;
        private String commitMessage;

        CommitTask(SVNClientManager manager, String commitMsg) {
            this.manager = manager;
            this.commitMessage = commitMsg;
        }

        @Override
        public Boolean invoke(File file, VirtualChannel virtualChannel) throws IOException, InterruptedException {
            try {
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
