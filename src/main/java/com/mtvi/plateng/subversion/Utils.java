package com.mtvi.plateng.subversion;

import hudson.EnvVars;
import hudson.FilePath;
import org.springframework.util.StringUtils;

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
 * @author sekfung
 */
public class Utils {
    private static final Logger LOGGER = Logger.getLogger(Utils.class.getName());

    public static String replaceVars(EnvVars vars, String original) {
        String replaced = original;
        if (Pattern.matches("\\$\\{.+}", original)) {
            for (Map.Entry<String, String> k : vars.entrySet()) {
                Pattern p = Pattern.compile("\\$\\{" + k.getKey() + "}");
                Matcher m = p.matcher(replaced);
                if (m.find()) {
                    replaced = m.replaceAll(vars.get(k.getKey()).trim());
                }
            }
        }

        return replaced;
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

                        invokeSet.invoke(a, Utils.replaceVars(vars, (String) invokeGet.invoke(a)));

                    } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NullPointerException ex) {
                        LOGGER.log(Level.FINEST, "{0} {1}", new Object[]{f.getName(), ex.getMessage()});
                    }
                }
            }
        }
        return originalArtifacts;
    }

    public static List<File> findFilesWithPattern(String path, String filePattern, String[] params, EnvVars envVars) throws SVNPublisherException {
        try {
            File baseDir = new File(path);
            FilePath filePath = new FilePath(baseDir);
            if (!filePath.exists()) {
                throw new SVNPublisherException("Path does not exists : " + path);
            }
            boolean canUpload = true;
            for (String variable : params) {
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

}
