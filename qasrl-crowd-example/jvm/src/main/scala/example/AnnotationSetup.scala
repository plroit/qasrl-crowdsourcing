package example

import java.nio.file.{Files, Path, Paths}

import com.github.tototoshi.csv.CSVReader
import com.typesafe.scalalogging.StrictLogging
import nlpdata.datasets.wiktionary
import nlpdata.util.HasTokens
import nlpdata.util.HasTokens.ops._
import qasrl.crowd._
import qasrl.labeling._
import spacro._
import spacro.tasks._
import upickle.default._

import scala.language.postfixOps
import scala.util.Try

class AnnotationSetup(datasetPath: Path, liveDataPath: Path,
                      numGenerationsPerPrompt: Int,
                      numValidationsPerPrompt: Int,
                      numActivePrompts: Int)(
  implicit config: TaskConfig) extends StrictLogging{

  val resourcePath: Path = java.nio.file.Paths.get("datasets")
  val staticDataPath: Path = Paths.get(s"data/static")

  val liveAnnotationDataService = new FileSystemAnnotationDataService(liveDataPath)

  def saveOutputFile(name: String, contents: String): Try[Unit] = Try {
    val path = staticDataPath.resolve("out").resolve(name)
    val directory = path.getParent
    if(!Files.exists(directory)) {
      Files.createDirectories(directory)
    }
    Files.write(path, contents.getBytes())
  }

  def loadOutputFile(name: String): Try[List[String]] = Try {
    val path = staticDataPath.resolve("out").resolve(name)
    import scala.collection.JavaConverters._
    Files.lines(path).iterator.asScala.toList
  }

  val dataset: Map[String, Vector[String]] = {
    logger.info(s"Reading dataset from: $datasetPath")
    val reader: CSVReader = CSVReader.open(datasetPath.toString)
    // CSV format:
    // qasrl_id, sentence
    // 10_13ecbplus.xml,0,Report: Red Sox offer Teixeira $200 million
    val csvRecords = reader.allWithHeaders()
    (for {
      rec <- csvRecords
      id = rec("qasrl_id")
      sent = rec("sentence")
      tokens: Vector[String] = Tokenizer.tokenize(sent)
    } yield id -> tokens).toMap
  }

  val allIds: Vector[SentenceId] = dataset.keys.map(SentenceId(_)).toVector

  lazy val Wiktionary = new wiktionary.WiktionaryFileSystemService(
    resourcePath.resolve("wiktionary")
  )

  implicit object SentenceIdHasTokens extends HasTokens[SentenceId] {
    override def getTokens(sid: SentenceId): Vector[String] = dataset(sid.id)
  }

  implicit lazy val inflections = {
    val tokens = for {
      id <- allIds.iterator
      word <- id.tokens.iterator
    } yield word
    Wiktionary.getInflectionsForTokens(tokens)
  }


  lazy val experiment = new QASRLAnnotationPipeline(
    allIds,
    numGenerationsPerPrompt,
    numValidationsPerPrompt,
    numActivePrompts,
    liveAnnotationDataService)

  def saveAnnotationData[A](
    filename: String,
    ids: Vector[SentenceId],
    genInfos: List[HITInfo[QASRLGenerationPrompt[SentenceId], List[VerbQA]]],
    valInfos: List[HITInfo[QASRLValidationPrompt[SentenceId], List[QASRLValidationAnswer]]],
    labelMapper: QuestionLabelMapper[String, A],
    labelRenderer: A => String
  ) = {
    saveOutputFile(
      s"$filename.tsv",
      DataIO.makeQAPairTSV(
        ids.toList,
        SentenceId.toString,
        genInfos,
        valInfos,
        labelMapper,
        labelRenderer)
    )
  }

  def saveAnnotationDataReadable(
    filename: String,
    ids: Vector[SentenceId],
    genInfos: List[HITInfo[QASRLGenerationPrompt[SentenceId], List[VerbQA]]],
    valInfos: List[HITInfo[QASRLValidationPrompt[SentenceId], List[QASRLValidationAnswer]]]
  ) = {
    saveOutputFile(
      s"$filename.tsv",
      DataIO.makeReadableQAPairTSV(
        ids.toList,
        SentenceId.toString,
        identity,
        genInfos,
        valInfos,
        (id: SentenceId, qa: VerbQA, responses: List[QASRLValidationAnswer]) => responses.forall(_.isAnswer))
    )
  }
}
