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

package org.apache.spark.resource

import java.util.{Map => JMap}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.spark.SparkConf
import org.apache.spark.annotation.Evolving
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.resource.ResourceUtils.{RESOURCE_DOT, RESOURCE_PREFIX}

/**
 * Resource profile to associate with an RDD. A ResourceProfile allows the user to
 * specify executor and task requirements for an RDD that will get applied during a
 * stage. This allows the user to change the resource requirements between stages.
 *
 * This class is private now for initial development, once we have the feature in place
 * this will become public.
 */
@Evolving
class ResourceProfile() extends Serializable {

  private val _id = ResourceProfile.getNextProfileId
  private val _taskResources = new mutable.HashMap[String, TaskResourceRequest]()
  private val _executorResources = new mutable.HashMap[String, ExecutorResourceRequest]()
  private var _limitingResource = ""

  def id: Int = _id
  def taskResources: Map[String, TaskResourceRequest] = _taskResources.toMap
  def executorResources: Map[String, ExecutorResourceRequest] = _executorResources.toMap
  // TODO - would be nice to get rid of SparkConf param
  def limitingResource(sparkConf: SparkConf): String = {
    if (_limitingResource.nonEmpty) {
      _limitingResource
    } else {
      _limitingResource = calculateLimitingResource(sparkConf)
      _limitingResource
    }
  }

  /**
   * (Java-specific) gets a Java Map of resources to TaskResourceRequest
   */
  def taskResourcesJMap: JMap[String, TaskResourceRequest] = _taskResources.asJava

  /**
   * (Java-specific) gets a Java Map of resources to ExecutorResourceRequest
   */
  def executorResourcesJMap: JMap[String, ExecutorResourceRequest] = _executorResources.asJava

  def reset(): Unit = {
    _taskResources.clear()
    _executorResources.clear()
  }

  def require(requests: ExecutorResourceRequests): this.type = {
    _executorResources ++= requests.requests
    this
  }

  def require(requests: TaskResourceRequests): this.type = {
    _taskResources ++= requests.requests
    this
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case that: ResourceProfile =>
        that.getClass == this.getClass && that._id == _id &&
          that._taskResources == _taskResources && that._executorResources == _executorResources
      case _ =>
        false
    }
  }

  override def hashCode(): Int = Seq(_taskResources, _executorResources).hashCode()

  override def toString(): String = {
    s"Profile: id = ${_id}, executor resources: ${_executorResources}, " +
      s"task resources: ${_taskResources}"
  }

  // utility functions to make it easier to access certain things
  def getExecutorCores: Option[Int] = {
    executorResources.get(ResourceProfile.CORES).map(_.amount.toInt)
  }

  def getTaskCpus: Option[Int] = {
    taskResources.get(ResourceProfile.CPUS).map(_.amount.toInt)
  }

  // figure out what is the current limiting resource based on executor and task resources
  // TODO - how expensive is this?
  // TODO - when do we call this? needs SparkConf or default cpus per task
  private def calculateLimitingResource(sparkConf: SparkConf): String = {
    var maxSlots = -1.0
    var limitingResource = ""
    executorResources.foreach { case (name, req) =>
      if (taskResources.contains(name)) {
        val execAmount = req.amount
        // this handles fractional resources
        val taskAmount = taskResources.get(name).get.amount
        val (amount, parts) = ResourceUtils.calculateAmountAndPartsForFraction(taskAmount)
        val slots = Math.floor(execAmount * parts / amount).toInt
        if (maxSlots == -1.0 || slots < maxSlots) {
          maxSlots = slots
          limitingResource = name
        }
      } else if (name == ResourceProfile.CPUS) {
        // CPUS are special cased to use cluster default if not specified
        val slots = req.amount / sparkConf.get(CPUS_PER_TASK)
        if (maxSlots == -1.0 || slots < maxSlots) {
          maxSlots = slots
          limitingResource = name
        }
      }
    }
    limitingResource
  }
}

private[spark] object ResourceProfile extends Logging {
  val UNKNOWN_RESOURCE_PROFILE_ID = -1
  val DEFAULT_RESOURCE_PROFILE_ID = 0

  val CPUS = "cpus"
  val CORES = "cores"
  val MEMORY = "memory"
  val OVERHEAD_MEM = "memoryOverhead"
  val PYSPARK_MEM = "pyspark.memory"

  val SPARK_RP_EXEC_PREFIX = "spark.resourceProfile.executor"

  private def resourceProfileIntConfPrefix(rpId: Int): String = {
    s"$SPARK_RP_EXEC_PREFIX.$rpId."
  }

  // Helper class for constructing the resource profile internal configs used to pass to
  // executors. The configs look like:
  // spark.resourceProfile.executor.[rpId].[resourceName].[amount, vendor, discoveryScript]
  // Note custom resources have name like resource.gpu.
  case class ResourceProfileInternalConf(id: Int, resourceName: String) {
    def resourceNameConf: String = s"${resourceProfileIntConfPrefix(id)}$resourceName"
    def resourceNameAndAmount: String = s"$resourceName.${ResourceUtils.AMOUNT}"
    def resourceNameAndDiscovery: String = s"$resourceName.${ResourceUtils.DISCOVERY_SCRIPT}"
    def resourceNameAndVendor: String = s"$resourceName.${ResourceUtils.VENDOR}"

    def amountConf: String = s"${resourceProfileIntConfPrefix(id)}$resourceNameAndAmount"
    def discoveryScriptConf: String =
      s"${resourceProfileIntConfPrefix(id)}$resourceNameAndDiscovery"
    def vendorConf: String = s"${resourceProfileIntConfPrefix(id)}$resourceNameAndVendor"
  }

  private lazy val nextProfileId = new AtomicInteger(0)

  // The default resource profile uses the application level configs.
  // Create the default profile immediately to get ID 0, its initialized later when fetched.
  private val defaultProfileRef: AtomicReference[ResourceProfile] =
    new AtomicReference[ResourceProfile](new ResourceProfile())

  assert(defaultProfileRef.get().id == DEFAULT_RESOURCE_PROFILE_ID,
    s"Default Profile must have the default profile id: $DEFAULT_RESOURCE_PROFILE_ID")

  def getNextProfileId: Int = nextProfileId.getAndIncrement()

  def getOrCreateDefaultProfile(conf: SparkConf): ResourceProfile = {
    val defaultProf = defaultProfileRef.get()
    // check to see if the default profile was initialized yet
    if (defaultProf.executorResources == Map.empty) {
      synchronized {
        val prof = defaultProfileRef.get()
        if (prof.executorResources == Map.empty) {
          addDefaultTaskResources(prof, conf)
          addDefaultExecutorResources(prof, conf)
        }
        prof
      }
    } else {
      defaultProf
    }
  }

  private def addDefaultTaskResources(rprof: ResourceProfile, conf: SparkConf): Unit = {
    val cpusPerTask = conf.get(CPUS_PER_TASK)
    val treqs = new TaskResourceRequests().cpus(cpusPerTask)
    val taskReq = ResourceUtils.parseResourceRequirements(conf, SPARK_TASK_PREFIX)
    taskReq.foreach { req =>
      val name = s"${RESOURCE_PREFIX}.${req.resourceName}"
      treqs.resource(name, req.amount)
    }
    rprof.require(treqs)
  }

  private def addDefaultExecutorResources(rprof: ResourceProfile, conf: SparkConf): Unit = {
    val ereqs = new ExecutorResourceRequests()
    ereqs.cores(conf.get(EXECUTOR_CORES))
    ereqs.memory(conf.get(EXECUTOR_MEMORY).toString)
    val execReq = ResourceUtils.parseAllResourceRequests(conf, SPARK_EXECUTOR_PREFIX)
    execReq.foreach { req =>
      val name = s"${RESOURCE_PREFIX}.${req.id.resourceName}"
      ereqs.resource(name, req.amount, req.discoveryScript.getOrElse(""),
        req.vendor.getOrElse(""))
    }
    rprof.require(ereqs)
  }

  // for testing purposes
  def resetDefaultProfile(conf: SparkConf): Unit = getOrCreateDefaultProfile(conf).reset()

  /**
   * Create the ResourceProfile internal confs that are used to pass between Driver and Executors.
   * It pulls any "resource." resources from the ResourceProfile and returns a Map of key
   * to value where the keys get formatted as:
   *
   * spark.resourceProfile.executor.[rpId].[resourceName].[amount, vendor, discoveryScript]
   *
   * Keep this here as utility a function rather then in public ResourceProfile interface because
   * end users doesn't need this.
   */
  def createResourceProfileInternalConfs(rp: ResourceProfile): Map[String, String] = {
    val ret = new mutable.HashMap[String, String]()
    rp.executorResources.filterKeys(_.startsWith(RESOURCE_DOT)).foreach { case (name, req) =>
      val execIntConf = ResourceProfileInternalConf(rp.id, name)
      ret(execIntConf.amountConf) = req.amount.toString
      if (req.vendor.nonEmpty) ret(execIntConf.vendorConf) = req.vendor
      if (req.discoveryScript.nonEmpty) ret(execIntConf.discoveryScriptConf) = req.discoveryScript
    }
    ret.toMap
  }

  /**
   * Parse out just the resourceName given the map of confs. It only looks for confs that
   * end with .amount because we should always have one of those for every resource.
   * Format is expected to be: [resourcename].amount, where resourceName could have multiple
   * .'s like resource.gpu.foo.amount
   */
  private def listResourceNames(confs: Map[String, String]): Seq[String] = {
    confs.filterKeys(_.endsWith(ResourceUtils.AMOUNT)).
      map { case (key, _) => key.substring(0, key.lastIndexOf(s".${ResourceUtils.AMOUNT}")) }.toSeq
  }

  /**
   * Get the executor ResourceRequests from the internal resource confs
   * The configs looks like:
   * spark.resourceProfile.executor.[rpId].[resourceName].[amount, vendor, discoveryScript]
   * Note that custom resources have names prefixed with resource., ie resource.gpu
   */
  def getResourceRequestsFromInternalConfs(
      sparkConf: SparkConf,
      rpId: Int): Seq[ResourceRequest] = {
    val execRpIdConfPrefix = resourceProfileIntConfPrefix(rpId)
    val execConfs = sparkConf.getAllWithPrefix(execRpIdConfPrefix).toMap
    val execResourceNames = listResourceNames(execConfs)
    val resourceReqs = execResourceNames.map { rName =>
      val intConf = ResourceProfileInternalConf(rpId, rName)
      val amount = execConfs.get(intConf.resourceNameAndAmount).get.toInt
      val vendor = execConfs.get(intConf.resourceNameAndVendor)
      val discoveryScript = execConfs.get(intConf.resourceNameAndDiscovery)
      // note resourceName at this point is resource.[something] because with ResourceProfiles
      // the name matches the spark conf. Strip off the resource. part here to match how global
      // custom resource confs are parsed and how any resource files are handled.
      val shortResourceName = rName.substring(RESOURCE_DOT.length)
      val resourceId = ResourceID(SPARK_EXECUTOR_PREFIX, shortResourceName)
      ResourceRequest(resourceId, amount, discoveryScript, vendor)
    }
    resourceReqs
  }

  /**
   * Utility function to calculate the number of tasks you can run on a single Executor based
   * on the task and executor resource requests in the ResourceProfile. This will be based
   * off the resource that is most restrictive. For instance, if the executor
   * request is for 4 cpus and 2 gpus and your task request is for 1 cpu and 1 gpu each, the
   * limiting resource is gpu, and this function will return 2.
   *
   * @param coresPerExecutor Number of cores per Executor
   * @param resourceProf ResourceProfile
   * @param sparkConf SparkConf
   * @return number of tasks that could be run on a single Executor
   */
  def numTasksPerExecutor(
      coresPerExecutor: Int,
      resourceProf: ResourceProfile,
      sparkConf: SparkConf): Int = {
    val cpusPerTask = resourceProf.taskResources.get(ResourceProfile.CPUS)
      .map(_.amount).getOrElse(sparkConf.get(CPUS_PER_TASK).toDouble).toInt
    val tasksBasedOnCores = coresPerExecutor / cpusPerTask
    var limitingResource = "CPUS"
    var taskLimit = tasksBasedOnCores
    // assumes the task resources are specified
    resourceProf.executorResources.foreach { case (rName, request) =>
      val taskReq = resourceProf.taskResources.get(rName).map(_.amount).getOrElse(0.0)
      if (taskReq > 0.0) {
        var parts = 1
        var numPerTask = taskReq
        if (taskReq < 1.0) {
          parts = Math.floor(1.0 / taskReq).toInt
          numPerTask = Math.ceil(taskReq)
        }
        val numTasks = ((request.amount * parts) / numPerTask).toInt
        if (numTasks < taskLimit) {
          limitingResource = rName
          taskLimit = numTasks
        }
      }
    }
    logInfo(s"Limiting resource is $limitingResource at $taskLimit tasks per Executor")
    taskLimit
  }
}
