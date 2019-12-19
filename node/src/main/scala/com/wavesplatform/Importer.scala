package com.wavesplatform

import java.io._

import com.google.common.primitives.Ints
import com.wavesplatform.Exporter.Formats
import com.wavesplatform.block.Block
import com.wavesplatform.consensus.PoSSelector
import com.wavesplatform.database.openDB
import com.wavesplatform.history.StorageFactory
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.estimator.ScriptEstimatorV1
import com.wavesplatform.lang.v1.estimator.v2.ScriptEstimatorV2
import com.wavesplatform.lang.v1.estimator.v3.ScriptEstimatorV3
import com.wavesplatform.protobuf.block.PBBlocks
import com.wavesplatform.state.appender.BlockAppender
import com.wavesplatform.state.{Blockchain, BlockchainUpdated}
import com.wavesplatform.transaction.assets.{IssueTransaction, SetAssetScriptTransaction}
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.{Asset, BlockchainUpdater, DiscardedBlocks, Transaction}
import com.wavesplatform.utils._
import com.wavesplatform.utx.UtxPoolImpl
import kamon.Kamon
import monix.execution.{Scheduler, UncaughtExceptionReporter}
import monix.reactive.Observer
import monix.reactive.subjects.PublishSubject
import scopt.OParser

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.estimator.ScriptEstimatorV1
import com.wavesplatform.lang.v1.estimator.v2.ScriptEstimatorV2
import com.wavesplatform.lang.v1.estimator.v3.ScriptEstimatorV3
import com.wavesplatform.state.{Blockchain, BlockchainUpdated}
import com.wavesplatform.transaction.assets.{IssueTransaction, SetAssetScriptTransaction}
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.{Asset, BlockchainUpdater, DiscardedBlocks, Transaction}
import scala.concurrent.Await
import scala.concurrent.duration._

object Importer extends ScorexLogging {
  //noinspection ScalaStyle
  def main(args: Array[String]): Unit = {
    OParser.parse(commandParser, args, ImportOptions()).foreach {
      case ImportOptions(configFile, blockchainFile, importHeight, format, verifyTransactions) =>
        val settings = Application.loadApplicationConfig(Some(configFile))

        implicit val scheduler: Scheduler = Scheduler.singleThread("appender")

        val time = new NTP(settings.ntpServer)
        log.info(s"Loading file '$blockchainFile'")

        Try(new FileInputStream(blockchainFile)) match {
          case Success(inputStream) =>
            val db                = openDB(settings.dbSettings.directory)
            val blockchainUpdater = StorageFactory(settings, db, time, Observer.empty(UncaughtExceptionReporter.default))
            val pos               = new PoSSelector(blockchainUpdater, settings.blockchainSettings, settings.synchronizationSettings)
            val ups               = new UtxPoolImpl(time, blockchainUpdater, PublishSubject(), settings.utxSettings)
            val extAppender       = BlockAppender(blockchainUpdater, time, ups, pos, scheduler, verifyTransactions) _
            checkGenesis(settings, blockchainUpdater)
            val bis           = new BufferedInputStream(inputStream)
            var quit          = false
            val lenBytes      = new Array[Byte](Ints.BYTES)
            val start         = System.nanoTime()
            var counter       = 0
            val startHeight = 1 // blockchainUpdater.height
            var blocksToSkip  = startHeight - 1
            val blocksToApply = importHeight - startHeight + 1

            log.info(s"Skipping $blocksToSkip block(s)")

            val pw = new PrintWriter(new FileOutputStream("script-estimations.csv", true))
            sys.addShutdownHook {
              import scala.concurrent.duration._
              val millis = (System.nanoTime() - start).nanos.toMillis
              log.info(s"Imported $counter block(s) from ${startHeight} to ${startHeight + counter} in ${humanReadableDuration(millis)}")
              pw.flush()
      pw.close()
    }

            while (!quit && counter < blocksToApply) {
              val s1 = bis.read(lenBytes)
              if (s1 == Ints.BYTES) {
                val len    = Ints.fromByteArray(lenBytes)
                val buffer = new Array[Byte](len)
                val s2     = bis.read(buffer)
                if (s2 == len) {
                  if (blocksToSkip > 0) {
                    blocksToSkip -= 1
                  } else {
                    val Right(block) =
                      if (format == Formats.Binary) Block.parseBytes(buffer).toEither
                      else PBBlocks.vanilla(PBBlocks.addChainId(protobuf.block.PBBlock.parseFrom(buffer)), unsafe = true)

                    val scripts = block.transactionData.collect {
                      case s: SetScriptTransaction if s.script.nonEmpty      => (s.id(), s.script.get)
                      case s: SetAssetScriptTransaction if s.script.nonEmpty => (s.id(), s.script.get)
                      case i: IssueTransaction if i.script.nonEmpty          => (i.id(), i.script.get)
                    }
                    scripts.foreach {
                      case (txId, script) =>
                        val e1 = Script.estimate(script, ScriptEstimatorV1).getOrElse(-1L)
                        val e2 = Script.estimate(script, ScriptEstimatorV2).getOrElse(-1L)
                        val e3 = Script.estimate(script, ScriptEstimatorV3).getOrElse(-1L)
                        println(s"$txId,$e1,$e2,$e3")
                pw.println(s"$txId,$e1,$e2,$e3")
                    }
                    counter += 1
                  }
                } else {
                  log.debug(s"$s2 != expected $len")
                  quit = true
                }
              } else {
                log.debug(s"Expecting to read ${Ints.BYTES} but got $s1 (${bis.available()})")
                quit = true
              }
            }
            bis.close()
            inputStream.close()

          case Failure(error) =>
            log.error(s"Failed to open file '$blockchainFile", error)
        }

        Await.result(Kamon.stopAllReporters(), 10.seconds)
        time.close()
    }
  }

  private[this] final case class ImportOptions(configFile: File = new File("waves-testnet.conf"),
                                               blockchainFile: File = new File("blockchain"),
                                               importHeight: Int = Int.MaxValue,
                                               format: String = Formats.Binary,
                                               verify: Boolean = true)

  private[this] lazy val commandParser = {
    import scopt.OParser

    val builder = OParser.builder[ImportOptions]
    import builder._

    OParser.sequence(
      programName("waves import"),
      head("Waves Blockchain Importer", Version.VersionString),
      opt[File]('c', "config")
        .text("Config file name")
        .action((f, c) => c.copy(configFile = f)),
      opt[File]('i', "input-file")
        .required()
        .text("Blockchain data file name")
        .action((f, c) => c.copy(blockchainFile = f)),
      opt[Int]('h', "height")
        .text("Import to height")
        .action((h, c) => c.copy(importHeight = h))
        .validate(h => if (h > 0) success else failure("Import height must be > 0")),
      opt[String]('f', "format")
        .text("Blockchain data file format")
        .action((f, c) => c.copy(format = f))
        .valueName(s"<${Formats.importerList.mkString("|")}> (default is ${Formats.default})")
        .validate {
          case f if Formats.isSupportedInImporter(f) => success
          case f                                     => failure(s"Unsupported format: $f")
        },
      opt[Unit]('n', "no-verify")
        .text("Disable signatures verification")
        .action((n, c) => c.copy(verify = false)),
      help("help").hidden()
    )
  }
}
