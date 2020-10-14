/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.query

import java.util.concurrent.atomic.AtomicInteger

import akka.pattern.ask
import akka.persistence.journal.{EventSeq, ReadEventAdapter, Tagged, WriteEventAdapter}
import akka.persistence.postgres.query.EventAdapterTest.TestFailingEventAdapter.NumberOfFailures
import akka.persistence.postgres.util.Schema.{NestedPartitions, Partitioned, Plain, SchemaType}
import akka.persistence.query.{EventEnvelope, NoOffset, Sequence}

import scala.concurrent.duration._

object EventAdapterTest {
  trait Event {
    def value: String
    def adapted = EventAdapted(value)
  }

  case class SimpleEvent(value: String) extends Event

  case class TaggedEvent(event: Event, tag: String)

  case class TaggedAsyncEvent(event: Event, tag: String)

  case class BrokenEvent(value: String) extends Event

  case class EventAdapted(value: String) {
    def restored = EventRestored(value)
  }

  case class EventRestored(value: String)

  class TestReadEventAdapter extends ReadEventAdapter {
    override def fromJournal(event: Any, manifest: String): EventSeq = event match {
      case e: EventAdapted => EventSeq.single(e.restored)
    }
  }

  class TestWriteEventAdapter extends WriteEventAdapter {
    override def manifest(event: Any): String = ""

    override def toJournal(event: Any): Any = event match {
      case e: Event                        => e.adapted
      case TaggedEvent(e: Event, tag)      => Tagged(e.adapted, Set(tag))
      case TaggedAsyncEvent(e: Event, tag) => Tagged(e.adapted, Set(tag))
      case _                               => event
    }
  }

  class TestFailingEventAdapter extends ReadEventAdapter {
    private val errorCountDownLatch = new AtomicInteger(NumberOfFailures)

    override def fromJournal(event: Any, manifest: String): EventSeq = {
      val count = errorCountDownLatch.getAndDecrement()
      if (count <= 0)
        EventSeq.single(event)
      else throw new IllegalStateException(s"Fake adapter exception [$count]")
    }
  }

  object TestFailingEventAdapter {
    val NumberOfFailures = 2
  }
}

/**
 * Tests that check persistence queries when event adapter is configured for persisted event.
 */
abstract class EventAdapterTest(val schemaType: SchemaType)
    extends QueryTestSpec(schemaType.configName) {
  import EventAdapterTest._

  final val NoMsgTime: FiniteDuration = 100.millis

  it should "apply event adapter when querying events for actor with pid 'my-1'" in withActorSystem { implicit system =>
    val journalOps = new ScalaPostgresReadJournalOperations(system)
    withTestActors() { (actor1, actor2, actor3) =>
      journalOps.withEventsByPersistenceId()("my-1", 0) { tp =>
        tp.request(10)
        tp.expectNoMessage(100.millis)

        actor1 ! SimpleEvent("1")
        tp.expectNext(ExpectNextTimeout, EventEnvelope(Sequence(1), "my-1", 1, EventRestored("1")))
        tp.expectNoMessage(100.millis)

        actor1 ! SimpleEvent("2")
        tp.expectNext(ExpectNextTimeout, EventEnvelope(Sequence(2), "my-1", 2, EventRestored("2")))
        tp.expectNoMessage(100.millis)
        tp.cancel()
      }
    }
  }

  it should "apply event adapters when querying events by tag from an offset" in withActorSystem { implicit system =>
    val journalOps = new ScalaPostgresReadJournalOperations(system)
    withTestActors(replyToMessages = true) { (actor1, actor2, actor3) =>
      (actor1 ? TaggedEvent(SimpleEvent("1"), "event")).futureValue
      (actor2 ? TaggedEvent(SimpleEvent("2"), "event")).futureValue
      (actor3 ? TaggedEvent(SimpleEvent("3"), "event")).futureValue

      eventually {
        journalOps.countJournal.futureValue shouldBe 3
      }

      journalOps.withEventsByTag(10.seconds)("event", Sequence(1)) { tp =>
        tp.request(Int.MaxValue)
        tp.expectNext(EventEnvelope(Sequence(2), "my-2", 1, EventRestored("2")))
        tp.expectNext(EventEnvelope(Sequence(3), "my-3", 1, EventRestored("3")))
        tp.expectNoMessage(NoMsgTime)

        actor1 ? TaggedEvent(SimpleEvent("1"), "event")
        tp.expectNext(EventEnvelope(Sequence(4), "my-1", 2, EventRestored("1")))
        tp.cancel()
        tp.expectNoMessage(NoMsgTime)
      }
    }
  }

  it should "apply event adapters when querying current events for actors" in withActorSystem { implicit system =>
    val journalOps = new ScalaPostgresReadJournalOperations(system)
    withTestActors() { (actor1, actor2, actor3) =>
      actor1 ! SimpleEvent("1")
      actor1 ! SimpleEvent("2")
      actor1 ! SimpleEvent("3")

      eventually {
        journalOps.countJournal.futureValue shouldBe 3
      }

      journalOps.withCurrentEventsByPersistenceId()("my-1", 1, 1) { tp =>
        tp.request(Int.MaxValue).expectNext(EventEnvelope(Sequence(1), "my-1", 1, EventRestored("1"))).expectComplete()
      }

      journalOps.withCurrentEventsByPersistenceId()("my-1", 2, 2) { tp =>
        tp.request(Int.MaxValue).expectNext(EventEnvelope(Sequence(2), "my-1", 2, EventRestored("2"))).expectComplete()
      }

      journalOps.withCurrentEventsByPersistenceId()("my-1", 3, 3) { tp =>
        tp.request(Int.MaxValue).expectNext(EventEnvelope(Sequence(3), "my-1", 3, EventRestored("3"))).expectComplete()
      }

      journalOps.withCurrentEventsByPersistenceId()("my-1", 2, 3) { tp =>
        tp.request(Int.MaxValue)
          .expectNext(EventEnvelope(Sequence(2), "my-1", 2, EventRestored("2")))
          .expectNext(EventEnvelope(Sequence(3), "my-1", 3, EventRestored("3")))
          .expectComplete()
      }
    }
  }

  it should "apply event adapters when querying all current events by tag" in withActorSystem { implicit system =>
    val journalOps = new ScalaPostgresReadJournalOperations(system)
    withTestActors(replyToMessages = true) { (actor1, actor2, actor3) =>
      (actor1 ? TaggedEvent(SimpleEvent("1"), "event")).futureValue
      (actor2 ? TaggedEvent(SimpleEvent("2"), "event")).futureValue
      (actor3 ? TaggedEvent(SimpleEvent("3"), "event")).futureValue

      eventually {
        journalOps.countJournal.futureValue shouldBe 3
      }

      journalOps.withCurrentEventsByTag()("event", NoOffset) { tp =>
        tp.request(Int.MaxValue)
        tp.expectNextPF { case EventEnvelope(Sequence(1), _, _, EventRestored("1")) => }
        tp.expectNextPF { case EventEnvelope(Sequence(2), _, _, EventRestored("2")) => }
        tp.expectNextPF { case EventEnvelope(Sequence(3), _, _, EventRestored("3")) => }
        tp.expectComplete()
      }

      journalOps.withCurrentEventsByTag()("event", Sequence(0)) { tp =>
        tp.request(Int.MaxValue)
        tp.expectNextPF { case EventEnvelope(Sequence(1), _, _, EventRestored("1")) => }
        tp.expectNextPF { case EventEnvelope(Sequence(2), _, _, EventRestored("2")) => }
        tp.expectNextPF { case EventEnvelope(Sequence(3), _, _, EventRestored("3")) => }
        tp.expectComplete()
      }

      journalOps.withCurrentEventsByTag()("event", Sequence(1)) { tp =>
        tp.request(Int.MaxValue)
        tp.expectNextPF { case EventEnvelope(Sequence(2), _, _, EventRestored("2")) => }
        tp.expectNextPF { case EventEnvelope(Sequence(3), _, _, EventRestored("3")) => }
        tp.expectComplete()
      }

      journalOps.withCurrentEventsByTag()("event", Sequence(2)) { tp =>
        tp.request(Int.MaxValue)
        tp.expectNextPF { case EventEnvelope(Sequence(3), _, _, EventRestored("3")) => }
        tp.expectComplete()
      }

      journalOps.withCurrentEventsByTag()("event", Sequence(3)) { tp =>
        tp.request(Int.MaxValue)
        tp.expectComplete()
      }
    }
  }
}

class NestedPartitionsScalaEventAdapterTest extends EventAdapterTest(NestedPartitions)

class PartitionedScalaEventAdapterTest extends EventAdapterTest(Partitioned)

class PlainScalaEventAdapterTest extends EventAdapterTest(Plain)
