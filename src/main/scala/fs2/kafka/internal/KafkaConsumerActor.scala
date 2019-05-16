/*
 * Copyright 2018-2019 OVO Energy Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2.kafka.internal

import cats.data.{Chain, NonEmptyList, NonEmptyVector}
import cats.effect.{ConcurrentEffect, IO, Timer}
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import fs2.Chunk
import fs2.concurrent.Queue
import fs2.kafka._
import fs2.kafka.internal.instances._
import fs2.kafka.internal.KafkaConsumerActor._
import fs2.kafka.internal.LogEntry._
import fs2.kafka.internal.syntax._
import java.time.Duration
import java.util
import java.util.regex.Pattern
import org.apache.kafka.clients.consumer.{
  ConsumerConfig,
  ConsumerRebalanceListener,
  OffsetCommitCallback,
  OffsetAndMetadata
}
import org.apache.kafka.common.TopicPartition
import scala.collection.immutable.SortedSet
import scala.collection.JavaConverters._

/**
  * [[KafkaConsumerActor]] wraps a Java `KafkaConsumer` and works similar to
  * a traditional actor, in the sense that it receives requests one at-a-time
  * via a queue, which are received as calls to the `handle` function. `Poll`
  * requests are scheduled at a fixed interval and, when handled, calls the
  * `KafkaConsumer#poll` function, allowing the Java consumer to perform
  * necessary background functions, and to return fetched records.<br>
  * <br>
  * The actor receives `Fetch` requests for topic-partitions for which there
  * is demand. The actor then attempts to fetch records for topic-partitions
  * where there is a `Fetch` request. For topic-partitions where there is no
  * request, no attempt to fetch records is made. This effectively enables
  * backpressure, as long as `Fetch` requests are only issued when there
  * is more demand.
  */
private[kafka] final class KafkaConsumerActor[F[_], K, V](
  settings: ConsumerSettings[F, K, V],
  ref: Ref[F, State[F, K, V]],
  requests: Queue[F, Request[F, K, V]],
  withConsumer: WithConsumer[F]
)(
  implicit F: ConcurrentEffect[F],
  logging: Logging[F],
  jitter: Jitter[F],
  timer: Timer[F]
) {
  import logging._

  private[this] type ConsumerRecords =
    Map[TopicPartition, NonEmptyVector[CommittableMessage[F, K, V]]]

  private[this] val consumerGroupId: Option[String] =
    settings.properties.get(ConsumerConfig.GROUP_ID_CONFIG)

  private[this] val consumerRebalanceListener: ConsumerRebalanceListener =
    new ConsumerRebalanceListener {
      override def onPartitionsRevoked(partitions: util.Collection[TopicPartition]): Unit =
        F.toIO(revoked(partitions.toSortedSet)).unsafeRunSync

      override def onPartitionsAssigned(partitions: util.Collection[TopicPartition]): Unit =
        F.toIO(assigned(partitions.toSortedSet)).unsafeRunSync
    }

  private[this] def subscribe(
    topics: NonEmptyList[String],
    deferred: Deferred[F, Either[Throwable, Unit]]
  ): F[Unit] = {
    val subscribe =
      withConsumer { consumer =>
        F.delay {
          consumer.subscribe(
            topics.toList.asJava,
            consumerRebalanceListener
          )
        }.attempt
      }

    subscribe
      .flatTap {
        case Left(_) => F.unit
        case Right(_) =>
          ref
            .updateAndGet(_.asSubscribed)
            .log(SubscribedTopics(topics, _))
      }
      .flatMap(deferred.complete)
  }

  private[this] def subscribe(
    pattern: Pattern,
    deferred: Deferred[F, Either[Throwable, Unit]]
  ): F[Unit] = {
    val subscribe =
      withConsumer { consumer =>
        F.delay {
          consumer.subscribe(
            pattern,
            consumerRebalanceListener
          )
        }.attempt
      }

    subscribe
      .flatTap {
        case Left(_) => F.unit
        case Right(_) =>
          ref
            .updateAndGet(_.asSubscribed)
            .log(SubscribedPattern(pattern, _))
      }
      .flatMap(deferred.complete)
  }

  private[this] def fetch(
    partition: TopicPartition,
    streamId: Int,
    deferred: Deferred[F, (Chunk[CommittableMessage[F, K, V]], FetchCompletedReason)]
  ): F[Unit] = {
    val assigned =
      withConsumer { consumer =>
        F.delay(consumer.assignment.contains(partition))
      }

    def storeFetch =
      ref
        .modify { state =>
          val (newState, oldFetch) =
            state.withFetch(partition, streamId, deferred)
          (newState, (newState, oldFetch))
        }
        .flatMap {
          case (newState, oldFetch) =>
            log(StoredFetch(partition, deferred, newState)) >>
              oldFetch.fold(F.unit) { fetch =>
                fetch.completeRevoked(Chunk.empty) >>
                  log(RevokedPreviousFetch(partition, streamId))
              }
        }

    def completeRevoked =
      deferred.complete((Chunk.empty, FetchCompletedReason.TopicPartitionRevoked))

    assigned.ifM(storeFetch, completeRevoked)
  }

  private[this] def commitAsync(request: Request.Commit[F, K, V]): F[Unit] = {
    val commit =
      withConsumer { consumer =>
        F.delay {
          consumer.commitAsync(
            request.offsets.asJava,
            new OffsetCommitCallback {
              override def onComplete(
                offsets: util.Map[TopicPartition, OffsetAndMetadata],
                exception: Exception
              ): Unit = {
                val result = Option(exception).toLeft(())
                val complete = request.deferred.complete(result)
                F.runAsync(complete)(_ => IO.unit).unsafeRunSync
              }
            }
          )
        }.attempt
      }

    commit.flatMap {
      case Right(()) => F.unit
      case Left(e)   => request.deferred.complete(Left(e))
    }
  }

  private[this] def commit(request: Request.Commit[F, K, V]): F[Unit] =
    ref.get.flatMap { state =>
      if (state.rebalancing) {
        ref
          .updateAndGet(_.withPendingCommit(request))
          .log(StoredPendingCommit(request, _))
      } else commitAsync(request)
    }

  private[this] def assigned(assigned: SortedSet[TopicPartition]): F[Unit] =
    ref
      .updateAndGet(_.withRebalancing(false))
      .flatMap { state =>
        log(AssignedPartitions(assigned, state)) >>
          state.onRebalances.foldLeft(F.unit)(_ >> _.onAssigned(assigned))
      }

  private[this] def revoked(revoked: SortedSet[TopicPartition]): F[Unit] =
    ref
      .updateAndGet(_.withRebalancing(true))
      .flatMap { state =>
        val fetches = state.fetches.keySetStrict
        val revokedFetches = revoked intersect fetches

        val logRevoked =
          log(RevokedPartitions(revoked, state))

        val completeWithoutRecords =
          if (revokedFetches.nonEmpty) {
            state.fetches
              .filterKeysStrictValuesList(revokedFetches)
              .traverse(_.values.toList.traverse(_.completeRevoked(Chunk.empty))) >>
              ref
                .updateAndGet(_.withoutFetches(revokedFetches))
                .log(RevokedFetchesWithoutRecords(revokedFetches, _))
          } else F.unit

        val onRevoked =
          state.onRebalances.foldLeft(F.unit)(_ >> _.onRevoked(revoked))

        logRevoked >> completeWithoutRecords >> onRevoked
      }

  private[this] def assignment(
    deferred: Deferred[F, Either[Throwable, SortedSet[TopicPartition]]],
    onRebalance: Option[OnRebalance[F, K, V]]
  ): F[Unit] =
    ref.get.flatMap { state =>
      val assigned: F[Either[Throwable, SortedSet[TopicPartition]]] =
        if (state.subscribed) withConsumer { consumer =>
          F.delay(Right(consumer.assignment.toSortedSet))
        } else F.pure(Left(NotSubscribedException()))

      val withOnRebalance =
        onRebalance.fold(F.unit) { on =>
          ref
            .updateAndGet(_.withOnRebalance(on))
            .log(StoredOnRebalance(on, _))
        }

      val asStreaming =
        ref.update(_.asStreaming)

      assigned.flatMap(deferred.complete) >> withOnRebalance >> asStreaming
    }

  private[this] val messageCommit: Map[TopicPartition, OffsetAndMetadata] => F[Unit] =
    offsets => {
      val commit =
        Deferred[F, Either[Throwable, Unit]].flatMap { deferred =>
          requests.enqueue1(Request.Commit(offsets, deferred)) >>
            F.race(timer.sleep(settings.commitTimeout), deferred.get.rethrow)
              .flatMap {
                case Right(_) => F.unit
                case Left(_) =>
                  F.raiseError[Unit] {
                    CommitTimeoutException(
                      settings.commitTimeout,
                      offsets
                    )
                  }
              }
        }

      commit.handleErrorWith {
        settings.commitRecovery
          .recoverCommitWith(offsets, commit)
      }
    }

  private[this] def message(
    record: ConsumerRecord[K, V],
    partition: TopicPartition
  ): CommittableMessage[F, K, V] =
    CommittableMessage(
      record = record,
      committableOffset = CommittableOffset(
        topicPartition = partition,
        consumerGroupId = consumerGroupId,
        offsetAndMetadata = new OffsetAndMetadata(
          record.offset + 1L,
          settings.recordMetadata(record)
        ),
        commit = messageCommit
      )
    )

  private[this] def records(batch: KafkaByteConsumerRecords): F[ConsumerRecords] =
    batch.partitions.toVector
      .traverse { partition =>
        NonEmptyVector
          .fromVectorUnsafe(batch.records(partition).toVector)
          .traverse { record =>
            ConsumerRecord
              .fromJava(
                record = record,
                keyDeserializer = settings.keyDeserializer,
                valueDeserializer = settings.valueDeserializer
              )
              .map(message(_, partition))
          }
          .map((partition, _))
      }
      .map(_.toMap)

  private[this] val pollTimeout: Duration =
    settings.pollTimeout.asJava

  private[this] val poll: F[Unit] = {
    def pollConsumer(state: State[F, K, V]): F[ConsumerRecords] =
      withConsumer { consumer =>
        F.suspend {
          val assigned = consumer.assignment.toSet
          val requested = state.fetches.keySetStrict

          if (requested.nonEmpty) {
            val resume = requested intersect assigned
            val pause = assigned diff resume

            if (pause.nonEmpty)
              consumer.pause(pause.asJava)

            if (resume.nonEmpty)
              consumer.resume(resume.asJava)

            records(consumer.poll(pollTimeout))
          } else {
            if (assigned.nonEmpty)
              consumer.pause(assigned.asJava)

            val batch =
              consumer.poll(Duration.ZERO)

            if (batch.isEmpty)
              F.pure(Map.empty)
            else
              F.raiseError(
                new IllegalStateException(
                  s"Received [${batch.count}] unexpected records"
                )
              )
          }
        }
      }

    def handleBatch(newRecords: ConsumerRecords): F[Unit] =
      if (newRecords.nonEmpty) {
        ref.get.flatMap { state =>
          val canBeCompleted = newRecords.keySetStrict

          val checkUnexpected = {
            val unexpected = canBeCompleted diff state.fetches.keySetStrict
            if (unexpected.isEmpty) F.unit
            else
              F.raiseError(
                new IllegalStateException(
                  s"Received unexpected records for partitions [${unexpected.mkString(", ")}]"
                )
              )
          }

          val completeFetches =
            state.fetches.filterKeysStrictList(canBeCompleted).traverse {
              case (partition, fetches) =>
                val records = Chunk.vector(newRecords(partition).toVector)
                fetches.values.toList.traverse(_.completeRecords(records))
            }

          val removeFetches =
            ref
              .updateAndGet(_.withoutFetches(canBeCompleted))
              .log(CompletedFetchesWithRecords(newRecords, _))

          checkUnexpected >> completeFetches >> removeFetches
        }
      } else F.unit

    def handlePendingCommits(initialRebalancing: Boolean): F[Unit] =
      ref.get.flatMap { state =>
        val currentRebalancing = state.rebalancing
        if (initialRebalancing && !currentRebalancing && state.pendingCommits.nonEmpty) {
          val commitPendingCommits =
            state.pendingCommits.foldLeft(F.unit)(_ >> commitAsync(_))

          val removePendingCommits =
            ref
              .updateAndGet(_.withoutPendingCommits)
              .log(CommittedPendingCommits(state.pendingCommits, _))

          commitPendingCommits >> removePendingCommits
        } else F.unit
      }

    ref.get.flatMap { state =>
      if (state.subscribed && state.streaming) {
        val initialRebalancing = state.rebalancing
        pollConsumer(state).flatMap(handleBatch) >>
          handlePendingCommits(initialRebalancing)
      } else F.unit
    }
  }

  def handle(request: Request[F, K, V]): F[Unit] =
    request match {
      case Request.Assignment(deferred, onRebalance)    => assignment(deferred, onRebalance)
      case Request.Poll()                               => poll
      case Request.SubscribeTopics(topics, deferred)    => subscribe(topics, deferred)
      case Request.SubscribePattern(pattern, deferred)  => subscribe(pattern, deferred)
      case Request.Fetch(partition, streamId, deferred) => fetch(partition, streamId, deferred)
      case request @ Request.Commit(_, _)               => commit(request)
    }
}

private[kafka] object KafkaConsumerActor {
  final case class FetchRequest[F[_], K, V](
    deferred: Deferred[F, (Chunk[CommittableMessage[F, K, V]], FetchCompletedReason)]
  ) {
    def completeRevoked(chunk: Chunk[CommittableMessage[F, K, V]]): F[Unit] =
      deferred.complete((chunk, FetchCompletedReason.TopicPartitionRevoked))

    def completeRecords(chunk: Chunk[CommittableMessage[F, K, V]]): F[Unit] =
      deferred.complete((chunk, FetchCompletedReason.FetchedRecords))

    override def toString: String =
      "FetchRequest$" + System.identityHashCode(this)
  }

  type StreamId = Int

  final case class State[F[_], K, V](
    fetches: Map[TopicPartition, Map[StreamId, FetchRequest[F, K, V]]],
    pendingCommits: Chain[Request.Commit[F, K, V]],
    onRebalances: Chain[OnRebalance[F, K, V]],
    rebalancing: Boolean,
    subscribed: Boolean,
    streaming: Boolean
  ) {
    def withOnRebalance(onRebalance: OnRebalance[F, K, V]): State[F, K, V] =
      copy(onRebalances = onRebalances append onRebalance)

    /**
      * @return (new-state, old-fetch-to-revoke)
      */
    def withFetch(
      partition: TopicPartition,
      streamId: Int,
      deferred: Deferred[F, (Chunk[CommittableMessage[F, K, V]], FetchCompletedReason)]
    ): (State[F, K, V], Option[FetchRequest[F, K, V]]) = {
      val oldPartitionFetches =
        fetches.get(partition)

      val oldPartitionFetch =
        oldPartitionFetches.flatMap(_.get(streamId))

      val newPartitionFetches =
        oldPartitionFetches
          .getOrElse(Map.empty)
          .updated(streamId, FetchRequest(deferred))

      val newFetches =
        fetches.updated(partition, newPartitionFetches)

      (copy(fetches = newFetches), oldPartitionFetch)
    }

    def withoutFetches(partitions: Set[TopicPartition]): State[F, K, V] =
      copy(fetches = fetches.filterKeysStrict(!partitions.contains(_)))

    def withPendingCommit(pendingCommit: Request.Commit[F, K, V]): State[F, K, V] =
      copy(pendingCommits = pendingCommits append pendingCommit)

    def withoutPendingCommits: State[F, K, V] =
      if (pendingCommits.isEmpty) this else copy(pendingCommits = Chain.empty)

    def withRebalancing(rebalancing: Boolean): State[F, K, V] =
      if (this.rebalancing == rebalancing) this else copy(rebalancing = rebalancing)

    def asSubscribed: State[F, K, V] =
      if (subscribed) this else copy(subscribed = true)

    def asStreaming: State[F, K, V] =
      if (streaming) this else copy(streaming = true)

    override def toString: String = {
      val fetchesString =
        fetches.toList
          .sortBy { case (tp, _) => tp }
          .mkStringAppend {
            case (append, (tp, fs)) =>
              append(tp.toString)
              append(" -> ")
              append(fs.mkString("[", ", ", "]"))
          }("", ", ", "")

      s"State(fetches = Map($fetchesString), pendingCommits = $pendingCommits, onRebalances = $onRebalances, rebalancing = $rebalancing, subscribed = $subscribed, streaming = $streaming)"
    }
  }

  object State {
    def empty[F[_], K, V]: State[F, K, V] =
      State(
        fetches = Map.empty,
        pendingCommits = Chain.empty,
        onRebalances = Chain.empty,
        rebalancing = false,
        subscribed = false,
        streaming = false
      )
  }

  sealed abstract class FetchCompletedReason {
    final def topicPartitionRevoked: Boolean = this match {
      case FetchCompletedReason.TopicPartitionRevoked => true
      case FetchCompletedReason.FetchedRecords        => false
    }
  }

  object FetchCompletedReason {
    case object TopicPartitionRevoked extends FetchCompletedReason

    case object FetchedRecords extends FetchCompletedReason
  }

  final case class OnRebalance[F[_], K, V](
    onAssigned: SortedSet[TopicPartition] => F[Unit],
    onRevoked: SortedSet[TopicPartition] => F[Unit]
  ) {
    override def toString: String =
      "OnRebalance$" + System.identityHashCode(this)
  }

  sealed abstract class Request[F[_], K, V]

  object Request {
    final case class Assignment[F[_], K, V](
      deferred: Deferred[F, Either[Throwable, SortedSet[TopicPartition]]],
      onRebalance: Option[OnRebalance[F, K, V]]
    ) extends Request[F, K, V]

    final case class Poll[F[_], K, V]() extends Request[F, K, V]

    private[this] val pollInstance: Poll[Nothing, Nothing, Nothing] =
      Poll[Nothing, Nothing, Nothing]()

    def poll[F[_], K, V]: Poll[F, K, V] =
      pollInstance.asInstanceOf[Poll[F, K, V]]

    final case class SubscribeTopics[F[_], K, V](
      topics: NonEmptyList[String],
      deferred: Deferred[F, Either[Throwable, Unit]]
    ) extends Request[F, K, V]

    final case class SubscribePattern[F[_], K, V](
      pattern: Pattern,
      deferred: Deferred[F, Either[Throwable, Unit]]
    ) extends Request[F, K, V]

    final case class Fetch[F[_], K, V](
      partition: TopicPartition,
      streamId: Int,
      deferred: Deferred[F, (Chunk[CommittableMessage[F, K, V]], FetchCompletedReason)]
    ) extends Request[F, K, V]

    final case class Commit[F[_], K, V](
      offsets: Map[TopicPartition, OffsetAndMetadata],
      deferred: Deferred[F, Either[Throwable, Unit]]
    ) extends Request[F, K, V]
  }
}
