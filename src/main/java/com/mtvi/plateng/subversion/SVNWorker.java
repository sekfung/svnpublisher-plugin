package com.mtvi.plateng.subversion;

import com.cloudbees.plugins.credentials.Credentials;
import com.google.common.collect.Lists;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.scm.CredentialsSVNAuthenticationProviderImpl;
import org.apache.commons.io.FileUtils;
import org.springframework.util.StringUtils;
import org.tmatesoft.svn.core.*;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

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
public class SVNWorker {

    private static final Logger LOGGER = Logger.getLogger(SVNWorker.class.getName());
    public final static String systemSeparator = System.getProperty("file.separator");

    private SVNClientManager manager;
    private SVNRepository repository;
    private String workingCopy;
    private String commitMessage = "";
    private String baseLocalDir;

    public SVNWorker(String url, String workingCopy, String baseLocalDir, Credentials credentials) {
        try {
            initRepo(SVNURL.parseURIDecoded(url), credentials);
            this.workingCopy = workingCopy;
            this.baseLocalDir = baseLocalDir;
        } catch (SVNException ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage());
        }
    }

    public SVNWorker(String url, Credentials credentials) {
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

    private static void cleanWorkspace(String workspace) {
        File f = new File(workspace);
        try {
            if (f.exists()) {
                FileUtils.deleteDirectory(f);
            }
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, ex.getMessage());
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

    /**
     * Replace the env vars and parameters of jenkins in
     *
     * @param <T>
     * @param vars
     * @param originalArtifacts
     * @return
     */
    public static <T> List<T> parseAndReplaceEnvVars(EnvVars vars, List<T> originalArtifacts) {

        for (T a : originalArtifacts) {
            Field[] fields;
            fields = a.getClass().getDeclaredFields();
            for (Field f : fields) {
                if (f.getType().isInstance("")) {
                    String capitalName = StringUtils.capitalize(f.getName());
                    try {
                        Method invokeSet = a.getClass().getDeclaredMethod("set" + capitalName, String.class);
                        Method invokeGet = a.getClass().getDeclaredMethod("get" + capitalName);

                        invokeSet.invoke(a, replaceVars(vars, (String) invokeGet.invoke(a)));

                    } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NullPointerException ex) {
                        LOGGER.log(Level.FINEST, "{0} {1}", new Object[]{f.getName(), ex.getMessage()});
                    }
                }
            }
        }
        return originalArtifacts;
    }

    public static String replaceVars(EnvVars vars, String original) {
        String replaced = original;
        if (Pattern.matches("\\$\\{.*}}", original)) {
            for (String k : vars.keySet()) {
                Pattern p = Pattern.compile("\\$\\{" + k + "}");

                Matcher m = p.matcher(replaced);
                if (m.find()) {
                    replaced = m.replaceAll(vars.get(k).trim());
                }
            }
        }

        return replaced;
    }

    private SVNPropertyData checkoutDir(SVNURL svnPath, File workingcopy) throws SVNException {
        manager.getUpdateClient().doCheckout(svnPath, workingcopy, SVNRevision.HEAD, SVNRevision.HEAD, SVNDepth.INFINITY, true);
        return manager.getWCClient().doGetProperty(svnPath, null, SVNRevision.HEAD, SVNRevision.HEAD);
    }

    private void addDir(File workingcopy) throws SVNException, SVNPublisherException {
        workingcopy.mkdirs();
        manager.getWCClient().doAdd(workingcopy, false, true, false, SVNDepth.INFINITY, false, false, true);
    }

    public List<File> createWorkingCopy(List<ImportItem> item, EnvVars envVars) throws SVNPublisherException {
        List<File> files = Lists.newArrayList();
        try {
            cleanWorkspace(workingCopy);
            SVNURL svnPath = repository.getLocation();
            File wc = new File(workingCopy);
            checkoutDir(svnPath, wc);

            for (ImportItem i : item) {
                SVNURL svnDestination = svnPath.appendPath(i.getPath(), false);
                SVNNodeKind pathType = repository.checkPath(getRelativePath(svnDestination, repository), repository.getLatestRevision());
                File dir = new File(workingCopy + SVNWorker.systemSeparator + i.getPath());

                if (pathType == SVNNodeKind.NONE) {
                    addDir(dir);
                } else if (pathType == SVNNodeKind.DIR) {
                    checkoutDir(svnDestination, dir);
                }
                String localPath = this.baseLocalDir + SVNWorker.systemSeparator + i.getLocalPath();
                String[] variables = i.getVariables().split(",");
                List<File> filesToCopy = findFilesWithPattern(localPath, i.getPattern(), variables, envVars);
                for (File f : filesToCopy) {
                    try {
                        if (filesToCopy.size() == 1) {
                            if (i.getName() == null || i.getName().equalsIgnoreCase("")) {
                                wc = new File(dir.getAbsolutePath() + SVNWorker.systemSeparator + f.getName());
                            } else {
                                wc = new File(dir.getAbsolutePath() + SVNWorker.systemSeparator + i.getName());
                            }
                        } else {
                            wc = new File(dir.getAbsolutePath() + SVNWorker.systemSeparator + f.getName());
                        }

                        boolean toAdd = !wc.exists();
                        FileUtils.copyFile(f, wc);
                        if (toAdd) {
                            manager.getWCClient().doAdd(wc, false, false, false, SVNDepth.FILES, false, false, false);
                        }
                        files.add(wc);
                    } catch (IOException ex) {
                        throw new SVNPublisherException("Cannot create working copy for file " + f.getAbsolutePath());
                    }
                }
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


    public static List<File> findFilesWithPattern(String path, String filePattern, String[] variables, EnvVars envVars) throws SVNPublisherException {
        try {
            File baseDir = new File(path);
            FilePath filePath = new FilePath(baseDir);
            if (!filePath.exists()) {
                throw new SVNPublisherException("Path does not exists : " + path);
            }
            boolean canUpload = true;
            for (String variable : variables) {
                if ("".equals(variable)) {
                    break;
                }
                if (!variable.contains("=") || variable.split("=").length != 2) {
                    canUpload = false;
                    break;
                } else {
                    String key = variable.split("=")[0];
                    String value = variable.split("=")[1];
                    if (value == null || !value.equals(envVars.get(key))) {
                        canUpload = false;
                        break;
                    }
                }

            }
            if (canUpload) {
                ListFiles listFiles = new ListFiles(filePattern, "");
                Map<String, String> files = filePath.act(listFiles);
                return files.values().stream().map(File::new).collect(Collectors.toList());
            }
            return new ArrayList<>();
        } catch (PatternSyntaxException e) {
            throw new SVNPublisherException("Invalid pattern file for " + filePattern);
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
            throw new SVNPublisherException("Invalid pattern file for " + e.getMessage());
        }
    }

    public void commit() throws SVNPublisherException {
        try {
            SVNCommitClient commit = manager.getCommitClient();
            SVNCommitPacket a = commit.doCollectCommitItems(new File[]{new File(workingCopy)}, false, true, SVNDepth.INFINITY, null);
            SVNCommitInfo info = commit.doCommit(a, false, commitMessage);
        } catch (SVNException e) {
            throw new SVNPublisherException("Cannot commit into repository " + e.getMessage());
        }
    }

    public void dispose() {
        cleanWorkspace(workingCopy);
        manager.dispose();
    }


}
