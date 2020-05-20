package com.mtvi.plateng.subversion;

import com.cloudbees.plugins.credentials.Credentials;
import com.google.common.collect.Lists;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import hudson.scm.CredentialsSVNAuthenticationProviderImpl;
import jenkins.MasterToSlaveFileCallable;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.*;

import java.io.*;
import java.nio.file.Paths;
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
public class SVNWorker implements Externalizable {
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

    private SVNWorker(String url, FilePath workingCopy, String baseLocalDir, Credentials credentials, String strategy) {
        try {
            initRepo(SVNURL.parseURIDecoded(url), credentials);
            this.workingCopy = workingCopy;
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

//    private static void createWorkspace(String workspace) {
//        File file = new File(workspace);
//        if (!file.exists()) {
//            boolean result = file.mkdir();
//        }
//    }

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
            cleanWorkspace(new FilePath(workingCopy.getParent(), "test"));
            SVNURL svnPath = repository.getLocation();
            checkoutDir(svnPath, workingCopy);

            for (ImportItem i : item) {
                SVNURL svnDestination = svnPath.appendPath(i.getPath(), true);
                SVNNodeKind pathType = repository.checkPath(getRelativePath(svnDestination, repository), repository.getLatestRevision());
                FilePath dir = new FilePath(workingCopy, i.getPath());
                if (pathType == SVNNodeKind.NONE) {
                    addDir(dir);
                } else if (pathType == SVNNodeKind.DIR) {
                    checkoutDir(svnDestination, dir);
                }
                String localPath = Paths.get(this.baseLocalDir, i.getLocalPath()).toString();
                String[] params = i.getParams().split(",");
                //  empty params equals always commit
                if (Constants.ALWAYS_COMMIT.equalsIgnoreCase(this.strategy)) {
                    params = new String[]{""};
                }
                List<File> filesToCopy = Utils.findFilesWithPattern(localPath, i.getPattern(), params, envVars);
//                for (File f : filesToCopy) {
//                    try {
//                        FilePath wc = new FilePath(dir, f.getName());
//                        boolean toAdd = !wc.exists();
//                        FileUtils.copyFile(new File(localPath, f.getName()), wc);
//                        if (toAdd) {
//                            manager.getWCClient().doAdd(wc, false, false, false, SVNDepth.INFINITY, false, false, false);
//                        }
//                        files.add(wc);
//                    } catch (IOException ex) {
//                        throw new SVNPublisherException("Cannot create working copy for file " + f.getAbsolutePath());
//                    }
//                }
            }
        } catch (SVNException e) {
            throw new SVNPublisherException("Error in repository " + e.getMessage());
        } catch (SVNPublisherException ex) {
            throw ex;
        } catch (Exception ex1) {
            throw new SVNPublisherException(ex1);
        }
        return files;
    }

//    public void commit() throws SVNPublisherException {
//        try {
//            SVNCommitClient commit = manager.getCommitClient();
//            SVNCommitPacket a = commit.doCollectCommitItems(new File[]{new File(workingCopy)}, false, true, SVNDepth.INFINITY, null);
//            commit.doCommit(a, false, commitMessage);
//        } catch (SVNException e) {
//            throw new SVNPublisherException("Cannot commit into repository " + e.getMessage());
//        }
//    }

    public void dispose() {
        cleanWorkspace(workingCopy);
        manager.dispose();
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {

    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {

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

}
