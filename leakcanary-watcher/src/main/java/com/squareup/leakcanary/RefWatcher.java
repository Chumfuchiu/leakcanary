/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import java.io.File;
import java.lang.ref.ReferenceQueue;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.squareup.leakcanary.HeapDumper.RETRY_LATER;
import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static com.squareup.leakcanary.Retryable.Result.DONE;
import static com.squareup.leakcanary.Retryable.Result.RETRY;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Watches references that should become weakly reachable. When the {@link RefWatcher} detects that
 * a reference might not be weakly reachable when it should, it triggers the {@link HeapDumper}.
 *
 * <p>This class is thread-safe: you can call {@link #watch(Object)} from any thread.
 */
public final class RefWatcher {

  public static final RefWatcher DISABLED = new RefWatcherBuilder<>().build();

  private final WatchExecutor watchExecutor;
  private final DebuggerControl debuggerControl;
  private final GcTrigger gcTrigger;
  private final HeapDumper heapDumper;
  private final HeapDump.Listener heapdumpListener;
  private final HeapDump.Builder heapDumpBuilder;
  private final Set<String> retainedKeys;
  private final ReferenceQueue<Object> queue;

  RefWatcher(WatchExecutor watchExecutor, DebuggerControl debuggerControl, GcTrigger gcTrigger,
      HeapDumper heapDumper, HeapDump.Listener heapdumpListener, HeapDump.Builder heapDumpBuilder) {
    this.watchExecutor = checkNotNull(watchExecutor, "watchExecutor");
    this.debuggerControl = checkNotNull(debuggerControl, "debuggerControl");
    this.gcTrigger = checkNotNull(gcTrigger, "gcTrigger");
    this.heapDumper = checkNotNull(heapDumper, "heapDumper");
    this.heapdumpListener = checkNotNull(heapdumpListener, "heapdumpListener");
    this.heapDumpBuilder = heapDumpBuilder;
    retainedKeys = new CopyOnWriteArraySet<>();
    queue = new ReferenceQueue<>();
  }

  /**
   * Identical to {@link #watch(Object, String)} with an empty string reference name.
   *
   * @see #watch(Object, String)
   */
  public void watch(Object watchedReference) {
    watch(watchedReference, "");
  }

  /**
   * Watches the provided references and checks if it can be GCed. This method is non blocking,
   * the check is done on the {@link WatchExecutor} this {@link RefWatcher} has been constructed
   * with.
   *
   * @param referenceName An logical identifier for the watched object.
   */
  public void watch(Object watchedReference, String referenceName) {
    if (this == DISABLED) {
      return;
    }
    checkNotNull(watchedReference, "watchedReference");
    checkNotNull(referenceName, "referenceName");
    final long watchStartNanoTime = System.nanoTime();
    String key = UUID.randomUUID().toString();
    retainedKeys.add(key);
    final KeyedWeakReference reference =
        new KeyedWeakReference(watchedReference, key, referenceName, queue);

    ensureGoneAsync(watchStartNanoTime, reference);
  }

  /**
   * LeakCanary will stop watching any references that were passed to {@link #watch(Object, String)}
   * so far.
   */
  public void clearWatchedReferences() {
    retainedKeys.clear();
  }

  boolean isEmpty() {
    removeWeaklyReachableReferences();
    return retainedKeys.isEmpty();
  }

  HeapDump.Builder getHeapDumpBuilder() {
    return heapDumpBuilder;
  }

  Set<String> getRetainedKeys() {
    return new HashSet<>(retainedKeys);
  }

  private void ensureGoneAsync(final long watchStartNanoTime, final KeyedWeakReference reference) {
    watchExecutor.execute(new Retryable() {
      @Override public Retryable.Result run() {
        return ensureGone(reference, watchStartNanoTime);
      }
    });
  }

  @SuppressWarnings("ReferenceEquality") // Explicitly checking for named null.
  Retryable.Result ensureGone(final KeyedWeakReference reference, final long watchStartNanoTime) {
    long gcStartNanoTime = System.nanoTime();
    long watchDurationMs = NANOSECONDS.toMillis(gcStartNanoTime - watchStartNanoTime);
    //1.利用WeakReference的gc特性，gc发生时，若watch的对象将要被gc回收掉，那么会将其key从retainedKeys中移除。
    removeWeaklyReachableReferences();

    //2.在Android平台下，如果是debug状态，那么会进行retry，不会执行步骤3.意味着debug下不会进行内存泄漏监测。
    if (debuggerControl.isDebuggerAttached()) {
      // The debugger can create false leaks.
      return RETRY;
    }
    //3.判断一个引用是否被回收(retainedKeys是否包含该reference的key，若包含则返回false，表示没有被gc)
    // 若已经被gc掉，说明该对象在这次gc中已经被回收掉，不会发生内存泄漏。
    if (gone(reference)) {
      return DONE;
    }
    //4.建议jvm进行gc，并在100m后期望执行finalize方法。
    gcTrigger.runGc();
    //5.第二次gc后，再次进行将已被回收的对象的key从retainedKey中移除。
    removeWeaklyReachableReferences();
    //6.如果第二次gc后该对象还没有被回收掉，那么就断定为内存泄漏。
    if (!gone(reference)) {
      long startDumpHeap = System.nanoTime();
      long gcDurationMs = NANOSECONDS.toMillis(startDumpHeap - gcStartNanoTime);
      //7.调用Debug.dumpHprofData方法将堆快照写入文件(生成UUID_pending.hprof文件)，\
      // heapDumpFile!=RETRY_LATER表示成功。
      File heapDumpFile = heapDumper.dumpHeap();
      if (heapDumpFile == RETRY_LATER) {
        // Could not dump the heap.
        return RETRY;
      }
      long heapDumpDurationMs = NANOSECONDS.toMillis(System.nanoTime() - startDumpHeap);
      //8.构建堆转储快照对象HeapDump。HeapDump持有了一切内存泄漏相关的信息。
      HeapDump heapDump = heapDumpBuilder.heapDumpFile(heapDumpFile).referenceKey(reference.key)
          .referenceName(reference.name)
          .watchDurationMs(watchDurationMs)
          .gcDurationMs(gcDurationMs)
          .heapDumpDurationMs(heapDumpDurationMs)
          .build();
      //9.将HeapDump对象交付给ServiceHeapDumpListener进行分析。
      heapdumpListener.analyze(heapDump);
    }
    return DONE;
  }

  private boolean gone(KeyedWeakReference reference) {
    return !retainedKeys.contains(reference.key);
  }

  private void removeWeaklyReachableReferences() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    KeyedWeakReference ref;
    while ((ref = (KeyedWeakReference) queue.poll()) != null) {
      retainedKeys.remove(ref.key);
    }
  }
}
