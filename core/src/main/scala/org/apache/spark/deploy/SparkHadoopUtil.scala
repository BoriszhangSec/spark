/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy

import java.io.IOException
import java.lang.reflect.Method
import java.security.PrivilegedExceptionAction
import java.text.DateFormat
import java.util.{Arrays, Comparator, Date}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.google.common.primitives.Longs
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path, PathFilter}
import org.apache.hadoop.fs.FileSystem.Statistics
import org.apache.hadoop.mapred.{JobConf, Master}
import org.apache.hadoop.security.{Credentials, UserGroupInformation}
import org.apache.hadoop.security.token.{Token, TokenIdentifier}
import org.apache.hadoop.security.token.delegation.AbstractDelegationTokenIdentifier

import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils

/**
 * :: DeveloperApi ::
 * Contains util methods to interact with Hadoop from Spark.
 */
@DeveloperApi
class SparkHadoopUtil extends Logging {
  private val sparkConf = new SparkConf(false).loadFromSystemProperties(true)
  val conf: Configuration = newConfiguration(sparkConf)
  UserGroupInformation.setConfiguration(conf)

  /**
   * Runs the given function with a Hadoop UserGroupInformation as a thread local variable
   * (distributed to child threads), used for authenticating HDFS and YARN calls.
   *
   * IMPORTANT NOTE: If this function is going to be called repeated in the same process
   * you need to look https://issues.apache.org/jira/browse/HDFS-3545 and possibly
   * do a FileSystem.closeAllForUGI in order to avoid leaking Filesystems
   */
  def runAsSparkUser(func: () => Unit) {
    val user = Utils.getCurrentUserName()
    logDebug("running as user: " + user)
    val ugi = UserGroupInformation.createRemoteUser(user)
    transferCredentials(UserGroupInformation.getCurrentUser(), ugi)
    ugi.doAs(new PrivilegedExceptionAction[Unit] {
      def run: Unit = func()
    })
  }

  def transferCredentials(source: UserGroupInformation, dest: UserGroupInformation) {
    for (token <- source.getTokens.asScala) {
      dest.addToken(token)
    }
  }


  /**
   * Appends S3-specific, spark.hadoop.*, and spark.buffer.size configurations to a Hadoop
   * configuration.
   */
  def appendS3AndSparkHadoopConfigurations(conf: SparkConf, hadoopConf: Configuration): Unit = {
    // Note: this null check is around more than just access to the "conf" object to maintain
    // the behavior of the old implementation of this code, for backwards compatibility.
    if (conf != null) {
      // Explicitly check for S3 environment variables
      if (System.getenv("AWS_ACCESS_KEY_ID") != null &&
          System.getenv("AWS_SECRET_ACCESS_KEY") != null) {
        val keyId = System.getenv("AWS_ACCESS_KEY_ID")
        val accessKey = System.getenv("AWS_SECRET_ACCESS_KEY")

        hadoopConf.set("fs.s3.awsAccessKeyId", keyId)
        hadoopConf.set("fs.s3n.awsAccessKeyId", keyId)
        hadoopConf.set("fs.s3a.access.key", keyId)
        hadoopConf.set("fs.s3.awsSecretAccessKey", accessKey)
        hadoopConf.set("fs.s3n.awsSecretAccessKey", accessKey)
        hadoopConf.set("fs.s3a.secret.key", accessKey)
      }
      // Copy any "spark.hadoop.foo=bar" system properties into conf as "foo=bar"
      conf.getAll.foreach { case (key, value) =>
        if (key.startsWith("spark.hadoop.")) {
          hadoopConf.set(key.substring("spark.hadoop.".length), value)
        }
      }
      val bufferSize = conf.get("spark.buffer.size", "65536")
      hadoopConf.set("io.file.buffer.size", bufferSize)
    }
  }

  /**
   * Return an appropriate (subclass) of Configuration. Creating config can initializes some Hadoop
   * subsystems.
   */
  def newConfiguration(conf: SparkConf): Configuration = {
    val hadoopConf = new Configuration()
    appendS3AndSparkHadoopConfigurations(conf, hadoopConf)
    hadoopConf
  }

  /**
   * Add any user credentials to the job conf which are necessary for running on a secure Hadoop
   * cluster.
   */
  def addCredentials(conf: JobConf) {}

  def isYarnMode(): Boolean = { false }

  def getCurrentUserCredentials(): Credentials = { null }

  def addCurrentUserCredentials(creds: Credentials) {}

  def addSecretKeyToUserCredentials(key: String, secret: String) {}

  def getSecretKeyFromUserCredentials(key: String): Array[Byte] = { null }

  def loginUserFromKeytab(principalName: String, keytabFilename: String) {
    UserGroupInformation.loginUserFromKeytab(principalName, keytabFilename)
  }

  def getTokenRenewer(conf: Configuration): String = {
    val delegTokenRenewer = Master.getMasterPrincipal(conf)
    logDebug("delegation token renewer is: " + delegTokenRenewer)
    if (delegTokenRenewer == null || delegTokenRenewer.length() == 0) {
      val errorMessage = "Can't get Master Kerberos principal for use as renewer"
      logError(errorMessage)
      throw new SparkException(errorMessage)
    }
    delegTokenRenewer
  }

  /**
   * Obtains tokens for the namenodes passed in and adds them to the credentials.
   */
  def obtainTokensForNamenodes(
      paths: Set[Path],
      conf: Configuration,
      creds: Credentials,
      renewer: Option[String] = None
  ): Unit = {
    if (UserGroupInformation.isSecurityEnabled()) {
      val delegTokenRenewer = renewer.getOrElse(getTokenRenewer(conf))
      paths.foreach { dst =>
        val dstFs = dst.getFileSystem(conf)
        logInfo("getting token for namenode: " + dst)
        dstFs.addDelegationTokens(delegTokenRenewer, creds)
      }
    }
  }

  /**
   * Returns a function that can be called to find Hadoop FileSystem bytes read. If
   * getFSBytesReadOnThreadCallback is called from thread r at time t, the returned callback will
   * return the bytes read on r since t.  Reflection is required because thread-level FileSystem
   * statistics are only available as of Hadoop 2.5 (see HADOOP-10688).
   * Returns None if the required method can't be found.
   */
  private[spark] def getFSBytesReadOnThreadCallback(): Option[() => Long] = {
    try {
      val threadStats = getFileSystemThreadStatistics()
      val getBytesReadMethod = getFileSystemThreadStatisticsMethod("getBytesRead")
      val f = () => threadStats.map(getBytesReadMethod.invoke(_).asInstanceOf[Long]).sum
      val baselineBytesRead = f()
      Some(() => f() - baselineBytesRead)
    } catch {
      case e @ (_: NoSuchMethodException | _: ClassNotFoundException) =>
        logDebug("Couldn't find method for retrieving thread-level FileSystem input data", e)
        None
    }
  }

  /**
   * Returns a function that can be called to find Hadoop FileSystem bytes written. If
   * getFSBytesWrittenOnThreadCallback is called from thread r at time t, the returned callback will
   * return the bytes written on r since t.  Reflection is required because thread-level FileSystem
   * statistics are only available as of Hadoop 2.5 (see HADOOP-10688).
   * Returns None if the required method can't be found.
   */
  private[spark] def getFSBytesWrittenOnThreadCallback(): Option[() => Long] = {
    try {
      val threadStats = getFileSystemThreadStatistics()
      val getBytesWrittenMethod = getFileSystemThreadStatisticsMethod("getBytesWritten")
      val f = () => threadStats.map(getBytesWrittenMethod.invoke(_).asInstanceOf[Long]).sum
      val baselineBytesWritten = f()
      Some(() => f() - baselineBytesWritten)
    } catch {
      case e @ (_: NoSuchMethodException | _: ClassNotFoundException) =>
        logDebug("Couldn't find method for retrieving thread-level FileSystem output data", e)
        None
    }
  }

  private def getFileSystemThreadStatistics(): Seq[AnyRef] = {
    FileSystem.getAllStatistics.asScala.map(
      Utils.invoke(classOf[Statistics], _, "getThreadStatistics"))
  }

  private def getFileSystemThreadStatisticsMethod(methodName: String): Method = {
    val statisticsDataClass =
      Utils.classForName("org.apache.hadoop.fs.FileSystem$Statistics$StatisticsData")
    statisticsDataClass.getDeclaredMethod(methodName)
  }

  /**
   * Get [[FileStatus]] objects for all leaf children (files) under the given base path. If the
   * given path points to a file, return a single-element collection containing [[FileStatus]] of
   * that file.
   */
  def listLeafStatuses(fs: FileSystem, basePath: Path): Seq[FileStatus] = {
    listLeafStatuses(fs, fs.getFileStatus(basePath))
  }

  /**
   * Get [[FileStatus]] objects for all leaf children (files) under the given base path. If the
   * given path points to a file, return a single-element collection containing [[FileStatus]] of
   * that file.
   */
  def listLeafStatuses(fs: FileSystem, baseStatus: FileStatus): Seq[FileStatus] = {
    def recurse(status: FileStatus): Seq[FileStatus] = {
      val (directories, leaves) = fs.listStatus(status.getPath).partition(_.isDirectory)
      leaves ++ directories.flatMap(f => listLeafStatuses(fs, f))
    }

    if (baseStatus.isDirectory) recurse(baseStatus) else Seq(baseStatus)
  }

  def listLeafDirStatuses(fs: FileSystem, basePath: Path): Seq[FileStatus] = {
    listLeafDirStatuses(fs, fs.getFileStatus(basePath))
  }

  def listLeafDirStatuses(fs: FileSystem, baseStatus: FileStatus): Seq[FileStatus] = {
    def recurse(status: FileStatus): Seq[FileStatus] = {
      val (directories, files) = fs.listStatus(status.getPath).partition(_.isDirectory)
      val leaves = if (directories.isEmpty) Seq(status) else Seq.empty[FileStatus]
      leaves ++ directories.flatMap(dir => listLeafDirStatuses(fs, dir))
    }

    assert(baseStatus.isDirectory)
    recurse(baseStatus)
  }

  def isGlobPath(pattern: Path): Boolean = {
    pattern.toString.exists("{}[]*?\\".toSet.contains)
  }

  def globPath(pattern: Path): Seq[Path] = {
    val fs = pattern.getFileSystem(conf)
    Option(fs.globStatus(pattern)).map { statuses =>
      statuses.map(_.getPath.makeQualified(fs.getUri, fs.getWorkingDirectory)).toSeq
    }.getOrElse(Seq.empty[Path])
  }

  def globPathIfNecessary(pattern: Path): Seq[Path] = {
    if (isGlobPath(pattern)) globPath(pattern) else Seq(pattern)
  }

  /**
   * Lists all the files in a directory with the specified prefix, and does not end with the
   * given suffix. The returned {{FileStatus}} instances are sorted by the modification times of
   * the respective files.
   */
  def listFilesSorted(
      remoteFs: FileSystem,
      dir: Path,
      prefix: String,
      exclusionSuffix: String): Array[FileStatus] = {
    try {
      val fileStatuses = remoteFs.listStatus(dir,
        new PathFilter {
          override def accept(path: Path): Boolean = {
            val name = path.getName
            name.startsWith(prefix) && !name.endsWith(exclusionSuffix)
          }
        })
      Arrays.sort(fileStatuses, new Comparator[FileStatus] {
        override def compare(o1: FileStatus, o2: FileStatus): Int = {
          Longs.compare(o1.getModificationTime, o2.getModificationTime)
        }
      })
      fileStatuses
    } catch {
      case NonFatal(e) =>
        logWarning("Error while attempting to list files from application staging dir", e)
        Array.empty
    }
  }

  private[spark] def getSuffixForCredentialsPath(credentialsPath: Path): Int = {
    val fileName = credentialsPath.getName
    fileName.substring(
      fileName.lastIndexOf(SparkHadoopUtil.SPARK_YARN_CREDS_COUNTER_DELIM) + 1).toInt
  }


  private val HADOOP_CONF_PATTERN = "(\\$\\{hadoopconf-[^\\}\\$\\s]+\\})".r.unanchored

  /**
   * Substitute variables by looking them up in Hadoop configs. Only variables that match the
   * ${hadoopconf- .. } pattern are substituted.
   */
  def substituteHadoopVariables(text: String, hadoopConf: Configuration): String = {
    text match {
      case HADOOP_CONF_PATTERN(matched) =>
        logDebug(text + " matched " + HADOOP_CONF_PATTERN)
        val key = matched.substring(13, matched.length() - 1) // remove ${hadoopconf- .. }
        val eval = Option[String](hadoopConf.get(key))
          .map { value =>
            logDebug("Substituted " + matched + " with " + value)
            text.replace(matched, value)
          }
        if (eval.isEmpty) {
          // The variable was not found in Hadoop configs, so return text as is.
          text
        } else {
          // Continue to substitute more variables.
          substituteHadoopVariables(eval.get, hadoopConf)
        }
      case _ =>
        logDebug(text + " didn't match " + HADOOP_CONF_PATTERN)
        text
    }
  }

  /**
   * Start a thread to periodically update the current user's credentials with new credentials so
   * that access to secured service does not fail.
   */
  private[spark] def startCredentialUpdater(conf: SparkConf) {}

  /**
   * Stop the thread that does the credential updates.
   */
  private[spark] def stopCredentialUpdater() {}

  /**
   * Return a fresh Hadoop configuration, bypassing the HDFS cache mechanism.
   * This is to prevent the DFSClient from using an old cached token to connect to the NameNode.
   */
  private[spark] def getConfBypassingFSCache(
      hadoopConf: Configuration,
      scheme: String): Configuration = {
    val newConf = new Configuration(hadoopConf)
    val confKey = s"fs.${scheme}.impl.disable.cache"
    newConf.setBoolean(confKey, true)
    newConf
  }

  /**
   * Dump the credentials' tokens to string values.
   *
   * @param credentials credentials
   * @return an iterator over the string values. If no credentials are passed in: an empty list
   */
  private[spark] def dumpTokens(credentials: Credentials): Iterable[String] = {
    if (credentials != null) {
      credentials.getAllTokens.asScala.map(tokenToString)
    } else {
      Seq()
    }
  }

  /**
   * Convert a token to a string for logging.
   * If its an abstract delegation token, attempt to unmarshall it and then
   * print more details, including timestamps in human-readable form.
   *
   * @param token token to convert to a string
   * @return a printable string value.
   */
  private[spark] def tokenToString(token: Token[_ <: TokenIdentifier]): String = {
    val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    val buffer = new StringBuilder(128)
    buffer.append(token.toString)
    try {
      val ti = token.decodeIdentifier
      buffer.append("; ").append(ti)
      ti match {
        case dt: AbstractDelegationTokenIdentifier =>
          // include human times and the renewer, which the HDFS tokens toString omits
          buffer.append("; Renewer: ").append(dt.getRenewer)
          buffer.append("; Issued: ").append(df.format(new Date(dt.getIssueDate)))
          buffer.append("; Max Date: ").append(df.format(new Date(dt.getMaxDate)))
        case _ =>
      }
    } catch {
      case e: IOException =>
        logDebug("Failed to decode $token: $e", e)
    }
    buffer.toString
  }
}

object SparkHadoopUtil {

  private lazy val hadoop = new SparkHadoopUtil
  private lazy val yarn = try {
    Utils.classForName("org.apache.spark.deploy.yarn.YarnSparkHadoopUtil")
      .newInstance()
      .asInstanceOf[SparkHadoopUtil]
  } catch {
    case e: Exception => throw new SparkException("Unable to load YARN support", e)
  }

  val SPARK_YARN_CREDS_TEMP_EXTENSION = ".tmp"

  val SPARK_YARN_CREDS_COUNTER_DELIM = "-"

  /**
   * Number of records to update input metrics when reading from HadoopRDDs.
   *
   * Each update is potentially expensive because we need to use reflection to access the
   * Hadoop FileSystem API of interest (only available in 2.5), so we should do this sparingly.
   */
  private[spark] val UPDATE_INPUT_METRICS_INTERVAL_RECORDS = 1000

  def get: SparkHadoopUtil = {
    // Check each time to support changing to/from YARN
    val yarnMode = java.lang.Boolean.parseBoolean(
        System.getProperty("SPARK_YARN_MODE", System.getenv("SPARK_YARN_MODE")))
    if (yarnMode) {
      yarn
    } else {
      hadoop
    }
  }
}
