import java.nio.file.{Path, Paths}

import eval._
import qasrl.crowd.{QASRLValidationAnswer, _}
import spacro._
import spacro.tasks._
import spacro.util._
import akka.pattern.ask

import scala.concurrent.duration._
import com.amazonaws.services.mturk._
import com.amazonaws.services.mturk.model._
import com.github.tototoshi.csv.CSVReader
import nlpdata.util.Text
import nlpdata.util.HasTokens.ops._


val isProduction = false // sandbox. change to true for production
// this is how the .js file is found to send to the server

//val domain = "localhost" // change to your domain, or keep localhost for testing
//val projectName = "qasrl-crowd-eval" // make sure it matches the SBT project;
//val interface = "0.0.0.0"
//val httpPort = 8888
//val httpsPort = 8080

 Ports and domains on te-srv4
val domain = "u.cs.biu.ac.il/~stanovg/qasrl" // change to your domain, or keep localhost for testing
val interface = "0.0.0.0"
val httpPort = 5908
val httpsPort = 5908

// Uncomment the phase you want to activate
//val phase = Trap
val phase = Training
//val phase = Production
val phaseName = phase.toString.toLowerCase
val annotationPath = Paths.get(s"data/annotations.$phaseName")
val liveDataPath = Paths.get(s"data/live.$phaseName")
val sentsPath = Paths.get(s"data/$phaseName.csv")
val qasrlPath = Paths.get(s"data/$phaseName.annot.csv")

implicit val timeout = akka.util.Timeout(5.seconds)
implicit val config: TaskConfig = {
  if(isProduction) {
    val hitDataService = new FileSystemHITDataService(annotationPath.resolve("production"))
    ProductionTaskConfig(projectName, domain, interface, httpPort, httpsPort, hitDataService)
  } else {
    val hitDataService = new FileSystemHITDataService(annotationPath.resolve("sandbox"))
    SandboxTaskConfig(projectName, domain, interface, httpPort, httpsPort, hitDataService)
  }
}

def exit = {
  // actor system has to be terminated for JVM to be able to terminate properly upon :q
  config.actorSystem.terminate
  // flush & release logging resources
  import org.slf4j.LoggerFactory
  import ch.qos.logback.classic.LoggerContext
  LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].stop
  System.out.println("Terminated actor system and logging. Type :q to end.")
}

val setup = new EvaluationSetup(qasrlPath, sentsPath, liveDataPath)

import setup.SentenceIdHasTokens

val exp = setup.experiment

// use with caution... intended mainly for sandbox
def deleteAll = {
  exp.setValHITsActive(0)
  Thread.sleep(200)
  exp.expire
  exp.delete
}

def yesterday = {
  val cal = java.util.Calendar.getInstance
  cal.add(java.util.Calendar.DATE, -1)
  cal.getTime
}

import scala.collection.JavaConverters._


def progress() = {
  val totalPrompts = exp.allPrompts.length
  val savedPrompts = exp.allInfos

  val uploadedPrompts = savedPrompts.length

  val completedPrompts = savedPrompts.count(_.assignments.nonEmpty)

  println(s"HitTypeId: ${exp.valTaskSpec.hitTypeId}")
  println(f"uploaded: $uploadedPrompts / $totalPrompts ")
  println(f"Completed: $completedPrompts / $totalPrompts ")
}






