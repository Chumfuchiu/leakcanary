package com.squareup.leakcanary;

import android.content.Context;
import android.support.annotation.NonNull;
import com.squareup.leakcanary.internal.DisplayLeakActivity;
import com.squareup.leakcanary.internal.FragmentRefWatcher;
import com.squareup.leakcanary.internal.LeakCanaryInternals;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.squareup.leakcanary.RefWatcher.DISABLED;
import static java.util.concurrent.TimeUnit.SECONDS;

/** A {@link RefWatcherBuilder} with appropriate Android defaults. */
public final class AndroidRefWatcherBuilder extends RefWatcherBuilder<AndroidRefWatcherBuilder> {

  private static final long DEFAULT_WATCH_DELAY_MILLIS = SECONDS.toMillis(5);

  private final Context context;
  private boolean watchActivities = true;
  private boolean watchFragments = true;

  AndroidRefWatcherBuilder(@NonNull Context context) {
    this.context = context.getApplicationContext();
  }

  /**
   * Sets a custom {@link AbstractAnalysisResultService} to listen to analysis results. This
   * overrides any call to {@link #heapDumpListener(HeapDump.Listener)}.
   */
  public @NonNull AndroidRefWatcherBuilder listenerServiceClass(
      @NonNull Class<? extends AbstractAnalysisResultService> listenerServiceClass) {
    return heapDumpListener(new ServiceHeapDumpListener(context, listenerServiceClass));
  }

  /**
   * Sets a custom delay for how long the {@link RefWatcher} should wait until it checks if a
   * tracked object has been garbage collected. This overrides any call to {@link
   * #watchExecutor(WatchExecutor)}.
   */
  public @NonNull AndroidRefWatcherBuilder watchDelay(long delay, @NonNull TimeUnit unit) {
    return watchExecutor(new AndroidWatchExecutor(unit.toMillis(delay)));
  }

  /**
   * Whether we should automatically watch activities when calling {@link #buildAndInstall()}.
   * Default is true.
   */
  public @NonNull AndroidRefWatcherBuilder watchActivities(boolean watchActivities) {
    this.watchActivities = watchActivities;
    return this;
  }

  /**
   * Whether we should automatically watch fragments when calling {@link #buildAndInstall()}.
   * Default is true. When true, LeakCanary watches native fragments on Android O+ and support
   * fragments if the leakcanary-support-fragment dependency is in the classpath.
   */
  public @NonNull AndroidRefWatcherBuilder watchFragments(boolean watchFragments) {
    this.watchFragments = watchFragments;
    return this;
  }

  /**
   * Sets the maximum number of heap dumps stored. This overrides any call to
   * {@link LeakCanary#setLeakDirectoryProvider(LeakDirectoryProvider)}
   *
   * @throws IllegalArgumentException if maxStoredHeapDumps < 1.
   */
  public @NonNull AndroidRefWatcherBuilder maxStoredHeapDumps(int maxStoredHeapDumps) {
    LeakDirectoryProvider leakDirectoryProvider =
        new DefaultLeakDirectoryProvider(context, maxStoredHeapDumps);
    LeakCanary.setLeakDirectoryProvider(leakDirectoryProvider);
    return self();
  }

  /**
   * Creates a {@link RefWatcher} instance and makes it available through {@link
   * LeakCanary#installedRefWatcher()}.
   *
   * Also starts watching activity references if {@link #watchActivities(boolean)} was set to true.
   *
   * @throws UnsupportedOperationException if called more than once per Android process.
   */
  public @NonNull RefWatcher buildAndInstall() {
    //1.判断LeakCanaryInternals.installedRefWatcher是否不为null，是则抛出异常，installedRefWatcher
    // 仅仅在buildAndInstall方法中被赋值，这就意味着buildAndInstall仅能调用一次，installedRefWatcher
    // 是全局唯一的。
    if (LeakCanaryInternals.installedRefWatcher != null) {
      throw new UnsupportedOperationException("buildAndInstall() should only be called once.");
    }
    //2.构建一个RefWatcher对象。
    RefWatcher refWatcher = build();

    if (refWatcher != DISABLED) {
      //3.利用PM.setComponentEnabledSetting &&AsyncTask 进行阻塞式地显示DisplayLeakActivity
      LeakCanaryInternals.setEnabledAsync(context, DisplayLeakActivity.class, true);
      //4.注册一个生命周期回调监听，在onActivityDestroyed方法里调用refWatcher.watch(activity)方法
      if (watchActivities) {
        ActivityRefWatcher.install(context, refWatcher);
      }
      //5.注册一个生命周期回调监听，在onActivityCreated方法里调用refWatcher.watchFragments(activity)
      // 方法。watchFragments(activity)方法内部对Android版本做了区别，在Android O以上支持fragment的全
      // 面监测，而 O 以下仅仅支持FragmentActivity的监测，监测的方式是通过SupportFragmentManager.
      // registerFragmentLifecycleCallbacks方法注册一个fragment的生命周期回调，在onFragmentViewDestroyed
      // 和onFragmentDestroyed分别调用refWatcher.watch(view);和refWatcher.watch(fragment);
      if (watchFragments) {
        FragmentRefWatcher.Helper.install(context, refWatcher);
      }
    }
    LeakCanaryInternals.installedRefWatcher = refWatcher;
    return refWatcher;
  }

  @Override protected boolean isDisabled() {
    return LeakCanary.isInAnalyzerProcess(context);
  }

  @Override protected @NonNull HeapDumper defaultHeapDumper() {
    LeakDirectoryProvider leakDirectoryProvider =
        LeakCanaryInternals.getLeakDirectoryProvider(context);
    return new AndroidHeapDumper(context, leakDirectoryProvider);
  }

  @Override protected @NonNull DebuggerControl defaultDebuggerControl() {
    return new AndroidDebuggerControl();
  }

  @Override protected @NonNull HeapDump.Listener defaultHeapDumpListener() {
    return new ServiceHeapDumpListener(context, DisplayLeakService.class);
  }

  @Override protected @NonNull ExcludedRefs defaultExcludedRefs() {
    return AndroidExcludedRefs.createAppDefaults().build();
  }

  @Override protected @NonNull WatchExecutor defaultWatchExecutor() {
    return new AndroidWatchExecutor(DEFAULT_WATCH_DELAY_MILLIS);
  }

  @Override protected @NonNull
  List<Class<? extends Reachability.Inspector>> defaultReachabilityInspectorClasses() {
    return AndroidReachabilityInspectors.defaultAndroidInspectors();
  }
}
