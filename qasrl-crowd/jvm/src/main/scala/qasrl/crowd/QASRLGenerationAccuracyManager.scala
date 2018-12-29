package qasrl.crowd

import scala.collection.JavaConverters._

import qasrl.crowd.util.implicits._
import qasrl.crowd.util.dollarsToCents
import spacro._
import spacro.tasks._
import spacro.util._

import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import akka.actor.{Actor, ActorRef}
import com.amazonaws.services.mturk.model.{Assignment => _, _}
import upickle.default._
import com.typesafe.scalalogging.StrictLogging

class QASRLGenerationAccuracyManager[SID : Reader : Writer](
  genDisqualificationTypeId: String, genAssignLimitDisqualType: String, assignLimit: Int)(
  implicit annotationDataService: AnnotationDataService,
  config: TaskConfig,
  settings: QASRLSettings
) extends Actor with StrictLogging {

  import config._

  val workerStatsFilename = "generationWorkerStats"

  var allWorkerStats =
    annotationDataService.loadLiveData(workerStatsFilename)
      .map(_.mkString)
      .map(read[Map[String, QASRLGenerationWorkerStats]])
      .toOption.getOrElse {
      Map.empty[String, QASRLGenerationWorkerStats]
    }

  def christenWorker(workerId: String, numAgreementsToAdd: Int) = {
    allWorkerStats = allWorkerStats.get(workerId).fold(allWorkerStats) { stats =>
      allWorkerStats.updated(workerId, stats.addBonusValids(numAgreementsToAdd))
    }
    assessQualification(workerId)
  }

  private[this] def save = {
    Try(
      annotationDataService.saveLiveData(
        workerStatsFilename,
        write[Map[String, QASRLGenerationWorkerStats]](allWorkerStats))
    ).toOptionLogging(logger).foreach(_ => logger.info("Worker stats data saved."))
  }

  private def getAssignmentFromValPrompt(valPrompt: QASRLValidationPrompt[SID]): Option[Assignment[List[VerbQA]]] = {
    val assignmentsForHIT = for {
      hit <- hitDataService.getHIT[QASRLGenerationPrompt[SID]](valPrompt.sourceHITTypeId, valPrompt.sourceHITId).toOptionLogging(logger).toList
      assignment <- hitDataService.getAssignmentsForHIT[List[VerbQA]](valPrompt.sourceHITTypeId, valPrompt.sourceHITId).get
    } yield assignment
    assignmentsForHIT.find(_.assignmentId == valPrompt.sourceAssignmentId)
  }

  def disqualify(workerId: String, qualification: String, notify: Boolean = true) = {
    // This may put a bit pressure on AMT, hopefully, not too much...
    val res = config.service.listWorkersWithQualificationType(
      new ListWorkersWithQualificationTypeRequest()
        .withMaxResults(100)
        .withQualificationTypeId(qualification))

    val workers = res.getQualifications.asScala.map(_.getWorkerId).toSet
    if (workers.contains(workerId)) {
      None
    } else {
      logger.info(s"Disqualifying: $qualification from worker: $workerId")
      Some(config.service.associateQualificationWithWorker(
        new AssociateQualificationWithWorkerRequest()
          .withQualificationTypeId(qualification)
          .withWorkerId(workerId)
          .withIntegerValue(1)
          .withSendNotification(notify)))
    }
  }

  def assessQualification(workerId: String): Unit = {
    Try {
      allWorkerStats.get(workerId).foreach { stats =>
        val workerIsDisqualified = config.service
          .listAllWorkersWithQualificationType(genDisqualificationTypeId)
          .contains(stats.workerId)

        val workerShouldBeDisqualified = !stats.accuracy.isNaN &&
          stats.accuracy < settings.generationAccuracyBlockingThreshold &&
          (stats.numValidatorJudgments / 2) > settings.generationAccuracyGracePeriod

        if(workerIsDisqualified && !workerShouldBeDisqualified) {
          config.service.disassociateQualificationFromWorker(
            new DisassociateQualificationFromWorkerRequest()
              .withQualificationTypeId(genDisqualificationTypeId)
              .withWorkerId(stats.workerId)
              .withReason("Accuracy dropped too low on the question writing task."))
        } else if(!workerIsDisqualified && workerShouldBeDisqualified) {
            logger.info(s"Generation\tDisqualifying\tWorker:\t${stats.workerId}\tAgreement:\t${stats.accuracy}")
            config.service.associateQualificationWithWorker(
              new AssociateQualificationWithWorkerRequest()
                .withQualificationTypeId(genDisqualificationTypeId)
                .withWorkerId(stats.workerId)
                .withIntegerValue(1)
                .withSendNotification(true))
        }

        if (stats.numAssignmentsCompleted > assignLimit){
          disqualify(stats.workerId, genAssignLimitDisqualType, false)
        }
      }
    }
  }

  override def receive = {
    case SaveData => save
    case ChristenWorker(workerId, numAgreementsToAdd) => christenWorker(workerId, numAgreementsToAdd)
    case ValidatorBlocked(badValidatorId) =>
      allWorkerStats = allWorkerStats.map {
        case (wid, stats) => wid -> stats.removeJudgmentsByWorker(badValidatorId)
      }
      allWorkerStats.keys.foreach(assessQualification)
    case vr: QASRLValidationResult[SID] => vr match {
      case QASRLValidationResult(valPrompt, valWorker, valResponse) =>
        getAssignmentFromValPrompt(valPrompt).foreach { assignment =>
          val accuracyJudgments = valResponse.map(r => AccuracyJudgment(valWorker, r.isAnswer)).toVector

          allWorkerStats = allWorkerStats.updated(
            assignment.workerId,
            allWorkerStats
              .get(assignment.workerId)
              .getOrElse(QASRLGenerationWorkerStats.empty(assignment.workerId))
              .addAccuracyJudgments(accuracyJudgments)
          )

          assessQualification(assignment.workerId)
        }
    }
    case vr: QASRLValidationFinished[SID] => vr match {
      case QASRLValidationFinished(valPrompt, numQAsValid) =>
        getAssignmentFromValPrompt(valPrompt).foreach { assignment =>
          // award bonuses
          val numQAsProvided = assignment.response.size
          val bonusAwarded = settings.generationBonus(numQAsValid)
          val bonusCents = dollarsToCents(bonusAwarded)
          if(bonusAwarded > 0.0) {
            val bonusMessage = s"Generation\tBonus:\t$bonusAwarded\tHitType:\t${assignment.hitTypeId}\tHitId:\t${assignment.hitId}\t"
            val assignMessage = s"Worker:\t${assignment.workerId}\tassignment:\t${assignment.assignmentId}"
            logger.info(bonusMessage + assignMessage)
            Try(
              service.sendBonus(
                new SendBonusRequest()
                  .withWorkerId(assignment.workerId)
                  .withBonusAmount(f"$bonusAwarded%.2f")
                  .withAssignmentId(assignment.assignmentId)
                  .withReason(
                  s"""$numQAsValid out of $numQAsProvided question-answer pairs were judged to be valid, for a bonus of ${bonusCents}c."""))
            ).toOptionLogging(logger).ifEmpty(logger.error(s"Failed to grant bonus of $bonusCents to worker ${assignment.workerId}"))
          }

          allWorkerStats = allWorkerStats.updated(
            assignment.workerId,
            allWorkerStats
              .get(assignment.workerId)
              .getOrElse(QASRLGenerationWorkerStats.empty(assignment.workerId))
              .registerValidationFinished(settings.generationReward + bonusAwarded)
          )
        }
    }
  }
}
