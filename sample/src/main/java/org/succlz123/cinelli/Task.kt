package org.succlz123.cinelli

import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.PrintStream
import java.io.PrintWriter
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by succlz123 on 2018/12/8.
 */
class Task<TR> {
    private val lock = java.lang.Object()

    private var complete: Boolean = false
    private var cancelled: Boolean = false

    private var result: TR? = null
    private var error: Exception? = null

    private var errorHasBeenObserved: Boolean = false
    private var unobservedErrorNotifier: UnobservedErrorNotifier? = null

    private var thens: ArrayList<Then<TR, Unit>> = ArrayList()

    val isCompleted: Boolean
        get() = synchronized(lock) {
            return complete
        }

    val isCancelled: Boolean
        get() = synchronized(lock) {
            return cancelled
        }

    val isFaulted: Boolean
        get() = synchronized(lock) {
            return getError() != null
        }

    internal constructor ()

    private constructor(result: TR?, cancelled: Boolean) {
        if (cancelled) {
            trySetCancelled()
        } else {
            trySetResult(result)
        }
    }

    fun geResult(): TR? {
        synchronized(lock) {
            return result
        }
    }

    fun getError(): Exception? {
        synchronized(lock) {
            if (error != null) {
                errorHasBeenObserved = true
                unobservedErrorNotifier?.let {
                    it.setObserved()
                    unobservedErrorNotifier = null
                }
            }
            return error
        }
    }

    @Throws(InterruptedException::class)
    fun waitForCompletion() {
        synchronized(lock) {
            if (!isCompleted) {
                lock.wait()
            }
        }
    }

    @Throws(InterruptedException::class)
    fun waitForCompletion(duration: Long, timeUnit: TimeUnit): Boolean {
        synchronized(lock) {
            if (!isCompleted) {
                lock.wait(timeUnit.toMillis(duration))
            }
            return isCompleted
        }
    }

    fun <Out> cast(): Task<Out> {
        return this as Task<Out>
    }

    fun makeUnit(): Task<Unit> {
        return continueWithTask<Unit>({ task ->
            if (task.isCancelled) {
                return@continueWithTask cancelled()
            }
            val currentError = task.getError()
            return@continueWithTask if (currentError != null) {
                forError(currentError)
            } else {
                forNullResult()
            }
        })
    }

    @JvmOverloads
    fun <CR> continueWith(then: Then<TR, CR>, executor: Executor = IMMEDIATE, canceller: Canceller? = null): Task<CR> {
        var completed = false
        val tk = Task<CR>()
        synchronized(lock) {
            completed = isCompleted
            if (!completed) {
                thens.add { task ->
                    completeImmediately(tk, then, task, executor, canceller)
                }
            }
        }
        if (completed) {
            completeImmediately(tk, then, this, executor, canceller)
        }
        return tk
    }

    @JvmOverloads
    fun <CR> continueWithTask(
        then: Then<TR, Task<CR>>,
        executor: Executor = IMMEDIATE,
        canceller: Canceller? = null
    ): Task<CR> {
        var completed = false
        val tk = Task<CR>()
        synchronized(lock) {
            completed = this.isCompleted
            if (!completed) {
                thens.add { task ->
                    completeAfterTask(tk, then, task, executor, canceller)
                }
            }
        }
        if (completed) {
            completeAfterTask(tk, then, this, executor, canceller)
        }
        return tk
    }

    @JvmOverloads
    fun continueWhile(
        callable: Callable<Boolean>,
        then: Then<Unit, Task<Unit>>,
        executor: Executor = IMMEDIATE,
        canceller: Canceller? = null
    ): Task<Unit> {
        val capture = Capture<Then<Unit, Task<Unit>>>()
        capture.set {
            if (canceller != null && canceller.isCancellerRequested) {
                return@set cancelled()
            }
            return@set if (callable.call()) {
                forNullResult<Unit>().onSuccessTask(then, executor)
                    .onSuccessTask(capture.get(), executor)
            } else {
                forNullResult()
            }
        }
        return makeUnit().continueWithTask(capture.get(), executor)
    }

    @JvmOverloads
    fun <CR> onSuccess(
        then: Then<TR, CR>,
        executor: Executor = IMMEDIATE,
        canceller: Canceller? = null
    ): Task<CR> {
        return continueWithTask<CR>({ task ->
            if (canceller != null && canceller.isCancellerRequested) {
                return@continueWithTask cancelled()
            }
            val currentError = getError()
            return@continueWithTask when {
                currentError != null -> forError(currentError)
                task.isCancelled -> cancelled()
                else -> task.continueWith(then)
            }
        }, executor)
    }

    @JvmOverloads
    fun <CR> onSuccessTask(
        then: Then<TR, Task<CR>>,
        executor: Executor = IMMEDIATE,
        canceller: Canceller? = null
    ): Task<CR> {
        return continueWithTask<CR>({ task ->
            if (canceller != null && canceller.isCancellerRequested) {
                return@continueWithTask cancelled()
            }
            val currentError = getError()
            return@continueWithTask when {
                currentError != null -> forError(currentError)
                task.isCancelled -> cancelled()
                else -> task.continueWithTask(then)
            }
        }, executor)
    }

    private fun runContinuations() {
        synchronized(lock) {
            for (then in thens) {
                try {
                    then.invoke(this)
                } catch (e: RuntimeException) {
                    throw e
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }
            thens.clear()
        }
    }

    internal fun trySetCancelled(): Boolean {
        synchronized(lock) {
            if (complete) {
                return false
            }
            complete = true
            cancelled = true
            lock.notifyAll()
            runContinuations()
            return true
        }
    }

    internal fun trySetResult(result: TR?): Boolean {
        synchronized(lock) {
            if (complete) {
                return false
            }
            complete = true
            this@Task.result = result
            lock.notifyAll()
            runContinuations()
            return true
        }
    }

    internal fun trySetError(ex: Exception): Boolean {
        synchronized(lock) {
            if (complete) {
                return false
            }
            complete = true
            error = ex
            errorHasBeenObserved = false
            lock.notifyAll()
            runContinuations()
            if (!errorHasBeenObserved && unobservedExceptionHandler != null) {
                unobservedErrorNotifier = UnobservedErrorNotifier(this)
            }
            return true
        }
    }

    fun setCancelled() {
        if (!trySetCancelled()) {
            throw IllegalStateException("Cannot cancel a completed task.")
        }
    }

    fun seTR(result: TR?) {
        if (!trySetResult(result)) {
            throw IllegalStateException("Cannot set the result of a completed task.")
        }
    }

    fun setError(error: Exception) {
        if (!trySetError(error)) {
            throw IllegalStateException("Cannot set the error on a completed task.")
        }
    }

    companion object {
        val BACKGROUND = TaskExecutors.background
        val IMMEDIATE = TaskExecutors.immediate
        val UI_THREAD = TaskExecutors.uiThread

        private val TASK_NULL = Task(null, false)
        private val TASK_TRUE = Task(true, false)
        private val TASK_FALSE = Task(false, false)
        private val TASK_CANCELLED = Task(true, true)

        // null unless explicitly set
        @Volatile
        @JvmStatic
        var unobservedExceptionHandler: UnobservedExceptionHandler? = null

        @JvmStatic
        fun <TR> forResult(value: TR): Task<TR> {
            if (value is Boolean) {
                return (if (value) TASK_TRUE else TASK_FALSE) as Task<TR>
            }
            val tk = Task<TR>()
            tk.seTR(value)
            return tk
        }

        @JvmStatic
        fun <TR> forNullResult(): Task<TR> {
            return TASK_NULL as Task<TR>
        }

        @JvmStatic
        fun <TR> forError(error: Exception): Task<TR> {
            val tk = Task<TR>()
            tk.setError(error)
            return tk
        }

        @JvmStatic
        fun <TR> cancelled(): Task<TR> {
            return TASK_CANCELLED as Task<TR>
        }

        @JvmOverloads
        @JvmStatic
        fun <TR> callInBackground(callable: Callable<TR>, ct: Canceller? = null): Task<TR> {
            return call(callable, BACKGROUND, ct)
        }

        @JvmOverloads
        @JvmStatic
        fun <TR> call(callable: Callable<TR>, executor: Executor = IMMEDIATE, ct: Canceller? = null): Task<TR> {
            val tk = Task<TR>()
            try {
                executor.execute {
                    if (ct != null && ct.isCancellerRequested) {
                        tk.setCancelled()
                        return@execute
                    }
                    try {
                        tk.seTR(callable.call())
                    } catch (e: CancellationException) {
                        tk.setCancelled()
                    } catch (e: Exception) {
                        tk.setError(e)
                    }
                }
            } catch (e: Exception) {
                tk.setError(ExecutorException(e))
            }
            return tk
        }

        @JvmOverloads
        @JvmStatic
        fun delay(delay: Long, canceller: Canceller? = null): Task<Unit> {
            if (canceller != null && canceller.isCancellerRequested) {
                return cancelled()
            }
            if (delay <= 0) {
                return forNullResult()
            }
            val tk = Task<Unit>()
            val scheduled = TaskExecutors.scheduled.schedule({ tk.trySetResult(null) }, delay, TimeUnit.MILLISECONDS)
            canceller?.register(Runnable {
                scheduled.cancel(true)
                tk.trySetCancelled()
            })
            return tk
        }

        @JvmStatic
        fun <TR> whenAnyResult(tasks: Collection<Task<TR>>): Task<Task<TR>> {
            if (tasks.isEmpty()) {
                return forNullResult()
            }
            val firstCompleted = Task<Task<TR>>()
            val isAnyTaskComplete = AtomicBoolean(false)
            for (task in tasks) {
                task.continueWith({ it ->
                    if (isAnyTaskComplete.compareAndSet(false, true)) {
                        firstCompleted.seTR(it)
                    } else {
                        val ensureObserved = task.error
                    }
                })
            }
            return firstCompleted
        }

        @JvmStatic
        fun whenAny(tasks: Collection<Task<*>>): Task<Task<*>> {
            if (tasks.isEmpty()) {
                return forNullResult()
            }
            val firstCompleted = Task<Task<*>>()
            val isAnyTaskComplete = AtomicBoolean(false)
            for (task in tasks) {
                (task as Task<Any>).continueWith({ it ->
                    if (isAnyTaskComplete.compareAndSet(false, true)) {
                        firstCompleted.seTR(it)
                    } else {
                        val ensureObserved = task.error
                    }
                })
            }
            return firstCompleted
        }

        @JvmStatic
        fun <TR> whenAllResult(tasks: Collection<Task<TR>>): Task<List<TR>> {
            return whenAll(tasks).onSuccess({
                val list: List<TR>
                if (tasks.isEmpty()) {
                    list = emptyList()
                } else {
                    list = ArrayList()
                    for (task in tasks) {
                        task.geResult()?.let { result ->
                            list.add(result)
                        }
                    }
                }
                return@onSuccess list
            })
        }

        @JvmStatic
        fun whenAll(tasks: Collection<Task<*>>): Task<Unit> {
            if (tasks.isEmpty()) {
                return forNullResult()
            }
            val allFinished = Task<Unit>()
            val causes = ArrayList<Exception>()
            val errorLock = Any()
            val count = AtomicInteger(tasks.size)
            val isCancelled = AtomicBoolean(false)
            for (task in tasks) {
                val t = task as Task<Any>
                t.continueWith({ current ->
                    if (task.isFaulted) {
                        current.error?.let { ex ->
                            synchronized(errorLock) {
                                causes.add(ex)
                            }
                        }
                    }
                    if (task.isCancelled) {
                        isCancelled.set(true)
                    }
                    if (count.decrementAndGet() == 0) {
                        if (causes.size != 0) {
                            if (causes.size == 1) {
                                allFinished.setError(causes[0])
                            } else {
                                val error = AggregateException(String.format("%d exceptions.", causes.size), causes)
                                allFinished.setError(error)
                            }
                        } else if (isCancelled.get()) {
                            allFinished.setCancelled()
                        } else {
                            allFinished.seTR(null)
                        }
                    }
                })
            }
            return allFinished
        }

        private fun <CR, TR> completeImmediately(
            tk: Task<CR>,
            then: Then<TR, CR>,
            task: Task<TR>,
            executor: Executor,
            canceller: Canceller?
        ) {
            try {
                executor.execute(Runnable {
                    if (canceller != null && canceller.isCancellerRequested) {
                        tk.setCancelled()
                        return@Runnable
                    }
                    try {
                        val result = then.invoke(task)
                        tk.seTR(result)
                    } catch (e: CancellationException) {
                        tk.setCancelled()
                    } catch (e: Exception) {
                        tk.setError(e)
                    }
                })
            } catch (e: Exception) {
                tk.setError(ExecutorException(e))
            }
        }

        private fun <CR, TR> completeAfterTask(
            tk: Task<CR>,
            then: Then<TR, Task<CR>>,
            after: Task<TR>,
            executor: Executor,
            canceller: Canceller?
        ) {
            try {
                executor.execute(Runnable {
                    if (canceller != null && canceller.isCancellerRequested) {
                        tk.setCancelled()
                        return@Runnable
                    }
                    try {
                        val result = then.invoke(after)
                        result.continueWith({ task ->
                            if (canceller != null && canceller.isCancellerRequested) {
                                tk.setCancelled()
                                return@continueWith
                            }
                            if (task.isCancelled) {
                                tk.setCancelled()
                                return@continueWith
                            }
                            val currentError = task.getError()
                            if (currentError != null) {
                                tk.setError(currentError)
                            } else {
                                tk.seTR(task.result)
                            }
                        })
                    } catch (e: CancellationException) {
                        tk.setCancelled()
                    } catch (e: Exception) {
                        tk.setError(e)
                    }
                })
            } catch (e: Exception) {
                tk.setError(ExecutorException(e))
            }
        }
    }
}

// A function to be called after a task completes.
typealias Then<TR, CR> = (task: Task<TR>) -> CR

class Canceller : Closeable {
    private val lock = java.lang.Object()
    private val listeners = ArrayList<CancellerListener>()
    private var scheduledCancelFuture: ScheduledFuture<*>? = null
    private var cancelRequested: Boolean = false
    private var closed: Boolean = false

    val isCancellerRequested: Boolean
        get() = synchronized(lock) {
            throwIfClosed()
            return cancelRequested
        }

    fun cancel() {
        synchronized(lock) {
            throwIfClosed()
            if (cancelRequested) {
                return
            }
            cancelScheduledCancellation()
            cancelRequested = true
            val iterator = listeners.iterator()
            while (iterator.hasNext()) {
                val listener = iterator.next()
                listener.runAction()
                iterator.remove()
            }
        }
    }

    @JvmOverloads
    fun cancelAfter(delay: Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) {
        if (delay < -1L) {
            throw IllegalArgumentException("Delay must be >= -1")
        }
        if (delay == 0L) {
            cancel()
            return
        }
        synchronized(lock) {
            if (cancelRequested) {
                return
            }
            cancelScheduledCancellation()
            if (delay != -1L) {
                scheduledCancelFuture = TaskExecutors.scheduled.schedule({
                    synchronized(lock) {
                        scheduledCancelFuture = null
                    }
                    cancel()
                }, delay, timeUnit)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            cancelScheduledCancellation()
            val iterator = listeners.iterator()
            while (iterator.hasNext()) {
                val listener = iterator.next()
                listener.close()
                iterator.remove()
            }
            closed = true
        }
    }

    fun register(action: Runnable): CancellerListener {
        synchronized(lock) {
            throwIfClosed()
            val ctr = CancellerListener(action)
            if (cancelRequested) {
                ctr.runAction()
            } else {
                listeners.add(ctr)
            }
            return ctr
        }
    }

    fun unregister(listener: CancellerListener) {
        synchronized(lock) {
            throwIfClosed()
            listeners.remove(listener)
        }
    }

    @Throws(CancellationException::class)
    fun throwIfCancellationRequested() {
        synchronized(lock) {
            throwIfClosed()
            if (cancelRequested) {
                throw CancellationException()
            }
        }
    }

    private fun throwIfClosed() {
        if (closed) {
            throw IllegalStateException("Object already closed")
        }
    }

    private fun cancelScheduledCancellation() {
        scheduledCancelFuture?.cancel(true)
        scheduledCancelFuture = null
    }

    override fun toString(): String {
        return String.format(
            Locale.US, "%s@%s[cancelRequested=%s]",
            javaClass.name,
            Integer.toHexString(hashCode()),
            java.lang.Boolean.toString(isCancellerRequested)
        )
    }
}

class CancellerListener(private val action: Runnable) {
    private var closed: Boolean = false

    @Synchronized
    internal fun close() {
        closed = true
    }

    @Synchronized
    internal fun runAction() {
        throwIfClosed()
        action.run()
        closed = true
    }

    private fun throwIfClosed() {
        if (closed) {
            throw IllegalStateException("Object already closed")
        }
    }
}

class Capture<T> {
    var value: T? = null

    constructor()

    constructor(value: T) {
        this.value = value
    }

    fun set(v: T) {
        value = v
    }

    fun get(): T {
        return value as T
    }
}

class ExecutorException(e: Exception) : RuntimeException("An exception was thrown by an Executor", e)

interface UnobservedExceptionHandler {

    fun process(t: Task<*>, e: UnobservedTaskException)
}

internal class UnobservedErrorNotifier(private var task: Task<*>?) {

    protected fun finalize() {
        try {
            val faultedTask = task
            if (faultedTask != null) {
                Task.unobservedExceptionHandler?.process(faultedTask, UnobservedTaskException(faultedTask.getError()))
            }
        } catch (t: Throwable) {
        }
    }

    fun setObserved() {
        task = null
    }
}

class UnobservedTaskException(cause: Throwable?) : RuntimeException(cause)

class AggregateException(detailMessage: String, val exceptions: ArrayList<Exception>) :
    Exception(detailMessage) {

    override fun printStackTrace(err: PrintStream) {
        super.printStackTrace(err)
        var currentIndex = -1
        for (ex in exceptions) {
            err.append("\n")
            err.append("  Inner ex #")
            err.append(Integer.toString(++currentIndex))
            err.append(": ")
            ex.printStackTrace(err)
            err.append("\n")
        }
    }

    override fun printStackTrace(err: PrintWriter) {
        super.printStackTrace(err)
        var currentIndex = -1
        for (throwable in exceptions) {
            err.append("\n")
            err.append("  Inner throwable #")
            err.append(Integer.toString(++currentIndex))
            err.append(": ")
            throwable.printStackTrace(err)
            err.append("\n")
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

object TaskExecutors {
    internal val background: ExecutorService
    internal val immediate: Executor
    internal var uiThread: Executor
    internal val scheduled: ScheduledExecutorService

    private val isAndroidRuntime: Boolean
        get() {
            val javaRuntimeName = System.getProperty("java.runtime.name") ?: return false
            return javaRuntimeName.toLowerCase(Locale.US).contains("android")
        }

    init {
        if (isAndroidRuntime) {
            uiThread = AndroidUIThreadExecutor()
            background = AndroidCacheThreadPoolExecutors.create()
        } else {
            val jvmPool = Executors.newCachedThreadPool()
            uiThread = jvmPool
            background = jvmPool
        }
        scheduled = Executors.newSingleThreadScheduledExecutor()
        immediate = ImmediateExecutor()
    }
}

private class ImmediateExecutor : Executor {
    private val executionDepth = ThreadLocal<Int>()

    private fun incrementDepth(): Int {
        var oldDepth = executionDepth.get()
        if (oldDepth == null) {
            oldDepth = 0
        }
        val newDepth = oldDepth + 1
        executionDepth.set(newDepth)
        return newDepth
    }

    private fun decrementDepth(): Int {
        var oldDepth = executionDepth.get()
        if (oldDepth == null) {
            oldDepth = 0
        }
        val newDepth = oldDepth - 1
        if (newDepth == 0) {
            executionDepth.remove()
        } else {
            executionDepth.set(newDepth)
        }
        return newDepth
    }

    override fun execute(command: Runnable) {
        val depth = incrementDepth()
        try {
            if (depth <= MAX_DEPTH) {
                command.run()
            } else {
                TaskExecutors.background.execute(command)
            }
        } finally {
            decrementDepth()
        }
    }

    companion object {
        private const val MAX_DEPTH = 15
    }
}

private class AndroidUIThreadExecutor : Executor {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun execute(command: Runnable) {
        mainHandler.post(command)
    }
}

private object AndroidCacheThreadPoolExecutors {
    private val CPU_COUNT = Runtime.getRuntime().availableProcessors()
    private val CORE_POOL_SIZE = CPU_COUNT + 1
    private val MAX_POOL_SIZE = CPU_COUNT * 2 + 1
    private const val KEEP_ALIVE_TIME = 1L

    fun create(threadFactory: ThreadFactory = Executors.defaultThreadFactory()): ExecutorService {
        val executor = ThreadPoolExecutor(
            CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE_TIME,
            TimeUnit.SECONDS, LinkedBlockingQueue(), threadFactory
        )
        executor.allowCoreThreadTimeOut(true)
        return executor
    }
}
