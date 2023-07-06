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
package org.apache.ambari.logfeeder.input.file.checkpoint.util;

import org.apache.ambari.logfeeder.input.InputFile;
import org.apache.ambari.logfeeder.util.LogFeederUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.EOFException;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class to get last processed line number from a checkpoint file.
 */
public class ResumeLineNumberHelper {

  private static final Logger logger = LogManager.getLogger(ResumeLineNumberHelper.class);

  private ResumeLineNumberHelper() {
  }

  /**
   * Get last processed line number from a checkpoint file for an input
   * @param inputFile input file object
   * @param checkPointFolder checkpoint folder that contains
   * @return last processed line number of an input file
   */
  public static int getResumeFromLineNumber(InputFile inputFile, File checkPointFolder) {
    int resumeFromLineNumber = 0;

    File checkPointFile = null;
    try {
      logger.info("Checking existing checkpoint file. " + inputFile.getShortDescription());

      String checkPointFileName = getCheckpointFileName(inputFile);
      checkPointFile = new File(checkPointFolder, checkPointFileName);
      inputFile.getCheckPointFiles().put(inputFile.getBase64FileKey(), checkPointFile);
      Map<String, Object> jsonCheckPoint = null;
      if (!checkPointFile.exists()) {
        logger.info("Checkpoint file for log file " + inputFile.getFilePath() + " doesn't exist, starting to read it from the beginning");
      } else {
        try (RandomAccessFile checkPointWriter = new RandomAccessFile(checkPointFile, "rw")) {
          int contentSize = checkPointWriter.readInt();
          byte b[] = new byte[contentSize];
          int readSize = checkPointWriter.read(b, 0, contentSize);
          if (readSize != contentSize) {
            logger.error("Couldn't read expected number of bytes from checkpoint file. expected=" + contentSize + ", read=" +
              readSize + ", checkPointFile=" + checkPointFile + ", input=" + inputFile.getShortDescription());
          } else {
            String jsonCheckPointStr = new String(b, 0, readSize);
            jsonCheckPoint = LogFeederUtil.toJSONObject(jsonCheckPointStr);

            resumeFromLineNumber = LogFeederUtil.objectToInt(jsonCheckPoint.get("line_number"), 0, "line_number");

            logger.info("CheckPoint. checkPointFile=" + checkPointFile + ", json=" + jsonCheckPointStr +
              ", resumeFromLineNumber=" + resumeFromLineNumber);
          }
        } catch (EOFException eofEx) {
          logger.info("EOFException. Will reset checkpoint file " + checkPointFile.getAbsolutePath() + " for " +
            inputFile.getShortDescription(), eofEx);
        }
      }
      if (jsonCheckPoint == null) {
        // This seems to be first time, so creating the initial checkPoint object
        jsonCheckPoint = new HashMap<String, Object>();
        jsonCheckPoint.put("file_path", inputFile.getFilePath());
        jsonCheckPoint.put("file_key", inputFile.getBase64FileKey());
      }

      inputFile.getJsonCheckPoints().put(inputFile.getBase64FileKey(), jsonCheckPoint);

    } catch (Throwable t) {
      logger.error("Error while configuring checkpoint file. Will reset file. checkPointFile=" + checkPointFile, t);
    }

    return resumeFromLineNumber;
  }

  private static String getCheckpointFileName(InputFile inputFile) {
    return String.format("%s-%s%s", inputFile.getLogType(),
      inputFile.getBase64FileKey(), inputFile.getCheckPointExtension());
  }

}
