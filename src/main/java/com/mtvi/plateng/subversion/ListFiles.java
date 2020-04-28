package com.mtvi.plateng.subversion;

import hudson.Util;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;
import org.apache.tools.ant.types.FileSet;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ListFiles extends MasterToSlaveFileCallable<Map<String, String>> {
    private static final long serialVersionUID = 1;
    private final String includes, excludes;
//        private final boolean defaultExcludes;
//        private final boolean caseSensitive;
//        private final boolean followSymlinks;

    ListFiles(String includes, String excludes) {
        this.includes = includes;
        this.excludes = excludes;
//            this.defaultExcludes = defaultExcludes;
//            this.caseSensitive = caseSensitive;
//            this.followSymlinks = followSymlinks;
    }

    @Override
    public Map<String, String> invoke(File basedir, VirtualChannel channel) throws IOException, InterruptedException {
        Map<String, String> r = new HashMap<>();

        FileSet fileSet = Util.createFileSet(basedir, includes, excludes);
//            fileSet.setDefaultexcludes(defaultExcludes);
//            fileSet.setCaseSensitive(caseSensitive);
//            fileSet.setFollowSymlinks(followSymlinks);

        for (String f : fileSet.getDirectoryScanner().getIncludedFiles()) {
            f = f.replace(File.separatorChar, '/');
            r.put(f, f);
        }
        return r;
    }
}
