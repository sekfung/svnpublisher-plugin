package com.mtvi.plateng.subversion;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import org.jenkinsci.remoting.RoleChecker;

import java.io.File;
import java.io.IOException;

public class TargetFileCallable implements FilePath.FileCallable<Boolean> {

    private static final long serialVersionUID = 1;
    private final TaskListener listener;
    private final EnvVars environment;
    private final String resolvedFolderPath;

    public TargetFileCallable(TaskListener listener, EnvVars environment, String resolvedFolderPath) {
        this.listener = listener;
        this.environment = environment;
        this.resolvedFolderPath = resolvedFolderPath;
    }

    @Override
    public Boolean invoke(File ws, VirtualChannel virtualChannel)  {
        boolean result;
        try {
            FilePath fpWs = new FilePath(ws);
            FilePath fpTL = new FilePath(fpWs, resolvedFolderPath);
            listener.getLogger().println("Creating folder: " + fpTL.getRemote()+ fpTL.getBaseName());
            if (!fpTL.exists()) {
                fpTL.mkdirs();
            }
            result = true;
        } catch (IOException | InterruptedException e) {
            listener.fatalError(e.getMessage());
            result = false;
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public void checkRoles(RoleChecker roleChecker) throws SecurityException {

    }
}
