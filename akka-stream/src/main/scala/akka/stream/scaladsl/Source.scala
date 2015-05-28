/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.{ ActorRef, Cancellable, Props }
import akka.stream._
import akka.stream.impl.Stages.{ MaterializingStageFactory, StageModule }
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.{ EmptyPublisher, ErrorPublisher, SynchronousIterablePublisher, _ }
import akka.stream.stage.{ Context, PushPullStage, SyncDirective, TerminationDirective }
import org.reactivestreams.{ Publisher, Subscriber }

import akka.stream.{ SourceShape, Inlet, Outlet }
import akka.stream.impl.StreamLayout.{ EmptyModule, Module }
import akka.stream.stage.{ TerminationDirective, Directive, Context, PushPullStage }
import scala.annotation.unchecked.uncheckedVariance
import scala.language.higherKinds
import akka.actor.Props
import akka.stream.impl.{ EmptyPublisher, ErrorPublisher, SynchronousIterablePublisher }
import org.reactivestreams.Publisher
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, Promise }
import scala.language.higherKinds
import scala.concurrent.{ ExecutionContext, Future }
import akka.stream.{ FlowMaterializer, Graph }
import akka.stream.impl._
import akka.actor.Cancellable
import akka.actor.ActorRef
import scala.concurrent.Promise
import org.reactivestreams.Subscriber
import akka.stream.stage.SyncDirective
import akka.stream.OverflowStrategy
import akka.stream.OperationAttributes

/**
 * A `Source` is a set of stream processing steps that has one open output. It can comprise
 * any number of internal sources and transformations that are wired together, or it can be
 * an “atomic” source, e.g. from a collection or a file. Materialization turns a Source into
 * a Reactive Streams `Publisher` (at least conceptually).
 */
final class Source[+Out, +Mat](private[stream] override val module: Module)
  extends FlowOps[Out, Mat] with Graph[SourceShape[Out], Mat] {

  override type Repr[+O, +M] = Source[O, M]

  override val shape: SourceShape[Out] = module.shape.asInstanceOf[SourceShape[Out]]

  /**
   * Transform this [[akka.stream.scaladsl.Source]] by appending the given processing stages.
   */
  def via[T, Mat2](flow: Graph[FlowShape[Out, T], Mat2]): Source[T, Mat] = viaMat(flow)(Keep.left)

  /**
   * Transform this [[akka.stream.scaladsl.Source]] by appending the given processing stages.
   */
  def viaMat[T, Mat2, Mat3](flow: Graph[FlowShape[Out, T], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): Source[T, Mat3] = {
    if (flow.module.isInstanceOf[Stages.Identity]) this.asInstanceOf[Source[T, Mat3]]
    else {
      val flowCopy = flow.module.carbonCopy
      new Source(
        module
          .growConnect(flowCopy, shape.outlet, flowCopy.shape.inlets.head, combine)
          .replaceShape(SourceShape(flowCopy.shape.outlets.head)))
    }
  }

  /**
   * Connect this [[akka.stream.scaladsl.Source]] to a [[akka.stream.scaladsl.Sink]],
   * concatenating the processing steps of both.
   */
  def to[Mat2](sink: Graph[SinkShape[Out], Mat2]): RunnableFlow[Mat] = toMat(sink)(Keep.left)

  /**
   * Connect this [[akka.stream.scaladsl.Source]] to a [[akka.stream.scaladsl.Sink]],
   * concatenating the processing steps of both.
   */
  def toMat[Mat2, Mat3](sink: Graph[SinkShape[Out], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): RunnableFlow[Mat3] = {
    val sinkCopy = sink.module.carbonCopy
    RunnableFlow(module.growConnect(sinkCopy, shape.outlet, sinkCopy.shape.inlets.head, combine))
  }

  /**
   * Transform only the materialized value of this Source, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: Mat ⇒ Mat2): Repr[Out, Mat2] =
    new Source(module.transformMaterializedValue(f.asInstanceOf[Any ⇒ Any]))

  /** INTERNAL API */
  override private[scaladsl] def andThen[U](op: StageModule): Repr[U, Mat] = {
    // No need to copy here, op is a fresh instance
    new Source(
      module
        .growConnect(op, shape.outlet, op.inPort)
        .replaceShape(SourceShape(op.outPort)))
  }

  override private[scaladsl] def andThenMat[U, Mat2](op: MaterializingStageFactory): Repr[U, Mat2] = {
    new Source(
      module
        .growConnect(op, shape.outlet, op.inPort, Keep.right)
        .replaceShape(SourceShape(op.outPort)))
  }

  /**
   * Connect this `Source` to a `Sink` and run it. The returned value is the materialized value
   * of the `Sink`, e.g. the `Publisher` of a [[akka.stream.scaladsl.Sink#publisher]].
   */
  def runWith[Mat2](sink: Graph[SinkShape[Out], Mat2])(implicit materializer: FlowMaterializer): Mat2 = toMat(sink)(Keep.right).run()

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   */
  def runFold[U](zero: U)(f: (U, Out) ⇒ U)(implicit materializer: FlowMaterializer): Future[U] =
    runWith(Sink.fold(zero)(f))

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  def runForeach(f: Out ⇒ Unit)(implicit materializer: FlowMaterializer): Future[Unit] = runWith(Sink.foreach(f))

  /**
   * Concatenates a second source so that the first element
   * emitted by that source is emitted after the last element of this
   * source.
   */
  def concat[Out2 >: Out, M](second: Graph[SourceShape[Out2], M]): Source[Out2, (Mat, M)] = concatMat(second)(Keep.both)

  /**
   * Concatenates a second source so that the first element
   * emitted by that source is emitted after the last element of this
   * source.
   */
  def concatMat[Out2 >: Out, Mat2, Mat3](second: Graph[SourceShape[Out2], Mat2])(
    combine: (Mat, Mat2) ⇒ Mat3): Source[Out2, Mat3] = Source.concatMat(this, second)(combine)

  /**
   * Concatenates a second source so that the first element
   * emitted by that source is emitted after the last element of this
   * source.
   *
   * This is a shorthand for [[concat]]
   */
  def ++[Out2 >: Out, M](second: Graph[SourceShape[Out2], M]): Source[Out2, (Mat, M)] = concat(second)

  override def withAttributes(attr: OperationAttributes): Repr[Out, Mat] =
    new Source(module.withAttributes(attr).wrap())

  override def named(name: String): Repr[Out, Mat] = withAttributes(OperationAttributes.name(name))

  /** Converts this Scala DSL element to it's Java DSL counterpart. */
  def asJava: javadsl.Source[Out, Mat] = new javadsl.Source(this)

}

object Source extends SourceApply {

  private[stream] def apply[Out, Mat](module: SourceModule[Out, Mat]): Source[Out, Mat] =
    new Source(module)

  /** INTERNAL API */
  private[stream] def shape[T](name: String): SourceShape[T] = SourceShape(new Outlet(name + ".out"))

  /**
   * Helper to create [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def apply[T](publisher: Publisher[T]): Source[T, Unit] =
    new Source(new PublisherSource(publisher, DefaultAttributes.publisherSource, shape("PublisherSource")))

  /**
   * Helper to create [[Source]] from `Iterator`.
   * Example usage: `Source(() => Iterator.from(0))`
   *
   * Start a new `Source` from the given function that produces anIterator.
   * The produced stream of elements will continue until the iterator runs empty
   * or fails during evaluation of the `next()` method.
   * Elements are pulled out of the iterator in accordance with the demand coming
   * from the downstream transformation steps.
   */
  def apply[T](f: () ⇒ Iterator[T]): Source[T, Unit] = {
    apply(new immutable.Iterable[T] {
      override def iterator: Iterator[T] = f()
    })
  }

  /**
   * A graph with the shape of a source logically is a source, this method makes
   * it so also in type.
   */
  // TODO optimize if no wrapping needed
  def wrap[T, M](g: Graph[SourceShape[T], M]): Source[T, M] = new Source(g.module)

  /**
   * Helper to create [[Source]] from `Iterable`.
   * Example usage: `Source(Seq(1,2,3))`
   *
   * Starts a new `Source` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def apply[T](iterable: immutable.Iterable[T]): Source[T, Unit] = {
    Source.single(()).mapConcat((_: Unit) ⇒ iterable).withAttributes(DefaultAttributes.iterableSource)
  }

  /**
   * Start a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `Future` is completed with a failure.
   */
  def apply[T](future: Future[T]): Source[T, Unit] =
    new Source(new FutureSource(future, DefaultAttributes.futureSource, shape("FutureSource")))

  /**
   * Elements are emitted periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def apply[T](initialDelay: FiniteDuration, interval: FiniteDuration, tick: T): Source[T, Cancellable] =
    new Source(new TickSource(initialDelay, interval, tick, DefaultAttributes.tickSource, shape("TickSource")))

  /**
   * Create a `Source` with one element.
   * Every connected `Sink` of this stream will see an individual stream consisting of one element.
   */
  def single[T](element: T): Source[T, Unit] =
    apply(SynchronousIterablePublisher(List(element), "SingleSource")).withAttributes(DefaultAttributes.singleSource) // FIXME optimize

  /**
   * Create a `Source` that will continually emit the given element.
   */
  def repeat[T](element: T): Source[T, Unit] =
    apply(() ⇒ Iterator.continually(element)).withAttributes(DefaultAttributes.repeat) // FIXME optimize

  /**
   * A `Source` with no elements, i.e. an empty stream that is completed immediately for every connected `Sink`.
   */
  def empty[T]: Source[T, Unit] = _empty
  private[this] val _empty: Source[Nothing, Unit] = apply(EmptyPublisher).withAttributes(DefaultAttributes.emptySource)

  /**
   * Create a `Source` with no elements, which does not complete its downstream,
   * until externally triggered to do so.
   *
   * It materializes a [[scala.concurrent.Promise]] which will be completed
   * when the downstream stage of this source cancels. This promise can also
   * be used to externally trigger completion, which the source then signalls
   * to its downstream.
   */
  def lazyEmpty[T]: Source[T, Promise[Unit]] =
    new Source(new LazyEmptySource[T](DefaultAttributes.lazyEmptySource, shape("LazyEmptySource")))

  /**
   * Create a `Source` that immediately ends the stream with the `cause` error to every connected `Sink`.
   */
  def failed[T](cause: Throwable): Source[T, Unit] =
    apply(ErrorPublisher(cause, "FailedSource")).withAttributes(DefaultAttributes.failedSource)

  /**
   * Concatenates two sources so that the first element
   * emitted by the second source is emitted after the last element of the first
   * source.
   */
  def concat[T, Mat1, Mat2](source1: Graph[SourceShape[T], Mat1], source2: Graph[SourceShape[T], Mat2]): Source[T, (Mat1, Mat2)] =
    concatMat(source1, source2)(Keep.both).withAttributes(DefaultAttributes.concatSource)

  /**
   * Concatenates two sources so that the first element
   * emitted by the second source is emitted after the last element of the first
   * source.
   */
  def concatMat[T, Mat1, Mat2, Mat3](source1: Graph[SourceShape[T], Mat1], source2: Graph[SourceShape[T], Mat2])(
    combine: (Mat1, Mat2) ⇒ Mat3): Source[T, Mat3] =
    wrap(FlowGraph.partial(source1, source2)(combine) { implicit b ⇒
      (s1, s2) ⇒
        import FlowGraph.Implicits._
        val c = b.add(Concat[T]())
        s1.outlet ~> c.in(0)
        s2.outlet ~> c.in(1)
        SourceShape(c.out)
    }).withAttributes(DefaultAttributes.concatMatSource)

  /**
   * Creates a `Source` that is materialized as a [[org.reactivestreams.Subscriber]]
   */
  def subscriber[T]: Source[T, Subscriber[T]] =
    new Source(new SubscriberSource[T](DefaultAttributes.subscriberSource, shape("SubscriberSource")))

  /**
   * Creates a `Source` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorPublisher]].
   */
  def actorPublisher[T](props: Props): Source[T, ActorRef] =
    new Source(new ActorPublisherSource(props, DefaultAttributes.actorPublisherSource, shape("ActorPublisherSource")))

  /**
   * Creates a `Source` that is materialized as an [[akka.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received.
   *
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received messages are dropped
   * if there is no demand from downstream. When `bufferSize` is 0 the `overflowStrategy` does
   * not matter.
   *
   * The stream can be completed successfully by sending the actor referende an [[akka.actor.Status.Success]]
   * messagein which case already buffered elements will be signalled before signalling completion,
   * or by sending a [[akka.actor.PoisonPill]] in which case completion will be signalled immediatly.
   *
   * The stream can be completed with failure by sending [[akka.actor.Status.Failure]] to the
   * actor reference. In case the Actor is still draining its internal buffer (after having received
   * an [[akka.actor.Status.Success]]) before signalling completion and it receives a [[akka.actor.Status.Failure]],
   * the failure will be signalled downstream immediatly (instead of the completion signal).
   *
   * The actor will be stopped when the stream is completed, failed or cancelled from downstream,
   * i.e. you can watch it to get notified when that happens.
   *
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def actorRef[T](bufferSize: Int, overflowStrategy: OverflowStrategy): Source[T, ActorRef] = {
    require(bufferSize >= 0, "bufferSize must be greater than or equal to 0")
    require(overflowStrategy != OverflowStrategy.Backpressure, "Backpressure overflowStrategy not supported")
    new Source(new ActorRefSource(bufferSize, overflowStrategy, DefaultAttributes.actorRefSource, shape("ActorRefSource")))
  }

}