package edu.illinois.cs.testrunner.runner

import java.io.File
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.UUID

import com.google.gson.Gson
import edu.illinois.cs.testrunner.configuration.{ConfigProps, Configuration}
import edu.illinois.cs.testrunner.data.framework.TestFramework
import edu.illinois.cs.testrunner.data.results.TestRunResult
import edu.illinois.cs.testrunner.execution.Executor
import edu.illinois.cs.testrunner.util._

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.{Failure, Try}

import java.util.logging.{Level, Logger}

trait Runner {
    def outputPath(): Path
    def classpath(): String
    def framework(): TestFramework

    def environment(): java.util.Map[String, String]

    def execution(testOrder: Stream[String], executionInfoBuilder: ExecutionInfoBuilder): ExecutionInfo

    def runList(testOrder: java.util.List[String]): Try[TestRunResult] =
        run(testOrder.asScala.toStream)

    def runListWithCp(cp: String, testOrder: java.util.List[String]): Try[TestRunResult] =
        runWithCp(cp, testOrder.asScala.toStream)

    def run(testOrder: Stream[String]): Try[TestRunResult] = runWithCp(classpath(), testOrder)

    def makeBuilder(cp: String): ExecutionInfoBuilder = {
        val builder = new ExecutionInfoBuilder(classOf[Executor]).classpath(cp)

        if (Configuration.config().getProperty(ConfigProps.CAPTURE_STATE, false)) {
            builder.javaAgent(Paths.get(Configuration.config().getProperty("testplugin.javaagent")))
        }

        if (!Configuration.config().getProperty("testplugin.javaopts", "").equals("")) {
            val javaopts = Configuration.config().getProperty("testplugin.javaopts", "").split(",")
            val javaoptsList = new ListBuffer[String]()
            for (opt <- javaopts) {
                javaoptsList += opt
            }
            if (!javaoptsList.isEmpty) {
                builder.javaOpts(javaoptsList.toList)
            }
        }

        builder.environment(environment())
    }

    def generateTestRunId(): String = System.currentTimeMillis() + "-" + UUID.randomUUID.toString

    val logger = Logger.getLogger(getClass.getName)

    def runWithCp(cp: String, testOrder: Stream[String]): Try[TestRunResult] = {
        logger.log(Level.INFO, "runWithCp method is being executed")


        TempFiles.withSeq(testOrder)(path => {
            println(s"Temporary path created: $path") // Print after creating the sequence of test paths

            TempFiles.withTempFile(outputPath => {
                println(s"Temporary output path created: $outputPath") // Print after creating the temporary output file

                TempFiles.withProperties(Configuration.config().properties())(propertiesPath => {
                    println(s"Properties path created: $propertiesPath") // Print after creating the properties path

                    val builder = makeBuilder(cp + File.pathSeparator + Configuration.config().getProperty("testplugin.classpath"))

                    val info = execution(testOrder, builder)

                    val testRunId = generateTestRunId()
                    println(s"Generated testRunId: $testRunId") // Print the generated testRunId

                    val exitCode = info.run(
                        testRunId,
                        framework().toString,
                        path.toAbsolutePath.toString,
                        propertiesPath.toAbsolutePath.toString,
                        outputPath.toAbsolutePath.toString).exitValue()

                    println(s"Execution finished with exit code: $exitCode") // Print the exit code

                    if (exitCode == 0) {
                        autoClose(Source.fromFile(outputPath.toAbsolutePath.toString).bufferedReader())(reader =>
                            Try(new Gson().fromJson(reader, classOf[TestRunResult])))
                    } else {
                        // Try to copy the output log so that it can be inspected
                        val failureLog = Paths.get("failing-test-output-" + testRunId)
                        Files.copy(info.outputPath, failureLog, StandardCopyOption.REPLACE_EXISTING)
                        println(s"Non-zero exit code. Output copied to: $failureLog") // Print the failure log location
                        Failure(new Exception("Non-zero exit code (output in " + failureLog.toAbsolutePath + "): " ++ exitCode.toString))
                    }
                })
            })
        }).flatten.flatten.flatten.flatten
    }
}

 
trait RunnerProvider[A <: Runner] {
    def withFramework(framework: TestFramework, classpath: String,
                      environment: java.util.Map[String, String], outputPath: Path): A
}

