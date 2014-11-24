package scala.slick.backend

import java.util.concurrent.atomic.{AtomicLong, AtomicBoolean}

import scala.language.existentials

import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.{Try, Success, Failure, DynamicVariable}
import scala.util.control.NonFatal
import java.io.Closeable

import org.slf4j.LoggerFactory
import org.reactivestreams._

import scala.slick.SlickException
import scala.slick.action._
import scala.slick.util.{DumpInfo, TreeDump, SlickLogger}

/** Backend for the basic database and session handling features.
  * Concrete backends like `JdbcBackend` extend this type and provide concrete
  * types for `Database`, `DatabaseFactory` and `Session`. */
trait DatabaseComponent { self =>
  protected lazy val actionLogger = new SlickLogger(LoggerFactory.getLogger(classOf[DatabaseComponent].getName+".action"))
  protected lazy val streamLogger = new SlickLogger(LoggerFactory.getLogger(classOf[DatabaseComponent].getName+".stream"))

  type This >: this.type <: DatabaseComponent
  /** The type of database objects used by this backend. */
  type Database <: DatabaseDef
  /** The type of the database factory used by this backend. */
  type DatabaseFactory <: DatabaseFactoryDef
  /** The type of session objects used by this backend. */
  type Session >: Null <: SessionDef
  /** The action effects supported by this backend. */
  type Effects <: Effect.Read with Effect.BackendType[This]

  /** The database factory */
  val Database: DatabaseFactory

  /** A database instance to which connections can be created. */
  trait DatabaseDef { this: Database =>
    /** Create a new session. The session needs to be closed explicitly by calling its close() method. */
    def createSession(): Session

    /** Free all resources allocated by Slick for this Database. */
    def close(): Unit

    /** Run an Action asynchronously and return the result as a Future. */
    final def run[R](a: Action[Effects, R, NoStream]): Future[R] = runInContext(a, new DatabaseActionContext, false)

    /** Create a `Publisher` for Reactive Streams which, when subscribed to, will run the specified
      * Action and return the result directly as a stream without buffering everything first. This
      * method is only supported for streaming Actions.
      *
      * The Publisher itself is just a stub that holds a reference to the Action and this Database.
      * The Action does not actually start to run until the call to `onSubscribe` returns, after
      * which the Subscriber is responsible for reading the full response or cancelling the
      * Subscription. The created Publisher will only serve a single Subscriber and cannot be
      * reused (because multiple runs of an Action can produce different results, which is not
      * allowed for a Publisher).
      *
      * For the purpose of combinators such as `andFinally` which can run after a stream has been
      * produced, consuming the stream is always considered to be successful, even when cancelled
      * by the Subscriber. For example, there is no way for the Subscriber to cause a rollback when
      * streaming the results of `someQuery.result.transactionally`.
      *
      * When using a JDBC back-end, all `onNext` calls are done synchronously and the ResultSet row
      * is not advanced before `onNext` returns. This allows the Subscriber to access LOB pointers
      * from within `onNext`. If streaming is interrupted due to back-pressure signaling, the next
      * row will be prefetched (in order to buffer the next result page from the server when a page
      * boundary has been reached). */
    final def stream[T](a: Action[Effects, _, Streaming[T]]): Publisher[T] = new Publisher[T] {
      private[this] val used = new AtomicBoolean()
      def subscribe(s: Subscriber[_ >: T]) =
        if(used.getAndSet(true))
          s.onError(new IllegalStateException("Database Action Publisher may not be subscribed to more than once"))
        else {
          val ctx = new StreamingDatabaseActionContext(s, DatabaseDef.this)
          val subscribed = try { s.onSubscribe(ctx); true } catch {
            case NonFatal(ex) =>
              streamLogger.warn("Subscriber.onSubscribe failed unexpectedly", ex)
              false
          }
          if(subscribed) {
            try {
              runInContext(a, ctx, true).onComplete {
                case Success(_) => ctx.tryOnComplete
                case Failure(t) => ctx.tryOnError(t)
              }(Action.sameThreadExecutionContext)
            } catch {
              case NonFatal(ex) =>
                streamLogger.warn("Database.streamInContext failed unexpectedly", ex)
                ctx.tryOnError(ex)
            }
          }
        }
    }

    /** Run an Action in an existing DatabaseActionContext. This method can be overridden in
      * subclasses to support new DatabaseActions which cannot be expressed through
      * SynchronousDatabaseAction.
      *
      * @param streaming Whether to return the result as a stream. In this case, the context must
      *                  be a `StreamingDatabaseActionContext` and the Future result should be
      *                  completed with `null` or failed after streaming has finished. This
      *                  method should not call any `Subscriber` method other than `onNext`. */
    protected[this] def runInContext[R](a: Action[Effects, R, NoStream], ctx: DatabaseActionContext, streaming: Boolean): Future[R] = {
      logAction(a, ctx)
      a match {
        case SuccessAction(v) => Future.successful(v)
        case FailureAction(t) => Future.failed(t)
        case FutureAction(f) => f
        case FlatMapAction(base, f, ec) =>
          runInContext(base, ctx, false).flatMap(v => runInContext(f(v), ctx, streaming))(ec)
        case AndThenAction(a1, a2) =>
          runInContext(a1, ctx, false).flatMap(_ => runInContext(a2, ctx, streaming))(Action.sameThreadExecutionContext)
        case ZipAction(a1, a2) =>
          runInContext(a1, ctx, false).flatMap { r1 =>
            runInContext(a2, ctx, false).map { r2 =>
              (r1, r2)
            }(Action.sameThreadExecutionContext)
          }(Action.sameThreadExecutionContext).asInstanceOf[Future[R]]
        case AndFinallyAction(a1, a2) =>
          val p = Promise[R]()
          runInContext(a1, ctx, streaming).onComplete { t1 =>
            runInContext(a2, ctx, false).onComplete { t2 =>
              if(t1.isFailure || t2.isSuccess) p.complete(t1)
              else p.complete(t2.asInstanceOf[Failure[R]])
            } (Action.sameThreadExecutionContext)
          } (Action.sameThreadExecutionContext)
          p.future
        case FailedAction(a) =>
          runInContext(a, ctx, false).failed.asInstanceOf[Future[R]]
        case AsTryAction(a) =>
          val p = Promise[R]()
          runInContext(a, ctx, false).onComplete(v => p.success(v.asInstanceOf[R]))(Action.sameThreadExecutionContext)
          p.future
        case NamedAction(a, _) =>
          runInContext(a, ctx, streaming)
        case a: SynchronousDatabaseAction[_, _, _, _] =>
          if(streaming) {
            if(a.supportsStreaming) streamSynchronousDatabaseAction(a.asInstanceOf[SynchronousDatabaseAction[This, _ <: Effect, _, _ <: NoStream]], ctx.asInstanceOf[StreamingDatabaseActionContext]).asInstanceOf[Future[R]]
            else runInContext(AndFinallyAction(AndThenAction(Action.Pin, a.nonFusedEquivalentAction), Action.Unpin), ctx, streaming)
          } else runSynchronousDatabaseAction(a.asInstanceOf[SynchronousDatabaseAction[This, _, R, NoStream]], ctx)
        case a: DatabaseAction[_, _, _, _] =>
          throw new SlickException(s"Unsupported database action $a for $this")
      }
    }

    /** Within a synchronous execution, ensure that a Session is available. */
    protected[this] final def acquireSession(ctx: DatabaseActionContext): Unit =
      if(!ctx.isPinned) ctx.currentSession = createSession()

    /** Within a synchronous execution, close the current Session unless it is pinned.
      *
      * @param discardErrors If set to true, swallow all non-fatal errors that arise while
      *        closing the Session. */
    protected[this] final def releaseSession(ctx: DatabaseActionContext, discardErrors: Boolean): Unit =
      if(!ctx.isPinned) {
        try ctx.currentSession.close() catch { case NonFatal(ex) if(discardErrors) => }
        ctx.currentSession = null
      }

    /** Run a `SynchronousDatabaseAction` on this database. */
    protected[this] def runSynchronousDatabaseAction[R](a: SynchronousDatabaseAction[This, _, R, NoStream], ctx: DatabaseActionContext): Future[R] = {
      val promise = Promise[R]()
      scheduleSynchronousDatabaseAction(new Runnable {
        def run: Unit =
          try {
            ctx.sync
            val res = try {
              acquireSession(ctx)
              val res = try a.run(ctx) catch { case NonFatal(ex) =>
                releaseSession(ctx, true)
                throw ex
              }
              releaseSession(ctx, false)
              res
            } finally { ctx.sync = 0 }
            promise.success(res)
          } catch { case NonFatal(ex) => promise.failure(ex) }
      })
      promise.future
    }

    /** Stream a `SynchronousDatabaseAction` on this database. */
    protected[this] def streamSynchronousDatabaseAction(a: SynchronousDatabaseAction[This, _ <: Effect, _, _ <: NoStream], ctx: StreamingDatabaseActionContext): Future[Null] = {
      ctx.streamingAction = a
      scheduleSynchronousStreaming(a, ctx)(null)
      ctx.streamingResultPromise.future
    }

    /** Stream a part of the results of a `SynchronousDatabaseAction` on this database. */
    protected[DatabaseComponent] def scheduleSynchronousStreaming(a: SynchronousDatabaseAction[This, _ <: Effect, _, _ <: NoStream], ctx: StreamingDatabaseActionContext)(initialState: a.StreamState): Unit =
      scheduleSynchronousDatabaseAction(new Runnable {
        def run: Unit = try {
          var totalSent = 0L
          var sessionAcquired = initialState ne null
          var state = initialState
          do {
            totalSent = 0L
            ctx.sync
            try {
              if(!sessionAcquired) {
                acquireSession(ctx)
                sessionAcquired = true
              }
              var isFirst = state eq null
              var newDemand = ctx.demand - totalSent
              if(streamLogger.isDebugEnabled)
                streamLogger.debug((if(isFirst) "Starting initial" else "Restarting ") + " streaming action, demand = " + ctx.demand)
              while(((newDemand > 0 && (state ne null)) || isFirst) && !ctx.cancelled) {
                val oldState = state
                state = null
                state = a.emitStream(ctx, newDemand, oldState)
                isFirst = false
                totalSent += newDemand
                newDemand = ctx.demand - totalSent
              }
              if(ctx.cancelled && (state ne null)) { // streaming cancelled before finishing
                val oldState = state
                state = null
                a.cancelStream(ctx, oldState)
              }
              if(state eq null) { // streaming finished and cleaned up
                releaseSession(ctx, true)
                ctx.streamingResultPromise.success(null)
              }
            } catch { case NonFatal(ex) =>
              if(state ne null) try a.cancelStream(ctx, state) catch { case NonFatal(_) => }
              if(sessionAcquired) releaseSession(ctx, true)
              throw ex
            } finally { ctx.sync = 0 }
            if(streamLogger.isDebugEnabled) {
              if(state eq null) streamLogger.debug(s"Sent up to $totalSent elements - Stream " + (if(ctx.cancelled) "cancelled" else "completely delivered"))
              else streamLogger.debug(s"Sent $totalSent elements, more available - Performing atomic state transition")
            }
            ctx.setStreamState(state)
          } while((state ne null) && ctx.delivered(totalSent))
          if(streamLogger.isDebugEnabled) {
            if(state ne null) streamLogger.debug("Suspending streaming action with continuation (more data available)")
            else streamLogger.debug("Finished streaming action")
          }
        } catch { case NonFatal(ex) => ctx.streamingResultPromise.failure(ex) }
      })


    /** Schedule a synchronous block of code from that runs a `SynchronousDatabaseAction` for
      * asynchronous execution. */
    protected[this] def scheduleSynchronousDatabaseAction(r: Runnable): Unit

    protected[this] def logAction(a: Action[_ <: Effect, _, _ <: NoStream], ctx: DatabaseActionContext): Unit = {
      if(actionLogger.isDebugEnabled && a.isLogged) {
        ctx.sequenceCounter += 1
        val logA = a.nonFusedEquivalentAction
        val aPrefix = if(a eq logA) "" else "[fused] "
        val dump = TreeDump.get(logA, prefix = "    ", firstPrefix = aPrefix, narrow = {
          case a: Action[_, _, _] => a.nonFusedEquivalentAction
          case o => o
        })
        val msg = DumpInfo.highlight("#" + ctx.sequenceCounter) + ": " + dump.substring(0, dump.length-1)
        actionLogger.debug(msg)
      }
    }

    /** Run the supplied function with a new session and automatically close the session at the end.
      * Exceptions thrown while closing the session are propagated, but only if the code block using the
      * session terminated normally. Otherwise the first exception wins. */
    def withSession[T](f: Session => T): T = {
      val s = createSession()
      var ok = false
      try {
        val res = f(s)
        ok = true
        res
      } finally {
        if(ok) s.close() // Let exceptions propagate normally
        else {
          // f(s) threw an exception, so don't replace it with an Exception from close()
          try s.close() catch { case _: Throwable => }
        }
      }
    }

    /** Run the supplied thunk with a new session and automatically close the
      * session at the end.
      * The session is stored in a dynamic (inheritable thread-local) variable
      * which can be accessed with the implicit function in
      * Database.dynamicSession. */
    def withDynSession[T](f: => T): T = withSession { s: Session => withDynamicSession(s)(f) }

    /** Run the supplied function with a new session in a transaction and automatically close the session at the end. */
    def withTransaction[T](f: Session => T): T = withSession { s => s.withTransaction(f(s)) }

    /** Run the supplied thunk with a new session in a transaction and
      * automatically close the session at the end.
      * The session is stored in a dynamic (inheritable thread-local) variable
      * which can be accessed with the implicit function in
      * Database.dynamicSession. */
    def withDynTransaction[T](f: => T): T = withDynSession { Database.dynamicSession.withTransaction(f) }
  }

  private[this] val dyn = new DynamicVariable[Session](null)

  /** Run a block of code with the specified `Session` bound to the thread-local `dynamicSession`. */
  protected def withDynamicSession[T](s: Session)(f: => T): T = dyn.withValue(s)(f)

  /** Factory methods for creating `Database` instances. */
  trait DatabaseFactoryDef {
    /** An implicit function that returns the thread-local session in a withSession block. */
    implicit def dynamicSession: Session = {
      val s = dyn.value
      if(s eq null)
        throw new SlickException("No implicit session available; dynamicSession can only be used within a withDynSession block")
      else s
    }
  }

  /** A logical session of a `Database`. The underlying database connection is created lazily on demand. */
  trait SessionDef extends Closeable {
    /** Close this Session. */
    def close(): Unit

    /** Call this method within a `withTransaction` call to roll back the current
      * transaction after `withTransaction` returns. */
    def rollback(): Unit

    /** Run the supplied function within a transaction. If the function throws an Exception
      * or the session's `rollback()` method is called, the transaction is rolled back,
      * otherwise it is committed when the function returns. */
    def withTransaction[T](f: => T): T

    /** Use this Session as the `dynamicSession` for running the supplied thunk. */
    def asDynamicSession[T](f: => T): T = withDynamicSession[T](this.asInstanceOf[Session])(f)

    /** Force an actual database session to be opened. Slick sessions are lazy, so you do not
      * get a real database connection until you need it or you call force() on the session. */
    def force(): Unit
  }

  /** The context object passed to database actions by the execution engine. */
  protected[this] class DatabaseActionContext extends ActionContext[This] {
    /** A volatile variable to enforce the happens-before relationship when executing something in
      * a synchronous action context. It is read when entering the context and written when leaving
      * so that all writes to non-volatile variables within the context are visible to the next
      * synchronous execution. */
    @volatile private[DatabaseComponent] var sync = 0

    private[DatabaseComponent] var currentSession: Session = null

    @volatile private[DatabaseComponent] var sequenceCounter = 0

    def session: Session = currentSession
  }

  /** A special DatabaseActionContext for streaming execution. */
  protected[this] class StreamingDatabaseActionContext(subscriber: Subscriber[_], database: Database) extends DatabaseActionContext with StreamingActionContext[This] with Subscription {
    /** Whether the Subscriber has been signaled with `onComplete` or `onError`. */
    private[this] val finished = new AtomicBoolean

    /** The total number of elements requested. This variable is only used for ensuring that clause
      * 3.17 of the Reactive Streams spec is not violated. It is accessed from within `request`
      * which may be called from `onNext` and `onSubscribe` (3.2) which both run in a synchronous
      * action context and are therefore safe to be called without external synchronization. If
      * `request` is not only called from event handling methods or, alternatively, from a single
      * client thread, the client must synchronize access on its own (2.7). */
    private[this] var requested = 0L

    /** The total number of elements requested and not yet marked as delivered by the synchronous
      * streaming action. Whenever this value drops to 0, streaming is suspended. When it is raised
      * up from 0 in `request`, streaming is scheduled to be restarted. */
    private[this] val remaining = new AtomicLong

    /** The state for a suspended streaming action */
    @volatile private[this] var streamState: AnyRef = null

    /** The streaming action which may need to be continued with the suspended state */
    @volatile private[DatabaseComponent] var streamingAction: SynchronousDatabaseAction[This, _ <: Effect, _, _ <: NoStream] = null

    @volatile private[this] var cancelRequested = false

    /** The Promise to complete when streaming has finished. */
    val streamingResultPromise = Promise[Null]()

    /** Indicate that the specified number of elements has been delivered. Returns true if there
      * is more demand, false if streaming should be suspended. This is an atomic operation. It
      * must only be called from the synchronous action context which performs the streaming. */
    def delivered(num: Long): Boolean = remaining.addAndGet(-num) > 0

    /** Get the current demand that has not yet been marked as delivered. */
    def demand: Long = remaining.get()

    /** Whether the stream has been cancelled by the Subscriber */
    def cancelled: Boolean = cancelRequested

    def emit(v: Any): Unit = subscriber.asInstanceOf[Subscriber[Any]].onNext(v)

    def tryOnComplete: Unit = if(!finished.getAndSet(true) && !cancelRequested) {
      if(streamLogger.isDebugEnabled) streamLogger.debug("Signaling onComplete()")
      try subscriber.onComplete() catch {
        case NonFatal(ex) => streamLogger.warn("Subscriber.onComplete failed unexpectedly", ex)
      }
    }

    def tryOnError(t: Throwable): Unit = if(!finished.getAndSet(true)) {
      if(streamLogger.isDebugEnabled) streamLogger.debug(s"Signaling onError($t)")
      try subscriber.onError(t) catch {
        case NonFatal(ex) => streamLogger.warn("Subscriber.onError failed unexpectedly", ex)
      }
    }

    /** Set a stream state for continuing the streaming operation. Must only be called from a
      * synchronous action context. */
    def setStreamState(s: AnyRef): Unit = streamState = s

    /** Restart a suspended streaming action. Must only be called from the Subscriber context. */
    def restartStreaming: Unit = {
      val s = streamState
      if(s ne null) {
        streamState = null
        if(streamLogger.isDebugEnabled) streamLogger.debug("Scheduling stream continuation after transition from demand = 0")
        val a = streamingAction
        database.scheduleSynchronousStreaming(a, this)(s.asInstanceOf[a.StreamState])
      } else {
        if(streamLogger.isDebugEnabled) streamLogger.debug("Saw transition from demand = 0, but no stream continuation available")
      }
    }

    ////////////////////////////////////////////////////////////////////////// Subscription methods

    def request(l: Long): Unit = if(!cancelRequested) {
      if(l <= 0)
        throw new IllegalArgumentException("Requested count must not be <= 0 (see Reactive Streams spec, 3.9)")
      else if(requested + l < 0)
        tryOnError(new IllegalStateException("Total requested count must not exceed 2^63-1 (see Reactive Streams spec, 3.17)"))
      else {
        requested += l
        if(!cancelRequested && remaining.getAndAdd(l) == 0L) restartStreaming
      }
    }

    def cancel: Unit = if(!cancelRequested) {
      cancelRequested = true
      // Restart streaming because cancelling requires closing the result set and the session from
      // within a synchronous action context. This will also complete the result Promise and thus
      // allow the rest of the scheduled Action to run.
      if(remaining.getAndSet(Long.MaxValue) == 0L) restartStreaming
    }
  }
}
