package ch.cern.sparkmeasure

import collection.mutable.LinkedHashMap

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer
import scala.math.{min, max}

/**
 *  Stage Metrics: collects stage-level metrics with Stage granularity
 *                 and provides aggregation and reporting functions for the end-user
 *
 * Example usage for stage metrics:
 * val stageMetrics = ch.cern.sparkmeasure.StageMetrics(spark)
 * stageMetrics.runAndMeasure(spark.sql("select count(*) from range(1000) cross join range(1000) cross join range(1000)").show)
 *
 * The tool is based on using Spark Listeners as data source and collecting metrics in a ListBuffer of
 * a case class that encapsulates Spark task metrics.
 * The List Buffer is then transformed into a DataFrame for ease of reporting and analysis.
 *
 * Stage metrics are stored in memory and use to produce a report that aggregates resource consumption
 * they can also be consumed "raw" (transformed into a DataFrame and/or saved to a file)
 *
 */
case class StageMetrics(sparkSession: SparkSession) {

  lazy val logger = LoggerFactory.getLogger(this.getClass.getName)

  // This inserts and starts the custom Spark Listener into the live Spark Context
  val listenerStage = new StageInfoRecorderListener
  registerListener(sparkSession, listenerStage)

  // Variables used to store the start and end time of the period of interest for the metrics report
  var beginSnapshot: Long = 0L
  var endSnapshot: Long = 0L

  def begin(): Long = {
    listenerStage.stageMetricsData.clear()    // clear previous data to reduce memory footprint
    beginSnapshot = System.currentTimeMillis()
    endSnapshot = beginSnapshot
    beginSnapshot
  }

  def end(): Long = {
    endSnapshot = System.currentTimeMillis()
    endSnapshot
  }

  // helper method to register the listener
  def registerListener(spark: SparkSession, listener: StageInfoRecorderListener): Unit = {
    spark.sparkContext.addSparkListener(listener)
  }

  // helper method to remove the listener
  def removeListenerStage(): Unit = {
    sparkSession.sparkContext.removeSparkListener(listenerStage)
  }

  // Compute basic aggregation on the Stage metrics for the metrics report
  // also filter on the time boundaries for the report
  def aggregateStageMetrics() : LinkedHashMap[String, Long] = {

    val agg = Utils.zeroMetricsStage()
    var submissionTime = Long.MaxValue
    var completionTime = 0L

    for (metrics <- listenerStage.stageMetricsData
         if (metrics.submissionTime >= beginSnapshot && metrics.completionTime <= endSnapshot)) {
      agg("numStages") += 1L
      agg("numTasks") += metrics.numTasks
      agg("stageDuration") += metrics.stageDuration
      agg("executorRunTime") += metrics.executorRunTime
      agg("executorCpuTime") += metrics.executorCpuTime
      agg("executorDeserializeTime") += metrics.executorDeserializeTime
      agg("executorDeserializeCpuTime") += metrics.executorDeserializeCpuTime
      agg("resultSerializationTime") += metrics.resultSerializationTime
      agg("jvmGCTime") += metrics.jvmGCTime
      agg("shuffleFetchWaitTime") += metrics.shuffleFetchWaitTime
      agg("shuffleWriteTime") += metrics.shuffleWriteTime
      agg("resultSize") = max(metrics.resultSize, agg("resultSize"))
      agg("diskBytesSpilled") += metrics.diskBytesSpilled
      agg("memoryBytesSpilled") += metrics.memoryBytesSpilled
      agg("peakExecutionMemory") += metrics.peakExecutionMemory
      agg("recordsRead") += metrics.recordsRead
      agg("bytesRead") += metrics.bytesRead
      agg("recordsWritten") += metrics.recordsWritten
      agg("bytesWritten") += metrics.bytesWritten
      agg("shuffleRecordsRead") += metrics.shuffleRecordsRead
      agg("shuffleTotalBlocksFetched") += metrics.shuffleTotalBlocksFetched
      agg("shuffleLocalBlocksFetched") += metrics.shuffleLocalBlocksFetched
      agg("shuffleRemoteBlocksFetched") += metrics.shuffleRemoteBlocksFetched
      agg("shuffleTotalBytesRead") += metrics.shuffleTotalBytesRead
      agg("shuffleLocalBytesRead") += metrics.shuffleLocalBytesRead
      agg("shuffleRemoteBytesRead") += metrics.shuffleRemoteBytesRead
      agg("shuffleRemoteBytesReadToDisk") += metrics.shuffleRemoteBytesReadToDisk
      agg("shuffleBytesWritten") += metrics.shuffleBytesWritten
      agg("shuffleRecordsWritten") += metrics.shuffleRecordsWritten
      submissionTime = min(metrics.submissionTime, submissionTime)
      completionTime = max(metrics.completionTime, completionTime)
    }
    agg("elapsedTime") = completionTime - submissionTime
    agg
  }

  // Custom aggregations and post-processing of metrics data
  def report(): String = {
    val aggregatedMetrics = aggregateStageMetrics()
    var result = ListBuffer[String]()

    result = result :+ s"\nScheduling mode = ${sparkSession.sparkContext.getSchedulingMode.toString}"
    result = result :+ s"Spark Context default degree of parallelism = ${sparkSession.sparkContext.defaultParallelism}"
    result = result :+ "Aggregated Spark stage metrics:"

    for (x <- aggregatedMetrics) {
      result = result :+ Utils.prettyPrintValues(x._1, x._2)
    }

    result.mkString("\n")
  }

  def printReport(): Unit = {
    println(report())
  }

  // Legacy transformation of data recorded from the custom Stage listener
  // into a DataFrame and register it as a view for querying with SQL
  def createStageMetricsDF(nameTempView: String = "PerfStageMetrics"): DataFrame = {
    import sparkSession.implicits._
    val resultDF = listenerStage.stageMetricsData.toDF
    resultDF.createOrReplaceTempView(nameTempView)
    logger.warn(s"Stage metrics data refreshed into temp view $nameTempView")
    resultDF
  }

  // legacy metrics aggregation computed using SQL
  def aggregateStageMetrics(nameTempView: String = "PerfStageMetrics"): DataFrame = {
    sparkSession.sql(s"select count(*) as numStages, sum(numTasks) as numTasks, " +
      s"max(completionTime) - min(submissionTime) as elapsedTime, sum(stageDuration) as stageDuration , " +
      s"sum(executorRunTime) as executorRunTime, sum(executorCpuTime) as executorCpuTime, " +
      s"sum(executorDeserializeTime) as executorDeserializeTime, sum(executorDeserializeCpuTime) as executorDeserializeCpuTime, " +
      s"sum(resultSerializationTime) as resultSerializationTime, sum(jvmGCTime) as jvmGCTime, "+
      s"sum(shuffleFetchWaitTime) as shuffleFetchWaitTime, sum(shuffleWriteTime) as shuffleWriteTime, " +
      s"max(resultSize) as resultSize, " +
      s"sum(diskBytesSpilled) as diskBytesSpilled, sum(memoryBytesSpilled) as memoryBytesSpilled, " +
      s"max(peakExecutionMemory) as peakExecutionMemory, sum(recordsRead) as recordsRead, sum(bytesRead) as bytesRead, " +
      s"sum(recordsWritten) as recordsWritten, sum(bytesWritten) as bytesWritten, " +
      s"sum(shuffleRecordsRead) as shuffleRecordsRead, sum(shuffleTotalBlocksFetched) as shuffleTotalBlocksFetched, "+
      s"sum(shuffleLocalBlocksFetched) as shuffleLocalBlocksFetched, sum(shuffleRemoteBlocksFetched) as shuffleRemoteBlocksFetched, "+
      s"sum(shuffleTotalBytesRead) as shuffleTotalBytesRead, sum(shuffleLocalBytesRead) as shuffleLocalBytesRead, " +
      s"sum(shuffleRemoteBytesRead) as shuffleRemoteBytesRead, sum(shuffleRemoteBytesReadToDisk) as shuffleRemoteBytesReadToDisk, " +
      s"sum(shuffleBytesWritten) as shuffleBytesWritten, sum(shuffleRecordsWritten) as shuffleRecordsWritten " +
      s"from $nameTempView " +
      s"where submissionTime >= $beginSnapshot and completionTime <= $endSnapshot")

  }

  // Custom aggregations and post-processing of metrics data
  // This is legacy and uses Spark DataFrame operations,
  // use report instead, which will process data in the driver using Scala
  def reportUsingDataFrame(): String = {
    val nameTempView = "PerfStageMetrics"
    createStageMetricsDF(nameTempView)
    val aggregateDF = aggregateStageMetrics(nameTempView)
    var result = ListBuffer[String]()

    result = result :+ s"\nScheduling mode = ${sparkSession.sparkContext.getSchedulingMode.toString}"
    result = result :+ s"Spark Context default degree of parallelism = ${sparkSession.sparkContext.defaultParallelism}"

    /** Print a summary of the stage metrics. */
    val aggregateValues = aggregateDF.take(1)(0).toSeq
    if (aggregateValues(1) != null) {
      result = result :+ "Aggregated Spark stage metrics:"
      val cols = aggregateDF.columns
      result = result :+ (cols zip aggregateValues)
        .map{
          case(n:String, v:Long) => Utils.prettyPrintValues(n, v)
          case(n: String, null) => n + " => null"
          case(_,_) => ""
        }.mkString("\n")
    } else {
      result = result :+ " no data to report "
    }

    result.mkString("\n")
  }

  /**
   * Send the metrics to Prometheus.
   * serverIPnPort: String with prometheus pushgateway address, format is hostIP:Port,
   * metricsJob: job name,
   * labelName: metrics label name, default is sparkSession.sparkContext.appName,
   * labelValue: metrics label value, default is sparkSession.sparkContext.applicationId
   */
  def sendReportPrometheus(serverIPnPort: String,
                 metricsJob: String,
                 labelName: String = sparkSession.sparkContext.appName,
                 labelValue: String = sparkSession.sparkContext.applicationId): Unit = {

    val nameTempView = "PerfStageMetrics"
    createStageMetricsDF(nameTempView)
    val aggregateDF = aggregateStageMetrics(nameTempView)

    /** Prepare a summary of the stage metrics for Prometheus. */
    val pushGateway = PushGateway(serverIPnPort, metricsJob)
    var str_metrics = s""
    val aggregateValues = aggregateDF.take(1)(0).toSeq
    val cols = aggregateDF.columns
    (cols zip aggregateValues)
      .foreach {
        case(n:String, v:Long) =>
          str_metrics += pushGateway.validateMetric(n.toLowerCase()) + s" " + v.toString + s"\n"
        case(_,_) => // We should no get here, in case add code to handle this
      }

    /** Send stage metrics to Prometheus. */
    val metricsType = s"stage"
    pushGateway.post(str_metrics, metricsType, labelName, labelValue)
  }

  /** Shortcut to run and measure the metrics for Spark execution, built after spark.time() */
  def runAndMeasure[T](f: => T): T = {
    begin()
    val startTime = System.nanoTime()
    val ret = f
    val endTime = System.nanoTime()
    end()
    println(s"Time taken: ${(endTime - startTime) / 1000000} ms")
    printReport()
    ret
  }

  // Helper method to save data, we expect to have small amounts of data so collapsing to 1 partition seems OK
  def saveData(df: DataFrame, fileName: String, fileFormat: String = "json", saveMode: String = "default") = {
    df.coalesce(1).write.format(fileFormat).mode(saveMode).save(fileName)
    logger.warn(s"Stage metric data saved into $fileName using format=$fileFormat")
  }
  
}