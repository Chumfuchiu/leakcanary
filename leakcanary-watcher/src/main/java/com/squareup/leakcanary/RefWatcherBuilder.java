package com.squareup.leakcanary;

import java.util.Collections;
import java.util.List;

/**
 * Responsible for building {@link RefWatcher} instances. Subclasses should provide sane defaults
 * for the platform they support.
 */
public class RefWatcherBuilder<T extends RefWatcherBuilder<T>> {

  private HeapDump.Listener heapDumpListener;
  private DebuggerControl debuggerControl;
  private HeapDumper heapDumper;
  private WatchExecutor watchExecutor;
  private GcTrigger gcTrigger;
  private final HeapDump.Builder heapDumpBuilder;

  public RefWatcherBuilder() {
    heapDumpBuilder = new HeapDump.Builder();
  }

  /** @see HeapDump.Listener */
  public final T heapDumpListener(HeapDump.Listener heapDumpListener) {
    this.heapDumpListener = heapDumpListener;
    return self();
  }

  /** @see ExcludedRefs */
  public final T excludedRefs(ExcludedRefs excludedRefs) {
    heapDumpBuilder.excludedRefs(excludedRefs);
    return self();
  }

  /** @see HeapDumper */
  public final T heapDumper(HeapDumper heapDumper) {
    this.heapDumper = heapDumper;
    return self();
  }

  /** @see DebuggerControl */
  public final T debuggerControl(DebuggerControl debuggerControl) {
    this.debuggerControl = debuggerControl;
    return self();
  }

  /** @see WatchExecutor */
  public final T watchExecutor(WatchExecutor watchExecutor) {
    this.watchExecutor = watchExecutor;
    return self();
  }

  /** @see GcTrigger */
  public final T gcTrigger(GcTrigger gcTrigger) {
    this.gcTrigger = gcTrigger;
    return self();
  }

  /** @see Reachability.Inspector */
  public final T stethoscopeClasses(
      List<Class<? extends Reachability.Inspector>> stethoscopeClasses) {
    heapDumpBuilder.reachabilityInspectorClasses(stethoscopeClasses);
    return self();
  }

  /**
   * Whether LeakCanary should compute the retained heap size when a leak is detected. False by
   * default, because computing the retained heap size takes a long time.
   */
  public final T computeRetainedHeapSize(boolean computeRetainedHeapSize) {
    heapDumpBuilder.computeRetainedHeapSize(computeRetainedHeapSize);
    return self();
  }

  /** Creates a {@link RefWatcher}.
   * RefWatcher由六个部分组成：watchExecutor,debuggerControl,gcTrigger,heapDumper,heapDumpListener,
   * 以及heapDumpBuilder。
   *
   * watchExecutor：线程控制器，在 onDestroy() 之后并且主线程空闲时执行内存泄漏检测。
   * debuggerControl：判断是否处于调试模式，调试模式中不会进行内存泄漏检测。
   * gcTrigger：用于 GC，watchExecutor 首次检测到可能的内存泄漏，会主动进行 GC，GC 之后会再检测一次，
   *            仍然泄漏的判定为内存泄漏，最后根据heapDump信息生成相应的泄漏引用链。
   * heapDumper：堆信息转储者，dump 内存泄漏处的 heap 信息到 hprof 文件。
   * heapDumpListener：转储堆信息到hprof文件，并在解析完 hprof 文件后进行回调，最后通知
   *                  DisplayLeakService 弹出泄漏提醒。
   * heapDumpBuilder.excludedRefs：记录可以被忽略的泄漏路径。
   * heapDumpBuilder.reachabilityInspectorClasses：用于要进行可达性检测的类列表。
   *
   * */
  public final RefWatcher build() {
    if (isDisabled()) {
      return RefWatcher.DISABLED;
    }

    if (heapDumpBuilder.excludedRefs == null) {
      heapDumpBuilder.excludedRefs(defaultExcludedRefs());
    }

    HeapDump.Listener heapDumpListener = this.heapDumpListener;
    if (heapDumpListener == null) {
      heapDumpListener = defaultHeapDumpListener();
    }

    DebuggerControl debuggerControl = this.debuggerControl;
    if (debuggerControl == null) {
      debuggerControl = defaultDebuggerControl();
    }

    HeapDumper heapDumper = this.heapDumper;
    if (heapDumper == null) {
      heapDumper = defaultHeapDumper();
    }

    WatchExecutor watchExecutor = this.watchExecutor;
    if (watchExecutor == null) {
      watchExecutor = defaultWatchExecutor();
    }

    GcTrigger gcTrigger = this.gcTrigger;
    if (gcTrigger == null) {
      gcTrigger = defaultGcTrigger();
    }

    if (heapDumpBuilder.reachabilityInspectorClasses == null) {
      heapDumpBuilder.reachabilityInspectorClasses(defaultReachabilityInspectorClasses());
    }

    return new RefWatcher(watchExecutor, debuggerControl, gcTrigger, heapDumper, heapDumpListener,
        heapDumpBuilder);
  }

  protected boolean isDisabled() {
    return false;
  }

  protected GcTrigger defaultGcTrigger() {
    return GcTrigger.DEFAULT;
  }

  protected DebuggerControl defaultDebuggerControl() {
    return DebuggerControl.NONE;
  }

  protected ExcludedRefs defaultExcludedRefs() {
    return ExcludedRefs.builder().build();
  }

  protected HeapDumper defaultHeapDumper() {
    return HeapDumper.NONE;
  }

  protected HeapDump.Listener defaultHeapDumpListener() {
    return HeapDump.Listener.NONE;
  }

  protected WatchExecutor defaultWatchExecutor() {
    return WatchExecutor.NONE;
  }

  protected List<Class<? extends Reachability.Inspector>> defaultReachabilityInspectorClasses() {
    return Collections.emptyList();
  }

  @SuppressWarnings("unchecked")
  protected final T self() {
    return (T) this;
  }
}
