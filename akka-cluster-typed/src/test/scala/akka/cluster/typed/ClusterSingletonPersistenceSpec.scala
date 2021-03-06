/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.cluster.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.actor.typed.TypedSpec
import akka.persistence.typed.scaladsl.PersistentActor
import akka.persistence.typed.scaladsl.PersistentActor.{ CommandHandler, Effect }
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

object ClusterSingletonPersistenceSpec {
  val config = ConfigFactory.parseString(
    """
      akka.actor.provider = cluster

      akka.remote.artery.enabled = true
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.coordinated-shutdown.terminate-actor-system = off

      akka.actor {
        serialize-messages = off
        allow-java-serialization = off
      }

      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """.stripMargin)

  sealed trait Command
  final case class Add(s: String) extends Command
  final case class Get(replyTo: ActorRef[String]) extends Command
  private final case object StopPlz extends Command

  val persistentActor: Behavior[Command] =
    PersistentActor.immutable[Command, String, String](
      persistenceId = "TheSingleton",
      initialState = "",
      commandHandler = CommandHandler((ctx, state, cmd) ⇒ cmd match {
        case Add(s) ⇒ Effect.persist(s)
        case Get(replyTo) ⇒
          replyTo ! state
          Effect.none
        case StopPlz ⇒ Effect.stop
      }),
      eventHandler = (state, evt) ⇒ if (state.isEmpty) evt else state + "|" + evt)

}

class ClusterSingletonPersistenceSpec extends TypedSpec(ClusterSingletonPersistenceSpec.config) with ScalaFutures {
  import ClusterSingletonPersistenceSpec._
  import akka.actor.typed.scaladsl.adapter._

  implicit val s = system
  implicit val testkitSettings = TestKitSettings(system)

  implicit val untypedSystem = system.toUntyped
  private val untypedCluster = akka.cluster.Cluster(untypedSystem)

  object `Typed cluster singleton with persistent actor` {

    untypedCluster.join(untypedCluster.selfAddress)

    def `01 start persistent actor`(): Unit = {
      val ref = ClusterSingleton(system).spawn(
        behavior = persistentActor,
        singletonName = "singleton",
        props = Props.empty,
        settings = ClusterSingletonSettings(system),
        terminationMessage = StopPlz)

      val p = TestProbe[String]()

      ref ! Add("a")
      ref ! Add("b")
      ref ! Add("c")
      ref ! Get(p.ref)
      p.expectMsg("a|b|c")
    }
  }

}
