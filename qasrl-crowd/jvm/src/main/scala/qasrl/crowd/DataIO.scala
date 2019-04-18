package qasrl.crowd

import qasrl.Frame
import qasrl.QuestionProcessor
import qasrl.TemplateStateMachine
import qasrl.labeling.{QuestionLabelMapper, SlotBasedLabel}
import qasrl.crowd.util.CategoricalDistribution
import qasrl.crowd.util.implicits._
import cats.Foldable
import cats.data.NonEmptyList
import cats.implicits._
import spacro.{Assignment, HITInfo}
import spacro.util.Span
import nlpdata.datasets.wiktionary.Inflections
import nlpdata.datasets.wiktionary.InflectedForms
import nlpdata.util.HasTokens.ops._
import nlpdata.util.LowerCaseStrings._
import nlpdata.util.{HasTokens, LowerCaseStrings, Text}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable
import scala.reflect.macros.whitebox


case class QASRL(sid: String, verbIdx: Int, verb: String,
                 workerId: String, assignId: String, sourceAssignId: String,
                 question: String, isRedundant: Boolean, answerRanges: String, answers: String,
                 wh: String, subj: String, obj: String, obj2: String,
                 aux: String, prep: String, verbPrefix: String,
                 isPassive: Boolean, isNegated: Boolean)

@deprecated("Use JSON format instead; see JsonCodecs in QASRLDataset.scala", "qasrl-crowd 0.1")
object DataIO extends LazyLogging {

  def makeEvaluationQAPairTSV[SID: HasTokens, QuestionLabel](
                                                              ids: List[SID],
                                                              writeId: SID => String, // serialize sentence ID for distribution in data file
                                                              infos: List[HITInfo[QASRLEvaluationPrompt[SID], List[QASRLValidationAnswer]]],
                                                              mapLabels: QuestionLabelMapper[String, QuestionLabel],
                                                              renderLabel: QuestionLabel => String)(
                                                              implicit inflections: Inflections
                                                            ): String = {
    val infosBySentenceId = infos.groupBy(_.hit.prompt.id).withDefaultValue(Nil)
    // val genInfosBySentenceId = genInfos.groupBy(_.hit.prompt.id).withDefaultValue(Nil)
    // val valInfosByGenAssignmentId = valInfos.groupBy(_.hit.prompt.sourceAssignmentId).withDefaultValue(Nil)
    val sb = new StringBuilder
    for (id <- ids) {
      val idString = writeId(id)
      val sentenceTokens = id.tokens
      val sentenceSB = new StringBuilder
      var shouldIncludeSentence = false // for now, print everything
      sentenceSB.append(s"${idString}\t${sentenceTokens.mkString(" ")}\n")
      val qaTuplesByVerbIndex = {
        val qas = for {
          HITInfo(hit, assignments) <- infosBySentenceId(id)
          (sourcedQuestion, answers) <- hit.prompt.sourcedQuestions.zip(assignments.map(_.response).transpose)
        } yield (sourcedQuestion, answers)
        qas.groupBy(_._1.verbIndex)
      }
      for {
        (verbIndex, qaPairs) <- qaTuplesByVerbIndex
        inflForms <- inflections.getInflectedForms(sentenceTokens(verbIndex).lowerCase).toList
        labels = mapLabels(sentenceTokens, inflForms, qaPairs.map(_._1.question))
        ((SourcedQuestion(_, _, sources), answers), Some(qLabel)) <- qaPairs.zip(labels)
      } yield {
        val valAnswerSpans = answers.flatMap(_.getAnswer).map(_.spans)
        if (valAnswerSpans.size == 3) {
          shouldIncludeSentence = true
          sentenceSB.append("\t")
          sentenceSB.append(verbIndex.toString + "\t")
          sentenceSB.append(sources.mkString(";") + "\t")
          sentenceSB.append(renderLabel(qLabel) + "\t")
          sentenceSB.append(
            valAnswerSpans.map { spans =>
              spans
                .map(span => s"${span.begin}-${span.end}")
                .mkString(";")
            }.mkString("\t")
          )
          sentenceSB.append("\n")
        }
      }
      if (shouldIncludeSentence) {
        sb.append(sentenceSB.toString)
      }
    }
    sb.toString
  }

  private def idAndVerb[SID](r: HITInfo[QASRLGenerationPrompt[SID], List[VerbQA]]): (SID, Int) = {
    (r.hit.prompt.id, r.hit.prompt.verbIndex)
  }

  private def getText(span: Span, tokens: Vector[String]) = {
    tokens.slice(span.begin, span.end + 1).mkString(" ")
  }

  private def getRangeAsText(span: Span) = {
    s"${span.begin}:${span.end + 1}"
  }

  def makeGenerationQAPairTSV[SID: HasTokens](
                                               writeId: SID => String, // serialize sentence ID for distribution in data file
                                               genInfos: List[HITInfo[QASRLGenerationPrompt[SID], List[VerbQA]]])(
                                               implicit inflections: Inflections): Iterable[QASRL] = {

    for {
      ((sid, verbIndex), hitInfos) <- genInfos.groupBy(idAndVerb)
      idString = writeId(sid)
      sTokens = sid.tokens
      HITInfo(genHIT, genAssignments) <- hitInfos.sortBy(_.hit.prompt.verbIndex)
      verb = sTokens(verbIndex).lowerCase
      inflForms: InflectedForms <- inflections.getInflectedForms(verb).toList
      genAssignment <- genAssignments.sortBy(_.workerId)
      workerId = genAssignment.workerId
      assignId = genAssignment.assignmentId
      verbQA: VerbQA <- genAssignment.response
      question = verbQA.question
      // take the question string without the '?' character. Last token might be a preposition.
      // We will not identify it if it contains '?' character
      prepositions: Set[LowerCaseString] = getAllPrepositions(question)
      stateMachine = new TemplateStateMachine(sTokens, inflForms, Some(prepositions))
      template = new QuestionProcessor(stateMachine)
      goodStatesOpt = template.processStringFully(question).toOption
      slotOpt <- SlotBasedLabel.getSlotsForQuestion(sTokens, inflForms, List(question))
      slot <- slotOpt
      goodStates <- goodStatesOpt
      frame: Frame = goodStates.toList.collect {
        case QuestionProcessor.CompleteState(_, someFrame, _) => someFrame
      }.head
    } yield {
      val subj = slot.subj.getOrElse("".lowerCase)
      val aux = slot.aux.getOrElse("".lowerCase)
      val verbPrefix = slot.verbPrefix.mkString("~!~")
      val obj = slot.obj.getOrElse("".lowerCase)
      val prep = slot.prep.getOrElse("".lowerCase)
      val obj2 = slot.obj2.getOrElse("".lowerCase)
      val answerRanges = verbQA.answers.map(getRangeAsText).mkString("~!~")
      val answers = verbQA.answers.map(getText(_, sTokens)).mkString("~!~")
      QASRL(idString, verbIndex, verb,
        workerId, assignId, None.toString, question, false, answerRanges, answers,
        slot.wh, subj, obj, obj2,
        aux, prep, verbPrefix,
        frame.isPassive, frame.isNegated)
    }
  }

  private def idAndVerbArbitration[SID](r: HITInfo[QASRLArbitrationPrompt[SID], List[QASRLValidationAnswer]]): (SID, Int) = {
    (r.hit.prompt.genPrompt.id, r.hit.prompt.genPrompt.verbIndex)
  }

  def makeArbitrationQAPairTSV[SID: HasTokens](
                                                writeId: SID => String, // serialize sentence ID for distribution in data file
                                                valInfos: List[HITInfo[QASRLArbitrationPrompt[SID], List[QASRLValidationAnswer]]])(
                                                implicit inflections: Inflections): Iterable[QASRL] = {
    for {
      ((sid, verbIndex), hitInfos) <- valInfos.groupBy(idAndVerbArbitration)
      idString = writeId(sid)
      sTokens = sid.tokens
      HITInfo(valHit, valAssignments) <- hitInfos
      verb = sTokens(verbIndex).lowerCase
      inflForms: InflectedForms <- inflections.getInflectedForms(verb).toList
      valAssignment <- valAssignments.sortBy(_.workerId)

      workerId = valAssignment.workerId
      assignId = valAssignment.assignmentId
      idx <- valHit.prompt.qaPairs.indices

      sourceAssignId = valHit.prompt.qaPairs(idx)._1
      question = valHit.prompt.qaPairs(idx)._2.question
      answer = valAssignment.response(idx)
      answerTexts = answer.getSpans.map(getText(_, sTokens)).mkString("~!~")
      answerRanges = answer.getSpans.map(getRangeAsText).mkString("~!~")
      // take the question string without the '?' character. Last token might be a preposition.
      // We will not identify it if it contains '?' character
      prepositions: Set[LowerCaseString] = getAllPrepositions(question)
      stateMachine = new TemplateStateMachine(sTokens, inflForms, Some(prepositions))
      template = new QuestionProcessor(stateMachine)
      goodStatesOpt = template.processStringFully(question).toOption
      slotOpt <- SlotBasedLabel.getSlotsForQuestion(sTokens, inflForms, List(question))
      slot <- slotOpt
      goodStates <- goodStatesOpt
      frame: Frame = goodStates.toList.collect {
        case QuestionProcessor.CompleteState(_, someFrame, _) => someFrame
      }.head
    } yield {
      val subj = slot.subj.getOrElse("".lowerCase)
      val aux = slot.aux.getOrElse("".lowerCase)
      val verbPrefix = slot.verbPrefix.mkString("~!~")
      val obj = slot.obj.getOrElse("".lowerCase)
      val prep = slot.prep.getOrElse("".lowerCase)
      val obj2 = slot.obj2.getOrElse("".lowerCase)
      val isValid = answer.isAnswer
      QASRL(idString, verbIndex, verb,
        workerId, assignId, sourceAssignId, question, answer.isRedundant,
        answerRanges, answerTexts,
        slot.wh, subj, obj, obj2,
        aux, prep, verbPrefix,
        frame.isPassive, frame.isNegated)
    }
  }


  private def getAllPrepositions[SID: HasTokens](question: String) = {
    val qTokens = question.init.split(" ").toVector.map(_.lowerCase)
    val qPreps = qTokens.filter(TemplateStateMachine.allPrepositions.contains).toSet
    val qPrepBigrams = qTokens.sliding(2)
      .filter(_.forall(TemplateStateMachine.allPrepositions.contains))
      .map(_.mkString(" ").lowerCase)
      .toSet
    val prepositions = qPreps ++ qPrepBigrams
    prepositions
  }

  def makeQAPairTSV[SID: HasTokens, QuestionLabel](
                                                    ids: List[SID],
                                                    writeId: SID => String, // serialize sentence ID for distribution in data file
                                                    genInfos: List[HITInfo[QASRLGenerationPrompt[SID], List[VerbQA]]],
                                                    valInfos: List[HITInfo[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]]],
                                                    mapLabels: QuestionLabelMapper[String, QuestionLabel],
                                                    renderLabel: QuestionLabel => String)(
                                                    implicit inflections: Inflections
                                                  ): String = {
    val genInfosBySentenceId = genInfos.groupBy(_.hit.prompt.id).withDefaultValue(Nil)
    val valInfosByGenAssignmentId = valInfos.groupBy(_.hit.prompt.sourceAssignmentId).withDefaultValue(Nil)
    val sb = new StringBuilder
    for (id <- ids) {
      val idString = writeId(id)
      val sentenceTokens = id.tokens
      val sentenceSB = new StringBuilder
      var shouldIncludeSentence = false // for now, print everything
      sentenceSB.append(s"${idString}\t${sentenceTokens.mkString(" ")}\n")
      for {
        // sort by keyword group first...
        HITInfo(genHIT, genAssignments) <- genInfosBySentenceId(id).sortBy(_.hit.prompt.verbIndex)
        // then worker ID second, so the data will be chunked correctly according to HIT;
        genAssignment <- genAssignments.sortBy(_.workerId)
        // process in order of verb
        verbIndex <- genAssignment.response.map(_.verbIndex).toSet.toList.sorted
        // only use verbs where we have inflected forms (should always be the case though)
        inflForms <- inflections.getInflectedForms(sentenceTokens(verbIndex).lowerCase).toList
        verbQAsIndexed = genAssignment.response.zipWithIndex.filter(_._1.verbIndex == verbIndex)
        labels = mapLabels(sentenceTokens, inflForms, verbQAsIndexed.map(_._1.question))
        ((wqa, qaIndex), Some(qLabel)) <- verbQAsIndexed.zip(labels)
      } yield {
        // pairs of (validation worker ID, validation answer)
        val valAnswerSpans = for {
          info <- valInfosByGenAssignmentId.get(genAssignment.assignmentId).getOrElse(Nil)
          assignment <- info.assignments
          answer <- assignment.response(qaIndex).getAnswer
        } yield answer.spans
        if (valAnswerSpans.size != 2) {
          logger.warn("Warning: don't have 2 validation answers for question. Actual number: " + valAnswerSpans.size)
        } else {
          shouldIncludeSentence = true
          sentenceSB.append("\t")
          sentenceSB.append(wqa.verbIndex.toString + "\t")
          sentenceSB.append(s"turk-${genAssignment.workerId}" + "\t")
          sentenceSB.append(renderLabel(qLabel) + "\t")
          sentenceSB.append(
            (wqa.answers :: valAnswerSpans).map { spans =>
              spans
                .map(span => s"${span.begin}-${span.end}")
                .mkString(";")
            }.mkString("\t")
          )
          sentenceSB.append("\n")
        }
      }
      if (shouldIncludeSentence) {
        sb.append(sentenceSB.toString)
      }
    }
    sb.toString
  }

  // how much and how long must come first so we register them as question prefix
  val whPhrases = List("how much", "how long", "who", "what", "when", "where", "why", "how").map(_.lowerCase)

  def makeReadableQAPairTSV[SID: HasTokens](
                                             ids: List[SID],
                                             writeId: SID => String, // serialize sentence ID for distribution in data file
                                             anonymizeWorker: String => String, // anonymize worker IDs so they can't be tied back to workers on Turk
                                             genInfos: List[HITInfo[QASRLGenerationPrompt[SID], List[VerbQA]]],
                                             valInfos: List[HITInfo[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]]],
                                             keepQA: (SID, VerbQA, List[QASRLValidationAnswer]) => Boolean = (
                                               (_: SID, _: VerbQA, _: List[QASRLValidationAnswer]) => true)
                                           ): String = {
    val genInfosBySentenceId = genInfos.groupBy(_.hit.prompt.id).withDefaultValue(Nil)
    val valInfosByGenAssignmentId = valInfos.groupBy(_.hit.prompt.sourceAssignmentId).withDefaultValue(Nil)
    val sb = new StringBuilder
    for (id <- ids) {
      val idString = writeId(id)
      val sentenceTokens = id.tokens
      val sentenceSB = new StringBuilder
      var shouldIncludeSentence = false
      sentenceSB.append(s"${idString}\t${nlpdata.util.Text.render(sentenceTokens)}\n")
      // sort by keyword group first...
      for (HITInfo(genHIT, genAssignments) <- genInfosBySentenceId(id).sortBy(_.hit.prompt.verbIndex)) {
        // then worker ID second, so the data will be chunked correctly according to HIT;
        for (genAssignment <- genAssignments.sortBy(_.workerId)) {
          // and these should already be ordered in terms of the target word used for a QA pair.
          for ((wqa, qaIndex) <- genAssignment.response.zipWithIndex) {
            // pairs of (validation worker ID, validation answer)
            val valResponses = valInfosByGenAssignmentId.get(genAssignment.assignmentId).getOrElse(Nil)
              .flatMap(_.assignments.map(a => (a.workerId, a.response(qaIndex))))
            if (valResponses.size != 2) {
              logger.warn("Warning: don't have 2 validation answers for question. Actual number: " + valResponses.size)
            }
            val valAnswers = valResponses.map(_._2)

            if (keepQA(id, wqa, valAnswers)) {
              shouldIncludeSentence = true
              sentenceSB.append(anonymizeWorker(genAssignment.workerId) + "\t") // anonymized worker ID
              sentenceSB.append(s"${Text.normalizeToken(sentenceTokens(wqa.verbIndex))} (${wqa.verbIndex})\t")
              sentenceSB.append(wqa.question + "\t") // question string written by worker
              sentenceSB.append(
                ((Answer(wqa.answers)) :: valResponses.map(_._2)).map { valAnswer =>
                  QASRLValidationAnswer.render(sentenceTokens, valAnswer)
                }.mkString("\t")
              )
              sentenceSB.append("\n")
            }
          }
        }
      }
      if (shouldIncludeSentence) {
        sb.append(sentenceSB.toString)
      }
    }
    sb.toString
  }
}
