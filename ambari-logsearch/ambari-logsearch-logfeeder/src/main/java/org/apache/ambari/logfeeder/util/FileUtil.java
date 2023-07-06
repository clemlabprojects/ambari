/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ambari.logfeeder.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tools.ant.DirectoryScanner;

public class FileUtil {
  private static final Logger logger = LogManager.getLogger(FileUtil.class);
  private static final Logger fileMonitorLogger = LogManager.getLogger("logfeeder.file.monitor");

  private static final String FOLDER_SEPARATOR = "/";

  private FileUtil() {
    throw new UnsupportedOperationException();
  }

  public static Object getFileKey(File file) {
    try {
      Path fileFullPath = Paths.get(file.getAbsolutePath());
      if (fileFullPath != null) {
        BasicFileAttributes basicAttr = Files.readAttributes(fileFullPath, BasicFileAttributes.class);
        return basicAttr.fileKey();
      }
    } catch (Throwable ex) {
      logger.error("Error getting file attributes for file=" + file, ex);
    }
    return file.toString();
  }

  public static File[] getInputFilesByPattern(String searchPath) {
    File searchFile = new File(searchPath);
    if (searchFile.isFile()) {
      return new File[]{searchFile};
    } else {
      if (searchPath.contains("*")) {
        try {
          String folderBeforeRegex = getLogDirNameBeforeWildCard(searchPath);
          String fileNameAfterLastFolder = searchPath.substring(folderBeforeRegex.length());

          DirectoryScanner scanner = new DirectoryScanner();
          scanner.setIncludes(new String[]{fileNameAfterLastFolder});
          scanner.setBasedir(folderBeforeRegex);
          scanner.setCaseSensitive(true);
          scanner.scan();
          String[] fileNames = scanner.getIncludedFiles();

          if (fileNames != null && fileNames.length > 0) {
            File[] files = new File[fileNames.length];
            for (int i = 0; i < fileNames.length; i++) {
              files[i] = new File(folderBeforeRegex + fileNames[i]);
            }
            return files;
          }
        } catch (Exception e) {
          fileMonitorLogger.info("Input file was not found by pattern (exception thrown); {}, message: {}", searchPath, e.getMessage());
        }

      } else {
        fileMonitorLogger.debug("Input file config was not found by pattern; {}", searchPath);
      }
      return new File[]{};
    }
  }

  public static Map<String, List<File>> getFoldersForFiles(File[] inputFiles) {
    Map<String, List<File>> foldersMap = new HashMap<>();
    if (inputFiles != null && inputFiles.length > 0) {
      for (File inputFile : inputFiles) {
        File folder = inputFile.getParentFile();
        if (folder.exists()) {
          if (foldersMap.containsKey(folder.getAbsolutePath())) {
            foldersMap.get(folder.getAbsolutePath()).add(inputFile);
          } else {
            List<File> fileList = new ArrayList<>();
            fileList.add(inputFile);
            foldersMap.put(folder.getAbsolutePath(), fileList);
          }
        }
      }
    }
    if (!foldersMap.isEmpty()) {
      for (Map.Entry<String, List<File>> entry : foldersMap.entrySet()) {
        Collections.sort(entry.getValue(), Collections.reverseOrder());
      }
    }
    return foldersMap;
  }

  private static String getLogDirNameBeforeWildCard(String pattern) {
    String[] splitByFirstRegex = pattern.split("\\*");
    String beforeRegex = splitByFirstRegex[0];
    if (beforeRegex.contains(FOLDER_SEPARATOR)) {
      int endIndex = beforeRegex.lastIndexOf(FOLDER_SEPARATOR);
      String parentFolder = beforeRegex;
      if (endIndex != -1) {
        parentFolder = beforeRegex.substring(0, endIndex) + FOLDER_SEPARATOR;
      }
      return parentFolder;
    } else {
      return beforeRegex;
    }
  }

  public static void move(File source, File target) throws IOException {
    Path sourcePath = Paths.get(source.getAbsolutePath());
    Path targetPath = Paths.get(target.getAbsolutePath());
    Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  public static boolean isFileTooOld(File file, long diffMin) {
    return (System.currentTimeMillis() - file.lastModified()) > diffMin * 1000 * 60;
  }
}