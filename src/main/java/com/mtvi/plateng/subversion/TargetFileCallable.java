package com.mtvi.plateng.subversion;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.remoting.RoleChecker;

import java.io.File;
import java.io.IOException;

public class TargetFileCallable extends MasterToSlaveFileCallable<Boolean> {

    private static final long serialVersionUID = 1;
    private String method;

    public TargetFileCallable(String method) {
        this.method = method;
    }

    @Override
    public Boolean invoke(File ws, VirtualChannel virtualChannel)  {
        boolean result = false;
        switch (this.method) {
            case "create":
                result = createFile(ws);
                break;
            case "delete":
                result = deleteFile(ws);
                break;
            default:
                throw new IllegalArgumentException("unknown file operation");
        }
        return result;
    }


    private boolean createFile(File ws) {
        if (!ws.exists()) {
            return ws.mkdirs();
        }
        return true;
    }

    private boolean deleteFile(File file) {
        if (file.exists()) {
            try {
                FileUtils.deleteDirectory(file);
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }
}
