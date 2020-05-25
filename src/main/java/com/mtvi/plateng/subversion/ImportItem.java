package com.mtvi.plateng.subversion;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.Serializable;


/**
 * A class used to hold information as to how a file or folder (or set of
 * files/folder) is to be recognized, where the matching files/folders are to be
 * placed in the project repository, and what name should be given to the
 * files/folders.
 *
 * @author bsmith
 *
 */
public class ImportItem extends AbstractDescribableImpl<ImportItem> implements Serializable {

    /**
     * The pattern used to find files/folders covered by this item.
     */
    private String pattern;
    /**
     * The path within the repository's project root where the items are to be
     * placed.
     */
    private String path;
    /**
     * The relative project local path of the directory where the files will be searched.
     */
    private String localPath;
    /**
     * The params used to trigger uploading. "," (comma) is the only supported separator.
     */
    private String params;
    


//    /**
//     * @param pattern	The pattern to be used to find matching files/folders.
//     * @param path	The path within the project repository where matched items
//     * are to be placed.
//     * @param localPath	The local path, relative to base path where the files will be searched
//     * @param name	The name given to items when they are placed in the
//     * repository.
//     */
//    @DataBoundConstructor
//    public ImportItem(String pattern, String path, String localPath, String name) {
//        this.pattern = pattern;
//        this.path = path;
//        this.name = name;
//        this.localPath = localPath;
//    }


    /**
     * @param pattern	The pattern to be used to find matching files/folders.
     * @param path	The path within the project repository where matched items
     * are to be placed.
     * @param localPath	The local path, relative to base path where the files will be searched
     * @param name	The name given to items when they are placed in the
     * repository.
     */
    @DataBoundConstructor
    public ImportItem(String pattern, String path, String localPath, String name, String params) {
        this.pattern = pattern;
        this.path = path;
        this.localPath = localPath;
        this.params = params;
    }



    /**
     * Use this constructor to clone another ImportItem
     *
     * @param a the import item to clone
     */
    ImportItem(ImportItem a) {
        this.pattern = a.getPattern();
        this.path = a.getPath();
        this.localPath = a.getLocalPath();
        this.params = a.getParams();
    }

      /**
     * Return the pattern used to find files/folders covered by this item.
     *
     * @return the pattern used to find files/folders covered by this item.
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * Return the path within the repository's project root where the items are
     * to be placed.
     *
     * @return the path within the repository's project root where the items are
     * to be placed.
     */
    public String getPath() {
        return path;
    }

    /**
     * Set the pattern used to find files/folders covered by this item.
     *
     * @param pattern the pattern used to find files/folders covered by this
     * item.
     */
    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    /**
     * Set the path within the repository's project root where the items are to
     * be placed.
     *
     * @param path the path within the repository's project root where the items
     * are to be placed.
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Set the local path relative project  where the items should be searched
     *
     * @return 
     */
    public String getLocalPath() {
        return localPath;
    }

    /**
     * Get the local path relative project  where the items should be searched
     *
     * @param localPath The local path of the root directory where the files
     * will be searched.
     */
    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    /**
     * Set the params used to trigger uploading
     * @param params
     */
    public void setParams(String params) {
        this.params = params;
    }

    /**
     * Get the params used to trigger uploading
     * @return the trigger params, "," (comma) is the only supported separator.
     */
    public String getParams() {
        return params;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ImportItem> {
        public String getDisplayName() { return "Artifacts"; }
    }
    
}
